package com.my.knowledge.data.ai

import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.SearchEngine
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * AI 全库对话检索融合 (T3)
 *
 * 把 AskViewModel.searchRelevantResults 拆出来独立可测。
 * 多源:本地跨库 + 共现 tag 关系图 + (可选) web。
 *
 * 默认行为:
 * - local 全库: 跨库 top-16,返回 top-8
 * - graph (共现 tag 关系图): 默认开, KB > 200 条目跳过(性能护栏)
 * - web: 默认关(成本 + 幻觉)
 *
 * 共现 tag 关系图算法 (coTagExpand):
 *   - topHits ≤ 8
 *   - 对每个 hit 的 tags,在候选 KB 内找同 tag 的 sibling
 *   - 排除 topHits 自身
 *   - 跳过 0 tag 的条目
 *   - 按 (siblingId 维度) 的共现 tag 总数排序, top 4
 *   - 单 KB 内 take(50) 限速防止最坏情况
 */
data class RetrievalHit(
    val item: KnowledgeItemEntity,
    /** LABEL_SOURCE / LABEL_RELATED — 决定 UI 配色 */
    val label: String = AskCitationRowLabels.SOURCE,
    /** 与 topHits 的共现 tag 数 (LABEL_RELATED 时有意义) */
    val coTagCount: Int = 0,
)

object AskCitationRowLabels {
    const val SOURCE = "来自原文"
    const val RELATED = "相关"
}

