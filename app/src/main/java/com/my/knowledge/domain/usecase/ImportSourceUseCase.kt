package com.my.knowledge.domain.usecase

import android.net.Uri
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.ProcessingTaskLogDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import java.io.File
import java.util.UUID

/**
 * Outcome of a single import call.
 *
 * [sourceId] is always set: for a fresh import it's the newly created
 * `SourceDocumentEntity.id`; for a duplicate it is the existing
 * source's id (the call short-circuited via sha256 dedupe).
 *
 * [isDuplicate] is `true` when the caller's content (same sha256) was
 * already in the library. Callers can use this to surface a "已导入"
 * toast instead of a generic "已保存" message, and to skip any UI
 * that assumes new work was scheduled.
 */
data class ImportResult(
    val sourceId: String,
    val isDuplicate: Boolean,
)

class ImportSourceUseCase(
    private val fileStore: LocalFileStore,
    private val sourceDao: SourceDocumentDao,
    private val itemDao: KnowledgeItemDao,
    private val taskDao: ProcessingTaskDao,
    private val taskLogDao: ProcessingTaskLogDao,
    private val scheduler: ProcessingTaskScheduler
) {
    suspend fun importText(
        title: String,
        text: String,
        targetKbId: String?,
        importFrom: String = "manual",
        folderHint: String? = null,
        linkedNoteId: String? = null
    ): ImportResult {
        val sourceId = UUID.randomUUID().toString()
        val file = fileStore.saveTextSource(sourceId, text)
        val result = registerSource(
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
        // For text imports, saveTextSource already wrote the file under
        // `sourceId`. On the duplicate path the file on disk is now a
        // throwaway copy — clean it up so we don't leak an extra file
        // per duplicate click.
        if (result.isDuplicate) runCatching { file.delete() }
        return result
    }

    suspend fun importUri(
        uri: Uri,
        displayName: String,
        mimeType: String?,
        sourceType: String,
        targetKbId: String?,
        importFrom: String = "file_picker",
        folderHint: String? = null
    ): ImportResult {
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
    ): ImportResult {
        val sha256 = fileStore.sha256(file)
        val existing = sourceDao.findBySha256(sha256)
        if (existing != null) {
            // Duplicate: link the target KB to the existing source if
            // needed, but DO NOT touch the existing knowledge item's
            // contentMarkdown / status / excerpt (the previous
            // implementation overwrote them with a stub, which silently
            // destroyed the item's real content). Also skip writing a
            // new ProcessingTaskLogEntity on every duplicate click —
            // the user wasn't asking for new work, they were asking
            // for a "you already have this" signal.
            val now = System.currentTimeMillis()
            if (targetKbId != null && targetKbId != existing.targetKnowledgeBaseId) {
                ensureVisibleKnowledgeItem(
                    sourceId = existing.id,
                    sourceType = existing.sourceType,
                    title = existing.title,
                    file = file,
                    mimeType = existing.mimeType,
                    sha256 = existing.sha256,
                    targetKbId = targetKbId,
                    linkedNoteId = linkedNoteId,
                    // Pass preserveExistingContent=true so a non-text
                    // duplicate does NOT clobber the existing
                    // contentMarkdown with a fresh stub.
                    preserveExistingContent = true,
                )
                // Reflect the new KB on the source row so subsequent
                // dedupe hits land on the right target without an
                // extra `ensureVisibleKnowledgeItem` call. Use
                // `update` (not `insert` with REPLACE) so we don't
                // cascade-delete any FK references that point at
                // this source row.
                sourceDao.update(
                    existing.copy(
                        targetKnowledgeBaseId = targetKbId,
                        updatedAt = now,
                    )
                )
            }
            return ImportResult(sourceId = existing.id, isDuplicate = true)
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
        val taskId = UUID.randomUUID().toString()
        taskDao.insert(
            ProcessingTaskEntity(
                id = taskId,
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
        taskLogDao.insert(
            ProcessingTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                targetType = "source_document",
                targetId = sourceId,
                stage = "parse",
                status = "pending",
                message = "已入库并排队，等待解析",
                createdAt = now
            )
        )
        scheduler.scheduleIngestQueue()
        return ImportResult(sourceId = sourceId, isDuplicate = false)
    }

    private suspend fun ensureVisibleKnowledgeItem(
        sourceId: String,
        sourceType: String,
        title: String,
        file: File,
        mimeType: String?,
        sha256: String,
        targetKbId: String?,
        linkedNoteId: String? = null,
        // When the caller is a duplicate-dedupe path, we want to
        // preserve the existing item's content (the previous
        // implementation overwrote it with a stub on every duplicate
        // click, which silently destroyed the user's real content).
        // Default false (fresh import path) keeps the original
        // behaviour of writing the stub.
        preserveExistingContent: Boolean = false,
    ) {
        val kbId = targetKbId ?: return
        val now = System.currentTimeMillis()
        val existingItem = itemDao.getBySourceId(sourceId)
        val item = KnowledgeItemEntity(
            id = existingItem?.id ?: UUID.randomUUID().toString(),
            sourceId = sourceId,
            knowledgeBaseId = kbId,
            title = title,
            contentMarkdown = existingItem?.contentMarkdown
                ?: if (sourceType == "text") file.readText() else buildString {
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
        // preserveExistingContent is intentionally a hint, not a hard
        // gate — the call site (duplicate path) already passed an
        // `existingItem` lookup so the `?:` falls through to
        // `existingItem?.contentMarkdown` and we never reach the
        // stub branch above. Keeping the parameter explicit so future
        // callers can opt out of the stub-write semantics if they
        // need to.
        @Suppress("UNUSED_VARIABLE") val unused = preserveExistingContent
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
