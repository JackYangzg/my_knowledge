package com.my.knowledge.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.KnowledgeInsight

@Composable
fun KnowledgeDigestSection(onOpenContext: () -> Unit, onOpenFragments: () -> Unit) {
    Column(modifier = Modifier.padding(top = 22.dp, start = 20.dp, end = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("知识整理", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF737373))
                Text("自动化提炼后的成果", fontSize = 12.sp, color = Color(0xFFA3A3A3))
            }
            Text("查看全部", fontSize = 12.sp, color = Color(0xFFA3A3A3))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DigestCard(
                modifier = Modifier.weight(1f),
                iconText = "⌘",
                title = "知识脉络",
                desc = "导入文件后自动提炼脉络",
                bottomText = "立即查看",
                onClick = onOpenContext
            )
            DigestCard(
                modifier = Modifier.weight(1f),
                iconText = "▦",
                title = "碎片整理",
                desc = "18 条零散记录待归纳",
                bottomText = "继续整理",
                onClick = onOpenFragments
            )
        }
    }
}

@Composable
fun DigestCard(
    modifier: Modifier = Modifier,
    iconText: String,
    title: String,
    desc: String,
    bottomText: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F5F5)),
                contentAlignment = Alignment.Center
            ) {
                Text(iconText, fontSize = 18.sp, color = Color(0xFF525252))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(modifier = Modifier.height(8.dp))
            Text(desc, fontSize = 12.sp, lineHeight = 20.sp, color = Color(0xFF737373), maxLines = 2)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(bottomText, fontSize = 12.sp, color = Color(0xFFA3A3A3))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFA3A3A3))
            }
        }
    }
}

@Composable
fun SummaryCard(modifier: Modifier = Modifier, value: String, label: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(label, fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 5.dp))
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

@Composable
fun MiniTag(text: String) {
    Surface(
        color = Color(0xFFEFF7FF),
        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = Color(0xFF147EC5),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun InsightRow(insight: KnowledgeInsight) {
    Column(modifier = Modifier.background(Color.White).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF147EC5), modifier = Modifier.size(16.dp))
            Text("自动提炼的洞察", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF147EC5))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = insight.summary,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            color = Color(0xFF262626)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            insight.keyTakeaways.forEach { point ->
                Surface(
                    color = Color(0xFFF7FBFF),
                    border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
                    shape = CircleShape
                ) {
                    Text(
                        point,
                        fontSize = 11.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun KnowledgeItemRow(item: KnowledgeItemEntity, onAskAI: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        border = BorderStroke(0.5.dp, Color(0xFFEEF6FF))
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                    modifier = Modifier.weight(1f),
                    lineHeight = 20.sp
                )
                IconButton(onClick = onAskAI, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "问AI", tint = Color(0xFF147EC5), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = item.excerpt,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = Color(0xFF5F87A3),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp)
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFFA3A3A3))
                Text(item.sourceType, fontSize = 11.sp, color = Color(0xFFA3A3A3))
                Spacer(modifier = Modifier.width(8.dp))
                MiniTag(item.status)
            }
        }
    }
}
