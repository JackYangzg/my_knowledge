package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeThreadDao {
    @Query("SELECT * FROM knowledge_thread WHERE knowledgeBaseId = :kbId LIMIT 1")
    suspend fun getByKb(kbId: String): KnowledgeThreadEntity?

    @Query("SELECT * FROM knowledge_thread WHERE id = :id")
    suspend fun getById(id: String): KnowledgeThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(thread: KnowledgeThreadEntity)

    @Update
    suspend fun update(thread: KnowledgeThreadEntity)
}

@Dao
interface KnowledgeThreadLogDao {
    @Query("SELECT * FROM knowledge_thread_log WHERE threadId = :threadId ORDER BY createdAt DESC")
    fun observeByThread(threadId: String): Flow<List<KnowledgeThreadLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: KnowledgeThreadLogEntity)
}
