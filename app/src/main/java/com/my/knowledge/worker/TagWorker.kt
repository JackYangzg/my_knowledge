package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository

class TagWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString("itemId") ?: return Result.failure()
        val repository = getRepository()
        val task = repository.getPendingTask("knowledge_item", itemId)
        repository.appendProcessingLog(log(task?.id, itemId, "tag", "running", "开始提取标签"))
        val item = repository.getItemById(itemId) ?: return Result.failure()

        val existingTags = parseTags(item.tagsJson)
        if (existingTags.isNotEmpty() && item.status == KnowledgeItemEntity.STATUS_PROCESSED) {
            return Result.success()
        }

        val tags = generateTags(item)
        val tagsJson = tags.joinToString(",", "[", "]") { "\"$it\"" }

        repository.updateItem(item.copy(
            tagsJson = tagsJson,
            updatedAt = System.currentTimeMillis()
        ))
        repository.appendProcessingLog(log(task?.id, itemId, "tag", "success", "标签提取完成"))

        return Result.success()
    }

    private suspend fun generateTags(item: KnowledgeItemEntity): Set<String> {
        val ai = AiGateway()
        if (ai.isAvailable()) {
            val text = "${item.title}\n${item.summary ?: ""}\n${item.contentMarkdown.take(800)}"
            val result = ai.analyze("""
请从以下内容中提取3-8个关键词/标签：
---
$text
---
只输出逗号分隔的标签，例如: 机器学习, 神经网络, 深度学习
""".trimIndent())
            if (result.isNotEmpty() && !result.startsWith("[")) {
                return result.split(Regex("[，,]"))
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() && it.length in 1..20 }
                    .take(8).toSet()
            }
        }
        return extractTags(item.title, item.contentMarkdown, item.summary)
    }

    private fun extractTags(title: String, content: String, summary: String?): Set<String> {
        val combined = "$title ${summary ?: ""} ${content.take(500)}"
        val separators = Regex("[\\s\\u3000-\\u303F\\uFF00-\\uFFEF\\p{Punct}\\p{Space}]+")
        val words = combined.split(separators)
            .filter { it.length in 2..20 }
            .filter { !it.all { c -> c in '0'..'9' } }

        val freq = words.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .toList()
            .sortedByDescending { it.second }
            .take(8)

        return if (freq.isNotEmpty()) {
            freq.map { it.first }.toSet()
        } else {
            words.distinct().sortedByDescending { it.length }.take(5).toSet()
        }
    }

    private fun parseTags(tagsJson: String): List<String> {
        if (tagsJson == "[]" || tagsJson.isBlank()) return emptyList()
        return try {
            tagsJson.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
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
            db.knowledgeGraphDao(), db.reviewItemDao(),
            db.analysisResultDao(),
            db.parsedContentDao(),
            db.sourceDocumentDao()
        )
    }

    private fun log(taskId: String?, itemId: String, stage: String, status: String, message: String) =
        ProcessingTaskLogEntity(
            id = java.util.UUID.randomUUID().toString(),
            taskId = taskId,
            targetType = "knowledge_item",
            targetId = itemId,
            stage = stage,
            status = status,
            message = message,
            createdAt = System.currentTimeMillis()
        )
}
