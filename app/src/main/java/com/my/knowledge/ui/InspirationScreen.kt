package com.my.knowledge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.my.knowledge.data.ai.VoiceRecognitionState
import com.my.knowledge.data.ai.VolcengineVoiceService
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.viewmodel.InspirationThreadUi
import com.my.knowledge.viewmodel.NoteEditorViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun InspirationScreen(
    viewModel: NoteEditorViewModel,
    onOpenItem: (String) -> Unit = {},
    startInEditor: Boolean = false
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val mode = viewModel.mode
    val title = viewModel.title
    val content = viewModel.content
    val inspirationItems by viewModel.inspirationItems.collectAsState()
    val inspirationThread by viewModel.inspirationThread.collectAsState()
    // THREAD-E3: spinner state for the manual re-evolve button
    val inspirationEvolving by viewModel.inspirationEvolving.collectAsState()

    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedLibrary by remember { mutableStateOf("灵感空间") }
    var showNewConfirmDialog by remember { mutableStateOf(false) }
    // Shown when the user taps the editor's back arrow while there are
    // unsaved changes. Three choices: 保存 (save and exit), 不保存
    // (exit without saving), 取消 (stay in editor). The old code just
    // set `showEditor = false` on back, which silently dropped any
    // in-flight edits — a notorious source of "I typed all that for
    // nothing" complaints.
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var aiActionStatus by remember { mutableStateOf<String?>(null) }
    var completionMessage by remember { mutableStateOf<String?>(null) }
    var showEditor by remember(startInEditor) { mutableStateOf(startInEditor) }
    
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val voiceService = remember { VolcengineVoiceService(context) }
    val voiceState by voiceService.stateFlow.collectAsState()
    
    var preVoiceContent by remember { mutableStateOf("") }
    var isPressingVoiceButton by remember { mutableStateOf(false) }
    var voicePressProgress by remember { mutableStateOf(0f) }
    // Held by the press-progress coroutine to tell the gesture loop that the
    // user kept the button down long enough to actually stop the recording.
    var voiceStopTriggered by remember { mutableStateOf(false) }
    var sessionCommittedText by remember { mutableStateOf("") }
    // 上次 commit 时服务端 partial 的快照. 用来计算"自上次 commit 以来
    // 服务端新追加的部分" (delta), 避免把服务端用来纠错的累积上文重写一遍.
    var lastPartialAtCommit by remember { mutableStateOf("") }

    var contentValue by remember {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }


    LaunchedEffect(completionMessage) {
        if (completionMessage != null) {
            kotlinx.coroutines.delay(1000)
            completionMessage = null
        }
    }

    fun commitVoiceTranscript(rawText: String) {
        val transcript = normalizeVoiceText(rawText)
        if (transcript.isBlank()) return

        // 服务端 partial 是累积的 (带上文用来纠错). 不能直接用 transcript 当增量
        // 跟 sessionCommittedText 合并, 否则纠错时 (比如服务端把"很"改成"真")
        // mergeWithOverlap 会找不到匹配, fallthrough 到 s1+s2 把旧文本重写一遍.
        //
        // 正确做法: 拿当前 partial 跟上次 commit 时的 partial 比, 算出"服务端
        // 自上次 commit 以来新加的部分" (delta), 只把 delta 拼进 session.
        val delta = extractDelta(lastPartialAtCommit, transcript)
        if (delta.isBlank()) return

        val nextSession = mergeWithOverlap(sessionCommittedText, delta)
        if (nextSession == sessionCommittedText) {
            // 即使 session 没变, 也要更新 lastPartialAtCommit 避免重复空跑
            lastPartialAtCommit = transcript
            return
        }

        sessionCommittedText = nextSession
        lastPartialAtCommit = transcript

        val base = preVoiceContent.trimEnd()
        val nextTotalText = mergeWithOverlap(base, nextSession)

        contentValue = TextFieldValue(nextTotalText, selection = TextRange(nextTotalText.length))
        viewModel.content = nextTotalText
        viewModel.markVoiceTranscriptionContent()
    }

    /**
     * 算两个连续 partial 之间的 delta (服务端新追加的内容).
     *
     *  - prev == "" (首次 commit)       → 全部都是新内容, 返回 curr
     *  - prev 是 curr 的前缀           → delta = curr.removePrefix(prev)
     *  - curr 是 prev 的子串           → 服务端做了回退/纠错缩短, 没有新内容
     *  - 部分重叠 (服务端重处理了上文)  → 返回整个 curr, 由后续 mergeWithOverlap
     *                                    跟 sessionCommittedText 做去重
     */

    val commitVoiceTranscriptLatest by rememberUpdatedState(newValue = ::commitVoiceTranscript)

    LaunchedEffect(voiceService) {
        voiceService.finalTranscriptFlow.collect { finalTranscript ->
            commitVoiceTranscriptLatest(finalTranscript)
        }
    }

    LaunchedEffect(content) {
        if (contentValue.text != content) {
            contentValue = TextFieldValue(
                text = content,
                selection = TextRange(content.length)
            )
        }
    }

    LaunchedEffect(mode) {
        if (mode == "edit") {
            contentValue = contentValue.copy(selection = TextRange(contentValue.text.length))
            kotlinx.coroutines.delay(100)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(voiceState.isRecording, voiceState.statusMessage) {
        if (!voiceState.isRecording && voiceState.statusMessage.contains("30 秒")) {
            commitVoiceTranscriptLatest(voiceState.partialTranscript)
            Toast.makeText(context, "30 秒未检测到人声，已停止录音", Toast.LENGTH_SHORT).show()
        }
    }

    fun insertMarkdownAtCursor(markdown: String) {
        val currentText = contentValue.text
        val selection = contentValue.selection
        val start = selection.min.coerceIn(0, currentText.length)
        val end = selection.max.coerceIn(0, currentText.length)
        
        val newText = currentText.substring(0, start) + markdown + currentText.substring(end)
        val newCursorPosition = start + markdown.length
        
        val newValue = TextFieldValue(newText, TextRange(newCursorPosition))
        contentValue = newValue
        viewModel.content = newText
    }

    fun copyUriToInternal(uri: Uri, subDir: String): File? {
        return try {
            val fileName = "${System.currentTimeMillis()}_" + (uri.lastPathSegment?.takeLast(20) ?: "file")
            val destDir = File(context.filesDir, subDir)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            val file = copyUriToInternal(it, "images")
            if (file != null) {
                insertMarkdownAtCursor("\n![image](file://${file.absolutePath})\n")
            } else {
                Toast.makeText(context, "保存图片失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val originalName = selectedUri.lastPathSegment ?: "attachment"
            val file = copyUriToInternal(selectedUri, "attachments")
            if (file != null) {
                insertMarkdownAtCursor("\n[$originalName](file://${file.absolutePath})\n")
            } else {
                Toast.makeText(context, "保存附件失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceService.release() }
    }

    // System back (hardware button / left-edge gesture) when in editor
    // mode. Mirrors the back-arrow onClick below so the user gets
    // the same unsaved-changes guard no matter how they try to
    // leave. When NOT in editor mode, the default back behavior
    // applies (close the inspiration tab, pop to the previous
    // route, etc.) — exactly what the user expects for normal
    // navigation.
    //
    // The `enabled = showEditor` clause is what scopes the
    // interception: when showEditor flips back to false (after a
    // confirmed exit), the handler unregisters itself on the next
    // recomposition and the system back is free to navigate again.
    BackHandler(enabled = showEditor) {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
        if (viewModel.isDirty) {
            showExitConfirmDialog = true
        } else {
            showEditor = false
        }
    }

    fun startSpeechInput() {
        keyboardController?.hide()
        if (mode != "edit") {
            Toast.makeText(context, "请切换到编辑模式以使用语音输入", Toast.LENGTH_SHORT).show()
            return
        }
        if (voiceState.isRecording) {
            return
        }
        preVoiceContent = contentValue.text
        sessionCommittedText = ""
        lastPartialAtCommit = ""
        voiceService.startRealtimeTranscription()
    }

    fun stopSpeechInput() {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
    }

    fun saveDirectly() {
        scope.launch {
            val savedTo = viewModel.saveToKnowledgeBase(selectedLibrary)
            Toast.makeText(context, "已保存到「$savedTo」知识库", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestSave() {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
        saveDirectly()
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeechInput() else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    if (!showEditor) {
        InspirationHomeScreen(
            thread = inspirationThread,
            items = inspirationItems,
            evolving = inspirationEvolving,
            onOpenItem = onOpenItem,
            onNew = {
                viewModel.createNewNote()
                selectedLibrary = "灵感空间"
                showEditor = true
            },
            onEvolve = { viewModel.triggerInspirationEvolution() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 48.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (voiceState.isRecording) {
                                commitVoiceTranscript(voiceState.partialTranscript)
                                voiceService.stopRecording()
                            }
                            // Unsaved-changes guard. Read `isDirty`
                            // inside the lambda (not at composition
                            // time) so we always see the freshest
                            // value, even if the user just typed a
                            // new character. The previous code went
                            // straight to `showEditor = false` and
                            // silently dropped in-flight edits.
                            if (viewModel.isDirty) {
                                showExitConfirmDialog = true
                            } else {
                                showEditor = false
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = palette.textPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.auto_ae2e58ab), style = MaterialTheme.typography.displayLarge, color = palette.textPrimary)
                    if (aiActionStatus != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Surface(color = Color(0xFFEFF6FF), shape = RoundedCornerShape(4.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF3B82F6))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(aiActionStatus!!, style = MaterialTheme.typography.labelMedium, color = Color(0xFF3B82F6))
                            }
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = palette.textPrimary)
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        offset = DpOffset(x = 0.dp, y = 4.dp),
                        modifier = Modifier.width(200.dp).background(Color.White),
                        shape = RoundedCornerShape(spacing.lg)
                    ) {
                        MoreMenuItem(label = "新建灵感", rightText = "", isStrong = true, onClick = {
                            showMoreMenu = false
                            if (title.isNotBlank() || content.isNotBlank()) showNewConfirmDialog = true else {
                                viewModel.createNewNote()
                                showEditor = true
                                completionMessage = "已新建灵感"
                            }
                        })
                        MoreMenuItem(label = "大模型润色", rightText = "AI", enabled = mode == "edit", onClick = {
                            showMoreMenu = false
                            aiActionStatus = "正在润色全文"
                            scope.launch {
                                val result = viewModel.polishContent()
                                aiActionStatus = null
                                if (result.isSuccess) completionMessage = "润色完成" else Toast.makeText(context, result.exceptionOrNull()?.message ?: "润色失败", Toast.LENGTH_SHORT).show()
                            }
                        })
                        MoreMenuItem(label = "标题生成", rightText = "AI", enabled = mode == "edit", onClick = {
                            showMoreMenu = false
                            aiActionStatus = "标题生成中"
                            scope.launch {
                                val result = viewModel.generateTitle()
                                aiActionStatus = null
                                if (result.isSuccess) completionMessage = "标题已更新" else Toast.makeText(context, result.exceptionOrNull()?.message ?: "生成失败", Toast.LENGTH_SHORT).show()
                            }
                        })
                        MoreMenuItem(label = if (mode == "edit") "切换到预览模式" else "切换到编辑模式", rightText = if (mode == "edit") "查看" else "编辑", onClick = {
                            showMoreMenu = false
                            viewModel.toggleMode()
                        })
                        MoreMenuItem(label = "保存到知识库", rightText = selectedLibrary, enabled = viewModel.isDirty, isStrong = true, onClick = {
                            showMoreMenu = false
                            requestSave()
                        })
                    }
                }
            }

            // Quick Actions / Voice (Hidden in preview mode)
            if (mode == "edit") {
                if (voiceState.isRecording || voiceState.partialTranscript.isNotBlank() || voiceState.errorMessage != null) {
                    VoiceRealtimePanel(
                        state = voiceState,
                        onStop = {
                            commitVoiceTranscript(voiceState.partialTranscript)
                            voiceService.stopRecording()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            shape = RoundedCornerShape(spacing.sm),
                            color = palette.bgSubtle
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF4B5563))
                                Text(stringResource(R.string.auto_be8da62e), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4B5563))
                            }
                        }
                        Surface(
                            onClick = { attachmentPickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(spacing.sm),
                            color = palette.bgSubtle
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF4B5563))
                                Text(stringResource(R.string.auto_99f6fe6c), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4B5563))
                            }
                        }
                    }
                }

                HorizontalDivider(color = palette.bgSubtle, modifier = Modifier.padding(top = 4.dp))
            }

            // Editor Area
            Box(modifier = Modifier.weight(1f).imePadding()) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    if (mode == "edit") {
                        TextField(
                            value = title,
                            onValueChange = { viewModel.title = it },
                            placeholder = { Text(stringResource(R.string.auto_748d7dc7), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        TextField(
                            value = if (voiceState.isRecording) {
                                val liveText = mergeWithOverlap(preVoiceContent, mergeWithOverlap(sessionCommittedText, voiceState.partialTranscript))
                                TextFieldValue(liveText, selection = TextRange(liveText.length))
                            } else {
                                contentValue
                            },
                            onValueChange = { 
                                if (!voiceState.isRecording) {
                                    contentValue = it
                                    viewModel.content = it.text
                                }
                            },
                            placeholder = { Text(stringResource(R.string.auto_23e51578), fontSize = 16.sp, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, color = palette.textPrimary),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    } else {
                        if (title.isNotEmpty()) {
                            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (content.isNotEmpty()) {
                            ComposeMarkdown(markdown = content, onLinkClick = { openFile(context, it) })
                        } else if (title.isEmpty()) {
                            Text(stringResource(R.string.auto_de7dadfa), fontSize = 16.sp, color = Color(0xFFD4D4D4))
                        }
                    }
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = if (voiceState.isRecording) {
                if (isPressingVoiceButton) palette.semanticError else palette.borderBrand
            } else palette.brand,
            contentColor = if (voiceState.isRecording) {
                if (isPressingVoiceButton) Color.White else palette.brand
            } else Color.White,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .imePadding()
                .padding(bottom = 24.dp, end = 24.dp)
                .size(72.dp)
                .pointerInput(Unit) {
                    // Key = Unit (constant) so this coroutine does NOT
                    // get cancelled+restarted when voiceState.isRecording
                    // flips. Old code keyed on isRecording, which meant:
                    // first press → isRecording=false → fires
                    // startSpeechInput() → key changes → coroutine
                    // restarts → the still-pressed finger is treated as a
                    // "new" gesture only on the second press. Net effect:
                    // user had to long-press TWICE (first to start,
                    // second to actually trigger the hold timer). Now
                    // both start and hold-timer run in the same gesture.
                    awaitEachGesture {
                        awaitFirstDown()
                        // One press covers both start and hold. If the
                        // recording isn't already on (cold first press),
                        // kick it off here.
                        if (!voiceState.isRecording) {
                            val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                startSpeechInput()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@awaitEachGesture
                            }
                        }

                        // Start the hold timer unconditionally.
                        isPressingVoiceButton = true
                        voicePressProgress = 0f
                        voiceStopTriggered = false
                        val startedAt = System.currentTimeMillis()
                        val stopThresholdMs = 1500L

                        val progressJob = scope.launch {
                            while (isPressingVoiceButton) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                voicePressProgress = (elapsed.toFloat() / stopThresholdMs)
                                    .coerceIn(0f, 1f)
                                if (elapsed >= stopThresholdMs) {
                                    voiceStopTriggered = true
                                    // Held long enough — flag set; keep
                                    // ticking the progress ring until
                                    // the user releases.
                                }
                                delay(16)
                            }
                        }

                        // Block until the user lifts their finger (or
                        // the gesture is cancelled by the system).
                        val up = waitForUpOrCancellation()
                        progressJob.cancel()
                        isPressingVoiceButton = false
                        val heldLongEnough = voiceStopTriggered
                        voiceStopTriggered = false
                        voicePressProgress = 0f

                        if (up == null) {
                            // System cancelled the gesture (e.g. an
                            // interrupting notification). Stop cleanly.
                            stopSpeechInput()
                            return@awaitEachGesture
                        }

                        // Release always stops the recording — the
                        // whole point of the press is "I'm talking,
                        // record me; I let go, stop". ONE press = start
                        // + stop. The 1.5s threshold is just a UX floor
                        // (visual progress ring) so accidental brushes
                        // don't silently record.
                        stopSpeechInput()
                        if (heldLongEnough) {
                            Toast.makeText(
                                context,
                                "已达到停止时长，录音已结束",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // No toast for early-release — the user already
                        // feels the button release; spamming
                        // "请长按结束录音" was the old behavior that
                        // prompted this fix.
                    }
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (voiceState.isRecording) {
                    if (isPressingVoiceButton) {
                        // Show the in-progress ring while the user is holding
                        // the button. The ring fills from 0 → 1 over 1.5s; when
                        // it reaches 1 the recording stops and the icon swaps
                        // back to the resting Mic.
                        CircularProgressIndicator(
                            progress = { voicePressProgress },
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = palette.brand, strokeWidth = 3.dp)
                    }
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }
        }

        if (completionMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(spacing.sm), modifier = Modifier.padding(bottom = 100.dp)) {
                    Text(text = completionMessage!!, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 15.sp)
                }
            }
        }
    }

    if (showNewConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNewConfirmDialog = false },
            icon = { Icon(Icons.Default.Add, contentDescription = null, tint = palette.brand, modifier = Modifier.size(24.dp)) },
            title = { Text(stringResource(R.string.auto_b6b35361), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.auto_04fe85ef)) },
            confirmButton = {
                Button(onClick = {
                    showNewConfirmDialog = false
                    scope.launch {
                        viewModel.saveToKnowledgeBase(selectedLibrary)
                        viewModel.createNewNote()
                        selectedLibrary = "灵感空间"
                        showEditor = true
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = palette.bgInverse)) { Text(stringResource(R.string.auto_ce374873), color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showNewConfirmDialog = false }) { Text(stringResource(R.string.auto_4d0b4688)) } }
        )
    }

    if (showExitConfirmDialog) {
        // Shown when the editor's back button is tapped while
        // `viewModel.isDirty` is true. Three outcomes:
        //   保存   — run `saveToKnowledgeBase`, then exit
        //   不保存 — exit immediately, draft stays in note table
        //            (so the user can recover it via "新建灵感" if
        //            they re-open the editor before the draft is
        //            auto-cleaned up)
        //   取消   — close the dialog, stay in the editor
        // We await the save inside our own coroutine so the
        // `forceSaveDraft` call inside `saveToKnowledgeBase` is
        // guaranteed to complete before the editor is hidden —
        // otherwise the user could "save and exit" and then
        // crash/relaunch and find the title/content never made it
        // to disk.
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = palette.brand,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = { Text(stringResource(R.string.auto_f3ba9caa), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.auto_35becb04)) },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        scope.launch {
                            val savedTo = viewModel.saveToKnowledgeBase(selectedLibrary)
                            Toast.makeText(
                                context,
                                "已保存到「$savedTo」知识库",
                                Toast.LENGTH_SHORT
                            ).show()
                            showEditor = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.bgInverse)
                ) { Text(stringResource(R.string.auto_fadf24db), color = Color.White) }
            },
            dismissButton = {
                // Two buttons live in the dismiss slot because the
                // user spec calls for three options (保存 / 不保存
                // / 取消). 不保存 is the destructive action so it
                // gets a red label, 取消 stays neutral.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showExitConfirmDialog = false
                        showEditor = false
                    }) { Text(stringResource(R.string.auto_95a2fe4a), color = palette.semanticError) }
                    TextButton(onClick = { showExitConfirmDialog = false }) { Text(stringResource(R.string.auto_4d0b4688)) }
                }
            }
        )
    }
}

