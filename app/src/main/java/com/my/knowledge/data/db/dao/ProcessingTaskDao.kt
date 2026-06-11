package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingTaskDao {
    @Query("SELECT * FROM processing_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProcessingTaskEntity?

    @Query("""
        SELECT * FROM processing_task
        WHERE status IN ('pending', 'pending_network')
        ORDER BY priority DESC, createdAt ASC
        LIMIT :limit
    """)
    suspend fun getPendingTaskCandidates(limit: Int = 8): List<ProcessingTaskEntity>

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

    @Query("""
        SELECT * FROM processing_task
        WHERE sourceId = :sourceId
          AND status IN ('pending', 'pending_network')
        ORDER BY priority DESC, createdAt ASC
        LIMIT :limit
    """)
    suspend fun getPendingTaskCandidatesForSource(sourceId: String, limit: Int = 8): List<ProcessingTaskEntity>

    @Transaction
    suspend fun claimNextPendingTask(startedAt: Long): ProcessingTaskEntity? {
        for (task in getPendingTaskCandidates()) {
            val claimed = claimTask(task.id, startedAt) > 0
            if (claimed) return getById(task.id)
        }
        return null
    }

    @Transaction
    suspend fun claimNextPendingTaskForSource(sourceId: String, startedAt: Long): ProcessingTaskEntity? {
        for (task in getPendingTaskCandidatesForSource(sourceId)) {
            val claimed = claimTask(task.id, startedAt) > 0
            if (claimed) return getById(task.id)
        }
        return null
    }

    @Query("""
        SELECT * FROM processing_task
        WHERE sourceId = :sourceId
          AND taskType = :taskType
          AND status IN ('pending', 'running', 'pending_network')
        LIMIT 1
    """)
    suspend fun getActiveBySourceAndType(sourceId: String, taskType: String): ProcessingTaskEntity?

    @Query("""
        SELECT * FROM processing_task
        WHERE itemId = :itemId
          AND taskType = :taskType
          AND status IN ('pending', 'running', 'pending_network')
        LIMIT 1
    """)
    suspend fun getActiveByItemAndType(itemId: String, taskType: String): ProcessingTaskEntity?

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

    /**
     * RELIAB-1 PR-N2 (late landing): one-shot snapshot of remaining
     * work. Used by [com.my.knowledge.worker.IngestRuntime] to push
     * the live count into the foreground-service notification after
     * each `runTask` returns. The Flow variant above is for UI; this
     * suspend variant is for the worker pipeline where we need a
     * consistent snapshot at a known moment.
     */
    @Query("SELECT COUNT(*) FROM processing_task WHERE status = 'pending' OR status = 'running' OR status = 'pending_network'")
    suspend fun countActive(): Int

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
