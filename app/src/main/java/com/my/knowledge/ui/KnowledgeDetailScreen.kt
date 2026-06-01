package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
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
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel

@Composable
fun KnowledgeDetailScreen(
    kbName: String,
    viewModel: KnowledgeItemListViewModel,
    onBack: () -> Unit,
    onAskAI: (String, String) -> Unit,
    onOpenItem: (String) -> Unit = {}
) {
    val items by viewModel.items.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val hasNext by viewModel.hasNextPage.collectAsState()
    val hasPrevious by viewModel.hasPreviousPage.collectAsState()
    val itemCount by viewModel.itemCount.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var statusTarget by remember { mutableStateOf<KnowledgeItemEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                        onAskAI = { onAskAI(item.id, item.title) },
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
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onOpenItem(item.id)
                            }
                        }
                    )
                }
            }
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

        // Pagination controls
        if (items.isNotEmpty()) {
            Surface(
                color = Color.White,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.previousPage() },
                        enabled = hasPrevious,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (hasPrevious) Color(0xFFF7FBFF) else Color(0xFFF5F5F5),
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一页",
                            tint = if (hasPrevious) Color(0xFF147EC5) else Color(0xFFA3A3A3)
                        )
                    }

                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0F172A)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { viewModel.nextPage() },
                            enabled = hasNext,
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (hasNext) Color(0xFFF7FBFF) else Color(0xFFF5F5F5),
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下一页",
                                tint = if (hasNext) Color(0xFF147EC5) else Color(0xFFA3A3A3)
                            )
                        }
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
