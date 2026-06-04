package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.viewmodel.ProcessingStatusViewModel

@Composable
fun ProcessingStatusScreen(
    viewModel: ProcessingStatusViewModel,
    onBack: () -> Unit
) {
    val activeTasks by viewModel.activeTasks.collectAsState()
    val pendingRecommendations by viewModel.pendingRecommendations.collectAsState()
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val recommendationTitles by viewModel.recommendationItemTitles.collectAsState()

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
                Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "加工状态",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Summary cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusSummaryCard(
                        modifier = Modifier.weight(1f),
                        count = activeTasks.size,
                        label = "运行中",
                        color = Color(0xFF147EC5),
                        icon = Icons.Default.Sync
                    )
                    StatusSummaryCard(
                        modifier = Modifier.weight(1f),
                        count = pendingRecommendations.size + pendingReviews.size,
                        label = "待人工确认",
                        color = Color(0xFFEA580C),
                        icon = Icons.Default.Recommend
                    )
                }
            }

            // Active tasks section
            item {
                SectionHeader("处理中的任务", Icons.Default.Sync)
            }

            if (activeTasks.isEmpty()) {
                item {
                    EmptyHint("暂无运行中的任务")
                }
            } else {
                items(activeTasks) { task ->
                    TaskCard(
                        task = task,
                        onRetry = { viewModel.retryTask(task.id) },
                        onCancel = { viewModel.cancelTask(task.id) },
                        viewModel = viewModel
                    )
                }
            }

            // Pending recommendations section
            item {
                SectionHeader("Review Queue", Icons.Default.RateReview)
            }

            if (pendingReviews.isEmpty()) {
                item {
                    EmptyHint("暂无需要人工确认的事项")
                }
            } else {
                items(pendingReviews) { review ->
                    ReviewCard(
                        review = review,
                        onAccept = { viewModel.acceptReview(review.id) },
                        onSkip = { viewModel.skipReview(review.id) }
                    )
                }
            }

            item {
                SectionHeader("归档推荐", Icons.Default.Recommend)
            }

            if (pendingRecommendations.isEmpty()) {
                item {
                    EmptyHint("暂无待处理的归档推荐")
                }
            } else {
                items(pendingRecommendations) { rec ->
                    RecommendationCard(
                        recommendation = rec,
                        itemTitle = recommendationTitles[rec.itemId] ?: "未知知识",
                        onAccept = { viewModel.acceptRecommendation(rec.id) },
                        onReject = { viewModel.rejectRecommendation(rec.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ReviewCard(
    review: ReviewItemEntity,
    onAccept: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RateReview, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(review.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text(review.type, fontSize = 11.sp, color = Color(0xFFEA580C), modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(review.description, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF5F87A3))
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) {
                    Text("稍后", fontSize = 13.sp, color = Color(0xFFA3A3A3))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAccept,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("确认", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    modifier: Modifier,
    count: Int,
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Text(label, fontSize = 12.sp, color = Color(0xFF5F87A3))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF5F87A3))
        Spacer(modifier = Modifier.width(6.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 14.sp, color = Color(0xFFA3A3A3))
        }
    }
}

@Composable
private fun TaskCard(
    task: ProcessingTaskEntity,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ProcessingStatusViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val logs by if (expanded) {
        viewModel.observeLogs(task.targetType, task.targetId).collectAsState(emptyList())
    } else {
        remember { mutableStateOf(emptyList<com.my.knowledge.data.db.entity.ProcessingTaskLogEntity>()) }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(task.status)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            task.taskType,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            "目标: ${task.targetType}/${task.targetId.take(8)}...",
                            fontSize = 12.sp,
                            color = Color(0xFF5F87A3)
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFFA3A3A3),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (task.status == "running" || task.progress > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Color(0xFF147EC5),
                    trackColor = Color(0xFFDBEEFF)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(task.currentStep ?: "执行中", fontSize = 11.sp, color = Color(0xFF5F87A3))
                    Text("${task.progress}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF147EC5))
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("加工详情", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                Spacer(modifier = Modifier.height(8.dp))
                
                // Pipeline Stages
                val stages = listOf(
                    "summary" to "内容摘要",
                    "tag" to "自动打标",
                    "archive_recommend" to "归档建议"
                )
                
                stages.forEach { (type, label) ->
                    val stageLog = logs.find { it.stage == type }
                    StageRow(
                        label = label,
                        status = stageLog?.status ?: "pending",
                        message = stageLog?.message
                    )
                }

                if (logs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("最近日志", fontSize = 12.sp, color = Color(0xFF5F87A3))
                    logs.take(3).forEach { log ->
                        Text(
                            "• [${log.status}] ${log.message}",
                            fontSize = 11.sp,
                            color = Color(0xFF737373),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            if (task.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        task.errorMessage,
                        fontSize = 12.sp,
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "重试: ${task.retryCount}/${task.maxRetry}",
                    fontSize = 11.sp,
                    color = Color(0xFFA3A3A3)
                )

                if (task.status == "failed") {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF147EC5)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重试", fontSize = 12.sp, color = Color(0xFF147EC5))
                    }
                }
                if (task.status == "pending" || task.status == "running") {
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFDC2626)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("取消", fontSize = 12.sp, color = Color(0xFFDC2626))
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRow(label: String, status: String, message: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, color) = when (status) {
            "success" -> Icons.Default.CheckCircle to Color(0xFF16A34A)
            "running" -> Icons.Default.Sync to Color(0xFF147EC5)
            "pending_network" -> Icons.Default.Sync to Color(0xFFF59E0B)
            "failed" -> Icons.Default.Error to Color(0xFFDC2626)
            else -> Icons.Default.RadioButtonUnchecked to Color(0xFFD4D4D4)
        }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 12.sp, color = if (status == "pending") Color(0xFFA3A3A3) else Color(0xFF0F172A))
            if (!message.isNullOrBlank()) {
                Text(displayTaskMessage(message), fontSize = 11.sp, color = Color(0xFF5F87A3))
            }
        }
    }
}

private fun displayTaskMessage(message: String): String =
    message
        .replace("网络波动，已暂停", "远端调用失败，已进入重试")
        .replace("联网后继续", "稍后继续")

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "running" -> Color(0xFF147EC5)
        "pending", "pending_network" -> Color(0xFFF59E0B)
        "failed" -> Color(0xFFDC2626)
        else -> Color(0xFFA3A3A3)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun RecommendationCard(
    recommendation: ArchiveRecommendationEntity,
    itemTitle: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "知识：$itemTitle",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        "推荐归档到: ${recommendation.recommendedKnowledgeBaseName ?: recommendation.recommendedKnowledgeBaseId ?: "未指定"}",
                        fontSize = 12.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        recommendation.reason,
                        fontSize = 12.sp,
                        color = Color(0xFF5F87A3)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "置信度: ",
                            fontSize = 11.sp,
                            color = Color(0xFFA3A3A3)
                        )
                        val confColor = when {
                            recommendation.confidence >= 0.7f -> Color(0xFF16A34A)
                            recommendation.confidence >= 0.4f -> Color(0xFFF59E0B)
                            else -> Color(0xFFDC2626)
                        }
                        Text(
                            "${(recommendation.confidence * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = confColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onReject) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFA3A3A3)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("拒绝", fontSize = 13.sp, color = Color(0xFFA3A3A3))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onAccept,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("确认归档", fontSize = 13.sp)
                }
            }
        }
    }
}
