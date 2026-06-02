package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.component.KnowledgeItemRow
import com.my.knowledge.ui.component.AskSheet
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.data.ai.ScopeType

@Composable
fun KnowledgeDetailScreen(
    kbName: String,
    viewModel: KnowledgeItemListViewModel,
    askViewModel: AskViewModel,
    knowledgeRepository: com.my.knowledge.domain.repository.KnowledgeRepository,
    allKnowledgeBases: List<com.my.knowledge.data.db.entity.KnowledgeBaseEntity>,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    onOpenIntermediate: (String) -> Unit = {}
) {
    val items by viewModel.items.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val itemCount by viewModel.itemCount.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var statusTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAskSheet by remember { mutableStateOf(false) }
    
    // Popup Menu State
    var showPopupMenu by remember { mutableStateOf(false) }
    var showMoveSelectionDialog by remember { mutableStateOf(false) }
    var popupTargetItem by remember { mutableStateOf<KnowledgeItemEntity?>(null) }

    // Resolve the current knowledge base id once so the floating "AI 问一问"
    // button can scope Ask to this whole base (rather than to any single
    // item the user happens to have hovered).
    val currentKbId = remember(items) { items.firstOrNull()?.knowledgeBaseId }
    val showFloatingAskButton = !currentKbId.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = kbName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = if (itemCount > 0) "共 $itemCount 条知识" else "暂无知识",
                        fontSize = 13.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                // Per-knowledge-base intermediate data (entities / relations
                // / communities) — drill-down into the graph of THIS base.
                val currentKbId = remember(items) { items.firstOrNull()?.knowledgeBaseId }
                if (!currentKbId.isNullOrBlank()) {
                    TextButton(
                        onClick = { onOpenIntermediate(currentKbId) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Hub,
                            contentDescription = null,
                            tint = Color(0xFF147EC5),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("本库图谱", fontSize = 12.sp, color = Color(0xFF147EC5))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectionMode && selectedIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.exportSelectedItems(selectedIds)
                                selectedIds = emptySet()
                                selectionMode = false
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFFF7FBFF), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "导出选中",
                                tint = Color(0xFF147EC5),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            selectionMode = !selectionMode
                            if (!selectionMode) selectedIds = emptySet()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFF7FBFF), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = "多选",
                            tint = if (selectionMode) Color(0xFFEA580C) else Color(0xFF147EC5),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showSearch = !showSearch },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFF7FBFF), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = Color(0xFF147EC5),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Search bar
            if (showSearch) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索知识...", color = Color(0xFFA3A3A3)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF147EC5),
                        unfocusedBorderColor = Color(0xFFDBEEFF)
                    )
                )
            }

            if (selectionMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已选择 ${selectedIds.size} 条",
                    fontSize = 12.sp,
                    color = Color(0xFF5F87A3)
                )
            }
        }

        val listState = rememberLazyListState()
        val shouldLoadMore by remember(items.size, hasMore, isLoadingMore) {
            derivedStateOf {
                hasMore && !isLoadingMore &&
                    listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                    listState.layoutInfo.visibleItemsInfo.last().index >= items.size - 2
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) viewModel.loadMore()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (items.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotEmpty()) "未找到匹配结果" else "该知识库尚无已整理知识",
                            color = Color(0xFF5F87A3),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(items) { item ->
                    KnowledgeItemRow(
                        item,
                        onDelete = {
                            deleteTarget = item
                            showDeleteDialog = true
                        },
                        onRetry = { viewModel.retryItem(item.id) },
                        onStatusClick = { statusTarget = item },
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onSelectionChange = { checked ->
                            selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                        },
                        onLongClick = {
                            popupTargetItem = item
                            showPopupMenu = true
                        },
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onOpenItem(item.id)
                            }
                        }
                    )
                }
                item(key = "list-footer") {
                    ListFooter(hasMore = hasMore, isLoadingMore = isLoadingMore)
                }
            }
        }
        
        // Popup Menu
        if (showPopupMenu && popupTargetItem != null) {
            DropdownMenu(
                expanded = showPopupMenu,
                onDismissRequest = { showPopupMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("将知识移动到知识库") },
                    onClick = {
                        showPopupMenu = false
                        showMoveSelectionDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除知识") },
                    onClick = {
                        showPopupMenu = false
                        deleteTarget = popupTargetItem
                        showDeleteDialog = true
                    }
                )
            }
        }

        // Move Selection Dialog
        if (showMoveSelectionDialog && popupTargetItem != null) {
            val currentBaseId = popupTargetItem!!.knowledgeBaseId
            AlertDialog(
                onDismissRequest = {
                    showMoveSelectionDialog = false
                    popupTargetItem = null
                },
                title = { Text("选择目标知识库", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        allKnowledgeBases.filter { it.id != currentBaseId }.forEach { base ->
                            TextButton(
                                onClick = {
                                    viewModel.moveItem(popupTargetItem!!.id, base.id)
                                    showMoveSelectionDialog = false
                                    popupTargetItem = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFFEFF7FF),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                base.iconText.ifBlank { base.name.take(1) },
                                                fontSize = 12.sp,
                                                color = Color(0xFF147EC5)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(base.name, color = Color(0xFF0F172A), fontSize = 15.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showMoveSelectionDialog = false
                        popupTargetItem = null
                    }) { Text("取消") }
                }
            )
        }

        exportStatus?.let { status ->
            Surface(
                color = Color(0xFFEFF7FF),
                shadowElevation = 2.dp
            ) {
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = Color(0xFF147EC5),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
        } // close inner Column (header + LazyColumn + exportStatus)

        // Floating "AI 问一问" button — pinned to the bottom-right of the
        // knowledge-base detail page, visible only while we know which
        // base we're inside. Tap to scope the Ask session to this whole
        // base and pop the AskSheet.
        if (showFloatingAskButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 20.dp)
            ) {
                Surface(
                    onClick = {
                        askViewModel.setScope(ScopeType.KNOWLEDGE_BASE, currentKbId)
                        askViewModel.startNewConversation(kbName)
                        showAskSheet = true
                    },
                    shape = CircleShape,
                    color = Color(0xFF111827),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(12.dp, CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "AI",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTarget = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEA580C)) },
            title = { Text("删除知识", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除「${deleteTarget!!.title}」吗？删除后可从回收站恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget?.let { viewModel.deleteItem(it.id) }
                        showDeleteDialog = false
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deleteTarget = null
                }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAskSheet) {
        AskSheet(askViewModel = askViewModel, onClose = { showAskSheet = false })
    }

    statusTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { statusTarget = null },
            title = { Text("处理状态：${item.title}", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (item.status == KnowledgeItemEntity.STATUS_FAILED) {
                        item.excerpt.ifBlank { "处理失败，暂无详细错误。" }
                    } else {
                        "当前状态：${item.status}"
                    }
                )
            },
            confirmButton = {
                if (item.status == KnowledgeItemEntity.STATUS_FAILED) {
                    Button(
                        onClick = {
                            viewModel.retryItem(item.id)
                            statusTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5))
                    ) { Text("重试") }
                }
            },
            dismissButton = {
                TextButton(onClick = { statusTarget = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun ListFooter(hasMore: Boolean, isLoadingMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF147EC5)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("加载中…", fontSize = 12.sp, color = Color(0xFFA3A3A3))
            }
            !hasMore -> Text(
                "— 已经到底了 —",
                fontSize = 12.sp,
                color = Color(0xFFA3A3A3)
            )
        }
    }
}
