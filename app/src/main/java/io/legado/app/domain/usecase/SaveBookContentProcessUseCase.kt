package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookContentProcess
import io.legado.app.domain.gateway.BookContentProcessGateway
import io.legado.app.domain.model.BookContentProcessEngine
import io.legado.app.domain.model.TextProcessAction
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class SaveBookContentProcessUseCase(
    private val bookContentProcessGateway: BookContentProcessGateway,
) {

    suspend fun saveReplacement(
        bookUrl: String,
        chapterIndex: Int,
        chapterPosition: Int,
        selectedText: String,
        contextBefore: String,
        contextAfter: String,
        replacementText: String,
        kind: String = BookContentProcess.KIND_AI_CLEAN,
        source: String = BookContentProcess.SOURCE_AI,
        aiArtifactId: String? = null,
        sourceContentHash: String? = null,
    ): Result<BookContentProcess> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedSelectedText = BookContentProcessEngine.normalizeProcessText(selectedText)
            require(normalizedSelectedText.isNotBlank()) { "Selected text is empty" }
            val normalizedReplacement = BookContentProcessEngine.normalizeProcessText(replacementText)
            require(normalizedSelectedText != normalizedReplacement) { "Replacement did not change text" }

            val anchor = TextProcessAnchor(
                chapterIndex = chapterIndex,
                chapterPosition = chapterPosition,
                selectedText = normalizedSelectedText,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                normalizedTextHash = MD5Utils.md5Encode(normalizedSelectedText),
            )
            val action = if (normalizedReplacement.isEmpty()) {
                TextProcessAction.delete()
            } else {
                TextProcessAction.replace(normalizedReplacement)
            }
            val now = System.currentTimeMillis()
            val processId = Uuid.random().toString()
            val process = BookContentProcess(
                id = processId,
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                kind = kind,
                stage = BookContentProcess.STAGE_CONTENT,
                target = BookContentProcess.TARGET_SELECTION,
                anchorJson = GSON.toJson(anchor),
                actionJson = GSON.toJson(action),
                source = source,
                aiArtifactId = aiArtifactId,
                revisionGroupId = processId,
                revisionNumber = 1,
                originalText = normalizedSelectedText,
                revisedText = normalizedReplacement,
                sourceContentHash = sourceContentHash,
                sortOrder = bookContentProcessGateway.nextOrder(bookUrl),
                createdAt = now,
                updatedAt = now,
            )
            bookContentProcessGateway.upsert(process)
            process
        }
    }

    suspend fun saveRevision(
        processId: String,
        replacementText: String,
        source: String = BookContentProcess.SOURCE_USER_EDIT,
    ): Result<BookContentProcess> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = requireNotNull(bookContentProcessGateway.getById(processId)) {
                "Content process not found"
            }
            val groupId = selected.revisionGroupId ?: selected.id
            val history = bookContentProcessGateway.getRevisionHistory(groupId)
            val active = history.firstOrNull {
                it.enabled && it.status == BookContentProcess.STATUS_ACTIVE
            } ?: selected
            val normalizedReplacement = BookContentProcessEngine.normalizeProcessText(replacementText)
            val activeAction = GSON.fromJsonObject<TextProcessAction>(active.actionJson).getOrThrow()
            val activeReplacement = when (activeAction.type) {
                TextProcessAction.TYPE_REPLACE -> activeAction.replacement.orEmpty()
                TextProcessAction.TYPE_DELETE -> ""
                else -> activeAction.text.orEmpty()
            }.let(BookContentProcessEngine::normalizeProcessText)
            require(activeReplacement != normalizedReplacement) { "Replacement did not change text" }

            val action = if (normalizedReplacement.isEmpty()) {
                TextProcessAction.delete()
            } else {
                TextProcessAction.replace(normalizedReplacement)
            }
            val now = System.currentTimeMillis()
            val revision = active.copy(
                id = Uuid.random().toString(),
                actionJson = GSON.toJson(action),
                source = source,
                revisionGroupId = groupId,
                parentProcessId = active.id,
                revisionNumber = bookContentProcessGateway.nextRevisionNumber(groupId),
                originalText = active.originalText
                    ?: GSON.fromJsonObject<TextProcessAnchor>(active.anchorJson).getOrThrow().selectedText,
                revisedText = normalizedReplacement,
                enabled = true,
                status = BookContentProcess.STATUS_ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
            bookContentProcessGateway.replaceActiveRevision(groupId, revision)
            revision
        }
    }
}
