package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.ui.component.SummaryCard
import com.my.knowledge.viewmodel.KnowledgeHomeViewModel
import com.my.knowledge.viewmodel.KnowledgeManageViewModel

@Composable
fun KnowledgeManageScreen(
    homeViewModel: KnowledgeHomeViewModel,
    manageViewModel: KnowledgeManageViewModel,
    onBack: () -> Unit,
    onOpenKbDetail: (String) -> Unit
) {
    val knowledgeBases by homeViewModel.knowledgeBases.collectAsState()

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
                        text = "知识库管理",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "查看和管理全部知识库",
                        fontSize = 13.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                IconButton(
                    onClick = { 
                        manageViewModel.createKnowledgeBase("新知识库", "新建于 ${System.currentTimeMillis()}")
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFF7FBFF), CircleShape)
                        .border(1.dp, Color(0xFFDBEEFF), CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF147EC5))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(Modifier.weight(1f), knowledgeBases.size.toString(), "知识库")
                    SummaryCard(Modifier.weight(1f), knowledgeBases.sumOf { it.itemCount }.toString(), "知识总数")
                    SummaryCard(Modifier.weight(1f), knowledgeBases.find { it.type == "unfiled" }?.itemCount?.toString() ?: "0", "未归档")
                }
            }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            manageViewModel.createKnowledgeBase("工作笔记", "用于存放所有与工作相关的内容")
                        },
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFF4BB8FF), Color(0xFF188BD7))),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("新建知识库", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFCBE8FF))
                    ) {
                        Text("排序", color = Color(0xFF147EC5), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Section(title = "全部知识库", more = "编辑") {
                    knowledgeBases.forEachIndexed { index, item ->
                        if (index != 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color(0xFFDBEEFF))
                        QuietCell(
                            title = item.name,
                            desc = if (item.type == "unfiled") "系统默认知识库，暂存还没决定去向的内容" else item.description,
                            onClick = { onOpenKbDetail(item.id) },
                            leftContent = {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFEFF7FF))
                                        .border(1.dp, Color(0xFFCBE8FF), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = item.iconText,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF147EC5)
                                    )
                                }
                            },
                            right = {
                                Column(modifier = Modifier.padding(top = 6.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MiniTag("${item.itemCount} 条知识")
                                        if (item.isSystem) MiniTag("系统")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
