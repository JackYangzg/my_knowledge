package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for knowledge item list with load-more (infinite scroll) pagination.
 * Loads [PAGE_SIZE] items on first paint, then tops up as the user scrolls near the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeItemListViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val fileStore: LocalFileStore
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 10
    }

    private val _kbId = MutableStateFlow<String?>(null)
    private val _loadedCount = MutableStateFlow(0)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus

    // Total item count for the knowledge base
    val itemCount: StateFlow<Int> = _kbId
        .filterNotNull()
        .flatMapLatest { id -> knowledgeRepository.observeItemCount(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Items currently loaded into the list (capped at itemCount by the query)
    val items: StateFlow<List<KnowledgeItemEntity>> = combine(_kbId, _loadedCount) { kbId, loaded ->
        kbId to loaded
    }.filter { (kbId, _) -> kbId != null }
        .flatMapLatest { (kbId, loaded) ->
            knowledgeRepository.observeItemsByKb(kbId!!, loaded, 0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasMore: StateFlow<Boolean> = combine(itemCount, _loadedCount) { count, loaded ->
        loaded < count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    fun setKnowledgeBaseId(id: String) {
        if (_kbId.value != id) {
            _kbId.value = id
            _loadedCount.value = 0
        }
        if (_loadedCount.value == 0) {
            _loadedCount.value = PAGE_SIZE.coerceAtMost(itemCount.value)
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val count = itemCount.value
        val current = _loadedCount.value
        if (current >= count) return
        _isLoadingMore.value = true
        _loadedCount.value = (current + PAGE_SIZE).coerceAtMost(count)
        _isLoadingMore.value = false
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            knowledgeRepository.deleteItem(itemId, softDelete = true)
        }
    }

    fun retryItem(itemId: String) {
        viewModelScope.launch {
            knowledgeRepository.retryProcessingForItem(itemId)
        }
    }

    fun exportSelectedItems(itemIds: Set<String>) {
        viewModelScope.launch {
            if (itemIds.isEmpty()) return@launch
            try {
                val items = itemIds.mapNotNull { knowledgeRepository.getItemById(it) }
                val markdown = buildString {
                    appendLine("# Knowledge Export")
                    appendLine()
                    items.forEach { item ->
                        appendLine("## ${item.title}")
                        appendLine()
                        if (!item.summary.isNullOrBlank()) {
                            appendLine("> ${item.summary}")
                            appendLine()
                        }
                        appendLine(item.contentMarkdown)
                        appendLine()
                    }
                }
                val file = fileStore.writeBackup("knowledge_selected_${System.currentTimeMillis()}.md", markdown)
                _exportStatus.value = "已导出 ${items.size} 条到 ${file.absolutePath}"
            } catch (e: Exception) {
                _exportStatus.value = "导出失败：${e.message ?: "未知错误"}"
            }
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

    fun moveItem(itemId: String, newKbId: String) {
        viewModelScope.launch {
            knowledgeRepository.moveItemToBase(itemId, newKbId)
        }
    }
}
