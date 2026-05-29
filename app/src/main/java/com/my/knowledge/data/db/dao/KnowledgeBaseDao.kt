package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeBaseDao {
    @Query("SELECT * FROM knowledge_base WHERE deletedAt IS NULL ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_base WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(kb: KnowledgeBaseEntity)

    @Update
    suspend fun update(kb: KnowledgeBaseEntity)

    @Query("UPDATE knowledge_base SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
