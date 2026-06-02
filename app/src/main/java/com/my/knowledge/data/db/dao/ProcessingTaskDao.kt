package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskDao {
    @Query("SELECT * FROM processing_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_task WHERE status IN ('pending', 'pending_config') ORDER BY priority DESC, createdAt ASC LIMIT 1")
    suspend fun getNextPendingTask(): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_task WHERE sourceId = :sourceId ORDER BY createdAt DESC")
    suspend fun getBySource(sourceId: String): List<ProcessingTaskEntity>

    @Query("SELECT * FROM processing_task WHERE status = 'pending' OR status = 'running' OR status = 'failed' OR status = 'pending_config' ORDER BY priority DESC, createdAt ASC")
    fun observeActiveTasks(): Flow<List<ProcessingTaskEntity>>

    @Query("SELECT * FROM processing_task ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<ProcessingTaskEntity>>

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

    @Query("UPDATE processing_task SET status = 'canceled', currentStep = '已取消', updatedAt = :updatedAt, finishedAt = :updatedAt WHERE id = :id AND status IN ('pending', 'running', 'failed')")
    suspend fun cancelTask(id: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'canceled', currentStep = '来源已删除', updatedAt = :updatedAt, finishedAt = :updatedAt WHERE sourceId = :sourceId AND status IN ('pending', 'running', 'failed')")
    suspend fun cancelBySource(sourceId: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'pending', retryCount = retryCount + 1, errorMessage = NULL, currentStep = '等待重试', updatedAt = :updatedAt WHERE id = :id")
    suspend fun retryTask(id: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'pending', errorMessage = NULL, currentStep = '等待重试', updatedAt = :updatedAt WHERE sourceId = :sourceId AND status IN ('failed', 'pending_config', 'canceled')")
    suspend fun retryBySource(sourceId: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'failed', errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: String, error: String, updatedAt: Long)

    @Query("DELETE FROM processing_task WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM processing_task WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteByTarget(targetType: String, targetId: String)
}
