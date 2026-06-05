package com.my.knowledge.domain.fragment

import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.dao.KnowledgeFragmentChainDao
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.entity.KnowledgeFragmentChainEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.KnowledgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * FRAG-1.3: synthesize a chain's entity/concept/source pages into one
 * distilled `wiki_synthesis` markdown article. The output is intended to
 * be displayed as a "成品" knowledge artifact and archived into the KB
 * after user confirmation (see FRAG-1.6 archive flow).
 *
 * Reasoning effort: the gateway pulls `KnowledgeManager.modelConfig.reasoningEffort`
 * (ARCH-8 wiring), so the caller does not pass it explicitly. The default
 * for synthesis work is `HIGH` (see design FRAG-1 §5.6 / §9). Callers that
 * want a different effort should set `KnowledgeManager.modelConfig.reasoningEffort`
 * before calling.
 *
 * Failure modes: if the gateway has no API key configured, or the LLM
 * returns empty, the call throws — the worker catches and retries per
 * WorkManager backoff (DISTILL_READY → DISTILL_READY, never advances to
 * RECOMMEND_READY until a non-empty markdown comes back).
 */
class DistillationEngine(
    private val db: AppDatabase,
    private val aiGateway: AiGateway = AiGateway(),
) {
    private val chainDao: KnowledgeFragmentChainDao = db.fragmentChainDao()
    private val itemDao: KnowledgeItemDao = db.knowledgeItemDao()

    /**
     * Run the LLM distillation. Returns the [DistillationResult] which
     * includes the generated markdown length, the new item id, and
     * source page count. The chain is advanced to `RECOMMEND_READY`
     * after the new item is persisted.
     */
    suspend fun distill(chainId: String): DistillationResult = withContext(Dispatchers.IO) {
        val chain = chainDao.getById(chainId) ?: error("chain $chainId not found")
        val thread = db.knowledgeThreadDao().getById(chain.threadId)
            ?: error("thread ${chain.threadId} not found")
        val kbBase = db.knowledgeBaseDao().getById(chain.knowledgeBaseId)
            ?: error("kb ${chain.knowledgeBaseId} not found")
        val relatedPages = loadRelatedPages(chain, thread)
        if (relatedPages.isEmpty()) {
            error("chain $chainId has no wiki pages to distill")
        }

        val coreQuestion = thread.coreQuestion.ifBlank { chain.goalSummary }
        val language = com.my.knowledge.data.ai.LanguageDetector.detect(coreQuestion)
        val systemPrompt = AiPromptTemplates.distillationPrompt(
            kbName = kbBase.name,
            coreQuestion = coreQuestion,
            materials = relatedPages.joinToString("\n\n") { page -> "## ${page.title}\n\n${page.contentMarkdown.take(4_000)}" },
            language = language,
        )
        val userMessage = "请基于以上材料,生成一篇结构严谨、引用清晰的中文综合性文章。"

        if (KnowledgeManager.modelConfig.apiKey.isBlank()) {
            error("LLM not configured (modelConfig.apiKey blank); user must add an API key first")
        }
        val markdown = aiGateway.complete(
            systemPrompt = systemPrompt,
            userMessage = userMessage,
        )
        if (markdown.isBlank()) {
            error("LLM returned empty markdown for chain $chainId")
        }

        val newItem = persistDistilledItem(chain, markdown)
        chainDao.setDistilledItemId(chain.id, newItem.id, System.currentTimeMillis())
        chainDao.updateStatus(
            chain.id,
            LifecycleStatus.RECOMMEND_READY.name,
            System.currentTimeMillis(),
        )
        DistillationResult(
            chainId = chain.id,
            distilledItemId = newItem.id,
            markdownLength = markdown.length,
            sourcePageCount = relatedPages.size,
        )
    }

    private suspend fun loadRelatedPages(
        chain: KnowledgeFragmentChainEntity,
        thread: com.my.knowledge.data.db.entity.KnowledgeThreadEntity,
    ): List<KnowledgeItemEntity> {
        val wikiPages = itemDao.getAllWikiByKb(chain.knowledgeBaseId)
        val allowedHeadings = runCatching {
            val arr = org.json.JSONArray(thread.mainlineJson.ifBlank { "[]" })
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
        return if (allowedHeadings.isEmpty()) {
            wikiPages
        } else {
            wikiPages.filter { page -> allowedHeadings.any { page.title.contains(it) || it.contains(page.title) } }
                .ifEmpty { wikiPages }
        }
    }

    private suspend fun persistDistilledItem(
        chain: KnowledgeFragmentChainEntity,
        markdown: String,
    ): KnowledgeItemEntity {
        val now = System.currentTimeMillis()
        val item = KnowledgeItemEntity(
            id = "synth_${chain.id}_${UUID.randomUUID().toString().take(8)}",
            sourceId = null,
            knowledgeBaseId = chain.knowledgeBaseId,
            title = "📚 ${chain.title.ifBlank { "知识链综合" }}",
            contentMarkdown = markdown,
            excerpt = markdown.take(160).replace("\n", " "),
            sourceType = "wiki_synthesis",
            status = KnowledgeItemEntity.STATUS_RECOMMEND_READY,
            contentHash = com.my.knowledge.data.util.Sha256.hex(markdown),
            sourceTraceJson = """[{"chainId":"${chain.id}","createdAt":$now}]""",
            confidence = 1.0f,
            summary = markdown.take(200).replace("\n", " "),
            tagsJson = "[]",
            rawNoteId = null,
            importance = 3,
            createdAt = now,
            updatedAt = now,
            processedAt = now,
            archivedAt = null,
            deletedAt = null,
            starredAt = null,
        )
        itemDao.insert(item)
        return item
    }

    data class DistillationResult(
        val chainId: String,
        val distilledItemId: String,
        val markdownLength: Int,
        val sourcePageCount: Int,
    )
}
