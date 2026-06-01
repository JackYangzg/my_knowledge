package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.ingest.IngestOrchestrator

class IngestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            IngestOrchestrator(
                db = AppDatabase.getInstance(applicationContext),
                fileStore = LocalFileStore(applicationContext)
            ).runUntilIdle()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
