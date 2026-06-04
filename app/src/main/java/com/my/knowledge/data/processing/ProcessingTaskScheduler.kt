package com.my.knowledge.data.processing

import android.content.Context
import android.util.Log
import androidx.work.*
import com.my.knowledge.worker.ArchiveRecommendWorker
import com.my.knowledge.worker.IngestWorker
import com.my.knowledge.worker.IngestRuntime
import com.my.knowledge.worker.LlmInspirationThreadWorker
import com.my.knowledge.worker.SummaryWorker
import com.my.knowledge.worker.TagWorker
import com.my.knowledge.worker.ThreadEvolutionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessingTaskScheduler(
    context: Context,
    /**
     * Optional debouncer that the P0-1 thread-update path delegates
     * to. When present, [scheduleThreadUpdate] becomes a fire-and-
     * forget ping into the debouncer (no WorkManager unique work,
     * no enqueue). When null, the method falls back to the legacy
     * WorkManager enqueue so the public API still works for any
     * host that hasn't wired a debouncer yet.
     */
    private val rebuildDebouncer: RebuildDebouncer? = null,
) {
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

    /**
     * P0-1: delegates to the [RebuildDebouncer] when one is wired
     * up. Falls back to the legacy `OneTimeWorkRequest` enqueue if
     * the host hasn't installed a debouncer (back-compat for tests
     * and ad-hoc callers).
     *
     * The previous WorkManager enqueue fired a SECOND `rebuildGraphForBase`
     * per ingest — once inside the orchestrator's KB write lock and
     * once again from inside the [com.my.knowledge.worker.ThreadEvolutionWorker]
     * it scheduled. Going through the debouncer collapses both
     * rebuilds into one debounced run on `Dispatchers.IO`.
     */
    fun scheduleThreadUpdate(kbId: String) {
        val debouncer = rebuildDebouncer
        if (debouncer != null) {
            debouncer.scheduleThreadEvolution(kbId)
            return
        }
        Log.d(
            "ProcessingTaskScheduler",
            "scheduleThreadUpdate($kbId) falling back to WorkManager (no debouncer wired). " +
                "P0-1 path expects DependencyProvider to install a RebuildDebouncer."
        )
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
     *                     worker 拿它去拉本条灵感全文
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
        IngestRuntime.start(appContext)

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

    suspend fun cancelIngestQueue() {
        withContext(Dispatchers.IO) {
            IngestRuntime.cancel()
            WorkManager.getInstance(appContext).cancelUniqueWork("ingest_queue").result.get()
        }
    }
}
