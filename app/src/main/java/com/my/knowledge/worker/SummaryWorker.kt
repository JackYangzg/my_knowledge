package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository

class SummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString("itemId") ?: return Result.failure()
        val repository = getRepository()
        val item = repository.getItemById(itemId) ?: return Result.failure()

        // Skip if already processed with same content
        val currentHash = repository.calculateContentHash(item.contentMarkdown)
        if (currentHash == item.contentHash && item.status == KnowledgeItemEntity.STATUS_PROCESSED) {
            return Result.success()
        }

        val summary = generateSummary(item)
        val excerpt = item.contentMarkdown.take(100)

        repository.updateItem(item.copy(
            summary = summary,
            excerpt = excerpt,
            contentHash = currentHash,
            status = KnowledgeItemEntity.STATUS_PROCESSING,
            updatedAt = System.currentTimeMillis()
        ))

        return Result.success()
    }

    private suspend fun generateSummary(item: KnowledgeItemEntity): String {
        val ai = AiGateway()
        if (ai.isAvailable()) {
            val prompt = """
请为以下内容生成一段简洁的摘要（200字以内）：

${item.contentMarkdown.take(3000)}

只输出摘要文本，不要有其他内容。""".trimIndent()
            val result = ai.analyze(prompt)
            if (result.isNotEmpty() && !result.startsWith("[")) {
                return result.take(500)
            }
        }
        return extractSummary(item.contentMarkdown)
    }

    private fun extractSummary(content: String): String {
        if (content.isBlank()) return ""
        val cleaned = content.replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("[*_~`]"), "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .trim()
        return cleaned.take(200) + if (cleaned.length > 200) "..." else ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRepository(): KnowledgeRepository {
        val db = AppDatabase.getInstance(applicationContext)
        return KnowledgeRepositoryImpl(
            db.knowledgeBaseDao(), db.knowledgeItemDao(),
            db.processingTaskDao(), db.archiveRecommendationDao(),
            db.aiConversationDao(), db.aiMessageDao(),
            db.knowledgeThreadDao(), db.knowledgeThreadLogDao()
        )
    }
}
