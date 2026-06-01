package com.my.knowledge.data.processing

import android.content.Context
import androidx.work.*
import com.my.knowledge.worker.ArchiveRecommendWorker
import com.my.knowledge.worker.IngestWorker
import com.my.knowledge.worker.SummaryWorker
import com.my.knowledge.worker.TagWorker
import com.my.knowledge.worker.ThreadEvolutionWorker

class ProcessingTaskScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun scheduleFullPipeline(itemId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val inputData = workDataOf("itemId" to itemId)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        // 1. Summarize
        val summaryRequest = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // 2. Tag (Depends on summary)
        val tagRequest = OneTimeWorkRequestBuilder<TagWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // 3. Archive Recommendation (Depends on tags)
        val archiveRequest = OneTimeWorkRequestBuilder<ArchiveRecommendWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.beginUniqueWork(
            "pipeline_$itemId",
            ExistingWorkPolicy.REPLACE,
            summaryRequest
        ).then(tagRequest)
         .then(archiveRequest)
         .enqueue()
    }

    fun scheduleThreadUpdate(kbId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<ThreadEvolutionWorker>()
            .setInputData(workDataOf("knowledgeBaseId" to kbId))
            .build()
        
        workManager.enqueueUniqueWork(
            "thread_update_$kbId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleIngestQueue() {
        val request = OneTimeWorkRequestBuilder<IngestWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "ingest_queue",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
