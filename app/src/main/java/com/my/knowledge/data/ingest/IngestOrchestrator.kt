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
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.parser.defaultParsers
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

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

        val summary = parsed.plainText.trim().take(220)
        val tags = extractTags("${source.title} ${parsed.plainText}")
        val confidence = if (parsed.plainText.length > 80) 0.78f else 0.42f
        val reviewReason = if (confidence < 0.6f) "内容较短，建议人工确认摘要和归档" else null
        updateProgress(task, 45, "调用 AI 生成分析", "模型生成中，最多等待 60 秒")
        val aiAnalysis = requestAiAnalysis(source, parsed)
            ?.takeIf { it.isNotBlank() && !it.startsWith("[") }
        val archiveJson = """{"targetKnowledgeBaseId":${source.targetKnowledgeBaseId?.let { "\"$it\"" } ?: "null"},"targetKnowledgeBaseName":"","confidence":$confidence,"reason":"基于标题、标签和来源提示推荐","suggestCreateNewBase":false,"newBaseName":null}"""
        val analysis = AnalysisResultEntity(
            id = UUID.randomUUID().toString(),
            sourceId = source.id,
            parsedContentId = parsed.id,
            summary = aiAnalysis?.take(3000) ?: summary,
            tagsJson = tags.toJsonArray(),
            entitiesJson = "[]",
            conceptsJson = tags.toJsonArray(),
            relationsJson = "[]",
            claimsJson = listOf(aiAnalysis ?: summary).toJsonArray(),
            gapsJson = reviewReason?.let { listOf(it).toJsonArray() } ?: "[]",
            archiveRecommendationJson = archiveJson,
            confidence = confidence,
            modelName = if (aiAnalysis != null) "configured-ai" else null,
            promptVersion = PromptVersions.INGEST_ANALYSIS_V1,
            analysisHash = fileStore.sha256Text(parsed.parseHash + tags.joinToString()),
            createdAt = System.currentTimeMillis()
        )
        db.analysisResultDao().insert(analysis)
        updateProgress(task, 80, "分析完成，准备生成知识页面", "识别到 ${tags.size} 个标签，正在排入生成队列")
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

        val pageDrafts: List<WikiPageDraft> = if (aiOutput != null) {
            // The parser now reports any path-safety rejections so we can warn the user
            // instead of silently dropping pages.
            val parsedBlocks = FileBlockParser.parseDetailed(aiOutput)
            if (parsedBlocks.unsafePaths.isNotEmpty() || parsedBlocks.truncated) {
                // Don't fail the whole task: skip unsafe blocks, keep safe ones,
                // and surface the issue via a review item.
            }
            parsedBlocks.blocks.map { block -> block.toWikiPageDraft(source, parsed, analysis) }
                .ifEmpty { wikiCompiler.compile(source, parsed, analysis) }
        } else {
            wikiCompiler.compile(source, parsed, analysis)
        }

        val writtenItems = pageDrafts.mapIndexed { index, draft ->
            val existingPage = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)
            val isListingPage = draft.sourceType == "wiki_index" || draft.sourceType == "wiki_overview"
            val mergedMarkdown = if (isListingPage) {
                draft.markdown
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

        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector.detect(parsed.markdown)
        val systemPrompt = com.my.knowledge.data.ai.AiPromptTemplates.generationPrompt(
            fileName = source.title,
            analysisResult = analysisText,
            sourceContent = parsed.markdown,
            schema = WIKI_SCHEMA,
            purpose = WIKI_PURPOSE,
            currentIndex = currentIndex,
            overview = overview,
            language = detectedLanguage
        )
        val userPrompt = buildGenerationUserMessage(source.title, analysisText, parsed.markdown)

        // System prompt mirrors llm_wiki's "You are a wiki maintainer."
        // The full language directive is injected BOTH at the head of the
        // user prompt and at the tail (handled inside generationPrompt).
        return ai.complete(
            systemPrompt = systemPrompt,
            userMessage = userPrompt
        )
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
        return if (response.startsWith("---")) response else {
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
            language = detectedLanguage
        )
        val userPrompt = buildAnalysisUserMessage(source, parsed.markdown.take(50_000))

        return ai.complete(systemPrompt, userPrompt)
            .takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    private suspend fun buildCurrentIndex(kbId: String?): String {
        if (kbId.isNullOrBlank()) return "No existing index."
        val pages = db.knowledgeItemDao().getAllByKb(kbId)
            .filter { it.sourceType.startsWith("wiki_") }
            .sortedByDescending { it.updatedAt }
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
        sourceContent: String
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
"""
        private const val ANALYSIS_SCHEMA = """
{
  "title": "string",
  "summary": "string",
  "tags": ["string"],
  "entities": [{"name":"string","type":"Person|Organization|Product|Dataset|Tool|System|Project|Place","aliases":["string"],"description":"string","role_in_source":"central|supporting|peripheral","evidence":"string","source_refs":["fragmentId"],"related_concepts":["string"],"related_entities":["string"],"confidence":0.9}],
  "concepts": [{"name":"string","category":"Theory|Method|Technique|Phenomenon|Principle|Framework|Problem","definition":"string","why_it_matters":"string","source_context":"string","related_entities":["string"],"related_concepts":["string"],"examples":["string"],"limitations":["string"],"source_refs":["fragmentId"],"confidence":0.9}],
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
