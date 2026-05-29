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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: KnowledgeItemEntity)

    @Update
    suspend fun update(item: KnowledgeItemEntity)

    @Query("UPDATE knowledge_item SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
