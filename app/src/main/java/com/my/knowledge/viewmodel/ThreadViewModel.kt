package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val scheduler: ProcessingTaskScheduler
) : ViewModel() {

    private val _kbId = MutableStateFlow<String?>(null)

    /**
     * THREAD-E3: tracks whether a manual re-evolution is in flight.
     * `true` from the moment [triggerManualEvolution] enqueues the
     * job until the underlying [thread] row's `updatedAt` advances
     * (or a 60s safety timeout fires). The home screen uses this to
     * disable the button and show a spinner. We intentionally do not
     * hard-link to `WorkManager.workInfo`: that's a heavier dep that
     * would force the ViewModel to take a `Context` argument. The
     * row-update poll is cheap (1Hz) and idempotent.
     */
    private val _evolving = MutableStateFlow(false)
    val evolving: StateFlow<Boolean> = _evolving.asStateFlow()

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

    val graphEntities = _kbId.filterNotNull()
        .flatMapLatest { knowledgeRepository.observeKnowledgeEntities(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val graphRelations = _kbId.filterNotNull()
        .flatMapLatest { knowledgeRepository.observeKnowledgeRelations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val graphCommunities = _kbId.filterNotNull()
        .flatMapLatest { knowledgeRepository.observeKnowledgeCommunities(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class ThreadRelation(val from: String, val to: String, val relation: String)

    val parsedRelations: StateFlow<List<ThreadRelation>> = thread.map { t ->
        parseRelations(t?.relationsJson)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKnowledgeBaseId(kbId: String) {
        _kbId.value = kbId
    }

    fun triggerManualEvolution() {
        val kbId = _kbId.value ?: return
        // Hand off to the worker instead of doing the work inline. The
        // worker has the full rewrite (input hash + tag cluster + wikilink
        // graph + score-driven gaps) and writes the resulting thread +
        // log atomically. The previous in-line path only updated the
        // graph and dropped a log row, so users saw an empty
        // "知识主线" list with a fake "刷新成功" toast.
        val beforeUpdatedAt = thread.value?.updatedAt
        scheduler.scheduleThreadUpdate(kbId)
        _evolving.value = true
        viewModelScope.launch {
            // Wait for the row to be rewritten, or fall back to a
            // 60s safety timeout so the spinner can't get stuck if
            // the worker is silently dropped (e.g. process suspension
            // on a whitelisted-app-stripping ROM — the very problem
            // RELIAB-1 PR-N4 nudges the user to fix).
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(1_000)
                val current = thread.value
                if (current != null && current.updatedAt != beforeUpdatedAt) {
                    break
                }
            }
            _evolving.value = false
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
