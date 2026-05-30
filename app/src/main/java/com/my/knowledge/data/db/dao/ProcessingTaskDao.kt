package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskDao {
    @Query("SELECT * FROM processing_task WHERE status = 'pending' OR status = 'running' ORDER BY priority DESC, createdAt ASC")
    fun observeActiveTasks(): Flow<List<ProcessingTaskEntity>>

    @Query("SELECT * FROM processing_task WHERE targetType = :targetType AND targetId = :targetId AND (status = 'pending' OR status = 'running' OR status = 'failed') LIMIT 1")
    suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_task WHERE status = 'failed' AND retryCount < maxRetry ORDER BY retryCount ASC, createdAt ASC LIMIT 1")
    suspend fun getRetryableTask(): ProcessingTaskEntity?

    @Query("SELECT COUNT(*) FROM processing_task WHERE status = 'pending' OR status = 'running'")
    fun observeActiveTaskCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processing_task WHERE status = 'failed'")
    fun observeFailedTaskCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: ProcessingTaskEntity)

    @Update
    suspend fun update(task: ProcessingTaskEntity)

    @Query("UPDATE processing_task SET status = :status, updatedAt = :updatedAt, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long, finishedAt: Long?)

    @Query("UPDATE processing_task SET status = 'pending', retryCount = retryCount + 1, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun retryTask(id: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'failed', errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: String, error: String, updatedAt: Long)

    @Query("DELETE FROM processing_task WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM processing_task WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteByTarget(targetType: String, targetId: String)
}