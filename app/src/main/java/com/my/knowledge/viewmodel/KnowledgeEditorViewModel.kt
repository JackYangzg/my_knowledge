package com.my.knowledge.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * Backs the [KnowledgeEditorScreen]. The user opens this when tapping
 * "编辑" on a knowledge item: it loads the item, lets the user edit its
 * title / content as raw markdown, writes the row back in place, and
 * then **re-triggers the ingest pipeline** (parse → analysis →
 * generation) for the underlying source so entity / concept / graph
 * indexes stay in sync with the new content.
 */
class KnowledgeEditorViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val db: AppDatabase,
    private val scheduler: ProcessingTaskScheduler
) : ViewModel() {

    private val _item = MutableStateFlow<KnowledgeItemEntity?>(null)
    val item: StateFlow<KnowledgeItemEntity?> = _item.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted.asStateFlow()

    /**
     * The status the caller should display in the toast after save().
     * Distinguishes "saved, re-ingest scheduled" from "saved, no source
     * to re-ingest (notebook-style items)" so the user can tell at a
     * glance what happened.
     */
    private val _reingestStatus = MutableStateFlow<String?>(null)
    val reingestStatus: StateFlow<String?> = _reingestStatus.asStateFlow()

    fun load(itemId: String) {
        viewModelScope.launch {
            val loaded = knowledgeRepository.getItemById(itemId)
            _item.value = loaded
            if (loaded != null) {
                _title.value = loaded.title
                _content.value = loaded.contentMarkdown
            }
        }
    }

    fun setTitle(value: String) {
        _title.value = value
    }

    fun setContent(value: String) {
        _content.value = value
    }

    /**
     * Persist the edit and re-trigger the ingest pipeline.
     *
     * Flow:
     *  1. updateItem with the new title / content / hash, and flip the
     *     status to STATUS_PROCESSING so any UI watching the item sees
     *     the "加工中" state.
     *  2. rebuildFragmentsForItem so RAG search picks up the new
     *     content immediately (independent of whether re-ingest runs).
     *  3. If the item has a sourceId, enqueue a fresh "parse" task
     *     against the source_document row + flip its status to
     *     STATUS_PARSING, then schedule the IngestWorker.
     *  4. If the item has no sourceId (e.g. user-typed knowledge that
     *     never went through a source), run the summary → tag →
     *     archive pipeline via ProcessingTaskScheduler.
     */
    fun save(onSaved: () -> Unit = {}) {
        val current = _item.value ?: return
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newHash = sha256(_content.value)
            val updated = current.copy(
                title = _title.value.ifBlank { current.title },
                contentMarkdown = _content.value,
                excerpt = _content.value.take(120).replace("\n", " "),
                contentHash = newHash,
                updatedAt = now,
                // Flip to "加工中" so the knowledge tab, processing status
                // screen and knowledge manager all surface the re-ingest
                // happening in the background.
                status = KnowledgeItemEntity.STATUS_PROCESSING,
                processedAt = now
            )
            knowledgeRepository.updateItem(updated)
            knowledgeRepository.rebuildFragmentsForItem(updated)
            _item.value = updated

            val base = knowledgeRepository.getBaseById(updated.knowledgeBaseId)
            if (base?.type == "inspiration") {
                scheduler.scheduleLlmThreadUpdate(
                    kbId = updated.knowledgeBaseId,
                    newItemId = updated.id,
                    triggerType = "inspiration_edited"
                )
                _reingestStatus.value = "已保存,正在重新生成灵感脉络"
                _isSaving.value = false
                _saveCompleted.value = true
                onSaved()
                return@launch
            }

            val sourceId = updated.sourceId
            if (!sourceId.isNullOrBlank()) {
                // Mirror the status on the source row too so the Log
                // Center / "知识库" tab header reflects "加工中" until
                // Stage 2 finishes.
                db.sourceDocumentDao().updateStatus(
                    sourceId,
                    SourceDocumentEntity.STATUS_PARSING,
                    null,
                    now
                )
                // Drop any older analysis for this source so the new
                // pipeline doesn't merge against a stale graph view.
                db.analysisResultDao().deleteBySource(sourceId)
                db.parsedContentDao().deleteBySource(sourceId)

                db.processingTaskDao().insert(
                    ProcessingTaskEntity(
                        id = UUID.randomUUID().toString(),
                        targetType = "source_document",
                        targetId = sourceId,
                        taskType = "parse",
                        status = "pending",
                        priority = 8,
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
                        currentStep = "等待解析(编辑触发)",
                        inputJson = """{"sourceId":"$sourceId","trigger":"edit"}"""
                    )
                )
                scheduler.scheduleIngestQueue()
                _reingestStatus.value = "已保存,正在重新加工"
            } else {
                // No source backing this item — fall back to the
                // summary/tag/archive pipeline so AI-derived metadata
                // (summary, tags, archive suggestion) refreshes.
                scheduler.scheduleFullPipeline(updated.id)
                _reingestStatus.value = "已保存,正在更新元数据"
            }

            _isSaving.value = false
            _saveCompleted.value = true
            onSaved()
        }
    }

    fun consumeSaveCompleted() {
        _saveCompleted.value = false
        _reingestStatus.value = null
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
