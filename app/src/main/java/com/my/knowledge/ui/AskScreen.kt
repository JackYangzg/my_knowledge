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
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun AskScreen(
    viewModel: AskViewModel,
    itemTitle: String,
    onBack: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
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
            .background(palette.bgPage)
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
                    tint = palette.brand
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.auto_94c32741), style = MaterialTheme.typography.titleSmall, color = palette.brand)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auto_37f4358f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "基于「$itemTitle」的知识内容", style = MaterialTheme.typography.labelLarge,
                    color = palette.textSecondary,
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
                        tint = palette.semanticError
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.auto_578bbcfa), style = MaterialTheme.typography.labelLarge, color = palette.semanticError)
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
                                stringResource(R.string.auto_c5b06dab),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.auto_b5d282f1), style = MaterialTheme.typography.labelLarge,
                                color = palette.textSecondary
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
                            color = palette.brand
                        )
                        Text(stringResource(R.string.auto_55b4eacf), style = MaterialTheme.typography.labelLarge, color = palette.textSecondary)
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
                    placeholder = { Text(stringResource(R.string.auto_650b2de8), color = palette.textTertiary, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.brand,
                        unfocusedBorderColor = palette.borderBrand
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
                            if (inputText.isNotBlank() && !isLoading) palette.brand
                            else palette.borderDefault
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
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
            color = if (isUser) palette.brand else Color.White,
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
                            Text(stringResource(R.string.auto_7f13af3e), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                debugPrompt, style = MaterialTheme.typography.labelSmall,
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
                        text = if (isUser) "你" else "AI", style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) Color.White.copy(alpha = 0.7f) else palette.textTertiary
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
                                text = if (isSaved) "已保存到知识库" else "保存到知识库", style = MaterialTheme.typography.labelSmall,
                                color = if (isSaved) palette.textTertiary else palette.brand
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

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(
        color = when (citation.label) {
            AskCitationEntity.LABEL_SOURCE -> palette.brandSubtle
            AskCitationEntity.LABEL_INFERENCE -> Color(0xFFFFF7ED)
            else -> palette.semanticErrorBg
        },
        shape = RoundedCornerShape(spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "〖${citation.label}〗${citation.fragmentId?.let { " 片段 ${it.take(8)}" } ?: ""}", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
            Text(
                citation.quote, style = MaterialTheme.typography.labelSmall,
                lineHeight = 16.sp,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
