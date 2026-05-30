package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeItemDao {
    @Query("""
        SELECT * FROM knowledge_item 
        WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL 
        ORDER BY updatedAt DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun observePagedByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT COUNT(*) FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL")
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

    @Query("SELECT * FROM knowledge_item WHERE contentHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun getByContentHash(hash: String): KnowledgeItemEntity?

    @Query("SELECT * FROM knowledge_item WHERE knowledgeBaseId = :kbId AND contentHash = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun getByKbAndContentHash(kbId: String, hash: String): KnowledgeItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: KnowledgeItemEntity)

    @Update
    suspend fun update(item: KnowledgeItemEntity)

    @Query("UPDATE knowledge_item SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("DELETE FROM knowledge_item WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE knowledge_item SET knowledgeBaseId = :targetKbId, updatedAt = :updatedAt WHERE id = :itemId")
    suspend fun moveToBase(itemId: String, targetKbId: String, updatedAt: Long)

    // Batch item count update
    @Query("UPDATE knowledge_base SET itemCount = (SELECT COUNT(*) FROM knowledge_item WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL) WHERE id = :kbId")
    suspend fun updateItemCount(kbId: String)

    // Status queries
    @Query("SELECT * FROM knowledge_item WHERE status = :status AND deletedAt IS NULL")
    fun observeByStatus(status: String): Flow<List<KnowledgeItemEntity>>
}