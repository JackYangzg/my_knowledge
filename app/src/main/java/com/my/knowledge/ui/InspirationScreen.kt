package com.my.knowledge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.my.knowledge.data.ai.VoiceRecognitionState
import com.my.knowledge.data.ai.VolcengineVoiceService
import com.my.knowledge.viewmodel.NoteEditorViewModel
import com.mukesh.MarkDown
import kotlinx.coroutines.launch

@Composable
fun InspirationScreen(viewModel: NoteEditorViewModel) {
    val context = LocalContext.current
    val mode = viewModel.mode
    val title = viewModel.title
    val content = viewModel.content
    val saveStatus by viewModel.saveStatus.collectAsState()

    var showMoreMenu by remember { mutableStateOf(false) }
    var selectedLibrary by remember { mutableStateOf("灵感空间") }
    var showLibraryPicker by remember { mutableStateOf(false) }
    var showNewConfirmDialog by remember { mutableStateOf(false) }
    var showVoicePolishDialog by remember { mutableStateOf(false) }
    var isPolishingVoiceContent by remember { mutableStateOf(false) }
    var voicePolishError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val voiceService = remember { VolcengineVoiceService(context) }
    val voiceState by voiceService.stateFlow.collectAsState()
    var lastCommittedVoiceText by remember { mutableStateOf("") }
    var voiceSessionBaseText by remember { mutableStateOf("") }
    var voiceCommittedInSession by remember { mutableStateOf("") }

    var contentValue by remember {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }

    fun commitVoiceTranscript(rawText: String) {
        val transcript = normalizeVoiceText(rawText)
        if (transcript.isBlank() || transcript == lastCommittedVoiceText) return
        val committed = normalizeVoiceText(voiceCommittedInSession)
        val delta = transcript.deltaAfter(committed)
            .ifBlank { transcript.deltaAfter(normalizeVoiceText(voiceSessionBaseText)) }
            .ifBlank { if (contentValue.text.containsNormalized(transcript)) "" else transcript }
        if (delta.isBlank()) return
        val base = contentValue.text.trimEnd()
        if (base.containsNormalized(delta) || base.endsWith(delta)) return
        val nextText = if (base.isBlank()) delta else "$base\n$delta"
        contentValue = TextFieldValue(nextText, selection = TextRange(nextText.length))
        viewModel.content = nextText
        viewModel.markVoiceTranscriptionContent()
        voiceCommittedInSession = normalizeVoiceText("$voiceCommittedInSession $delta")
        lastCommittedVoiceText = delta
    }

    val commitVoiceTranscriptLatest by rememberUpdatedState(newValue = ::commitVoiceTranscript)

    LaunchedEffect(voiceService) {
        voiceService.finalTranscriptFlow.collect { finalTranscript ->
            commitVoiceTranscriptLatest(finalTranscript)
        }
    }

    // Sync contentValue when viewModel.content changes
    LaunchedEffect(content) {
        if (contentValue.text != content) {
            contentValue = TextFieldValue(
                text = content,
                selection = TextRange(content.length)
            )
        }
    }

    // When switching to edit mode, move cursor to the end and request focus
    LaunchedEffect(mode) {
        if (mode == "edit") {
            contentValue = contentValue.copy(selection = TextRange(contentValue.text.length))
            kotlinx.coroutines.delay(100) // Small delay to ensure TextField is composed
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(voiceState.isRecording, voiceState.statusMessage) {
        if (!voiceState.isRecording && voiceState.statusMessage.contains("30 秒")) {
            commitVoiceTranscriptLatest(voiceState.partialTranscript)
            Toast.makeText(context, "30 秒未检测到人声，已停止录音", Toast.LENGTH_SHORT).show()
        }
    }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.appendMarkdown("\n![image]($it)\n")
        }
    }

    // Attachment picker
    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = uri.lastPathSegment ?: "attachment"
            viewModel.appendMarkdown("\n[${fileName}]($it)\n")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceService.release()
        }
    }

    fun startSpeechInput() {
        if (mode != "edit") {
            Toast.makeText(context, "请切换到编辑模式以使用语音输入", Toast.LENGTH_SHORT).show()
            return
        }

        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
            return
        }

        lastCommittedVoiceText = ""
        voiceCommittedInSession = ""
        voiceSessionBaseText = contentValue.text
        voiceService.startRealtimeTranscription()
    }

    fun saveDirectly() {
        scope.launch {
            val savedTo = viewModel.saveToKnowledgeBase(selectedLibrary)
            Toast.makeText(context, "已保存到「${savedTo}」知识库", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestSave() {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
        if (viewModel.hasVoiceTranscriptionContent) {
            voicePolishError = null
            showVoicePolishDialog = true
        } else {
            saveDirectly()
        }
    }

    // Voice input - check permission and record
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSpeechInput()
        } else {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 48.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("灵感", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = {
                            if (title.isNotBlank() || content.isNotBlank()) {
                                showNewConfirmDialog = true
                            } else {
                                viewModel.createNewNote()
                                Toast.makeText(context, "已新建灵感", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFDBEEFF))
                    ) {
                        Text(
                            "新建",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF147EC5),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    if (mode == "edit") {
                        Surface(
                            onClick = { requestSave() },
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF111827)
                        ) {
                            Text(
                                "保存",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Markdown Tab Bar
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .background(Color(0xFFEFF7FF), RoundedCornerShape(999.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarkdownModeBtn("编辑", mode == "edit") { viewModel.updateMode("edit") }
                MarkdownModeBtn("查看", mode == "preview") { viewModel.updateMode("preview") }
                Spacer(modifier = Modifier.weight(1f))
            }

            HorizontalDivider(color = Color(0xFFF3F4F6), modifier = Modifier.padding(top = 8.dp))

            // Editor Area
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    if (mode == "edit") {
                        TextField(
                            value = title,
                            onValueChange = { viewModel.title = it },
                            placeholder = { Text("标题", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = if (voiceState.partialTranscript.isNotEmpty()) {
                                val preview = voiceState.partialTranscript.previewDelta(
                                    baseText = contentValue.text,
                                    committedText = voiceCommittedInSession
                                )
                                val liveText = if (preview.isBlank()) {
                                    contentValue.text
                                } else {
                                    "${contentValue.text.trimEnd()}\n$preview".trimStart()
                                }
                                TextFieldValue(liveText, selection = TextRange(liveText.length))
                            } else {
                                contentValue
                            },
                            onValueChange = { 
                                if (voiceState.partialTranscript.isEmpty() && !voiceState.isRecording) {
                                    contentValue = it
                                    viewModel.content = it.text
                                }
                            },
                            placeholder = { Text("先记下来，不必马上整理。", fontSize = 16.sp, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, color = Color(0xFF262626)),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        // Preview Mode
                        if (title.isNotEmpty()) {
                            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (content.isNotEmpty()) {
                            MarkdownPreview(content)
                        } else if (title.isEmpty()) {
                            Text("无内容", fontSize = 16.sp, color = Color(0xFFD4D4D4))
                        }
                    }
                    Spacer(modifier = Modifier.height(100.dp))
                }
                
                // Floating Action Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .imePadding()
                        .padding(bottom = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (mode == "edit") {
                        SmallFloatingActionButton(
                            onClick = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    startSpeechInput()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            containerColor = if (voiceState.isRecording) Color(0xFFDBEEFF) else Color.White,
                            contentColor = Color(0xFF147EC5),
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp).shadow(4.dp, CircleShape)
                        ) {
                            if (voiceState.isRecording) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF147EC5),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Mic, contentDescription = null)
                            }
                        }
                    }
                    FloatingActionButton(
                        onClick = { showMoreMenu = true },
                        containerColor = Color(0xFF147EC5),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp).shadow(12.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = null)
                    }
                }
            }
        }

        if (voiceState.isRecording || voiceState.partialTranscript.isNotBlank() || voiceState.errorMessage != null) {
            VoiceRealtimePanel(
                state = voiceState,
                onStop = {
                    commitVoiceTranscript(voiceState.partialTranscript)
                    voiceService.stopRecording()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 82.dp)
            )
        }

        // More Menu Overlay
        if (showMoreMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showMoreMenu = false }
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 82.dp)
                        .fillMaxWidth()
                        .shadow(22.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFECECEC))
                ) {
                    Column {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("更多", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                            IconButton(
                                onClick = { showMoreMenu = false },
                                modifier = Modifier.size(28.dp).background(Color(0xFFF5F5F5), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF737373))
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF3F4F6))
                        MoreMenuItem(
                            label = "添加图片到文档",
                            rightText = "",
                            onClick = { imagePickerLauncher.launch("image/*") }
                        )
                        MoreMenuItem(
                            label = "添加附件到文档",
                            rightText = "",
                            onClick = { attachmentPickerLauncher.launch("*/*") }
                        )

                        // Library Picker Item
                        Box(modifier = Modifier.fillMaxWidth()) {
                            MoreMenuItem(
                                label = "保存到",
                                rightText = selectedLibrary,
                                isStrong = true,
                                onClick = { showLibraryPicker = !showLibraryPicker }
                            )
                            DropdownMenu(
                                expanded = showLibraryPicker,
                                onDismissRequest = { showLibraryPicker = false },
                                modifier = Modifier.fillMaxWidth(0.8f).background(Color.White)
                            ) {
                                val kbNames by viewModel.knowledgeBaseNames.collectAsState()
                                kbNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedLibrary = name
                                            showLibraryPicker = false
                                        }
                                    )
                                }
                            }
                        }

                        MoreMenuItem("添加标签", "›")
                        MoreMenuItem("移动到知识库", "›")
                        MoreMenuItem("标记需要整理", "›")
                        MoreMenuItem(
                            label = "删除",
                            rightText = "",
                            isDestructive = true,
                            onClick = {
                                viewModel.content = ""
                                viewModel.title = ""
                                showMoreMenu = false
                            }
                        )
                    }
                }
            }

        }
    }

    // New inspiration confirmation dialog
    if (showNewConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNewConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = Color(0xFF147EC5),
                    modifier = Modifier.size(24.dp)
                )
            },
            title = { Text("新建灵感", fontWeight = FontWeight.Bold) },
            text = { Text("当前灵感尚未保存，是否保存后新建？") },
            confirmButton = {
                Button(
                    onClick = {
                        showNewConfirmDialog = false
                        scope.launch {
                            viewModel.saveToKnowledgeBase(selectedLibrary)
                            viewModel.createNewNote()
                            selectedLibrary = "灵感空间"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
                ) {
                    Text("保存并新建", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showVoicePolishDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isPolishingVoiceContent) showVoicePolishDialog = false
            },
            icon = {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = Color(0xFF147EC5),
                    modifier = Modifier.size(24.dp)
                )
            },
            title = { Text("润色语音转写内容？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("检测到当前灵感包含语音转写内容。是否先通过大模型修正错别字、标点和格式后再保存？不会改变原文含义。")
                    voicePolishError?.let {
                        Text(it, fontSize = 13.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isPolishingVoiceContent,
                    onClick = {
                        scope.launch {
                            isPolishingVoiceContent = true
                            voicePolishError = null
                            val polishResult = viewModel.polishVoiceTranscriptionContent()
                            isPolishingVoiceContent = false
                            polishResult
                                .onSuccess {
                                    showVoicePolishDialog = false
                                    saveDirectly()
                                }
                                .onFailure {
                                    voicePolishError = it.message ?: "润色失败，请检查模型配置后重试"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
                ) {
                    if (isPolishingVoiceContent) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("润色后保存", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isPolishingVoiceContent,
                    onClick = {
                        showVoicePolishDialog = false
                        saveDirectly()
                    }
                ) {
                    Text("直接保存原文")
                }
            }
        )
    }
}

@Composable
private fun VoiceRealtimePanel(
    state: VoiceRecognitionState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val level = (state.rms * 18f).coerceIn(0.04f, 1f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (state.errorMessage == null) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.statusMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "中英双语 · 手动停止或 30 秒无人声自动停止",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
                TextButton(onClick = onStop, enabled = state.isRecording) {
                    Text("停止")
                }
            }
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Color(0xFF147EC5),
                trackColor = Color(0xFFEFF7FF)
            )
            val displayText = state.errorMessage ?: state.partialTranscript.ifBlank { "正在等待语音..." }
            Text(
                text = displayText,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = if (state.errorMessage == null) Color(0xFF334155) else Color(0xFFB91C1C),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun String.previewDelta(baseText: String, committedText: String): String {
    val transcript = normalizeVoiceText(this)
    if (transcript.isBlank()) return ""
    val committed = normalizeVoiceText(committedText)
    val delta = transcript.deltaAfter(committed)
        .ifBlank { if (baseText.containsNormalized(transcript)) "" else transcript }
    return delta.trim()
}

private fun String.deltaAfter(prefixText: String): String {
    val current = trim()
    val prefix = prefixText.trim()
    if (current.isBlank()) return ""
    if (prefix.isBlank()) return current
    if (current == prefix || prefix.contains(current)) return ""
    if (current.startsWith(prefix)) return current.removePrefix(prefix).trimStart(' ', '\n', '，', '。', ',', '.')

    val maxOverlap = minOf(prefix.length, current.length)
    for (size in maxOverlap downTo 4) {
        if (prefix.takeLast(size) == current.take(size)) {
            return current.drop(size).trimStart(' ', '\n', '，', '。', ',', '.')
        }
    }
    return if (prefix.containsNormalized(current)) "" else current
}

private fun String.containsNormalized(value: String): Boolean {
    val haystack = normalizeVoiceText(this).compactVoiceText()
    val needle = normalizeVoiceText(value).compactVoiceText()
    return needle.isNotBlank() && haystack.contains(needle)
}

private fun normalizeVoiceText(text: String): String =
    text.replace(Regex("\\s+"), " ")
        .replace(Regex("([，。！？,.!?])\\1+"), "$1")
        .trim()

private fun String.compactVoiceText(): String =
    replace(Regex("[\\s，。！？,.!?；;：:、]"), "")

@Composable
fun MarkdownModeBtn(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) Color.White else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = if (active) 2.dp else 0.dp
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (active) Color(0xFF0F172A) else Color(0xFF737373),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MarkdownPreview(markdown: String) {
    MarkDown(
        modifier = Modifier.fillMaxWidth(),
        text = markdown,
        shouldOpenUrlInBrowser = true
    )
}

@Composable
fun MoreMenuItem(
    label: String, 
    rightText: String, 
    isStrong: Boolean = false, 
    isDestructive: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.White
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (isDestructive) Color(0xFFDC2626) else Color(0xFF111827)
                )
                Text(
                    rightText,
                    fontSize = 14.sp,
                    fontWeight = if (isStrong) FontWeight.Bold else FontWeight.Normal,
                    color = if (isDestructive) Color(0xFFFCA5A5) else if (isStrong) Color(0xFF111827) else Color(0xFFD4D4D4)
                )
            }
            HorizontalDivider(color = Color(0xFFF5F5F5))
        }
    }
}
