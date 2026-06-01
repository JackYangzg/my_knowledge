package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class IntermediateDataViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    val entities: StateFlow<List<KnowledgeEntityEntity>> =
        knowledgeRepository.observeAllKnowledgeEntities()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relations: StateFlow<List<KnowledgeRelationEntity>> =
        knowledgeRepository.observeAllKnowledgeRelations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val communities: StateFlow<List<KnowledgeCommunityEntity>> =
        knowledgeRepository.observeAllKnowledgeCommunities()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
