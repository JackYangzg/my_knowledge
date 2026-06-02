package com.my.knowledge.domain.usecase

import android.net.Uri
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import java.io.File
import java.util.UUID

class ImportSourceUseCase(
    private val fileStore: LocalFileStore,
    private val sourceDao: SourceDocumentDao,
    private val itemDao: KnowledgeItemDao,
    private val taskDao: ProcessingTaskDao,
    private val scheduler: ProcessingTaskScheduler
) {
    suspend fun importText(
        title: String,
        text: String,
        targetKbId: String?,
        importFrom: String = "manual",
        folderHint: String? = null,
        linkedNoteId: String? = null
    ): String {
        val sourceId = UUID.randomUUID().toString()
        val file = fileStore.saveTextSource(sourceId, text)
        return registerSource(
            sourceId = sourceId,
            sourceType = "text",
            title = title.ifBlank { "文本导入" },
            originalUri = null,
            file = file,
            mimeType = "text/plain",
            importFrom = importFrom,
            folderHint = folderHint,
            targetKbId = targetKbId,
            linkedNoteId = linkedNoteId
        )
    }

    suspend fun importUri(
        uri: Uri,
        displayName: String,
        mimeType: String?,
        sourceType: String,
        targetKbId: String?,
        importFrom: String = "file_picker",
        folderHint: String? = null
    ): String {
        val sourceId = UUID.randomUUID().toString()
        val file = fileStore.copyUriSource(sourceId, uri, displayName)
        return registerSource(
            sourceId = sourceId,
            sourceType = sourceType,
            title = displayName.ifBlank { "文件导入" },
            originalUri = uri.toString(),
            file = file,
            mimeType = mimeType,
            importFrom = importFrom,
            folderHint = folderHint,
            targetKbId = targetKbId
        )
    }

    private suspend fun registerSource(
        sourceId: String,
        sourceType: String,
        title: String,
        originalUri: String?,
        file: File,
        mimeType: String?,
        importFrom: String,
        folderHint: String?,
        targetKbId: String?,
        linkedNoteId: String? = null
    ): String {
        val sha256 = fileStore.sha256(file)
        val existing = sourceDao.findBySha256(sha256)
        if (existing != null) {
            ensureVisibleKnowledgeItem(
                sourceId = existing.id,
                sourceType = existing.sourceType,
                title = existing.title,
                file = file,
                mimeType = existing.mimeType,
                sha256 = existing.sha256,
                targetKbId = targetKbId ?: existing.targetKnowledgeBaseId,
                linkedNoteId = linkedNoteId
            )
            return existing.id
        }

        val now = System.currentTimeMillis()
        sourceDao.insert(
            SourceDocumentEntity(
                id = sourceId,
                sourceType = sourceType,
                title = title,
                originalUri = originalUri,
                localPath = file.absolutePath,
                mimeType = mimeType,
                sizeBytes = file.length(),
                sha256 = sha256,
                importFrom = importFrom,
                folderHint = folderHint,
                status = SourceDocumentEntity.STATUS_IMPORTED,
                errorMessage = null,
                targetKnowledgeBaseId = targetKbId,
                createdAt = now,
                updatedAt = now
            )
        )
        ensureVisibleKnowledgeItem(sourceId, sourceType, title, file, mimeType, sha256, targetKbId, linkedNoteId)
        fileStore.writeSourceManifest(
            sourceId,
            """{"id":"$sourceId","title":"${title.escapeJson()}","sha256":"$sha256","mimeType":"${mimeType.orEmpty().escapeJson()}"}"""
        )
        taskDao.insert(
            ProcessingTaskEntity(
                id = UUID.randomUUID().toString(),
                targetType = "source_document",
                targetId = sourceId,
                taskType = "parse",
                status = "pending",
                priority = 10,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null,
                sourceId = sourceId,
                itemId = null,
                progress = 0,
                currentStep = "等待解析",
                inputJson = """{"sourceId":"$sourceId"}"""
            )
        )
        scheduler.scheduleIngestQueue()
        return sourceId
    }

    private suspend fun ensureVisibleKnowledgeItem(
        sourceId: String,
        sourceType: String,
        title: String,
        file: File,
        mimeType: String?,
        sha256: String,
        targetKbId: String?,
        linkedNoteId: String? = null
    ) {
        val kbId = targetKbId ?: return
        val now = System.currentTimeMillis()
        val existingItem = itemDao.getBySourceId(sourceId)
        val item = KnowledgeItemEntity(
            id = existingItem?.id ?: UUID.randomUUID().toString(),
            sourceId = sourceId,
            knowledgeBaseId = kbId,
            title = title,
            contentMarkdown = if (sourceType == "text") file.readText() else buildString {
                appendLine("# $title")
                appendLine()
                appendLine("> 原始文件已导入，正在等待解析。")
                appendLine()
                appendLine("- 文件类型：${mimeType.orEmpty().ifBlank { sourceType }}")
                appendLine("- 文件大小：${file.length()} bytes")
            },
            excerpt = existingItem?.excerpt?.takeIf { it.isNotBlank() } ?: "原始内容已导入，等待知识加工",
            sourceType = sourceType,
            status = existingItem?.status ?: KnowledgeItemEntity.STATUS_PROCESSING,
            contentHash = sha256,
            sourceTraceJson = """{"sourceId":"$sourceId","localPath":"${file.absolutePath.escapeJson()}"}""",
            confidence = existingItem?.confidence ?: 0f,
            summary = existingItem?.summary,
            tagsJson = existingItem?.tagsJson ?: "[]",
            // If the caller is the inspiration editor, pin the knowledge item
            // to the originating note so re-saving updates the same row.
            rawNoteId = linkedNoteId ?: existingItem?.rawNoteId,
            importance = existingItem?.importance ?: 1,
            createdAt = existingItem?.createdAt ?: now,
            updatedAt = now,
            processedAt = existingItem?.processedAt,
            deletedAt = null
        )
        itemDao.insert(item)
        itemDao.updateItemCount(kbId)
        existingItem?.knowledgeBaseId?.takeIf { it != kbId }?.let { itemDao.updateItemCount(it) }
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
