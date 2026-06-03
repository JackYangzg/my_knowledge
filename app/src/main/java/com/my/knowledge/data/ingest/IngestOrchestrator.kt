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
import com.my.knowledge.data.parser.defaultParsers
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private class IngestNetworkPause(message: String) : Exception(message)

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

class IngestOrchestrator(
    private val db: AppDatabase,
    private val fileStore: LocalFileStore,
    private val repository: KnowledgeRepository,
    private val ai: AiGateway = AiGateway(),
    private val scheduler: com.my.knowledge.data.processing.ProcessingTaskScheduler? = null
) {
    private val fragmenter = MarkdownFragmenter()
    private val wikiCompiler = WikiPageCompiler()

    // Mirrors llm_wiki's `withProjectLock` (src/lib/project-mutex.ts):
    // every task runs under a single Mutex so two ingest workers (or a
    // watcher-induced import during a manual ingest) cannot race each
    // other while both are rewriting `wiki/index.md`.
    private val ingestMutex = Mutex()

    suspend fun runUntilIdle(maxTasks: Int = 20) {
        repeat(maxTasks) {
            val task = db.processingTaskDao().getNextPendingTask() ?: return
            ingestMutex.withLock { runTask(task) }
        }
    }

    private suspend fun runTask(task: ProcessingTaskEntity) {
        val startedAt = System.currentTimeMillis()
        markRunning(task, startedAt)
        appendLog(task, "开始 ${taskLabel(task.taskType)}", "running")
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
            if (task.taskType in setOf("parse", "analysis", "generation") && isIngestCacheHit(task)) {
                val now = System.currentTimeMillis()
                db.sourceDocumentDao().updateStatus(
                    task.sourceId ?: task.targetId,
                    SourceDocumentEntity.STATUS_GENERATED,
                    null,
                    now
                )
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
                return
            }
            when (task.taskType) {
                "parse" -> parseTask(task)
                "analysis" -> analysisTask(task)
                "generation" -> generationTask(task)
                "embedding" -> embeddingTask(task)
                else -> markSuccess(task, "Unsupported task skipped", "{}")
            }
        } catch (e: IngestNetworkPause) {
            val now = System.currentTimeMillis()
            db.processingTaskDao().update(
                task.copy(
                    status = "pending_network",
                    errorMessage = e.message,
                    currentStep = "等待网络恢复",
                    updatedAt = now,
                    finishedAt = null
                )
            )
            appendLog(
                task,
                "网络波动，已暂停 ${taskLabel(task.taskType)}，联网后继续：${e.message ?: "等待网络恢复"}",
                "pending_network",
                "等待网络恢复"
            )
            task.sourceId?.let {
                db.sourceDocumentDao().updateStatus(it, SourceDocumentEntity.STATUS_IMPORTED, "等待网络恢复", now)
            }
            throw e
        } catch (e: Exception) {
            val retry = task.retryCount + 1
            val now = System.currentTimeMillis()
            val failImmediately = shouldFailImmediately(task)
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
                    "${taskLabel(task.taskType)}失败，等待重试：${e.message ?: "未知错误"}"
                } else {
                    "${taskLabel(task.taskType)}失败：${e.message ?: "未知错误"}"
                },
                if (willRetry) "pending" else "failed"
            )
            task.sourceId?.let {
                if (!willRetry) {
                    db.knowledgeItemDao().updateFailureBySourceId(it, e.message, now)
                    db.sourceDocumentDao().updateStatus(it, SourceDocumentEntity.STATUS_FAILED, e.message, now)
                }
            }
        }
    }

    private suspend fun shouldFailImmediately(task: ProcessingTaskEntity): Boolean {
        if (task.taskType != "parse") return false
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: return false
        return source.sourceType == "image" || source.mimeType?.startsWith("image/") == true
    }

    private suspend fun isIngestCacheHit(task: ProcessingTaskEntity): Boolean {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: return false
        if (source.sha256.isBlank()) return false
        val previous = db.sourceDocumentDao().findBySha256(source.sha256) ?: return false
        // Only treat as a hit if a DIFFERENT source row already completed
        // the pipeline end-to-end (status = generated). The new source
        // row is still "imported", so we compare against the existing one.
        return previous.id != source.id && previous.status == SourceDocumentEntity.STATUS_GENERATED
    }

    private suspend fun parseTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_PARSING, null, System.currentTimeMillis())
        db.knowledgeItemDao().updateStatusBySourceId(source.id, KnowledgeItemEntity.STATUS_PROCESSING, System.currentTimeMillis())
        updateProgress(task, 15, "解析文件 ${source.title}", "正在解析 ${source.mimeType ?: source.sourceType} 内容")

        val parser = defaultParsers().first { it.supports(source.mimeType, source.sourceType) }
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
        val fragments = fragmenter.split(parsedEntity, source.targetKnowledgeBaseId.orEmpty())
        db.knowledgeFragmentDao().insertAll(fragments)
        updateProgress(task, 80, "生成 ${fragments.size} 个知识切片", "切片完成，准备进入分析阶段")
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_PARSED, null, now)
        markSuccess(task, "Parsed ${source.title}", """{"parsedContentId":"${parsedEntity.id}"}""")
        enqueue(source.id, "analysis", 9, """{"parsedContentId":"${parsedEntity.id}"}""")
    }

    private suspend fun analysisTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        val parsed = db.parsedContentDao().getLatestBySource(source.id) ?: error("Parsed content not found")
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_ANALYZING, null, System.currentTimeMillis())
        db.knowledgeItemDao().updateStatusBySourceId(source.id, KnowledgeItemEntity.STATUS_PROCESSING, System.currentTimeMillis())
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
            db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_IMPORTED, "等待模型配置", now)
            db.knowledgeItemDao().updateFailureBySourceId(source.id, "请先在设置中配置模型 API Key", now)
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
        val rawAiOutput = requestAiAnalysis(source, parsed)
            ?.takeIf { it.isNotBlank() && !it.startsWith("[") }
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

    private suspend fun generationTask(task: ProcessingTaskEntity) {
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

        // Generate wiki pages (Step 2: AI-driven or Template-driven)
        val aiOutput = if (ai.isAvailable()) {
            updateProgress(task, 50, "调用 AI 生成 wiki 页面", "等模型返回 FILE 块")
            requestAiRawOutput(source, parsed, analysis)
        } else {
            updateProgress(task, 50, "使用模板生成 wiki 页面", "未配置 AI Key，走本地模板")
            null
        }

        // llm_wiki writes the model's FILE blocks directly (with parser
        // safety + merge guards) and only relies on deterministic fallback
        // when the model omits a page. Mirror that here: AI-generated
        // entity/concept pages are the primary content, so their body depth
        // matches llm_wiki; the local compiler only fills missing source /
        // index / overview / log / entity / concept pages.
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

        val writtenItems = pageDrafts.mapIndexed { index, draft ->
            val existingPage = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)
            val isListingPage = draft.sourceType == "wiki_index" || draft.sourceType == "wiki_overview"
            val mergedMarkdown = if (isListingPage) {
                // 关键修复: 之前 `draft.markdown` 是无脑覆盖——新文档导入后
                // wiki_index / wiki_overview 的 body 只剩新来源的条目,
                // 历史来源的实体/概念/源全被擦掉(用户体感是"历史的实体、
                // 概念、源全部不可见了")。现在用 section-aware merge:
                // 1) frontmatter 数组(related/sources/tags)走 `wikiCompiler.merge`
                //    拿到 case-insensitive union;
                // 2) body 按 `## 标题` 段拆,每个段独立做"老 bullet + 新 bullet"
                //    合并 + 按 wikilink 去重;
                // 3) 排序保持稳定——历史条目在前,新增条目追加在末尾。
                mergeListingPage(
                    existing = existingPage?.contentMarkdown.orEmpty(),
                    incoming = draft.markdown,
                    pageTitle = draft.title,
                )
            } else if (existingPage != null && ai.isAvailable()) {
                val aiMerged = requestAiMerge(existingPage.contentMarkdown, draft.markdown, source.title)
                // Body-shrink sanity check (matches llm_wiki's BODY_SHRINK_THRESHOLD).
                if (aiMerged.length < ((existingPage.contentMarkdown.length + draft.markdown.length) / 2) * 7 / 10) {
                    wikiCompiler.merge(existingPage.contentMarkdown, draft.markdown, draft.title)
                } else {
                    aiMerged
                }
            } else {
                wikiCompiler.merge(existingPage?.contentMarkdown.orEmpty(), draft.markdown, draft.title)
            }
            if (index % 3 == 0) {
                updateProgress(task, 60 + (index * 5 / pageDrafts.size.coerceAtLeast(1)), "写入 wiki 页面 ${index + 1}/${pageDrafts.size}", "已合并 ${draft.title}")
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

        // Process Review Blocks
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

        db.knowledgeItemDao().updateItemCount(kbId)

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
        // The injected `repository` is the same instance the rest of the app uses;
        // it owns the graph rebuild logic and the right DAO set. Building a
        // second repository here (the old code) was a layer leak and made the
        // orchestrator responsible for every DAO.
        updateProgress(task, 90, "重建知识图谱", "实体 ${writtenItems.count { it.sourceType == "wiki_entity" }}，概念 ${writtenItems.count { it.sourceType == "wiki_concept" }}")
        repository.refreshOverviewForBase(kbId)
        repository.rebuildGraphForBase(kbId)
        // Sweep pending reviews now that the graph is up to date. A
        // review for "missing-page" or "duplicate" that points at a
        // page that we just generated gets auto-resolved, so the user
        // doesn't have to re-dismiss stale items after every ingest.
        SweepReviews(db).sweep(kbId)
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_GENERATED, null, now)
        markSuccess(task, "Generated ${writtenItems.size} processed wiki pages", """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
        enqueue(source.id, "embedding", 5, """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
        // Recompute the knowledge base's mainline / gaps / suggestions
        // whenever a new generation lands. The worker is responsible for
        // the actual computation (input hash, tag cluster, wikilink
        // graph) and writes the resulting thread + log atomically; the
        // inline log row here is just a user-visible breadcrumb.
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
                    scheduler?.scheduleThreadUpdate(kbId)
                }
            }
        }
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

    private suspend fun embeddingTask(task: ProcessingTaskEntity) {
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
            else -> "synthesis"
        }
        val sourceType = when {
            path.startsWith("wiki/entities/") || path.contains("/entities/") -> "wiki_entity"
            path.startsWith("wiki/concepts/") || path.contains("/concepts/") -> "wiki_concept"
            path.endsWith("/index.md") || path == "wiki/index.md" -> "wiki_index"
            path.endsWith("/overview.md") || path == "wiki/overview.md" -> "wiki_overview"
            path.endsWith("/log.md") || path == "wiki/log.md" -> "wiki_log"
            path.startsWith("wiki/sources/") || path.contains("/sources/") -> "wiki_source"
            else -> "wiki_ai_generated"
        }
        val title = frontMatterValue(cleaned, "title")
            ?: path.substringAfterLast("/").removeSuffix(".md")
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

    private suspend fun requestAiRawOutput(
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

        // System prompt mirrors llm_wiki's "You are a wiki maintainer."
        // The full language directive is injected BOTH at the head of the
        // user prompt and at the tail (handled inside generationPrompt).
        // The output is the raw FILE-block text we feed into
        // FileBlockParser; cleaning the think block here keeps reasoning
        // out of the persisted wiki content even if a future
        // AiGateway.change forgets to do it at the boundary.
        val response = ai.complete(
            systemPrompt = systemPrompt,
            userMessage = userPrompt
        )
        val cleaned = with(AiTextCleaner) { response.cleanModelOutput() }
        throwIfTransientAiFailure(cleaned)
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
        throwIfTransientAiFailure(cleaned)
        return if (cleaned.startsWith("---")) cleaned else {
            // Fallback to template merge if AI output is invalid
            wikiCompiler.merge(existingContent, incomingContent, sourceTitle)
        }
    }

    private suspend fun requestAiAnalysis(
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
            content = parsed.markdown.take(50_000),
            sourceType = source.sourceType,
            currentIndex = currentIndex,
            purpose = purpose,
            fragments = emptyList(),
            // The schema hint is what makes the LLM actually emit the
            // entities / concepts / relations structure that the
            // downstream WikiPageCompiler + KnowledgeRepositoryImpl
            // expect. Without it the model defaults to the markdown
            // `## Key Entities` format and parseAiAnalysisJson ends up
            // with empty arrays — which is exactly the bug we are
            // fixing here.
            schemaHint = ANALYSIS_SCHEMA,
            language = detectedLanguage
        )
        val userPrompt = buildAnalysisUserMessage(source, parsed.markdown.take(50_000))

        // `chatJson` appends "只输出严格 JSON，不要 Markdown，不要解释。\nSchema:\n..."
        // to the system prompt and pins temperature to 0.2. That dual
        // signal (system + suffix + schema hint) is what keeps small /
        // mid-size models honest about emitting pure JSON. We still
        // call AiTextCleaner.cleanModelOutput afterwards to strip
        // <think>...</think> blocks the gateway may have left in.
        val raw = ai.chatJson(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            schemaHint = ANALYSIS_SCHEMA,
            temperature = 0.1f
        )
        val cleaned = with(AiTextCleaner) { raw.cleanModelOutput() }
        throwIfTransientAiFailure(cleaned)
        return cleaned.takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    private fun throwIfTransientAiFailure(text: String) {
        if (isTransientAiFailure(text)) {
            throw IngestNetworkPause(text)
        }
    }

    private fun isTransientAiFailure(text: String): Boolean {
        val value = text.trim()
        return value.startsWith("[DNS 失败]") ||
            value.startsWith("[连接失败]") ||
            value.startsWith("[超时]") ||
            value.startsWith("[限流]") ||
            value.startsWith("[服务端错误]") ||
            (value.startsWith("[AI 调用异常]") && value.containsNetworkKeyword())
    }

    private fun String.containsNetworkKeyword(): Boolean {
        val lower = lowercase()
        return listOf(
            "network",
            "timeout",
            "timed out",
            "unable to resolve",
            "failed to connect",
            "connection reset",
            "connection abort",
            "enetunreach",
            "ehostunreach",
            "断网",
            "网络",
            "超时",
            "连接"
        ).any { lower.contains(it) }
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
        }
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
        appendLine(sourceContent.take(50_000).ifBlank { "(empty file)" })
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
        val now = System.currentTimeMillis()
        db.processingTaskDao().insert(
            ProcessingTaskEntity(
                id = UUID.randomUUID().toString(),
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
                startedAt = startedAt,
                updatedAt = startedAt
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
        private const val WIKI_PURPOSE = "建立一个可读、可维护、可进化的本地知识库（Wiki），用于深度学习和长期记忆。"
        private const val WIKI_SCHEMA = """
# Wiki Schema

| Type | Directory | Purpose |
|------|-----------|---------|
| entity | wiki/entities/ | Named things: people, organizations, products, tools, datasets, systems, projects, places, source works |
| concept | wiki/concepts/ | Ideas, methods, techniques, mechanisms, theories, principles, frameworks, problems |
| source | wiki/sources/ | Source summaries for imported files, articles, PDFs, images, notes |
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
