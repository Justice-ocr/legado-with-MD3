package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookContentProcess
import kotlinx.coroutines.flow.Flow

@Dao
interface BookContentProcessDao {

    @Query(
        """
        select * from book_content_processes
        where bookUrl = :bookUrl
          and (:chapterIndex is null or chapterIndex is null or chapterIndex = :chapterIndex)
          and status != ${BookContentProcess.STATUS_DELETED}
        order by sortOrder, createdAt
        """
    )
    suspend fun getForChapter(bookUrl: String, chapterIndex: Int?): List<BookContentProcess>

    @Query(
        """
        select * from book_content_processes
        where bookUrl = :bookUrl
          and (:chapterIndex is null or chapterIndex is null or chapterIndex = :chapterIndex)
          and status != ${BookContentProcess.STATUS_DELETED}
        order by sortOrder, createdAt
        """
    )
    fun getForChapterSync(bookUrl: String, chapterIndex: Int?): List<BookContentProcess>

    @Query(
        """
        select * from book_content_processes
        where bookUrl = :bookUrl
          and (:chapterIndex is null or chapterIndex is null or chapterIndex = :chapterIndex)
          and status != ${BookContentProcess.STATUS_DELETED}
        order by sortOrder, createdAt
        """
    )
    fun flowForChapter(bookUrl: String, chapterIndex: Int?): Flow<List<BookContentProcess>>

    @Query("select coalesce(max(sortOrder), 0) from book_content_processes where bookUrl = :bookUrl")
    suspend fun maxOrder(bookUrl: String): Int

    @Query("select * from book_content_processes where id = :id limit 1")
    suspend fun getById(id: String): BookContentProcess?

    @Query(
        """
        select * from book_content_processes
        where (revisionGroupId = :groupId or id = :groupId)
          and status != ${BookContentProcess.STATUS_DELETED}
        order by revisionNumber desc, createdAt desc
        """
    )
    suspend fun getRevisionHistory(groupId: String): List<BookContentProcess>

    @Query(
        """
        select coalesce(max(revisionNumber), 0) from book_content_processes
        where revisionGroupId = :groupId or id = :groupId
        """
    )
    suspend fun maxRevisionNumber(groupId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(process: BookContentProcess)

    @Query(
        """
        update book_content_processes
        set enabled = 0,
            status = ${BookContentProcess.STATUS_DISABLED},
            updatedAt = :updatedAt
        where (revisionGroupId = :groupId or id = :groupId)
          and status = ${BookContentProcess.STATUS_ACTIVE}
        """
    )
    suspend fun disableActiveRevision(groupId: String, updatedAt: Long)

    @Transaction
    suspend fun replaceActiveRevision(
        groupId: String,
        process: BookContentProcess,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        disableActiveRevision(groupId, updatedAt)
        upsert(process)
    }

    @Query("update book_content_processes set enabled = :enabled, updatedAt = :updatedAt where id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("update book_content_processes set status = ${BookContentProcess.STATUS_DELETED}, updatedAt = :updatedAt where id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long = System.currentTimeMillis())
}
