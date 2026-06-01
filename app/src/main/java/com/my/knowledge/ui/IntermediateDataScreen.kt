package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.viewmodel.IntermediateDataViewModel

@Composable
fun IntermediateDataScreen(
    viewModel: IntermediateDataViewModel,
    onBack: () -> Unit
) {
    val entities by viewModel.entities.collectAsState()
    val relations by viewModel.relations.collectAsState()
    val communities by viewModel.communities.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("实体与概念", "关联关系", "主题群")

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
                text = "中间处理数据",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = Color(0xFF147EC5),
            divider = { HorizontalDivider(color = Color(0xFFF3F4F6)) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 14.sp) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> EntityList(entities)
                1 -> RelationList(relations)
                2 -> CommunityList(communities)
            }
        }
    }
}

@Composable
private fun EntityList(entities: List<KnowledgeEntityEntity>) {
    if (entities.isEmpty()) {
        EmptyState("暂无提取的实体或概念")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entities) { entity ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 0.5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFF0F9FF), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (entity.type == "concept") Icons.Default.Category else Icons.Default.BubbleChart,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entity.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text(
                                "${entity.type} · 权重: ${entity.weight.toInt()}",
                                fontSize = 12.sp,
                                color = Color(0xFF5F87A3)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationList(relations: List<KnowledgeRelationEntity>) {
    if (relations.isEmpty()) {
        EmptyState("暂无识别的关联关系")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(relations) { relation ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 0.5.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default. Hub, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(relation.relationType, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1))
                            Spacer(modifier = Modifier.weight(1f))
                            Text("置信度: ${(relation.confidence * 100).toInt()}%", fontSize = 11.sp, color = Color(0xFFA3A3A3))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(relation.fromEntityId.take(8), fontSize = 14.sp, color = Color(0xFF0F172A))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFD4D4D4))
                            Text(relation.toEntityId.take(8), fontSize = 14.sp, color = Color(0xFF0F172A))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityList(communities: List<KnowledgeCommunityEntity>) {
    if (communities.isEmpty()) {
        EmptyState("暂无形成的主题群")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(communities) { community ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 0.5.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(community.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(community.summary, fontSize = 13.sp, color = Color(0xFF5F87A3), lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFFDBEEFF))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text, fontSize = 14.sp, color = Color(0xFFA3A3A3))
        }
    }
}
