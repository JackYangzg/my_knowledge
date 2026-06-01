package com.my.knowledge.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.usecase.AutoSaveNoteUseCase
import com.my.knowledge.domain.usecase.CreateNoteUseCase
import com.my.knowledge.domain.usecase.ImportSourceUseCase
import com.my.knowledge.domain.repository.NoteRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.MessageDigest

@OptIn(FlowPreview::class)
class NoteEditorViewModel(
    private val createNoteUseCase: CreateNoteUseCase,
    private val autoSaveNoteUseCase: AutoSaveNoteUseCase,
    private val noteRepository: NoteRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val importSourceUseCase: ImportSourceUseCase
) : ViewModel() {

    var currentNote by mutableStateOf<NoteEntity?>(null)
        private set

    var title by mutableStateOf("")
    var content by mutableStateOf("")
    var mode by mutableStateOf("preview") // edit, preview
    var hasVoiceTranscriptionContent by mutableStateOf(false)
        private set
    private var savedKnowledgeItemId: String? = null
    
    private val _saveStatus = MutableStateFlow("idle")
    val saveStatus: StateFlow<String> = _saveStatus

    init {
        loadOrCreateNote()
        setupAutoSave()
    }

    private fun loadOrCreateNote() {
        viewModelScope.launch {
            noteRepository.observeCurrentDraft().firstOrNull()?.let { draft ->
                currentNote = draft
                title = draft.title ?: ""
                content = noteRepository.readNoteContent(draft.id)
            } ?: run {
                currentNote = createNoteUseCase()
            }
        }
    }

    private fun setupAutoSave() {
        viewModelScope.launch {
            // Observe content changes and auto-save
            combine(
                snapshotFlow { title },
                snapshotFlow { content }
            ) { t, c -> t to c }
                .debounce(1000)
                .collectLatest { (t, c) ->
                    currentNote?.let { note ->
                        try {
                            _saveStatus.value = "saving"
                            autoSaveNoteUseCase(note.id, c, t)
                            _saveStatus.value = "saved"
                        } catch (_: Exception) {
                            _saveStatus.value = "save_failed"
                        }
                    }
                }
        }
    }

    fun toggleMode() {
        mode = if (mode == "edit") "preview" else "edit"
    }

    fun updateMode(nextMode: String) {
        if (nextMode == "edit" || nextMode == "preview") {
            mode = nextMode
        }
    }

    val knowledgeBaseNames: StateFlow<List<String>> = knowledgeRepository.observeAllBases()
        .map { bases -> bases.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createNewNote() {
        viewModelScope.launch {
            currentNote = createNoteUseCase()
            title = ""
            content = ""
            mode = "preview"
            hasVoiceTranscriptionContent = false
            savedKnowledgeItemId = null
        }
    }

    fun markVoiceTranscriptionContent() {
        hasVoiceTranscriptionContent = true
    }

    fun appendMarkdown(markdown: String) {
        content += markdown
        viewModelScope.launch {
            forceSaveDraft()
        }
    }

    suspend fun forceSaveDraft() {
        currentNote?.let { note ->
            try {
                _saveStatus.value = "saving"
                autoSaveNoteUseCase(note.id, content, title)
                _saveStatus.value = "saved"
            } catch (_: Exception) {
                _saveStatus.value = "save_failed"
            }
        }
    }

    suspend fun saveToKnowledgeBase(kbName: String): String {
        forceSaveDraft()
        knowledgeRepository.ensureDefaultBases()
        val bases = knowledgeRepository.observeAllBases().first()
        val targetBase = bases.find { it.name == kbName }
            ?: bases.find { it.type == "inspiration" }
            ?: bases.find { it.type == "unfiled" }
        val targetName = targetBase?.name ?: "灵感空间"

        val savedTitle = title.trim().ifBlank { "灵感 ${System.currentTimeMillis()}" }
        val savedContent = content.trim()
        if (savedContent.isEmpty() && title.trim().isEmpty()) return targetName

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(savedContent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val existingId = savedKnowledgeItemId
        if (existingId != null) {
            val existing = knowledgeRepository.getItemById(existingId)
            if (existing != null) {
                knowledgeRepository.updateItem(existing.copy(
                    title = savedTitle,
                    contentMarkdown = savedContent,
                    excerpt = savedContent.take(100),
                    contentHash = hash,
                    updatedAt = System.currentTimeMillis()
                ))
                return targetName
            }
        }

        importSourceUseCase.importText(
            title = savedTitle,
            text = savedContent,
            targetKbId = targetBase?.id,
            importFrom = "manual",
            folderHint = "灵感空间"
        )
        return targetName
    }

    suspend fun polishVoiceTranscriptionContent(): Result<String> {
        val original = content.trim()
        if (original.isBlank()) return Result.success("")

        val polished = AiGateway().complete(
            systemPrompt = """
                你是一个语音转写文本校对助手。
                你的任务只包括：修正错别字、明显语音识别错误、标点、换行和 Markdown 格式。
                严禁改变原文含义，严禁新增观点、删除信息、改写风格或总结压缩。
                保留原文中的中英文、数字、专有名词和口语表达。
                只输出润色后的 Markdown 正文，不要解释，不要加标题，不要使用代码块包裹。
            """.trimIndent(),
            userMessage = """
                请对下面这段用户语音转写内容进行轻量润色和纠错。

                原始内容：
                ```markdown
                $original
                ```
            """.trimIndent()
        ).trim().removeMarkdownFence()

        val failurePrefixes = listOf("[配置缺失]", "[AI 调用失败]", "[连接失败]", "[超时]", "[AI 调用异常]", "[API 错误]", "[解析失败]")
        if (failurePrefixes.any { polished.startsWith(it) }) {
            return Result.failure(IllegalStateException(polished))
        }

        content = polished
        forceSaveDraft()
        return Result.success(polished)
    }

    private fun String.removeMarkdownFence(): String {
        return replace(Regex("^```(?:markdown)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
    }
}
