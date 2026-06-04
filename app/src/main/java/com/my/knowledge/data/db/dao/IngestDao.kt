package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDocumentDao {
    @Query("SELECT * FROM source_document WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceDocumentEntity?

    @Query("SELECT * FROM source_document WHERE sha256 = :sha256 AND status != 'deleted' LIMIT 1")
    suspend fun findBySha256(sha256: String): SourceDocumentEntity?

    @Query("SELECT * FROM source_document WHERE status != 'deleted' ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SourceDocumentEntity>>

    @Query("SELECT * FROM source_document WHERE targetKnowledgeBaseId = :kbId AND status != 'deleted' ORDER BY updatedAt DESC")
    fun observeByKnowledgeBase(kbId: String): Flow<List<SourceDocumentEntity>>

    @Query("""
        SELECT * FROM source_document AS s
        WHERE s.status IN (:statuses)
          AND s.status != 'deleted'
          AND NOT EXISTS (
              SELECT 1 FROM processing_task AS t
              WHERE (t.sourceId = s.id OR (t.targetType = 'source_document' AND t.targetId = s.id))
                AND t.status IN ('pending', 'running', 'pending_network', 'pending_config')
          )
        ORDER BY s.updatedAt ASC
        LIMIT :limit
    """)
    suspend fun getRunnableSourcesWithoutActiveTask(
        statuses: List<String>,
        limit: Int = 50
    ): List<SourceDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceDocumentEntity)

    @Update
    suspend fun update(source: SourceDocumentEntity)

    @Query("UPDATE source_document SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String?, updatedAt: Long)

    @Query("UPDATE source_document SET status = 'deleted', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long)
}

@Dao
interface ParsedContentDao {
    @Query("SELECT * FROM parsed_content WHERE sourceId = :sourceId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestBySource(sourceId: String): ParsedContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(parsed: ParsedContentEntity)

    @Query("DELETE FROM parsed_content WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}

@Dao
interface AnalysisResultDao {
    @Query("SELECT * FROM analysis_result WHERE sourceId = :sourceId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestBySource(sourceId: String): AnalysisResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: AnalysisResultEntity)

    @Query("DELETE FROM analysis_result WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}

@Dao
interface ReviewItemDao {
    @Query("SELECT * FROM review_item WHERE status = 'pending' ORDER BY createdAt ASC")
    fun observePending(): Flow<List<ReviewItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ReviewItemEntity)

    @Query("UPDATE review_item SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: String, status: String, resolvedAt: Long)

    @Query("UPDATE review_item SET status = 'skipped', resolvedAt = :resolvedAt WHERE sourceId = :sourceId AND status = 'pending'")
    suspend fun skipBySource(sourceId: String, resolvedAt: Long)
}
