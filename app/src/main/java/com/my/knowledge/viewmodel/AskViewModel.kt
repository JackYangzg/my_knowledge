package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.SearchEngine
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AskViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val searchEngine: SearchEngine
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchResults: StateFlow<List<KnowledgeItemEntity>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.length < 2) flowOf(emptyList())
            else searchEngine.search(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
