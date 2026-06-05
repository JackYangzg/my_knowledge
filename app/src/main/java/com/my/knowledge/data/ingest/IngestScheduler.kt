package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.dao.ParsedContentDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-1 (first half): the 4-lane parallel claim loop that used to live
 * in [IngestOrchestrator.runUntilIdle]. Carved out as a stand-alone
 * class so the orchestrator stops being a 2600-line grab-bag and the
 * scheduler logic can be unit-tested with a fake [runTask] function
 * (the per-task business logic stays on the orchestrator — only the
 * claim / idle / recover orchestration moved here).
 *
 * What lives here:
 *   - The supervisorScope lane fan-out (`runUntilIdle`).
 *   - The "claim next same-source task" loop (the inner `while` that
 *     chains parse → analysis → generation without releasing the lane
 *     back to the claim queue).
 *   - The cold-start recovery: `resetInterruptedTasks` (any task left
 *     in `running` from a crash → back to `pending`) and
 *     `recoverSourcesWithoutActiveTasks` (sources stuck mid-pipeline
 *     without a task row get one re-enqueued).
 *
 * What stays on the orchestrator:
 *   - [IngestOrchestrator.runTask] (per-task business logic, the LLM
 *     calls, the four stage methods, the `currentJob` Job tracking
 *     for `cancel()`). The orchestrator passes `runTask` and its
 *     private `enqueue` helper in as function references.
 *   - The orchestrator's `currentJob` cancel signaling, which targets
 *     a specific LLM stream Job — a layer below the scheduler.
 *
 * The scheduler doesn't see `currentCoroutineContext()[Job]` because
 * cancel propagates through the parent scope (`supervisorScope`) the
 * same way it did when this code was inline; the orchestrator's
 * `runTask` continues to pin the current Job for the LLM cooperative
 * cancel hook.
 */
