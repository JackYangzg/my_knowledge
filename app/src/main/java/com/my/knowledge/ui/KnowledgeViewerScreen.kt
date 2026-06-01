package com.my.knowledge.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {}
) {
    LaunchedEffect(itemId) {
        viewModel.loadItem(itemId)
    }

    val item by viewModel.item.collectAsState()
    val processedItems by viewModel.processedItems.collectAsState()
    val sourceItem by viewModel.sourceItem.collectAsState()
    var showProcessedItems by remember(itemId) { mutableStateOf(false) }
    val linkTargets = remember(processedItems, sourceItem) {
        buildLinkTargets(processedItems, sourceItem)
    }

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
            item?.let { currentItem ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val canDrillDown = processedItems.isNotEmpty() && !currentItem.sourceType.startsWith("wiki_")
                    if (canDrillDown) {
                        TextButton(
                            onClick = { showProcessedItems = !showProcessedItems },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (showProcessedItems) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Hub,
                                contentDescription = null,
                                tint = Color(0xFF147EC5),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (showProcessedItems) "原文" else "加工数据", fontSize = 12.sp, color = Color(0xFF147EC5))
                        }
                    }
                    MiniTag(fileTypeLabel(currentItem.sourceType))
                }
            }
        }

        item?.let { knowledgeItem ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = if (showProcessedItems) "加工数据" else knowledgeItem.title,
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

                if (showProcessedItems) {
                    ProcessedWikiSection(
                        items = processedItems,
                        onOpenItem = onOpenItem
                    )
                } else {
                    when {
                        knowledgeItem.sourceType == "pdf" -> {
                            PdfContentViewer(knowledgeItem)
                        }
                        isImageType(knowledgeItem.sourceType) -> {
                            ImagePlaceholder(knowledgeItem.contentMarkdown)
                        }
                        else -> {
                            if (knowledgeItem.sourceType.startsWith("wiki_")) {
                                WikiMarkdownContent(
                                    markdown = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                                    linkTargets = linkTargets,
                                    onOpenItem = onOpenItem
                                )
                            } else {
                                MarkDown(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                                    shouldOpenUrlInBrowser = true
                                )
                            }
                        }
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
private fun WikiMarkdownContent(
    markdown: String,
    linkTargets: Map<String, String>,
    onOpenItem: (String) -> Unit
) {
    val sources = remember(markdown) { extractFrontMatterList(markdown, "sources") }
    val body = remember(markdown) { stripFrontMatter(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (sources.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7FBFF), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("引用原文", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    sources.forEach { source ->
                        InternalLinkText(
                            text = source,
                            linkTargets = linkTargets,
                            onOpenItem = onOpenItem,
                            fallbackAsLink = true
                        )
                    }
                }
            }
        }
        body.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                trimmed.startsWith("# ") -> Text(trimmed.removePrefix("# "), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), lineHeight = 30.sp)
                trimmed.startsWith("## ") -> Text(trimmed.removePrefix("## "), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A), modifier = Modifier.padding(top = 8.dp))
                trimmed.startsWith("### ") -> Text(trimmed.removePrefix("### "), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155), modifier = Modifier.padding(top = 4.dp))
                else -> InternalLinkText(
                    text = trimmed,
                    linkTargets = linkTargets,
                    onOpenItem = onOpenItem,
                    fallbackAsLink = false
                )
            }
        }
    }
}

@Composable
private fun InternalLinkText(
    text: String,
    linkTargets: Map<String, String>,
    onOpenItem: (String) -> Unit,
    fallbackAsLink: Boolean
) {
    val annotated = remember(text, linkTargets, fallbackAsLink) {
        buildInternalLinkAnnotatedString(text, linkTargets, fallbackAsLink)
    }
    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF334155)),
        onClick = { offset ->
            annotated.getStringAnnotations(TAG_INTERNAL_LINK, offset, offset)
                .firstOrNull()
                ?.item
                ?.let(onOpenItem)
        }
    )
}

