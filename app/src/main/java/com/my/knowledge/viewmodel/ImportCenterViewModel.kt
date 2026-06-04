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
import com.my.knowledge.domain.usecase.DeleteSourceLogUseCase
import com.my.knowledge.domain.usecase.DeleteSourceUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportCenterRow(
    val source: SourceDocumentEntity,
    val latestTask: ProcessingTaskEntity?,
    val allTasks: List<ProcessingTaskEntity>,
    val activeTaskCount: Int
)

class ImportCenterViewModel(
    private val sourceDao: SourceDocumentDao,
    private val taskDao: ProcessingTaskDao,
    private val knowledgeRepository: KnowledgeRepository,
    private val scheduler: ProcessingTaskScheduler,
    private val deleteSourceUseCase: DeleteSourceUseCase,
    private val deleteSourceLogUseCase: DeleteSourceLogUseCase
) : ViewModel() {
    val rows: StateFlow<List<ImportCenterRow>> = combine(
        sourceDao.observeAll(),
        taskDao.observeAllTasks()
    ) { sources, tasks ->
        val tasksBySource = tasks.groupBy { it.sourceId ?: it.targetId.takeIf { _ -> it.targetType == "source_document" } }
        sources.map { source ->
            val sourceTasks = tasksBySource[source.id].orEmpty().sortedBy { it.createdAt }
            ImportCenterRow(
                source = source,
                latestTask = sourceTasks.maxByOrNull { it.createdAt },
                allTasks = sourceTasks,
                activeTaskCount = sourceTasks.count {
                    it.status == "pending" ||
                        it.status == "running" ||
                        it.status == "pending_network" ||
                        it.status == "failed"
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledgeBases: StateFlow<List<KnowledgeBaseEntity>> = knowledgeRepository.observeAllBases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retrySource(sourceId: String) {
        // The previous implementation only flipped a `failed / canceled /
        // pending_config` task back to `pending` and re-ran the queue. That
        // silently no-op'd once the latest task was `success` — exactly the
        // case the user hits when they tap "重新发起 分析" on a finished
        // source. The user expects a full re-ingest: clear stale parse /
        // analysis / wiki pages, enqueue a fresh parse, and let the
        // orchestrator walk the pipeline again. `retryProcessingForItem`
        // does all of that and is the right call for both the success and
        // the failure case.
        viewModelScope.launch {
            knowledgeRepository.retryProcessingForSource(sourceId)
            scheduler.scheduleIngestQueue()
        }
    }

    fun retrySourceFromLogCenter(sourceId: String) {
        viewModelScope.launch {
            knowledgeRepository.retryProcessingForSourceFromLogCenter(sourceId)
            scheduler.scheduleIngestQueue()
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            val task = knowledgeRepository.getProcessingTask(taskId)
            val shouldStopIngest = task?.status == "running" && task.isIngestTask()
            if (shouldStopIngest) scheduler.cancelIngestQueue()
            knowledgeRepository.cancelTask(taskId)
            if (shouldStopIngest) {
                knowledgeRepository.resetInterruptedRunningTasks(taskId)
                scheduler.scheduleIngestQueue()
            }
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            deleteSourceUseCase.deleteSource(sourceId).forEach { kbId ->
                knowledgeRepository.refreshOverviewForBase(kbId)
                knowledgeRepository.rebuildGraphForBase(kbId)
            }
        }
    }

    fun deleteSourceLog(sourceId: String) {
        viewModelScope.launch {
            val shouldStopIngest = taskDao.getBySourceDocument(sourceId)
                .any { it.status == "running" && it.isIngestTask() }
            if (shouldStopIngest) scheduler.cancelIngestQueue()
            deleteSourceLogUseCase.deleteSourceLog(sourceId)
            if (shouldStopIngest) {
                knowledgeRepository.resetInterruptedRunningTasks()
                scheduler.scheduleIngestQueue()
            }
        }
    }
}

private fun ProcessingTaskEntity.isIngestTask(): Boolean =
    sourceId != null || targetType == "source_document"
