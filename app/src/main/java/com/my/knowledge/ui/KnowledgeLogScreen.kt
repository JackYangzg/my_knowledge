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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
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
    val bases by importViewModel.knowledgeBases.collectAsState()
    val reviews by processingViewModel.pendingReviews.collectAsState()
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
                    LogSummaryCard(reviews.size, "待确认", Color(0xFFEA580C), Modifier.weight(1f))
                }
            }

            if (rows.isEmpty() && reviews.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("暂无日志", color = Color(0xFF5F87A3), fontSize = 14.sp)
                    }
                }
            }

            items(rows) { row ->
                LogSourceCard(
                    row = row,
                    latestLog = processingViewModel.observeLogs("source_document", row.source.id)
                        .collectAsState(initial = emptyList()).value.firstOrNull(),
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
        val recentLogs by processingViewModel.observeLogs("source_document", row.source.id)
            .collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { detailRow = null },
            title = { Text(row.source.title, fontWeight = FontWeight.Bold) },
            text = {
                // 给整段加 verticalScroll：来源在长任务链下可能堆积几十条
                // 内部日志（parse → analysis → generation → embedding），原来
                // 用 .take(6) 截断,用户根本看不到后续阶段的失败原因。改成
                // 可滚动列表后,所有日志行都可见,且不影响 AlertDialog 自带
                // 的高度上限(Material 3 会按内容收缩 dialog 高度,我们再手动
                // 给整体一个 360dp 的内层高度上限,保证移动端不被压扁)。
                val logScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(logScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("来源类型：${row.source.sourceType}")
                    Text("MIME：${row.source.mimeType ?: "unknown"}")
                    Text("导入状态：${row.source.status}")
                    Text("目标知识库：${row.source.targetKnowledgeBaseId?.let { id -> bases.firstOrNull { it.id == id }?.name } ?: "未归档"}")
                    row.latestTask?.let { task ->
                        HorizontalDivider()
                        Text("任务：${task.taskType}")
                        Text("步骤：${task.currentStep ?: task.status}")
                        Text("进度：${task.progress}%")
                        Text("重试：${task.retryCount}/${task.maxRetry}")
                        if (!task.errorMessage.isNullOrBlank()) Text("错误：${task.errorMessage}", color = Color(0xFFDC2626))
                    }
                    if (recentLogs.isNotEmpty()) {
                        HorizontalDivider()
                        // 用行数+首末时间标注一行小标题,方便用户一眼看到
                        // 这段是不是已截断——以后只要日志变长,标题里会带着
                        // 真实的条数,而不是"最近 6 条"。
                        val first = recentLogs.lastOrNull()
                        val last = recentLogs.firstOrNull()
                        Text(
                            buildString {
                                append("最近内部日志（")
                                append(recentLogs.size)
                                append(" 条")
                                if (first != null && last != null && first.id != last.id) {
                                    append(" · ")
                                    append(formatLogTime(first.createdAt))
                                    append(" ~ ")
                                    append(formatLogTime(last.createdAt))
                                }
                                append("）")
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        recentLogs.forEach { log ->
                            Text(
                                "[${formatLogTime(log.createdAt)}][${log.stage}] ${log.message}",
                                fontSize = 11.sp,
                                color = when (log.status) {
                                    "success" -> Color(0xFF16A34A)
                                    "pending_config", "failed" -> Color(0xFFDC2626)
                                    else -> Color(0xFF5F87A3)
                                }
                            )
                        }
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
    latestLog: ProcessingTaskLogEntity?,
    onDetail: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val task = row.latestTask
    // "重新发起 分析" used to be hidden until the task was failed / pending_config
    // because the old retry path only handled those cases. Now that
    // `retrySource` does a full re-ingest (it clears the source's parse,
    // analysis and wiki pages, then enqueues a fresh parse), the button
    // is safe to expose for any state — including `success` and `pending`.
    val canRetry = task != null && task.status !in setOf("running")
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
                PipelineStepper(row.allTasks)
                Spacer(modifier = Modifier.height(8.dp))
                // The "description" the user sees in the log center now merges
                // the task's high-level step (e.g. "解析文件…") with the most
                // recent ProcessingTaskLog message (the in-flight log line
                // the orchestrator pushes via `appendLog`). The log entry
                // is fresher than the step on the task row, so we prefer it
                // when present.
                val latestMessage = latestLog?.message?.takeIf { it.isNotBlank() }
                val latestStage = latestLog?.stage?.takeIf { it.isNotBlank() }
                val displayStep: String = latestMessage
                    ?: task.currentStep
                    ?: task.status
                val logStage: String? = latestStage
                Text(
                    buildString {
                        append(task.taskType)
                        if (!logStage.isNullOrBlank() && logStage != task.taskType) {
                            append(" · ")
                            append(logStage)
                        }
                        append(" · ")
                        append(displayStep)
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF5F87A3)
                )
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = logStatusColor(task.status), trackColor = Color(0xFFEFF7FF))
                if (!task.errorMessage.isNullOrBlank()) {
                    Text(task.errorMessage, fontSize = 12.sp, color = Color(0xFFDC2626), modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (canRetry) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "重新发起分析", tint = Color(0xFF147EC5)) }
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
private fun PipelineStepper(tasks: List<ProcessingTaskEntity>) {
    val steps = listOf(
        "parse" to "解析",
        "analysis" to "分析",
        "generation" to "生成",
        "embedding" to "入库"
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (type, label) ->
            val task = tasks.find { it.taskType == type }
            val status = task?.status ?: "upcoming"
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(getStepColor(status))
                )
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = if (status == "running") FontWeight.Bold else FontWeight.Normal,
                    color = getStepColor(status),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            if (index < steps.size - 1) {
                val nextTask = tasks.find { it.taskType == steps[index+1].first }
                val connectorColor = if (status == "success" && nextTask != null) {
                    getStepColor(nextTask.status)
                } else if (status == "success") {
                    Color(0xFFE5E7EB)
                } else {
                    Color(0xFFE5E7EB)
                }
                
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .weight(0.5f)
                        .background(connectorColor)
                )
            }
        }
    }
}

private fun getStepColor(status: String): Color = when (status) {
    "success", "generated" -> Color(0xFF0B816F)
    "running", "parsing", "analyzing" -> Color(0xFF147EC5)
    "failed" -> Color(0xFFDC2626)
    "pending", "imported", "parsed", "pending_config" -> Color(0xFFF59E0B)
    "canceled" -> Color(0xFFEA580C)
    else -> Color(0xFFD1D5DB) // upcoming / gray
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
