package com.my.knowledge.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase

/**
 * P1-C / ARCH-1: `WorkerFactory` for the project's `CoroutineWorker`
 * subclasses. Registered through
 * `WorkManager.Configuration.Builder().setWorkerFactory(...)` so
 * WorkManager can construct workers from class name without a Hilt
 * dependency.
 *
 * The factory hands each worker a [WorkerDependencies] handle so
 * the worker's `doWork` can pull the shared `KnowledgeRepository`
 * (and the rest of the dependency graph) without re-deriving the
 * 18-arg `KnowledgeRepositoryImpl` constructor.
 */
class MyKnowledgeWorkerFactory(
    private val appContext: Context,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        IngestWorker::class.java.name -> IngestWorker(
            appContext,
            workerParameters,
        )
        ThreadEvolutionWorker::class.java.name -> ThreadEvolutionWorker(
            appContext,
            workerParameters,
        )
        TagWorker::class.java.name -> TagWorker(
            appContext,
            workerParameters,
        )
        SummaryWorker::class.java.name -> SummaryWorker(
            appContext,
            workerParameters,
        )
        ArchiveRecommendWorker::class.java.name -> ArchiveRecommendWorker(
            appContext,
            workerParameters,
        )
        LlmInspirationThreadWorker::class.java.name -> LlmInspirationThreadWorker(
            appContext,
            workerParameters,
        )
        else -> null // WorkManager falls back to reflection for unknown workers.
    }

    /**
     * Convenience: warm the dependency cache so the first
     * `doWork` invocation doesn't pay the `AppDatabase.getInstance`
     * latency. Safe to call multiple times.
     */
    fun warmup() {
        // Touching the companion's `from` is enough — the
        // `AppDatabase.getInstance` call is the only IO in
        // `WorkerDependencies.from`, and Room caches the
        // singleton internally. We don't need to keep a strong
        // reference: the next worker invocation will re-resolve
        // it through the same path.
        AppDatabase.getInstance(appContext)
    }
}
