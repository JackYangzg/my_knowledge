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
import com.my.knowledge.data.repository.InspirationThreadContext
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
        val newItemId = inputData.getString("newItemId") ?: return Result.failure()
        val triggerType = inputData.getString("triggerType") ?: "inspiration_added"

        val db = AppDatabase.getInstance(applicationContext)
        val repository = newRepository(db)
        val base = repository.getBaseById(kbId) ?: return Result.failure()
        // 只对灵感库和普通 KB 启用 LLM 脉络;system / unfiled 跳过
        if (base.type != "inspiration" && base.type != "normal") {
            return Result.success()
        }

        val inspirationCtx: InspirationThreadContext = try {
            repository.getInspirationContext(kbId, newItemId)
        } catch (e: Exception) {
            return Result.retry()
        }

        val newInspiration: AiPromptTemplates.NewInspiration = inspirationCtx.newInspiration
        val detectedLanguage = com.my.knowledge.data.ai.LanguageDetector
            .detect(newInspiration.content.ifBlank { newInspiration.title })

        val inputHash = computeInputHash(inspirationCtx)
        val existing = repository.getThreadByKb(kbId)
        if (existing != null && existing.inputHash == inputHash) {
            return Result.success()
        }

        val systemPrompt = AiPromptTemplates.inspirationThreadPrompt(
            kbName = base.name,
            newInspiration = newInspiration,
            historicalInspirationDigest = inspirationCtx.historicalInspirationDigest,
            existingThread = inspirationCtx.existingThread,
            language = detectedLanguage,
        )
        val userMessage = "请基于以上灵感脉络上下文,生成 JSON 输出。"

        val llmConfigured = KnowledgeManager.modelConfig.apiKey.isNotBlank()
        val generated = if (llmConfigured) {
            try {
                val raw = AiGateway().chatJson(
                    systemPrompt = systemPrompt,
                    userPrompt = userMessage,
                    schemaHint = THREAD_JSON_SCHEMA,
                    temperature = 0.2f,
                )
                val cleaned = raw.cleanModelOutput()
                if (cleaned.isBlank() || cleaned.startsWith("[")) null
                else parseThreadJson(cleaned)
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
            triggerId = newItemId,
            beforeHash = existing?.let { sha256(it.mainlineJson + it.relationsJson) },
            afterHash = sha256(thread.mainlineJson + thread.relationsJson),
            summary = buildString {
                if (generated != null) {
                    append("LLM 灵感脉络更新:${thread.mainlineJson.countMainlineSegments()} 条主线,")
                    append("${thread.relationsJson.countRelationSegments()} 条关联")
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

    // ---- Hash & helpers --------------------------------------------------

    private fun computeInputHash(
        ctx: InspirationThreadContext,
    ): String {
        val ni: AiPromptTemplates.NewInspiration = ctx.newInspiration
        val pieces = buildList {
            add(ni.id)
            add(ni.title)
            add(ni.content)
            add(ni.summary)
            add(ni.tags.joinToString(","))
            for (d in ctx.historicalInspirationDigest) {
                add(d.id)
                add(d.title)
            }
        }
        return sha256(pieces.joinToString("\n"))
    }

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

    internal data class ParsedEvolved(val label: String, val before: String, val after: String) {
        fun toJson(): String =
            "{\"label\":\"${label.escape()}\",\"before\":\"${before.escape()}\",\"after\":\"${after.escape()}\"}"
        private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    }

    companion object {
        const val DIFF_SENTINEL = "<!--DIFF-V1:"

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
