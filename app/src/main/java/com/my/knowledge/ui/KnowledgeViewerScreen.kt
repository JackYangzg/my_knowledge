package com.my.knowledge.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.viewmodel.KnowledgeItemDetailViewModel
import com.mukesh.MarkDown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                    knowledgeItem.sourceType == "pdf" -> {
                        PdfContentViewer(knowledgeItem)
                    }
                    isImageType(knowledgeItem.sourceType) -> {
                        ImagePlaceholder(knowledgeItem.contentMarkdown)
                    }
                    else -> {
                        MarkDown(
                            modifier = Modifier.fillMaxWidth(),
                            text = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                            shouldOpenUrlInBrowser = true
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
private fun PdfContentViewer(item: KnowledgeItemEntity) {
    val localPath = remember(item.sourceTraceJson) { extractJsonString(item.sourceTraceJson, "localPath") }
    var pages by remember(localPath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember(localPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(localPath) {
        if (localPath.isNullOrBlank()) {
            error = "未找到 PDF 本地文件路径。"
            return@LaunchedEffect
        }
        val result = renderPdfPages(localPath)
        pages = result.getOrElse {
            error = it.localizedMessage ?: "PDF 渲染失败"
            emptyList()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!localPath.isNullOrBlank() && Build.VERSION.SDK_INT >= 31) {
            AndroidxPdfViewer(localPath)
            return@Column
        }

        if (pages.isEmpty() && error == null) {
            CircularProgressIndicator(color = Color(0xFF147EC5))
        }
        error?.let {
            Surface(color = Color(0xFFFEF2F2), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, color = Color(0xFFDC2626), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }
        pages.forEachIndexed { index, bitmap ->
            Surface(shape = RoundedCornerShape(8.dp), color = Color.White, shadowElevation = 1.dp) {
                Column {
                    Text("第 ${index + 1} 页", fontSize = 12.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(10.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF 第 ${index + 1} 页",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (item.contentMarkdown.isNotBlank()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7FBFF), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("解析文本", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Spacer(modifier = Modifier.height(8.dp))
                    MarkDown(modifier = Modifier.fillMaxWidth(), text = item.contentMarkdown, shouldOpenUrlInBrowser = true)
                }
            }
        }
    }
}

@Composable
private fun AndroidxPdfViewer(path: String) {
    val context = LocalContext.current
    val containerId = remember(path) { android.view.View.generateViewId() }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(620.dp),
        factory = { ctx ->
            FragmentContainerView(ctx).apply { id = containerId }
        },
        update = {
            val activity = context as? FragmentActivity ?: return@AndroidView
            val tag = "pdf_viewer_$containerId"
            if (activity.supportFragmentManager.findFragmentByTag(tag) == null) {
                val fragment = PdfViewerFragment().apply {
                    documentUri = Uri.fromFile(File(path))
                    isToolboxVisible = true
                }
                activity.supportFragmentManager.beginTransaction()
                    .replace(containerId, fragment, tag)
                    .commitAllowingStateLoss()
            }
        }
    )
}

private suspend fun renderPdfPages(path: String): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        require(file.exists()) { "PDF 文件不存在：$path" }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount.coerceAtMost(12)).map { pageIndex ->
                renderer.openPage(pageIndex).use { page ->
                    val width = 1080
                    val height = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }.also { descriptor.close() }
    }
}

private fun extractJsonString(raw: String, key: String): String? {
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(raw) ?: return null
    return match.groupValues[1]
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
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
                MarkDown(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    text = contentMarkdown,
                    shouldOpenUrlInBrowser = true
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
