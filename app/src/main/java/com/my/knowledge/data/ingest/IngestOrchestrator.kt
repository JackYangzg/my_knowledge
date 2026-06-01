package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.parser.defaultParsers
import java.util.UUID

class IngestOrchestrator(
    private val db: AppDatabase,
    private val fileStore: LocalFileStore
) {
    private val fragmenter = MarkdownFragmenter()
    private val wikiCompiler = WikiPageCompiler()

    suspend fun runUntilIdle(maxTasks: Int = 20) {
        repeat(maxTasks) {
            val task = db.processingTaskDao().getNextPendingTask() ?: return
            runTask(task)
        }
    }

    private suspend fun runTask(task: ProcessingTaskEntity) {
        val startedAt = System.currentTimeMillis()
        markRunning(task, startedAt)
        try {
            when (task.taskType) {
                "parse" -> parseTask(task)
                "analysis" -> analysisTask(task)
                "generation" -> generationTask(task)
                "embedding" -> embeddingTask(task)
                else -> markSuccess(task, "Unsupported task skipped", "{}")
            }
        } catch (e: Exception) {
            val retry = task.retryCount + 1
            db.processingTaskDao().update(
                task.copy(
                    status = if (retry < task.maxRetry) "pending" else "failed",
                    retryCount = retry,
                    errorMessage = e.message,
                    updatedAt = System.currentTimeMillis(),
                    finishedAt = if (retry < task.maxRetry) null else System.currentTimeMillis()
                )
            )
            task.sourceId?.let {
                db.knowledgeItemDao().updateFailureBySourceId(it, e.message, System.currentTimeMillis())
                db.sourceDocumentDao().updateStatus(it, SourceDocumentEntity.STATUS_FAILED, e.message, System.currentTimeMillis())
            }
        }
    }

    private suspend fun parseTask(task: ProcessingTaskEntity) {
        val sourceId = task.sourceId ?: task.targetId
        val source = db.sourceDocumentDao().getById(sourceId) ?: error("Source not found: $sourceId")
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_PARSING, null, System.currentTimeMillis())
        db.knowledgeItemDao().updateStatusBySourceId(source.id, KnowledgeItemEntity.STATUS_PROCESSING, System.currentTimeMillis())

        val parser = defaultParsers().first { it.supports(source.mimeType, source.sourceType) }
        val parsed = parser.parse(source)
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
        db.knowledgeFragmentDao().insertAll(fragmenter.split(parsedEntity, source.targetKnowledgeBaseId.orEmpty()))
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

        if (!AiGateway().isAvailable()) {
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
            return
        }

        val fragments = db.knowledgeFragmentDao().getBySource(source.id)
        val summary = parsed.plainText.trim().take(220)
        val tags = extractTags("${source.title} ${parsed.plainText}")
        val entities = tags.take(8)
        val confidence = if (parsed.plainText.length > 80) 0.78f else 0.42f
        val reviewReason = if (confidence < 0.6f) "内容较短，建议人工确认摘要和归档" else null
        val fallbackAnalysisJson = IngestJsonValidator.fallbackAnalysisJson(
            title = source.title,
            summary = summary,
            tagsJson = tags.toJsonArray(),
            confidence = confidence,
            reviewReason = reviewReason
        )
        val aiAnalysisJson = requestAiAnalysis(source, parsed, fragments)
        val validatedAnalysisJson = IngestJsonValidator.normalizeAnalysisJson(aiAnalysisJson, fallbackAnalysisJson)
        val analysisObj = IngestJsonValidator.parseObjectOrNull(validatedAnalysisJson)
        val archiveJson = """{"targetKnowledgeBaseId":${source.targetKnowledgeBaseId?.let { "\"$it\"" } ?: "null"},"targetKnowledgeBaseName":"","confidence":$confidence,"reason":"基于标题、标签和来源提示推荐","suggestCreateNewBase":false,"newBaseName":null}"""
        val analysis = AnalysisResultEntity(
            id = UUID.randomUUID().toString(),
            sourceId = source.id,
            parsedContentId = parsed.id,
            summary = analysisObj?.let { IngestJsonValidator.string(it, "summary", summary) } ?: summary,
            tagsJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "tags") } ?: tags.toJsonArray(),
            entitiesJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "entities") } ?: entities.toJsonArray(),
            conceptsJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "concepts") } ?: tags.toJsonArray(),
            relationsJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "relations") } ?: "[]",
            claimsJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "claims") } ?: fragments.take(5).map { it.content.take(120) }.toJsonArray(),
            gapsJson = analysisObj?.let { IngestJsonValidator.arrayAsJson(it, "gaps") } ?: reviewReason?.let { listOf(it).toJsonArray() } ?: "[]",
            archiveRecommendationJson = analysisObj?.let { IngestJsonValidator.archiveRecommendationJson(it, archiveJson) } ?: archiveJson,
            confidence = analysisObj?.let { IngestJsonValidator.float(it, "confidence", confidence) } ?: confidence,
            modelName = if (aiAnalysisJson != null) "configured-ai" else null,
            promptVersion = PromptVersions.INGEST_ANALYSIS_V1,
            analysisHash = fileStore.sha256Text(parsed.parseHash + tags.joinToString()),
            createdAt = System.currentTimeMillis()
        )
        db.analysisResultDao().insert(analysis)
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

        // Generate wiki pages (Step 2: AI-driven or Template-driven)
        val pageDrafts = if (AiGateway().isAvailable()) {
            requestAiGeneration(source, parsed, analysis)
        } else {
            wikiCompiler.compile(source, parsed, analysis)
        }
        
        val writtenItems = pageDrafts.mapIndexed { index, draft ->
            val existingPage = db.knowledgeItemDao().getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)
            val mergedMarkdown = if (existingPage != null && AiGateway().isAvailable()) {
                requestAiMerge(existingPage.contentMarkdown, draft.markdown, source.title)
            } else {
                existingPage?.let { wikiCompiler.merge(it.contentMarkdown, draft.markdown) } ?: draft.markdown
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
        db.knowledgeItemDao().updateItemCount(kbId)
        // ... rest of the method

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
        com.my.knowledge.data.repository.KnowledgeRepositoryImpl(
            db.knowledgeBaseDao(),
            db.knowledgeItemDao(),
            db.processingTaskDao(),
            db.archiveRecommendationDao(),
            db.aiConversationDao(),
            db.aiMessageDao(),
            db.knowledgeThreadDao(),
            db.knowledgeThreadLogDao(),
            db.sourceManifestDao(),
            db.knowledgeFragmentDao(),
            db.processingTaskLogDao(),
            db.askCitationDao(),
            db.knowledgeGraphDao(),
            db.reviewItemDao()
        ).rebuildGraphForBase(kbId)
        db.sourceDocumentDao().updateStatus(source.id, SourceDocumentEntity.STATUS_GENERATED, null, now)
        markSuccess(task, "Generated ${writtenItems.size} processed wiki pages", """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
        enqueue(source.id, "embedding", 5, """{"rootItemId":"${rootItem.id}","processedItemIds":[${writtenItems.joinToString(",") { "\"${it.id}\"" }}]}""")
    }

    private suspend fun embeddingTask(task: ProcessingTaskEntity) {
        // Fragment embeddings are already maintained by repository rebuilds; this task keeps the queue explicit.
        markSuccess(task, "Embedding task acknowledged", "{}")
    }

    private suspend fun requestAiGeneration(
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        analysis: AnalysisResultEntity
    ): List<WikiPageDraft> {
        val ai = AiGateway()
        val currentIndex = buildCurrentIndex(source.targetKnowledgeBaseId)
        val analysisBrief = "Summary: ${analysis.summary}\nTags: ${analysis.tagsJson}\nEntities: ${analysis.entitiesJson}\nConcepts: ${analysis.conceptsJson}"

        val userPrompt = com.my.knowledge.data.ai.AiPromptTemplates.generationPrompt(
            fileName = source.title,
            analysisResult = analysisBrief,
            sourceContent = parsed.markdown,
            currentIndex = currentIndex
        )

        val response = ai.complete(
            systemPrompt = "You are a wiki generation assistant. Reason internally but output only FILE blocks as requested. Start immediately with '---FILE:'.",
            userMessage = userPrompt
        )

        val blocks = FileBlockParser.parse(response)
        return blocks.map { block ->
            WikiPageDraft(
                type = when {
                    block.path.contains("/sources/") -> "source"
                    block.path.contains("/entities/") -> "entity"
                    block.path.contains("/concepts/") -> "concept"
                    else -> "synthesis"
                },
                title = block.path.substringAfterLast("/").removeSuffix(".md"),
                sourceType = "wiki_ai_generated",
                markdown = block.content,
                summary = analysis.summary,
                tagsJson = analysis.tagsJson,
                sourceTraceJson = """{"wikiPath":"${block.path.escapeJson()}","sourceId":"${source.id}","parsedContentId":"${parsed.id}"}"""
            )
        }
    }

    private suspend fun requestAiMerge(
        existingContent: String,
        incomingContent: String,
        sourceTitle: String
    ): String {
        val ai = AiGateway()
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
            wikiCompiler.merge(existingContent, incomingContent)
        }
    }

    private suspend fun requestAiAnalysis(
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        fragments: List<com.my.knowledge.data.db.entity.KnowledgeFragmentEntity>
    ): String? {
        val ai = AiGateway()
        if (!ai.isAvailable()) return null

        val currentIndex = buildCurrentIndex(source.targetKnowledgeBaseId)
        val userPrompt = com.my.knowledge.data.ai.AiPromptTemplates.analysisPrompt(
            title = source.title,
            content = parsed.markdown.take(6000),
            sourceType = source.sourceType,
            currentIndex = currentIndex,
            fragments = fragments.take(12).map { "${it.id}: ${it.content.take(160)}" }
        )

        return ai.chatJson(
            systemPrompt = "You are an expert research analyst for a local wiki ingest pipeline. Do not output chain-of-thought. Read the source and produce concise, structured JSON analysis for later wiki page generation. Every entity, concept, claim, relation, gap, and page recommendation must be grounded in source evidence.",
            userPrompt = userPrompt,
            schemaHint = ANALYSIS_SCHEMA,
            temperature = 0.2f
        ).takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    private suspend fun buildCurrentIndex(kbId: String?): String {
        if (kbId.isNullOrBlank()) return "No existing index."
        val pages = db.knowledgeItemDao().getAllByKb(kbId)
            .filter { it.sourceType.startsWith("wiki_") }
            .take(120)
        if (pages.isEmpty()) return "No existing wiki pages."
        return pages.joinToString("\n") { "- ${it.title} (${it.sourceType.removePrefix("wiki_")})" }
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
        db.processingTaskDao().update(
            task.copy(status = "running", progress = 5, currentStep = task.taskType, startedAt = startedAt, updatedAt = startedAt)
        )
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

    private fun emptyJsonObject() = kotlinx.serialization.json.buildJsonObject { }

    private companion object {
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