class AskRetrievalPipeline(
    private val searchEngine: SearchEngine,
    private val knowledgeRepository: KnowledgeRepository,
    private val webSearch: WebSearch? = null,
) {
    /**
     * @param scopeType KNOWLEDGE_ITEM / KNOWLEDGE_BASE / THREAD / GLOBAL
     * @param askGraphEnabled 共现 tag 关系图扩展开关
     * @param askWebEnabled web search 开关(还需要 webSearch != null)
     */
    suspend fun search(
        question: String,
        scopeType: String,
        scopeId: String,
        askGraphEnabled: Boolean = true,
        askWebEnabled: Boolean = false,
    ): List<RetrievalHit> {
        val topHits = localSearch(question, scopeType, scopeId)
        val items = topHits.mapNotNull { knowledgeRepository.getItemById(it.itemId) }
            .distinctBy { it.id }

        val relatedItems: List<RetrievalHit> = if (
            askGraphEnabled && items.isNotEmpty() && scopeType != ScopeType.KNOWLEDGE_ITEM
        ) {
            coTagExpand(items)
        } else emptyList()

        val webHits: List<RetrievalHit> = if (
            askWebEnabled && webSearch != null && question.isNotBlank()
        ) {
            try {
                webSearch.search(question, top = 3)
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        val sources = items.map { RetrievalHit(item = it, label = AskCitationRowLabels.SOURCE) }
        return (sources + relatedItems + webHits)
            .distinctBy { it.item.id }
            .take(if (scopeType == ScopeType.GLOBAL) 16 else 8)
    }

    /**
     * 走 SearchEngine 跨库检索,返回原始 KnowledgeSearchResult (轻量,不带 content)。
     * KnowledgeItemEntity 化留到上层 pipeline 用 repository.getItemById 时再做 batch。
     */
    private suspend fun localSearch(
        question: String,
        scopeType: String,
        scopeId: String,
    ): List<com.my.knowledge.data.search.KnowledgeSearchResult> {
        return when (scopeType) {
            ScopeType.KNOWLEDGE_ITEM -> {
                val item = knowledgeRepository.getItemById(scopeId) ?: return emptyList()
                listOf(
                    com.my.knowledge.data.search.KnowledgeSearchResult(
                        itemId = item.id,
                        fragmentId = null,
                        knowledgeBaseId = item.knowledgeBaseId,
                        title = item.title,
                        snippet = item.contentMarkdown.take(8000),
                        sourceType = item.sourceType,
                        score = 3f,
                        matchType = "item_scope"
                    )
                )
            }
            ScopeType.KNOWLEDGE_BASE -> {
                if (scopeId.isBlank()) emptyList()
                else searchEngine.searchResults(question, scopeId, 8)
            }
            ScopeType.THREAD -> {
                val thread = knowledgeRepository.getThreadByKb(scopeId) ?: return emptyList()
                searchEngine.searchResults(question, thread.knowledgeBaseId, 8)
            }
            ScopeType.GLOBAL -> {
                // 跨库 top-16,后面 coTagExpand 取前 8
                searchEngine.searchResults(question, null, 16).take(8)
            }
            else -> emptyList()
        }
    }

    /**
     * 共现 tag 关系图扩展 (T3 内层算法)
     *
     * 对每个 top hit:
     *   1. 拿 hit.tagsJson
     *   2. 在 hit.knowledgeBaseId 内找 sibling(item != self, hit.knowledgeBaseId)
     *   3. sibling 与 hit 共 tag 数累加
     *
     * 排序:按 sibling 维度总 tag 数 desc → top 4
     *
     * 护栏:
     * - KB > 200 条目跳过 (性能,实测 8 hits × 200 sibs = 1600 iter)
     * - KB 内 siblings.take(50) (再保险)
     * - 0 tag 的 hit 跳过
     * - 跳过 sibling 自身
     */
    private suspend fun coTagExpand(topHits: List<KnowledgeItemEntity>): List<RetrievalHit> {
        if (topHits.isEmpty()) return emptyList()

        val coOccurrence = mutableMapOf<String, Int>()  // siblingId -> tag共现总数
        val topIds = topHits.map { it.id }.toSet()
        val json = Json { ignoreUnknownKeys = true }

        topHits.forEach { hit ->
            val hitTags = parseTagsJson(hit.tagsJson, json)
            if (hitTags.isEmpty()) return@forEach

            // KB > 200 跳过 (性能护栏)。observeItemCount 是 Flow,first() 取当前快照。
            val kbItemCount = knowledgeRepository.observeItemCount(hit.knowledgeBaseId).first()
            if (kbItemCount > 200) return@forEach

            // 取 KB 内前 50 个条目作 sibling 候选
            val siblings = knowledgeRepository.observeItemsByKb(
                kbId = hit.knowledgeBaseId,
                limit = 50,
                offset = 0,
            ).first()
            siblings.forEach { sibling ->
                if (sibling.id in topIds) return@forEach
                val siblingTags = parseTagsJson(sibling.tagsJson, json)
                val shared = hitTags.intersect(siblingTags).size
                if (shared > 0) {
                    coOccurrence[sibling.id] = (coOccurrence[sibling.id] ?: 0) + shared
                }
            }
        }

        return coOccurrence.entries
            .sortedByDescending { it.value }
            .take(4)
            .mapNotNull { (siblingId, count) ->
                knowledgeRepository.getItemById(siblingId)?.let { item ->
                    RetrievalHit(
                        item = item,
                        label = AskCitationRowLabels.RELATED,
                        coTagCount = count,
                    )
                }
            }
    }

    /**
     * 解析 tagsJson。格式:`["tag1","tag2"]`
     * 用 kotlinx.serialization 比手写解析更稳;失败时返回空 list。
     */
    private fun parseTagsJson(tagsJson: String, json: Json): List<String> {
        if (tagsJson.isBlank() || tagsJson == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(tagsJson)
        }.getOrDefault(emptyList())
    }
}

/**
 * Web search 接口 (T3 P1 stub)
 *
 * 默认 AskRetrievalPipeline 不带 webSearch (构造参数可空)。
 * 后续接入时实现这个接口:Google Custom Search / Tavily / Bing 都行。
 */
interface WebSearch {
    suspend fun search(query: String, top: Int = 3): List<RetrievalHit>
}