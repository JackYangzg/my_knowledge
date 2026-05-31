package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.first
import java.util.*

class ArchiveRecommendWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString("itemId") ?: return Result.failure()
        val repository = getRepository()
        val task = repository.getPendingTask("knowledge_item", itemId)
        repository.appendProcessingLog(log(task?.id, itemId, "archive_recommend", "running", "开始生成归档推荐"))
        val item = repository.getItemById(itemId) ?: return Result.failure()

        // Don't re-recommend if already exists
        val existing = repository.getRecommendationForItem(itemId)
        if (existing != null) {
            task?.let { repository.updateProcessingTask(it.copy(status = "success", finishedAt = System.currentTimeMillis())) }
            repository.appendProcessingLog(log(task?.id, itemId, "archive_recommend", "success", "已有归档推荐，跳过重复生成"))
            return Result.success()
        }

        val allBases = repository.observeAllBases().let { f -> f.first() }
        val normalBases = allBases.filter { it.type != "unfiled" && it.type != "system" }
        val unfiled = repository.getUnfiledBase()

        val (recommendedBase, confidence) = if (normalBases.isNotEmpty()) {
            matchToBase(item, normalBases)
        } else {
            unfiled to 0.5f
        }

        val recommendation = ArchiveRecommendationEntity(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            recommendedKnowledgeBaseId = recommendedBase?.id,
            recommendedKnowledgeBaseName = recommendedBase?.name ?: "未归类",
            confidence = confidence,
            reason = if (confidence > 0.7f) "内容与知识库主题高度匹配" else "基于内容关键词匹配推荐",
            alternativeJson = "[]",
            suggestCreateNewBase = confidence < 0.3f,
            status = "pending",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        repository.createArchiveRecommendation(recommendation)
        repository.updateItem(item.copy(
            status = KnowledgeItemEntity.STATUS_RECOMMEND_READY,
            updatedAt = System.currentTimeMillis()
        ))
        task?.let { repository.updateProcessingTask(it.copy(status = "success", finishedAt = System.currentTimeMillis())) }
        repository.appendProcessingLog(log(task?.id, itemId, "archive_recommend", "success", "归档推荐已生成"))

        return Result.success()
    }

    private fun matchToBase(
        item: KnowledgeItemEntity,
        bases: List<com.my.knowledge.data.db.entity.KnowledgeBaseEntity>
    ): Pair<com.my.knowledge.data.db.entity.KnowledgeBaseEntity?, Float> {
        val itemText = "${item.title} ${item.summary ?: ""} ${item.tagsJson}".lowercase()
        var bestMatch: com.my.knowledge.data.db.entity.KnowledgeBaseEntity? = null
        var bestScore = 0f

        for (base in bases) {
            val baseText = "${base.name} ${base.description ?: ""}".lowercase()
            val sepRegex = Regex("[\\s,\\u3001\\uFF0C]+")
            val itemWords = itemText.split(sepRegex).filter { it.length > 1 }.toSet()
            val baseWords = baseText.split(sepRegex).filter { it.length > 1 }.toSet()
            val overlap = itemWords.intersect(baseWords).size
            val score = if (baseWords.isNotEmpty()) overlap.toFloat() / baseWords.size else 0f
            if (score > bestScore) {
                bestScore = score
                bestMatch = base
            }
        }
        return bestMatch to bestScore.coerceIn(0f, 1f)
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

    private fun log(taskId: String?, itemId: String, stage: String, status: String, message: String) =
        ProcessingTaskLogEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            targetType = "knowledge_item",
            targetId = itemId,
            stage = stage,
            status = status,
            message = message,
            createdAt = System.currentTimeMillis()
        )
}
