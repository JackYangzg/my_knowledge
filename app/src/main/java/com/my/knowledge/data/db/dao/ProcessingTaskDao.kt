package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskDao {
    @Query("SELECT * FROM processing_task WHERE status = 'pending' OR status = 'running' ORDER BY priority DESC, createdAt ASC")
    fun observeActiveTasks(): Flow<List<ProcessingTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: ProcessingTaskEntity)

    @Update
    suspend fun update(task: ProcessingTaskEntity)

    @Query("UPDATE processing_task SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, finishedAt: Long?)
}
