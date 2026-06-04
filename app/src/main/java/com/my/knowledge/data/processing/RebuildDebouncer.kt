package com.my.knowledge.data.processing

import android.util.Log
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.ingest.SweepReviews
import com.my.knowledge.data.ingest.ThreadEvolutionRunner
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-knowledge-base debouncer for expensive, idempotent rebuilds that
 * follow every ingest.
 *
 * P0-1 motivation: previously `IngestOrchestrator.generationTask` ran
 * `updateItemCount` / `refreshOverviewForBase` / `rebuildGraphForBase` /
 * `SweepReviews.sweep` **inside** the per-KB write lock, then fired a
 * second `rebuildGraphForBase` from the lock-free
 * `scheduler?.scheduleThreadUpdate` → `ThreadEvolutionWorker` chain.
 * Every ingest triggered two full-KB graph rebuilds and serialized the
 * whole KB.
 *
 * The debouncer splits the work:
 *   1. The KB write lock only covers wiki-page writes now.
 *   2. The four side-effecting rebuilds are scheduled here, off the
 *      lock, on `Dispatchers.IO`.
 *   3. Per-KB debounce: rapid-fire triggers (e.g. importing five
 *      sources into the same KB in quick succession) collapse into a
 *      single rebuild. `collectLatest` cancels the in-flight rebuild
 *      when a newer trigger lands.
 *   4. Failure isolation: a throw inside one KB's action is logged
 *      and swallowed by the per-KB `collectLatest`; other KBs keep
 *      working because each KB has its own flow + job.
 *
 * Three schedules are exposed (graph / sweep / thread) so callers
 * don't have to pick the right debounce window themselves. A generic
 * [schedule] is also provided for callers that need custom debounce
 * (e.g. unit tests).
 *
 * Backed by `MutableSharedFlow` + `debounce` + `collectLatest` per
 * the design note in the P0-1 spec.
 */
