package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for knowledge item list with pagination
 * P2: Fixed page size of 3 items per page as per requirements
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeItemListViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 3 // P2: Fixed page size of 3 items
    }

    private val _kbId = MutableStateFlow<String?>(null)
    private val _currentPage = MutableStateFlow(0)
    
    // Total item count for the knowledge base
    val itemCount: StateFlow<Int> = _kbId
        .filterNotNull()
        .flatMapLatest { id -> knowledgeRepository.observeItemCount(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Current page items
    val items: StateFlow<List<KnowledgeItemEntity>> = combine(_kbId, _currentPage) { kbId, page ->
        kbId to page
    }.filter { (kbId, _) -> kbId != null }
        .flatMapLatest { (kbId, page) ->
            knowledgeRepository.observeItemsByKb(kbId!!, PAGE_SIZE, page * PAGE_SIZE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pagination state
    val hasNextPage: StateFlow<Boolean> = combine(itemCount, _currentPage) { count, page ->
        (page + 1) * PAGE_SIZE < count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasPreviousPage: StateFlow<Boolean> = _currentPage.map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    val totalPages: StateFlow<Int> = itemCount.map { count ->
        if (count == 0) 1 else ((count - 1) / PAGE_SIZE) + 1
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    fun setKnowledgeBaseId(id: String) {
        if (_kbId.value != id) {
            _kbId.value = id
            _currentPage.value = 0 // Reset to first page on base change
        }
    }

    fun nextPage() {
        _currentPage.value++
    }

    fun previousPage() {
        if (_currentPage.value > 0) {
            _currentPage.value--
        }
    }

    fun goToPage(page: Int) {
        if (page >= 0) {
            _currentPage.value = page
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            knowledgeRepository.deleteItem(itemId, softDelete = true)
        }
    }

    // === Recycle bin operations ===

    val deletedItems: StateFlow<List<KnowledgeItemEntity>> = knowledgeRepository.observeDeletedItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restoreItem(itemId: String) {
        viewModelScope.launch {
            knowledgeRepository.restoreItem(itemId)
        }
    }

    fun permanentDeleteItem(itemId: String) {
        viewModelScope.launch {
            knowledgeRepository.permanentDeleteItem(itemId)
        }
    }
}