package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskDao {
    @Query("SELECT * FROM processing_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProcessingTaskEntity?

    @Query("""
        SELECT * FROM processing_task AS t
        WHERE t.status IN ('pending', 'pending_network')
          AND NOT EXISTS (
              SELECT 1 FROM processing_task AS r
              WHERE r.status = 'running'
                AND r.taskType = t.taskType
                AND COALESCE(r.sourceId, r.targetId) = COALESCE(t.sourceId, t.targetId)
          )
        ORDER BY priority DESC, createdAt ASC
        LIMIT 1
    """)
    suspend fun getNextPendingTask(): ProcessingTaskEntity?

    @Query("""
        UPDATE processing_task
        SET status = 'running',
            progress = 0,
            errorMessage = NULL,
            currentStep = '等待执行',
            startedAt = :startedAt,
            updatedAt = :startedAt,
            finishedAt = NULL
        WHERE id = :id
          AND status IN ('pending', 'pending_network')
    """)
    suspend fun claimTask(id: String, startedAt: Long): Int

    @Transaction
    suspend fun claimNextPendingTask(startedAt: Long): ProcessingTaskEntity? {
        val task = getNextPendingTask() ?: return null
        return if (claimTask(task.id, startedAt) > 0) getById(task.id) else null
    }

    @Query("""
        SELECT * FROM processing_task
        WHERE sourceId = :sourceId
          AND taskType = :taskType
          AND status IN ('pending', 'running', 'pending_network')
        LIMIT 1
    """)
    suspend fun getActiveBySourceAndType(sourceId: String, taskType: String): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_task WHERE sourceId = :sourceId ORDER BY createdAt DESC")
    suspend fun getBySource(sourceId: String): List<ProcessingTaskEntity>

    @Query("SELECT * FROM processing_task WHERE sourceId = :sourceId OR (targetType = 'source_document' AND targetId = :sourceId) ORDER BY createdAt DESC")
    suspend fun getBySourceDocument(sourceId: String): List<ProcessingTaskEntity>

    @Query("SELECT * FROM processing_task WHERE status = 'pending' OR status = 'running' OR status = 'failed' OR status = 'pending_config' OR status = 'pending_network' ORDER BY priority DESC, createdAt ASC")
    fun observeActiveTasks(): Flow<List<ProcessingTaskEntity>>

    @Query("SELECT * FROM processing_task ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<ProcessingTaskEntity>>

    @Query("SELECT * FROM processing_task WHERE targetType = :targetType AND targetId = :targetId AND (status = 'pending' OR status = 'running' OR status = 'failed' OR status = 'pending_network') LIMIT 1")
    suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity?

    @Query("SELECT * FROM processing_task WHERE status = 'failed' AND retryCount < maxRetry ORDER BY retryCount ASC, createdAt ASC LIMIT 1")
    suspend fun getRetryableTask(): ProcessingTaskEntity?

    @Query("SELECT COUNT(*) FROM processing_task WHERE status = 'pending' OR status = 'running' OR status = 'pending_network'")
    fun observeActiveTaskCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM processing_task WHERE status = 'failed'")
    fun observeFailedTaskCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: ProcessingTaskEntity)

    @Update
    suspend fun update(task: ProcessingTaskEntity)

    @Query("UPDATE processing_task SET status = :status, updatedAt = :updatedAt, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long, finishedAt: Long?)

    @Query("""
        UPDATE processing_task
        SET status = 'pending',
            errorMessage = NULL,
            currentStep = '上次处理中断，等待继续',
            updatedAt = :updatedAt,
            finishedAt = NULL
        WHERE status = 'running'
          AND updatedAt < :cutoff
    """)
    suspend fun resetStaleRunningTasks(cutoff: Long, updatedAt: Long): Int

    @Query("""
        UPDATE processing_task
        SET status = 'pending',
            errorMessage = NULL,
            currentStep = '后台任务已停止，等待继续',
            updatedAt = :updatedAt,
            finishedAt = NULL
        WHERE status = 'running'
          AND (:excludedTaskId IS NULL OR id != :excludedTaskId)
    """)
    suspend fun resetInterruptedRunningTasks(excludedTaskId: String?, updatedAt: Long): Int

    @Query("UPDATE processing_task SET status = 'canceled', currentStep = '已取消', updatedAt = :updatedAt, finishedAt = :updatedAt WHERE id = :id AND status IN ('pending', 'running', 'failed', 'pending_network')")
    suspend fun cancelTask(id: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'canceled', currentStep = '来源已删除', updatedAt = :updatedAt, finishedAt = :updatedAt WHERE sourceId = :sourceId AND status IN ('pending', 'running', 'failed', 'pending_network')")
    suspend fun cancelBySource(sourceId: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'pending', retryCount = retryCount + 1, errorMessage = NULL, currentStep = '等待重试', updatedAt = :updatedAt, finishedAt = NULL WHERE id = :id")
    suspend fun retryTask(id: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'pending', errorMessage = NULL, currentStep = '等待重试', updatedAt = :updatedAt, finishedAt = NULL WHERE sourceId = :sourceId AND status IN ('failed', 'pending_config', 'pending_network', 'canceled')")
    suspend fun retryBySource(sourceId: String, updatedAt: Long)

    @Query("UPDATE processing_task SET status = 'failed', errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markFailed(id: String, error: String, updatedAt: Long)

    @Query("DELETE FROM processing_task WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM processing_task WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteByTarget(targetType: String, targetId: String)

    @Query("DELETE FROM processing_task WHERE sourceId = :sourceId OR (targetType = 'source_document' AND targetId = :sourceId)")
    suspend fun deleteBySource(sourceId: String)
}
