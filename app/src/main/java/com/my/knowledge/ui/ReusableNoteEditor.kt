package com.my.knowledge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.data.ai.VolcengineVoiceService
import com.my.knowledge.viewmodel.NoteEditorViewModel
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reusable note editor for creating a knowledge item in any non-recyclebin KB.
 *
 * Replaces the previous scope of `InspirationScreen`'s editor block (lines
 * 342-665 + 688+ of the original file). The editor keeps the same UX
 * (voice / image / attachment / AI / save-confirm) but is now driven by
 * an injected `initialKbName` so the same Composable can be opened from
 * any KB list with a "+" FAB.
 */
@Composable
fun ReusableNoteEditor(
    viewModel: NoteEditorViewModel,
    initialKbName: String,
    onDismiss: () -> Unit,
    onSaved: (kbName: String) -> Unit = {}
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val title = viewModel.title
    val content = viewModel.content
    val mode = viewModel.mode

    var selectedLibrary by remember { mutableStateOf(initialKbName) }
    var showAiMenu by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var aiActionStatus by remember { mutableStateOf<String?>(null) }
    var completionMessage by remember { mutableStateOf<String?>(null) }

    var preVoiceContent by remember { mutableStateOf("") }
    var sessionCommittedText by remember { mutableStateOf("") }
    var lastPartialAtCommit by remember { mutableStateOf("") }

    var contentValue by remember {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }

    // T7: reset voice state on entry so the first commit lands (no leak from
    // a prior KB session).
    LaunchedEffect(Unit) {
        preVoiceContent = ""
        sessionCommittedText = ""
        lastPartialAtCommit = ""
        aiActionStatus = null
        completionMessage = null
    }

    val voiceService = remember { VolcengineVoiceService(context) }
    val voiceState by voiceService.stateFlow.collectAsState()

    // T3: ensure voice service is released on dispose.
    DisposableEffect(Unit) {
        onDispose { voiceService.release() }
    }

    fun commitVoiceTranscript(rawText: String) {
        val transcript = rawText.trim()
        if (transcript.isBlank()) return
        if (transcript == sessionCommittedText) {
            lastPartialAtCommit = transcript
            return
        }
        sessionCommittedText = transcript
        lastPartialAtCommit = transcript
        val base = preVoiceContent.trimEnd()
        val nextTotalText = mergeWithOverlap(base, sessionCommittedText)
        contentValue = TextFieldValue(nextTotalText, selection = TextRange(nextTotalText.length))
        viewModel.content = nextTotalText
        viewModel.markVoiceTranscriptionContent()
    }

    LaunchedEffect(voiceService) {
        voiceService.finalTranscriptFlow.collect { finalTranscript ->
            commitVoiceTranscript(finalTranscript)
        }
    }

    LaunchedEffect(content) {
        if (contentValue.text != content) {
            contentValue = TextFieldValue(text = content, selection = TextRange(content.length))
        }
    }

    LaunchedEffect(voiceState.isRecording, voiceState.statusMessage) {
        if (!voiceState.isRecording && voiceState.statusMessage.contains("30 秒")) {
            commitVoiceTranscript(voiceState.partialTranscript)
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
        contentValue = TextFieldValue(newText, TextRange(newCursorPosition))
        viewModel.content = newText
    }

    // T8: 20MB cap + filename whitelist + scheme allowlist
    fun copyUriToInternal(uri: Uri, subDir: String, maxBytes: Long = 20L * 1024 * 1024): File? {
        return try {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "content" && scheme != "file") return null
            val rawName = uri.lastPathSegment?.takeLast(60) ?: "file"
            val safe = rawName
                .replace(Regex("[/\\\\]"), "_")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('.', '_')
                .ifBlank { "file" }
                .take(60)
            val fileName = "${System.currentTimeMillis()}_$safe"
            val destDir = File(context.filesDir, subDir)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileName)
            val written = context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
                destFile.length()
            } ?: 0L
            if (written <= 0L || written > maxBytes) {
                destFile.delete()
                null
            } else destFile
        } catch (_: Exception) {
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
                Toast.makeText(context, "图片过大或保存失败", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "附件过大或保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startSpeechInput() {
        keyboardController?.hide()
        if (mode != "edit") {
            Toast.makeText(context, "请切换到编辑模式以使用语音输入", Toast.LENGTH_SHORT).show()
            return
        }
        if (voiceState.isRecording) return
        preVoiceContent = contentValue.text
        sessionCommittedText = ""
        lastPartialAtCommit = ""
        voiceService.startRealtimeTranscription()
    }

    // T6: 200ms 防双击 (跟 InspirationScreen 同步)。根因可能是 stopRecording() async
    // 导致 voiceState.isRecording 翻转 50-200ms,期间用户单击被识别成 start+stop。
    var lastVoiceTapMs = 0L

    fun stopSpeechInput() {
        val now = System.currentTimeMillis()
        if (now - lastVoiceTapMs < 200) return  // debounce
        lastVoiceTapMs = now
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
    }

    fun requestSave() {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
        scope.launch {
            val result = viewModel.saveToKnowledgeBase(selectedLibrary)
            Toast.makeText(context, "已保存到「$result」知识库", Toast.LENGTH_SHORT).show()
            onSaved(result)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeechInput() else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    BackHandler {
        if (voiceState.isRecording) {
            commitVoiceTranscript(voiceState.partialTranscript)
            voiceService.stopRecording()
        }
        if (viewModel.isDirty) {
            showExitConfirmDialog = true
        } else {
            onDismiss()
        }
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
                            if (viewModel.isDirty) {
                                showExitConfirmDialog = true
                            } else {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = palette.textPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建知识", style = MaterialTheme.typography.displayLarge, color = palette.textPrimary)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(spacing.sm),
                        color = palette.bgSubtle,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "保存到 $selectedLibrary",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.textPrimary)
                        }
                    }
                    Box {
                        IconButton(onClick = { showAiMenu = true }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI", tint = palette.textPrimary)
                        }
                        DropdownMenu(
                            expanded = showAiMenu,
                            onDismissRequest = { showAiMenu = false },
                            offset = DpOffset(x = 0.dp, y = 4.dp),
                            modifier = Modifier.width(220.dp).background(Color.White),
                            shape = RoundedCornerShape(spacing.lg)
                        ) {
                            DropdownMenuTextItem("大模型润色", "AI", mode == "edit") {
                                showAiMenu = false
                                aiActionStatus = "正在润色全文"
                                scope.launch {
                                    val result = viewModel.polishContent()
                                    aiActionStatus = null
                                    if (result.isSuccess) completionMessage = "润色完成"
                                    else Toast.makeText(context, result.exceptionOrNull()?.message ?: "润色失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                            DropdownMenuTextItem("标题生成", "AI", mode == "edit") {
                                showAiMenu = false
                                aiActionStatus = "标题生成中"
                                scope.launch {
                                    val result = viewModel.generateTitle()
                                    aiActionStatus = null
                                    if (result.isSuccess) completionMessage = "标题已更新"
                                    else Toast.makeText(context, result.exceptionOrNull()?.message ?: "生成失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                            DropdownMenuTextItem("润色语音转写", "AI", mode == "edit" && viewModel.hasVoiceTranscriptionContent) {
                                showAiMenu = false
                                aiActionStatus = "正在润色转写"
                                scope.launch {
                                    val result = viewModel.polishVoiceTranscriptionContent()
                                    aiActionStatus = null
                                    if (result.isSuccess) completionMessage = "转写已润色"
                                    else Toast.makeText(context, result.exceptionOrNull()?.message ?: "润色失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = { showSaveConfirm = true },
                        enabled = viewModel.isDirty
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "保存", tint = if (viewModel.isDirty) palette.brand else palette.textMuted)
                    }
                }
            }

            // Quick actions (image / attachment) in edit mode
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
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            shape = RoundedCornerShape(spacing.sm),
                            color = palette.bgSubtle
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF4B5563))
                                Text(stringResource(R.string.auto_be8da62e), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4B5563))
                            }
                        }
                        Surface(
                            onClick = { attachmentPickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(spacing.sm),
                            color = palette.bgSubtle
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF4B5563))
                                Text(stringResource(R.string.auto_99f6fe6c), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4B5563))
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.bgSubtle, modifier = Modifier.padding(top = 4.dp))
            }

            // Editor area
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
                            modifier = Modifier.fillMaxWidth(),
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

        // Voice button (long-press)
        Surface(
            shape = CircleShape,
            color = if (voiceState.isRecording) palette.borderBrand else palette.brand,
            contentColor = Color.White,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .imePadding()
                .padding(bottom = 24.dp, end = 24.dp)
                .size(72.dp)
                .clickable {
                    if (voiceState.isRecording) {
                        stopSpeechInput()
                    } else {
                        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            startSpeechInput()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (voiceState.isRecording) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = palette.brand, strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }
        }

        if (completionMessage != null) {
            LaunchedEffect(completionMessage) {
                delay(1000)
                completionMessage = null
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(spacing.sm), modifier = Modifier.padding(bottom = 100.dp)) {
                    Text(text = completionMessage!!, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 15.sp)
                }
            }
        }
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("保存", fontWeight = FontWeight.Bold) },
            text = { Text("将当前内容保存到「$selectedLibrary」？") },
            confirmButton = {
                TextButton(onClick = { showSaveConfirm = false; requestSave() }) {
                    Text("保存", color = palette.brand, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("未保存的修改", fontWeight = FontWeight.Bold) },
            text = { Text("当前内容尚未保存,如何处理?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmDialog = false
                    requestSave()
                    onDismiss()
                }) { Text("保存并退出", color = palette.brand, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitConfirmDialog = false; onDismiss() }) { Text("不保存") }
                    TextButton(onClick = { showExitConfirmDialog = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun DropdownMenuTextItem(
    label: String,
    rightText: String,
    enabled: Boolean,
    isStrong: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (enabled) Color(0xFF1F2937) else Color(0xFFB0B7C3),
                fontWeight = if (isStrong) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (rightText.isNotEmpty()) {
                Surface(color = Color(0xFFEFF6FF), shape = RoundedCornerShape(4.dp)) {
                    Text(rightText, fontSize = 11.sp, color = Color(0xFF3B82F6), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

/**
 * Merge two strings with overlap handling — used by the live voice preview
 * to combine the prior content with the in-flight partial transcript.
 */
private fun mergeWithOverlap(left: String, right: String): String {
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left
    if (right.startsWith(left)) return right
    if (left.endsWith(right)) return left
    val maxOverlap = minOf(left.length, right.length, 200)
    for (i in maxOverlap downTo 1) {
        if (left.endsWith(right.substring(0, i))) {
            return left + right.substring(i)
        }
    }
    return if (left.isEmpty()) right else "$left\n$right"
}
