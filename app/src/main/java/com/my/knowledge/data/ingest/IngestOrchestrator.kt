package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.AiTextCleaner
import com.my.knowledge.data.ai.AiTextCleaner.cleanModelOutput
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.parser.AudioTranscriptParser
import com.my.knowledge.data.parser.DocxParser
import com.my.knowledge.data.parser.HtmlWebParser
import com.my.knowledge.data.parser.ImageOcrParser
import com.my.knowledge.data.parser.MarkdownParser
import com.my.knowledge.data.parser.MetadataOnlyParser
import com.my.knowledge.data.parser.PdfTextParser
import com.my.knowledge.data.parser.PlainTextParser
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Structured result of Stage 1 — the LLM analysis, normalized into the
 * exact column shape [AnalysisResultEntity] expects.
 *
 * The old `analysisTask` skipped this step and wrote the raw AI text
 * into `summary` with `entitiesJson = "[]"` / `conceptsJson = tags` /
 * `relationsJson = "[]"`, which broke the wiki generation stage. This
 * class is the bridge that the new `parseAiAnalysisJson` returns so
 * the DB write at the end of `analysisTask` fills every column with
 * real data.
 *
 * Marked `internal` so the unit test in
 * `com.my.knowledge.data.ingest.ParseAiAnalysisJsonTest` can verify
 * the extraction logic without needing the full Android DB stack.
 */
internal data class ParsedAnalysis(
    val summary: String,
    val tagsJson: String,
    val entitiesJson: String,
    val conceptsJson: String,
    val relationsJson: String,
    val claimsJson: String,
    val gapsJson: String,
    val archiveRecommendationJson: String,
    val confidence: Float,
    val entityCount: Int,
    val conceptCount: Int,
    val relationCount: Int,
) {
    companion object {
        /**
         * Fallback used when the LLM is unavailable, the response is empty,
         * or the JSON fails to parse. It preserves summary/tags only and
         * leaves entities/concepts/relations empty. We deliberately do not
         * invent entities or concepts locally.
         */
        fun fromFallback(fallbackTags: List<String>, fallbackConfidence: Float): ParsedAnalysis {
            val empty = "[]"
            return ParsedAnalysis(
                summary = "",
                tagsJson = encodeTagArray(fallbackTags),
                entitiesJson = empty,
                conceptsJson = empty,
                relationsJson = empty,
                claimsJson = empty,
                gapsJson = if (fallbackConfidence < 0.6f) "[\"内容较短，建议人工确认摘要和归档\"]" else empty,
                archiveRecommendationJson = "{\"targetKnowledgeBaseId\":null,\"targetKnowledgeBaseName\":\"\",\"confidence\":$fallbackConfidence,\"reason\":\"本地规则兜底\",\"suggestCreateNewBase\":false,\"newBaseName\":null}",
                confidence = fallbackConfidence,
                entityCount = 0,
                conceptCount = 0,
                relationCount = 0,
            )
        }

        /**
         * Extract every column from a parsed analysis-JSON object.
         * Defensive: missing arrays default to `[]`, missing scalars use
         * fallback values, and entity/concept entries without a `name` are
         * dropped (the downstream
         * `parseNamedObjects` would drop them too, but we filter
         * here so the count metric is honest).
         */
        fun fromObj(
            obj: kotlinx.serialization.json.JsonObject,
            fallbackTags: List<String>,
            fallbackConfidence: Float,
            aiSucceeded: Boolean,
            parseErrorNote: String? = null,
        ): ParsedAnalysis {
            val tags = IngestJsonValidator.arrayAsJson(obj, "tags")
                .takeIf { it != "[]" }
                ?: encodeTagArray(fallbackTags)
            val entitiesRaw = IngestJsonValidator.arrayAsJson(obj, "entities")
            val conceptsRaw = IngestJsonValidator.arrayAsJson(obj, "concepts")
            val relationsRaw = IngestJsonValidator.arrayAsJson(obj, "relations")
            val claimsRaw = IngestJsonValidator.arrayAsJson(obj, "claims")
            val gapsRaw = IngestJsonValidator.arrayAsJson(obj, "gaps")
            val archiveRaw = IngestJsonValidator.archiveRecommendationJson(
                obj,
                fallback = "{\"targetKnowledgeBaseId\":null,\"targetKnowledgeBaseName\":\"\",\"confidence\":$fallbackConfidence,\"reason\":\"${if (aiSucceeded) "AI 摘要未含归档建议" else "本地规则兜底"}\",\"suggestCreateNewBase\":false,\"newBaseName\":null}"
            )
            val summary = IngestJsonValidator.string(obj, "summary", fallback = "").trim()
            val confidence = IngestJsonValidator.float(obj, "confidence", fallbackConfidence)
            val reviewReasonsRaw = IngestJsonValidator.arrayAsJson(obj, "reviewReasons")
            // If the AI said needHumanReview but didn't enumerate reasons,
            // still surface a gap so the review queue picks it up.
            val needReview = obj["needHumanReview"]?.let { it.toString().contains("true") } == true
            val gapsFinal = when {
                gapsRaw != "[]" && parseErrorNote == null -> gapsRaw
                gapsRaw != "[]" -> appendGapEntry(gapsRaw, parseErrorNote)
                reviewReasonsRaw != "[]" && parseErrorNote == null -> reviewReasonsRaw
                reviewReasonsRaw != "[]" -> appendGapEntry(reviewReasonsRaw, parseErrorNote)
                needReview && parseErrorNote != null -> "[\"${parseErrorNote.escapeForJson()}\"]"
                needReview -> "[\"模型标记需要人工复核，但未列出原因\"]"
                parseErrorNote != null -> "[\"${parseErrorNote.escapeForJson()}\"]"
                else -> "[]"
            }
            return ParsedAnalysis(
                summary = summary,
                tagsJson = tags,
                entitiesJson = sanitizeEntityArray(entitiesRaw),
                conceptsJson = sanitizeConceptArray(conceptsRaw),
                relationsJson = sanitizeRelationArray(relationsRaw),
                claimsJson = claimsRaw,
                gapsJson = gapsFinal,
                archiveRecommendationJson = archiveRaw,
                confidence = confidence,
                entityCount = countByName(entitiesRaw),
                conceptCount = countByName(conceptsRaw),
                relationCount = countByPair(relationsRaw),
            )
        }

        // The LLM occasionally returns entities / concepts / relations
        // that pass `JSONArray` parsing but would break downstream — e.g.
        // a relation with empty `source` or `target`, or a relation type
        // outside the allowed enum. We strip those here so the wiki
        // generation stage never has to deal with garbage rows. The
        // helpers are private; if you need them outside, hoist them
        // to a `object AnalysisJsonNormalizer`.
        private fun sanitizeEntityArray(raw: String): String {
            if (raw.isBlank() || raw == "[]") return "[]"
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return "[]"
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                out.put(item)
            }
            return out.toString()
        }

        private fun sanitizeConceptArray(raw: String): String {
            // Same shape, but concepts use `definition` instead of
            // `description`. Keep the filter symmetric — anything
            // without a usable `name` is dropped.
            return sanitizeEntityArray(raw)
        }

        private fun sanitizeRelationArray(raw: String): String {
            if (raw.isBlank() || raw == "[]") return "[]"
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return "[]"
            val allowedTypes = setOf("supports", "contradicts", "extends", "uses", "part_of", "related_to")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val source = item.optString("source").trim()
                val target = item.optString("target").trim()
                if (source.isBlank() || target.isBlank()) continue
                if (source.equals(target, ignoreCase = true)) continue
                val type = item.optString("type", "related_to").trim().lowercase()
                if (type !in allowedTypes) {
                    item.put("type", "related_to")
                }
                out.put(item)
            }
            return out.toString()
        }

        private fun countByName(raw: String): Int {
            if (raw.isBlank() || raw == "[]") return 0
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return 0
            var n = 0
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotBlank()) n++
            }
            return n
        }

        private fun countByPair(raw: String): Int {
            if (raw.isBlank() || raw == "[]") return 0
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return 0
            var n = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val source = obj.optString("source").trim()
                val target = obj.optString("target").trim()
                if (source.isNotBlank() && target.isNotBlank()) n++
            }
            return n
        }
    }
}

