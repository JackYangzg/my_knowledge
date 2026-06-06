package com.my.knowledge.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.ai.AiTextCleaner.cleanModelOutput
import com.my.knowledge.data.ai.AiTextCleaner.removeMarkdownFence
import com.my.knowledge.data.ai.AiTextCleaner.removeThinkBlock
import com.my.knowledge.data.db.entity.NoteEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.usecase.AutoSaveNoteUseCase
import com.my.knowledge.domain.usecase.CreateNoteUseCase
import com.my.knowledge.domain.usecase.ImportSourceUseCase
import com.my.knowledge.domain.repository.NoteRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import com.my.knowledge.data.util.Sha256
import kotlinx.coroutines.launch
import org.json.JSONArray

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteEditorViewModel(
    private val createNoteUseCase: CreateNoteUseCase,
    private val autoSaveNoteUseCase: AutoSaveNoteUseCase,
    private val noteRepository: NoteRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val importSourceUseCase: ImportSourceUseCase,
    private val scheduler: ProcessingTaskScheduler? = null,
) : ViewModel() {

    var currentNote by mutableStateOf<NoteEntity?>(null)
        private set

    var title by mutableStateOf("")
    var content by mutableStateOf("")
    var mode by mutableStateOf("edit") // edit, preview
    var hasVoiceTranscriptionContent by mutableStateOf(false)
        private set
    private var savedKnowledgeItemId: String? = null

    private var lastPushedTitle by mutableStateOf("")
    private var lastPushedContent by mutableStateOf("")

    val isDirty: Boolean
        get() = title != lastPushedTitle || content != lastPushedContent
    
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
                val savedTitle = draft.title ?: ""
                val savedContent = noteRepository.readNoteContent(draft.id)
                title = savedTitle
                content = savedContent
                
                // If it was already saved to a knowledge base, it's not dirty initially
                // Actually, currentNote.id and savedKnowledgeItemId are different.
                // We'd need to track if THIS draft has been pushed.
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

    private val inspirationBase = knowledgeRepository.observeAllBases()
        .map { bases -> bases.firstOrNull { it.type == "inspiration" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val inspirationItems: StateFlow<List<KnowledgeItemEntity>> = inspirationBase
        .filterNotNull()
        .flatMapLatest { base -> knowledgeRepository.observeItemsByKb(base.id, 200, 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inspirationThread: StateFlow<InspirationThreadUi> = combine(inspirationBase, inspirationItems) { base, items ->
        base to items
    }
        .flatMapLatest { (base, items) ->
            flow {
                val thread = base?.let { knowledgeRepository.getThreadByKb(it.id) }
                val latestDiff = if (thread != null) {
                    // threadLogDao.observeByThread 是 Flow,但这里只需要"最新一条"
                    // 拿它的 diff 解析;如果 worker 还没跑过,latestDiff == null,
                    // UI 不画角标(UNCHANGED),跟老 thread 体感一致。
                    runCatching {
                        knowledgeRepository.observeThreadLogs(thread.id).first().firstOrNull()
                    }.getOrNull()?.summary?.let { LlmThreadDiff.parseFromLogSummary(it) }
                } else null
                emit(InspirationThreadUi.from(items, thread, latestDiff))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InspirationThreadUi.empty())

    // THREAD-E2: full evolution history for the inspiration KB.
    // Drives the "演化历史" expandable panel in [InspirationScreen].
    // Flat-maps the inspiration base to its thread (if any) and then
    // to the full ordered log list. Empty list when no thread exists
    // yet, which is the common cold-start state.
    val inspirationThreadLogs: StateFlow<List<KnowledgeThreadLogEntity>> = inspirationBase
        .filterNotNull()
        .flatMapLatest { base ->
            flow {
                val thread = knowledgeRepository.getThreadByKb(base.id)
                if (thread == null) {
                    emit(emptyList())
                } else {
                    knowledgeRepository.observeThreadLogs(thread.id).collect { emit(it) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // THREAD-E3: tracks whether a manual evolution is in flight.
    // `true` from the moment [triggerInspirationEvolution] enqueues
    // the job until the underlying [inspirationThread] reflects a new
    // `updatedAt` (or a 60s safety timeout fires). The home screen
    // uses this to disable the button and show a spinner. We poll
    // 1Hz — cheap, idempotent, and avoids wiring WorkManager work
    // info into this ViewModel just for a single boolean.
    private val _inspirationEvolving = MutableStateFlow(false)
    val inspirationEvolving: StateFlow<Boolean> = _inspirationEvolving.asStateFlow()

    fun triggerInspirationEvolution() {
        val base = inspirationBase.value ?: return
        val scheduler = scheduler ?: return
        val beforeUpdatedAt = inspirationThread.value.let { ui ->
            // InspirationThreadUi wraps the raw thread entity; the
            // `updatedAt` is propagated through so we can detect the
            // worker's write the same way ThreadViewModel does.
            ui.toThreadUpdatedAt()
        }
        // 用户点「重新演化」走 LLM re-evolve:worker 读最近 N 条灵感 full
        // content + 现有脉络当草稿,整体重写。incremental 路径只在
        // saveToKnowledgeBase 里随新灵感触发,这里不复用。
        scheduler.scheduleLlmThreadUpdate(
            kbId = base.id,
            newItemId = null,
            triggerType = "inspiration_re_evolve",
            mode = "re_evolve",
        )
        _inspirationEvolving.value = true
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(1_000)
                val current = inspirationThread.value.toThreadUpdatedAt()
                if (current != null && current != beforeUpdatedAt) {
                    break
                }
            }
            _inspirationEvolving.value = false
        }
    }

    /**
     * Extract the underlying `updatedAt` from an [InspirationThreadUi]
     * so the polling loop can detect the worker's write without
     * re-fetching the raw [KnowledgeThreadEntity]. Returns `null`
     * when the UI is in its empty state (no thread row yet).
     */
    private fun InspirationThreadUi.toThreadUpdatedAt(): Long? =
        // The UI model exposes `description` / `coreQuestion` / etc.
        // We piggy-back on the log list which is always populated when
        // a thread exists; the latest log's `createdAt` is a tight
        // proxy for "thread was just rewritten" without changing the
        // UI model surface.
        inspirationThreadLogs.value.firstOrNull()?.createdAt

    fun createNewNote() {
        viewModelScope.launch {
            currentNote = createNoteUseCase()
            title = ""
            content = ""
            lastPushedTitle = ""
            lastPushedContent = ""
            mode = "edit"
            hasVoiceTranscriptionContent = false
            savedKnowledgeItemId = null
        }
    }

    fun markVoiceTranscriptionContent() {
        hasVoiceTranscriptionContent = true
    }

    /**
     * Re-purpose the editor for editing an existing knowledge item.
     *
     * Called when the user taps "编辑" on the knowledge viewer page. The
     * item's content is copied into the editor and `rawNoteId` is preserved
     * so that saving from the editor re-uses the same `knowledge_item` row
     * (see `saveToKnowledgeBase`).
     */
    fun loadFromKnowledgeItem(itemId: String) {
        viewModelScope.launch {
            val item = knowledgeRepository.getItemById(itemId) ?: return@launch
            val noteId = item.rawNoteId
            val note = if (noteId != null) {
                noteRepository.getNoteById(noteId) ?: createNoteUseCase()
            } else {
                createNoteUseCase()
            }
            currentNote = note
            title = item.title
            content = item.contentMarkdown
            lastPushedTitle = item.title
            lastPushedContent = item.contentMarkdown
            mode = "edit"
            hasVoiceTranscriptionContent = false
            savedKnowledgeItemId = item.id
        }
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

        val hash = Sha256.hex(savedContent)

        val noteId = currentNote?.id
        val now = System.currentTimeMillis()

        // Look up an existing knowledge item for THIS note. If found, update
        // it in place — no new source / new item / new parse task. This is
        // the user-asked behaviour: same inspiration note → same knowledge
        // entry, edits overwrite, not append.
        val existingForNote = noteId?.let { knowledgeRepository.getByRawNoteId(it) }
        if (existingForNote != null) {
            knowledgeRepository.updateItem(
                existingForNote.copy(
                    title = savedTitle,
                    contentMarkdown = savedContent,
                    excerpt = savedContent.take(100),
                    contentHash = hash,
                    updatedAt = now,
                    // If the user picked a different knowledge base, move it.
                    knowledgeBaseId = targetBase?.id ?: existingForNote.knowledgeBaseId
                )
            )
            savedKnowledgeItemId = existingForNote.id
            lastPushedTitle = title
            lastPushedContent = content
            scheduleLlmThreadUpdateIfInspiration(targetBase, existingForNote.id, triggerType = "inspiration_edited")
            return targetName
        }

        // Fall back to the old in-memory hint for callers that already
        // saved once this session.
        val inMemoryExisting = savedKnowledgeItemId?.let { knowledgeRepository.getItemById(it) }
        if (inMemoryExisting != null) {
            knowledgeRepository.updateItem(inMemoryExisting.copy(
                title = savedTitle,
                contentMarkdown = savedContent,
                excerpt = savedContent.take(100),
                contentHash = hash,
                updatedAt = now,
                knowledgeBaseId = targetBase?.id ?: inMemoryExisting.knowledgeBaseId
            ))
            lastPushedTitle = title
            lastPushedContent = content
            scheduleLlmThreadUpdateIfInspiration(targetBase, inMemoryExisting.id, triggerType = "inspiration_edited")
            return targetName
        }

        // First-time save: create a fresh source + item, but link the item
        // back to the note so subsequent saves deduplicate.
        val newSourceId = importSourceUseCase.importText(
            title = savedTitle,
            text = savedContent,
            targetKbId = targetBase?.id,
            importFrom = "manual",
            folderHint = "灵感空间",
            linkedNoteId = noteId
        )
        lastPushedTitle = title
        lastPushedContent = content
        // 拿新创建的 knowledge_item id(importText 返回的是 sourceId),用于
        // 触发灵感脉络的 LLM 增量更新。
        val newItem = knowledgeRepository.getItemBySourceId(newSourceId)
        newItem?.let { scheduleLlmThreadUpdateIfInspiration(targetBase, it.id, triggerType = "inspiration_added") }
        return targetName
    }

    /**
     * 仅当保存到「灵感空间」时调度 LLM 脉络更新;其他知识库走原来的
     * 启发式脉络(避免给"分布式系统"这种大型 KB 跑 LLM,会贵且慢)。
     * 注:灵感空间是用户的"思考沙盒",需要真正的语义脉络,而不是 tag 聚类。
     */
    private fun scheduleLlmThreadUpdateIfInspiration(
        targetBase: com.my.knowledge.data.db.entity.KnowledgeBaseEntity?,
        itemId: String,
        triggerType: String,
    ) {
        val base = targetBase ?: return
        if (base.type != "inspiration") return
        val sched = scheduler ?: return
        sched.scheduleLlmThreadUpdate(
            kbId = base.id,
            newItemId = itemId,
            triggerType = triggerType,
        )
    }

    suspend fun generateTitle(): Result<String> {
        val original = content.trim()
        if (original.isBlank()) return Result.failure(IllegalStateException("内容为空，无法生成标题"))

        val generated = AiGateway().complete(
            systemPrompt = "你是一个起标题专家。请根据用户提供的内容，生成一个简短、有吸引力且准确的标题（通常在15字以内）。只输出标题文字，不要包含引号、解释或其他修饰。",
            userMessage = "内容：\n$original"
        ).trim().cleanModelOutput().removePrefix("\"").removeSuffix("\"")

        val failurePrefixes = listOf("[配置缺失]", "[AI 调用失败]", "[连接失败]", "[超时]", "[AI 调用异常]", "[API 错误]", "[解析失败]")
        if (failurePrefixes.any { generated.startsWith(it) }) {
            return Result.failure(IllegalStateException(generated))
        }

        title = generated
        forceSaveDraft()
        return Result.success(generated)
    }

    suspend fun polishContent(): Result<String> {
        val original = content.trim()
        if (original.isBlank()) return Result.success("")

        return try {
            val polished = AiGateway().complete(
                systemPrompt = """
                    你是一个文字润色和纠错专家。
                    你的任务是：修正错别字、标点符号、明显的语法错误，并优化分段和排版，使其更易读。
                    
                    原则：
                    1. 严禁改变原文核心含义。
                    2. 严禁新增观点或删除关键信息。
                    3. 保持原文的语气和风格（如果是口语则保留口语感）。
                    4. 输出纯文本或 Markdown 格式。
                    5. 只输出润色后的正文内容，不要包含任何开场白、解释或总结。
                """.trimIndent(),
                userMessage = "待润色内容如下：\n\n$original"
            ).trim().cleanModelOutput()

            val failurePrefixes = listOf("[配置缺失]", "[AI 调用失败]", "[连接失败]", "[超时]", "[AI 调用异常]", "[API 错误]", "[解析失败]")
            if (failurePrefixes.any { polished.startsWith(it) }) {
                return Result.failure(IllegalStateException(polished))
            }

            content = polished
            forceSaveDraft()
            Result.success(polished)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun polishVoiceTranscriptionContent(): Result<String> {
        return polishContent()
    }
}

data class InspirationThreadUi(
    val summary: String,
    val mainlines: List<String>,
    val questions: List<String>,
    val nextActions: List<String>,
    // P1: LLM 增量脉络的 diff —— 主线条中哪些是"本次新增",哪些是"被改写",
    // 哪些是"被废弃"。前端 InspirationScreen 拿它来在主线条上画"🆕"角标,
    // 让用户直观看到"这次保存带来了什么变化"。
    //
    // 旧 thread(没有 LLM 增量脉络)→ diff 为空,UI 退回到无角标。
    val diff: ThreadDiffUi = ThreadDiffUi(),
) {
    /**
     * 增量 diff 的 UI 镜像。`mainlineSegmentHints` 给出 mainlines 列表中
     * 每个元素的"身份 hint"和"本次变化标记",UI 渲染时按 hint 决定是否
     * 画 "🆕" / "↻" / "✕" 角标。
     */
    data class ThreadDiffUi(
        val newMainlineSegments: List<String> = emptyList(),
        val evolvedSegments: List<EvolvedSegment> = emptyList(),
        val obsoleteSegments: List<String> = emptyList(),
        // 跟 mainlines 数组 1:1 对应:每个 mainline 元素的 hint 标记。
        // `null` 表示无变化(老 thread 或非 LLM 脉络)。
        val mainlineSegmentHints: List<SegmentHint> = emptyList(),
    )

    data class EvolvedSegment(
        val label: String,
        val before: String,
        val after: String,
    )

    enum class SegmentHint { NEW, EVOLVED, OBSOLETE, UNCHANGED }

    companion object {
        fun empty(): InspirationThreadUi = InspirationThreadUi(
            summary = "还没有足够的灵感内容。新建几条灵感后，这里会自动整理出主线、问题和下一步。",
            mainlines = emptyList(),
            questions = emptyList(),
            nextActions = emptyList()
        )

        fun from(
            items: List<KnowledgeItemEntity>,
            thread: KnowledgeThreadEntity? = null,
            latestDiff: LlmThreadDiff? = null,
        ): InspirationThreadUi {
            if (items.isEmpty()) return empty()
            if (thread != null) {
                val mainlines = parseStringList(thread.mainlineJson)
                val gaps = parseStringList(thread.gapsJson)
                val suggestions = parseStringList(thread.nextSuggestionsJson)
                return InspirationThreadUi(
                    summary = thread.description.ifBlank { localSummary(items) },
                    mainlines = mainlines.ifEmpty { localMainlines(items) },
                    questions = buildList {
                        if (thread.coreQuestion.isNotBlank()) add(thread.coreQuestion)
                        addAll(gaps)
                    }.ifEmpty { localQuestions(items) },
                    nextActions = suggestions.ifEmpty { localNextActions(items) },
                    diff = buildDiffUi(mainlines, latestDiff),
                )
            }
            return InspirationThreadUi(
                summary = localSummary(items),
                mainlines = localMainlines(items),
                questions = localQuestions(items),
                nextActions = localNextActions(items),
            )
        }

        private fun buildDiffUi(
            mainlines: List<String>,
            diff: LlmThreadDiff?,
        ): ThreadDiffUi {
            if (diff == null || mainlines.isEmpty()) {
                return ThreadDiffUi(mainlineSegmentHints = mainlines.map { SegmentHint.UNCHANGED })
            }
            val newSet = diff.newMainlineSegments.toSet()
            val obsoleteSet = diff.obsoleteSegments.toSet()
            val evolvedLabels = diff.evolvedSegments.map { it.label }.toSet()
            val hints = mainlines.map { line ->
                when {
                    line in newSet -> SegmentHint.NEW
                    line in obsoleteSet -> SegmentHint.OBSOLETE
                    evolvedLabels.any { line.contains(it) } -> SegmentHint.EVOLVED
                    else -> SegmentHint.UNCHANGED
                }
            }
            return ThreadDiffUi(
                newMainlineSegments = diff.newMainlineSegments,
                evolvedSegments = diff.evolvedSegments,
                obsoleteSegments = diff.obsoleteSegments,
                mainlineSegmentHints = hints,
            )
        }

        private fun localSummary(items: List<KnowledgeItemEntity>): String {
            val keywords = extractKeywords(items)
            return buildString {
                append("当前灵感空间共有 ${items.size} 条灵感")
                if (keywords.isNotEmpty()) append("，近期集中在 ${keywords.take(4).joinToString("、")} 等方向")
                append("。")
            }
        }

        private fun localMainlines(items: List<KnowledgeItemEntity>): List<String> {
            val recent = items.sortedByDescending { it.updatedAt }
            val titles = recent.take(6).map { it.title.trim() }.filter { it.isNotBlank() }
            val keywords = extractKeywords(items)
            return buildList {
                if (titles.isNotEmpty()) add("近期主线：${titles.take(3).joinToString(" → ")}")
                if (keywords.isNotEmpty()) add("关键词聚合：${keywords.take(6).joinToString("、")}")
                add("结构化整理：把零散记录沉淀为可追踪的问题、素材和行动线索。")
            }
        }

        private fun localQuestions(items: List<KnowledgeItemEntity>): List<String> =
            items.sortedByDescending { it.updatedAt }
                .take(3)
                .map { it.title.trim() }
                .filter { it.isNotBlank() }
                .map { "「$it」背后还需要补充哪些证据、场景或下一步？" }

        private fun localNextActions(items: List<KnowledgeItemEntity>): List<String> {
            val keywords = extractKeywords(items)
            return buildList {
                add("挑选 1 条最有推进价值的灵感，补充背景、目标和约束。")
                add("把相近灵感合并为一个主题，并保存到对应知识库继续加工。")
                if (keywords.isNotEmpty()) add("围绕 ${keywords.first()} 新建一条延展灵感或行动计划。")
            }
        }

        private fun extractKeywords(items: List<KnowledgeItemEntity>): List<String> =
            items.sortedByDescending { it.updatedAt }
                .flatMap {
                    "${it.title} ${it.summary.orEmpty()} ${it.excerpt} ${it.contentMarkdown.take(2000)}"
                        .split(Regex("[\\s，。！？、,.!?；;：:()（）\\[\\]#*`]+"))
                }
                .map { it.trim() }
                .filter { it.length in 2..18 }
                .filterNot { it.all(Char::isDigit) }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .map { it.first }
                .take(8)

        private fun parseStringList(json: String): List<String> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                List(array.length()) { index -> array.optString(index).trim() }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * 灵感脉络的 LLM 增量 diff —— 跟 [com.my.knowledge.worker.LlmInspirationThreadWorker]
 * 写入 threadLog summary 末尾的 `<!--DIFF-V1: ... -->` 哨兵块配套。
 *
 * 这一份独立 data class(放 ViewModel 而不是 Worker)是为了 UI 层拿数据
 * 时不用反射 Worker 的 private 内部类型;Worker 写,这里读,边界清晰。
 */
data class LlmThreadDiff(
    val newMainlineSegments: List<String> = emptyList(),
    val evolvedSegments: List<InspirationThreadUi.EvolvedSegment> = emptyList(),
    val obsoleteSegments: List<String> = emptyList(),
) {
    companion object {
        /** 从 threadLog.summary 末尾哨兵块解析 diff;没有哨兵 → null。 */
        fun parseFromLogSummary(summary: String?): LlmThreadDiff? {
            if (summary.isNullOrBlank()) return null
            val sentinel = "<!--DIFF-V1:"
            val start = summary.indexOf(sentinel)
            if (start < 0) return null
            val end = summary.indexOf("-->", start + sentinel.length)
            if (end < 0) return null
            val json = summary.substring(start + sentinel.length, end)
            return parseFromJson(json)
        }

        internal fun parseFromJson(json: String): LlmThreadDiff? = runCatching {
            val obj = org.json.JSONObject(json)
            val newSegs = obj.optJSONArray("newMainlineSegments")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } } }
                ?: emptyList()
            val obsoleteSegs = obj.optJSONArray("obsoleteSegments")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } } }
                ?: emptyList()
            val evolved = obj.optJSONArray("evolvedSegments")
                ?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                        val label = o.optString("label").trim()
                        val before = o.optString("before").trim()
                        val after = o.optString("after").trim()
                        if (label.isBlank() && before.isBlank() && after.isBlank()) null
                        else InspirationThreadUi.EvolvedSegment(label, before, after)
                    }
                }
                ?: emptyList()
            LlmThreadDiff(newSegs, evolved, obsoleteSegs)
        }.getOrNull()
    }
}
