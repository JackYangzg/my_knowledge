package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.ui.component.KnowledgeItemRow
import com.my.knowledge.viewmodel.KnowledgeItemListViewModel

@Composable
fun KnowledgeDetailScreen(
    kbName: String,
    viewModel: KnowledgeItemListViewModel,
    onBack: () -> Unit,
    onAskAI: (String) -> Unit
) {
    val items by viewModel.items.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF)) // Ocean 25
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
            Text(
                text = "知识详情",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Text(
                text = "$kbName · 已完成智能分析与碎片整理",
                fontSize = 13.sp,
                color = Color(0xFF5F87A3),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (items.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("该知识库尚无已整理知识", color = Color(0xFF5F87A3), fontSize = 14.sp)
                    }
                }
            } else {
                items(items) { item ->
                    KnowledgeItemRow(item, onAskAI = { onAskAI(item.title) })
                }
            }
        }
    }
}