private fun buildInternalLinkAnnotatedString(
    text: String,
    linkTargets: Map<String, String>,
    fallbackAsLink: Boolean
): AnnotatedString = buildAnnotatedString {
    val regex = Regex("\\[\\[([^\\]]+)]]")
    var cursor = 0
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) {
        appendMaybeLinkedPlainText(text, linkTargets, fallbackAsLink)
        return@buildAnnotatedString
    }
    matches.forEach { match ->
        append(text.substring(cursor, match.range.first))
        val label = match.groupValues[1].substringBefore("|").trim()
        appendLinkedLabel(label, linkTargets)
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private fun AnnotatedString.Builder.appendMaybeLinkedPlainText(
    text: String,
    linkTargets: Map<String, String>,
    fallbackAsLink: Boolean
) {
    if (fallbackAsLink) appendLinkedLabel(text, linkTargets) else append(text)
}

private fun AnnotatedString.Builder.appendLinkedLabel(label: String, linkTargets: Map<String, String>) {
    val targetId = linkTargets[normalizeLinkKey(label)]
    if (targetId == null) {
        append(label)
        return
    }
    pushStringAnnotation(TAG_INTERNAL_LINK, targetId)
    withStyle(SpanStyle(color = Color(0xFF147EC5), fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) {
        append(label)
    }
    pop()
}

private fun buildLinkTargets(processedItems: List<KnowledgeItemEntity>, sourceItem: KnowledgeItemEntity?): Map<String, String> {
    val pairs = mutableListOf<Pair<String, String>>()
    sourceItem?.let {
        pairs += normalizeLinkKey(it.title) to it.id
        pairs += normalizeLinkKey(it.title.substringBeforeLast('.', it.title)) to it.id
    }
    processedItems.forEach {
        pairs += normalizeLinkKey(it.title) to it.id
        pairs += normalizeLinkKey(it.title.substringBeforeLast('.', it.title)) to it.id
    }
    return pairs.filter { it.first.isNotBlank() }.distinctBy { it.first }.toMap()
}

private fun normalizeLinkKey(value: String): String =
    value.trim().lowercase().replace(Regex("[\\s_\\-，。！？,.!?；;：:、()（）\\[\\]]+"), "")

private fun stripFrontMatter(markdown: String): String {
    val trimmed = markdown.trimStart()
    if (!trimmed.startsWith("---")) return markdown
    return trimmed.substringAfter("---").substringAfter("---", missingDelimiterValue = markdown).trim()
}

private fun extractFrontMatterList(markdown: String, key: String): List<String> {
    val frontMatter = markdown.trimStart().takeIf { it.startsWith("---") }
        ?.substringAfter("---")
        ?.substringBefore("---")
        ?: return emptyList()
    val line = frontMatter.lines().firstOrNull { it.trimStart().startsWith("$key:") } ?: return emptyList()
    return line.substringAfter("[", "").substringBefore("]", "")
        .split(",")
        .map { it.trim().trim('"') }
        .filter { it.isNotBlank() }
}

private const val TAG_INTERNAL_LINK = "internal_link"

@Composable
private fun ProcessedWikiSection(
    items: List<KnowledgeItemEntity>,
    onOpenItem: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF7FBFF),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = Color(0xFF147EC5), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("由该知识加工生成", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                Spacer(modifier = Modifier.weight(1f))
                Text("${items.size} 项", fontSize = 12.sp, color = Color(0xFF5F87A3))
            }
            Spacer(modifier = Modifier.height(10.dp))
            items.forEachIndexed { index, processed ->
                if (index != 0) HorizontalDivider(color = Color(0xFFDBEEFF))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenItem(processed.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(processed.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0F172A))
                        Text(fileTypeLabel(processed.sourceType), fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 2.dp))
                    }
                    Icons.AutoMirrored.Filled.KeyboardArrowRight.let {
                        Icon(it, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                }
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

private suspend fun renderPdfPages(path: String): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        require(file.exists()) { "PDF 文件不存在：$path" }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount.coerceAtMost(100)).map { pageIndex ->
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
    "wiki_source" -> "来源摘要页"
    "wiki_entity" -> "实体页"
    "wiki_concept" -> "概念页"
    "wiki_index" -> "Wiki 索引"
    "wiki_overview" -> "Wiki 概览"
    "wiki_log" -> "Ingest 日志"
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
