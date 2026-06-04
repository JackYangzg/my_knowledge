package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.ingest.IngestOrchestrator
import com.my.knowledge.ui.DependencyProvider

class IngestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            IngestOrchestrator(
                db = AppDatabase.getInstance(applicationContext),
                fileStore = LocalFileStore(applicationContext),
                repository = DependencyProvider.provideKnowledgeRepository(applicationContext),
                scheduler = DependencyProvider.provideScheduler(applicationContext),
                // P0-1: the orchestrator hands the four post-write
                // rebuilds to the debouncer (off the KB write lock,
                // per-KB debounce, Dispatchers.IO).
                rebuildDebouncer = DependencyProvider.provideRebuildDebouncer(applicationContext),
            ).runUntilIdle()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
