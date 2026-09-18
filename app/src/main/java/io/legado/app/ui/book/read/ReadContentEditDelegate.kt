package io.legado.app.ui.book.read

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.domain.usecase.SaveBookContentProcessUseCase
import io.legado.app.feature.reader.core.navigation.ReaderPageContext
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 阅读页正文编辑域：打开编辑弹层、载入当前章正文、保存、还原。
 *
 * 自持 [ContentEditUiState]；章节读取走 [Host]（理由同 [ReadAiDelegate]——
 * 不让 DAO 直连从 `legacyDaoInjectionBaseline` 洗进宽松的 `legacyUiDaoAccessBaseline`）。
 *
 * `execute {}` 是 `BaseViewModel` 的成员，这里换成它的实现体
 * `Coroutine.async(scope, Dispatchers.IO)`，默认参数一致，语义不变。
 */
class ReadContentEditDelegate(
    private val scope: CoroutineScope,
    private val host: Host,
    private val readSettingsRepository: ReadSettingsRepository,
    private val saveBookContentProcessUseCase: SaveBookContentProcessUseCase,
) {

    interface Host {
        val currentCanvasPage: ReaderPageContext?

        fun setActiveSheet(sheet: ReadBookSheet?)

        suspend fun findChapter(bookUrl: String, chapterIndex: Int): BookChapter?
    }

    private val _uiState = MutableStateFlow(ContentEditUiState())
    val uiState = _uiState.asStateFlow()

    private var pendingCursorOffset: Int? = null
    private var pendingAnchor: String? = null

    fun open() {
        pendingCursorOffset = currentOffset()
        pendingAnchor = currentAnchor()
        host.setActiveSheet(ReadBookSheet.ContentEdit)
    }

    /** 关闭弹层时清空正文缓冲，避免下次开弹层闪上一章内容。 */
    fun onSheetDismissed() {
        _uiState.update {
            it.copy(
                text = "",
                originalText = "",
                title = "",
                cursorOffset = 0,
                loading = false,
                saveToSource = false,
            )
        }
    }

    fun setText(text: String) {
        _uiState.update { it.copy(text = text) }
    }

    fun setSaveToSource(value: Boolean) {
        _uiState.update { it.copy(saveToSource = value) }
    }

    fun load() {
        _uiState.update { it.copy(loading = true, text = "") }
        Coroutine.async(scope, Dispatchers.IO) {
            val book = ReadBook.book ?: return@async
            val chapter = host.findChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            val title = chapter.getDisplayTitle(
                chineseConverterType = readSettingsRepository.currentSettings.chineseConverterType
            )
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val rawContent = BookHelp.getContent(book, chapter) ?: return@async
            val text = contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
                .toString()
            val cursorOffset = resolveCursorOffset(text)
            _uiState.update {
                it.copy(
                    text = text,
                    originalText = text,
                    title = title,
                    cursorOffset = cursorOffset,
                    isLocalTxt = book.isLocalTxt,
                )
            }
        }.onFinally {
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun save(content: String, saveToSource: Boolean) {
        Coroutine.async(scope, Dispatchers.IO) {
            val book = ReadBook.book ?: return@async
            val chapter = host.findChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            if (saveToSource) {
                BookHelp.saveText(book, chapter, content, true)
            } else {
                val original = _uiState.value.originalText
                val prefix = commonPrefixLength(original, content)
                val suffix = commonSuffixLength(original, content, prefix)
                val selected = original.substring(prefix, original.length - suffix)
                val replacement = content.substring(prefix, content.length - suffix)
                if (selected.isNotBlank() && selected != replacement) {
                    saveBookContentProcessUseCase.saveReplacement(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapter.index,
                        chapterPosition = prefix,
                        selectedText = selected,
                        contextBefore = original.substring(0, prefix).takeLast(64),
                        contextAfter = original.substring(original.length - suffix).take(64),
                        replacementText = replacement,
                        kind = io.legado.app.data.entities.BookContentProcess.KIND_MANUAL_EDIT,
                        source = io.legado.app.data.entities.BookContentProcess.SOURCE_USER_EDIT,
                    ).getOrThrow()
                }
            }
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    fun reset() {
        _uiState.update { it.copy(loading = true) }
        Coroutine.async(scope, Dispatchers.IO) {
            val book = ReadBook.book ?: return@async
            val chapter = host.findChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            BookHelp.delContent(book, chapter)
            if (!book.isLocal) {
                ReadBook.bookSource?.let { bookSource ->
                    WebBook.getContentAwait(bookSource, book, chapter)
                }
            }
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val rawContent = BookHelp.getContent(book, chapter)
            val text = if (rawContent != null) {
                contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
                    .toString()
            } else {
                ""
            }
            val cursorOffset = resolveCursorOffset(text)
            _uiState.update {
                it.copy(
                    text = text,
                    originalText = text,
                    cursorOffset = cursorOffset,
                    loading = false,
                )
            }
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }.onError {
            _uiState.update { it.copy(loading = false) }
        }
    }

    // --- 光标定位：优先用打开弹层那一刻的可见首行，其次用锚点文本 ---

    private fun currentPage(): ReaderPageContext? = host.currentCanvasPage
        ?.takeIf { it.chapterIndex == ReadBook.durChapterIndex }

    private fun currentOffset(): Int = currentPage()?.contentStartPosition ?: ReadBook.durChapterPos

    private fun currentAnchor(): String? = currentPage()?.anchorText

    private fun resolveCursorOffset(text: String): Int {
        if (text.isEmpty()) {
            clearPendingLocation()
            return 0
        }
        val preferred = (pendingCursorOffset ?: currentOffset())
            .coerceIn(0, text.length)
        val anchor = pendingAnchor ?: currentAnchor()
        clearPendingLocation()
        if (anchor.isNullOrBlank()) {
            return preferred
        }
        val startIndex = (preferred - 200).coerceAtLeast(0)
        val nearIndex = text.indexOf(anchor, startIndex = startIndex)
        if (nearIndex >= 0) {
            return nearIndex
        }
        val anyIndex = text.indexOf(anchor)
        return if (anyIndex >= 0) anyIndex else preferred
    }

    private fun clearPendingLocation() {
        pendingCursorOffset = null
        pendingAnchor = null
    }

    private fun commonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }

    private fun commonSuffixLength(left: String, right: String, prefix: Int): Int {
        var length = 0
        val max = minOf(left.length, right.length) - prefix
        while (length < max && left[left.length - length - 1] == right[right.length - length - 1]) {
            length++
        }
        return length
    }
}
