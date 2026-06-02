package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.AskCitationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.ui.component.AiMessageContent
import kotlinx.coroutines.launch

@Composable
fun AskScreen(
    viewModel: AskViewModel,
    itemTitle: String,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val citations by viewModel.lastCitations.collectAsState()
    val debugPrompts by viewModel.debugPrompts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF))
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF147EC5)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("返回上一层", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI 对话",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "基于「$itemTitle」的知识内容",
                    fontSize = 13.sp,
                    color = Color(0xFF5F87A3),
                    modifier = Modifier.padding(top = 4.dp).weight(1f)
                )
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清空对话",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFEF4444)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空历史", fontSize = 13.sp, color = Color(0xFFEF4444))
                }
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty() && !isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📚", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "知识内容已加载到上下文中",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "在下方输入问题，AI 将基于该知识内容为你解答",
                                fontSize = 13.sp,
                                color = Color(0xFF5F87A3)
                            )
                        }
                    }
                }
            }

            items(messages) { msg ->
                val visibleCitations = if (msg.role == "assistant" && msg == messages.lastOrNull { it.role == "assistant" }) citations else emptyList()
                MessageBubble(
                    msg = msg,
                    citations = visibleCitations,
                    debugPrompt = debugPrompts[msg.id],
                    onSaveAsKnowledge = { viewModel.saveAnswerAsKnowledge(msg.id) }
                )
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF147EC5)
                        )
                        Text("AI 正在思考...", fontSize = 13.sp, color = Color(0xFF5F87A3))
                    }
                }
            }
        }

        // Input bar
        Surface(
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("输入问题...", color = Color(0xFFA3A3A3), fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF147EC5),
                        unfocusedBorderColor = Color(0xFFDBEEFF)
                    )
                )
                IconButton(
                    onClick = {
                        val question = inputText.trim()
                        if (question.isNotEmpty() && !isLoading) {
                            viewModel.askQuestion(question)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (inputText.isNotBlank() && !isLoading) Color(0xFF147EC5)
                            else Color(0xFFE5E5E5)
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: AiMessageEntity,
    citations: List<AskCitationEntity>,
    debugPrompt: String? = null,
    onSaveAsKnowledge: () -> Unit
) {
    val isUser = msg.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) Color(0xFF147EC5) else Color.White,
            border = if (!isUser) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEF6FF)) else null,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                AiMessageContent(
                    content = msg.content,
                    isUser = isUser,
                    messageKey = msg.id,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isUser && !debugPrompt.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("调试上下文", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                debugPrompt,
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                                color = Color.White.copy(alpha = 0.86f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) "你" else "AI",
                        fontSize = 11.sp,
                        color = if (isUser) Color.White.copy(alpha = 0.7f) else Color(0xFFA3A3A3)
                    )
                    if (!isUser) {
                        val isSaved = msg.savedAsKnowledgeItemId != null
                        TextButton(
                            onClick = onSaveAsKnowledge,
                            enabled = !isSaved,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isSaved) "已保存到知识库" else "保存到知识库",
                                fontSize = 11.sp,
                                color = if (isSaved) Color(0xFFA3A3A3) else Color(0xFF147EC5)
                            )
                        }
                    }
                }
                if (!isUser && citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    citations.take(4).forEach { citation ->
                        CitationRow(citation)
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationRow(citation: AskCitationEntity) {
    Surface(
        color = when (citation.label) {
            AskCitationEntity.LABEL_SOURCE -> Color(0xFFEFF7FF)
            AskCitationEntity.LABEL_INFERENCE -> Color(0xFFFFF7ED)
            else -> Color(0xFFFEF2F2)
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "〖${citation.label}〗${citation.fragmentId?.let { " 片段 ${it.take(8)}" } ?: ""}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F172A)
            )
            Text(
                citation.quote,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFF5F87A3),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
