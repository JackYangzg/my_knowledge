package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the "中间处理数据" screen.
 *
 * The view-model is knowledge-base-scoped: callers can set [setKbId] to a
 * specific base and the entities / relations / communities flows will
 * then emit only rows for that base. Passing `null` switches the view
 * back to the "全局" (all-base) view, which is what ProfileScreen's
 * "查看详情" link lands on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntermediateDataViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _kbId = MutableStateFlow<String?>(null)
    val currentKbId: StateFlow<String?> = _kbId.asStateFlow()

    val knowledgeBases: StateFlow<List<com.my.knowledge.data.db.entity.KnowledgeBaseEntity>> =
        knowledgeRepository.observeAllBases()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Switch the active knowledge base. `null` means "all bases" — used
     * by the global entry on ProfileScreen.
     */
    fun setKbId(kbId: String?) {
        _kbId.value = kbId
    }

    val entities: StateFlow<List<KnowledgeEntityEntity>> = _kbId
        .flatMapLatest { kbId ->
            if (kbId.isNullOrBlank()) {
                knowledgeRepository.observeAllKnowledgeEntities()
            } else {
                knowledgeRepository.observeKnowledgeEntities(kbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relations: StateFlow<List<KnowledgeRelationEntity>> = _kbId
        .flatMapLatest { kbId ->
            if (kbId.isNullOrBlank()) {
                knowledgeRepository.observeAllKnowledgeRelations()
            } else {
                knowledgeRepository.observeKnowledgeRelations(kbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val communities: StateFlow<List<KnowledgeCommunityEntity>> = _kbId
        .flatMapLatest { kbId ->
            if (kbId.isNullOrBlank()) {
                knowledgeRepository.observeAllKnowledgeCommunities()
            } else {
                knowledgeRepository.observeKnowledgeCommunities(kbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteEntities(ids: List<String>) {
        viewModelScope.launch {
            knowledgeRepository.deleteKnowledgeEntities(ids)
        }
    }

    fun deleteRelations(ids: List<String>) {
        viewModelScope.launch {
            knowledgeRepository.deleteKnowledgeRelations(ids)
        }
    }

    fun deleteCommunities(ids: List<String>) {
        viewModelScope.launch {
            knowledgeRepository.deleteKnowledgeCommunities(ids)
        }
    }

    /**
     * Re-runs the local `WikiPageCompiler` template against every
     * source document's latest `AnalysisResultEntity` and inserts the
     * missing `wiki_entity` / `wiki_concept` rows, then rebuilds the
     * knowledge graph. Used by the "重新生成图谱" button on the
     * graph tab to recover knowledge bases whose previous
     * (buggy) ingest path never materialised those pages.
     */
    fun backfillWikiPages(onDone: (com.my.knowledge.domain.repository.BackfillResult) -> Unit = {}) {
        val targetKb = _kbId.value
        viewModelScope.launch {
            if (targetKb.isNullOrBlank()) {
                onDone(com.my.knowledge.domain.repository.BackfillResult(0, 0, 0, 0))
                return@launch
            }
            val result = knowledgeRepository.backfillWikiPagesForBase(targetKb)
            onDone(result)
        }
    }

    suspend fun getEntityByName(name: String) = knowledgeRepository.getEntityByName(name)
}
