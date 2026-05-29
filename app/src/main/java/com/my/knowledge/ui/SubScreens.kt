package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.my.knowledge.ui.component.InsightRow
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.ui.component.StatCard

@Composable
fun KnowledgeContextScreen(onBack: () -> Unit) {
    val insights = KnowledgeManager.insights

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF)), // Ocean 25
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            PageHeader(
                title = "知识脉络",
                hint = "自动化分析后的逻辑演进",
                back = {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF147EC5))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
                    }
                },
                action = {
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
                        shadowElevation = 1.dp
                    ) {
                        Text("整理", fontSize = 12.sp, color = Color(0xFF147EC5), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            )
        }

        if (insights.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("暂无提炼的脉络，请先导入文件", color = Color(0xFF5F87A3), fontSize = 14.sp)
                }
            }
        } else {
            items(insights.size) { index ->
                val insight = insights[index]
                if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                InsightRow(insight)
            }
        }
    }
}

@Composable
fun FragmentOrganizeScreen(onBack: () -> Unit) {
    var filter by remember { mutableStateOf("全部") }
    val filters = listOf("全部", "待归类", "可提炼", "可归档")
    val fragments = KnowledgeManager.fragments

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF)), // Ocean 25
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            PageHeader(
                title = "碎片整理",
                hint = "把零散记录进一步归纳整理",
                back = {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF147EC5))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 14.sp, color = Color(0xFF147EC5))
                    }
                },
                action = {
                    Surface(
                        onClick = {},
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
                        shadowElevation = 1.dp
                    ) {
                        Text("筛选", fontSize = 12.sp, color = Color(0xFF147EC5), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(Modifier.weight(1f), fragments.size.toString(), "待整理")
                    StatCard(Modifier.weight(1f), "0", "已推荐")
                    StatCard(Modifier.weight(1f), "0", "可归档")
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { item ->
                        val selected = filter == item
                        Surface(
                            onClick = { filter = item },
                            shape = CircleShape,
                            color = if (selected) Color(0xFF147EC5) else Color.White,
                            border = if (selected) null else BorderStroke(1.dp, Color(0xFFDBEEFF)),
                        ) {
                            Text(
                                text = item,
                                fontSize = 12.sp,
                                color = if (selected) Color.White else Color(0xFF5F87A3),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (fragments.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("暂无碎片，请先通过灵感或文件导入", color = Color(0xFF5F87A3), fontSize = 14.sp)
                }
            }
        } else {
            item {
                Section(title = "推荐整理") {
                    fragments.forEachIndexed { index, fragment ->
                        if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                        QuietCell(
                            icon = when {
                                fragment.sourceFile.endsWith(".md") -> Icons.Default.Description
                                else -> Icons.Default.Link
                            },
                            title = fragment.title,
                            desc = "来源: ${fragment.sourceFile}",
                            right = { MiniTag(fragment.tags.firstOrNull() ?: "待分类") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, value: String, label: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(label, fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 2.dp))
        }
    }
}
