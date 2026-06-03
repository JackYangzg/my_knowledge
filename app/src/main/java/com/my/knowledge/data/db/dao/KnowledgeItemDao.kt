package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeItemDao {
    @Query("""
        SELECT * FROM knowledge_item 
        WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL
        AND sourceType NOT LIKE 'wiki_%'
        ORDER BY updatedAt DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun observePagedByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL AND sourceType NOT LIKE 'wiki_%'")
    fun observeCountByKb(kbId: String): Flow<Int>

    // Unfiled items
    @Query("""
        SELECT ki.* FROM knowledge_item ki
        INNER JOIN knowledge_base kb ON ki.knowledgeBaseId = kb.id
        WHERE kb.type = 'unfiled' AND ki.deletedAt IS NULL
        ORDER BY ki.createdAt DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT COUNT(*) FROM knowledge_item ki
        INNER JOIN knowledge_base kb ON ki.knowledgeBaseId = kb.id
        WHERE kb.type = 'unfiled' AND ki.deletedAt IS NULL
    """)
    fun observeUnfiledItemCount(): Flow<Int>

    // Single item operations
    @Query("SELECT * FROM knowledge_item WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE sourceId = :sourceId AND deletedAt IS NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun getBySourceId(sourceId: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE sourceId = :sourceId AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getAllBySourceId(sourceId: String): List<KnowledgeItemEntity>

    /**
     * Find the live knowledge item linked to a given inspiration note. Used
     * by `NoteEditorViewModel.saveToKnowledgeBase` so that re-saving the
     * same note updates the same item rather than creating a fresh one.
     */
    @Query("SELECT * FROM knowledge_item WHERE rawNoteId = :noteId AND deletedAt IS NULL ORDER BY createdAt ASC LIMIT 1")
    suspend fun getByRawNoteId(noteId: String): KnowledgeItemEntity?

    @Query("""
        SELECT * FROM knowledge_item
        WHERE sourceId = :sourceId AND deletedAt IS NULL
        AND sourceType LIKE 'wiki_%'
        ORDER BY
            CASE sourceType
                WHEN 'wiki_source' THEN 0
                WHEN 'wiki_entity' THEN 1
                WHEN 'wiki_concept' THEN 2
                WHEN 'wiki_overview' THEN 3
                WHEN 'wiki_index' THEN 4
                WHEN 'wiki_log' THEN 5
                ELSE 9
            END,
            title ASC
    """)
    fun observeProcessedBySource(sourceId: String): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_item WHERE id = :id")
    suspend fun getByIdIncludeDeleted(id: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE contentHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun getByContentHash(hash: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE knowledgeBaseId = :kbId AND contentHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun getByKbAndContentHash(kbId: String, hash: String): KnowledgeItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: KnowledgeItemEntity)

    @Update
    suspend fun update(item: KnowledgeItemEntity)

    @Query("UPDATE knowledge_item SET deletedAt = :deletedAt, status = 'deleted' WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE knowledge_item SET deletedAt = :deletedAt, status = 'deleted' WHERE sourceId = :sourceId AND deletedAt IS NULL")
    suspend fun softDeleteBySource(sourceId: String, deletedAt: Long)

    @Query("DELETE FROM knowledge_item WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE knowledge_item SET deletedAt = NULL, status = 'unfiled', updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: String, updatedAt: Long)

    @Query("SELECT * FROM knowledge_item WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeletedItems(): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_item WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC LIMIT :limit OFFSET :offset")
    fun observeDeletedItemsPaged(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE deletedAt IS NOT NULL")
    fun observeDeletedItemCount(): Flow<Int>

    @Query("UPDATE knowledge_item SET deletedAt = NULL, status = 'unfiled', updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun restoreItems(ids: List<String>, updatedAt: Long)

    @Query("DELETE FROM knowledge_item WHERE id IN (:ids)")
    suspend fun hardDeleteItems(ids: List<String>)

    @Query("UPDATE knowledge_item SET knowledgeBaseId = :targetKbId, updatedAt = :updatedAt WHERE id = :itemId")
    suspend fun moveToBase(itemId: String, targetKbId: String, updatedAt: Long)

    @Query("UPDATE knowledge_item SET status = :status, updatedAt = :updatedAt WHERE sourceId = :sourceId AND deletedAt IS NULL")
    suspend fun updateStatusBySourceId(sourceId: String, status: String, updatedAt: Long)

    @Query("UPDATE knowledge_item SET status = 'failed', excerpt = :errorMessage, updatedAt = :updatedAt WHERE sourceId = :sourceId AND deletedAt IS NULL")
    suspend fun updateFailureBySourceId(sourceId: String, errorMessage: String?, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE status IN (:statuses) AND deletedAt IS NULL")
    fun observeCountByStatuses(statuses: List<String>): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE deletedAt IS NULL AND sourceType NOT LIKE 'wiki_%'")
    fun observeActiveItemCount(): Flow<Int>

    // Batch item count update
    @Query("UPDATE knowledge_base SET itemCount = (SELECT COUNT(*) FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL AND sourceType NOT LIKE 'wiki_%') WHERE id = :kbId")
    suspend fun updateItemCount(kbId: String)

    // Status queries
    @Query("SELECT * FROM knowledge_item WHERE status = :status AND deletedAt IS NULL")
    fun observeByStatus(status: String): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL")
    suspend fun getAllByKb(kbId: String): List<KnowledgeItemEntity>

    /**
     * Same as [getAllByKb] but restricted to wiki pages. The previous
     * `IngestOrchestrator.buildCurrentIndex` and similar code paths
     * called `getAllByKb` then filtered `sourceType LIKE 'wiki_%'` in
     * Kotlin — fine for a 20-page library, but every ingest step
     * (`requestAiRawOutput` / `requestAiAnalysis` / `rebuildGraphForBase`)
     * paid the cost of loading every raw note too. Once a KB crossed
     * a few thousand items the `AI 联网分析 → 生成` stage spent most
     * of its time in this query, masquerading as "generation 卡住".
     * Pushing the filter down to SQL keeps each ingest step bounded
     * by the number of wiki pages, not the total knowledge-base size.
     */
    @Query("SELECT * FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL AND sourceType LIKE 'wiki_%' ORDER BY updatedAt DESC")
    suspend fun getAllWikiByKb(kbId: String): List<KnowledgeItemEntity>

    @Query("SELECT * FROM knowledge_item WHERE knowledgeBaseId = :kbId AND sourceType = :sourceType AND title = :title AND deletedAt IS NULL LIMIT 1")
    suspend fun getByKbSourceTypeAndTitle(kbId: String, sourceType: String, title: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllActive(limit: Int, offset: Int): List<KnowledgeItemEntity>

    @Query("SELECT * FROM knowledge_item WHERE contentHash = :sourceHash AND deletedAt IS NULL")
    suspend fun getBySourceHash(sourceHash: String): List<KnowledgeItemEntity>
}