@Composable
private fun InspirationHomeScreen(
    thread: InspirationThreadUi,
    items: List<KnowledgeItemEntity>,
    evolving: Boolean,
    onOpenItem: (String) -> Unit,
    onNew: () -> Unit,
    onEvolve: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(top = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.auto_ae2e58ab), style = MaterialTheme.typography.displayLarge,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // RELIAB-1 N4: if the user hasn't whitelisted us from
            // battery optimizations, the in-flight LLM request can be
            // silently killed by aggressive OEM ROMs once the screen
            // turns off. A one-tap banner pointing at the system
            // settings page fixes ~80% of "为什么后台跑着跑着就断流"
            // support load. isIgnoring() is a no-op on API < 23, so
            // the banner stays hidden on older devices.
            if (!BatteryOptimizationPrompt.isIgnoring(context)) {
                item {
                    BatteryWhitelistBanner(
                        onClick = { BatteryOptimizationPrompt.launch(context) }
                    )
                }
            }
            item {
                InspirationThreadCard(thread = thread, itemCount = items.size)
            }
            // THREAD-E3: a one-tap "重新演化" button next to the
            // "我的灵感" header. Disabled + spinner while the worker
            // is in flight, so the user gets feedback that the
            // request actually started.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.auto_b589cf12), style = MaterialTheme.typography.titleLarge, color = palette.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${items.size} 条", style = MaterialTheme.typography.labelLarge, color = palette.textMuted)
                        EvolveButton(evolving = evolving, onClick = onEvolve)
                    }
                }
            }
            if (items.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(spacing.sm),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, palette.borderDefault)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.auto_b748221a), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.bgInverse)
                            Text(stringResource(R.string.auto_032a01c0), style = MaterialTheme.typography.labelLarge, lineHeight = 20.sp, color = palette.textMuted)
                        }
                    }
                }
            } else {
                items(items, key = { it.id }) { item ->
                    InspirationItemRow(item = item, onClick = { onOpenItem(item.id) })
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 10.dp
        ) {
            Button(
                onClick = onNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(spacing.sm),
                colors = ButtonDefaults.buttonColors(containerColor = palette.bgInverse)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.auto_b6b35361), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun InspirationThreadCard(thread: InspirationThreadUi, itemCount: Int) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.sm),
        color = palette.bgPage,
        border = BorderStroke(1.dp, Color(0xFFD9ECFF))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFFE0F2FE), modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF0369A1))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_ec9e36b8), style = MaterialTheme.typography.titleLarge, color = palette.textPrimary)
                    Text("基于 $itemCount 条灵感整理", style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
                }
            }
            Text(thread.summary, fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF334155))
            // 主线条带 diff 角标(本次新增/演变/废弃)
            InspirationThreadSection(
                title = stringResource(R.string.auto_342ed943),
                values = thread.mainlines,
                hints = thread.diff.mainlineSegmentHints
            )
            // 增量 diff 摘要,仅在确实有变化时显示
            if (thread.diff.newMainlineSegments.isNotEmpty() ||
                thread.diff.evolvedSegments.isNotEmpty() ||
                thread.diff.obsoleteSegments.isNotEmpty()
            ) {
                ThreadDiffSummary(diff = thread.diff)
            }
            InspirationThreadSection(title = stringResource(R.string.auto_2fb49eec), values = thread.questions)
            InspirationThreadSection(title = stringResource(R.string.auto_ea0ef2ae), values = thread.nextActions)
        }
    }
}

