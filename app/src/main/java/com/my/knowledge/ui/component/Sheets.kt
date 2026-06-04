package com.my.knowledge.ui.component

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.ui.ComposeMarkdown
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R

/**
 * Result of a confirmed import. Caller decides what to do with it — the
 * sheet is intentionally VM-free so two different VMs (home & KB-detail)
 * can share the same UI.
 *
 * @param displayName file name as shown in the picker
 * @param mimeType resolved by ContentResolver; may be null for unknown types
 * @param targetKbId ID of the chosen / locked knowledge base. Null only
 *                  if the caller passed an empty `lockedKbId` AND the
 *                  user somehow confirmed without a selection (shouldn't
 *                  happen — the sheet always picks a default).
 */
data class ImportConfirmRequest(
    val displayName: String,
    val mimeType: String?,
    val targetKbId: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    uri: Uri,
    knowledgeBases: List<KnowledgeBaseEntity>,
    onClose: () -> Unit,
    onConfirm: (ImportConfirmRequest) -> Unit,
    /**
     * When non-null, the sheet hides the KB dropdown and locks the
     * destination to this KB. Use this when the user picks a file from
     * INSIDE a knowledge base — the destination is obvious and forcing
     * a choice is just friction.
     */
    lockedKb: KnowledgeBaseEntity? = null
) {
    val context = LocalContext.current
    val fileName = remember(uri) { getFileName(context, uri) ?: "未知文档" }
    var selectedLibrary by remember { mutableStateOf(lockedKb?.name ?: "未归类") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(knowledgeBases, lockedKb) {
        if (lockedKb != null) {
            // Locked mode: the dropdown is hidden, but keep the state in
            // sync so the callback below knows which KB to use.
            selectedLibrary = lockedKb.name
            return@LaunchedEffect
        }
        if (knowledgeBases.isNotEmpty() && knowledgeBases.none { it.name == selectedLibrary }) {
            selectedLibrary = knowledgeBases.firstOrNull { it.type == "unfiled" }?.name
                ?: knowledgeBases.first().name
        }
    }

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
            Text(stringResource(R.string.auto_f4cd0bec), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF7FBFF),
                border = BorderStroke(1.dp, Color(0xFFDBEEFF))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = Color(0xFF147EC5))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fileName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                        Text(stringResource(R.string.auto_36872c93), fontSize = 12.sp, color = Color(0xFF5F87A3))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(stringResource(R.string.auto_442d21cc), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF737373))
            Spacer(modifier = Modifier.height(8.dp))

            if (lockedKb != null) {
                // Locked mode: render the destination as a non-interactive
                // chip so the user can SEE where it's going, but can't
                // accidentally redirect it to another base. The whole point
                // of being inside a KB is the import lands HERE.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEFF7FF),
                    border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lockedKb.name,
                            fontSize = 14.sp,
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.auto_7edce8dc),
                            fontSize = 11.sp,
                            color = Color(0xFF147EC5)
                        )
                    }
                }
            } else {
                Box {
                    Surface(
                        onClick = { expanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedLibrary, fontSize = 14.sp, color = Color(0xFF262626))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFFA3A3A3))
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Color.White).fillMaxWidth(0.85f)
                    ) {
                        knowledgeBases.forEach { kb ->
                            DropdownMenuItem(
                                text = { Text(kb.name) },
                                onClick = {
                                    selectedLibrary = kb.name
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val mimeType = context.contentResolver.getType(uri)
                    val targetKbId = if (lockedKb != null) {
                        lockedKb.id
                    } else {
                        knowledgeBases.firstOrNull { it.name == selectedLibrary }?.id
                    }
                    onConfirm(
                        ImportConfirmRequest(
                            displayName = fileName,
                            mimeType = mimeType,
                            targetKbId = targetKbId
                        )
                    )
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
            ) {
                Text(stringResource(R.string.auto_3d048808), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
    val activeConversationId by askViewModel.activeConversationId.collectAsState()
    val conversationsWithCount by askViewModel.conversationsWithCount.collectAsState()
    var inputText by remember { mutableStateOf("") }
    // History drawer: collapsed by default so the message stream is the
    // primary surface; a single tap on the chevron in the header expands
    // it. State is per-sheet (not saved across recompositions of the
    // sheet) so reopening the sheet returns to the conversation view.
    var historyExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.92f),
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFEEEEEE)) }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxSize()
        ) {
            // ---- Header: title + new-conversation + history toggle + close ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_b773dbb2), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    if (activeConversationId != null) {
                        TextButton(
                            onClick = { askViewModel.startNewConversation() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(stringResource(R.string.auto_ccde88e4), fontSize = 12.sp, color = Color(0xFF147EC5))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Inline history toggle. Replaces the previous
                    // standalone AskHistorySheet — every conversation the
                    // user has ever had in this scope is now reachable
                    // from inside the sheet itself.
                    if (conversationsWithCount.isNotEmpty()) {
                        TextButton(
                            onClick = { historyExpanded = !historyExpanded },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                if (historyExpanded) Icons.Default.ExpandLess else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF147EC5)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (historyExpanded) "收起历史" else "历史（${conversationsWithCount.size}）",
                                fontSize = 12.sp,
                                color = Color(0xFF147EC5)
                            )
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.background(Color(0xFFF5F5F5), CircleShape).size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // ---- Inline history drawer ----
            if (historyExpanded && conversationsWithCount.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF7FBFF),
                    border = BorderStroke(0.5.dp, Color(0xFFDBEEFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            stringResource(R.string.auto_539d8bbe),
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(conversationsWithCount, key = { it.conversation.id }) { row ->
                                val isActive = row.conversation.id == activeConversationId
                                Surface(
                                    onClick = {
                                        askViewModel.selectConversation(row.conversation.id)
                                        historyExpanded = false
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isActive) Color(0xFFE0F2FE) else Color.White,
                                    border = if (isActive) BorderStroke(0.5.dp, Color(0xFF147EC5))
                                    else BorderStroke(0.5.dp, Color(0xFFEEEEEE)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                row.conversation.title,
                                                fontSize = 13.sp,
                                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                                color = Color(0xFF0F172A),
                                                maxLines = 1
                                            )
                                            Text(
                                                "${formatAskTime(row.conversation.updatedAt)} · ${row.messageCount} 条消息",
                                                fontSize = 10.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        if (isActive) {
                                            Surface(
                                                color = Color(0xFF147EC5),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    stringResource(R.string.auto_25e74dce),
                                                    fontSize = 10.sp,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Messages area ----
            if (messages.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
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
                                Text(stringResource(R.string.auto_7f318ca4), fontSize = 13.sp, color = Color(0xFF5F87A3))
                            }
                        }
                    }
                }
            } else if (activeConversationId == null) {
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

            // ---- Input area ----
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
                        placeholder = { Text(stringResource(R.string.auto_86b9da2d), fontSize = 14.sp, color = Color(0xFFA3A3A3)) },
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

private fun formatAskTime(timestamp: Long): String {
    if (timestamp <= 0) return "—"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7L * 86_400_000 -> "${diff / 86_400_000} 天前"
        else -> {
            val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            format.format(java.util.Date(timestamp))
        }
    }
}

@Composable
fun AiMessageContent(
    content: String,
    isUser: Boolean,
    messageKey: String = content,
    modifier: Modifier = Modifier
) {
    if (isUser) {
        Text(
            text = content,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = Color(0xFF0F172A)
        )
    } else {
        // Models differ on how they delimit their reasoning:
        //   - <think>...</think>   (DeepSeek-R1, Qwen-QwQ, OpenAI o-series)
        //   - <thinking>...</thinking>
        //   - <reasoning>...</reasoning>
        //   - <reflection>...</reflection>
        // The original implementation only knew `<think>`, which meant
        // every other provider's chain-of-thought got folded into the
        // user-visible answer — making the "思考过程折叠" toggle useless
        // in practice. We accept any of them, case-insensitive, and pick
        // the EARLIEST open tag in the content (some models emit a stray
        // `</think>` after the body, which we also need to clean up).
        val parsed = remember(content) { splitThinkAndBody(content) }
        val thinkPart = parsed.think
        val actualContent = parsed.body
        val isStreaming = parsed.thinkingInProgress

        Column(modifier = modifier) {
            // Collapsible "思考过程" surface. Visible only when the model
            // produced (or is producing) a reasoning block — the toggle
            // is a single tap, expanded state persists per-message via
            // rememberSaveable so the user can leave it open while
            // scrolling.
            if (thinkPart != null) {
                var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = !expanded },
                    color = Color(0xFFF3F4F6),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF6B7280)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                buildString {
                                    append(if (expanded) "收起思考过程" else "已折叠思考过程")
                                    if (isStreaming) append(" · 思考中…")
                                },
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (expanded && thinkPart.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                thinkPart,
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            if (actualContent.isNotBlank()) {
                ComposeMarkdown(
                    markdown = actualContent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class ThinkSplit(
    val think: String?,
    val body: String,
    val thinkingInProgress: Boolean
)

/**
 * Pull the model's chain-of-thought out of [content] and return the
 * think text, the post-think body, and a flag telling the caller
 * whether the open tag has not yet been closed (we're still streaming).
 *
 * Strategy: scan for the first open tag (`<think>` / `<thinking>` /
 * `<reasoning>` / `<reflection>`, case-insensitive). If we find one,
 * look for the matching close tag. If found, return everything between
 * them as `think` and everything after as `body`. If not found, treat
 * the rest of the content as a `think` in progress and the body as
 * empty — the streaming UI shows a "思考中…" hint and the body appears
 * once the model closes the tag.
 *
 * Models that don't emit a reasoning tag at all return `think = null`
 * and the body is the full content. That matches the prior behaviour
 * for non-thinking models and keeps the toggle invisible.
 */
private fun splitThinkAndBody(content: String): ThinkSplit {
    if (content.isEmpty()) return ThinkSplit(null, "", false)
    val openTags = listOf("<think>", "<thinking>", "<reasoning>", "<reflection>")
    val closeTags = mapOf(
        "<think>" to "</think>",
        "<thinking>" to "</thinking>",
        "<reasoning>" to "</reasoning>",
        "<reflection>" to "</reflection>"
    )
    val lower = content.lowercase()
    val firstOpen = openTags
        .map { it to lower.indexOf(it) }
        .filter { it.second >= 0 }
        .minByOrNull { it.second }
        ?: return ThinkSplit(null, content.trim(), false)
    val openTag = firstOpen.first
    val openIdx = firstOpen.second
    val closeTag = closeTags[openTag]!!
    val closeIdx = lower.indexOf(closeTag, startIndex = openIdx + openTag.length)
    return if (closeIdx == -1) {
        ThinkSplit(content.substring(openIdx + openTag.length).trim(), "", true)
    } else {
        val think = content.substring(openIdx + openTag.length, closeIdx).trim()
        val body = content.substring(closeIdx + closeTag.length).trim()
        ThinkSplit(think, body, false)
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = alignment,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isUser) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(top = 8.dp, end = 4.dp),
                    tint = Color(0xFF147EC5)
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = bgColor,
                shadowElevation = 0.dp,
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    AiMessageContent(content = msg.content, isUser = isUser, messageKey = msg.id)
                }
            }
        }

        // Save as knowledge button for all assistant messages
        if (!isUser && msg.savedAsKnowledgeItemId == null && msg.content.isNotBlank()) {
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
                Text(stringResource(R.string.auto_ad868eb9), fontSize = 11.sp, color = Color(0xFF147EC5))
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
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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

private fun buildImportedMarkdown(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
    mimeType: String
): String {
    val size = readSize(context, uri)
    val header = buildString {
        appendLine("# $fileName")
        appendLine()
        appendLine("- 来源：外部导入")
        appendLine("- MIME：$mimeType")
        if (size != null) appendLine("- 大小：$size bytes")
        appendLine("- URI：$uri")
        appendLine()
    }
    return when {
        mimeType.startsWith("text/") ||
            mimeType.contains("json") ||
            mimeType.contains("markdown") ||
            mimeType.contains("csv") -> {
            header + readTextPreview(context, uri)
        }
        mimeType.startsWith("image/") -> {
            header + "![导入图片]($uri)\n\n> 图片已进入本地加工队列，后续 OCR 会在处理任务中补全文字内容。"
        }
        mimeType.contains("pdf") -> {
            header + "> PDF 已注册为来源文件。当前版本先保存元数据和文件引用，后续解析任务会补充页文本与片段。"
        }
        mimeType.contains("word") ||
            mimeType.contains("officedocument") -> {
            header + "> Word 文档已注册为来源文件。当前版本先保存元数据和文件引用，后续解析任务会补充正文片段。"
        }
        else -> {
            header + "> 文件已注册为来源文件，等待加工任务解析。"
        }
    }
}

private fun readTextPreview(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().take(200_000)
            }
        }.orEmpty()
    }.getOrElse {
        "> 文本读取失败：${it.message ?: "未知错误"}"
    }
}

private fun readSize(context: android.content.Context, uri: Uri): Long? {
    if (uri.scheme != "content") return null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return try {
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index != -1 && !cursor.isNull(index)) cursor.getLong(index) else null
        } else {
            null
        }
    } finally {
        cursor?.close()
    }
}
