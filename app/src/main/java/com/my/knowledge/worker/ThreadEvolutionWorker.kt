package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.*

class ThreadEvolutionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kbId = inputData.getString("knowledgeBaseId") ?: return Result.failure()
        val repository = getRepository()

        val base = repository.getBaseById(kbId) ?: return Result.failure()
        if (base.type == "unfiled") return Result.success()

        val items = repository.observeItemsByKb(kbId, Int.MAX_VALUE, 0).first()
        val existing = repository.getThreadByKb(kbId)

        // Hash check to skip if no changes
        val itemIds = items.sortedBy { it.createdAt }.joinToString(",") { it.id }
        val currentHash = sha256(itemIds)
        if (existing != null && currentHash == sha256(
                existing.mainlineJson + existing.relationsJson + existing.gapsJson
            )) {
            return Result.success()
        }

        val description = buildDescription(base.name, items)
        val coreQuestion = extractCoreQuestion(items)
        val mainlines = extractMainlines(items)
        val relations = extractRelations(items)
        val gaps = detectGaps(items)
        val suggestions = generateSuggestions(items, gaps)

        val thread = KnowledgeThreadEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            knowledgeBaseId = kbId,
            description = description,
            coreQuestion = coreQuestion,
            mainlineJson = mainlines.joinToString(",", "[", "]") { "\"$it\"" },
            relationsJson = relations.joinToString(",", "[", "]") { r ->
                "{\"from\":\"${r.first}\",\"to\":\"${r.second}\",\"relation\":\"${r.third}\"}"
            },
            gapsJson = gaps.joinToString(",", "[", "]") { "\"$it\"" },
            nextSuggestionsJson = suggestions.joinToString(",", "[", "]") { "\"$it\"" },
            version = (existing?.version ?: 0) + 1,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        repository.saveThread(thread)

        val log = KnowledgeThreadLogEntity(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            triggerType = "auto_evolution",
            triggerId = kbId,
            beforeHash = existing?.let { sha256(it.mainlineJson + it.relationsJson) },
            afterHash = sha256(thread.mainlineJson + thread.relationsJson),
            summary = "知识脉络自动更新：${items.size}条知识，识别${gaps.size}个缺口",
            createdAt = System.currentTimeMillis()
        )
        repository.appendThreadLog(log)
        repository.rebuildGraphForBase(kbId)

        repository.updateBase(base.copy(
            threadStatus = "ready",
            updatedAt = System.currentTimeMillis()
        ))

        return Result.success()
    }

    private fun buildDescription(
        baseName: String,
        items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>
    ): String {
        if (items.isEmpty()) return "知识库「$baseName」尚无已整理的知识条目"
        val tagSet = items.flatMap {
            try {
                it.tagsJson.removeSurrounding("[", "]").split(",")
                    .map { t -> t.trim().removeSurrounding("\"") }.filter { t -> t.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }.toSet()
        return "知识库「$baseName」包含${items.size}条知识，涵盖${tagSet.take(5).joinToString("、")}等主题"
    }

    private fun extractCoreQuestion(items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>): String {
        if (items.isEmpty()) return "尚未确定核心问题"
        val titles = items.map { it.title }
        val sepRegex = Regex("[\\s\\u3000-\\u303F\\uFF00-\\uFFEF\\p{Punct}]+")
        val commonWords = titles.flatMap { it.split(sepRegex) }
            .filter { it.length > 2 }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .keys.take(3)
        return if (commonWords.isNotEmpty()) {
            "如何理解和应用${commonWords.joinToString("、")}相关知识？"
        } else {
            "探索「${titles.first().take(20)}」等相关知识"
        }
    }

    private fun extractMainlines(items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>): List<String> {
        return items.chunked(maxOf(1, items.size / 3)).map { chunk ->
            val prefix = chunk.firstOrNull()?.title?.take(20) ?: "知识起点"
            val suffix = chunk.lastOrNull()?.title?.take(20) ?: "知识终点"
            "$prefix → $suffix"
        }
    }

    private fun extractRelations(
        items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>
    ): List<Triple<String, String, String>> {
        if (items.size < 2) return emptyList()
        val relations = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until items.size - 1) {
            val a = items[i]
            val b = items[i + 1]
            val wordSep = Regex("[\\s\\u3000-\\u303F\\uFF00-\\uFFEF\\p{Punct}]+")
            val aWords = a.title.split(wordSep).filter { it.length > 1 }.toSet()
            val bWords = b.title.split(wordSep).filter { it.length > 1 }.toSet()
            if (aWords.intersect(bWords).isNotEmpty()) {
                relations.add(Triple(a.title.take(20), b.title.take(20), "主题相关"))
            }
        }
        return relations
    }

    private fun detectGaps(items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>): List<String> {
        val gaps = mutableListOf<String>()
        if (items.isEmpty()) {
            gaps.add("知识库中没有已整理的知识条目")
            return gaps
        }
        if (items.any { it.summary.isNullOrBlank() }) {
            gaps.add("部分知识缺少摘要，建议补充")
        }
        if (items.count { it.tagsJson != "[]" } < items.size / 2) {
            gaps.add("超过半数知识缺少标签，建议补充标签以便检索")
        }
        return gaps
    }

    private fun generateSuggestions(
        items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity>,
        gaps: List<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()
        if (gaps.isNotEmpty()) suggestions.add("建议补充缺失的摘要和标签信息")
        if (items.size < 3) suggestions.add("建议添加更多相关知识以构建完整的知识脉络")
        if (items.size >= 5) suggestions.add("知识条目较多，建议进行主题分类整理")
        return suggestions.ifEmpty { listOf("继续丰富知识库内容") }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRepository(): KnowledgeRepository {
        val db = AppDatabase.getInstance(applicationContext)
        return KnowledgeRepositoryImpl(
            db.knowledgeBaseDao(), db.knowledgeItemDao(),
            db.processingTaskDao(), db.archiveRecommendationDao(),
            db.aiConversationDao(), db.aiMessageDao(),
            db.knowledgeThreadDao(), db.knowledgeThreadLogDao(),
            db.sourceManifestDao(), db.knowledgeFragmentDao(),
            db.processingTaskLogDao(), db.askCitationDao(),
            db.knowledgeGraphDao()
        )
    }
}