@Composable
private fun InspirationThreadSection(
    title: String,
    values: List<String>,
    hints: List<com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint> = emptyList()
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
        values.take(4).forEachIndexed { i, value ->
            val hint = hints.getOrNull(i) ?: com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.UNCHANGED
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(5.dp)
                        .background(Color(0xFF38BDF8), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            value, style = MaterialTheme.typography.labelLarge,
                            lineHeight = 20.sp,
                            color = Color(0xFF334155),
                            modifier = Modifier.weight(1f)
                        )
                        if (hint != com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.UNCHANGED) {
                            Spacer(modifier = Modifier.width(6.dp))
                            DiffBadge(hint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffBadge(hint: com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val (text, bg, fg) = when (hint) {
        com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.NEW ->
            Triple("🆕 本次新增", Color(0xFFECFDF5), palette.semanticSuccess)
        com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.EVOLVED ->
            Triple("↻ 已演变", Color(0xFFFFFBEB), palette.semanticWarning)
        com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.OBSOLETE ->
            Triple("✕ 已废弃", palette.semanticErrorBg, palette.semanticError)
        com.my.knowledge.viewmodel.InspirationThreadUi.SegmentHint.UNCHANGED ->
            return
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ThreadDiffSummary(diff: com.my.knowledge.viewmodel.InspirationThreadUi.ThreadDiffUi) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(spacing.sm))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.auto_5a0af1a0), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
        if (diff.newMainlineSegments.isNotEmpty()) {
            Text("+ 新增 ${diff.newMainlineSegments.size} 段主线", style = MaterialTheme.typography.labelSmall, color = palette.semanticSuccess)
        }
        if (diff.evolvedSegments.isNotEmpty()) {
            Text("↻ 演变 ${diff.evolvedSegments.size} 段", style = MaterialTheme.typography.labelSmall, color = palette.semanticWarning)
        }
        if (diff.obsoleteSegments.isNotEmpty()) {
            Text("✕ 废弃 ${diff.obsoleteSegments.size} 段", style = MaterialTheme.typography.labelSmall, color = palette.semanticError)
        }
    }
}

@Composable
private fun InspirationItemRow(item: KnowledgeItemEntity, onClick: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val excerpt = item.summary?.takeIf { it.isNotBlank() }
        ?: item.excerpt.takeIf { it.isNotBlank() }
        ?: item.contentMarkdown.replace(Regex("\\s+"), " ").take(120)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.sm),
        color = Color.White,
        border = BorderStroke(1.dp, palette.borderDefault),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.title.ifBlank { "未命名灵感" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.bgInverse,
                    maxLines = 2
                )
                if (excerpt.isNotBlank()) {
                    Text(
                        text = excerpt, style = MaterialTheme.typography.labelLarge,
                        lineHeight = 20.sp,
                        color = palette.textMuted,
                        maxLines = 3
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = palette.bgSubtle) {
                        Text(stringResource(R.string.auto_ae2e58ab), style = MaterialTheme.typography.labelSmall, color = Color(0xFF475569), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                    Text(formatInspirationTime(item.updatedAt), style = MaterialTheme.typography.labelSmall, color = palette.textMuted)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFCBD5E1))
        }
    }
}

