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

    @Query("SELECT * FROM knowledge_base WHERE type = :type AND deletedAt IS NULL LIMIT 1")
    suspend fun getByType(type: String): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_base WHERE name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun getByName(name: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(base: KnowledgeBaseEntity)

    @Update
    suspend fun update(base: KnowledgeBaseEntity)

    @Query("UPDATE knowledge_base SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("DELETE FROM knowledge_base WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE knowledge_base SET itemCount = :count, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItemCount(id: String, count: Int, updatedAt: Long)

    @Query("SELECT id FROM knowledge_base WHERE deletedAt IS NULL")
    suspend fun getAllIds(): List<String>
}