package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.dao.ParsedContentDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency contract for [IngestScheduler.runUntilIdle]:
 *   1. With parallelism=4, four lanes run tasks in parallel (not
 *      serially). Verified by an atomic peak counter that
 *      should exceed 1.
 *   2. No double-claim. A task claimed by one lane is not re-claimed
 *      by another. Verified by an atomic counter that ends at
 *      exactly the number of enqueued tasks.
 *   3. Lanes exit cleanly when the queue drains. Bounded with a
 *      4-second `withTimeout` (well above the
 *      INGEST_IDLE_POLLS × INGEST_IDLE_POLL_MS ≈ 1s idle window).
 *   4. Chainable task types (parse → analysis → generation) on
 *      the same source run inside one lane without releasing
 *      back to the global claim queue.
 */
class IngestSchedulerConcurrencyTest {

    @Test
    fun `four lanes run tasks in parallel without double-claim`() = runBlocking {
        val taskDao = FakeTaskDao()
        val sourceDao = FakeSourceDao()
        val parsedDao = FakeParsedContentDao()
        val total = 40
        val now = System.currentTimeMillis()
        repeat(total) { i ->
            taskDao.pending += ProcessingTaskEntity(
                id = "task-$i",
                targetType = "source_document",
                targetId = "source-$i",
                sourceId = "source-$i",
                taskType = "embedding", // not chainable — exercises the global claim path
                status = "pending",
                priority = 5,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null
            )
        }
        val scheduler = IngestScheduler(taskDao, sourceDao, parsedDao)

        val observed = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val ran = ConcurrentLinkedQueue<String>()
        val runTask: suspend (ProcessingTaskEntity) -> Boolean = { task ->
            val live = observed.incrementAndGet()
            peak.updateAndGet { kotlin.math.max(it, live) }
            delay(20)
            ran += task.id
            observed.decrementAndGet()
            true
        }
        withTimeout(4_000) {
            scheduler.runUntilIdle(
                maxTasks = total,
                parallelism = 4,
                runTask = runTask,
                enqueueFn = { _, _, _, _ -> },
            )
        }
        assertEquals("no double-claim", total, ran.size)
        assertEquals("every enqueued task ran exactly once", total, ran.toSet().size)
        assertTrue(
            "expected concurrent execution (peak > 1), got $peak",
            peak.get() > 1
        )
    }

    @Test
    fun `chainable task types chain inside one lane for the same source`() = runBlocking {
        val taskDao = FakeTaskDao()
        val sourceDao = FakeSourceDao()
        val parsedDao = FakeParsedContentDao()
        val now = System.currentTimeMillis()
        // Three chainable task types for a single source. The
        // scheduler's chainable claim loop should walk them
        // without releasing the lane back to the global queue.
        listOf("parse", "analysis", "generation").forEachIndexed { i, type ->
            taskDao.pending += ProcessingTaskEntity(
                id = "chain-$i",
                targetType = "source_document",
                targetId = "src-A",
                sourceId = "src-A",
                taskType = type,
                status = "pending",
                priority = 10,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null
            )
        }
        val scheduler = IngestScheduler(taskDao, sourceDao, parsedDao)

        val order = ConcurrentLinkedQueue<String>()
        val runTask: suspend (ProcessingTaskEntity) -> Boolean = { task ->
            order += task.taskType
            true
        }
        withTimeout(4_000) {
            scheduler.runUntilIdle(
                maxTasks = 3,
                parallelism = 4,
                runTask = runTask,
                enqueueFn = { _, _, _, _ -> },
            )
        }
        assertEquals(listOf("parse", "analysis", "generation"), order.toList())
    }

    @Test
    fun `idle scheduler exits when the claim queue stays empty`() = runBlocking {
        val taskDao = FakeTaskDao()
        val sourceDao = FakeSourceDao()
        val parsedDao = FakeParsedContentDao()
        val scheduler = IngestScheduler(taskDao, sourceDao, parsedDao)
        val calls = AtomicInteger(0)
        val runTask: suspend (ProcessingTaskEntity) -> Boolean = {
            calls.incrementAndGet()
            true
        }
        withTimeout(4_000) {
            scheduler.runUntilIdle(
                maxTasks = 80,
                parallelism = 4,
                runTask = runTask,
                enqueueFn = { _, _, _, _ -> },
            )
        }
        assertEquals(0, calls.get())
    }
}

// === Hand-rolled fakes ====================================================
//
// IngestScheduler only touches a small subset of the DAO surface, so
// we implement just what the scheduler calls and throw
// NotImplementedError for the rest. That keeps the test honest about
// its dependencies: if the scheduler ever starts reading a method we
// haven't stubbed, the test will fail loudly instead of silently
// returning zero.

private class FakeTaskDao : ProcessingTaskDao {
    val pending: MutableList<ProcessingTaskEntity> = mutableListOf()
    private val lock = Mutex()

    override suspend fun resetInterruptedRunningTasks(excludedTaskId: String?, updatedAt: Long): Int = 0