@Composable
fun VoiceRealtimePanel(state: VoiceRecognitionState, onStop: () -> Unit, modifier: Modifier = Modifier) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, palette.borderBrand), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(if (state.errorMessage == null) Color(0xFF22C55E) else palette.semanticError, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = state.statusMessage, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onStop) { Text(stringResource(R.string.auto_a17f70a8)) }
            }
            val displayText = state.errorMessage ?: state.partialTranscript.ifBlank { "正在等待语音..." }
            Text(text = displayText, fontSize = 14.sp, lineHeight = 21.sp, color = Color(0xFF334155))
        }
    }
}

private fun normalizeVoiceText(text: String): String = text.replace(Regex("\\s+"), " ").replace(Regex("([，。！？,.!?])\\1+"), "$1").trim()

/**
 * 算两个连续 partial 之间的 delta (服务端新追加的内容).
 *
 *  - prev == "" (首次 commit)       → 全部都是新内容, 返回 curr
 *  - prev 是 curr 的前缀           → delta = curr.removePrefix(prev)
 *  - curr 是 prev 的子串           → 服务端做了回退/纠错缩短, 没有新内容
 *  - 部分重叠 (服务端重处理了上文)  → 返回整个 curr, 由后续 mergeWithOverlap
 *                                    跟 sessionCommittedText 做去重
 */
