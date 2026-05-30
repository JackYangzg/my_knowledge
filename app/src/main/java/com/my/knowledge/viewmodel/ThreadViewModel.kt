package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _kbId = MutableStateFlow<String?>(null)

    val thread: StateFlow<KnowledgeThreadEntity?> = _kbId
        .filterNotNull()
        .flatMapLatest { kbId -> flow { emit(knowledgeRepository.getThreadByKb(kbId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val threadLogs: StateFlow<List<KnowledgeThreadLogEntity>> = _kbId
        .filterNotNull()
        .flatMapLatest { kbId ->
            val t = knowledgeRepository.getThreadByKb(kbId)
            if (t != null) knowledgeRepository.observeThreadLogs(t.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parsedMainlines: StateFlow<List<String>> = thread.map { t ->
        parseStringList(t?.mainlineJson)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parsedGaps: StateFlow<List<String>> = thread.map { t ->
        parseStringList(t?.gapsJson)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parsedSuggestions: StateFlow<List<String>> = thread.map { t ->
        parseStringList(t?.nextSuggestionsJson)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class ThreadRelation(val from: String, val to: String, val relation: String)

    val parsedRelations: StateFlow<List<ThreadRelation>> = thread.map { t ->
        parseRelations(t?.relationsJson)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKnowledgeBaseId(kbId: String) {
        _kbId.value = kbId
    }

    fun triggerManualEvolution() {
        viewModelScope.launch {
            val kbId = _kbId.value ?: return@launch
            val log = KnowledgeThreadLogEntity(
                id = java.util.UUID.randomUUID().toString(),
                threadId = thread.value?.id ?: "",
                triggerType = "manual",
                triggerId = kbId,
                beforeHash = null,
                afterHash = null,
                summary = "用户手动触发知识脉络更新",
                createdAt = System.currentTimeMillis()
            )
            knowledgeRepository.appendThreadLog(log)
        }
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            json.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseRelations(json: String?): List<ThreadRelation> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            json.removeSurrounding("[", "]")
                .split("},{")
                .map { it.trim().removeSurrounding("{", "}") }
                .map { entry ->
                    val from = Regex("\"from\":\"([^\"]*)\"").find(entry)?.groupValues?.get(1) ?: ""
                    val to = Regex("\"to\":\"([^\"]*)\"").find(entry)?.groupValues?.get(1) ?: ""
                    val relation = Regex("\"relation\":\"([^\"]*)\"").find(entry)?.groupValues?.get(1) ?: ""
                    ThreadRelation(from, to, relation)
                }
        } catch (_: Exception) { emptyList() }
    }
}
