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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.viewmodel.RecycleBinViewModel

@Composable
fun RecycleBinScreen(
    viewModel: RecycleBinViewModel,
    onBack: () -> Unit
) {
    val items by viewModel.items.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val selectionCount by viewModel.selectionCount.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val hasNext by viewModel.hasNextPage.collectAsState()
    val hasPrevious by viewModel.hasPreviousPage.collectAsState()
    val totalCount by viewModel.deletedItemCount.collectAsState()

    var showBatchDeleteDialog by remember { mutableStateOf(false) }

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
                    Text("回收站", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Text("共 $totalCount 条已删除", fontSize = 13.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 4.dp))
                }
                if (items.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedIds.size == items.size && items.isNotEmpty(),
                            onCheckedChange = { checked ->
                                if (checked) viewModel.selectAll(items.map { it.id })
                                else viewModel.clearSelection()
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF147EC5))
                        )
                        Text("全选", fontSize = 13.sp, color = Color(0xFF5F87A3))
                    }
                }
            }
        }

        // Batch action bar
        if (selectionCount > 0) {
            Surface(
                color = Color(0xFFEFF7FF),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已选 $selectionCount 项", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0F172A))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.restoreSelected() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复所选", fontSize = 13.sp, color = Color.White)
                        }
                        Button(
                            onClick = { showBatchDeleteDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除所选", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Content
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(64.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("回收站为空", color = Color(0xFFA3A3A3), fontSize = 15.sp) }
                }
            } else {
                item { Spacer(modifier = Modifier.height(12.dp)) }
                items(items) { item ->
                    val isSelected = item.id in selectedIds
                    Card(
                        onClick = { viewModel.toggleSelection(item.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 5.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFFEFF7FF) else Color.White
                        ),
                        border = if (isSelected) CardDefaults.outlinedCardBorder().copy(
                            width = 1.5.dp,
                            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF147EC5))
                        ) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleSelection(item.id) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF147EC5))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    item.excerpt,
                                    fontSize = 13.sp,
                                    color = Color(0xFF737373),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    "删除时间: ${formatTimestamp(item.deletedAt ?: 0)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFA3A3A3),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.restoreItem(item.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = "恢复", tint = Color(0xFF147EC5), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Pagination
        if (items.isNotEmpty()) {
            Surface(color = Color.White, shadowElevation = 4.dp) {
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
                        "${currentPage + 1} / $totalPages",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0F172A)
                    )
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

    // Batch permanent delete confirmation dialog
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFDC2626)) },
            title = { Text("永久删除", fontWeight = FontWeight.Bold) },
            text = { Text("确定要永久删除所选的 $selectionCount 条知识条目吗？此操作不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentDeleteSelected()
                        showBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("永久删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
