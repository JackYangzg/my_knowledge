package com.my.knowledge.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1-A.4: lifecycle-only runner extracted from [IngestRuntime].
 *
 * The original `IngestRuntime` object is a singleton, which makes
 * it impossible to write a clean lifecycle test (state leaks between
 * tests, no way to inject a fake `runOnce`). This class is the
 * lifecycle policy — start, idempotent re-entry, rerun-while-running,
 * and cancel — with a `runOnce: suspend () -> Unit` injection point
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
    private val rerunRequested = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    /**
     * Start the loop. Idempotent — a second [start] call while a
     * pass is in flight sets `rerunRequested` so the loop runs
     * one more time after the current pass completes, but does
     * not spawn a second coroutine.
     */
    fun start() {
        val active = job
        if (active?.isActive == true) {
            rerunRequested.set(true)
            return
        }
        rerunRequested.set(true)
        job = scope.launch {
            runMutex.withLock {
                do {
                    rerunRequested.set(false)
                    runOnce()
                } while (rerunRequested.get())
            }
        }
    }

    /**
     * Cancel the in-flight pass and the queued re-run. Idempotent
     * — calling on an already-finished loop is a no-op.
     */
    fun cancel() {
        job?.cancel(CancellationException("Ingest cancelled by user"))
        job = null
        rerunRequested.set(false)
    }

    fun isActive(): Boolean = job?.isActive == true
}