class IngestScheduler(
    private val taskDao: ProcessingTaskDao,
    private val sourceDao: SourceDocumentDao,
    private val parsedContentDao: ParsedContentDao,
) {
    /**
     * 4-lane parallel claim loop. `runTask` is the per-task business
     * logic hosted by the orchestrator; `enqueueFn` is the orchestrator's
     * own `enqueue` helper, used here by the cold-start recovery path
     * to mint a fresh `analysis` / `parse` task for a source that's
     * stuck mid-pipeline after a crash.
     */
    suspend fun runUntilIdle(
        maxTasks: Int = 80,
        parallelism: Int = 4,
        runTask: suspend (ProcessingTaskEntity) -> Boolean,
        enqueueFn: suspend (sourceId: String, taskType: String, priority: Int, inputJson: String) -> Unit,
    ) = supervisorScope {
        resetInterruptedTasks()
        recoverSourcesWithoutActiveTasks(enqueueFn)
        val laneCount = parallelism.coerceIn(1, 4)
        val processed = AtomicInteger(0)
        val activeTasks = AtomicInteger(0)
        val lanes = (0 until laneCount).map {
            async {
                var idlePolls = 0
                while (processed.get() < maxTasks) {
                    val task = taskDao.claimNextPendingTask(System.currentTimeMillis())
                    if (task == null) {
                        if (activeTasks.get() > 0) {
                            delay(INGEST_IDLE_POLL_MS)
                            continue
                        }
                        if (idlePolls >= INGEST_IDLE_POLLS) break
                        idlePolls++
                        delay(INGEST_IDLE_POLL_MS)
                        continue
                    }
                    idlePolls = 0
                    var nextTask: ProcessingTaskEntity? = task
                    while (nextTask != null && processed.get() < maxTasks) {
                        val current = nextTask
                        processed.incrementAndGet()
                        activeTasks.incrementAndGet()
                        val success = try {
                            runTask(current)
                        } finally {
                            activeTasks.decrementAndGet()
                        }
                        nextTask = if (shouldClaimNextSameSourceTask(current.taskType, success)) {
                            claimNextSameSourceTask(current)
                        } else {
                            null
                        }
                    }
                }
            }
        }
        lanes.awaitAll()
    }

    private suspend fun claimNextSameSourceTask(task: ProcessingTaskEntity): ProcessingTaskEntity? {
        val sourceId = task.sourceId ?: task.targetId
        if (sourceId.isBlank()) return null
        return taskDao.claimNextPendingTaskForSource(
            sourceId = sourceId,
            startedAt = System.currentTimeMillis()
        )
    }

    /**
     * Should the lane, after a successful task, immediately claim
     * the *next* task for the same source (parse → analysis →
     * generation) without releasing the lane back to the global
     * claim queue? Yes for the three chainable task types when
     * the previous one succeeded; otherwise release so a different
     * lane (or a fresh round of scheduling) can pick up work.
     *
     * CQ-2: inlined from the 8-line `IngestQueuePolicy` object —
     * YAGNI while there's only one call site.
     */
    private fun shouldClaimNextSameSourceTask(taskType: String, taskSucceeded: Boolean): Boolean =
        taskSucceeded && taskType in CHAINABLE_TASK_TYPES

    private suspend fun resetInterruptedTasks() {
        taskDao.resetInterruptedRunningTasks(
            excludedTaskId = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Cold-start recovery: a source can be in any of
     * `imported / parsing / parsed / analyzing` (i.e. mid-pipeline) but
     * have *no* corresponding active task row — usually because the
     * process was killed between `task.finish()` and the next
     * `enqueue()`. We re-enqueue a `parse` or `analysis` task per
     * source based on the source's current status, with
     * `recovered: true` in the inputJson so the stage method can take
     * a slightly different code path (e.g. re-use an existing
     * `parsed_content` row instead of erroring on duplicate).
     */
    private suspend fun recoverSourcesWithoutActiveTasks(
        enqueueFn: suspend (sourceId: String, taskType: String, priority: Int, inputJson: String) -> Unit,
    ) {
        val sources = sourceDao.getRunnableSourcesWithoutActiveTask(
            statuses = listOf(
                SourceDocumentEntity.STATUS_IMPORTED,
                SourceDocumentEntity.STATUS_PARSING,
                SourceDocumentEntity.STATUS_PARSED,
                SourceDocumentEntity.STATUS_ANALYZING
            )
        )
        sources.forEach { source ->
            when (source.status) {
                SourceDocumentEntity.STATUS_PARSED,
                SourceDocumentEntity.STATUS_ANALYZING -> {
                    val parsed = parsedContentDao.getLatestBySource(source.id)
                    if (parsed != null) {
                        enqueueFn(source.id, "analysis", 9, """{"parsedContentId":"${parsed.id}","recovered":true}""")
                    } else {
                        enqueueFn(source.id, "parse", 10, """{"sourceId":"${source.id}","recovered":true}""")
                    }
                }
                else -> enqueueFn(source.id, "parse", 10, """{"sourceId":"${source.id}","recovered":true}""")
            }
        }
    }

    companion object {
        /**
         * Backing-off poll interval when the claim queue is empty.
         * Picked low enough that the worker reacts to a new enqueue
         * within ~250ms (the existing "Poll for new tasks" cadence in
         * the Android `WorkManager` schedule), but high enough that
         * an idle lane isn't a hot loop.
         */
        const val INGEST_IDLE_POLL_MS: Long = 250L

        /**
         * Number of consecutive idle polls (no claimable task, no
         * in-flight task in the other lanes) before the lane
         * declares itself done and the supervisor scope completes.
         * 4 × 250ms = 1s of confirmed idle before exit — the
         * WorkManager re-schedule picks it back up if new work
         * shows up after that.
         */
        const val INGEST_IDLE_POLLS: Int = 4

        /**
         * The three task types that chain inside one source's
         * pipeline without releasing the lane. `embedding` is
         * intentionally NOT here — it's a different shape of work
         * (purely local, no LLM) and runs after the source has
         * fully `STATUS_GENERATED`, so it gets its own claim
         * round.
         */
        private val CHAINABLE_TASK_TYPES = setOf("parse", "analysis", "generation")
    }
}
