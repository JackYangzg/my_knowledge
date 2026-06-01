package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.viewmodel.ImportCenterRow
import com.my.knowledge.viewmodel.ImportCenterViewModel
import com.my.knowledge.viewmodel.ProcessingStatusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KnowledgeLogScreen(
    importViewModel: ImportCenterViewModel,
    processingViewModel: ProcessingStatusViewModel,
    onBack: () -> Unit
) {
    val rows by importViewModel.rows.collectAsState()
    val reviews by processingViewModel.pendingReviews.collectAsState()
    val recommendations by processingViewModel.pendingRecommendations.collectAsState()
    val recommendationTitles by processingViewModel.recommendationItemTitles.collectAsState()
    var deleteSourceId by remember { mutableStateOf<String?>(null) }
    var detailRow by remember { mutableStateOf<ImportCenterRow?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF7FBFF))) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp).padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF147EC5))
                Spacer(modifier = Modifier.size(4.dp))
                Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("日志中心", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text("导入、加工、未归档和人工复核状态", fontSize = 13.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    LogSummaryCard(rows.count { it.latestTask?.status == "pending" || it.latestTask?.status == "running" }, "处理中", Color(0xFF147EC5), Modifier.weight(1f))
                    LogSummaryCard(rows.count { it.latestTask?.status == "failed" || it.source.status == SourceDocumentEntity.STATUS_FAILED }, "失败", Color(0xFFDC2626), Modifier.weight(1f))
                    LogSummaryCard(reviews.size + recommendations.size, "待确认", Color(0xFFEA580C), Modifier.weight(1f))
                }
            }

            if (rows.isEmpty() && reviews.isEmpty() && recommendations.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("暂无日志", color = Color(0xFF5F87A3), fontSize = 14.sp)
                    }
                }
            }

            items(rows) { row ->
                LogSourceCard(
                    row = row,
                    onDetail = { detailRow = row },
                    onRetry = { importViewModel.retrySource(row.source.id) },
                    onCancel = { row.latestTask?.let { importViewModel.cancelTask(it.id) } },
                    onDelete = { deleteSourceId = row.source.id }
                )
            }

            items(reviews) { review ->
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RateReview, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(review.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                Text("文件/来源：${review.sourceId ?: "未知"}", fontSize = 11.sp, color = Color(0xFF5F87A3))
                            }
                        }
                        Text(review.description, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { processingViewModel.skipReview(review.id) }) { Text("稍后", fontSize = 13.sp, color = Color(0xFFA3A3A3)) }
                            Button(onClick = { processingViewModel.acceptReview(review.id) }, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5))) {
                                Text("确认", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            items(recommendations) { rec ->
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("归档推荐", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                        Text("知识：${recommendationTitles[rec.itemId] ?: "未知知识"} · 建议目标：${rec.recommendedKnowledgeBaseName ?: rec.recommendedKnowledgeBaseId ?: "未指定"}", fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 4.dp))
                        Text(rec.reason, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { processingViewModel.rejectRecommendation(rec.id) }) { Text("拒绝", fontSize = 13.sp, color = Color(0xFFA3A3A3)) }
                            Button(onClick = { processingViewModel.acceptRecommendation(rec.id) }, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5))) {
                                Text("接受", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteSourceId != null) {
        AlertDialog(
            onDismissRequest = { deleteSourceId = null },
            title = { Text("删除来源") },
            text = { Text("会取消相关任务、清理解析结果和本地来源文件，并将关联知识移入回收站。") },
            confirmButton = {
                Button(onClick = {
                    deleteSourceId?.let(importViewModel::deleteSource)
                    deleteSourceId = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteSourceId = null }) { Text("取消") } }
        )
    }

    detailRow?.let { row ->
        AlertDialog(
            onDismissRequest = { detailRow = null },
            title = { Text(row.source.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("来源类型：${row.source.sourceType}")
                    Text("MIME：${row.source.mimeType ?: "unknown"}")
                    Text("导入状态：${row.source.status}")
                    Text("目标知识库：${row.source.targetKnowledgeBaseId ?: "未归档"}")
                    row.latestTask?.let { task ->
                        HorizontalDivider()
                        Text("任务：${task.taskType}")
                        Text("步骤：${task.currentStep ?: task.status}")
                        Text("进度：${task.progress}%")
                        Text("重试：${task.retryCount}/${task.maxRetry}")
                        if (!task.errorMessage.isNullOrBlank()) Text("错误：${task.errorMessage}", color = Color(0xFFDC2626))
                    }
                    if (!row.source.errorMessage.isNullOrBlank()) Text("来源错误：${row.source.errorMessage}", color = Color(0xFFDC2626))
                }
            },
            confirmButton = { TextButton(onClick = { detailRow = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun LogSummaryCard(count: Int, label: String, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LogSourceCard(
    row: ImportCenterRow,
    onDetail: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val task = row.latestTask
    Surface(onClick = onDetail, shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(row.source), contentDescription = null, tint = logStatusColor(task?.status ?: row.source.status), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.source.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text("${row.source.sourceType} · ${row.source.mimeType ?: "unknown"} · ${formatLogTime(row.source.updatedAt)}", fontSize = 11.sp, color = Color(0xFF5F87A3))
                }
                LogStatusPill(task?.status ?: row.source.status)
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (task != null) {
                Text("${task.taskType} · ${task.currentStep ?: task.status}", fontSize = 12.sp, color = Color(0xFF5F87A3))
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = logStatusColor(task.status), trackColor = Color(0xFFEFF7FF))
                if (!task.errorMessage.isNullOrBlank()) {
                    Text(task.errorMessage, fontSize = 12.sp, color = Color(0xFFDC2626), modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (task?.status == "failed" || task?.status == "canceled" || task?.status == "pending_config") {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "重试", tint = Color(0xFF147EC5)) }
                }
                if (task?.status == "pending" || task?.status == "running") {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, contentDescription = "取消", tint = Color(0xFFEA580C)) }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除来源", tint = Color(0xFFDC2626)) }
            }
        }
    }
}

@Composable
private fun LogStatusPill(status: String) {
    Surface(color = logStatusColor(status).copy(alpha = 0.12f), shape = CircleShape) {
        Text(logStatusLabel(status), fontSize = 11.sp, color = logStatusColor(status), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private fun sourceIcon(source: SourceDocumentEntity): ImageVector = when (source.sourceType) {
    "image" -> Icons.Default.Image
    "audio" -> Icons.Default.Mic
    "pdf" -> Icons.Default.PictureAsPdf
    "docx", "file" -> Icons.Default.InsertDriveFile
    else -> Icons.Default.Description
}

private fun logStatusColor(status: String): Color = when (status) {
    "running", "parsing", "analyzing" -> Color(0xFF147EC5)
    "pending", "imported", "parsed", "pending_config" -> Color(0xFFF59E0B)
    "failed" -> Color(0xFFDC2626)
    "canceled" -> Color(0xFFEA580C)
    "generated", "success" -> Color(0xFF0B816F)
    else -> Color(0xFF5F87A3)
}

private fun logStatusLabel(status: String): String = when (status) {
    "pending_config" -> "待配置"
    "pending" -> "待处理"
    "running" -> "处理中"
    "success" -> "成功"
    "failed" -> "失败"
    "canceled" -> "已取消"
    "generated" -> "已生成"
    else -> status
}

private fun formatLogTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
