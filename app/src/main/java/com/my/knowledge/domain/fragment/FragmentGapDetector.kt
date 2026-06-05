package com.my.knowledge.domain.fragment

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.dao.KnowledgeFragmentChainDao
import com.my.knowledge.data.db.dao.KnowledgeFragmentGapDao
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeFragmentChainEntity
import com.my.knowledge.data.db.entity.KnowledgeFragmentGapEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * FRAG-1.2: core algorithm that materialises one [Chain] per
 * `knowledge_thread` row, derives the current [Gap] list from the live
 * `knowledge_item` rows, and updates the
 * `knowledge_fragment_chain` / `knowledge_fragment_gap` tables.
 *
 * v1 "card-driven" detector (Approach A in the design doc) — does NOT
 * call any LLM. Rules are a 1:1 mirror of
 * `ThreadEvolutionRunner.detectGaps` so the substring classifier in
 * MIGRATION_11_12 and the runtime detector stay in lockstep.
 *
 * Re-entrancy: safe. The work is keyed by `chainId` and uses REPLACE
 * semantics on the chain row, so concurrent re-runs collapse to the last
 * write. The `replaceForChain` DAO transaction drops + reinserts gap rows
 * inside a single transaction.
 */
class FragmentGapDetector(
    private val db: AppDatabase,
) {
    private val chainDao: KnowledgeFragmentChainDao = db.fragmentChainDao()
    private val gapDao: KnowledgeFragmentGapDao = db.fragmentGapDao()
    private val threadDao = db.knowledgeThreadDao()
    private val itemDao = db.knowledgeItemDao()
    private val graphDao = db.knowledgeGraphDao()
    private val baseDao = db.knowledgeBaseDao()

    /**
     * Run the full detection pass for one knowledge base. Returns the
     * chains that were written (same content as a follow-up read of
     * `chainDao.observeByKb(kbId)`).
     *
     * Skips unfiled KBs (`ThreadEvolutionRunner.runEvolution` does the
     * same — unfiled content has no thread yet, so there is nothing to
     * chain off).
     */
    suspend fun detectByKb(kbId: String): List<Chain> = withContext(Dispatchers.IO) {
        val base = baseDao.getById(kbId) ?: return@withContext emptyList()
        if (base.type == "unfiled") return@withContext emptyList()

        val threads = threadDao.listByKb(kbId)
        if (threads.isEmpty()) return@withContext emptyList()

        val allItems = itemDao.getAllByKb(kbId)
        val allEntities = graphDao.getAllEntitiesByKb(kbId)
        val allRelations = graphDao.getAllRelationsByKb(kbId)

        val now = System.currentTimeMillis()
        threads.map { thread ->
            val gaps = detectGaps(allItems, allEntities, allRelations)
            val status = classifyStatus(thread, gaps, allItems, allEntities, allRelations)
            val averageDegree = averageEntityDegree(allEntities, allRelations)
            val sourceCount = allItems.count { !it.sourceType.startsWith("wiki_") }
            val chainEntity = upsertChain(
                thread = thread,
                kbId = kbId,
                status = status,
                gapCount = gaps.size,
                entityCount = allEntities.size,
                sourceCount = sourceCount,
                now = now,
            )
            gapDao.replaceForChain(chainEntity.id, gaps.toEntities(chainEntity.id, now))
            Chain(
                id = chainEntity.id,
                title = chainEntity.title,
                goal = chainEntity.goalSummary,
                coreQuestion = thread.coreQuestion,
                confidence = chainEntity.confidence,
                entityCount = allEntities.size,
                sourceCount = sourceCount,
                gaps = gaps,
                status = status,
                averageDegree = averageDegree,
                threadId = thread.id,
            )
        }
    }

    /**
     * Recompute one chain by id (used by user "重新分析" + reanalysis
     * worker). No-op if the chain has been deleted.
     */
    suspend fun detectByChainId(chainId: String): Chain? = withContext(Dispatchers.IO) {
        val existing = chainDao.getById(chainId) ?: return@withContext null
        detectByKb(existing.knowledgeBaseId).firstOrNull { it.id == chainId }
    }

    // --- Internals ----------------------------------------------------

    private suspend fun upsertChain(
        thread: KnowledgeThreadEntity,
        kbId: String,
        status: LifecycleStatus,
        gapCount: Int,
        entityCount: Int,
        sourceCount: Int,
        now: Long,
    ): KnowledgeFragmentChainEntity {
        val existing = chainDao.getByThreadId(thread.id)
        val chain = if (existing != null) {
            existing.copy(
                knowledgeBaseId = kbId,
                title = thread.description.take(80).ifBlank { "知识脉络" },
                goalSummary = thread.description,
                confidence = threadConfidence(thread),
                entityCount = entityCount,
                sourceCount = sourceCount,
                gapCount = gapCount,
                status = status.name,
                updatedAt = now,
            )
        } else {
            KnowledgeFragmentChainEntity(
                id = thread.id,
                knowledgeBaseId = kbId,
                threadId = thread.id,
                title = thread.description.take(80).ifBlank { "知识脉络" },
                goalSummary = thread.description,
                confidence = threadConfidence(thread),
                entityCount = entityCount,
                sourceCount = sourceCount,
                gapCount = gapCount,
                status = status.name,
                distilledItemId = null,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (existing == null) chainDao.insert(chain) else chainDao.update(chain)
        return chain
    }

    private fun threadConfidence(thread: KnowledgeThreadEntity): Float = when {
        thread.gapsJson.isBlank() || thread.gapsJson == "[]" -> 0.8f
        else -> 0.5f
    }

    private fun averageEntityDegree(
        entities: List<KnowledgeEntityEntity>,
        relations: List<KnowledgeRelationEntity>,
    ): Double {
        if (entities.isEmpty()) return 0.0
        val ids = entities.map { it.id }.toSet()
        val degreeMap = HashMap<String, Int>()
        entities.forEach { degreeMap[it.id] = 0 }
        for (rel in relations) {
            if (rel.fromEntityId in ids) degreeMap[rel.fromEntityId] = (degreeMap[rel.fromEntityId] ?: 0) + 1
            if (rel.toEntityId in ids) degreeMap[rel.toEntityId] = (degreeMap[rel.toEntityId] ?: 0) + 1
        }
        return degreeMap.values.sum().toDouble() / entities.size
    }

    private suspend fun classifyStatus(
        thread: KnowledgeThreadEntity,
        gaps: List<Gap>,
        items: List<KnowledgeItemEntity>,
        entities: List<KnowledgeEntityEntity>,
        relations: List<KnowledgeRelationEntity>,
    ): LifecycleStatus {
        val existing = chainDao.getByThreadId(thread.id)
        if (existing?.status == LifecycleStatus.ARCHIVED.name) return LifecycleStatus.ARCHIVED
        if (existing?.status == LifecycleStatus.RECOMMEND_READY.name) {
            return if (gaps.isEmpty()) LifecycleStatus.RECOMMEND_READY else LifecycleStatus.NEED_REVIEW
        }
        if (gaps.isNotEmpty()) return LifecycleStatus.NEED_REVIEW
        val hasSynthesis = items.any { it.sourceType in SYNTHESIS_SOURCE_TYPES }
        val avgDegree = averageEntityDegree(entities, relations)
        return if (hasSynthesis && avgDegree >= 2.0) LifecycleStatus.DISTILL_READY
        else LifecycleStatus.NEED_REVIEW
    }

    /**
     * 1:1 with `ThreadEvolutionRunner.detectGaps` (FRAG-1 design §1.6).
     */
    private fun detectGaps(
        items: List<KnowledgeItemEntity>,
        entities: List<KnowledgeEntityEntity>,
        relations: List<KnowledgeRelationEntity>,
    ): List<Gap> {
        val gaps = mutableListOf<Gap>()
        if (items.isEmpty()) {
            gaps += gapOf(GapType.KB_EMPTY, "知识库尚无已整理的知识条目", "导入首批知识条目以启动脉络")
            return gaps
        }
        val wikiPages = items.filter { it.sourceType.startsWith("wiki_") }
        if (wikiPages.isEmpty()) {
            gaps += gapOf(
                GapType.NO_WIKI_PAGES,
                "还没有任何 wiki 页面，需要先完成知识加工",
                "先完成知识加工产出 wiki 页面",
            )
        }
        val synthesisCount = wikiPages.count { it.sourceType in SYNTHESIS_SOURCE_TYPES }
        if (synthesisCount == 0) {
            gaps += gapOf(
                GapType.MISSING_SYNTHESIS,
                "缺少 index / overview / log 合成页，无法形成主线",
                "补充 index / overview / log 合成页形成主线",
            )
        }
        val tagsMissing = items.count { it.parseTags().isEmpty() }
        if (tagsMissing > items.size / 2) {
            gaps += gapOf(
                GapType.MISSING_TAGS,
                "超过半数知识缺少标签",
                "为超过半数缺少标签的知识补充标签",
            )
        }
        val summaryMissing = items.count { it.summary.isNullOrBlank() }
        if (summaryMissing > items.size / 3) {
            gaps += gapOf(
                GapType.MISSING_SUMMARY,
                "部分知识缺少摘要，建议补充",
                "为缺少摘要的知识补充摘要",
            )
        }
        val mainlineEmpty = entities.isEmpty() || entities.none { it.weight > 0f }
        if (mainlineEmpty && wikiPages.isNotEmpty()) {
            gaps += gapOf(
                GapType.NO_MAINLINE,
                "未能识别到主线（标签聚类为空），建议补充更明确的标签",
                "补充更明确的标签以形成主线",
            )
        }
        if (relations.isEmpty() && entities.size >= 2) {
            gaps += gapOf(
                GapType.NO_RELATIONS,
                "知识之间没有形成显式引用或同主题关联",
                "建立知识之间的显式引用或同主题关联",
            )
        }
        val lowConfidence = items.count { it.confidence < LOW_CONFIDENCE_THRESHOLD }
        if (lowConfidence > 0) {
            gaps += gapOf(
                GapType.LOW_CONFIDENCE,
                "存在低置信度知识，需要人工复核",
                "人工复核低置信度知识条目",
            )
        }
        return gaps
    }

    private fun gapOf(type: GapType, description: String, suggestion: String): Gap = Gap(
        id = UUID.randomUUID().toString(),
        type = type,
        priority = type.priority,
        description = description,
        suggestion = suggestion,
        resolved = false,
    )

    private fun List<Gap>.toEntities(chainId: String, now: Long): List<KnowledgeFragmentGapEntity> {
        return map { gap ->
            KnowledgeFragmentGapEntity(
                id = gap.id,
                chainId = chainId,
                gapType = gap.type.name,
                priority = gap.priority.name,
                description = gap.description,
                suggestion = gap.suggestion,
                resolved = gap.resolved,
                resolvedByItemId = null,
                resolvedByUserText = null,
                resolvedAt = null,
                createdAt = now,
            )
        }
    }

    // --- Public result types -----------------------------------------

    data class Chain(
        val id: String,
        val title: String,
        val goal: String,
        val coreQuestion: String,
        val confidence: Float,
        val entityCount: Int,
        val sourceCount: Int,
        val gaps: List<Gap>,
        val status: LifecycleStatus,
        val averageDegree: Double,
        val threadId: String,
    )

    data class Gap(
        val id: String,
        val type: GapType,
        val priority: GapPriority,
        val description: String,
        val suggestion: String,
        val resolved: Boolean,
    )

    private fun KnowledgeItemEntity.parseTags(): List<String> {
        return try {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val SYNTHESIS_SOURCE_TYPES = setOf("wiki_index", "wiki_overview", "wiki_log")
        private const val LOW_CONFIDENCE_THRESHOLD = 0.5f
    }
}
