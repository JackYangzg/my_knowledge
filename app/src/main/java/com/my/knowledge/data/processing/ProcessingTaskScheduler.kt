package com.my.knowledge.data.processing

import android.content.Context
import androidx.work.*
import com.my.knowledge.worker.ArchiveRecommendWorker
import com.my.knowledge.worker.DistillationWorker
import com.my.knowledge.worker.IngestWorker
import com.my.knowledge.worker.IngestRuntime
import com.my.knowledge.worker.LlmInspirationThreadWorker
import com.my.knowledge.worker.NaturalLanguageGapReanalysisWorker
import com.my.knowledge.worker.NewItemGapMatchWorker
import com.my.knowledge.worker.SummaryWorker
import com.my.knowledge.worker.TagWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessingTaskScheduler(
    context: Context,
    /**
     * Optional rebuild debouncer for graph / overview / sweep rebuilds.
     * Left here so the host can plug in a single debouncer instance for
     * all rebuild types; this scheduler itself no longer touches the
     * debouncer (灵感脉络已经全量改走 LLM,不再有「程序化脉络」debounce 的需求)。
     */
    @Suppress("unused")
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
     * 灵感脉络的 LLM 更新入口。双模式:
     *   - **incremental** (默认):每新增/编辑一条灵感,NoteEditorViewModel
     *     调一次,带 [newItemId]。worker 拿这条 + 历史摘要 + 现有脉络做增量。
     *   - **re_evolve**:用户在灵感空间 / KB 详情页点「重新演化」时,ViewModel
     *     传 `mode = "re_evolve"`、`newItemId = null`,worker 改读最近 N 条灵感
     *     full content 整体重写。
     *
     * 失败时:incremental 退到本地 tag 聚类 fallback;re-evolve 在有旧脉络时保留
     * 旧脉络只写一条 log,冷启动也退到 fallback。详见
     * [LlmInspirationThreadWorker].
     *
     * @param kbId         灵感知识库 id(目前固定 type="inspiration" 或 "normal")
     * @param newItemId    incremental 模式必填;re_evolve 模式传 null
     * @param triggerType  "inspiration_added" | "inspiration_edited" | "inspiration_re_evolve"
     * @param mode         "incremental" | "re_evolve";不传时 worker 根据
     *                     `newItemId` 是否为空自动推断
     */
    fun scheduleLlmThreadUpdate(
        kbId: String,
        newItemId: String? = null,
        triggerType: String = "inspiration_added",
        mode: String? = null,
    ) {
        val workManager = WorkManager.getInstance(appContext)
        val resolvedMode = mode ?: if (newItemId.isNullOrBlank()) "re_evolve" else "incremental"
        val dataBuilder = Data.Builder()
            .putString("knowledgeBaseId", kbId)
            .putString("triggerType", triggerType)
            .putString("mode", resolvedMode)
        if (!newItemId.isNullOrBlank()) {
            dataBuilder.putString("newItemId", newItemId)
        }
        val request = OneTimeWorkRequestBuilder<LlmInspirationThreadWorker>()
            .setInputData(dataBuilder.build())
            .build()

        workManager.enqueueUniqueWork(
            "llm_inspiration_thread_$kbId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * FRAG-1.3: enqueue a distillation worker for one chain. Unique
     * work name (`distillation_<chainId>`) plus REPLACE semantics mean
     * a user re-tap on "开始提炼" collapses into the existing in-flight
     * job instead of spawning a second LLM call.
     */
    fun scheduleDistillation(chainId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<DistillationWorker>()
            .setInputData(workDataOf("chainId" to chainId))
            .build()
        workManager.enqueueUniqueWork(
            "distillation_$chainId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * FRAG-1.4 (P12): enqueue a natural-language reanalysis worker
     * for one chain. Triggered by the user typing in the detail page
     * "重新分析" text field. REPLACE policy collapses multiple taps.
     */
    fun scheduleGapReanalysis(chainId: String, userText: String) {
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<NaturalLanguageGapReanalysisWorker>()
            .setInputData(
                workDataOf(
                    "chainId" to chainId,
                    "userText" to userText,
                )
            )
            .build()
        workManager.enqueueUniqueWork(
            "gap_reanalysis_$chainId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * FRAG-1.4 (P13): enqueue a best-effort gap match worker for a
     * freshly imported item. Called from the ingest-completion path
     * (RELIAB-1 chain) and the user "重新分析" button.
     */
    fun scheduleNewItemMatch(itemId: String) {
        val workManager = WorkManager.getInstance(appContext)
        val request = OneTimeWorkRequestBuilder<NewItemGapMatchWorker>()
            .setInputData(workDataOf("itemId" to itemId))
            .build()
        workManager.enqueueUniqueWork(
            "new_item_gap_match_$itemId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleIngestQueue() {
        // P1-REL: warn the user once they're about to kick off a long
        // ingest while the app isn't on the battery-optimization
        // whitelist. OEM ROMs (MIUI / EMUI / ColorOS / OriginOS) freeze
        // background processes even when our FGS is up, so the user
        // needs to explicitly whitelist for the long LLM stages to
        // actually finish in the background.
        com.my.knowledge.ui.BatteryOptimizationPrompt.warnIfNotIgnoring(appContext)
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