private fun extractDelta(prev: String, curr: String): String {
    if (prev.isEmpty()) return curr
    if (curr.isEmpty()) return ""
    val maxLen = minOf(prev.length, curr.length)
    var i = 0
    while (i < maxLen && prev[i] == curr[i]) i++
    return when {
        i == prev.length -> curr.substring(i)
        i == curr.length -> ""
        else -> curr
    }
}

private fun mergeWithOverlap(old: String, new: String): String {
    val s1 = old.trim()
    val s2 = new.trim()
    if (s1.isEmpty()) return s2
    if (s2.isEmpty()) return s1

    if (isSimilar(s1, s2)) return s2
    if (containsSimilar(s1, s2)) return s1
    if (containsSimilar(s2, s1)) return s2

    val maxSearch = minOf(s1.length, s2.length, 100)
    for (len in maxSearch downTo 1) {
        val suffix = s1.takeLast(len)
        val prefix = s2.take(len)
        if (isSimilar(suffix, prefix)) {
            return s1.dropLast(len) + s2
        }
    }

    val lastChar = s1.lastOrNull() ?: ' '
    return when {
        lastChar == '\n' -> s1 + s2
        isChinese(lastChar) -> s1 + s2
        lastChar.isLetterOrDigit() -> "$s1 $s2"
        else -> "$s1\n$s2"
    }
}

