package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.ai.ContentType
import com.my.knowledge.data.ai.ScopeType
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.SearchEngine
import com.my.knowledge.domain.repository.KnowledgeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

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

    private val _currentScopeType = MutableStateFlow(ScopeType.GLOBAL)
    val currentScopeType: StateFlow<String> = _currentScopeType.asStateFlow()

    private val _currentScopeId = MutableStateFlow("")
    val currentScopeId: StateFlow<String> = _currentScopeId.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<AiMessageEntity>>(emptyList())
    val messages: StateFlow<List<AiMessageEntity>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<AiConversationEntity>>(emptyList())
    val conversations: StateFlow<List<AiConversationEntity>> = _conversations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun setScope(scopeType: String, scopeId: String) {
        _currentScopeType.value = scopeType
        _currentScopeId.value = scopeId
        loadConversations()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun loadConversations() {
        viewModelScope.launch {
            knowledgeRepository.observeConversations(
                _currentScopeType.value, _currentScopeId.value
            ).collect { _conversations.value = it }
        }
    }

    fun startNewConversation(title: String = "新对话") {
        viewModelScope.launch {
            val conversation = AiConversationEntity(
                id = UUID.randomUUID().toString(),
                scopeType = _currentScopeType.value,
                scopeId = _currentScopeId.value,
                title = title,
                isLocalOnly = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                deletedAt = null
            )
            knowledgeRepository.createConversation(conversation)
            _activeConversationId.value = conversation.id
            _messages.value = emptyList()

            val systemMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = "system",
                content = AiPromptTemplates.BASE_SYSTEM_PROMPT,
                contentType = ContentType.GENERAL,
                createdAt = System.currentTimeMillis()
            )
            knowledgeRepository.createMessage(systemMsg)
        }
    }

    fun selectConversation(conversationId: String) {
        viewModelScope.launch {
            _activeConversationId.value = conversationId
            knowledgeRepository.observeMessages(conversationId).collect { list ->
                _messages.value = list.filter { it.role != "system" }
            }
        }
    }

    fun askQuestion(question: String) {
        if (_activeConversationId.value == null) {
            startNewConversation()
        }
        val conversationId = _activeConversationId.value ?: return

        viewModelScope.launch {
            _isLoading.value = true

            val userMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = question,
                contentType = ContentType.GENERAL,
                createdAt = System.currentTimeMillis()
            )
            knowledgeRepository.createMessage(userMsg)
            _messages.value = _messages.value + userMsg

            val relevantItems = searchRelevantItems(question)
            val answer = generateAnswerWithMarkers(question, relevantItems)

            val assistantMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = answer,
                contentType = ContentType.GENERAL,
                citationJson = buildCitationJson(relevantItems),
                sourceItemIdsJson = relevantItems.map { it.id }
                    .joinToString(",", "[", "]") { "\"$it\"" },
                createdAt = System.currentTimeMillis()
            )
            knowledgeRepository.createMessage(assistantMsg)
            _messages.value = _messages.value + assistantMsg

            val conversation = knowledgeRepository.getConversation(conversationId)
            if (conversation != null && conversation.title == "新对话") {
                knowledgeRepository.updateConversation(
                    conversation.copy(title = question.take(30), updatedAt = System.currentTimeMillis())
                )
            }

            _isLoading.value = false
        }
    }

    fun saveAnswerAsKnowledge(messageId: String) {
        viewModelScope.launch {
            val msg = _messages.value.find { it.id == messageId } ?: return@launch
            val conversation = _activeConversationId.value?.let {
                knowledgeRepository.getConversation(it)
            } ?: return@launch

            val newItem = KnowledgeItemEntity(
                id = UUID.randomUUID().toString(),
                knowledgeBaseId = conversation.scopeId.ifEmpty {
                    knowledgeRepository.getUnfiledBase()?.id ?: ""
                },
                title = "问答: ${msg.content.take(50)}",
                contentMarkdown = msg.content,
                excerpt = msg.content.take(100),
                sourceType = "ai_answer",
                status = KnowledgeItemEntity.STATUS_UNFILED,
                contentHash = knowledgeRepository.calculateContentHash(msg.content),
                summary = null,
                tagsJson = "[]",
                rawNoteId = null,
                importance = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                processedAt = null,
                deletedAt = null
            )
            knowledgeRepository.createItem(newItem)

            val updatedMsg = msg.copy(
                savedAsKnowledgeItemId = newItem.id,
                contentType = ContentType.SAVED_KNOWLEDGE
            )
            knowledgeRepository.createMessage(updatedMsg)
            _messages.value = _messages.value.map { if (it.id == messageId) updatedMsg else it }
        }
    }

    private suspend fun searchRelevantItems(question: String): List<KnowledgeItemEntity> {
        val scopeType = _currentScopeType.value
        val scopeId = _currentScopeId.value
        return when (scopeType) {
            ScopeType.KNOWLEDGE_ITEM -> {
                listOfNotNull(knowledgeRepository.getItemById(scopeId))
            }
            ScopeType.KNOWLEDGE_BASE -> {
                searchEngine.search(question, scopeId).firstOrNull() ?: emptyList()
            }
            else -> {
                searchEngine.search(question).firstOrNull() ?: emptyList()
            }
        }.take(5)
    }

    private fun generateAnswerWithMarkers(
        question: String,
        relevantItems: List<KnowledgeItemEntity>
    ): String {
        if (relevantItems.isEmpty()) {
            return buildString {
                appendLine("【信息不足】")
                appendLine()
                appendLine("知识库中没有找到与「$question」相关的信息。")
                appendLine()
                appendLine("建议：")
                appendLine("- 尝试用不同的关键词搜索")
                appendLine("- 在灵感页添加相关知识内容")
            }
        }

        return buildString {
            for ((index, item) in relevantItems.withIndex()) {
                appendLine("【来自原文】")
                appendLine(item.contentMarkdown.take(300))
                appendLine("来源：知识条目「${item.title}」")
                if (item.summary != null) appendLine("摘要：${item.summary}")
                appendLine()
            }

            val titles = relevantItems.joinToString("、") { it.title }
            appendLine("【AI推理】")
            appendLine("基于以上${relevantItems.size}条知识条目（$titles）的内容，" +
                "对「$question」进行了分析。以上推断基于现有知识库内容，仅供参考。")
            appendLine()
        }
    }

    private fun buildCitationJson(items: List<KnowledgeItemEntity>): String {
        return items.map { item ->
            val fragment = item.contentMarkdown.take(100)
                .replace("\"", "\\\"")
                .replace("\n", " ")
            """{"itemId":"${item.id}","itemTitle":"${item.title}","fragment":"$fragment","confidence":1.0}"""
        }.joinToString(",", "[", "]")
    }
}
