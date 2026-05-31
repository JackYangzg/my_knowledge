package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.viewmodel.KnowledgeItemDetailViewModel

@Composable
fun KnowledgeViewerScreen(
    itemId: String,
    viewModel: KnowledgeItemDetailViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(itemId) {
        viewModel.loadItem(itemId)
    }

    val item by viewModel.item.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
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
            if (item != null) {
                MiniTag(fileTypeLabel(item!!.sourceType))
            }
        }

        item?.let { knowledgeItem ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // Title
                Text(
                    text = knowledgeItem.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                    lineHeight = 32.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Meta info
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniTag(knowledgeItem.status)
                    MiniTag(knowledgeItem.sourceType)
                    Text(
                        formatTimestamp(knowledgeItem.updatedAt),
                        fontSize = 12.sp,
                        color = Color(0xFFA3A3A3)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Color(0xFFF3F4F6))
                Spacer(modifier = Modifier.height(16.dp))

                // Content body
                when {
                    isImageType(knowledgeItem.sourceType) -> {
                        ImagePlaceholder(knowledgeItem.contentMarkdown)
                    }
                    else -> {
                        Text(
                            text = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                            color = Color(0xFF262626)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(60.dp))
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF147EC5))
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(contentMarkdown: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color(0xFFDBEEFF)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "图片类型知识",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "该知识为图像类型，内容已提取为文本描述",
            fontSize = 14.sp,
            color = Color(0xFFA3A3A3)
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (contentMarkdown.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF7FBFF)
            ) {
                Text(
                    text = contentMarkdown,
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    color = Color(0xFF262626),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

private fun fileTypeLabel(sourceType: String): String = when (sourceType.lowercase()) {
    "md", "markdown" -> "Markdown"
    "txt", "text" -> "纯文本"
    "doc", "docx" -> "Word 文档"
    "pdf" -> "PDF 文档"
    "ppt", "pptx" -> "PPT 演示"
    "wps", "wpp" -> "WPS 文档"
    "jpg", "jpeg", "png", "bmp", "gif", "webp" -> "图片"
    "灵感记录" -> "灵感记录"
    else -> sourceType
}

private fun isImageType(sourceType: String): Boolean {
    val lower = sourceType.lowercase()
    return lower in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
