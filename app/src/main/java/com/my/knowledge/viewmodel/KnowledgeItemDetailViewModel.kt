package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KnowledgeItemDetailViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _item = MutableStateFlow<KnowledgeItemEntity?>(null)
    val item: StateFlow<KnowledgeItemEntity?> = _item.asStateFlow()

    private val _processedItems = MutableStateFlow<List<KnowledgeItemEntity>>(emptyList())
    val processedItems: StateFlow<List<KnowledgeItemEntity>> = _processedItems.asStateFlow()
    private val _sourceItem = MutableStateFlow<KnowledgeItemEntity?>(null)
    val sourceItem: StateFlow<KnowledgeItemEntity?> = _sourceItem.asStateFlow()
    private var processedItemsJob: Job? = null

    fun loadItem(itemId: String) {
        viewModelScope.launch {
            val loaded = knowledgeRepository.getItemById(itemId)
            _item.value = loaded
            processedItemsJob?.cancel()
            val sourceId = loaded?.sourceId
            if (sourceId.isNullOrBlank()) {
                _processedItems.value = emptyList()
                _sourceItem.value = null
            } else {
                _sourceItem.value = knowledgeRepository.getItemBySourceId(sourceId)
                processedItemsJob = viewModelScope.launch {
                    knowledgeRepository.observeProcessedItemsBySource(sourceId).collectLatest { items ->
                        _processedItems.value = items.filter { it.id != loaded.id }
                    }
                }
            }
        }
    }
}