class RebuildDebouncer(
    private val db: AppDatabase,
    private val repository: KnowledgeRepository,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val graphDebounceMs: Long = DEFAULT_GRAPH_DEBOUNCE_MS,
    private val threadDebounceMs: Long = DEFAULT_THREAD_DEBOUNCE_MS,
    private val sweepDebounceMs: Long = DEFAULT_SWEEP_DEBOUNCE_MS,
) {
    /**
     * Internal per-KB bucket. Keyed by `kbId.ifBlank { "_unfiled" }` so
     * the "unfiled" / blank-id path also gets its own coalescing
     * pipeline. Each bucket owns:
     *   - a `MutableSharedFlow` of "ping me" signals;
     *   - a `Job` that runs the bucket's `collectLatest { action() }`
     *     pipeline. The job is supervised by [externalScope]; a throw
     *     inside the bucket does not tear down sibling buckets.
     */
    private class Bucket(
        val trigger: MutableSharedFlow<Unit>,
        var job: Job?,
    )

    private val buckets = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    /**
     * Per-KB graph rebuild. Coalesces [scheduleGraphRebuild] calls
     * inside [graphDebounceMs] and runs [repository.rebuildGraphForBase]
     * on `Dispatchers.IO`. Cancel-and-replace semantics: a new trigger
     * that lands while the previous rebuild is still running cancels
     * the in-flight coroutine and starts a fresh one (this is what
     * `collectLatest` gives us).
     */
    fun scheduleGraphRebuild(kbId: String) {
        scheduleInternal(
            kbId = kbId,
            kind = "graph",
            debounceMs = graphDebounceMs,
            action = {
                withContext(Dispatchers.IO) {
                    repository.rebuildGraphForBase(kbId)
                }
            },
        )
    }

    /**
     * Per-KB overview markdown refresh. Coalesces inside
     * [graphDebounceMs] (the overview is rebuilt from the same data
     * the graph sees, so keeping the same debounce window means we
     * don't accidentally fire one without the other).
     */
    fun scheduleOverviewRefresh(kbId: String) {
        if (kbId.isBlank()) return
        scheduleInternal(
            kbId = kbId,
            kind = "overview",
            debounceMs = graphDebounceMs,
            action = {
                withContext(Dispatchers.IO) {
                    repository.refreshOverviewForBase(kbId)
                }
            },
        )
    }

    /**
     * Per-KB review-queue sweep. Off the ingest hot path (per P0-1
     * spec): runs on its own debounce window so an import burst
     * doesn't push review work onto every generation.
     */
    fun scheduleSweepReviews(kbId: String) {
        scheduleInternal(
            kbId = kbId,
            kind = "sweep",
            debounceMs = sweepDebounceMs,
            action = {
                withContext(Dispatchers.IO) {
                    SweepReviews(db).sweep(kbId)
                }
            },
        )
    }

    /**
     * Per-KB thread evolution (the `ThreadEvolutionWorker` body,
     * inlined as a `suspend` function so we don't go through
     * WorkManager). Coalesces inside [threadDebounceMs] — this is
     * intentionally larger than the graph debounce because the
     * thread rebuild is more expensive and the user-visible thread
     * doesn't change between two quick generations.
     */
    fun scheduleThreadEvolution(kbId: String) {
        if (kbId.isBlank()) return
        scheduleInternal(
            kbId = kbId,
            kind = "thread",
            debounceMs = threadDebounceMs,
            action = {
                withContext(Dispatchers.IO) {
                    ThreadEvolutionRunner.runEvolution(db, repository, kbId)
                }
            },
        )
    }

    /**
     * Generic per-KB debounced action. Used by [RebuildDebouncerTest]
     * to verify the dispatch contract without spinning up the full
     * ingest pipeline. Most callers should prefer the typed helpers
     * above so the debounce windows stay consistent.
     */
    fun schedule(kbId: String, debounceMs: Long, action: suspend () -> Unit) {
        scheduleInternal(kbId = kbId, kind = "custom:$debounceMs", debounceMs = debounceMs, action = action)
    }

    @OptIn(FlowPreview::class)
    private fun scheduleInternal(kbId: String, kind: String, debounceMs: Long, action: suspend () -> Unit) {
        val kbKey = kbId.ifBlank { "_unfiled" }
        val key = "$kbKey:$kind"
        val bucket = buckets.getOrPut(key) {
            Bucket(
                trigger = MutableSharedFlow(
                    replay = 1,
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                ),
                job = null,
            )
        }
        // If the previous job finished (normal completion OR throw),
        // start a fresh one. We do not restart a job that is still
        // running — `collectLatest` already handles back-pressure
        // for live pings.
        val current = bucket.job
        if (current == null || !current.isActive) {
            bucket.job = externalScope.launch {
                bucket.trigger
                    .debounce(debounceMs)
                    .collectLatest {
                        try {
                            action()
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            // Failure isolation: log and swallow so the
                            // bucket keeps accepting future triggers.
                            // The KB remains consistent at the last
                            // successful rebuild; the next ingest will
                            // re-schedule and the next successful run
                            // will catch the KB up.
                            Log.e(
                                "RebuildDebouncer",
                                "Rebuild action failed for kb=$kbKey kind=$kind (debounceMs=$debounceMs): ${t.message}",
                                t,
                            )
                        }
                    }
            }
            // Make sure the bucket is also cancelled if the external
            // scope is cancelled (process death / app teardown).
            bucket.job?.invokeOnCompletion { cause ->
                if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                    Log.w("RebuildDebouncer", "Bucket for kb=$kbKey kind=$kind ended with: ${cause.message}")
                }
            }
        }
        // Fire-and-forget: tryEmit because we asked for buffer=1 +
        // DROP_OLDEST, so this never suspends and never blocks the
        // caller. The "DROP_OLDEST" semantics are exactly what we
        // want for debounce: a flood of pings collapses to "fire
        // action() once after the debounce window closes".
        val emitted = bucket.trigger.tryEmit(Unit)
        if (!emitted) {
            Log.w("RebuildDebouncer", "Failed to emit trigger for kb=$kbKey kind=$kind (unexpected — buffer full?)")
        }
    }

    /**
     * Test hook. Cancels every running bucket so unit tests don't
     * leak coroutines between cases. Production code never calls
     * this; the bucket lifecycle is owned by [externalScope].
     */
    fun shutdownForTests() {
        buckets.values.forEach { it.job?.cancel() }
        buckets.clear()
    }

    /**
     * Cancel everything and stop the external scope. Used by app
     * teardown if a host wires one up; usually the process-scoped
     * scope just dies with the process.
     */
    fun shutdown() {
        shutdownForTests()
        externalScope.cancel()
    }

    companion object {
        /**
         * Default per-KB debounce for graph + overview rebuilds.
         *
         * 1s matches the P0-1 spec: "1 秒内多次触发合并成 1 次;最少间隔 1s".
         * Imports are paced by the user / queue, so 1s is short enough
         * that the user never sees a stale graph and long enough that
         * a burst of N generations for the same KB collapses to 1
         * rebuild.
         */
        const val DEFAULT_GRAPH_DEBOUNCE_MS: Long = 1_000L

        /**
         * Default per-KB debounce for thread evolution. Larger than
         * the graph debounce because (a) the thread rebuild is more
         * expensive and (b) the user-visible thread doesn't change
         * meaningfully between two quick generations. Matches the
         * P0-1 spec: "5 秒,合并多次 generation 的连续触发".
         */
        const val DEFAULT_THREAD_DEBOUNCE_MS: Long = 5_000L

        /**
         * Default per-KB debounce for review sweeps. Runs off the
         * hot path; 3s is enough to coalesce multiple sources in the
         * same import batch.
         */
        const val DEFAULT_SWEEP_DEBOUNCE_MS: Long = 3_000L
    }
}
