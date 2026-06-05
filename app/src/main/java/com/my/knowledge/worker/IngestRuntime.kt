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

    fun start(context: Context) {
        val appContext = context.applicationContext
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
    }

    private fun rerunActiveLoop() {
        // The active loop reads `rerunRequested` between passes;
        // setting it here is enough to queue a follow-up run.
        loop?.start()
    }

    private suspend fun runOnceInLocks(appContext: Context) {
        withIngestRuntimeLocks(appContext) {
            runOrchestratorOnce(appContext)
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
