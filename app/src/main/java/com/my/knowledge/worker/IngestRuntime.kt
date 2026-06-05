package com.my.knowledge.worker

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.ingest.IngestOrchestrator
import com.my.knowledge.data.ingest.IngestOrchestratorApi
import com.my.knowledge.ui.DependencyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()

    // The loop is rebuilt lazily so a successful cancel / a fresh
    // start can spin up a clean instance. In the old design this
    // was inlined in the singleton and untestable.
    @Volatile
    private var loop: IngestRuntimeLoop? = null

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

        try {
            runCatching { wakeLock?.acquire(INGEST_RUNTIME_LOCK_TIMEOUT_MS) }
            runCatching { wifiLock?.acquire() }
            block()
        } finally {
            runCatching {
                if (wifiLock?.isHeld == true) wifiLock.release()
            }
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private const val INGEST_RUNTIME_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
}
