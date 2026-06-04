package com.my.knowledge.data.ingest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-1.1: collapses the 3 byte-identical SSE throttling blocks
 * that used to live in [IngestOrchestrator] —
 * `collectWithThrottledProgress` /
 * `streamJsonWithThrottledProgress` /
 * `streamTextWithThrottledProgress`. Each one hand-rolled the
 * same `MutableSharedFlow<Int>` + `sample` + `runCatching` +
 * "every-N-tokens gate" dance with the same constants
 * (`PROGRESS_EVERY_N_TOKENS` / `PROGRESS_SAMPLE_MS`); the only
 * real difference was the *source* (a `Flow<String>` accumulator
 * vs an `onChunk: (delta) -> Unit` callback).
 *
 * Lifecycle: the throttler owns its own `SupervisorJob`-backed
 * scope (decoupling from the host's scope makes the call sites
 * simpler — they don't need to hand a `CoroutineScope` in). The
 * writer runs with `CoroutineStart.UNDISPATCHED` so the first
 * `tryEmit` from [observe] is observed without an extra
 * dispatcher hop. Call [observe] (Flow source — pass cumulative
 * length) or [observeDelta] (callback source — pass the chunk
 * length, the throttler tracks the running total). Call [flush]
 * before [close] to guarantee the trailing "done" progress
 * lands before the writer is torn down.
 *
 * `writeProgress` failures are caught at the throttler boundary
 * (transient Room failures must not crash the upstream stream);
 * cancellation propagates normally.
 */
@OptIn(FlowPreview::class)
internal class SseProgressThrottler(
    private val writeProgress: suspend (count: Int) -> Unit,
    private val everyN: Int,
    private val sampleMs: Long,
) {
    private val totalCount = AtomicInteger(0)
    private val lastSignalled = AtomicInteger(0)
    private val firstChunkSeen = AtomicBoolean(false)
    private val signals = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val writerJob: Job

    init {
        writerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                signals.sample(sampleMs).collect { count ->
                    runCatching { writeProgress(count) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Swallow non-cancellation failures: losing one
                // progress tick is fine, losing the LLM response
                // is not. The throttler must never fail its caller.
            }
        }
    }

    /**
     * Feed a cumulative count. Use this when the source is a
     * `Flow<String>` and the host owns the accumulator — the
     * host passes the running length after each append.
     */
    fun observe(count: Int) {
        if (firstChunkSeen.compareAndSet(false, true)) signals.tryEmit(count)
        if (count - lastSignalled.get() >= everyN) {
            lastSignalled.set(count)
            signals.tryEmit(count)
        }
    }

    /**
     * Feed a chunk delta. Use this with gateway `onChunk`
     * callbacks; the throttler tracks the running total itself.
     */
    fun observeDelta(deltaLength: Int) {
        observe(totalCount.addAndGet(deltaLength))
    }

    /**
     * Emit a trailing signal so `sample()` flushes the final
     * "done" progress within `sampleMs` of stream completion.
     */
    fun flush(finalCount: Int) {
        if (finalCount > 0) signals.tryEmit(finalCount)
    }

    /**
     * Expose the running total so the host can log the final
     * count alongside the throttler's trailing flush. Only
     * meaningful when the host used [observeDelta]; for
     * [observe]-driven sources, the host already owns the
     * accumulator and can pass the length directly to [flush].
     */
    fun totalCountForFlush(): Int = totalCount.get()

    /**
     * Cancel the writer and the throttler's scope. Idempotent.
     * Call from a `finally` block so the writer can't write a
     * stale progress update after the host's main work has
     * returned.
     */
    fun close() {
        writerJob.cancel()
        scope.cancel()
    }
}

