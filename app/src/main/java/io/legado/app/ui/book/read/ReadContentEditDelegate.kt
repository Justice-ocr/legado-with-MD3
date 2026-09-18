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
                errorMessage = null,
            )
        }
    }

    fun setText(text: String) {
        _uiState.update { it.copy(text = text, errorMessage = null) }
    }

    fun setSaveToSource(value: Boolean) {
        _uiState.update { it.copy(saveToSource = value) }
    }

    fun load() {
        _uiState.update { it.copy(loading = true, text = "", errorMessage = null) }
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
        val stateSnapshot = _uiState.value
        if (stateSnapshot.loading) return
        val original = stateSnapshot.originalText
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        Coroutine.async(scope, Dispatchers.IO) {
            val book = requireNotNull(ReadBook.book) { "Book is unavailable" }
            val chapter = requireNotNull(
                host.findChapter(book.bookUrl, ReadBook.durChapterIndex)
            ) { "Chapter is unavailable" }
            if (saveToSource) {
                BookHelp.saveText(book, chapter, content, true)
            } else {
                buildEditSpan(original, content)?.let { edit ->
                    saveBookContentProcessUseCase.saveReplacement(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapter.index,
                        chapterPosition = edit.start,
                        selectedText = edit.selected,
                        contextBefore = original.substring(0, edit.start).takeLast(64),
                        contextAfter = original.substring(edit.endExclusive).take(64),
                        replacementText = edit.replacement,
                        kind = io.legado.app.data.entities.BookContentProcess.KIND_MANUAL_EDIT,
                        source = io.legado.app.data.entities.BookContentProcess.SOURCE_USER_EDIT,
                    ).getOrThrow()
                }
            }
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
            host.setActiveSheet(null)
            onSheetDismissed()
        }.onError { error ->
            _uiState.update {
                it.copy(
                    loading = false,
                    errorMessage = error.localizedMessage ?: "Save failed",
                )
            }
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

    private fun buildEditSpan(original: String, content: String): EditSpan? {
        if (original == content) return null
        val prefix = commonPrefixLength(original, content)
        val suffix = commonSuffixLength(original, content, prefix)
        val selectedEnd = original.length - suffix
        val replacementEnd = content.length - suffix
        if (selectedEnd > prefix) {
            return EditSpan(
                start = prefix,
                endExclusive = selectedEnd,
                selected = original.substring(prefix, selectedEnd),
                replacement = content.substring(prefix, replacementEnd),
            )
        }

        require(original.isNotEmpty()) { "Empty chapter content cannot be edited without writing to source" }
        val inserted = content.substring(prefix, replacementEnd)
        val left = (prefix - 1 downTo 0).firstOrNull { !original[it].isProcessWhitespace() }
        val right = (prefix until original.length).firstOrNull { !original[it].isProcessWhitespace() }
        return when {
            left != null && right != null -> {
                val selected = original.substring(left, right + 1)
                val insertionOffset = prefix - left
                EditSpan(
                    start = left,
                    endExclusive = right + 1,
                    selected = selected,
                    replacement = selected.substring(0, insertionOffset) + inserted +
                        selected.substring(insertionOffset),
                )
            }

            right != null -> EditSpan(
                start = right,
                endExclusive = right + 1,
                selected = original.substring(right, right + 1),
                replacement = inserted + original[right],
            )

            left != null -> EditSpan(
                start = left,
                endExclusive = left + 1,
                selected = original.substring(left, left + 1),
                replacement = original[left] + inserted,
            )

            else -> error("Chapter content contains no editable text anchor")
        }
    }

    private fun Char.isProcessWhitespace(): Boolean = isWhitespace() || this == '　'

    private data class EditSpan(
        val start: Int,
        val endExclusive: Int,
        val selected: String,
        val replacement: String,
    )
}
