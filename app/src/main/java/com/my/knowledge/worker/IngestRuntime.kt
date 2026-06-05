package com.my.knowledge.worker

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.ingest.IngestOrchestrator
import com.my.knowledge.data.ingest.IngestOrchestratorApi
import com.my.knowledge.ui.DependencyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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
 */
object IngestRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val rerunRequested = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        val active = job
        if (active?.isActive == true) {
            rerunRequested.set(true)
            return
        }

        rerunRequested.set(true)
        job = scope.launch {
            runMutex.withLock {
                withIngestRuntimeLocks(appContext) {
                    do {
                        rerunRequested.set(false)
                        runOnceInternal(appContext)
                    } while (rerunRequested.get())
                }
            }
        }
    }

    suspend fun runOnce(context: Context) {
        val appContext = context.applicationContext
        runMutex.withLock {
            withIngestRuntimeLocks(appContext) {
                runOnceInternal(appContext)
            }
        }
    }

    fun cancel() {
        job?.cancel(CancellationException("Ingest cancelled by user"))
        job = null
        rerunRequested.set(false)
    }

    private suspend fun runOnceInternal(appContext: Context) {
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
