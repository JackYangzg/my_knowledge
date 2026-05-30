package com.my.knowledge.ui.component

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(viewModel: KnowledgeHomeViewModel, uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val fileName = remember(uri) { getFileName(context, uri) ?: "未知文档" }
    var selectedLibrary by remember { mutableStateOf("未归类") }
    var expanded by remember { mutableStateOf(false) }
    
    val knowledgeBases by viewModel.knowledgeBases.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFEEEEEE)) }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("确认导入", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF7FBFF),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFDBEEFF))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = Color(0xFF147EC5))
                    Column {
                        Text(fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text("准备导入到知识库", fontSize = 12.sp, color = Color(0xFF5F87A3))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedLibrary,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("目标知识库") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f).background(Color.White)
                ) {
                    knowledgeBases.forEach { lib ->
                        DropdownMenuItem(
                            text = { Text(lib.name) },
                            onClick = {
                                selectedLibrary = lib.name
                                expanded = false
                            }
                        )
                    }
                }
                Box(modifier = Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = true }
                ))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val mimeType = context.contentResolver.getType(uri) ?: "unknown"
                    val type = when {
                        mimeType.startsWith("image/") -> "图片"
                        mimeType.contains("pdf") -> "PDF"
                        mimeType.contains("word") -> "Word"
                        else -> "文档"
                    }
                    viewModel.importFile(fileName, type, "从外部导入的文件内容...", selectedLibrary)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
            ) {
                Text("开始分析并导入", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    askViewModel: AskViewModel,
    onClose: () -> Unit
) {
    val messages by askViewModel.messages.collectAsState()
    val isLoading by askViewModel.isLoading.collectAsState()
    val conversations by askViewModel.conversations.collectAsState()
    val activeConversationId by askViewModel.activeConversationId.collectAsState()
    var inputText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFEEEEEE)) }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .heightIn(max = 560.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("问一问", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    if (activeConversationId != null) {
                        TextButton(
                            onClick = { askViewModel.startNewConversation() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("+ 新对话", fontSize = 12.sp, color = Color(0xFF147EC5))
                        }
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color(0xFFF5F5F5), CircleShape).size(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversations list (when no active conversation or showing history)
            if (activeConversationId == null && conversations.isNotEmpty()) {
                Text("历史对话", fontSize = 12.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(bottom = 8.dp))
                conversations.take(5).forEach { conv ->
                    Surface(
                        onClick = { askViewModel.selectConversation(conv.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF7FBFF),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            conv.title,
                            fontSize = 13.sp,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Messages area
            if (messages.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        MessageBubble(msg, onSaveAsKnowledge = { messageId ->
                            askViewModel.saveAnswerAsKnowledge(messageId)
                        })
                    }
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF147EC5)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("思考中...", fontSize = 13.sp, color = Color(0xFF5F87A3))
                            }
                        }
                    }
                }
            } else if (activeConversationId == null) {
                // Suggested questions
                val suggestions = listOf(
                    "这个知识库现在主要在讲什么？",
                    "帮我整理明天会议可以讲的观点",
                    "最近导入的内容有哪些值得归档？"
                )
                suggestions.forEach { q ->
                    Surface(
                        onClick = {
                            askViewModel.startNewConversation()
                            inputText = q
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        border = BorderStroke(0.5.dp, Color(0xFFF3F3F3)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(q, fontSize = 14.sp, color = Color(0xFF0F172A), modifier = Modifier.padding(18.dp, 15.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input area
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                color = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { if (!isLoading) inputText = it },
                        placeholder = { Text("输入问题", fontSize = 14.sp, color = Color(0xFFA3A3A3)) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                askViewModel.askQuestion(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isLoading,
                        modifier = Modifier.size(36.dp).background(
                            if (inputText.isNotBlank() && !isLoading) Color(0xFF111827) else Color(0xFFE5E5E5),
                            RoundedCornerShape(14.dp)
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (inputText.isNotBlank() && !isLoading) Color.White else Color(0xFFA3A3A3),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: AiMessageEntity,
    onSaveAsKnowledge: (String) -> Unit
) {
    val isUser = msg.role == "user"
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    val bgColor = if (isUser) Color(0xFFEFF7FF) else Color(0xFFF9FAFB)
    val textColor = Color(0xFF0F172A)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = alignment,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isUser) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    tint = Color(0xFF147EC5)
                )
            }
            Surface(
                shape = RoundedCornerShape(if (isUser) 16.dp else 16.dp),
                color = bgColor,
                shadowElevation = 0.dp
            ) {
                Text(
                    msg.content,
                    fontSize = 13.sp,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    lineHeight = 20.sp
                )
            }
        }

        // Save as knowledge button for all assistant messages
        if (!isUser && msg.savedAsKnowledgeItemId == null) {
            TextButton(
                onClick = { onSaveAsKnowledge(msg.id) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(
                    Icons.Default.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color(0xFF147EC5)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("保存为知识", fontSize = 11.sp, color = Color(0xFF147EC5))
            }
        }
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
