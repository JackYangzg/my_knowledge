package com.my.knowledge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.AskRetrievalPipeline
import com.my.knowledge.data.ai.AiTextCleaner
import com.my.knowledge.data.ai.AiTextCleaner.cleanModelOutput
import com.my.knowledge.data.ai.ContentType
import com.my.knowledge.data.ai.RetrievalHit
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AskViewModel(
    private val knowledgeRepository: KnowledgeRepository,
    private val searchEngine: SearchEngine
) : ViewModel() {

    // T3: 多源检索 pipeline (跨库 + 共现 tag 关系图 + 可选 web)
    private val retrievalPipeline = AskRetrievalPipeline(searchEngine, knowledgeRepository)
    // 暴露 modelConfig 以便取 askGraphEnabled / askWebEnabled 开关
    private val modelConfig get() = KnowledgeManager.modelConfig

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
    private var pendingConversationTitle: String = "新对话"

    private val _messages = MutableStateFlow<List<AiMessageEntity>>(emptyList())
    val messages: StateFlow<List<AiMessageEntity>> = _messages.asStateFlow()

    private val _lastCitations = MutableStateFlow<List<AskCitationEntity>>(emptyList())
    val lastCitations: StateFlow<List<AskCitationEntity>> = _lastCitations.asStateFlow()

    private val _conversations = MutableStateFlow<List<AiConversationEntity>>(emptyList())
    val conversations: StateFlow<List<AiConversationEntity>> = _conversations.asStateFlow()
    private var conversationsJob: Job? = null
    private var messagesJob: Job? = null

    /**
     * Same scope as [conversations] but each row also carries the live
     * message count for the conversation. The AskSheet history drawer
     * renders this list so the user can see "对话名 · 5 条消息 · 2 小时前"
     * without us doing an N+1 query inside the Compose tree.
     */
    val conversationsWithCount: StateFlow<List<com.my.knowledge.data.repository.KnowledgeRepositoryImpl.ConversationWithCount>> =
        combine(
            _currentScopeType,
            _currentScopeId
        ) { type, id -> type to id }
            .flatMapLatest { (type, id) ->
                knowledgeRepository.observeConversationsWithCount(type, id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _debugPrompts = MutableStateFlow<Map<String, String>>(emptyMap())
    val debugPrompts: StateFlow<Map<String, String>> = _debugPrompts.asStateFlow()

    fun setScope(scopeType: String, scopeId: String) {
        val normalizedType = when (scopeType) {
            ScopeType.KNOWLEDGE_ITEM,
            ScopeType.KNOWLEDGE_BASE,
            ScopeType.THREAD,
            ScopeType.GLOBAL -> scopeType
            else -> ScopeType.GLOBAL
        }
        val scopeChanged = _currentScopeType.value != normalizedType || _currentScopeId.value != scopeId
        _currentScopeType.value = normalizedType
        _currentScopeId.value = scopeId
        if (scopeChanged) {
            messagesJob?.cancel()
            _activeConversationId.value = null
            _messages.value = emptyList()
            _lastCitations.value = emptyList()
            _debugPrompts.value = emptyMap()
            pendingConversationTitle = "新对话"
        }
        loadConversations()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun loadConversations() {
        conversationsJob?.cancel()
        conversationsJob = viewModelScope.launch {
            knowledgeRepository.observeConversations(
                _currentScopeType.value, _currentScopeId.value
            ).collect { _conversations.value = it }
        }
    }

    fun startNewConversation(title: String = "新对话") {
        pendingConversationTitle = title.ifBlank { "新对话" }
        messagesJob?.cancel()
        _activeConversationId.value = null
        _messages.value = emptyList()
        _lastCitations.value = emptyList()
        _debugPrompts.value = emptyMap()
    }

    fun selectConversation(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            val conversation = knowledgeRepository.getConversation(conversationId)
            if (
                conversation == null ||
                conversation.scopeType != _currentScopeType.value ||
                conversation.scopeId != _currentScopeId.value
            ) {
                _activeConversationId.value = null
                _messages.value = emptyList()
                _lastCitations.value = emptyList()
                return@launch
            }
            pendingConversationTitle = "新对话"
            _activeConversationId.value = conversationId
            knowledgeRepository.observeMessages(conversationId).collect { list ->
                _messages.value = list.filter { it.role != "system" }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val scopeType = _currentScopeType.value
            val scopeId = _currentScopeId.value

            knowledgeRepository.clearConversationsByScope(scopeType, scopeId)

            _activeConversationId.value = null
            _messages.value = emptyList()
            _lastCitations.value = emptyList()
            _debugPrompts.value = emptyMap()
            pendingConversationTitle = "新对话"

            // Reload conversations (which should now be empty)
            loadConversations()
        }
    }

    fun askQuestion(question: String) {
        viewModelScope.launch {
            val conversationId = ensureActiveConversation()
            _isLoading.value = true
            val previousMessages = _messages.value
            val systemPrompt = AiPromptTemplates.systemPromptFor(_currentScopeType.value)

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

            // T3: 用 AskRetrievalPipeline 替 searchRelevantResults
            // (跨库 + 共现 tag 关系图 + 可选 web,AskRetrievalPipeline.kt:80-105)
            val retrievalHits = retrievalPipeline.search(
                question = question,
                scopeType = _currentScopeType.value,
                scopeId = _currentScopeId.value,
                askGraphEnabled = modelConfig.askGraphEnabled,
                askWebEnabled = modelConfig.askWebEnabled,
            )
            val relevantItems: List<KnowledgeItemEntity> = retrievalHits.map { it.item }
            val relevantResults: List<KnowledgeSearchResult> = relevantItems.map { item ->
                KnowledgeSearchResult(
                    itemId = item.id,
                    fragmentId = null,
                    knowledgeBaseId = item.knowledgeBaseId,
                    title = item.title,
                    snippet = item.contentMarkdown,
                    sourceType = item.sourceType,
                    score = 1f,
                    matchType = "pipeline"
                )
            }
            val debugPrompt = buildAskPrompt(question, relevantResults, relevantItems, previousMessages)
            if (KnowledgeManager.modelConfig.debugPromptEnabled) {
                _debugPrompts.value = _debugPrompts.value + (userMsg.id to debugPrompt)
            }
            val assistantMsgId = UUID.randomUUID().toString()
            val streamingMsg = AiMessageEntity(
                id = assistantMsgId,
                conversationId = conversationId,
                role = "assistant",
                content = "",
                contentType = ContentType.GENERAL,
                citationJson = buildCitationJson(relevantResults, relevantItems),
                sourceItemIdsJson = relevantResults.map { it.itemId }
                    .ifEmpty { relevantItems.map { it.id } }
                    .distinct()
                    .joinToString(",", "[", "]") { "\"$it\"" },
                createdAt = System.currentTimeMillis()
            )
            _messages.value = _messages.value + streamingMsg

            var answer = ""
            runCatching {
                AiGateway().completeStream(systemPrompt, debugPrompt)
                    .collect { chunk ->
                        answer += chunk
                        _messages.value = _messages.value.map {
                            if (it.id == assistantMsgId) it.copy(content = answer) else it
                        }
                    }
            }
            if (answer.isBlank() || isAiFailure(answer)) {
                answer = generateAnswerWithMarkers(question, relevantResults, relevantItems)
                _messages.value = _messages.value.map {
                    if (it.id == assistantMsgId) it.copy(content = answer) else it
                }
            }

            val assistantMsg = AiMessageEntity(
                id = assistantMsgId,
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
            _messages.value = _messages.value.map { if (it.id == assistantMsgId) assistantMsg else it }

            val conversation = knowledgeRepository.getConversation(conversationId)
            if (conversation != null && conversation.title == "新对话") {
                knowledgeRepository.updateConversation(
                    conversation.copy(title = question.take(30), updatedAt = System.currentTimeMillis())
                )
            }

            _isLoading.value = false
        }
    }

    private fun isAiFailure(text: String): Boolean {
        return listOf("[配置缺失]", "[AI 调用失败]", "[连接失败]", "[超时]", "[AI 调用异常]", "[API 错误]", "[解析失败]")
            .any { text.trimStart().startsWith(it) }
    }

    private suspend fun ensureActiveConversation(): String {
        _activeConversationId.value?.let { id ->
            val existing = knowledgeRepository.getConversation(id)
            if (
                existing != null &&
                existing.scopeType == _currentScopeType.value &&
                existing.scopeId == _currentScopeId.value
            ) {
                return id
            }
            messagesJob?.cancel()
            _activeConversationId.value = null
            _messages.value = emptyList()
            _lastCitations.value = emptyList()
        }
        val conversation = AiConversationEntity(
            id = UUID.randomUUID().toString(),
            scopeType = _currentScopeType.value,
            scopeId = _currentScopeId.value,
            title = pendingConversationTitle,
            isLocalOnly = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deletedAt = null
        )
        pendingConversationTitle = "新对话"
        knowledgeRepository.createConversation(conversation)
        _activeConversationId.value = conversation.id
        _messages.value = emptyList()
        knowledgeRepository.createMessage(
            AiMessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                role = "system",
                content = AiPromptTemplates.systemPromptFor(_currentScopeType.value),
                contentType = ContentType.GENERAL,
                createdAt = System.currentTimeMillis()
            )
        )
        return conversation.id
    }

    fun saveAnswerAsKnowledge(messageId: String) {
        viewModelScope.launch {
            val msg = _messages.value.find { it.id == messageId } ?: return@launch
            if (_activeConversationId.value == null) return@launch

            // Strip the model's hidden <think>...</think> reasoning block (and
            // any stray markdown fence) before persisting. The chat display
            // already hides think blocks, but the original code wrote the
            // raw `msg.content` straight into the knowledge base, so users
            // got the model's private notes filed as their own knowledge.
            val cleaned = msg.content.cleanModelOutput()
                .let { if (it.isBlank()) msg.content.trim() else it }

            val unfiledId = knowledgeRepository.getUnfiledBase()?.id ?: ""
            val newItem = KnowledgeItemEntity(
                id = UUID.randomUUID().toString(),
                knowledgeBaseId = unfiledId,
                title = "问答: ${cleaned.take(50)}",
                contentMarkdown = cleaned,
                excerpt = cleaned.take(100),
                sourceType = "ai_answer",
                status = KnowledgeItemEntity.STATUS_UNFILED,
                contentHash = knowledgeRepository.calculateContentHash(cleaned),
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
                if (scopeId.isBlank()) emptyList()
                else searchEngine.searchResults(question, scopeId, 8)
            }
            ScopeType.THREAD -> {
                val thread = knowledgeRepository.getThreadByKb(scopeId)
                if (thread == null) emptyList() else {
                    searchEngine.searchResults(question, thread.knowledgeBaseId, 8)
                }
            }
            ScopeType.GLOBAL -> {
                // Cross-base retrieval: pass a null kbId so the search
                // engine drops the per-base filter and ranks across the
                // whole library.
                searchEngine.searchResults(question, null, 16)
            }
            else -> {
                emptyList()
            }
        }.take(if (scopeType == ScopeType.GLOBAL) 8 else 5)
    }

    private suspend fun hydrateItems(results: List<KnowledgeSearchResult>): List<KnowledgeItemEntity> =
        results.mapNotNull { knowledgeRepository.getItemById(it.itemId) }.distinctBy { it.id }

    private suspend fun buildCitations(
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
        // T4: 批取 KB 名称 (避免 N+1)
        val kbIds = items.map { it.knowledgeBaseId }.distinct()
        val kbNameMap: Map<String, String> = kbIds.associateWith { kbId ->
            knowledgeRepository.getBaseById(kbId)?.name ?: "(已删除)"
        }
        val resultCitations = results.map { result ->
            AskCitationEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                itemId = result.itemId,
                fragmentId = result.fragmentId,
                quote = result.snippet.take(240),
                label = AskCitationEntity.LABEL_SOURCE,
                createdAt = now,
                sourceKnowledgeBaseId = result.knowledgeBaseId,
                sourceKnowledgeBaseName = kbNameMap[result.knowledgeBaseId]
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
                    createdAt = now,
                    sourceKnowledgeBaseId = item.knowledgeBaseId,
                    sourceKnowledgeBaseName = kbNameMap[item.knowledgeBaseId]
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
                    appendLine("来源：${result.title}")
                    appendLine()
                }
            } else {
                for (item in relevantItems) {
                    appendLine("【来自原文】")
                    appendLine("来源：${item.title}")
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

    private suspend fun buildAskPrompt(
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
            .joinToString("\n") { "${if (it.role == "user") "user" else "assistant"}: ${it.content.take(2000)}" }
            .ifBlank { "暂无历史上下文。" }

        val scopeName = when (_currentScopeType.value) {
            ScopeType.KNOWLEDGE_ITEM -> "单条知识点"
            ScopeType.KNOWLEDGE_BASE -> "整个知识库"
            ScopeType.GLOBAL -> "全局知识库"
            ScopeType.THREAD -> "知识脉络"
            else -> "全局搜索"
        }

        if (_currentScopeType.value == ScopeType.KNOWLEDGE_ITEM) {
            return AiPromptTemplates.buildKnowledgeItemAskPrompt(
                question = question,
                referencedKnowledge = buildKnowledgeItemReference(relevantResults, relevantItems),
                conversation = conversation
            )
        }

        return AiPromptTemplates.buildAskPrompt(
            question = question,
            originals = originals,
            conversation = conversation,
            scopeName = scopeName
        )
    }

    private suspend fun buildKnowledgeItemReference(
        relevantResults: List<KnowledgeSearchResult>,
        relevantItems: List<KnowledgeItemEntity>
    ): String {
        val item = relevantItems.firstOrNull()
        if (item != null) {
            val baseName = knowledgeRepository.getBaseById(item.knowledgeBaseId)?.name ?: "未知知识库"
            return buildString {
                appendLine("知识库：$baseName")
                appendLine("知识条目：${item.title}")
                appendLine()
                appendLine(item.contentMarkdown.take(8000))
            }.trim()
        }

        val result = relevantResults.firstOrNull()
        if (result != null) {
            val baseName = knowledgeRepository.getBaseById(result.knowledgeBaseId)?.name ?: "未知知识库"
            return buildString {
                appendLine("知识库：$baseName")
                appendLine("知识条目：${result.title}")
                appendLine()
                appendLine(result.snippet.take(8000))
            }.trim()
        }

        return "未检索到可用知识。"
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
