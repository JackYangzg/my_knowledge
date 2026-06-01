package com.my.knowledge.worker

import android.content.Context
import androidx.work.*
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker for knowledge processing pipeline
 * Implements Two-Step Ingest: Analysis → Generation
 */
class KnowledgeProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val targetType = inputData.getString(KEY_TARGET_TYPE) ?: return@withContext Result.failure()
        val targetId = inputData.getString(KEY_TARGET_ID) ?: return@withContext Result.failure()
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return@withContext Result.failure()

        try {
            when (taskType) {
                TASK_TYPE_ANALYSIS -> processAnalysis(targetId)
                TASK_TYPE_ARCHIVE_RECOMMEND -> processArchiveRecommendation(targetId)
                TASK_TYPE_GENERATE_SUMMARY -> processSummary(targetId)
                else -> Result.failure()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun processAnalysis(itemId: String) {
        // Step 1: Analysis - Output: title, summary, tags, entities, concepts, fragments, archive recommendation
        val repository = getRepository()
        val item = repository.getItemById(itemId) ?: return

        // Check content hash - skip if already processed
        val currentHash = repository.calculateContentHash(item.contentMarkdown)
        if (currentHash == item.contentHash && item.status == "processed") {
            return // Skip, already processed with same content
        }

        // Call AI for analysis (placeholder implementation)
        val summary = generateSummary(item.contentMarkdown)
        val tags = generateTags(item.contentMarkdown)

        // Update item with analysis results
        val updatedItem = item.copy(
            summary = summary,
            tagsJson = tags,
            contentHash = currentHash,
            status = com.my.knowledge.data.db.entity.KnowledgeItemEntity.STATUS_ARCHIVED,
            processedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        repository.updateItem(updatedItem)
    }

    private suspend fun processArchiveRecommendation(itemId: String) {
        val repository = getRepository()
        val item = repository.getItemById(itemId) ?: return
        
        repository.updateItem(item.copy(status = com.my.knowledge.data.db.entity.KnowledgeItemEntity.STATUS_ARCHIVED))
    }

    private suspend fun processSummary(itemId: String) {
        val repository = getRepository()
        val item = repository.getItemById(itemId) ?: return
        
        val summary = generateSummary(item.contentMarkdown)
        repository.updateItem(item.copy(
            summary = summary,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun generateSummary(content: String): String {
        // Simple extraction - in production, call AI
        return content.take(200) + if (content.length > 200) "..." else ""
    }

    private fun generateTags(content: String): String {
        // Simple keyword extraction - in production, call AI
        val words = content.split(Regex("\\s+")).take(50)
        val tagSet = words.filter { it.length > 4 }.take(5).toSet()
        return tagSet.joinToString(",", "[", "]")
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRepository(): KnowledgeRepository {
        // In real implementation, get from DI container
        val db = com.my.knowledge.data.db.AppDatabase.getInstance(applicationContext)
        return com.my.knowledge.data.repository.KnowledgeRepositoryImpl(
            db.knowledgeBaseDao(),
            db.knowledgeItemDao(),
            db.processingTaskDao(),
            db.archiveRecommendationDao(),
            db.aiConversationDao(),
            db.aiMessageDao(),
            db.knowledgeThreadDao(),
            db.knowledgeThreadLogDao(),
            db.sourceManifestDao(),
            db.knowledgeFragmentDao(),
            db.processingTaskLogDao(),
            db.askCitationDao(),
            db.knowledgeGraphDao(),
            db.reviewItemDao()
        )
    }

    companion object {
        const val KEY_TARGET_TYPE = "target_type"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_TASK_TYPE = "task_type"

        const val TASK_TYPE_ANALYSIS = "analysis"
        const val TASK_TYPE_ARCHIVE_RECOMMEND = "archive_recommend"
        const val TASK_TYPE_GENERATE_SUMMARY = "generate_summary"

        fun enqueue(
            context: Context,
            targetType: String,
            targetId: String,
            taskType: String
        ) {
            val workRequest = OneTimeWorkRequestBuilder<KnowledgeProcessingWorker>()
                .setInputData(workDataOf(
                    KEY_TARGET_TYPE to targetType,
                    KEY_TARGET_ID to targetId,
                    KEY_TASK_TYPE to taskType
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${targetType}_${targetId}_$taskType",
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )
        }
    }
}
