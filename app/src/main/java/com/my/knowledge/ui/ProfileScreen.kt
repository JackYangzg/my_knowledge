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

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onOpenSettings: () -> Unit,
    onOpenLogCenter: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    onOpenIntermediateData: () -> Unit = {}
) {
    val originalFiles = KnowledgeManager.originalFiles
    val unfiledWorkCount by viewModel.unfiledWorkCount.collectAsState()
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
                onClick = onOpenIntermediateData,
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
                        Text("个人知识空间", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
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
            Section(title = "知识加工数据", more = "查看详情", onMoreClick = onOpenIntermediateData) {
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
                            onClick = onOpenIntermediateData
                        )
                    }
                }
            }
        }

        item {
            Section(title = "原始文件导入", more = "查看文件夹") {
                originalFiles.forEachIndexed { index, file ->
                    if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                    QuietCell(
                        icon = when (file.source) {
                            "语音" -> Icons.Default.Mic
                            "网页" -> Icons.Default.Link
                            "PDF" -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.Description
                        },
                        title = file.title,
                        desc = "${file.source} · ${file.time} · ${file.size}",
                        right = {
                            Text(
                                file.status,
                                fontSize = 12.sp,
                                color = if (file.status == "分析中...") Color(0xFF147EC5) else Color(0xFF6AA8D0)
                            )
                        }
                    )
                }
            }
        }

        item {
            Section(title = "管理") {
                val items = listOf(
                    Triple(Icons.Default.ListAlt, "日志中心", "${unfiledWorkCount} 条未归档，${pendingRecommendationCount} 条待确认"),
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