@OptIn(FlowPreview::class)
// CQ-10: implement the narrow entry-point interface so callers
// (IngestRuntime, the future WorkerFactory) can depend on
// IngestOrchestratorApi instead of the concrete 2600-line class.
class IngestOrchestrator(
    private val db: AppDatabase,
    private val fileStore: LocalFileStore,
    private val repository: KnowledgeRepository,
    private val ai: AiGateway = AiGateway(),
    private val scheduler: com.my.knowledge.data.processing.ProcessingTaskScheduler? = null,
    /**
     * P0-1: the four post-write side effects (`updateItemCount`,
     * `refreshOverviewForBase`, `rebuildGraphForBase`, `SweepReviews.sweep`,
     * thread evolution) all run on this debouncer instead of inside
     * the KB write lock. Optional for back-compat with unit tests
     * that exercise `parseAiAnalysisJson`; production wiring always
     * passes one through `DependencyProvider`.
     */
    private val rebuildDebouncer: com.my.knowledge.data.processing.RebuildDebouncer? = null,
    /**
     * P0-3: long-source checkpoint store. The orchestrator splits
     * markdown larger than [LONG_SOURCE_BUDGET_CHARS] into semantic
     * chunks, calls the LLM once per chunk, and persists progress
     * through this store so a retry resumes from `completedThrough`
     * instead of re-paying the full chunked-LLM bill.
     *
     * Optional for back-compat with unit tests that don't exercise
     * the long-source path. Production wiring (the [com.my.knowledge.worker.IngestWorker])
     * always passes one rooted at `context.filesDir`.
     */
    private val longSourceCheckpointStore: LongSourceCheckpointStore? = null,
    /**
     * P0-3: chunker used for the long-source path. Configurable
     * (rather than always-`MarkdownSemanticChunker()`) so unit
     * tests can plug in a smaller `targetChars` to drive the
     * chunked code path with a short fixture.
     */
    private val markdownChunker: MarkdownSemanticChunker = MarkdownSemanticChunker(),
) : IngestOrchestratorApi {
    private val fragmenter = MarkdownFragmenter()
    private val wikiCompiler = WikiPageCompiler()

    // P0-1: the 4-lane claim loop / idle detection / cold-start
    // recovery that used to be inlined at the top of this class now
    // lives in [IngestScheduler]. The orchestrator still owns the
    // per-task business logic (runTask + the 4 stage methods) and
    // passes them in as a function reference plus its own private
    // `enqueue` helper.
    private val ingestScheduler = IngestScheduler(
        taskDao = db.processingTaskDao(),
        sourceDao = db.sourceDocumentDao(),
        parsedContentDao = db.parsedContentDao(),
    )

    // P0-1: ingest cache fast-path (sha256 == generated sibling).
    // Was a 10-line private method on the orchestrator; lifted to
    // its own class so the cache hit policy is testable in
    // isolation and the orchestrator shrinks another 10 lines.
    // ARCH-6 follow-up (cache key includes promptVersion) is
    // tracked separately — it needs a v10→v11 schema migration.
    private val ingestCache = IngestCache(sourceDao = db.sourceDocumentDao())

    // P0-1: state machine for the source_document.status / linked
    // knowledge_item.status flips. Lifted from inlined
    // `sourceDocumentDao().updateStatus(...)` calls in every stage
    // method; now every transition goes through one chokepoint so
    // the cross-table writes (source + linked item) never drift
    // apart.
    private val ingestStateMachine = IngestStateMachine(
        sourceDao = db.sourceDocumentDao(),
        itemDao = db.knowledgeItemDao(),
    )

    /**
     * P0-2: tracks the [Job] of the most recently entered [runTask]
     * call. The "User pressed Stop Processing" / IngestWorker stop
     * signal calls [cancel], which forwards the cancel to whichever
     * lane is currently inside an LLM stream. The downstream
     * `ai.streamJson` / `ai.completeStream` cooperative-cancel hook
     * (`currentCoroutineContext().ensureActive()` per SSE line) then
     * tears the HTTP connection down on the very next read instead
     * of waiting out the HTTP `readTimeout`.
     *
     * **Scope note:** [runUntilIdle] runs up to 4 parallel lanes.
     * Last-write-wins means [cancel] only stops the most recent lane
     * to enter [runTask]. That's the documented contract from the
     * P0-2 spec; in practice only one lane is doing the long-running
     * Stage-1/Stage-2 LLM call at a time (the others are either
     * waiting on the DB or in fast parse/embedding steps), so
     * "press Stop" still stops the user-visible hang.
     *
     * Thread safety: the field is mutated only from
     * `runUntilIdle`'s lane coroutines (a single dispatcher at a
     * time, post-P0-1 we always run under `Dispatchers.IO` from the
     * worker), so a plain `var` is sufficient. The [cancel] entry
     * point is called from the main thread / worker dispatch UI,
     * which is the only cross-thread reader/writer — `@Volatile`
     * would technically be required if the worker dispatch path
     * is on a different thread from the lane coroutines, but
     * single-writer / single-reader volatile-ish access is
     * acceptable here because the cancel itself is idempotent
     * (calling cancel on an already-completed Job is a no-op).
     */
    private var currentJob: Job? = null

    /**
     * P0-1 stage split: `taskType` -> [Stage]. The dispatch site
     * (`runTask`) replaces the old `when (task.taskType)` with a
     * single map lookup. Adding a new step means a new entry here
     * + a new `runXxxTask` method on this class, not a sweeping
     * edit of the dispatch chain.
     */
    private val stages: Map<String, Stage> = mapOf(
        "parse" to ParseStage(),
        "analysis" to AnalysisStage(),
        "generation" to GenerationStage(),
        "embedding" to EmbeddingStage(),
    )

    /**
     * P0-2: stop the currently running ingest step. Designed to be
     * wired to the log-center "Stop" button and to
     * [com.my.knowledge.worker.IngestWorker]'s cancel path.
     *
     * Idempotent — safe to call when no [runTask] is in flight, when
     * the in-flight task is already finishing, or after a previous
     * cancel. Each in-flight LLM stream observes the cancel at the
     * next SSE line read and throws [CancellationException]; the
     * orchestrator's `try { ... } catch (e: Exception)` in [runTask]
     * then routes the failure to the task's retry path.
     */
    override fun cancel() {
        currentJob?.cancel()
    }

    /**
     * Mirrors llm_wiki's project mutex, but narrows the lock to the
     * actual wiki page key. Analysis / generation can run concurrently;
     * only the read-merge-write of the same logical file is serialized.
     */
    private suspend fun <T> withWikiPageWriteLocks(
        kbId: String,
        drafts: List<WikiPageDraft>,
        block: suspend () -> T
    ): T {
        val keys = drafts
            .map { draft -> wikiPageLockKey(kbId, draft.sourceType, draft.title) }
            .distinct()
            .sorted()
        return withLocks(keys, 0, block)
    }

    private suspend fun <T> withLocks(
        keys: List<String>,
        index: Int,
        block: suspend () -> T
    ): T {
        if (index >= keys.size) return block()
        val mutex = pageWriteMutexes.getOrPut(keys[index]) { Mutex() }
        return mutex.withLock { withLocks(keys, index + 1, block) }
    }

    // P0-1: the 4-lane claim loop, idle detection, and cold-start
    // recovery moved to [IngestScheduler]. The orchestrator keeps
    // `runTask` (per-task business logic) and `currentJob` (LLM
    // stream cancel) and hands them in as a function reference.
    override suspend fun runUntilIdle(maxTasks: Int, parallelism: Int) {
        ingestScheduler.runUntilIdle(
            maxTasks = maxTasks,
            parallelism = parallelism,
            runTask = ::runTask,
            enqueueFn = ::enqueue,
        )
    }

    private suspend fun runTask(task: ProcessingTaskEntity): Boolean {
        val startedAt = System.currentTimeMillis()
        // P0-2: pin the current coroutine's Job so [cancel] can stop
        // the in-flight LLM stream. Done at the very top of runTask
        // — before any DB write or parser dispatch — so even a
        // cancel-during-parse propagates correctly. Cleared in
        // `finally` so a completed task doesn't leave a stale Job
        // reference lying around.
        val job = currentCoroutineContext()[Job]
        currentJob = job
        try {
            // ---- Ingest cache fast-path (1:1 with llm_wiki) ----------
            //
            // If a previous source with the same sha256 already made it
            // all the way to STATUS_GENERATED, we treat the whole pipeline
            // as a cache hit: every step the user is queuing (parse,
            // analysis, generation) is skipped and we just enqueue the
            // embedding task that we still need to (re)run for the
            // brand-new source row. Saves a full Stage 1 + Stage 2 LLM
            // call on the second-and-later import of the same file.
            if (task.taskType in setOf("parse", "analysis", "generation") && ingestCache.isHit(task)) {
                val now = System.currentTimeMillis()
                ingestStateMachine.transitionToGenerated(task.sourceId ?: task.targetId, now)
                markSuccess(
                    task,
                    "Cache hit (sha256 already ingested) — skipped to embedding",
                    "{}"
                )
                enqueue(
                    task.sourceId ?: task.targetId,
                    "embedding",
                    5,
                    "{}"
                )
                return true
            }
            val stage = stages[task.taskType]
            if (stage != null) {
                stage.run(task, this)
            } else {
                markSuccess(task, "Unsupported task skipped", "{}")
            }
            return true
        } catch (e: Exception) {
            val retry = task.retryCount + 1
            val now = System.currentTimeMillis()
            val failImmediately = shouldFailImmediately(task, e)
            val willRetry = !failImmediately && retry < task.maxRetry
            db.processingTaskDao().update(
                task.copy(
                    status = if (willRetry) "pending" else "failed",
                    retryCount = retry,
                    errorMessage = e.message,
                    updatedAt = now,
                    finishedAt = if (willRetry) null else now
                )
            )
            appendLog(
                task,
                if (willRetry) {
                    "${taskLabel(task.taskType)}请求失败，已进入任务重试 ${retry}/${task.maxRetry}：${e.message ?: "未知错误"}"
                } else {
                    "${taskLabel(task.taskType)}失败：${e.message ?: "未知错误"}"
                },
                if (willRetry) "pending" else "failed"
            )
            task.sourceId?.let {
                if (!willRetry) {
                    ingestStateMachine.transitionToFailed(it, e.message, now)
                }
            }
            return false
        } finally {
            // P0-2: only clear the Job reference if it still points
            // at *this* runTask's coroutine. A nested runTask call
            // (e.g. test code driving a sub-task) would otherwise
            // clobber the outer's reference. Identity check
            // (`currentCoroutineContext()[Job] == currentJob`) keeps
            // that nested case correct.
            if (currentCoroutineContext()[Job] == currentJob) {
                currentJob = null
            }
        }
    }

    private suspend fun shouldFailImmediately(task: ProcessingTaskEntity, error: Exception): Boolean {
        if (task.taskType != "parse") return false
        if (error.message.isRetryableAiOrNetworkFailure()) return false
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: return false
        return source.sourceType == "image" || source.mimeType?.startsWith("image/") == true
    }

    private fun String?.isRetryableAiOrNetworkFailure(): Boolean {
        val value = this?.lowercase().orEmpty()
        return listOf(
            "dns",
            "unable to resolve",
            "连接失败",
            "failed to connect",
            "connection reset",
            "connection abort",
            "connection aborted",
            "connection refused",
            "software caused connection abort",
            "ssl",
            "超时",
            "timeout",
            "timed out",
            "ai 调用",
            "ai调用",
            "http 5"
        ).any { value.contains(it) }
    }

    private fun ingestParsers() = listOf(
        MarkdownParser(),
        ImageOcrParser(),
        PdfTextParser(),
        DocxParser(),
        HtmlWebParser(),
        AudioTranscriptParser(),
        PlainTextParser(),
        MetadataOnlyParser()
    )

    internal suspend fun runParseTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        ingestStateMachine.transitionToParsing(source.id)
        updateProgress(task, 15, "解析文件 ${source.title}", "正在解析 ${source.mimeType ?: source.sourceType} 内容")

        val parser = ingestParsers().first { parser -> parser.supports(source.mimeType, source.sourceType) }
        val parsed = parser.parse(source)
        updateProgress(task, 50, "写入解析结果", "已抽取 ${parsed.plainText.length} 字正文，准备切片")
        val now = System.currentTimeMillis()
        val parsedEntity = ParsedContentEntity(
            id = UUID.randomUUID().toString(),
            sourceId = source.id,
            parserType = parsed.parserType,
            markdown = parsed.markdown,
            plainText = parsed.plainText,
            parseHash = fileStore.sha256Text(parsed.markdown),
            metadataJson = parsed.metadataJson,
            createdAt = now,
            updatedAt = now
        )
        db.parsedContentDao().insert(parsedEntity)
        fileStore.writeParsedMarkdown(source.id, parsed.markdown)
        fileStore.writeParsedMetadata(source.id, parsed.metadataJson)
        syncVisibleKnowledgeItemAfterParse(source.id, parsed.markdown, parsed.plainText, now)
        val fragments = fragmenter.split(parsedEntity, source.targetKnowledgeBaseId.orEmpty())
        db.knowledgeFragmentDao().insertAll(fragments)
        updateProgress(task, 80, "生成 ${fragments.size} 个知识切片", "切片完成，准备进入分析阶段")
        ingestStateMachine.transitionToParsed(source.id, now)
        markSuccess(task, "Parsed ${source.title}", """{"parsedContentId":"${parsedEntity.id}"}""")
        enqueue(source.id, "analysis", 9, """{"parsedContentId":"${parsedEntity.id}"}""")
    }

    private suspend fun syncVisibleKnowledgeItemAfterParse(
        sourceId: String,
        markdown: String,
        plainText: String,
        updatedAt: Long
    ) {
        val visibleMarkdown = markdown.trim().takeIf { it.isNotBlank() } ?: return
        val excerpt = plainText.trim()
            .replace(Regex("\\s+"), " ")
            .take(240)
            .ifBlank { "已解析文本内容" }
        db.knowledgeItemDao().updateVisibleParsedContentBySourceId(
            sourceId = sourceId,
            contentMarkdown = visibleMarkdown,
            excerpt = excerpt,
            updatedAt = updatedAt
        )
    }

    internal suspend fun runAnalysisTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        val parsed = db.parsedContentDao().getLatestBySource(source.id) ?: error("Parsed content not found")
        ingestStateMachine.transitionToAnalyzing(source.id)
        updateProgress(task, 20, "加载 ${parsed.plainText.length} 字解析结果", "正在汇总标签与摘要")

        if (!ai.isAvailable()) {
            val now = System.currentTimeMillis()
            db.processingTaskDao().update(
                task.copy(
                    status = "pending_config",
                    progress = 35,
                    currentStep = "等待模型配置",
                    errorMessage = "请先在设置中配置模型 API Key",
                    updatedAt = now,
                    finishedAt = null
                )
            )
            ingestStateMachine.transitionToImportedWaitingForConfig(source.id, "请先在设置中配置模型 API Key", now)
            appendLog(task, "等待模型 API Key 配置后重试", "pending_config", "等待模型配置")
            return
        }

        val localSummary = parsed.plainText.trim().take(220)
        val tags = extractTags("${source.title} ${parsed.plainText}")
        val baseConfidence = if (parsed.plainText.length > 80) 0.78f else 0.42f
        updateProgress(task, 45, "调用 AI 生成结构化分析", "模型生成中，等待 JSON 结果")
        // Stage 1 — call the LLM with the JSON-only analysis prompt, then
        // PARSE the result into structured entities / concepts / relations.
        // The previous code dropped the AI output into `summary` and hard-
        // coded `entitiesJson = "[]"`, `conceptsJson = tags.toJsonArray()`,
        // `relationsJson = "[]"`, which meant no real entities, tag-named
        // "concept" pages with empty descriptions, and an empty knowledge
        // graph. parseAiAnalysisJson is the bridge that fixes that.
        //
        // P0-3: long-source path. Sources larger than
        // [LONG_SOURCE_BUDGET_CHARS] used to be silently truncated by
        // `parsed.markdown.take(50_000)` (the 50K cap inside
        // `requestAiAnalysis` below). That truncation dropped late
        // entities / concepts and broke graph + wiki completeness
        // for any import over a few chapters. We now route
        // `parsed.markdown.length > sourceBudget` through
        // `requestAiAnalysisLongSource` — semantic chunking, one LLM
        // call per chunk via the existing `chatJson` helper, with
        // progress persisted to [longSourceCheckpointStore] so a
        // retry resumes from the last completed chunk instead of
        // re-paying the full bill. Short sources keep the original
        // single-call path.
        val isLongSource = parsed.markdown.length > LONG_SOURCE_BUDGET_CHARS &&
            longSourceCheckpointStore != null
        appendLog(
            task,
            "诊断:analysis 输入 title=${source.title}, markdown=${parsed.markdown.length} 字符, plainText=${parsed.plainText.length} 字符, mode=${if (isLongSource) "chunked" else "non_stream"}",
            "running",
            "调用 AI 生成结构化分析"
        )
        val rawAiOutput: String? = if (isLongSource) {
            requestAiAnalysisLongSource(task, source, parsed)
        } else {
            requestAiAnalysis(task, source, parsed)
                ?.takeIf { it.isNotBlank() && !it.startsWith("[") }
        }
        // P1 诊断:把 LLM 实际返回写到 ProcessingTaskLog,这样用户能
        // 直接看到「AI 返回了 N 字符 / 0 个实体」,不用盲猜为什么图谱空。
        val rawSnippet = rawAiOutput?.take(200)?.replace("\n", " ")
        if (rawAiOutput.isNullOrBlank()) {
            appendLog(task, "诊断:AI 阶段未返回任何内容(可能未配置 API Key / 网络异常 / JSON 解析失败)", "running")
        } else {
            appendLog(
                task,
                "诊断:AI 返回 ${rawAiOutput.length} 字符,前 200 字符: $rawSnippet",
                "running"
            )
        }
        val parsedAnalysis = parseAiAnalysisJson(
            raw = rawAiOutput,
            fallbackTitle = source.title,
            fallbackSummary = localSummary,
            fallbackTags = tags,
            fallbackConfidence = baseConfidence,
        )
        updateProgress(task, 70, "分析完成", "识别 ${parsedAnalysis.entityCount} 个实体 / ${parsedAnalysis.conceptCount} 个概念 / ${parsedAnalysis.relationCount} 个关系")
        appendLog(
            task,
            "诊断:解析后 entities=${parsedAnalysis.entityCount}, concepts=${parsedAnalysis.conceptCount}, relations=${parsedAnalysis.relationCount}, confidence=${parsedAnalysis.confidence}",
            "running"
        )
        val finalEntitiesJson = parsedAnalysis.entitiesJson
        val finalConceptsJson = parsedAnalysis.conceptsJson
        if (parsedAnalysis.entityCount == 0 || parsedAnalysis.conceptCount == 0) {
            appendLog(
                task,
                "AI 抽取结果保留原样：entities=${parsedAnalysis.entityCount}, concepts=${parsedAnalysis.conceptCount}；未使用本地启发式补集",
                "running"
            )
        }
        val analysis = AnalysisResultEntity(
            id = UUID.randomUUID().toString(),
            sourceId = source.id,
            parsedContentId = parsed.id,
            summary = parsedAnalysis.summary.take(3000),
            tagsJson = parsedAnalysis.tagsJson,
            entitiesJson = finalEntitiesJson,
            conceptsJson = finalConceptsJson,
            relationsJson = parsedAnalysis.relationsJson,
            claimsJson = parsedAnalysis.claimsJson,
            gapsJson = parsedAnalysis.gapsJson,
            archiveRecommendationJson = parsedAnalysis.archiveRecommendationJson,
            confidence = parsedAnalysis.confidence,
            modelName = if (rawAiOutput != null) "configured-ai" else null,
            promptVersion = PromptVersions.INGEST_ANALYSIS_V1,
            analysisHash = fileStore.sha256Text(parsed.parseHash + parsedAnalysis.tagsJson + finalEntitiesJson + finalConceptsJson + parsedAnalysis.relationsJson),
            createdAt = System.currentTimeMillis()
        )
        db.analysisResultDao().insert(analysis)
        updateProgress(task, 80, "分析完成，准备生成知识页面", "实体 ${JSONArray(finalEntitiesJson).length()} / 概念 ${JSONArray(finalConceptsJson).length()} / 关系 ${parsedAnalysis.relationCount}")
        markSuccess(task, "Analysis completed", """{"analysisResultId":"${analysis.id}"}""")
        enqueue(source.id, "generation", 8, """{"analysisResultId":"${analysis.id}"}""")
    }

    internal suspend fun runGenerationTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        val parsed = db.parsedContentDao().getLatestBySource(source.id) ?: error("Parsed content not found")
        val analysis = db.analysisResultDao().getLatestBySource(source.id) ?: error("Analysis result not found")
        val kbId = source.targetKnowledgeBaseId ?: db.knowledgeBaseDao().getByType("inspiration")?.id ?: db.knowledgeBaseDao().getByType("unfiled")?.id.orEmpty()
        val now = System.currentTimeMillis()
        updateProgress(task, 15, "准备写入根知识", "知识库：${db.knowledgeBaseDao().getById(kbId)?.name ?: "未归档"}")

        // Root item for the source itself
        val rootExistingItem = db.knowledgeItemDao().getBySourceId(source.id)
        val rootItem = KnowledgeItemEntity(
            id = rootExistingItem?.id ?: UUID.randomUUID().toString(),
            sourceId = source.id,
            knowledgeBaseId = kbId,
            title = source.title,
            contentMarkdown = parsed.markdown,
            excerpt = analysis.summary.take(120),
            sourceType = source.sourceType,
            status = if (analysis.confidence < 0.6f) KnowledgeItemEntity.STATUS_NEED_REVIEW else KnowledgeItemEntity.STATUS_ARCHIVED,
            contentHash = source.sha256,
            sourceTraceJson = """{"sourceId":"${source.id}","parsedContentId":"${parsed.id}","localPath":"${source.localPath.orEmpty().escapeJson()}"}""",
            confidence = analysis.confidence,
            summary = analysis.summary,
            tagsJson = analysis.tagsJson,
            rawNoteId = null,
            importance = 2,
            createdAt = rootExistingItem?.createdAt ?: now,
            updatedAt = now,
            processedAt = now,
            archivedAt = now,
            deletedAt = null
        )
        db.knowledgeItemDao().insert(rootItem)
        db.knowledgeFragmentDao().attachSourceFragmentsToItem(source.id, rootItem.id, kbId)
        updateProgress(task, 30, "根知识写入完成", "开始生成实体 / 概念页面")

        // Generate wiki pages (Step 2: AI-driven or Template-driven) — LLM outside KB lock
        val aiOutput = if (ai.isAvailable()) {
            updateProgress(task, 50, "调用 AI 生成 wiki 页面", "等模型返回 FILE 块")
            requestAiRawOutput(task, source, parsed, analysis)
        } else {
            updateProgress(task, 50, "使用模板生成 wiki 页面", "未配置 AI Key，走本地模板")
            null
        }

        val templatePages = wikiCompiler.compile(source, parsed, analysis)
        val aiDrafts: List<WikiPageDraft> = if (aiOutput != null) {
            val parsedBlocks = FileBlockParser.parseDetailed(aiOutput)
            if (parsedBlocks.unsafePaths.isNotEmpty() || parsedBlocks.truncated) {
                // Don't fail the whole task: skip unsafe blocks, keep
                // safe ones, and surface the issue via a review item.
            }
            parsedBlocks.blocks.map { block -> block.toWikiPageDraft(source, parsed, analysis) }
        } else {
            emptyList()
        }
        val pageDrafts: List<WikiPageDraft> = preferAiFileBlocks(
            aiDrafts = aiDrafts,
            templatePages = templatePages
        )

        updateProgress(task, 55, "合并并写入 wiki 页面", "共 ${pageDrafts.size} 页，等待页面写锁")

        // Same rule as llm_wiki: model work stays parallel; only the
        // read-merge-write for identical wiki files is serialized.
        // Android stores wiki pages as rows, so the logical file key is
        // (knowledgeBaseId, sourceType, title).
        val writtenItems = withWikiPageWriteLocks(kbId, pageDrafts) {
            val items = pageDrafts.mapIndexed { index, draft ->
                val existingPage = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)
                val mergedMarkdown = mergeWikiPageMarkdown(
                    existingMarkdown = existingPage?.contentMarkdown.orEmpty(),
                    draft = draft,
                )
                if (index % 3 == 0) {
                    updateProgress(
                        task,
                        60 + (index * 5 / pageDrafts.size.coerceAtLeast(1)),
                        "写入 wiki 页面 ${index + 1}/${pageDrafts.size}",
                        "已合并 ${draft.title}"
                    )
                }

                val item = KnowledgeItemEntity(
                    id = existingPage?.id ?: UUID.randomUUID().toString(),
                    sourceId = source.id,
                    knowledgeBaseId = kbId,
                    title = draft.title,
                    contentMarkdown = mergedMarkdown,
                    excerpt = draft.summary.take(120),
                    sourceType = draft.sourceType,
                    status = KnowledgeItemEntity.STATUS_ARCHIVED,
                    contentHash = fileStore.sha256Text(mergedMarkdown),
                    sourceTraceJson = draft.sourceTraceJson,
                    confidence = analysis.confidence,
                    summary = draft.summary,
                    tagsJson = draft.tagsJson,
                    rawNoteId = null,
                    importance = if (index == 0) 2 else 1,
                    createdAt = existingPage?.createdAt ?: now,
                    updatedAt = now,
                    processedAt = now,
                    archivedAt = now,
                    deletedAt = null
                )
                db.knowledgeItemDao().insert(item)
                item
            }
            items
        }
        updateProgress(
            task,
            90,
            "wiki 页面写入完成",
            "实体 ${writtenItems.count { it.sourceType == "wiki_entity" }}，概念 ${writtenItems.count { it.sourceType == "wiki_concept" }} — 触发异步图谱重建"
        )
        schedulePostGenerationRebuilds(kbId)


        if (aiOutput != null) {
            val reviews = ReviewBlockParser.parse(aiOutput)
            reviews.forEach { review ->
                db.reviewItemDao().insert(
                    ReviewItemEntity(
                        id = UUID.randomUUID().toString(),
                        sourceId = source.id,
                        itemId = null,
                        type = review.type,
                        title = review.title,
                        description = review.description,
                        payloadJson = """{"affectedPages":${review.affectedPages.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" }}}""",
                        suggestedActionsJson = review.options.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" },
                        status = ReviewItemEntity.STATUS_PENDING,
                        createdAt = now,
                        resolvedAt = null
                    )
                )
            }
        }


        val reviewReason = IngestJsonValidator.firstJsonArrayText(analysis.gapsJson)
        if (analysis.confidence < 0.6f || reviewReason != null) {
            db.reviewItemDao().insert(
                ReviewItemEntity(
                    id = UUID.randomUUID().toString(),
                    sourceId = source.id,
                    itemId = rootItem.id,
                    type = "low_confidence",
                    title = "需要确认：${source.title}",
                    description = reviewReason ?: "本地分析置信度较低，请确认摘要、标签和归档位置。",
                    payloadJson = """{"analysisResultId":"${analysis.id}","confidence":${analysis.confidence}}""",
                    suggestedActionsJson = """["accept","edit","skip"]""",
                    status = ReviewItemEntity.STATUS_PENDING,
                    createdAt = now,
                    resolvedAt = null
                )
            )
        }
        ingestStateMachine.transitionToGenerated(source.id, now)
        markSuccess(task, "Generated ${writtenItems.size} processed wiki pages", """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
        enqueue(source.id, "embedding", 5, """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
        // Recompute the knowledge base's mainline / gaps / suggestions
        // whenever a new generation lands. P0-1: the inline log row
        // stays (cheap, just a breadcrumb) but the actual evolution
        // goes through the debouncer instead of `scheduler?.scheduleThreadUpdate`.
        // The debouncer coalesces 5+ rapid ingests into a single
        // rebuild per KB and runs the work off the hot path.
        if (kbId.isNotBlank()) {
            db.knowledgeBaseDao().getById(kbId)?.let { base ->
                if (base.type != "unfiled") {
                    db.knowledgeThreadLogDao().insert(
                        com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity(
                            id = UUID.randomUUID().toString(),
                            threadId = "pending",
                            triggerType = "ingest_complete",
                            triggerId = source.id,
                            beforeHash = null,
                            afterHash = null,
                            summary = "源 ${source.title} 加工完成，触发脉络更新",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    val debouncer = rebuildDebouncer
                    if (debouncer != null) {
                        debouncer.scheduleThreadEvolution(kbId)
                    } else {
                        // Back-compat: no debouncer wired → fall
                        // through to the scheduler (which itself now
                        // delegates to the debouncer when one is
                        // present, so this is mostly for the unit
                        // tests).
                        scheduler?.scheduleThreadUpdate(kbId)
                    }
                }
            }
        }
    }

    /**
     * P0-1: hand off the four post-write rebuilds to the
     * [com.my.knowledge.data.processing.RebuildDebouncer] so they
     * run off the KB write lock, on `Dispatchers.IO`, coalesced per
     * KB. When no debouncer is wired (legacy callers, unit tests
     * that don't exercise the full path) we fall back to the
     * pre-P0-1 in-line behaviour to keep the public contract.
     *
     * Order:
     *   1. `updateItemCount` — quick, no debounce.
     *   2. `scheduleOverviewRefresh` — 1s debounce.
     *   3. `scheduleGraphRebuild` — 1s debounce.
     *   4. `scheduleSweepReviews` — 3s debounce, off the hot path.
     */
    private suspend fun schedulePostGenerationRebuilds(kbId: String) {
        if (kbId.isBlank()) return
        val debouncer = rebuildDebouncer
        if (debouncer == null) {
            // Legacy path: do the work inline (and accept the
            // pre-P0-1 latency cost). Only hit by callers that
            // construct an IngestOrchestrator without a debouncer
            // (i.e. unit tests, future ad-hoc tooling).
            db.knowledgeItemDao().updateItemCount(kbId)
            repository.refreshOverviewForBase(kbId)
            repository.rebuildGraphForBase(kbId)
            SweepReviews(db).sweep(kbId)
            return
        }
        // 1. Item count is cheap and idempotent — fire immediately
        //    on the caller's dispatcher (no debounce). It does
        //    NOT take the KB write lock, so this is safe to do
        //    right after the lock releases.
        db.knowledgeItemDao().updateItemCount(kbId)
        // 2-4. Hand off to the debouncer.
        debouncer.scheduleOverviewRefresh(kbId)
        debouncer.scheduleGraphRebuild(kbId)
        debouncer.scheduleSweepReviews(kbId)
    }

    private fun preferAiFileBlocks(
        aiDrafts: List<WikiPageDraft>,
        templatePages: List<WikiPageDraft>
    ): List<WikiPageDraft> {
        if (aiDrafts.isEmpty()) return templatePages
        val seen = linkedSetOf<String>()
        val out = mutableListOf<WikiPageDraft>()
        fun key(page: WikiPageDraft): String = "${page.sourceType}:${page.title.trim().lowercase()}"
        aiDrafts.forEach { draft ->
            val k = key(draft)
            if (seen.add(k)) out += draft
        }
        templatePages.forEach { template ->
            val k = key(template)
            if (seen.add(k)) out += template
        }
        return out
    }

    internal suspend fun runEmbeddingTask(task: ProcessingTaskEntity) {
        // Fragment embeddings are already maintained by repository rebuilds; this task keeps the queue explicit.
        markSuccess(task, "Embedding task acknowledged", "{}")
    }

    private fun FileBlockParser.ParsedBlock.toWikiPageDraft(
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        analysis: AnalysisResultEntity
    ): WikiPageDraft {
        val cleaned = sanitizeIngestedFileContent(content)
        val type = frontMatterValue(cleaned, "type") ?: when {
            path.startsWith("wiki/entities/") || path.contains("/entities/") -> "entity"
            path.startsWith("wiki/concepts/") || path.contains("/concepts/") -> "concept"
            path.startsWith("wiki/sources/") || path.contains("/sources/") -> "source"
            path.startsWith("wiki/papers/") || path.contains("/papers/") -> "paper"
            path.startsWith("wiki/methods/") || path.contains("/methods/") -> "method"
            path.startsWith("wiki/queries/") || path.contains("/queries/") -> "query"
            path.startsWith("wiki/comparisons/") || path.contains("/comparisons/") -> "comparison"
            path.startsWith("wiki/synthesis/") || path.contains("/synthesis/") -> "synthesis"
            else -> "synthesis"
        }
        val sourceType = when {
            path.startsWith("wiki/entities/") || path.contains("/entities/") -> "wiki_entity"
            path.startsWith("wiki/concepts/") || path.contains("/concepts/") -> "wiki_concept"
            path.startsWith("wiki/papers/") || path.contains("/papers/") -> "wiki_paper"
            path.startsWith("wiki/methods/") || path.contains("/methods/") -> "wiki_method"
            path.startsWith("wiki/queries/") || path.contains("/queries/") -> "wiki_query"
            path.startsWith("wiki/comparisons/") || path.contains("/comparisons/") -> "wiki_comparison"
            path.startsWith("wiki/synthesis/") || path.contains("/synthesis/") -> "wiki_synthesis"
            path.endsWith("/index.md") || path == "wiki/index.md" -> "wiki_index"
            path.endsWith("/overview.md") || path == "wiki/overview.md" -> "wiki_overview"
            path.endsWith("/log.md") || path == "wiki/log.md" -> "wiki_log"
            path.startsWith("wiki/sources/") || path.contains("/sources/") -> "wiki_source"
            else -> "wiki_ai_generated"
        }
        val title = when {
            path.endsWith("/index.md") || path == "wiki/index.md" -> "index.md"
            path.endsWith("/overview.md") || path == "wiki/overview.md" -> "overview.md"
            path.endsWith("/log.md") || path == "wiki/log.md" -> "log.md"
            else -> frontMatterValue(cleaned, "title")
                ?: path.substringAfterLast("/").removeSuffix(".md")
        }
        return WikiPageDraft(
            type = type,
            title = title,
            sourceType = sourceType,
            markdown = cleaned,
            summary = stripFrontMatter(cleaned).take(240).ifBlank { analysis.summary.take(240) },
            tagsJson = analysis.tagsJson,
            sourceTraceJson = """{"wikiPath":"${path.escapeJson()}","sourceId":"${source.id}","parsedContentId":"${parsed.id}","analysisResultId":"${analysis.id}"}"""
        )
    }

    /**
     * P0-2: throttle a [Flow] of SSE delta strings into at most one
     * `updateProgress` write per 500ms, gated by "every N chunks
     * received" first. The pattern:
     *
     *   1. Wrap the consumer-side flow in a [MutableSharedFlow] of
     *      progress signals (the char count after the new chunk
     *      landed). The signal goes out on every chunk; throttling
     *      happens at the writer side.
     *   2. A child coroutine `launch { signals.sample(500.ms).collect { ... } }`
     *      consumes the signal flow and calls [updateProgress] — at
     *      most once per sample window.
     *   3. The main coroutine `source.collect { ... }` keeps
     *      accumulating, untouched by the throttle.
     *
     * The child coroutine runs under [supervisorScope] so an
     * `updateProgress` DB failure doesn't take down the streaming
     * collection. Cancellation of the parent (via [cancel]) tears
     * down both the child and the SSE reader on the next line.
     *
     * @param everyN  the spec's "每收到 N token 调一次" gate. We use
     *   chunk-character-count modulo N — N=20 is a reasonable
     *   sweet spot for "many cheap ticks, few expensive DB writes".
     *   The actual filter is `sb.length % everyN == 0`, so on small
     *   outputs (< N chars) the very last progress signal still
     *   gets through (sample() flushes the trailing value when the
     *   upstream completes).
     * @param sampleMs  the spec's "500ms 内只写 1 次 DB" window.
     */
    private suspend fun collectWithThrottledProgress(
        source: Flow<String>,
        task: ProcessingTaskEntity,
        step: String,
        logMessage: (Int) -> String,
        everyN: Int = PROGRESS_EVERY_N_TOKENS,
        sampleMs: Long = PROGRESS_SAMPLE_MS,
    ): String = supervisorScope {
        val accumulator = StringBuilder()
        val throttler = SseProgressThrottler(
            writeProgress = { count -> updateProgress(task, 50, step, logMessage(count)) },
            everyN = everyN,
            sampleMs = sampleMs,
        )
        appendLog(task, "诊断:开始流式模型输出，progressEvery=$everyN, sampleMs=$sampleMs", "running", step)
        try {
            source.collect { chunk ->
                accumulator.append(chunk)
                throttler.observe(accumulator.length)
            }
            throttler.flush(accumulator.length)
            appendLog(task, "诊断:流式模型输出完成，累计接收 ${accumulator.length} 字符", "running", step)
        } catch (e: CancellationException) {
            appendLog(task, "诊断:流式模型输出被取消：${e.message ?: "无附加信息"}", "running", step)
            throw e
        } catch (t: Throwable) {
            appendLog(task, "诊断:流式模型输出异常：${t::class.simpleName ?: "Throwable"} ${t.message ?: "无错误信息"}", "running", step)
            throw t
        } finally {
            throttler.close()
        }
        accumulator.toString()
    }

    /**
     * P0-2: convenience wrapper around [ai.streamJson] that wires
     * the per-chunk `onChunk` callback into the throttled progress
     * writer from [collectWithThrottledProgress]. The gateway owns
     * the accumulation + cleaning; we just observe chunks as they
     * land and rate-limit our DB writes.
     *
     * This is the analysis-task counterpart to
     * [collectWithThrottledProgress] (which operates on a
     * `Flow<String>`). They share the same throttling constants
     * (PROGRESS_EVERY_N_TOKENS / PROGRESS_SAMPLE_MS) so a single
     * rate limit is enforced across the whole pipeline.
     *
     * Implementation note: we DON'T collect the chunks into a
     * local accumulator here — that would duplicate the gateway's
     * own accumulation. Instead we let the gateway build the full
     * text and we just listen to the onChunk side-channel for the
     * every-N-tokens gate.
     */
    private suspend fun streamJsonWithThrottledProgress(
        systemPrompt: String,
        userPrompt: String,
        schemaHint: String,
        temperature: Float,
        task: ProcessingTaskEntity,
        step: String,
        logMessage: (Int) -> String,
        onRetry: suspend (com.my.knowledge.data.ai.AiRetryEvent) -> Unit = {},
    ): String {
        val throttler = SseProgressThrottler(
            writeProgress = { count -> updateProgress(task, 50, step, logMessage(count)) },
            everyN = PROGRESS_EVERY_N_TOKENS,
            sampleMs = PROGRESS_SAMPLE_MS,
        )
        appendLog(
            task,
            "诊断:开始请求流式 JSON，systemPrompt=${systemPrompt.length} 字符, userPrompt=${userPrompt.length} 字符, schema=${schemaHint.length} 字符, readTimeout=${AI_READ_TIMEOUT_MS}ms",
            "running",
            step
        )
        return try {
            val result = ai.streamJsonObserved(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                schemaHint = schemaHint,
                temperature = temperature,
                maxAttempts = INGEST_AI_REMOTE_ATTEMPTS,
                onRetry = onRetry,
                onChunk = { delta -> throttler.observeDelta(delta.length) },
            )
            throttler.flush(throttler.totalCountForFlush())
            appendLog(
                task,
                "诊断:流式 JSON 请求完成，累计接收 ${throttler.totalCountForFlush()} 字符，清洗后 ${result.length} 字符",
                "running",
                step
            )
            result
        } catch (e: CancellationException) {
            appendLog(task, "诊断:流式 JSON 请求被取消：${e.message ?: "无附加信息"}", "running", step)
            throw e
        } catch (t: Throwable) {
            appendLog(task, "诊断:流式 JSON 请求异常：${t::class.simpleName ?: "Throwable"} ${t.message ?: "无错误信息"}", "running", step)
            throw t
        } finally {
            throttler.close()
        }
    }

    private suspend fun streamTextWithThrottledProgress(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        task: ProcessingTaskEntity,
        step: String,
        logMessage: (Int) -> String,
        onRetry: suspend (com.my.knowledge.data.ai.AiRetryEvent) -> Unit = {},
    ): String {
        val throttler = SseProgressThrottler(
            writeProgress = { count -> updateProgress(task, 50, step, logMessage(count)) },
            everyN = PROGRESS_EVERY_N_TOKENS,
            sampleMs = PROGRESS_SAMPLE_MS,
        )
        appendLog(
            task,
            "诊断:开始请求流式文本，systemPrompt=${systemPrompt.length} 字符, userPrompt=${userPrompt.length} 字符, readTimeout=${AI_READ_TIMEOUT_MS}ms",
            "running",
            step
        )
        return try {
            val result = ai.completeStreamObserved(
                systemPrompt = systemPrompt,
                userMessage = userPrompt,
                temperature = temperature,
                maxAttempts = INGEST_AI_REMOTE_ATTEMPTS,
                onRetry = onRetry,
                onChunk = { delta -> throttler.observeDelta(delta.length) },
            )
            val finalCount = throttler.totalCountForFlush()
            throttler.flush(finalCount)
            appendLog(
                task,
                "诊断:流式文本请求完成，累计接收 $finalCount 字符，清洗后 ${result.length} 字符",
                "running",
                step
            )
            result
        } catch (e: CancellationException) {
            appendLog(task, "诊断:流式文本请求被取消：${e.message ?: "无附加信息"}", "running", step)
            throw e
        } catch (t: Throwable) {
            appendLog(task, "诊断:流式文本请求异常：${t::class.simpleName ?: "Throwable"} ${t.message ?: "无错误信息"}", "running", step)
            throw t
        } finally {
            throttler.close()
        }
    }

    private suspend fun requestAiRawOutput(
        task: ProcessingTaskEntity,
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        analysis: AnalysisResultEntity
    ): String? {
        if (!ai.isAvailable()) return null
        val kbId = source.targetKnowledgeBaseId
        val currentIndex = buildCurrentIndex(kbId)
        val overview = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId.orEmpty(), "wiki_overview", "overview.md")?.contentMarkdown ?: ""
        val analysisText = analysis.summary
        // In addition to the prose summary, surface the structured
        // entities / concepts / relations extracted by Stage 1 so the
        // Stage 2 model can write FILE blocks that line up with the
        // real nodes (and so the LLM doesn't re-derive its own
        // entities from the raw text and end up with a different set
        // than what KnowledgeRepositoryImpl.rebuildGraphForBase will
        // materialize). This is the bridge that turns the analysis
        // stage from a text blob into a real source of truth.
        val structuredContext = buildStructuredAnalysisContext(analysis)

        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector.detect(parsed.markdown)
        val systemPrompt = com.my.knowledge.data.ai.AiPromptTemplates.generationPrompt(
            fileName = source.title,
            analysisResult = analysisText + structuredContext,
            sourceContent = parsed.markdown,
            schema = WIKI_SCHEMA,
            purpose = WIKI_PURPOSE,
            currentIndex = currentIndex,
            overview = overview,
            language = detectedLanguage
        )
        val userPrompt = buildGenerationUserMessage(source.title, analysisText, parsed.markdown, structuredContext)
        appendLog(
            task,
            "诊断:generation 输入 title=${source.title}, markdown=${parsed.markdown.length} 字符, analysis=${analysisText.length} 字符, structured=${structuredContext.length} 字符, systemPrompt=${systemPrompt.length} 字符, userPrompt=${userPrompt.length} 字符",
            "running",
            "调用 AI 生成 wiki 页面"
        )

        // System prompt mirrors llm_wiki's "You are a wiki maintainer."
        // The full language directive is injected BOTH at the head of the
        // user prompt and at the tail (handled inside generationPrompt).
        // The output is the raw FILE-block text we feed into
        // FileBlockParser; cleaning the think block here keeps reasoning
        // out of the persisted wiki content even if a future
        // AiGateway.change forgets to do it at the boundary.
        //
        appendLog(task, "诊断:generation 使用流式模型调用，边接收 FILE 块边更新进度，readTimeout=${AI_READ_TIMEOUT_MS}ms, remoteAttempts=$INGEST_AI_REMOTE_ATTEMPTS", "running", "调用 AI 生成 wiki 页面")
        val response = try {
            streamTextWithThrottledProgress(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = 0.1f,
                task = task,
                step = "调用 AI 生成 wiki 页面",
                logMessage = { count -> "generation 流式接收 ${count} 字符" },
                onRetry = { event ->
                    appendLog(
                        task,
                        "诊断:generation 远端请求第 ${event.attempt}/${event.maxAttempts} 次失败：${event.errorType} ${event.message}，${event.delayMs / 1000}s 后重试",
                        "running",
                        "调用 AI 生成 wiki 页面"
                    )
                }
            )
        } catch (e: CancellationException) {
            appendLog(task, "诊断:generation 流式模型调用被取消：${e.message ?: "无附加信息"}", "running", "调用 AI 生成 wiki 页面")
            throw e
        } catch (t: Throwable) {
            appendLog(task, "诊断:generation 流式模型调用异常：${t::class.simpleName ?: "Throwable"} ${t.message ?: "无错误信息"}", "running", "调用 AI 生成 wiki 页面")
            throw t
        }
        val cleaned = with(AiTextCleaner) { response.cleanModelOutput() }
        appendLog(
            task,
            "诊断:generation AI 返回 ${cleaned.length} 字符，FILE 块标记数=${Regex("(?m)^FILE:").findAll(cleaned).count()}",
            "running",
            "调用 AI 生成 wiki 页面"
        )
        throwIfAiFailure(cleaned)
        return cleaned.takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    /**
     * Render the structured columns of [AnalysisResultEntity] as a
     * compact markdown block. Used by [requestAiRawOutput] to feed
     * Stage 2 the same entity / concept / relation list the DB now
     * holds, so the FILE blocks the LLM emits line up with what the
     * downstream compiler and graph rebuild will materialize. Without
     * this bridge, Stage 2 only sees `analysis.summary` (a 2-4
     * sentence prose), which is too thin to drive a multi-page wiki.
     */
    private fun buildStructuredAnalysisContext(analysis: AnalysisResultEntity): String {
        if (analysis.entitiesJson == "[]" && analysis.conceptsJson == "[]" && analysis.relationsJson == "[]") {
            return ""
        }
        val entities = runCatching { JSONArray(analysis.entitiesJson) }.getOrNull()
        val concepts = runCatching { JSONArray(analysis.conceptsJson) }.getOrNull()
        val relations = runCatching { JSONArray(analysis.relationsJson) }.getOrNull()
        if ((entities == null || entities.length() == 0) &&
            (concepts == null || concepts.length() == 0) &&
            (relations == null || relations.length() == 0)
        ) {
            return ""
        }
        return buildString {
            appendLine()
            appendLine("## Stage 1 Structured Extraction (use these as the source of truth)")
            appendLine()
            if (entities != null && entities.length() > 0) {
                appendLine("### Entities (${entities.length()})")
                for (i in 0 until entities.length()) {
                    val e = entities.optJSONObject(i) ?: continue
                    val name = e.optString("name").trim()
                    if (name.isBlank()) continue
                    // P1: 优先读 LLM 的 entityType(自由语义类型),
                    // 回退到老的 `type` 字段(enum Person/Organization/...),
                    // 最后回退到 "entity"——这样 Stage 2 看到的类型始终是
                    // 真正"是什么",而不是被强制成"entity"。
                    val type = e.optString("entityType").ifBlank { e.optString("type") }.ifBlank { "entity" }
                    val desc = e.optString("description").ifBlank { e.optString("definition") }
                    val role = e.optString("role_in_source").ifBlank { e.optString("why_it_matters") }
                    appendLine("- **$name** ($type) — ${desc.take(200)}")
                    if (role.isNotBlank()) appendLine("  - role: ${role.take(120)}")
                }
                appendLine()
            }
            if (concepts != null && concepts.length() > 0) {
                appendLine("### Concepts (${concepts.length()})")
                for (i in 0 until concepts.length()) {
                    val c = concepts.optJSONObject(i) ?: continue
                    val name = c.optString("name").trim()
                    if (name.isBlank()) continue
                    // P1: 同上,优先 conceptCategory,回退 category。
                    val category = c.optString("conceptCategory").ifBlank { c.optString("category") }.ifBlank { "concept" }
                    val definition = c.optString("definition").ifBlank { c.optString("description") }
                    appendLine("- **$name** ($category) — ${definition.take(200)}")
                }
                appendLine()
            }
            if (relations != null && relations.length() > 0) {
                appendLine("### Relations (${relations.length()})")
                for (i in 0 until relations.length()) {
                    val r = relations.optJSONObject(i) ?: continue
                    val source = r.optString("source").trim()
                    val target = r.optString("target").trim()
                    if (source.isBlank() || target.isBlank()) continue
                    val type = r.optString("type").ifBlank { "related_to" }
                    appendLine("- $source —[$type]→ $target")
                }
                appendLine()
            }
            appendLine("Use these names and types as the authoritative list of nodes. When you emit `---FILE:` blocks, the `type:` frontmatter enum is FIXED (entity|concept|...) by the directory the page lives in — DO NOT write entityType/conceptCategory values into `type:`. Put the semantic type in `entityType` (entity pages) or `conceptCategory` (concept pages) instead.")
        }
    }

    private suspend fun requestAiMerge(
        existingContent: String,
        incomingContent: String,
        sourceTitle: String
    ): String {
        val prompt = com.my.knowledge.data.ai.AiPromptTemplates.mergePrompt(
            existingContent = existingContent,
            incomingContent = incomingContent,
            sourceFileName = sourceTitle
        )
        val response = ai.complete(
            systemPrompt = "You are a wiki merging assistant. Output only the merged markdown content starting with '---'.",
            userMessage = prompt
        )
        // Strip think + fence before validating the leading "---" sentinel
        // — otherwise a model's preamble-think-then-merge pattern would
        // make the response look invalid and force a template fallback.
        val cleaned = with(AiTextCleaner) { response.cleanModelOutput() }
        throwIfAiFailure(cleaned)
        return if (cleaned.startsWith("---")) cleaned else {
            // Fallback to template merge if AI output is invalid
            wikiCompiler.merge(existingContent, incomingContent, sourceTitle)
        }
    }

    private suspend fun requestAiAnalysis(
        task: ProcessingTaskEntity,
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity
    ): String? {
        if (!ai.isAvailable()) return null

        val kbId = source.targetKnowledgeBaseId
        val currentIndex = buildCurrentIndex(kbId)
        val purpose = "建立一个可读、可维护、可进化的本地知识库（Wiki），用于深度学习和长期记忆。"

        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector.detect(parsed.markdown)
        val systemPrompt = com.my.knowledge.data.ai.AiPromptTemplates.analysisPrompt(
            title = source.title,
            sourceType = source.sourceType,
            currentIndex = currentIndex,
            purpose = purpose,
            fragments = emptyList(),
            // `ai.chatJson` appends ANALYSIS_SCHEMA at the end of the
            // system prompt. Keeping it there avoids duplicating the
            // full schema while preserving the strongest tail-anchor.
            schemaHint = "",
            language = detectedLanguage
        )
        val userPrompt = buildAnalysisUserMessage(source, parsed.markdown)

        appendLog(
            task,
            "诊断:analysis 使用流式 JSON 调用，systemPrompt=${systemPrompt.length} 字符, userPrompt=${userPrompt.length} 字符, schema=${ANALYSIS_SCHEMA.length} 字符, readTimeout=${AI_READ_TIMEOUT_MS}ms, remoteAttempts=$INGEST_AI_REMOTE_ATTEMPTS",
            "running",
            "调用 AI 生成结构化分析"
        )
        val cleaned = try {
            streamJsonWithThrottledProgress(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                schemaHint = ANALYSIS_SCHEMA,
                temperature = 0.1f,
                task = task,
                step = "调用 AI 生成结构化分析",
                logMessage = { count -> "analysis 流式接收 ${count} 字符" },
                onRetry = { event ->
                    appendLog(
                        task,
                        "诊断:analysis 远端请求第 ${event.attempt}/${event.maxAttempts} 次失败：${event.errorType} ${event.message}，${event.delayMs / 1000}s 后重试",
                        "running",
                        "调用 AI 生成结构化分析"
                    )
                }
            )
        } catch (e: CancellationException) {
            appendLog(task, "诊断:analysis 流式 JSON 调用被取消：${e.message ?: "无附加信息"}", "running", "调用 AI 生成结构化分析")
            throw e
        } catch (t: Throwable) {
            appendLog(task, "诊断:analysis 流式 JSON 调用异常：${t::class.simpleName ?: "Throwable"} ${t.message ?: "无错误信息"}", "running", "调用 AI 生成结构化分析")
            throw t
        }
        appendLog(
            task,
            "诊断:analysis 流式 JSON 返回 ${cleaned.length} 字符",
            "running",
            "调用 AI 生成结构化分析"
        )
        throwIfAiFailure(cleaned)
        return cleaned.takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    private fun throwIfAiFailure(text: String) {
        if (text.trimStart().startsWith("[")) {
            throw IllegalStateException(text)
        }
    }

    /**
     * P0-3: long-source analysis path. Splits [parsed].markdown into
     * semantic chunks via [markdownChunker], runs the LLM once per
     * chunk (via the existing [ai.chatJson] helper, with the same
     * `ANALYSIS_SCHEMA` so each chunk's response is a
     * fully-structured `ParsedAnalysis`-shaped JSON), and merges
     * the per-chunk JSONs into one consolidated analysis JSON that
     * [parseAiAnalysisJson] can ingest through the normal short-
     * path code below.
     *
     * Progress is persisted to [longSourceCheckpointStore] after
     * every chunk, so a retry / crash resumes from
     * `completedThrough + 1` instead of re-running every chunk. The
     * store's compat check rejects any checkpoint whose identity /
     * shape (source hash, chunk count, target/overlap chars,
     * budget) no longer matches the current run, which forces a
     * clean restart whenever the source content or the chunking
     * policy changes.
     *
     * The merge is deliberately simple — union of entities (by
     * case-insensitive name), concepts (same), relations (by
     * `source|target|type` triple), claims (by claim text), gaps
     * (by gap text); tags deduped and ranked by frequency. Per-
     * chunk summaries are concatenated into the final `summary`.
     * The merged JSON is then re-shaped to match the schema the
     * short path's `chatJson` output would have produced, so the
     * existing [parseAiAnalysisJson] can read it without any
     * special-casing.
     *
     * Returns `null` (instead of throwing) when the API key is
     * blank, mirroring the short path's `ai.isAvailable()` short-
     * circuit. Mid-stream / parse errors are NOT swallowed —
     * they propagate up to `analysisTask`'s outer try/catch so the
     * task retries the whole run.
     */
    private suspend fun requestAiAnalysisLongSource(
        task: ProcessingTaskEntity,
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity
    ): String? {
        if (!ai.isAvailable()) return null
        val store = longSourceCheckpointStore
            ?: return requestAiAnalysis(task, source, parsed)
                ?.takeIf { it.isNotBlank() && !it.startsWith("[") }

        val content = parsed.markdown
        val sourceBudget = LONG_SOURCE_BUDGET_CHARS
        val targetChars = ((sourceBudget * 0.55).toInt())
            .coerceIn(LONG_SOURCE_CHUNK_MIN, LONG_SOURCE_CHUNK_MAX)
        val overlapChars = ((targetChars * 0.08).toInt())
            .coerceIn(LONG_SOURCE_OVERLAP_MIN, LONG_SOURCE_OVERLAP_MAX)

        val chunks = markdownChunker.split(content)
        if (chunks.size <= 1) {
            // MarkdownSemanticChunker collapsed the whole thing into
            // one oversized chunk. Cheaper to just run the normal
            // single-call path than to ask the LLM to digest a
            // 60K-char monobloc twice.
            appendLog(task, "P0-3: 源长度 ${content.length} 字符，但分块只产出 ${chunks.size} 段；走单次分析路径", "running")
            return requestAiAnalysis(task, source, parsed)
                ?.takeIf { it.isNotBlank() && !it.startsWith("[") }
        }

        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector.detect(content)
        val sourceIdentity = "${source.id}:${source.sha256}"
        val sourceHash = LongSourceCheckpointStore.sha256Hex(content)
        val sourceSlug = LongSourceCheckpointStore.slugify(source.title)
        val checkpointFile = store.checkpointPath(sourceSlug, sourceHash)
        val params = LongSourceCheckpointParams(
            sourceIdentity = sourceIdentity,
            sourceHash = sourceHash,
            sourceLength = content.length,
            sourceBudget = sourceBudget,
            targetChars = targetChars,
            overlapChars = overlapChars,
            chunkTotal = chunks.size
        )

        val existing = store.load(checkpointFile, params)
        val analyses: MutableList<String> = existing?.analyses?.toMutableList()
            ?: mutableListOf()
        var completedThrough = existing?.completedThrough ?: 0
        var globalDigest = existing?.globalDigest.orEmpty()

        if (completedThrough > 0) {
            appendLog(
                task,
                "P0-3: 从 checkpoint 恢复长源分析，已完成 $completedThrough/${chunks.size} 段",
                "running"
            )
            updateProgress(
                task,
                45 + (35 * completedThrough / chunks.size).coerceIn(0, 35),
                "恢复分块分析（$completedThrough/${chunks.size}）",
                "从断点继续，跳过已完成 ${completedThrough} 段"
            )
        } else {
            appendLog(
                task,
                "P0-3: 源长度 ${content.length} 字符（> ${sourceBudget}），切分为 ${chunks.size} 段，目标 ${targetChars} 字符 / 段，重叠 ${overlapChars} 字符",
                "running"
            )
        }

        val kbId = source.targetKnowledgeBaseId
        val currentIndex = buildCurrentIndex(kbId)
        val purpose = "建立一个可读、可维护、可进化的本地知识库（Wiki），用于深度学习和长期记忆。"

        for (chunk in chunks) {
            if (chunk.index <= completedThrough) continue

            updateProgress(
                task,
                45 + (35 * (chunk.index - 1) / chunks.size).coerceIn(0, 35),
                "分块 ${chunk.index}/${chunks.size} 分析中",
                "标题路径：${chunk.headingPath.ifBlank { "(无标题)" }}"
            )
            appendLog(
                task,
                "P0-3: 分块 ${chunk.index}/${chunks.size}（${chunk.main.length} 字符，标题：${chunk.headingPath.ifBlank { "无" }}）开始调用 LLM",
                "running"
            )

            val systemPrompt = buildChunkAnalysisSystemPrompt(
                purpose = purpose,
                schema = "",
                index = currentIndex,
                language = detectedLanguage,
                chunkTotal = chunks.size
            )
            val userPrompt = buildChunkAnalysisUserPrompt(
                sourceIdentity = sourceIdentity,
                folderContext = source.folderHint,
                chunk = chunk,
                globalDigest = globalDigest
            )
            val raw = streamJsonWithThrottledProgress(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                schemaHint = ANALYSIS_SCHEMA,
                temperature = 0.1f,
                task = task,
                step = "分块 ${chunk.index}/${chunks.size} 分析中",
                logMessage = { count -> "分块 ${chunk.index}/${chunks.size} 流式接收 ${count} 字符" },
                onRetry = { event ->
                    appendLog(
                        task,
                        "P0-3: 分块 ${chunk.index}/${chunks.size} 远端请求第 ${event.attempt}/${event.maxAttempts} 次失败：${event.errorType} ${event.message}，${event.delayMs / 1000}s 后重试",
                        "running",
                        "分块 ${chunk.index}/${chunks.size} 分析中"
                    )
                }
            )
            appendLog(
                task,
                "P0-3: 分块 ${chunk.index}/${chunks.size} LLM 返回 ${raw.length} 字符",
                "running"
            )
            throwIfAiFailure(raw)
            if (raw.isBlank()) {
                throw IllegalStateException("P0-3: 分块 ${chunk.index}/${chunks.size} 未返回任何内容")
            }

            analyses.add(raw)
            completedThrough = chunk.index
            // The next-chunk global digest is whatever the latest
            // chunk's `summary` field was, so subsequent chunks
            // can preserve cross-boundary naming without re-reading
            // the full prior chunk set.
            globalDigest = extractChunkDigest(raw) ?: globalDigest
            val ok = store.save(
                checkpointFile,
                LongSourceCheckpoint(
                    version = LongSourceCheckpointStore.CHECKPOINT_VERSION,
                    sourceIdentity = sourceIdentity,
                    sourceHash = sourceHash,
                    sourceLength = content.length,
                    sourceBudget = sourceBudget,
                    targetChars = targetChars,
                    overlapChars = overlapChars,
                    chunkTotal = chunks.size,
                    completedThrough = completedThrough,
                    globalDigest = globalDigest,
                    analyses = analyses.toList(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (!ok) {
                appendLog(task, "P0-3: checkpoint 写入失败，将继续下一段（不阻塞当前分析）", "running")
            }
        }

        // Merge per-chunk JSONs into one consolidated analysis JSON
        // shaped like the short-path output. parseAiAnalysisJson
        // can then read it through the normal column-extraction
        // path.
        val merged = mergeChunkAnalyses(
            analyses = analyses,
            fallbackTitle = source.title
        )
        // Clean up the checkpoint on success so the next re-import
        // of the same file doesn't see a stale "all done" state and
        // confuse the cache-hit logic. Best-effort.
        store.clear(checkpointFile)
        updateProgress(task, 80, "分块分析完成", "合并 ${chunks.size} 段结果，识别实体 / 概念 / 关系")
        return merged
    }

    /**
     * P0-3: build the chunk-level system prompt. Mirrors
     * `buildChunkAnalysisSystemPrompt` in llm_wiki's ingest.ts,
     * but constrained to the JSON-mode contract `chatJson` expects
     * (the schema is the regular [ANALYSIS_SCHEMA] — we just ask
     * the model to fill in only what this chunk supports).
     */
    private fun buildChunkAnalysisSystemPrompt(
        purpose: String,
        schema: String,
        index: String,
        language: String,
        chunkTotal: Int
    ): String {
        val sb = StringBuilder()
        sb.append("You are analyzing chunk of a long source document for a personal wiki.\n")
        sb.append("Do not output chain-of-thought, hidden reasoning, or a thinking transcript.\n")
        sb.append("Analyze ONLY the current MAIN CHUNK. Use overlap and digest for context only.\n")
        sb.append("Keep stable names consistent with the existing wiki and prior digest.\n")
        sb.append("\n")
        sb.append(com.my.knowledge.data.ai.AiPromptTemplates.languageDirective(language))
        sb.append("\n\n")
        sb.append("This document is split into $chunkTotal semantic chunks with paragraph/section boundaries and overlap.\n")
        sb.append("Output JSON ONLY — no markdown, no prose, no commentary.\n")
        sb.append("Focus your extraction on the MAIN CHUNK below. Use prior digest and overlap ONLY to:\n")
        sb.append("  - keep entity / concept names consistent with earlier chunks,\n")
        sb.append("  - decide whether a concept introduced in overlap is \"new\" or \"already known\".\n")
        sb.append("Emit empty arrays for entities / concepts / relations / claims / gaps when the chunk truly has nothing to add.\n")
        sb.append("\nStable project context follows. It changes rarely and should be treated as background:\n")
        if (purpose.isNotBlank()) {
            sb.append("## Wiki Purpose\n").append(purpose).append("\n\n")
        }
        if (schema.isNotBlank()) {
            sb.append("## Wiki Schema\n").append(schema).append("\n\n")
        }
        if (index.isNotBlank()) {
            sb.append("## Current Wiki Index\n").append(index.take(40_000)).append("\n")
        }
        return sb.toString()
    }

    /**
     * P0-3: build the chunk-level user prompt. Mirrors
     * `buildChunkAnalysisUserPrompt` in llm_wiki's ingest.ts.
     */
    private fun buildChunkAnalysisUserPrompt(
        sourceIdentity: String,
        folderContext: String?,
        chunk: SourceChunk,
        globalDigest: String
    ): String {
        val sb = StringBuilder()
        sb.append("Source file: ").append(sourceIdentity).append("\n")
        if (!folderContext.isNullOrBlank()) {
            sb.append("Folder context: ").append(folderContext).append("\n")
        }
        sb.append("Chunk: ").append(chunk.index).append("/").append(chunk.total).append("\n")
        if (chunk.headingPath.isNotBlank()) {
            sb.append("Heading path: ").append(chunk.headingPath).append("\n")
        }
        sb.append("\n")
        sb.append("## Current Global Digest\n")
        sb.append(globalDigest.ifBlank { "(No prior digest yet.)" }).append("\n\n")
        if (chunk.overlapBefore.isNotBlank()) {
            sb.append("## Previous Overlap Context\n")
                .append(chunk.overlapBefore).append("\n\n")
        }
        sb.append("## MAIN CHUNK TO ANALYZE\n")
        sb.append(chunk.main).append("\n\n")
        sb.append("Return JSON only. Do not repeat overlap-only facts unless the main chunk supports them.\n")
        return sb.toString()
    }

    /**
     * P0-3: pull the `summary` field out of one chunk's response so
     * the next chunk can keep a rolling cross-boundary context. The
     * field is best-effort — a missing or empty `summary` is treated
     * as "no digest to pass forward" and the prior digest is kept.
     */
    private fun extractChunkDigest(rawJson: String): String? {
        return try {
            val obj = org.json.JSONObject(rawJson)
            val s = obj.optString("summary").trim()
            if (s.isBlank()) null else s.take(LONG_SOURCE_DIGEST_MAX)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * P0-3: union all per-chunk JSON responses into one consolidated
     * analysis JSON shaped like the short-path `chatJson` output.
     * See [requestAiAnalysisLongSource] for the merge rules. On any
     * parse failure the corresponding chunk's contribution is
     * dropped (its summary is still preserved via the
     * `summaries` concat) so a single bad chunk doesn't break the
     * whole merge.
     */
    private fun mergeChunkAnalyses(
        analyses: List<String>,
        fallbackTitle: String
    ): String {
        val entities = LinkedHashMap<String, org.json.JSONObject>()
        val concepts = LinkedHashMap<String, org.json.JSONObject>()
        val relations = LinkedHashSet<String>()
        val relationsArr = org.json.JSONArray()
        val claims = LinkedHashSet<String>()
        val claimsArr = org.json.JSONArray()
        val gaps = LinkedHashSet<String>()
        val gapsArr = org.json.JSONArray()
        val tagCounts = HashMap<String, Int>()
        val summaries = mutableListOf<String>()
        var confidenceSum = 0f
        var confidenceCount = 0
        var archiveRecommendation: org.json.JSONObject? = null
        var needHumanReview = false
        val reviewReasons = LinkedHashSet<String>()
        val pageRecommendations = org.json.JSONArray()

        for ((idx, raw) in analyses.withIndex()) {
            val obj = runCatching { org.json.JSONObject(raw) }.getOrNull()
            if (obj == null) {
                summaries.add("（分块 ${idx + 1} JSON 解析失败）")
                continue
            }
            val summary = obj.optString("summary").trim()
            if (summary.isNotBlank()) summaries.add("【分块 ${idx + 1}】$summary")

            val tagsArr = obj.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i).trim()
                    if (t.isBlank()) continue
                    tagCounts[t] = (tagCounts[t] ?: 0) + 1
                }
            }

            collectNamedObjects(obj.optJSONArray("entities"), entities, "entity")
            collectNamedObjects(obj.optJSONArray("concepts"), concepts, "concept")
            collectRelations(obj.optJSONArray("relations"), relations, relationsArr)
            collectClaims(obj.optJSONArray("claims"), claims, claimsArr)
            collectGaps(obj.optJSONArray("gaps"), gaps, gapsArr)

            val conf = obj.opt("confidence")
            if (conf != null && conf != org.json.JSONObject.NULL) {
                val c = runCatching { conf.toString().toFloat() }.getOrNull()
                if (c != null) {
                    confidenceSum += c
                    confidenceCount++
                }
            }
            if (!needHumanReview && obj.optBoolean("needHumanReview", false)) {
                needHumanReview = true
            }
            val reviewArr = obj.optJSONArray("reviewReasons")
            if (reviewArr != null) {
                for (i in 0 until reviewArr.length()) {
                    val r = reviewArr.optString(i).trim()
                    if (r.isNotBlank()) reviewReasons.add(r)
                }
            }
            if (archiveRecommendation == null) {
                val ar = obj.optJSONObject("archiveRecommendation")
                if (ar != null) archiveRecommendation = ar
            }
            val pr = obj.optJSONArray("pageRecommendations")
            if (pr != null) {
                for (i in 0 until pr.length()) {
                    pr.optJSONObject(i)?.let { pageRecommendations.put(it) }
                }
            }
        }

        val tagsArray = org.json.JSONArray()
        tagCounts.entries
            .sortedByDescending { it.value }
            .take(16)
            .forEach { tagsArray.put(it.key) }

        val entitiesArr = org.json.JSONArray()
        entities.values.forEach { entitiesArr.put(it) }
        val conceptsArr = org.json.JSONArray()
        concepts.values.forEach { conceptsArr.put(it) }

        val merged = org.json.JSONObject()
        merged.put("title", fallbackTitle)
        merged.put(
            "summary",
            if (summaries.isEmpty()) "(Long source analysis produced no per-chunk summary.)"
            else summaries.joinToString("\n\n")
        )
        merged.put("tags", tagsArray)
        merged.put("entities", entitiesArr)
        merged.put("concepts", conceptsArr)
        merged.put("relations", relationsArr)
        merged.put("claims", claimsArr)
        merged.put("gaps", gapsArr)
        merged.put("pageRecommendations", pageRecommendations)
        merged.put(
            "archiveRecommendation",
            archiveRecommendation ?: org.json.JSONObject()
                .put("targetKnowledgeBaseId", org.json.JSONObject.NULL)
                .put("targetKnowledgeBaseName", "")
                .put("confidence", if (confidenceCount > 0) confidenceSum / confidenceCount else 0.5f)
                .put("reason", "由 ${analyses.size} 段分块分析合并")
                .put("suggestCreateNewBase", false)
                .put("newBaseName", org.json.JSONObject.NULL)
        )
        merged.put(
            "confidence",
            if (confidenceCount > 0) confidenceSum / confidenceCount else 0.6f
        )
        merged.put("needHumanReview", needHumanReview || reviewReasons.isNotEmpty())
        val reasonsArr = org.json.JSONArray()
        reviewReasons.forEach { reasonsArr.put(it) }
        merged.put("reviewReasons", reasonsArr)
        return merged.toString()
    }

    private fun collectNamedObjects(
        arr: org.json.JSONArray?,
        sink: LinkedHashMap<String, org.json.JSONObject>,
        defaultType: String
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            val key = name.lowercase()
            // P1 兼容: 优先 entityType / conceptCategory,回退到 type.
            if (!item.has("entityType") && !item.has("conceptCategory") && !item.has("type")) {
                item.put("type", defaultType)
            }
            // Last-wins on name collision so later chunks' descriptions
            // overwrite earlier ones — usually the more specific
            // extraction lives in the chunk that actually uses the
            // entity / concept.
            sink[key] = item
        }
    }

    private fun collectRelations(
        arr: org.json.JSONArray?,
        keys: LinkedHashSet<String>,
        out: org.json.JSONArray
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val source = item.optString("source").trim()
            val target = item.optString("target").trim()
            if (source.isBlank() || target.isBlank()) continue
            val type = item.optString("type", "related_to").trim()
            val key = "${source.lowercase()}|${target.lowercase()}|${type.lowercase()}"
            if (keys.add(key)) out.put(item)
        }
    }

    private fun collectClaims(
        arr: org.json.JSONArray?,
        keys: LinkedHashSet<String>,
        out: org.json.JSONArray
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val claim = item.optString("claim").trim()
            if (claim.isBlank()) continue
            val key = claim.lowercase()
            if (keys.add(key)) out.put(item)
        }
    }

    private fun collectGaps(
        arr: org.json.JSONArray?,
        keys: LinkedHashSet<String>,
        out: org.json.JSONArray
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val gap = item.optString("gap").trim()
            if (gap.isBlank()) continue
            val key = gap.lowercase()
            if (keys.add(key)) out.put(item)
        }
    }

    /**
     * Bridge between the LLM's raw JSON output and the structured columns
     * on [AnalysisResultEntity]. The previous version of `analysisTask`
     * skipped this step entirely and just stuffed the AI's text into
     * `summary`, leaving `entitiesJson` / `conceptsJson` / `relationsJson`
     * hard-coded to `[]` / `tags` / `[]`. That meant the wiki never
     * received any real entities, every "concept" page was named after
     * a tag with an empty description, and the knowledge graph had zero
     * non-wikilink edges.
     *
     * This function is the missing piece: it takes whatever the model
     * produced (or null if the model failed), repairs + parses the JSON,
     * and normalizes the result into a [ParsedAnalysis] the rest of
     * the pipeline can consume. On parse failure we fall back to a
     * local summary/tag/archiveRecommendation only — but we DO NOT
     * invent fake entities, concepts, or relations; the fallback keeps
     * those arrays empty so the
     * generation stage's `FILE block` path can still synthesize pages
     * from the (also AI-driven) `requestAiRawOutput` pass.
     */
    /**
     * The reason [parseAiAnalysisJson] is `internal` rather than
     * `private` is the same as [ParsedAnalysis] above — we want the
     * unit test in the same package to be able to drive this function
     * with sample LLM outputs (valid JSON, invalid JSON, missing
     * fields, etc.) without standing up a real DB.
     */
    internal fun parseAiAnalysisJson(
        raw: String?,
        fallbackTitle: String,
        fallbackSummary: String,
        fallbackTags: List<String>,
        fallbackConfidence: Float,
    ): ParsedAnalysis {
        val fallback = IngestJsonValidator.fallbackAnalysisJson(
            title = fallbackTitle,
            summary = fallbackSummary,
            tagsJson = fallbackTags.toJsonArray(),
            confidence = fallbackConfidence,
            reviewReason = if (fallbackConfidence < 0.6f) "内容较短，建议人工确认摘要和归档" else null
        )
        if (raw.isNullOrBlank()) {
            val obj = IngestJsonValidator.parseObjectOrNull(fallback) ?: return ParsedAnalysis.fromFallback(fallbackTags, fallbackConfidence)
            return ParsedAnalysis.fromObj(obj, fallbackTags, fallbackConfidence, aiSucceeded = false)
        }
        // First pass — try to parse the raw AI output as JSON.
        val obj = IngestJsonValidator.parseObjectOrNull(raw)
        if (obj == null) {
            // The AI returned something, but it wasn't JSON. Surface
            // the failure to the user via the analysis summary and a
            // gap entry instead of silently dropping it on the floor.
            return ParsedAnalysis.fromObj(
                obj = IngestJsonValidator.parseObjectOrNull(fallback) ?: return ParsedAnalysis.fromFallback(fallbackTags, fallbackConfidence),
                fallbackTags = fallbackTags,
                fallbackConfidence = fallbackConfidence,
                aiSucceeded = false,
                parseErrorNote = "AI 未能返回有效 JSON,已使用本地摘要兜底。原始输出前 200 字符: ${raw.take(200)}"
            )
        }
        if (!IngestJsonValidator.validateAnalysisJson(raw)) {
            // Got JSON, but it's missing required fields. Fall back to
            // merging the raw into the fallback to preserve whatever
            // we did get (e.g. summary + tags survive even if entities
            // are absent).
            val normalized = IngestJsonValidator.normalizeAnalysisJson(raw, fallback)
            val merged = IngestJsonValidator.parseObjectOrNull(normalized)
                ?: return ParsedAnalysis.fromFallback(fallbackTags, fallbackConfidence)
            return ParsedAnalysis.fromObj(merged, fallbackTags, fallbackConfidence, aiSucceeded = true)
        }
        return ParsedAnalysis.fromObj(obj, fallbackTags, fallbackConfidence, aiSucceeded = true)
    }

    private suspend fun buildCurrentIndex(kbId: String?): String {
        if (kbId.isNullOrBlank()) return "No existing index."
        // 用专门的 wiki-only 查询 (KnowledgeItemDao.getAllWikiByKb),避免把
        // 全部笔记都加载到内存后再 filter。KB 越大,这一步节省的延迟越明显,
        // 之前在大 KB 下这里就是"generation 阶段卡住"的隐形凶手。
        val pages = db.knowledgeItemDao().getAllWikiByKb(kbId)
            .take(150)
        if (pages.isEmpty()) return "No existing wiki pages."
        return pages.joinToString("\n") {
            val type = it.sourceType.removePrefix("wiki_")
            val summaryText = it.summary?.takeIf { s -> s.isNotBlank() }?.let { s -> ": ${s.take(50)}" } ?: ""
            "- ${it.title} ($type)$summaryText"
        }.take(CURRENT_INDEX_PROMPT_CHARS)
    }

    private fun buildAnalysisUserMessage(source: SourceDocumentEntity, content: String): String =
        buildString {
            appendLine("Analyze this source document:")
            appendLine()
            appendLine("**File:** ${source.title}")
            source.folderHint?.takeIf { it.isNotBlank() }?.let {
                appendLine("**Folder context:** $it")
            }
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(content.ifBlank { "(empty file)" })
        }

    private fun buildGenerationUserMessage(
        fileName: String,
        analysis: String,
        sourceContent: String,
        structuredContext: String = ""
    ): String = buildString {
        appendLine("Source document to process: **$fileName**")
        appendLine()
        appendLine("The Stage 1 analysis below is CONTEXT to inform your output. Do NOT echo")
        appendLine("its tables, bullet points, or prose. Your output must be FILE/REVIEW")
        appendLine("blocks as specified in the system prompt — nothing else.")
        appendLine()
        appendLine("## Stage 1 Analysis (context only — do not repeat)")
        appendLine()
        appendLine(analysis)
        if (structuredContext.isNotBlank()) {
            appendLine(structuredContext)
        }
        appendLine()
        appendLine("## Original Source Content")
        appendLine()
        appendLine(sourceContent.take(STAGE2_SOURCE_EXCERPT_CHARS).ifBlank { "(empty file)" })
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("Now emit the FILE blocks for the wiki files derived from **$fileName**.")
        appendLine("Your response MUST begin with `---FILE:` as the very first characters.")
        appendLine("No preamble. No analysis prose. Start immediately.")
    }

    private fun sanitizeIngestedFileContent(content: String): String {
        return repairWikilinkListsInFrontmatter(
            stripFrontmatterKeyPrefix(
                stripOuterCodeFence(content)
            )
        )
    }

    private fun stripOuterCodeFence(content: String): String {
        val open = Regex("^[ \\t]*```(?:yaml|md|markdown)?[ \\t]*\\r?\\n").find(content) ?: return content
        val afterOpen = content.substring(open.range.last + 1)
        val close = Regex("\\r?\\n[ \\t]*```[ \\t]*\\r?\\n?\\s*$").find(afterOpen) ?: return content
        return afterOpen.substring(0, close.range.first)
    }

    private fun stripFrontmatterKeyPrefix(content: String): String =
        content.replaceFirst(Regex("^[ \\t]*frontmatter\\s*:\\s*\\r?\\n(?=[ \\t]*---\\s*\\r?\\n)"), "")

    private fun repairWikilinkListsInFrontmatter(content: String): String {
        val match = Regex("^---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*(\\r?\\n|$)").find(content) ?: return content
        val payload = match.groupValues[1]
        val repaired = payload.lines().joinToString("\n") { line ->
            val lm = Regex("^(\\s*[A-Za-z_][\\w-]*\\s*:\\s*)(\\[\\[[^\\]]+\\]\\](?:\\s*,\\s*\\[\\[[^\\]]+\\]\\])+ )\\s*$").matchEntire(line)
                ?: Regex("^(\\s*[A-Za-z_][\\w-]*\\s*:\\s*)(\\[\\[[^\\]]+\\]\\](?:\\s*,\\s*\\[\\[[^\\]]+\\]\\])+)\\s*$").matchEntire(line)
            if (lm == null) {
                line
            } else {
                val items = lm.groupValues[2].split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ") { "\"${it.escapeYaml()}\"" }
                "${lm.groupValues[1]}[$items]"
            }
        }
        return content.replaceRange(match.groups[1]!!.range, repaired)
    }


    /**
     * Deterministic merge for wiki pages. Listing pages use section-aware
     * union; other pages use [WikiPageCompiler.merge]. AI merge is avoided
     * on the hot path to keep the KB write lock short.
     */
    private fun mergeWikiPageMarkdown(existingMarkdown: String, draft: WikiPageDraft): String {
        val isListingPage = draft.sourceType == "wiki_index" || draft.sourceType == "wiki_overview"
        return if (isListingPage) {
            mergeListingPage(
                existing = existingMarkdown,
                incoming = draft.markdown,
                pageTitle = draft.title,
            )
        } else {
            wikiCompiler.merge(existingMarkdown, draft.markdown, draft.title)
        }
    }

    /**
     * Section-aware merge for `wiki_index` and `wiki_overview` pages.
     *
     * The simple "use draft.markdown" path that was here before lost
     * every historical entry on a re-ingest — the user could see
     * "wiki/index.md" shrink back to only the newest source's
     * entities / sources. This splits the page into frontmatter +
     * body, walks the body by `## Title` sections, and per-section
     * unions the bullet list (case-insensitive, dedup by normalised
     * wikilink target so `[[ Apple ]]` and `[[apple]]` collapse).
     *
     * Sections that only exist in the incoming draft (e.g. a freshly
     * emitted "## 关键实体与概念" subsection) are appended at the end
     * of the merged body so the new source's highlights still show up
     * — we don't drop new content, we add to it.
     *
     * Frontmatter is unified via [wikiCompiler.merge] (which already
     * does case-insensitive array union for `related` / `sources` /
     * `tags`).
     */
    private fun mergeListingPage(existing: String, incoming: String, pageTitle: String): String {
        if (existing.isBlank()) return incoming
        if (incoming.isBlank()) return existing
        // Frontmatter: union arrays (sources/tags/related), keep type/title/created,
        // bump `updated` to today.
        var merged = wikiCompiler.merge(existing, incoming, pageTitle)
        // Re-extract the merged frontmatter + body, then rebuild the
        // body using the section-union logic below. wikiCompiler.merge
        // already moved all frontmatter into the merged string, so we
        // operate on the post-merge markdown.
        val (exFm, exBody) = splitFrontMatter(existing)
        val (_, inBody) = splitFrontMatter(incoming)
        val (mFm, _) = splitFrontMatter(merged)
        val exSections = parseSections(exBody)
        val inSections = parseSections(inBody)
        val sectionOrder = LinkedHashMap<String, Unit>()
        exSections.keys.forEach { sectionOrder[it] = Unit }
        inSections.keys.forEach { sectionOrder[it] = Unit }
        val rebuiltBody = StringBuilder()
        for (title in sectionOrder.keys) {
            val exBullets = exSections[title].orEmpty()
            val inBullets = inSections[title].orEmpty()
            val combined = LinkedHashMap<String, String>()
            // 历史在前,新增在尾,按 wikilink label (规范化后) 去重
            for (bullet in exBullets) combined[normalizeBulletKey(bullet)] = bullet
            for (bullet in inBullets) combined.putIfAbsent(normalizeBulletKey(bullet), bullet)
            val bullets = combined.values.filter { it.isNotBlank() }
            if (bullets.isNotEmpty()) {
                rebuiltBody.append("## ").append(title).append('\n')
                bullets.forEach { bullet -> rebuiltBody.append(bullet).append('\n') }
                rebuiltBody.append('\n')
            }
        }
        // Reassemble: frontmatter (from merged) + body (from section merge)
        val fm = mFm ?: exFm
        return if (fm != null) {
            fm.trimEnd('\n') + "\n\n" + rebuiltBody.toString().trimEnd('\n') + "\n"
        } else {
            rebuiltBody.toString().trimEnd('\n') + "\n"
        }
    }

    private data class FrontMatterBody(val frontMatter: String?, val body: String)

    private fun splitFrontMatter(markdown: String): FrontMatterBody {
        val trimmed = markdown.trimStart()
        if (!trimmed.startsWith("---")) return FrontMatterBody(null, markdown)
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return FrontMatterBody(null, markdown)
        val afterFirst = trimmed.substring(firstNewline + 1)
        val closeIdx = afterFirst.indexOf("\n---")
        if (closeIdx < 0) return FrontMatterBody(null, markdown)
        val fm = trimmed.substring(0, firstNewline + 1 + closeIdx + 4) // include trailing \n---
        val body = afterFirst.substring(closeIdx + 4).trimStart('\n')
        return FrontMatterBody(fm, body)
    }

    /**
     * Split body by `## Title` headers. Returns ordered map from
     * section title to list of bullet lines (without the header
     * itself). Lines outside any section (paragraphs between H1
     * and the first H2) are not preserved — listing pages have
     * only an H1 title, then sections.
     */
    private fun parseSections(body: String): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        for (rawLine in body.lines()) {
            val line = rawLine.trimEnd()
            val header = Regex("^##\\s+(.+?)\\s*$").find(line)
            if (header != null) {
                current = header.groupValues[1].trim()
                out.getOrPut(current) { mutableListOf() }
            } else if (current != null) {
                if (line.isNotBlank()) {
                    // 跳过跟 frontmatter 重新切开可能产生的"前缀行"
                    out.getOrPut(current) { mutableListOf() }.add(line)
                }
            }
        }
        return out
    }

    /**
     * Normalise a bullet line for de-dup purposes: trim, lowercase
     * ASCII, strip the outer `[[ ]]` if any, and collapse internal
     * whitespace. Two bullets that resolve to the same key are
     * treated as the same entry — `[[Apple]]` and `[[ apple ]]`
     * collide intentionally.
     */
    private fun normalizeBulletKey(bullet: String): String {
        val label = bullet.trim()
            .removePrefix("- ")
            .removePrefix("* ")
            .trim()
        val inside = Regex("\\[\\[\\s*(.+?)\\s*]]").find(label)?.groupValues?.get(1) ?: label
        val firstBar = inside.substringBefore("|").trim()
        return firstBar.lowercase().replace(Regex("\\s+"), " ")
    }

    private fun frontMatterValue(markdown: String, key: String): String? {
        if (!markdown.startsWith("---")) return null
        val lines = markdown.lines()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---") break
            if (line.trimStart().startsWith("$key:")) {
                val raw = line.substringAfter(":").trim().trim('"').trim('\'')
                if (raw.isBlank() || raw.startsWith("[")) return null
                return raw
            }
        }
        return null
    }

    private fun stripFrontMatter(markdown: String): String {
        if (!markdown.startsWith("---")) return markdown.trim()
        val lines = markdown.lines()
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                return lines.drop(i + 1).joinToString("\n").trim()
            }
        }
        return markdown.trim()
    }

    private suspend fun enqueue(sourceId: String, taskType: String, priority: Int, inputJson: String) {
        if (db.processingTaskDao().getActiveBySourceAndType(sourceId, taskType) != null) return
        val now = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString()
        db.processingTaskDao().insert(
            ProcessingTaskEntity(
                id = taskId,
                targetType = "source_document",
                targetId = sourceId,
                taskType = taskType,
                status = "pending",
                priority = priority,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null,
                sourceId = sourceId,
                currentStep = "等待 $taskType",
                inputJson = inputJson
            )
        )
        db.processingTaskLogDao().insert(
            ProcessingTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                targetType = "source_document",
                targetId = sourceId,
                stage = taskType,
                status = "pending",
                message = "${taskLabel(taskType)}已排队",
                createdAt = now
            )
        )
    }

    private suspend fun markRunning(task: ProcessingTaskEntity, startedAt: Long) {
        // Don't pin the progress bar to 5 here — that left the log stuck at
        // "5% forever" until the task finished. Real progress is pushed
        // mid-task by `updateProgress`; we just flip status and start time.
        db.processingTaskDao().update(
            task.copy(
                status = "running",
                progress = 0,
                currentStep = "启动 ${taskLabel(task.taskType)}",
                errorMessage = null,
                startedAt = startedAt,
                updatedAt = startedAt,
                finishedAt = null
            )
        )
    }

    private suspend fun updateProgress(
        task: ProcessingTaskEntity,
        progress: Int,
        step: String,
        logMessage: String? = null
    ) {
        val clamped = progress.coerceIn(0, 99)
        db.processingTaskDao().update(
            task.copy(
                progress = clamped,
                currentStep = step,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (!logMessage.isNullOrBlank()) appendLog(task, logMessage, "running", step)
    }

    private suspend fun markSuccess(task: ProcessingTaskEntity, step: String, outputJson: String) {
        db.processingTaskDao().update(
            task.copy(
                status = "success",
                progress = 100,
                currentStep = step,
                outputJson = outputJson,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis()
            )
        )
        appendLog(task, step, "success")
    }

    private suspend fun appendLog(
        task: ProcessingTaskEntity,
        message: String,
        status: String,
        step: String? = null
    ) {
        val sourceId = task.sourceId ?: task.targetId
        // The target keying on ProcessingTaskLog is the SOURCE id; the log
        // center's `LogSourceCard` watches this flow and re-renders the
        // description column with the latest message, so any progress
        // string here is what the user sees in the log center.
        db.processingTaskLogDao().insert(
            ProcessingTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = task.id,
                targetType = "source_document",
                targetId = sourceId,
                stage = task.taskType,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun taskLabel(taskType: String): String = when (taskType) {
        "parse" -> "解析"
        "analysis" -> "分析"
        "generation" -> "生成"
        "embedding" -> "入库"
        else -> taskType
    }

    private fun extractTags(text: String): List<String> =
        text.replace(Regex("[\\[\\]{}\"#*`~!?.:;，。！？、（）()<>/\\\\|]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length in 2..24 }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }
            .take(8)

    // (Local heuristic extractor lives in LocalEntityHeuristic.kt so
    //  the unit test can drive it without standing up a full DB. The
    //  single production call site is in `analysisTask` above.)

    private fun List<String>.toJsonArray(): String =
        joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun String.escapeYaml(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private companion object {
        private val pageWriteMutexes = ConcurrentHashMap<String, Mutex>()
        private fun wikiPageLockKey(kbId: String, sourceType: String, title: String): String =
            "${kbId.ifBlank { "_unfiled" }}:${sourceType.trim().lowercase()}:${title.trim().lowercase()}"

        // INGEST_IDLE_POLLS / INGEST_IDLE_POLL_MS moved to
        // [IngestScheduler] companion in P0-1 — the orchestrator
        // no longer owns the lane loop.
        private const val INGEST_AI_REMOTE_ATTEMPTS: Int = 2
        private const val AI_READ_TIMEOUT_MS: Int = 300_000
        private const val CURRENT_INDEX_PROMPT_CHARS: Int = 20_000
        private const val STAGE2_SOURCE_EXCERPT_CHARS: Int = 24_000

        /**
         * P0-2: throttling constants for the SSE-driven progress
         * writer. Pin them as named constants (instead of inline
         * literals) so:
         *   1. A future tuning PR can't silently change the rate
         *      limit without a code review trace.
         *   2. The unit test can assert the values match the
         *      P0-2 spec (N=20 tokens / 500ms window).
         *
         * Token-counting uses chunk-character-length, not real
         * tokenizer tokens — the gateway exposes deltas as opaque
         * strings, and a 1-3 char chunk from the SSE stream is
         * close enough to "one token" for progress-bar purposes.
         * Real tokenizer count would require pulling the LLM's
         * tokenizer into the gateway, which is out of scope.
         */
        const val PROGRESS_EVERY_N_TOKENS: Int = 20
        const val PROGRESS_SAMPLE_MS: Long = 500L

        // ── P0-3: long-source path tunables ─────────────────────────────
        //
        // The source budget is the per-LLM-call source-text ceiling.
        // Sources at or below this threshold stay on the original
        // single-call path; anything above gets chunked.
        //
        // The previous 50K ceiling often produced one huge remote
        // request that spent minutes before the first useful token.
        // 30K pushes borderline documents into the checkpointed
        // chunk path earlier, which is smoother and recoverable
        // after timeout / abort.
        const val LONG_SOURCE_BUDGET_CHARS: Int = 30_000

        // Per-chunk target / min / max. The target is `budget * 0.55`
        // clamped into [LONG_SOURCE_CHUNK_MIN, LONG_SOURCE_CHUNK_MAX],
        // mirroring the long-source chunk-size formula in
        // llm_wiki's `analyzeLongSourceInChunks` (ingest.ts).
        const val LONG_SOURCE_CHUNK_MIN: Int = 12_000
        const val LONG_SOURCE_CHUNK_MAX: Int = 60_000

        // Overlap between adjacent chunks. `targetChars * 0.08` clamped
        // into [LONG_SOURCE_OVERLAP_MIN, LONG_SOURCE_OVERLAP_MAX].
        const val LONG_SOURCE_OVERLAP_MIN: Int = 800
        const val LONG_SOURCE_OVERLAP_MAX: Int = 3_000

        // How much of the LLM's "summary" we keep as the rolling
        // global digest. Bigger = more cross-chunk context per
        // prompt, but the prompt grows too.
        const val LONG_SOURCE_DIGEST_MAX: Int = 15_000

        private const val WIKI_PURPOSE = "建立一个可读、可维护、可进化的本地知识库（Wiki），用于深度学习和长期记忆。"
        private const val WIKI_SCHEMA = """
# Wiki Schema

| Type | Directory | Purpose |
|------|-----------|---------|
| entity | wiki/entities/ | Named things: people, organizations, products, tools, datasets, systems, projects, places, source works |
| concept | wiki/concepts/ | Ideas, methods, techniques, mechanisms, theories, principles, frameworks, problems |
| source | wiki/sources/ | Source summaries for imported files, articles, PDFs, images, notes |
| paper | wiki/papers/ | Academic papers with OmegaWiki-style problem/method/results/limitations/take sections |
| method | wiki/methods/ | Reusable, named, citable techniques extracted from papers or technical sources |
| query | wiki/queries/ | Open questions under active investigation |
| comparison | wiki/comparisons/ | Side-by-side analysis of related pages |
| synthesis | wiki/synthesis/ | Cross-cutting summaries and conclusions |
| overview | wiki/ | High-level knowledge-base summary, exactly wiki/overview.md |

Naming:
- Files use kebab-case.md.
- Entity pages use official names when possible.
- Concept pages use stable descriptive noun phrases.
- Every entity/concept page should be linked from wiki/index.md and should include body wikilinks to related pages.

# Frontmatter `type` vs `entityType` / `conceptCategory`

The `type:` field in frontmatter is a STRICT enum and is FIXED by the
directory the page lives in (entity → `entity`, concept → `concept`,
etc.). DO NOT take it from the analysis stage's `entities[].type` /
`concepts[].category` — those are free-form semantic types ("Person",
"Algorithm", "Theory"...) that may live outside the enum and would
break the viewer's type filter.

For entity pages, the semantic kind goes into a SEPARATE frontmatter
field `entityType` (a free-form string). For concept pages it goes
into `conceptCategory`. Both are optional; if missing, the graph
rebuild falls back to "entity" / "concept" and the UI groups them
under the default bucket.
"""
        // P1: 实体 / 概念 的"语义类型"字段 (`type` / `category`) 不再强制 enum。
        //
        // 历史: 旧版 schema hint 写的是
        //   entities.type:    "Person|Organization|Product|Dataset|Tool|System|Project|Place"
        //   concepts.category:"Theory|Method|Technique|Phenomenon|Principle|Framework|Problem"
        // 强 enum 让 LLM 频繁给出 enum 外的合理值(例:"Algorithm"、"Paper"、"Software"、
        // "API"、"Framework")——这些值下游会被 sanitize 抹平,导致:
        //   1) Wiki page frontmatter 出现 `type: Algorithm`(跟 generationPrompt 中
        //      规定的 `source|entity|concept|...` enum 冲突);
        //   2) KnowledgeRepositoryImpl.normalizeWikiGraphType 强制把 wiki_entity
        //      节点的 type 写成 "entity"——所有实体一个桶,UI 的"中间处理数据"页
        //      按 type 分组就完全没意义;
        //   3) 概念的 `category` 字段被 WikiPageCompiler.parseNamedObjects 漏读
        //      (它读 `type` 不读 `category`),分类信息直接丢失。
        //
        // 1:1 对齐 llm_wiki: schema 描述"是什么",Wiki page frontmatter `type` 只
        // 描述"页面在 wiki 里的角色",两者解耦。LLM 用语义类型自由描述实体/概念,
        // 由 KnowledgeRepositoryImpl 的归一化逻辑 + UI 的 nodeColor / 标签
        // 映射表把它们渲染到正确的视觉桶里。
        //
        // 双字段兼容: `entityType` 优先,`type` 作为 fallback;`conceptCategory` 优先,
        // `category` 作为 fallback——这样老 LLM 输出(只给 `type`/`category`)和老
        // wiki page(frontmatter 只有 `type: entity`)都不会断。
        private const val ANALYSIS_SCHEMA = """
{
  "title": "string",
  "summary": "string",
  "tags": ["string"],
  "entities": [
    {
      "name":"string",
      "entityType":"string (free-form: e.g. Person, Organization, Algorithm, Paper, Software, Tool, Dataset, API, Framework, Place, Event...)",
      "type":"DEPRECATED: prefer entityType. Kept as alias for backward compat.",
      "aliases":["string"],
      "description":"string",
      "role_in_source":"central|supporting|peripheral",
      "evidence":"string",
      "source_refs":["fragmentId"],
      "related_concepts":["string"],
      "related_entities":["string"],
      "confidence":0.9
    }
  ],
  "concepts": [
    {
      "name":"string",
      "conceptCategory":"string (free-form: e.g. Theory, Method, Technique, Phenomenon, Principle, Framework, Problem, Pattern, Protocol, Metric...)",
      "category":"DEPRECATED: prefer conceptCategory. Kept as alias for backward compat.",
      "definition":"string",
      "why_it_matters":"string",
      "source_context":"string",
      "related_entities":["string"],
      "related_concepts":["string"],
      "examples":["string"],
      "limitations":["string"],
      "source_refs":["fragmentId"],
      "confidence":0.9
    }
  ],
  "relations": [{"source":"string","target":"string","type":"supports|contradicts|extends|uses|part_of|related_to","reason":"string","evidenceFragmentIds":["string"],"confidence":0.8}],
  "claims": [{"claim":"string","evidence":"string","evidenceFragmentIds":["string"],"confidence":0.8}],
  "gaps": [{"gap":"string","whyItMatters":"string","suggestedAction":"ask_user|web_research|connect_nodes|validate_claim"}],
  "pageRecommendations": [{"path":"wiki/entities/name.md","type":"entity|concept|source|paper|method|synthesis","title":"string","action":"create|update","reason":"string"}],
  "archiveRecommendation": {"targetKnowledgeBaseId":null,"targetKnowledgeBaseName":"","confidence":0.75,"reason":"string","suggestCreateNewBase":false,"newBaseName":null},
  "confidence": 0.75,
  "needHumanReview": true,
  "reviewReasons": ["string"]
}
"""
    }
}

/**
 * Top-level helpers used by [ParsedAnalysis] (which lives outside the
 * [IngestOrchestrator] class, so it can't reach the class-private
 * extensions). Each one is the minimum-viable version of what the
 * orchestrator's own `List<String>.toJsonArray()` /
 * `String.escapeJson()` would produce — they only need to handle the
 * small inputs the analysis stage feeds them.
 */
private fun encodeTagArray(tags: List<String>): String =
    if (tags.isEmpty()) "[]"
    else tags.joinToString(",", "[", "]") { "\"${it.escapeForJson()}\"" }

private fun String.escapeForJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

/**
 * Append a free-form note as a JSON gap object inside an existing
 * gaps array. Used when we want to surface "AI didn't return valid JSON"
 * alongside any AI-emitted gaps without overwriting them.
 */
private fun appendGapEntry(existingGapsJson: String, note: String?): String {
    if (note.isNullOrBlank()) return existingGapsJson
    val arr = runCatching { JSONArray(existingGapsJson) }.getOrNull() ?: JSONArray()
    val noteObj = JSONObject()
        .put("gap", note)
        .put("whyItMatters", "修复后才能完整重建实体/概念/关系")
        .put("suggestedAction", "ask_user")
    arr.put(noteObj)
    return arr.toString()
}
