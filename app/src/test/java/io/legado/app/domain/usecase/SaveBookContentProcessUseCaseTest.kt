package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookContentProcess
import io.legado.app.domain.gateway.BookContentProcessGateway
import io.legado.app.domain.model.BookContentProcessEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveBookContentProcessUseCaseTest {

    @Test
    fun `saved replacement is returned on chapter reload and changes rendered text`() = runTest {
        val gateway = InMemoryBookContentProcessGateway()
        val useCase = SaveBookContentProcessUseCase(gateway)

        val saved = useCase.saveReplacement(
            bookUrl = "book",
            chapterIndex = 3,
            chapterPosition = 2,
            selectedText = "原来的文字",
            contextBefore = "开头",
            contextAfter = "结尾",
            replacementText = "修改后的文字",
            kind = BookContentProcess.KIND_AI_REWRITE,
        ).getOrThrow()

        val reloaded = gateway.getForChapter("book", 3)
        val rendered = BookContentProcessEngine.apply(
            content = "开头原来的文字结尾",
            processes = reloaded,
        )

        assertEquals(listOf(saved), reloaded)
        assertEquals("开头修改后的文字结尾", rendered.text)
        assertTrue(rendered.effectiveProcesses.contains(saved))
    }

    @Test
    fun `rolling back to original text creates a new active revision`() = runTest {
        val gateway = InMemoryBookContentProcessGateway()
        val useCase = SaveBookContentProcessUseCase(gateway)
        val first = useCase.saveReplacement(
            bookUrl = "book",
            chapterIndex = 3,
            chapterPosition = 2,
            selectedText = "原文",
            contextBefore = "开头",
            contextAfter = "结尾",
            replacementText = "AI 改写",
            kind = BookContentProcess.KIND_AI_REWRITE,
        ).getOrThrow()

        val rollback = useCase.saveRevision(
            processId = first.id,
            replacementText = first.originalText.orEmpty(),
            source = BookContentProcess.SOURCE_ROLLBACK,
        ).getOrThrow()
        val history = gateway.getRevisionHistory(first.revisionGroupId ?: first.id)
        val rendered = BookContentProcessEngine.apply(
            content = "开头原文结尾",
            processes = gateway.getForChapter("book", 3),
        )

        assertEquals(2, history.size)
        assertEquals(BookContentProcess.SOURCE_ROLLBACK, rollback.source)
        assertTrue(rollback.enabled)
        assertEquals(BookContentProcess.STATUS_ACTIVE, rollback.status)
        assertEquals("开头原文结尾", rendered.text)
    }

    private class InMemoryBookContentProcessGateway : BookContentProcessGateway {
        private val records = linkedMapOf<String, BookContentProcess>()

        override suspend fun getForChapter(
            bookUrl: String,
            chapterIndex: Int?,
        ): List<BookContentProcess> = records.values.filter {
            it.bookUrl == bookUrl &&
                (chapterIndex == null || it.chapterIndex == null || it.chapterIndex == chapterIndex) &&
                it.status != BookContentProcess.STATUS_DELETED
        }.sortedWith(compareBy(BookContentProcess::sortOrder, BookContentProcess::createdAt))

        override fun flowForChapter(
            bookUrl: String,
            chapterIndex: Int?,
        ): Flow<List<BookContentProcess>> = flowOf(emptyList())

        override suspend fun nextOrder(bookUrl: String): Int =
            records.values.filter { it.bookUrl == bookUrl }.maxOfOrNull { it.sortOrder }?.plus(1) ?: 1

        override suspend fun getById(id: String): BookContentProcess? = records[id]

        override suspend fun getRevisionHistory(groupId: String): List<BookContentProcess> =
            records.values.filter { it.revisionGroupId == groupId || it.id == groupId }

        override suspend fun nextRevisionNumber(groupId: String): Int =
            getRevisionHistory(groupId).maxOfOrNull { it.revisionNumber }?.plus(1) ?: 1

        override suspend fun upsert(process: BookContentProcess) {
            records[process.id] = process
        }

        override suspend fun replaceActiveRevision(groupId: String, process: BookContentProcess) {
            records.replaceAll { _, record ->
                if ((record.revisionGroupId == groupId || record.id == groupId) &&
                    record.status == BookContentProcess.STATUS_ACTIVE
                ) {
                    record.copy(enabled = false, status = BookContentProcess.STATUS_DISABLED)
                } else {
                    record
                }
            }
            records[process.id] = process
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            records[id] = records.getValue(id).copy(enabled = enabled)
        }

        override suspend fun delete(id: String) {
            records[id] = records.getValue(id).copy(status = BookContentProcess.STATUS_DELETED)
        }
    }
}
