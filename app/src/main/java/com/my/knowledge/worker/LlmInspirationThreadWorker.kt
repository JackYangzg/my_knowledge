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
import com.my.knowledge.data.repository.InspirationReEvolveContext
import com.my.knowledge.data.repository.InspirationThreadContext
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.data.util.Sha256
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 灵感脉络 —— LLM 脉络 worker(双模式)。
 *
 * 触发:
 *   - **incremental** 模式 —— 每新增 / 编辑一条灵感,NoteEditorViewModel.saveToKnowledgeBase
 *     调 [com.my.knowledge.data.processing.ProcessingTaskScheduler.scheduleLlmThreadUpdate]
 *     调度本 worker,带 `newItemId`。
 *   - **re_evolve** 模式 —— 用户在灵感空间 / 知识库详情页点「重新演化」按钮,
 *     ViewModel 调同一个 scheduler,但传 `mode = "re_evolve"`、`newItemId = null`,
 *     worker 读现有脉络 + 最近 N 条灵感 full content 整体重写。
 *
 * 流程:
 *   1. 按 `mode` 拿输入上下文:incremental 走 [KnowledgeRepository.getInspirationContext]
 *      (1 条新灵感 + 30 条历史摘要 + 现有脉络);re-evolve 走
 *      [KnowledgeRepository.getInspirationReEvolveContext]
 *      (N 条最近灵感 full content + 25 条历史摘要 + 现有脉络当草稿);
 *   2. 算 input hash,如果跟现有 thread 的 inputHash 一致,跳过 LLM;
 *   3. 拼 [AiPromptTemplates.inspirationThreadPrompt](按 mode 走不同分支),
 *      调 LLM(用 chatJson 拿到严格 JSON);
 *   4. 解析 LLM 输出,写回 KnowledgeThreadEntity;
 *   5. 失败 / 不可用 / 解析失败 → 双轨 fallback:
 *      - incremental + 没有旧脉络 → 本地 tag 聚类(buildFallbackThread),保证 UI 不会空;
 *      - incremental + 有旧脉络 → 本地 tag 聚类(覆盖旧脉络,保持「新增触发就有新结果」语义);
 *      - re_evolve + 有旧脉络 → 保留旧脉络,只 append 一条 threadLog 提示;
 *      - re_evolve + 没有旧脉络 → 本地 tag 聚类占位(冷启动)。
 *
 * 关于 diff 字段:incremental 模式 LLM 输出含 diff,序列化进 threadLog 的 summary 末尾
 * (以 <!--DIFF-V1: ... --> 哨兵开头)。re-evolve 模式整脉络在重写,diff 没意义,跳过。
 */
class LlmInspirationThreadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kbId = inputData.getString("knowledgeBaseId") ?: return Result.failure()
        val newItemId = inputData.getString("newItemId")
        val triggerType = inputData.getString("triggerType") ?: "inspiration_added"
        val mode = inputData.getString("mode")
            ?: if (newItemId.isNullOrBlank()) "re_evolve" else "incremental"

        val db = AppDatabase.getInstance(applicationContext)
        val repository = newRepository(db)
        val base = repository.getBaseById(kbId) ?: return Result.failure()
        // 只对灵感库和普通 KB 启用 LLM 脉络;system / unfiled 跳过
        if (base.type != "inspiration" && base.type != "normal") {
            return Result.success()
        }

        // 按 mode 拿输入上下文 + 算 input hash
        val incrementalCtx: InspirationThreadContext?
        val reEvolveCtx: InspirationReEvolveContext?
        val inputHash: String
        val existingThreadSnapshot: AiPromptTemplates.ExistingThreadSnapshot?
        val detectedLanguage: String
        when (mode) {
            "re_evolve" -> {
                incrementalCtx = null
                reEvolveCtx = try {
                    repository.getInspirationReEvolveContext(kbId)
                } catch (e: Exception) {
                    return Result.retry()
                }
                inputHash = computeInputHashForReEvolve(reEvolveCtx)
                existingThreadSnapshot = reEvolveCtx.existingThread
                val anchor = reEvolveCtx.recentInspiration.firstOrNull()
                detectedLanguage = com.my.knowledge.data.ai.LanguageDetector
                    .detect(anchor?.content?.ifBlank { anchor.title } ?: base.name)
            }
            else -> { // "incremental"
                if (newItemId.isNullOrBlank()) return Result.failure()
                incrementalCtx = try {
                    repository.getInspirationContext(kbId, newItemId)
                } catch (e: Exception) {
                    return Result.retry()
                }
                reEvolveCtx = null
                inputHash = computeInputHash(incrementalCtx)
                existingThreadSnapshot = incrementalCtx.existingThread
                val ni = incrementalCtx.newInspiration
                detectedLanguage = com.my.knowledge.data.ai.LanguageDetector
                    .detect(ni.content.ifBlank { ni.title })
            }
        }

        val existing = repository.getThreadByKb(kbId)
        if (existing != null && existing.inputHash == inputHash) {
            return Result.success()
        }

        val systemPrompt = when (mode) {
            "re_evolve" -> AiPromptTemplates.inspirationThreadPrompt(
                kbName = base.name,
                historicalInspirationDigest = reEvolveCtx!!.historicalInspirationDigest,
                existingThread = existingThreadSnapshot,
                language = detectedLanguage,
                newInspiration = null,
                recentInspiration = reEvolveCtx.recentInspiration,
            )
            else -> AiPromptTemplates.inspirationThreadPrompt(
                kbName = base.name,
                historicalInspirationDigest = incrementalCtx!!.historicalInspirationDigest,
                existingThread = existingThreadSnapshot,
                language = detectedLanguage,
                newInspiration = incrementalCtx.newInspiration,
                recentInspiration = emptyList(),
            )
        }
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

        // re-evolve + 有旧脉络 + LLM 失败 → 保留旧脉络,只 append log。
        if (generated == null && mode == "re_evolve" && existing != null) {
            val log = KnowledgeThreadLogEntity(
                id = UUID.randomUUID().toString(),
                threadId = existing.id,
                triggerType = triggerType,
                triggerId = "re_evolve",
                beforeHash = sha256(existing.mainlineJson + existing.relationsJson),
                afterHash = sha256(existing.mainlineJson + existing.relationsJson),
                summary = "LLM 不可用 / 失败,保留旧脉络(re-evolve 模式)",
                createdAt = System.currentTimeMillis()
            )
            repository.appendThreadLog(log)
            repository.updateBase(base.copy(threadStatus = "ready", updatedAt = System.currentTimeMillis()))
            return Result.success()
        }

        val thread = if (generated != null) {
            buildThreadEntity(
                existing = existing,
                kbId = kbId,
                inputHash = inputHash,
                generated = generated,
            )
        } else {
            // incremental + LLM 失败,或 re-evolve 冷启动(existing == null)且 LLM 失败 → 本地 fallback
            buildFallbackThread(
                existing = existing,
                kbId = kbId,
                inputHash = inputHash,
                base = base,
                items = db.knowledgeItemDao().getAllByKb(kbId).filter { it.deletedAt == null },
            )
        }

        repository.saveThread(thread)

        // diff 序列化进 threadLog;re-evolve 模式整脉络在重写,diff 无意义,跳过
        val diffBlob = if (mode == "incremental") {
            generated?.diff?.let { serializeDiff(it) } ?: ""
        } else ""
        val log = KnowledgeThreadLogEntity(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            triggerType = triggerType,
            triggerId = newItemId ?: "re_evolve",
            beforeHash = existing?.let { sha256(it.mainlineJson + it.relationsJson) },
            afterHash = sha256(thread.mainlineJson + thread.relationsJson),
            summary = buildString {
                if (generated != null) {
                    val modeLabel = if (mode == "re_evolve") "重新演化" else "增量更新"
                    append("LLM 灵感脉络$modeLabel:${thread.mainlineJson.countMainlineSegments()} 条主线,")
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

    /**
     * re-evolve 模式的输入 hash:把最近 N 条灵感的 id/title/content/summary/tags
     * 和历史摘要一起 hash。跟 [computeInputHash] 区别在于「锚」是 N 条而不是 1 条,
     * 这样只要 N 条里任意一条变动 (或现有脉络存在 / 不存在变化) 就会触发 LLM
     * 重写,跟「手动重新演化」的语义对齐 —— 用户只要再点一次按钮、且最近灵感
     * 有任何变化,就该出新结果。
     *
     * existing thread 的快照也参与 hash,因为重新演化 = 「拿当前草稿 + 当前最近灵感
     * 整体重写」,草稿换了就该重算。
     */
    private fun computeInputHashForReEvolve(
        ctx: InspirationReEvolveContext,
    ): String {
        val pieces = buildList {
            add("mode=re_evolve")
            for (ni in ctx.recentInspiration) {
                add(ni.id)
                add(ni.title)
                add(ni.content)
                add(ni.summary)
                add(ni.tags.joinToString(","))
            }
            for (d in ctx.historicalInspirationDigest) {
                add(d.id)
                add(d.title)
            }
            val existing = ctx.existingThread
            if (existing != null) {
                add(existing.description)
                add(existing.coreQuestion)
                add(existing.mainline.joinToString("|"))
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

    private fun sha256(content: String): String = Sha256.hex(content)

    private fun parseStringArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { it.isNotBlank() } }
    }

    private fun jsonArray(values: List<String>): String =
        JSONArray(values).toString()

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
        fun toJson(): String = JSONObject()
            .put("from", from)
            .put("to", to)
            .put("relation", relation)
            .toString()
    }

    private data class ParsedDiff(
        val newMainlineSegments: List<String> = emptyList(),
        val evolvedSegments: List<ParsedEvolved> = emptyList(),
        val obsoleteSegments: List<String> = emptyList(),
    )

    internal data class ParsedEvolved(val label: String, val before: String, val after: String) {
        fun toJson(): String = JSONObject()
            .put("label", label)
            .put("before", before)
            .put("after", after)
            .toString()
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
