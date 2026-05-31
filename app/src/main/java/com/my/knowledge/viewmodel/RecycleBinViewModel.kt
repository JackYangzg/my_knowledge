package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RecycleBinViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 10
    }

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val deletedItemCount: StateFlow<Int> = knowledgeRepository.observeDeletedItemCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val items: StateFlow<List<KnowledgeItemEntity>> = _currentPage
        .flatMapLatest { page ->
            knowledgeRepository.observeDeletedItemsPaged(PAGE_SIZE, page * PAGE_SIZE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasNextPage: StateFlow<Boolean> = combine(deletedItemCount, _currentPage) { count, page ->
        (page + 1) * PAGE_SIZE < count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasPreviousPage: StateFlow<Boolean> = _currentPage.map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val totalPages: StateFlow<Int> = deletedItemCount.map { count ->
        if (count == 0) 1 else ((count - 1) / PAGE_SIZE) + 1
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    // Multi-select state
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    val selectionCount: StateFlow<Int> = _selectedIds.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun nextPage() { _currentPage.value++ }
    fun previousPage() { if (_currentPage.value > 0) _currentPage.value-- }

    fun toggleSelection(id: String) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAll(ids: List<String>) {
        _selectedIds.value = ids.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun restoreSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            knowledgeRepository.restoreItems(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun permanentDeleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            knowledgeRepository.permanentDeleteItems(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun restoreItem(id: String) {
        viewModelScope.launch {
            knowledgeRepository.restoreItem(id)
        }
    }
}