private fun isSimilar(a: String, b: String): Boolean {
    val ca = cleanText(a)
    val cb = cleanText(b)
    return ca.isNotEmpty() && ca == cb
}

private fun containsSimilar(container: String, content: String): Boolean {
    val cContainer = cleanText(container)
    val cContent = cleanText(content)
    return cContainer.contains(cContent)
}

private fun cleanText(text: String): String =
    text.lowercase().replace(Regex("[\\p{P}\\s]+"), "")

private fun isChinese(c: Char): Boolean = c.code in 0x4E00..0x9FFF

private fun formatInspirationTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

/**
 * N4 (RELIAB-1 PR-N4): lightweight banner that nudges the user to
 * whitelist us from battery optimizations. Sits at the top of the
 * inspiration home so the user sees it before kicking off any
 * AI analysis. The click target is a single Surface (not a wrapping
 * Box + Text) so the whole card is tappable, which matters on
 * small phones — the user shouldn't have to aim for a 16dp icon.
 */
@Composable
private fun BatteryWhitelistBanner(onClick: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.sm),
        color = Color(0xFFFFF7ED),
        border = BorderStroke(1.dp, Color(0xFFFDBA74))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.BatteryAlert,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFFEA580C)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "后台任务可能被系统中断",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF9A3412)
                )
                Text(
                    "为避免 AI 分析被中断,建议在系统设置中将本应用加入电池白名单",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC2410C),
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "去设置 ›",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEA580C)
            )
        }
    }
}

