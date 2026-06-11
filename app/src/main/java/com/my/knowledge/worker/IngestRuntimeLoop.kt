package com.my.knowledge.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P1-A.4: lifecycle-only runner extracted from [IngestRuntime].
 *
 * The original `IngestRuntime` object is a singleton, which makes
 * it impossible to write a clean lifecycle test (state leaks between
 * tests, no way to inject a fake `runOnce`). This class is the
 * lifecycle policy — start, idempotent re-entry, and cancel — with a
 * `runOnce: suspend () -> Unit` injection point
 * so tests can drive it without spinning up the real
 * `AppDatabase` / orchestrator.
 *
 * The real [IngestRuntime] is now a thin wrapper that owns the
 * singleton scope + the wake / wifi locks, and hands its `runOnce`
 * body into an [IngestRuntimeLoop] instance.
 */
class IngestRuntimeLoop(
    private val scope: CoroutineScope,
    private val runOnce: suspend () -> Unit,
) {
    private val runMutex = Mutex()
    @Volatile
    private var job: Job? = null

    /**
     * Start one drain pass. Re-entry while that pass is active is a
     * no-op: the scheduler already polls for tasks enqueued during the
     * pass, and a second full pass after embedding can cold-recover a
     * source into a brand-new ingest cycle.
     */
    fun start() {
        val active = job
        if (active?.isActive == true) {
            return
        }
        job = scope.launch {
            runMutex.withLock {
                runOnce()
            }
        }
    }

    /**
     * Cancel the in-flight pass. Idempotent — calling on an
     * already-finished loop is a no-op.
     */
    fun cancel() {
        job?.cancel(CancellationException("Ingest cancelled by user"))
        job = null
    }

    fun isActive(): Boolean = job?.isActive == true
}
