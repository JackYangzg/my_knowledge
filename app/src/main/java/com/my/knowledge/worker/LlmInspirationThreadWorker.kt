package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.ai.AiTextCleaner.cleanModelOutput
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.ui.KnowledgeManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 灵感脉络 —— 增量 LLM 脉络 worker。
 *
 * 触发:每新增一条灵感,NoteEditorViewModel.saveToKnowledgeBase
 *       调 [com.my.knowledge.data.processing.ProcessingTaskScheduler.scheduleLlmThreadUpdate]
 *       调度本 worker。
 *
 * 流程:
 *   1. [KnowledgeRepository.getInspirationContext] 一次性拿齐输入
 *      (本次新灵感 / 历史摘要 / 现有脉络);
 *   2. 算 input hash,如果跟现有 thread 的 inputHash 一致,跳过 LLM;
 *   3. 拼 [AiPromptTemplates.inspirationThreadPrompt],调 LLM(用 chatJson
 *      拿到严格 JSON);
 *   4. 解析 LLM 输出,写回 KnowledgeThreadEntity;
 *   5. 失败 / 不可用 / 解析失败 → fallback:本地 tag 聚类,保证 UI 不会空。
 *
 * 1:1 对齐 llm_wiki 的两步 ingest 设计 —— 这里是"增量分析 + 增量写入",
 * 跟 [ThreadEvolutionWorker] 的全量程序化算法是双轨:LLM 优先,启发式兜底。
 *
 * 关于 diff 字段:prompt 里有,但 KnowledgeThreadEntity 当前没 diff 列。
 * 本版把 diff 序列化进 threadLog 的 summary 末尾(以 <!--DIFF-V1: ... -->
 * 哨兵开头),后续 schema 升级时再拆出独立列。这样:
 *   - 不用触发 Room schema 迁移
 *   - 老 threadLog 没 diff,UI 解析时 fallback 为空 diff
 *   - 不影响主线 / 关联 / 缺口 / 下一步的核心数据
 */
class LlmInspirationThreadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kbId = inputData.getString("knowledgeBaseId") ?: return Result.failure()
        val triggerItemId = inputData.getString("newItemId")
        val triggerType = inputData.getString("triggerType") ?: "inspiration_added"

        val db = AppDatabase.getInstance(applicationContext)
        val repository = newRepository(db)
        val base = repository.getBaseById(kbId) ?: return Result.failure()
        // 灵感脉络只由灵感空间的新建 / 编辑 / 手动刷新触发,不再和 ingest 关联。
        if (base.type != "inspiration") {
            return Result.success()
        }

        val items = db.knowledgeItemDao().getAllByKb(kbId)
            .filter { it.deletedAt == null }
            .sortedWith(compareBy<KnowledgeItemEntity> { it.createdAt }.thenBy { it.updatedAt })
            .map { it.toInspirationSnapshot() }
        val triggerItem = triggerItemId
            ?.let { id -> items.firstOrNull { it.id == id } }
            ?: items.maxByOrNull { it.updatedAt }
        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector.detect(
            (triggerItem?.content ?: items.takeLast(5).joinToString("\n") { it.title + "\n" + it.content })
                .ifBlank { base.name }
        )

        val inputHash = computeInputHash(kbId, items)
        val existing = repository.getThreadByKb(kbId)
        if (existing != null && existing.inputHash == inputHash) {
            return Result.success()
        }
        val existingSnapshot = existing?.toSnapshot()

        val llmConfigured = KnowledgeManager.modelConfig.apiKey.isNotBlank()
        val generated = if (llmConfigured) {
            try {
                generateThreadWithBudget(
                    kbName = base.name,
                    items = items,
                    triggerItem = triggerItem,
                    existingThread = existingSnapshot,
                    language = detectedLanguage,
                )
            } catch (e: Exception) {
                null
            }
        } else null

        val thread = if (generated != null) {
            buildThreadEntity(
                existing = existing,
                kbId = kbId,
                inputHash = inputHash,
                generated = generated,
            )
        } else {
            buildFallbackThread(
                existing = existing,
                kbId = kbId,
                inputHash = inputHash,
                base = base,
                items = db.knowledgeItemDao().getAllByKb(kbId).filter { it.deletedAt == null },
            )
        }

        repository.saveThread(thread)

        // diff 序列化进 threadLog,让前端能可靠检测"本次新增 / 演变 / 废弃"
        val diffBlob = generated?.diff?.let { serializeDiff(it) } ?: ""
        val log = KnowledgeThreadLogEntity(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            triggerType = triggerType,
            triggerId = triggerItem?.id ?: kbId,
            beforeHash = existing?.let { sha256(it.mainlineJson + it.relationsJson) },
            afterHash = sha256(thread.mainlineJson + thread.relationsJson),
            summary = buildString {
                if (generated != null) {
                    append("LLM 灵感脉络更新:${thread.mainlineJson.countMainlineSegments()} 条主线,")
                    append("${thread.relationsJson.countRelationSegments()} 条关联")
                    if (items.joinToString("\n") { it.title + it.content }.length > MODEL_CONTEXT_CHAR_LIMIT) {
                        append(",已按 100000 字上下文上限分批合并")
                    }
                } else {
                    append("LLM 不可用 / 失败,使用程序化 fallback")
                }
                if (diffBlob.isNotEmpty()) append("\n$DIFF_SENTINEL$diffBlob-->")
            },
            createdAt = System.currentTimeMillis()
        )
        repository.appendThreadLog(log)
        repository.updateBase(base.copy(threadStatus = "ready", updatedAt = System.currentTimeMillis()))
        return Result.success()
    }

    // ---- LLM 解析 --------------------------------------------------------

    private fun parseThreadJson(raw: String): ParsedThread? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val description = obj.optString("description").trim()
        val coreQuestion = obj.optString("coreQuestion").trim()
        if (description.isBlank() && coreQuestion.isBlank()) return null
        return ParsedThread(
            description = description,
            coreQuestion = coreQuestion,
            mainline = obj.optJSONArray("mainline")?.toStringList() ?: emptyList(),
            relations = obj.optJSONArray("relations")?.toRelationList() ?: emptyList(),
            gaps = obj.optJSONArray("gaps")?.toStringList() ?: emptyList(),
            nextSuggestions = obj.optJSONArray("nextSuggestions")?.toStringList() ?: emptyList(),
            diff = parseDiff(obj.optJSONObject("diff")),
        )
    }

    private fun parseDiff(obj: JSONObject?): ParsedDiff {
        if (obj == null) return ParsedDiff()
        return ParsedDiff(
            newMainlineSegments = obj.optJSONArray("newMainlineSegments")?.toStringList() ?: emptyList(),
            evolvedSegments = obj.optJSONArray("evolvedSegments")?.toEvolvedList() ?: emptyList(),
            obsoleteSegments = obj.optJSONArray("obsoleteSegments")?.toStringList() ?: emptyList(),
        )
    }

    private fun buildThreadEntity(
        existing: KnowledgeThreadEntity?,
        kbId: String,
        inputHash: String,
        generated: ParsedThread,
    ): KnowledgeThreadEntity = KnowledgeThreadEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        knowledgeBaseId = kbId,
        description = generated.description.take(2_000),
        coreQuestion = generated.coreQuestion.take(500),
        mainlineJson = jsonArray(generated.mainline.take(5).map { it.take(150) }),
        relationsJson = jsonArray(generated.relations.take(8).map { it.toJson() }),
        gapsJson = jsonArray(generated.gaps.take(5).map { it.take(200) }),
        nextSuggestionsJson = jsonArray(generated.nextSuggestions.take(5).map { it.take(200) }),
        inputHash = inputHash,
        version = (existing?.version ?: 0) + 1,
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    // ---- Fallback --------------------------------------------------------

    private fun buildFallbackThread(
        existing: KnowledgeThreadEntity?,
        kbId: String,
        inputHash: String,
        base: com.my.knowledge.data.db.entity.KnowledgeBaseEntity,
        items: List<KnowledgeItemEntity>,
    ): KnowledgeThreadEntity {
        val tagSet = items.flatMap { parseStringArray(it.tagsJson) }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        val titleLine = items.sortedBy { it.createdAt }.take(5).map { it.title }.joinToString(" → ")
        val description = if (items.isEmpty()) {
            "知识库「${base.name}」尚无灵感,稍后再来整理脉络"
        } else {
            "知识库「${base.name}」已收录 ${items.size} 条灵感${if (tagSet.isNotEmpty()) ",集中在 ${tagSet.joinToString("、")}" else ""}。"
        }
        val mainline = if (titleLine.isBlank()) emptyList() else listOf("近期主线:$titleLine")
        val gaps = if (items.size < 3) listOf("灵感数量较少,继续添加后脉络会更清晰") else emptyList()
        val nextSuggestions = listOf("继续记录灵感,或把成熟的灵感整理到具体知识库")

        return KnowledgeThreadEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            knowledgeBaseId = kbId,
            description = description,
            coreQuestion = if (tagSet.isNotEmpty()) "如何理解和应用${tagSet.first()}相关主题?" else "探索当前灵感库的主题",
            mainlineJson = jsonArray(mainline),
            relationsJson = "[]",
            gapsJson = jsonArray(gaps),
            nextSuggestionsJson = jsonArray(nextSuggestions),
            inputHash = inputHash,
            version = (existing?.version ?: 0) + 1,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ---- LLM generation with 100k context cap ---------------------------

    private suspend fun generateThreadWithBudget(
        kbName: String,
        items: List<InspirationItemSnapshot>,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        language: String,
    ): ParsedThread? {
        if (items.isEmpty()) return null
        val fullPrompt = buildFullThreadPrompt(kbName, items, triggerItem, existingThread, language)
        if (fullPrompt.requestSize() <= MODEL_CONTEXT_CHAR_LIMIT) {
            return callThreadLlm(fullPrompt)
        }

        val batches = buildItemBatches(items)
        val partials = batches.mapIndexedNotNull { index, batch ->
            val prompt = buildBatchThreadPrompt(
                kbName = kbName,
                batchIndex = index + 1,
                batchTotal = batches.size,
                batch = batch,
                triggerItem = triggerItem,
                existingThread = existingThread,
                language = language,
            )
            callThreadLlm(prompt)
        }
        if (partials.isEmpty()) return null
        return mergePartialThreads(
            kbName = kbName,
            triggerItem = triggerItem,
            existingThread = existingThread,
            partials = partials,
            language = language,
        )
    }

    private suspend fun mergePartialThreads(
        kbName: String,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        partials: List<ParsedThread>,
        language: String,
    ): ParsedThread? {
        if (partials.size == 1) return partials.first()
        val prompt = buildMergeThreadPrompt(kbName, triggerItem, existingThread, partials, language)
        if (prompt.requestSize() <= MODEL_CONTEXT_CHAR_LIMIT) {
            return callThreadLlm(prompt)
        }
        val mergedGroups = partials.chunked(MERGE_PARTIALS_PER_ROUND).mapNotNull { group ->
            val groupPrompt = buildMergeThreadPrompt(kbName, triggerItem, existingThread, group, language)
            callThreadLlm(groupPrompt.takePromptBudget())
        }
        if (mergedGroups.isEmpty()) return null
        return mergePartialThreads(kbName, triggerItem, existingThread, mergedGroups, language)
    }

    private suspend fun callThreadLlm(systemPrompt: String): ParsedThread? {
        val raw = AiGateway().chatJson(
            systemPrompt = systemPrompt,
            userPrompt = "请基于以上灵感脉络上下文,生成 JSON 输出。",
            schemaHint = THREAD_JSON_SCHEMA,
            temperature = 0.2f,
        )
        val cleaned = raw.cleanModelOutput()
        return if (cleaned.isBlank() || cleaned.startsWith("[")) null else parseThreadJson(cleaned)
    }

    private fun buildItemBatches(items: List<InspirationItemSnapshot>): List<List<InspirationItemSnapshot>> {
        val batches = mutableListOf<MutableList<InspirationItemSnapshot>>()
        var current = mutableListOf<InspirationItemSnapshot>()
        var currentChars = 0
        for (item in items) {
            val size = item.renderForPrompt(MAX_SINGLE_ITEM_CONTENT_CHARS).length
            if (current.isNotEmpty() && currentChars + size > BATCH_BODY_CHAR_LIMIT) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += item
            currentChars += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun buildFullThreadPrompt(
        kbName: String,
        items: List<InspirationItemSnapshot>,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        language: String,
    ): String = buildThreadPromptHeader(
        title = "全量灵感脉络生成",
        kbName = kbName,
        triggerItem = triggerItem,
        existingThread = existingThread,
        language = language,
    ) {
        appendLine("### 全量灵感文件（按创建时间从旧到新）")
        items.forEachIndexed { index, item ->
            appendLine("#### ${index + 1}. ${item.title}")
            appendLine(item.renderForPrompt(MAX_SINGLE_ITEM_CONTENT_CHARS))
            appendLine()
        }
        appendThreadOutputRules()
    }

    private fun buildBatchThreadPrompt(
        kbName: String,
        batchIndex: Int,
        batchTotal: Int,
        batch: List<InspirationItemSnapshot>,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        language: String,
    ): String = buildThreadPromptHeader(
        title = "分批灵感脉络分析 $batchIndex/$batchTotal",
        kbName = kbName,
        triggerItem = triggerItem,
        existingThread = existingThread,
        language = language,
    ) {
        appendLine("### 当前批次灵感文件")
        appendLine("这是全量灵感库的第 $batchIndex/$batchTotal 批。请只分析本批内容，但要结合上一次脉络保持命名和主线稳定。")
        batch.forEachIndexed { index, item ->
            appendLine("#### $batchIndex.${index + 1}. ${item.title}")
            appendLine(item.renderForPrompt(MAX_SINGLE_ITEM_CONTENT_CHARS))
            appendLine()
        }
        appendThreadOutputRules(extra = "本批输出是中间结果，稍后会和其他批次合并。请提炼高信号主线，不要写批次说明。")
    }

    private fun buildMergeThreadPrompt(
        kbName: String,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        partials: List<ParsedThread>,
        language: String,
    ): String = buildThreadPromptHeader(
        title = "分批结果合并为最终灵感脉络",
        kbName = kbName,
        triggerItem = triggerItem,
        existingThread = existingThread,
        language = language,
    ) {
        appendLine("### 分批分析结果")
        appendLine("下面每个 JSON 都是某一批灵感的中间脉络。请合并为一个最终脉络，并结合上一次脉络进行演进。")
        partials.forEachIndexed { index, partial ->
            appendLine("#### 批次结果 ${index + 1}")
            appendLine(partial.toPromptJson())
            appendLine()
        }
        appendThreadOutputRules(extra = "合并时去重、收束相近主线，保留真实演进，不要简单拼接批次。")
    }

    private fun buildThreadPromptHeader(
        title: String,
        kbName: String,
        triggerItem: InspirationItemSnapshot?,
        existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
        language: String,
        body: StringBuilder.() -> Unit,
    ): String = buildString {
        appendLine(AiPromptTemplates.languageDirective(language))
        appendLine()
        appendLine("你是用户的灵感脉络编辑。你的任务是直接使用大模型整理灵感文件形成「我最近在想什么、推到了哪里、下一步该做什么」的可读主线。")
        appendLine("脉络生成只基于灵感文件的新建/编辑/手动刷新触发,不要引用 ingest、wiki 页面、图谱重建或外部知识。")
        appendLine()
        appendLine("## $title")
        appendLine("- 灵感知识库: $kbName")
        appendLine("- 请求上下文硬上限: $MODEL_CONTEXT_CHAR_LIMIT 字符；若内容过多，系统会分批合并。")
        if (triggerItem != null) {
            appendLine("- 本次触发文件: ${triggerItem.title} (${triggerItem.id})")
        }
        appendLine()
        if (existingThread != null) {
            appendExistingThread(existingThread)
        } else {
            appendLine("### 上一次灵感脉络")
            appendLine("(暂无，上一次脉络为空。本次从灵感文件直接生成。)")
            appendLine()
        }
        body()
        appendLine()
        appendLine(AiPromptTemplates.languageDirective(language))
    }

    private fun StringBuilder.appendExistingThread(existingThread: AiPromptTemplates.ExistingThreadSnapshot) {
        appendLine("### 上一次灵感脉络（演进起点）")
        appendLine("- 描述: ${existingThread.description}")
        appendLine("- 核心问题: ${existingThread.coreQuestion}")
        appendLine("- 主线:")
        existingThread.mainline.forEachIndexed { index, line -> appendLine("  ${index + 1}. $line") }
        appendLine("- 缺口:")
        existingThread.gaps.forEach { appendLine("  - $it") }
        appendLine("- 下一步:")
        existingThread.nextSuggestions.forEach { appendLine("  - $it") }
        appendLine()
    }

    private fun StringBuilder.appendThreadOutputRules(extra: String = "") {
        appendLine("## 输出要求")
        appendLine("严格输出 JSON。字段包括 description/coreQuestion/mainline/relations/gaps/nextSuggestions/diff。")
        appendLine("必须结合上一次灵感脉络进行演进:保留稳定主线,合并相近主题,标记新增/演化/废弃。")
        appendLine("mainline 1-5 条,relations 0-8 条,gaps 0-5 条,nextSuggestions 1-5 条。")
        appendLine("整体输出 <= 2000 字符。")
        if (extra.isNotBlank()) appendLine(extra)
    }

    private fun InspirationItemSnapshot.renderForPrompt(maxContentChars: Int): String = buildString {
        appendLine("- id: $id")
        appendLine("- 标题: $title")
        appendLine("- 创建/更新: $createdAtLabel / $updatedAtLabel")
        appendLine("- 标签: ${tags.joinToString("、").ifBlank { "(无)" }}")
        appendLine("- 摘要: ${summary.ifBlank { "(无摘要)" }}")
        appendLine("- 内容:")
        appendLine("```")
        val clipped = content.take(maxContentChars)
        appendLine(clipped)
        if (content.length > clipped.length) appendLine("...（内容过长,本批已截断 ${content.length - clipped.length} 字符）")
        appendLine("```")
    }

    private fun KnowledgeItemEntity.toInspirationSnapshot(): InspirationItemSnapshot =
        InspirationItemSnapshot(
            id = id,
            title = title,
            tags = parseStringArray(tagsJson),
            summary = summary?.takeIf { it.isNotBlank() } ?: excerpt,
            content = contentMarkdown,
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdAtLabel = formatDateLabel(createdAt),
            updatedAtLabel = formatDateLabel(updatedAt),
            contentHash = contentHash,
        )

    private fun KnowledgeThreadEntity.toSnapshot(): AiPromptTemplates.ExistingThreadSnapshot =
        AiPromptTemplates.ExistingThreadSnapshot(
            description = description,
            coreQuestion = coreQuestion,
            mainline = parseStringArray(mainlineJson),
            gaps = parseStringArray(gapsJson),
            nextSuggestions = parseStringArray(nextSuggestionsJson),
        )

    private fun ParsedThread.toPromptJson(): String {
        val obj = JSONObject()
        obj.put("description", description)
        obj.put("coreQuestion", coreQuestion)
        obj.put("mainline", JSONArray(mainline))
        obj.put("relations", JSONArray().also { arr ->
            relations.forEach { rel ->
                arr.put(JSONObject().put("from", rel.from).put("to", rel.to).put("relation", rel.relation))
            }
        })
        obj.put("gaps", JSONArray(gaps))
        obj.put("nextSuggestions", JSONArray(nextSuggestions))
        return obj.toString()
    }

    // ---- Hash & helpers --------------------------------------------------

    private fun computeInputHash(
        kbId: String,
        items: List<InspirationItemSnapshot>,
    ): String {
        val pieces = buildList {
            add(kbId)
            for (item in items) {
                add(item.id)
                add(item.title)
                add(item.tags.joinToString(","))
                add(item.summary)
                add(item.contentHash)
                add(item.updatedAt.toString())
            }
        }
        return sha256(pieces.joinToString("\n"))
    }

    private fun formatDateLabel(epochMs: Long): String {
        val date = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "%d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun String.requestSize(): Int = length + THREAD_JSON_SCHEMA.length + LLM_REQUEST_OVERHEAD_CHARS

    private fun String.takePromptBudget(): String =
        if (requestSize() <= MODEL_CONTEXT_CHAR_LIMIT) this
        else take((MODEL_CONTEXT_CHAR_LIMIT - THREAD_JSON_SCHEMA.length - LLM_REQUEST_OVERHEAD_CHARS).coerceAtLeast(10_000))

    private fun serializeDiff(diff: ParsedDiff): String {
        val obj = JSONObject()
        obj.put("newMainlineSegments", JSONArray(diff.newMainlineSegments))
        obj.put("evolvedSegments", JSONArray().also { arr ->
            diff.evolvedSegments.forEach { e -> arr.put(e.toJson()) }
        })
        obj.put("obsoleteSegments", JSONArray(diff.obsoleteSegments))
        return obj.toString()
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun parseStringArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { it.isNotBlank() } }
    }

    private fun jsonArray(values: List<String>): String =
        if (values.isEmpty()) "[]"
        else values.joinToString(",", "[", "]") { "\"${escape(it)}\"" }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun String.countMainlineSegments(): Int =
        if (length < 2) 0 else (count { it == '\"' } / 2).coerceAtLeast(0)

    private fun String.countRelationSegments(): Int {
        if (length < 2) return 0
        var depth = 0
        var inStr = false
        var escape = false
        var n = 0
        for (c in this) {
            if (inStr) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> if (depth == 0) { depth = 1; n++ } else depth++
                '}' -> if (depth > 0) depth--
            }
        }
        return n
    }

    private fun newRepository(db: AppDatabase): KnowledgeRepository = KnowledgeRepositoryImpl(
        db.knowledgeBaseDao(), db.knowledgeItemDao(),
        db.processingTaskDao(), db.archiveRecommendationDao(),
        db.aiConversationDao(), db.aiMessageDao(),
        db.knowledgeThreadDao(), db.knowledgeThreadLogDao(),
        db.sourceManifestDao(), db.knowledgeFragmentDao(),
        db.processingTaskLogDao(), db.askCitationDao(),
        db.knowledgeGraphDao(), db.reviewItemDao(),
        db.analysisResultDao(), db.parsedContentDao(),
        db.sourceDocumentDao()
    )

    // ---- Data holders ----------------------------------------------------

    private data class ParsedThread(
        val description: String,
        val coreQuestion: String,
        val mainline: List<String>,
        val relations: List<ParsedRelation>,
        val gaps: List<String>,
        val nextSuggestions: List<String>,
        val diff: ParsedDiff,
    )

    internal data class ParsedRelation(val from: String, val to: String, val relation: String) {
        fun toJson(): String =
            "{\"from\":\"${from.escape()}\",\"to\":\"${to.escape()}\",\"relation\":\"${relation.escape()}\"}"
        private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private data class ParsedDiff(
        val newMainlineSegments: List<String> = emptyList(),
        val evolvedSegments: List<ParsedEvolved> = emptyList(),
        val obsoleteSegments: List<String> = emptyList(),
    )

    private data class InspirationItemSnapshot(
        val id: String,
        val title: String,
        val tags: List<String>,
        val summary: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long,
        val createdAtLabel: String,
        val updatedAtLabel: String,
        val contentHash: String,
    )

    internal data class ParsedEvolved(val label: String, val before: String, val after: String) {
        fun toJson(): String =
            "{\"label\":\"${label.escape()}\",\"before\":\"${before.escape()}\",\"after\":\"${after.escape()}\"}"
        private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    }

    companion object {
        const val DIFF_SENTINEL = "<!--DIFF-V1:"
        private const val MODEL_CONTEXT_CHAR_LIMIT = 100_000
        private const val LLM_REQUEST_OVERHEAD_CHARS = 1_500
        private const val BATCH_BODY_CHAR_LIMIT = 78_000
        private const val MAX_SINGLE_ITEM_CONTENT_CHARS = 28_000
        private const val MERGE_PARTIALS_PER_ROUND = 24

        private val THREAD_JSON_SCHEMA = """
{
  "description": "string (2-3 句)",
  "coreQuestion": "string (1 句)",
  "mainline": ["string (60-100 字, 1-5 条)"],
  "relations": [{"from":"string","to":"string","relation":"string (1 句话)"}],
  "gaps": ["string (0-5 条)"],
  "nextSuggestions": ["string (1 句话, 1-5 条,以动词开头)"],
  "diff": {
    "newMainlineSegments": ["string"],
    "evolvedSegments": [{"label":"string","before":"string","after":"string"}],
    "obsoleteSegments": ["string"]
  }
}
""".trimIndent()
    }
}

// ---- JSONArray 工具扩展(file-private) -------------------------------------

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotBlank() } }

private fun JSONArray.toRelationList(): List<LlmInspirationThreadWorker.ParsedRelation> =
    (0 until length()).mapNotNull { idx ->
        val obj = optJSONObject(idx) ?: return@mapNotNull null
        val from = obj.optString("from").trim()
        val to = obj.optString("to").trim()
        val relation = obj.optString("relation").trim()
        if (from.isBlank() || to.isBlank()) null
        else LlmInspirationThreadWorker.ParsedRelation(from, to, relation)
    }

private fun JSONArray.toEvolvedList(): List<LlmInspirationThreadWorker.ParsedEvolved> =
    (0 until length()).mapNotNull { idx ->
        val obj = optJSONObject(idx) ?: return@mapNotNull null
        val label = obj.optString("label").trim()
        val before = obj.optString("before").trim()
        val after = obj.optString("after").trim()
        if (label.isBlank() && before.isBlank() && after.isBlank()) null
        else LlmInspirationThreadWorker.ParsedEvolved(label, before, after)
    }
