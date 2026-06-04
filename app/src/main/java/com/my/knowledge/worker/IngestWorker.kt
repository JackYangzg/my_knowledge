package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class IngestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            IngestRuntime.runOnce(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
