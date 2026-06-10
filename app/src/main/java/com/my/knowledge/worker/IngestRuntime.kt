package com.my.knowledge.worker

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.ingest.IngestOrchestrator
import com.my.knowledge.data.ingest.IngestOrchestratorApi
import com.my.knowledge.ui.DependencyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifetime ingest runner.
 *
 * WorkManager is still enqueued as a recovery path, but on some
 * devices a long-running Worker can be stopped/deferred when the app
 * moves to the background. This runner is scoped to the app process
 * instead of an Activity/ViewModel, so imports keep draining while the
 * app is merely in the background. If the process is killed, the
 * persisted pending/running task rows plus WorkManager restart the
 * pipeline on the next opportunity.
 *
 * P1-A.4: the lifecycle policy (start / re-entry / rerun / cancel)
 * is now hosted by [IngestRuntimeLoop]. This object is a thin
 * singleton wrapper that owns the app-process scope + the wake /
 * wifi locks and hands its `runOnce` body into a loop instance.
 * That split is what makes the lifecycle testable without
 * standing up the real `AppDatabase`.
 */
object IngestRuntime {
    private const val TAG = "IngestRuntime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()

    // The loop is rebuilt lazily so a successful cancel / a fresh
    // start can spin up a clean instance. In the old design this
    // was inlined in the singleton and untestable.
    @Volatile
    private var loop: IngestRuntimeLoop? = null

    // Cached app context so [cancel] can tear the FG service down
    // even when called from a coroutine that no longer has the
    // original `context` reference.
    @Volatile
    private var lastAppContext: Context? = null

    /**
     * N3 (RELIAB-1 PR-N3): expose wake/wifi lock acquisition result
     * to the UI layer so the user can see when battery-optimization
     * or OEM background-killers are stripping our locks. The state
     * updates synchronously inside [withIngestRuntimeLocks], so the
     * Flow re-emits at the start and end of every `runOnce` pass.
     */
    data class LockStatus(
        val wakeLockHeld: Boolean,
        val wifiLockHeld: Boolean,
        val wakeLockError: String? = null,
        val wifiLockError: String? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val _lockStatus = MutableStateFlow(
        LockStatus(wakeLockHeld = false, wifiLockHeld = false)
    )
    val lockStatus: StateFlow<LockStatus> = _lockStatus.asStateFlow()

    /**
     * RELIAB-1 PR-N1 + PR-N2: live pending-count surfaced to the
     * foreground service so the notification text updates on each
     * item. The orchestrator calls [reportPending] at the end of
     * every `runTask` (success and error paths) via the
     * `pendingCountReporter` constructor callback it receives; this
     * function both updates the StateFlow and pushes a fresh
     * notification when an FG service is running.
     */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    fun reportPending(count: Int) {
        _pendingCount.value = count
        // PR-N2 wiring: if a foreground service is already up
        // (i.e. `start()` was called and `lastAppContext` is
        // cached), push the new count into the notification now.
        // If start() was never called (e.g. WorkManager-only
        // path that goes straight through `runOnce`), there's no
        // notification to update, so we silently no-op.
        lastAppContext?.let { ctx ->
            runCatching { IngestForegroundService.notify(ctx, count) }
        }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        lastAppContext = appContext
        // PR-N2 wiring: read the *actual* pending count from the
        // DB before the FG service starts, so the initial
        // notification text isn't a hard-coded "0 条". `start()` is
        // a non-suspend public entrypoint (called from
        // `ProcessingTaskScheduler.scheduleIngestQueue`); we run the
        // suspend DAO call in a launched coroutine on the runtime
        // scope and seed the StateFlow eagerly with 0 so the FG
        // service's first paint isn't blank. The launched coroutine
        // then `reportPending()`s the real count (which also
        // re-notifies the FG service via `lastAppContext` already
        // being set).
        _pendingCount.value = 0
        scope.launch {
            val initialCount = runCatching {
                AppDatabase.getInstance(appContext).processingTaskDao().countActive()
            }.getOrDefault(0)
            reportPending(initialCount)
        }
        // RELIAB-1 PR-N1: promote to FG service *before* the
        // idempotent re-entry check, because even a no-op re-entry
        // (active loop just polls again) must keep the process
        // boosted while the screen is off. Re-starting an already
        // running FG service is a cheap no-op (system dedupes by id).
        IngestForegroundService.start(appContext, pendingCount = 0)
        val active = loop
        if (active?.isActive() == true) {
            // Idempotent re-entry: the active loop sees the new
            // task on its next `rerunRequested` poll.
            rerunActiveLoop()
            return
        }
        val newLoop = IngestRuntimeLoop(
            scope = scope,
            runOnce = { runOnceInLocks(appContext) }
        )
        loop = newLoop
        newLoop.start()
    }

    suspend fun runOnce(context: Context) {
        val appContext = context.applicationContext
        runMutex.withLock {
            runOnceInLocks(appContext)
        }
    }

    fun cancel() {
        loop?.cancel()
        loop = null
        // RELIAB-1 PR-N1: dismiss the FG notification so the user
        // sees the import stop, and so the service doesn't leak
        // past the loop's lifetime. The `stopService` call is safe
        // even if no service was running (system no-ops).
        IngestForegroundService.stop(appContextForService())
    }

    private fun appContextForService(): android.content.Context {
        // Reuse the most recent appContext captured by `start` /
        // `runOnce`; if neither ran yet this is a no-op since the
        // service isn't started.
        return lastAppContext
            ?: throw IllegalStateException("IngestRuntime.appContext not initialized; call start() before cancel()")
    }

    private fun rerunActiveLoop() {
        // The active loop reads `rerunRequested` between passes;
        // setting it here is enough to queue a follow-up run.
        loop?.start()
    }

    private suspend fun runOnceInLocks(appContext: Context) {
        try {
            withIngestRuntimeLocks(appContext) {
                runOrchestratorOnce(appContext)
            }
        } finally {
            _lockStatus.value = LockStatus(wakeLockHeld = false, wifiLockHeld = false)
            // RELIAB-1 PR-N1: collapse the FG service once no more
            // work is queued. We only stop when the loop has been
            // torn down (i.e. a fresh `start()` is required to
            // resume), otherwise a still-active loop would lose
            // its Doze protection mid-flight.
            if (loop == null) {
                IngestForegroundService.stop(appContext)
            }
        }
    }

    private suspend fun runOrchestratorOnce(appContext: Context) {
        val orchestrator: IngestOrchestratorApi = IngestOrchestrator(
            db = AppDatabase.getInstance(appContext),
            fileStore = LocalFileStore(appContext),
            repository = DependencyProvider.provideKnowledgeRepository(appContext),
            scheduler = DependencyProvider.provideScheduler(appContext),
            rebuildDebouncer = DependencyProvider.provideRebuildDebouncer(appContext),
            longSourceCheckpointStore = com.my.knowledge.data.ingest.LongSourceCheckpointStore(appContext.filesDir),
            // RELIAB-1 PR-N2 (late landing): wire the live
            // "remaining work" reporter. The orchestrator calls
            // this from the end of every `runTask`'s `finally`,
            // which is exactly the point where the DB row is in
            // its post-task state (success: marked complete, error:
            // marked failed / pending-retry). Counting the
            // pending+running rows at that moment gives the
            // notification an honest "剩余 N 条" at all times.
            pendingCountReporter = ::reportPending,
        )
        orchestrator.runUntilIdle()
    }

    private suspend fun withIngestRuntimeLocks(appContext: Context, block: suspend () -> Unit) {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "my_knowledge:IngestRuntime")
            ?.apply { setReferenceCounted(false) }
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiLock = wifiManager
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "my_knowledge:IngestRuntimeWifi")
            ?.apply { setReferenceCounted(false) }

