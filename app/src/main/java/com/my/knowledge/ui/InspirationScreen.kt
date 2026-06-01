package com.my.knowledge.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    val scope = rememberCoroutineScope()

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

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (transcript.isNotBlank()) {
                viewModel.appendMarkdown("\n\n> 语音转写\n\n$transcript\n")
                Toast.makeText(context, "语音已插入 Markdown", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要记录的灵感")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { Toast.makeText(context, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show() }
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
                    Surface(
                        onClick = {
                            scope.launch {
                                val savedTo = viewModel.saveToKnowledgeBase(selectedLibrary)
                                Toast.makeText(context, "已保存到「${savedTo}」知识库", Toast.LENGTH_SHORT).show()
                            }
                        },
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
                            value = content,
                            onValueChange = { viewModel.content = it },
                            placeholder = { Text("先记下来，不必马上整理。", fontSize = 16.sp, color = Color(0xFFD4D4D4)) },
                            modifier = Modifier.fillMaxWidth(),
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
                        .padding(bottom = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        containerColor = Color.White,
                        contentColor = Color(0xFF147EC5),
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp).shadow(4.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
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
}

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
