package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.ContentType
import com.my.knowledge.data.ai.ScopeType
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.data.db.entity.AskCitationEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.KnowledgeSearchResult
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

    private val _currentScopeType = MutableStateFlow(ScopeType.KNOWLEDGE_BASE)
    val currentScopeType: StateFlow<String> = _currentScopeType.asStateFlow()

    private val _currentScopeId = MutableStateFlow("")
    val currentScopeId: StateFlow<String> = _currentScopeId.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<AiMessageEntity>>(emptyList())
    val messages: StateFlow<List<AiMessageEntity>> = _messages.asStateFlow()

    private val _lastCitations = MutableStateFlow<List<AskCitationEntity>>(emptyList())
    val lastCitations: StateFlow<List<AskCitationEntity>> = _lastCitations.asStateFlow()

    private val _conversations = MutableStateFlow<List<AiConversationEntity>>(emptyList())
    val conversations: StateFlow<List<AiConversationEntity>> = _conversations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _debugPrompts = MutableStateFlow<Map<String, String>>(emptyMap())
    val debugPrompts: StateFlow<Map<String, String>> = _debugPrompts.asStateFlow()

    fun setScope(scopeType: String, scopeId: String) {
        _currentScopeType.value = when (scopeType) {
            ScopeType.KNOWLEDGE_ITEM, ScopeType.KNOWLEDGE_BASE, ScopeType.THREAD -> scopeType
            else -> ScopeType.KNOWLEDGE_BASE
        }
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
        viewModelScope.launch {
            val conversationId = ensureActiveConversation()
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

            val relevantResults = searchRelevantResults(question)
            val relevantItems = hydrateItems(relevantResults)
            val debugPrompt = buildAskPrompt(question, relevantResults, relevantItems, _messages.value)
            if (KnowledgeManager.modelConfig.debugPromptEnabled) {
                _debugPrompts.value = _debugPrompts.value + (userMsg.id to debugPrompt)
            }
            val answer = runCatching {
                AiGateway().complete(AiPromptTemplates.BASE_SYSTEM_PROMPT, debugPrompt)
            }.getOrNull()
                ?.takeIf { it.isNotBlank() && !it.startsWith("[配置缺失]") && !it.startsWith("[AI 调用") && !it.startsWith("[连接失败]") && !it.startsWith("[超时]") }
                ?: generateAnswerWithMarkers(question, relevantResults, relevantItems)

            val assistantMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = answer,
                contentType = ContentType.GENERAL,
                citationJson = buildCitationJson(relevantResults, relevantItems),
                sourceItemIdsJson = relevantResults.map { it.itemId }
                    .ifEmpty { relevantItems.map { it.id } }
                    .distinct()
                    .joinToString(",", "[", "]") { "\"$it\"" },
                createdAt = System.currentTimeMillis()
            )
            knowledgeRepository.createMessage(assistantMsg)
            val citations = buildCitations(assistantMsg.id, relevantResults, relevantItems, answer)
            knowledgeRepository.replaceCitationsForMessage(assistantMsg.id, citations)
            _lastCitations.value = citations
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

    private suspend fun ensureActiveConversation(): String {
        _activeConversationId.value?.let { return it }
        val conversation = AiConversationEntity(
            id = UUID.randomUUID().toString(),
            scopeType = _currentScopeType.value,
            scopeId = _currentScopeId.value,
            title = "新对话",
            isLocalOnly = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deletedAt = null
        )
        knowledgeRepository.createConversation(conversation)
        _activeConversationId.value = conversation.id
        _messages.value = emptyList()
        knowledgeRepository.createMessage(
            AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = "system",
                content = AiPromptTemplates.BASE_SYSTEM_PROMPT,
                contentType = ContentType.GENERAL,
                createdAt = System.currentTimeMillis()
            )
        )
        return conversation.id
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

    private suspend fun searchRelevantResults(question: String): List<KnowledgeSearchResult> {
        val scopeType = _currentScopeType.value
        val scopeId = _currentScopeId.value
        return when (scopeType) {
            ScopeType.KNOWLEDGE_ITEM -> {
                val item = knowledgeRepository.getItemById(scopeId)
                if (item == null) emptyList() else listOf(
                    KnowledgeSearchResult(
                        itemId = item.id,
                        fragmentId = null,
                        knowledgeBaseId = item.knowledgeBaseId,
                        title = item.title,
                    snippet = item.contentMarkdown,
                        sourceType = item.sourceType,
                        score = 3f,
                        matchType = "item_scope"
                    )
                )
            }
            ScopeType.KNOWLEDGE_BASE -> {
                if (scopeId.isBlank()) emptyList() else searchEngine.searchResults(question, scopeId, 8).firstOrNull() ?: emptyList()
            }
            ScopeType.THREAD -> {
                val thread = knowledgeRepository.getThreadByKb(scopeId)
                if (thread == null) emptyList() else {
                    searchEngine.searchResults(question, thread.knowledgeBaseId, 8).firstOrNull() ?: emptyList()
                }
            }
            else -> {
                emptyList()
            }
        }.take(5)
    }

    private suspend fun hydrateItems(results: List<KnowledgeSearchResult>): List<KnowledgeItemEntity> =
        results.mapNotNull { knowledgeRepository.getItemById(it.itemId) }.distinctBy { it.id }

    private fun buildCitations(
        messageId: String,
        results: List<KnowledgeSearchResult>,
        items: List<KnowledgeItemEntity>,
        answer: String
    ): List<AskCitationEntity> {
        val now = System.currentTimeMillis()
        if (results.isEmpty() && items.isEmpty()) {
            return listOf(
                AskCitationEntity(
                    id = UUID.randomUUID().toString(),
                    messageId = messageId,
                    itemId = null,
                    fragmentId = null,
                    quote = answer.take(200),
                    label = AskCitationEntity.LABEL_INSUFFICIENT,
                    createdAt = now
                )
            )
        }
        val resultCitations = results.map { result ->
            AskCitationEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                itemId = result.itemId,
                fragmentId = result.fragmentId,
                quote = result.snippet.take(240),
                label = AskCitationEntity.LABEL_SOURCE,
                createdAt = now
            )
        }
        return resultCitations.ifEmpty {
            items.map { item ->
                AskCitationEntity(
                    id = UUID.randomUUID().toString(),
                    messageId = messageId,
                    itemId = item.id,
                    fragmentId = null,
                    quote = item.contentMarkdown.take(240),
                    label = AskCitationEntity.LABEL_SOURCE,
                    createdAt = now
                )
            }
        } + AskCitationEntity(
            id = UUID.randomUUID().toString(),
            messageId = messageId,
            itemId = null,
            fragmentId = null,
            quote = "基于 ${items.size} 条来源生成的分析",
            label = AskCitationEntity.LABEL_INFERENCE,
            createdAt = now + 1
        )
    }

    private fun generateAnswerWithMarkers(
        question: String,
        relevantResults: List<KnowledgeSearchResult>,
        relevantItems: List<KnowledgeItemEntity>
    ): String {
        if (relevantResults.isEmpty() && relevantItems.isEmpty()) {
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
            if (relevantResults.isNotEmpty()) {
                for (result in relevantResults) {
                    appendLine("【来自原文】")
                    appendLine(result.snippet.take(300))
                    appendLine("来源：知识条目「${result.title}」${result.fragmentId?.let { " · 片段 ${it.take(8)}" } ?: ""}")
                    appendLine()
                }
            } else {
                for (item in relevantItems) {
                    appendLine("【来自原文】")
                    appendLine(item.contentMarkdown.take(300))
                    appendLine("来源：知识条目「${item.title}」")
                    if (item.summary != null) appendLine("摘要：${item.summary}")
                    appendLine()
                }
            }

            val titles = (relevantResults.map { it.title } + relevantItems.map { it.title }).distinct().joinToString("、")
            val sourceCount = if (relevantResults.isNotEmpty()) relevantResults.size else relevantItems.size
            appendLine("【AI推理】")
            appendLine("基于以上${sourceCount}条知识来源（$titles）的内容，" +
                "对「$question」进行了分析。以上推断基于当前 scope 内的本地知识，仅供参考。")
            appendLine()
        }
    }

    private fun buildAskPrompt(
        question: String,
        relevantResults: List<KnowledgeSearchResult>,
        relevantItems: List<KnowledgeItemEntity>,
        messages: List<AiMessageEntity>
    ): String {
        val originals = buildString {
            if (relevantResults.isNotEmpty()) {
                relevantResults.forEachIndexed { index, result ->
                    appendLine("[原始内容 ${index + 1}] ${result.title}")
                    appendLine(result.snippet.take(8000))
                    appendLine()
                }
            } else {
                relevantItems.forEachIndexed { index, item ->
                    appendLine("[原始内容 ${index + 1}] ${item.title}")
                    appendLine(item.contentMarkdown.take(8000))
                    appendLine()
                }
            }
        }.ifBlank { "未检索到可用原始内容。" }

        val conversation = messages
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(8)
            .joinToString("\n") { "${if (it.role == "user") "用户" else "AI"}：${it.content.take(500)}" }
            .ifBlank { "暂无历史上下文。" }

        return """
            系统提示：
            ${AiPromptTemplates.BASE_SYSTEM_PROMPT}

            原始内容：
            $originals

            上下文对话：
            $conversation

            用户问题：
            $question
        """.trimIndent()
    }

    private fun buildCitationJson(
        results: List<KnowledgeSearchResult>,
        items: List<KnowledgeItemEntity>
    ): String {
        if (results.isNotEmpty()) {
            return results.map { result ->
                val fragment = result.snippet.take(100)
                    .replace("\"", "\\\"")
                    .replace("\n", " ")
                """{"itemId":"${result.itemId}","fragmentId":${result.fragmentId?.let { "\"$it\"" } ?: "null"},"itemTitle":"${result.title}","fragment":"$fragment","confidence":${result.score}}"""
            }.joinToString(",", "[", "]")
        }
        return items.map { item ->
            val fragment = item.contentMarkdown.take(100)
                .replace("\"", "\\\"")
                .replace("\n", " ")
            """{"itemId":"${item.id}","fragmentId":null,"itemTitle":"${item.title}","fragment":"$fragment","confidence":1.0}"""
        }.joinToString(",", "[", "]")
    }
}
