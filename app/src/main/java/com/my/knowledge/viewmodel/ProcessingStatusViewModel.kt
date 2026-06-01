package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProcessingStatusViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val processingTaskScheduler: ProcessingTaskScheduler
) : ViewModel() {

    val activeTasks: StateFlow<List<ProcessingTaskEntity>> = flow {
        knowledgeRepository.getActiveTasks().collect { emit(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingRecommendations: StateFlow<List<ArchiveRecommendationEntity>> =
        knowledgeRepository.observePendingRecommendations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingReviews: StateFlow<List<ReviewItemEntity>> =
        knowledgeRepository.observePendingReviews()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recommendationItemTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val recommendationItemTitles: StateFlow<Map<String, String>> = _recommendationItemTitles.asStateFlow()

    private val _activeTaskCount = MutableStateFlow(0)
    val activeTaskCount: StateFlow<Int> = _activeTaskCount.asStateFlow()

    private val _pendingRecCount = MutableStateFlow(0)
    val pendingRecCount: StateFlow<Int> = _pendingRecCount.asStateFlow()

    init {
        viewModelScope.launch {
            activeTasks.collect { _activeTaskCount.value = it.size }
        }
        viewModelScope.launch {
            pendingRecommendations.collect { _pendingRecCount.value = it.size }
        }
        viewModelScope.launch {
            pendingRecommendations.collect { recommendations ->
                _recommendationItemTitles.value = recommendations.associate { rec ->
                    rec.itemId to (knowledgeRepository.getItemById(rec.itemId)?.title ?: "未知知识")
                }
            }
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            val task = knowledgeRepository.getProcessingTask(taskId)
            knowledgeRepository.retryTask(taskId)
            if (task?.targetType == "knowledge_item") {
                processingTaskScheduler.scheduleFullPipeline(task.targetId)
            } else if (task?.targetType == "source_document") {
                processingTaskScheduler.scheduleIngestQueue()
            }
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch {
            knowledgeRepository.cancelTask(taskId)
        }
    }

    fun acceptRecommendation(recommendationId: String) {
        viewModelScope.launch {
            knowledgeRepository.acceptRecommendation(recommendationId)
        }
    }

    fun rejectRecommendation(recommendationId: String) {
        viewModelScope.launch {
            knowledgeRepository.rejectRecommendation(recommendationId)
        }
    }

    fun observeLogs(targetType: String, targetId: String): Flow<List<ProcessingTaskLogEntity>> {
        return knowledgeRepository.observeProcessingLogs(targetType, targetId)
    }

    fun acceptReview(reviewId: String) {
        viewModelScope.launch {
            knowledgeRepository.resolveReview(reviewId, ReviewItemEntity.STATUS_ACCEPTED)
        }
    }

    fun skipReview(reviewId: String) {
        viewModelScope.launch {
            knowledgeRepository.resolveReview(reviewId, ReviewItemEntity.STATUS_SKIPPED)
        }
    }
}
