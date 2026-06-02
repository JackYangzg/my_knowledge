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

    private val _loadedCount = MutableStateFlow(0)
    private val _isLoadingMore = MutableStateFlow(false)

    val deletedItemCount: StateFlow<Int> = knowledgeRepository.observeDeletedItemCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val items: StateFlow<List<KnowledgeItemEntity>> = _loadedCount
        .flatMapLatest { loaded ->
            knowledgeRepository.observeDeletedItemsPaged(loaded, 0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasMore: StateFlow<Boolean> = combine(deletedItemCount, _loadedCount) { count, loaded ->
        loaded < count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    init {
        if (_loadedCount.value == 0) {
            _loadedCount.value = PAGE_SIZE.coerceAtMost(deletedItemCount.value)
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val count = deletedItemCount.value
        val current = _loadedCount.value
        if (current >= count) return
        _isLoadingMore.value = true
        _loadedCount.value = (current + PAGE_SIZE).coerceAtMost(count)
        _isLoadingMore.value = false
    }

    // Multi-select state
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    val selectionCount: StateFlow<Int> = _selectedIds.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
