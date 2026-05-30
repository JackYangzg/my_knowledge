package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProcessingStatusViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    val activeTasks: StateFlow<List<ProcessingTaskEntity>> = flow {
        knowledgeRepository.getActiveTasks().collect { emit(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingRecommendations: StateFlow<List<ArchiveRecommendationEntity>> =
        knowledgeRepository.observePendingRecommendations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            knowledgeRepository.retryTask(taskId)
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
}
