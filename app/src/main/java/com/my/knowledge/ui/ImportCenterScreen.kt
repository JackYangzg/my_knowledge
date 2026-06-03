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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImportCenterScreen(
    viewModel: ImportCenterViewModel,
    onBack: () -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    var deleteSourceId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF147EC5))
                Spacer(modifier = Modifier.size(4.dp))
                Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("导入中心", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text("查看来源、进度、失败任务和本地文件状态", fontSize = 13.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rows.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("暂无导入来源", color = Color(0xFF5F87A3), fontSize = 14.sp)
                    }
                }
            } else {
                items(rows) { row ->
                    ImportSourceCard(
                        row = row,
                        onRetry = { viewModel.retrySource(row.source.id) },
                        onCancel = { row.latestTask?.let { viewModel.cancelTask(it.id) } },
                        onDelete = { deleteSourceId = row.source.id }
                    )
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
                Button(
                    onClick = {
                        deleteSourceId?.let(viewModel::deleteSource)
                        deleteSourceId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSourceId = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ImportSourceCard(
    row: ImportCenterRow,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val task = row.latestTask
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(row.source), contentDescription = null, tint = statusColor(row.source.status), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.source.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text("${row.source.sourceType} · ${row.source.mimeType ?: "unknown"} · ${formatTime(row.source.updatedAt)}", fontSize = 11.sp, color = Color(0xFF5F87A3))
                }
                StatusPill(row.source.status)
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (task != null) {
                Text("${task.taskType} · ${task.currentStep ?: task.status}", fontSize = 12.sp, color = Color(0xFF5F87A3))
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = statusColor(task.status),
                    trackColor = Color(0xFFEFF7FF)
                )
            } else {
                Text("尚未创建处理任务", fontSize = 12.sp, color = Color(0xFFA3A3A3))
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (task?.status == "failed" || task?.status == "canceled") {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "重试", tint = Color(0xFF147EC5))
                    }
                }
                if (task?.status == "pending" || task?.status == "running") {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = "取消", tint = Color(0xFFEA580C))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除来源", tint = Color(0xFFDC2626))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    Surface(color = statusColor(status).copy(alpha = 0.12f), shape = CircleShape) {
        Text(status, fontSize = 11.sp, color = statusColor(status), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

private fun sourceIcon(source: SourceDocumentEntity): ImageVector = when (source.sourceType) {
    "image" -> Icons.Default.Image
    "audio" -> Icons.Default.Mic
    "pdf" -> Icons.Default.PictureAsPdf
    "docx", "file" -> Icons.Default.InsertDriveFile
    else -> Icons.Default.Description
}

private fun statusColor(status: String): Color = when (status) {
    "running", "parsing", "analyzing" -> Color(0xFF147EC5)
    "pending", "imported", "parsed", "pending_network" -> Color(0xFFF59E0B)
    "failed" -> Color(0xFFDC2626)
    "canceled" -> Color(0xFFEA580C)
    "generated", "success" -> Color(0xFF0B816F)
    else -> Color(0xFF5F87A3)
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