/**
 * THREAD-E3: the "重新演化" button. We keep the same visual weight
 * as the rest of the home screen surface chrome (RoundedCornerShape +
 * subtle background) so it doesn't compete with the primary "新建灵感"
 * CTA at the bottom. The button is disabled and shows a spinner
 * while the worker is in flight; the disabled state also flips the
 * alpha down so the affordance is unambiguous.
 */
@Composable
private fun EvolveButton(evolving: Boolean, onClick: () -> Unit) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        onClick = { if (!evolving) onClick() },
        shape = RoundedCornerShape(spacing.sm),
        color = if (evolving) Color(0xFFEFF6FF) else palette.brand,
        modifier = Modifier.alpha(if (evolving) 0.7f else 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (evolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = palette.brand
                )
            } else {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = Color.White
                )
            }
            Text(
                if (evolving) "整理中…" else "重新演化",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (evolving) palette.brand else Color.White
            )
        }
    }
}


@Composable
fun MoreMenuItem(label: String, rightText: String, isStrong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(onClick = if (enabled) onClick else ({}), modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), color = Color.White) {
        Column {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = palette.bgInverse)
                Text(rightText, fontSize = 14.sp, fontWeight = if (isStrong) FontWeight.Bold else FontWeight.Normal, color = if (isStrong) palette.bgInverse else Color(0xFFD4D4D4))
            }
            HorizontalDivider(color = palette.bgSubtle)
        }
    }
}
