package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.viewmodel.ProfileViewModel
import androidx.compose.ui.res.stringResource
import com.my.knowledge.R

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onOpenSettings: () -> Unit,
    onOpenLogCenter: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    onOpenIntermediateData: (String?) -> Unit = {}
) {
    // "原始文件导入" section 已被移除——同名信息在「日志中心」页面（LogSourceCard）
    // 已经能完整展示,这里只保留「知识加工数据」「管理」等更高层的摘要。
    val unfiledWorkCount by viewModel.unfiledWorkCount.collectAsState()
    val processingTaskCount by viewModel.processingTaskCount.collectAsState()
    val failedTaskCount by viewModel.failedTaskCount.collectAsState()
    val pendingRecommendationCount by viewModel.pendingRecommendationCount.collectAsState()
    val profileStats by viewModel.profileStats.collectAsState()
    val processingSummaries by viewModel.processingSummaries.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF)), // Ocean 25
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            PageHeader(title = "我")
        }

        item {
            Card(
                onClick = {
                    onOpenIntermediateData(processingSummaries.firstOrNull()?.knowledgeBaseId)
                },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0xFF0F172A), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_805d5a4a), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Text(
                            "${profileStats.knowledgeItemCount} 条知识，${profileStats.knowledgeBaseCount} 个知识库",
                            fontSize = 14.sp,
                            color = Color(0xFF5F87A3),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "${profileStats.entityCount} 个实体，${profileStats.conceptCount} 个概念",
                            fontSize = 12.sp,
                            color = Color(0xFFA3A3A3),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        item {
            Section(
                title = "知识加工数据",
                more = "查看详情",
                onMoreClick = {
                    onOpenIntermediateData(processingSummaries.firstOrNull()?.knowledgeBaseId)
                }
            ) {
                val visibleSummaries = processingSummaries.take(6)
                if (visibleSummaries.isEmpty()) {
                    QuietCell(
                        icon = Icons.Default.Hub,
                        title = "暂无加工数据",
                        desc = "导入并完成知识加工后，会按知识库展示实体、概念和关系"
                    )
                } else {
                    visibleSummaries.forEachIndexed { index, summary ->
                        if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                        QuietCell(
                            icon = Icons.Default.Hub,
                            title = summary.knowledgeBaseName,
                            desc = "${summary.itemCount} 条知识 · ${summary.entityCount} 个实体 · ${summary.conceptCount} 个概念 · ${summary.relationCount} 条关系 · ${summary.communityCount} 个主题群",
                            right = {
                                val topText = summary.topTerms.take(2).joinToString("、")
                                if (topText.isNotBlank()) {
                                    Text(topText, fontSize = 11.sp, color = Color(0xFF6AA8D0), maxLines = 1)
                                }
                            },
                            onClick = { onOpenIntermediateData(summary.knowledgeBaseId) }
                        )
                    }
                }
            }
        }

        item {
            Section(title = "管理") {
                val items = listOf(
                    // 日志中心描述要跟日志中心自身的 summary 卡片对齐:
                    //   - 处理中: pending + running + pending_network 任务
                    //   - 失败:   failed 任务
                    //   - 待确认: pending review items (含 archive rec)
                    // 旧的"X 条未归档"太单薄,且只覆盖了 unfiled 状态,漏掉了
                    // 失败和待确认——用户切到日志中心能看到三张卡,回到这里却
                    // 只能看到一个数字,体感割裂。
                    Triple(
                        Icons.Default.ListAlt,
                        "日志中心",
                        "${processingTaskCount} 条处理中，${failedTaskCount} 条失败，${pendingRecommendationCount} 条待确认"
                    ),
                    Triple(Icons.Default.Delete, "回收站", "已删除的知识条目，可恢复"),
                    Triple(Icons.Default.Settings, "设置", "同步、模型、默认知识库")
                )
                items.forEachIndexed { index, (icon, title, desc) ->
                    if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                    QuietCell(
                        icon = icon,
                        title = title,
                        desc = desc,
                        onClick = {
                            when (title) {
                                "设置" -> onOpenSettings()
                                "日志中心" -> onOpenLogCenter()
                                "回收站" -> onOpenRecycleBin()
                            }
                        }
                    )
                }
            }
        }
    }
}