    override suspend fun claimNextPendingTask(startedAt: Long): ProcessingTaskEntity? = lock.withLock {
        pending.firstOrNull { it.status == "pending" }?.also { claimed ->
            val idx = pending.indexOf(claimed)
            pending[idx] = claimed.copy(status = "running")
        }
    }

    override suspend fun claimNextPendingTaskForSource(sourceId: String, startedAt: Long): ProcessingTaskEntity? = lock.withLock {
        pending.firstOrNull { it.status == "pending" && it.sourceId == sourceId }?.also { claimed ->
            val idx = pending.indexOf(claimed)
            pending[idx] = claimed.copy(status = "running")
        }
    }

    // ---- Unused: throw to surface accidental coupling. ------------------
    override fun observeActiveTasks() = notImpl("observeActiveTasks")
    override fun observeAllTasks() = notImpl("observeAllTasks")
    override suspend fun getById(id: String) = notImpl("getById")
    override suspend fun getPendingTaskCandidates(limit: Int) = notImpl("getPendingTaskCandidates")
    override suspend fun claimTask(id: String, startedAt: Long) = notImpl("claimTask")
    override suspend fun getPendingTaskCandidatesForSource(sourceId: String, limit: Int) = notImpl("getPendingTaskCandidatesForSource")
    override suspend fun getActiveBySourceAndType(sourceId: String, taskType: String) = notImpl("getActiveBySourceAndType")
    override suspend fun getActiveByItemAndType(itemId: String, taskType: String) = notImpl("getActiveByItemAndType")
    override suspend fun getBySource(sourceId: String) = notImpl("getBySource")
    override suspend fun getBySourceDocument(sourceId: String) = notImpl("getBySourceDocument")
    override suspend fun getPendingTask(targetType: String, targetId: String) = notImpl("getPendingTask")
    override suspend fun getRetryableTask() = notImpl("getRetryableTask")
    override fun observeActiveTaskCount() = notImpl("observeActiveTaskCount")
    override fun observeFailedTaskCount() = notImpl("observeFailedTaskCount")
    override suspend fun countActive() = notImpl("countActive")
    override suspend fun insert(task: ProcessingTaskEntity) = notImpl("insert")
    override suspend fun update(task: ProcessingTaskEntity) = notImpl("update")
    override suspend fun updateStatus(id: String, status: String, updatedAt: Long, finishedAt: Long?) = notImpl("updateStatus")
    override suspend fun resetStaleRunningTasks(cutoff: Long, updatedAt: Long) = notImpl("resetStaleRunningTasks")
    override suspend fun cancelTask(id: String, updatedAt: Long) = notImpl("cancelTask")
    override suspend fun cancelBySource(sourceId: String, updatedAt: Long) = notImpl("cancelBySource")
    override suspend fun retryTask(id: String, updatedAt: Long) = notImpl("retryTask")
    override suspend fun retryBySource(sourceId: String, updatedAt: Long) = notImpl("retryBySource")
    override suspend fun markFailed(id: String, error: String, updatedAt: Long) = notImpl("markFailed")
    override suspend fun delete(id: String) = notImpl("delete")
    override suspend fun deleteByTarget(targetType: String, targetId: String) = notImpl("deleteByTarget")
    override suspend fun deleteBySource(sourceId: String) = notImpl("deleteBySource")

    private fun notImpl(name: String): Nothing =
        throw NotImplementedError("FakeTaskDao.$name is not stubbed; the scheduler pulled a new dependency")
}

private class FakeSourceDao : SourceDocumentDao {
    val runnable: MutableList<SourceDocumentEntity> = mutableListOf()

    override suspend fun getRunnableSourcesWithoutActiveTask(
        statuses: List<String>,
        limit: Int
    ): List<SourceDocumentEntity> = runnable.toList()

    override suspend fun getById(id: String) = notImpl("getById")
    override suspend fun findBySha256(sha256: String) = notImpl("findBySha256")
    override fun observeAll() = notImpl("observeAll")
    override fun observeByKnowledgeBase(knowledgeBaseId: String) = notImpl("observeByKnowledgeBase")
    override suspend fun insert(source: SourceDocumentEntity) = notImpl("insert")
    override suspend fun update(source: SourceDocumentEntity) = notImpl("update")
    override suspend fun updateStatus(id: String, status: String, errorMessage: String?, updatedAt: Long) = notImpl("updateStatus")
    override suspend fun markDeleted(id: String, deletedAt: Long) = notImpl("markDeleted")

    private fun notImpl(name: String): Nothing =
        throw NotImplementedError("FakeSourceDao.$name is not stubbed")
}

private class FakeParsedContentDao : ParsedContentDao {
    override suspend fun getLatestBySource(sourceId: String): ParsedContentEntity? = null
    override suspend fun insert(parsed: ParsedContentEntity) = notImpl("insert")
    override suspend fun deleteBySource(sourceId: String) = notImpl("deleteBySource")
    private fun notImpl(name: String): Nothing =
        throw NotImplementedError("FakeParsedContentDao.$name is not stubbed")
}
