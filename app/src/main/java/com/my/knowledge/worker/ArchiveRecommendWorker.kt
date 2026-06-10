package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository
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

        repository.updateItem(item.copy(
            status = KnowledgeItemEntity.STATUS_ARCHIVED,
            archivedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))
        task?.let { repository.updateProcessingTask(it.copy(status = "success", finishedAt = System.currentTimeMillis())) }
        repository.appendProcessingLog(log(task?.id, itemId, "archive_recommend", "success", "保持原知识库归档完成"))

        return Result.success()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRepository(): KnowledgeRepository {
        val db = AppDatabase.getInstance(applicationContext)
        return KnowledgeRepositoryImpl(
            db,
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