        // N3: log + record lock-acquisition failures so the operator
        // can see when OEM doze / battery-optimization is stripping
        // our locks. Previously `runCatching{}` silently swallowed
        // these and the worker kept running without any protection.
        // Note: `isHeld` is queried on the original `wakeLock`/`wifiLock`
        // reference (not on the runCatching result) because the lambda
        // returns `Unit?` and doesn't carry the WakeLock object.
        val wakeResult = runCatching { wakeLock?.acquire(INGEST_RUNTIME_LOCK_TIMEOUT_MS) }
        val wakeError = wakeResult.exceptionOrNull()?.let { "wake lock failed: ${it.message}" }
        val wakeLockHeld = wakeError == null && wakeLock?.isHeld == true
        if (wakeError != null) {
            Log.w(TAG, wakeError)
        }

        val wifiResult = runCatching { wifiLock?.acquire() }
        val wifiError = wifiResult.exceptionOrNull()?.let { "wifi lock failed: ${it.message}" }
        val wifiLockHeld = wifiError == null && wifiLock?.isHeld == true
        if (wifiError != null) {
            Log.w(TAG, wifiError)
        }

        _lockStatus.value = LockStatus(
            wakeLockHeld = wakeLockHeld,
            wifiLockHeld = wifiLockHeld,
            wakeLockError = wakeError,
            wifiLockError = wifiError,
        )

        try {
            block()
        } finally {
            runCatching {
                if (wifiLock?.isHeld == true) wifiLock.release()
            }
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
            _lockStatus.value = LockStatus(
                wakeLockHeld = false,
                wifiLockHeld = false,
                wakeLockError = wakeError,
                wifiLockError = wifiError,
            )
        }
    }

    private const val INGEST_RUNTIME_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
}
