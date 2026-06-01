package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.usecase.DeleteSourceUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportCenterRow(
    val source: SourceDocumentEntity,
    val latestTask: ProcessingTaskEntity?,
    val activeTaskCount: Int
)

class ImportCenterViewModel(
    private val sourceDao: SourceDocumentDao,
    private val taskDao: ProcessingTaskDao,
    private val knowledgeRepository: KnowledgeRepository,
    private val scheduler: ProcessingTaskScheduler,
    private val deleteSourceUseCase: DeleteSourceUseCase
) : ViewModel() {
    val rows: StateFlow<List<ImportCenterRow>> = combine(
        sourceDao.observeAll(),
        taskDao.observeAllTasks()
    ) { sources, tasks ->
        val tasksBySource = tasks.groupBy { it.sourceId ?: it.targetId.takeIf { _ -> it.targetType == "source_document" } }
        sources.map { source ->
            val sourceTasks = tasksBySource[source.id].orEmpty()
            ImportCenterRow(
                source = source,
                latestTask = sourceTasks.maxByOrNull { it.createdAt },
                activeTaskCount = sourceTasks.count { it.status == "pending" || it.status == "running" || it.status == "failed" }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = knowledgeRepository.observeAllBases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retrySource(sourceId: String) {
        viewModelScope.launch {
            val tasks = taskDao.getBySource(sourceId)
            val retryable = tasks.firstOrNull { it.status == "failed" || it.status == "canceled" || it.status == "pending_config" }
            if (retryable != null) {
                knowledgeRepository.retryTask(retryable.id)
            }
            scheduler.scheduleIngestQueue()
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            knowledgeRepository.cancelTask(taskId)
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            deleteSourceUseCase.deleteSource(sourceId)
        }
    }
}
