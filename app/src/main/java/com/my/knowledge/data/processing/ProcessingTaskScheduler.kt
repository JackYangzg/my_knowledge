package com.my.knowledge.data.processing

import android.content.Context
import androidx.work.*
import com.my.knowledge.worker.ArchiveRecommendWorker
import com.my.knowledge.worker.IngestWorker
import com.my.knowledge.worker.LlmInspirationThreadWorker
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

    /**
     * 灵感脉络的 LLM 增量更新。每新增一条灵感,NoteEditorViewModel
     * 调一次这个方法;worker 调大模型,失败时 fallback 到程序化
     * [ThreadEvolutionWorker] 写一份占位脉络,保证 UI 不会空。
     *
     * @param kbId         灵感知识库 id(目前固定 type="inspiration")
     * @param newItemId    本次新增的 inspiration knowledge_item id,
     *                     worker 拿它去拉「本条灵感全文 + 关联到的 wiki 实体」
     * @param triggerType  "inspiration_added" | "inspiration_edited"
     */
    fun scheduleLlmThreadUpdate(
        kbId: String,
        newItemId: String,
        triggerType: String = "inspiration_added",
    ) {
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<LlmInspirationThreadWorker>()
            .setInputData(
                workDataOf(
                    "knowledgeBaseId" to kbId,
                    "newItemId" to newItemId,
                    "triggerType" to triggerType,
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            "llm_inspiration_thread_$kbId",
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
