package com.my.knowledge.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
import com.my.knowledge.data.ai.ScopeType
import com.my.knowledge.ui.component.AskSheet
import com.my.knowledge.ui.component.MiniTag
import com.my.knowledge.viewmodel.AskViewModel
import com.my.knowledge.viewmodel.KnowledgeItemDetailViewModel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun KnowledgeViewerScreen(
    itemId: String,
    viewModel: KnowledgeItemDetailViewModel,
    askViewModel: AskViewModel,
    knowledgeRepository: com.my.knowledge.domain.repository.KnowledgeRepository,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    onEditItem: (String) -> Unit = {}
) {
    val context = LocalContext.current
    LaunchedEffect(itemId) {
        viewModel.loadItem(itemId)
    }

    val item by viewModel.item.collectAsState()
    val processedItems by viewModel.processedItems.collectAsState()
    val sourceItem by viewModel.sourceItem.collectAsState()
    var showProcessedItems by remember(itemId) { mutableStateOf(false) }
    var showAskSheet by remember { mutableStateOf(false) }
    val linkTargets = remember(processedItems, sourceItem) {
        buildLinkTargets(processedItems, sourceItem)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
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
                    // Per-item ask history used to open AskHistorySheet; the
                    // history list now lives inside AskSheet itself, so this
                    // entry has been removed.
                    if (canEditItem(currentItem)) {
                        TextButton(
                            onClick = { onEditItem(currentItem.id) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = Color(0xFF147EC5),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("编辑", fontSize = 12.sp, color = Color(0xFF147EC5))
                        }
                    }
                    MiniTag(fileTypeLabel(currentItem.sourceType))
                }
            }
        }

        item?.let { knowledgeItem ->
            if (!showProcessedItems && knowledgeItem.sourceType == "pdf") {
                PdfContentViewer(
                    item = knowledgeItem,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    KnowledgeBodyHeader(
                        title = if (showProcessedItems) "加工数据" else knowledgeItem.title,
                        item = knowledgeItem
                    )

                    if (showProcessedItems) {
                        ProcessedWikiSection(
                            items = processedItems,
                            onOpenItem = onOpenItem
                        )
                    } else {
                        when {
                            isImageType(knowledgeItem.sourceType) -> {
                                ImagePlaceholder(knowledgeItem.contentMarkdown)
                            }
                            knowledgeItem.sourceType.startsWith("wiki_") -> {
                                WikiMarkdownContent(
                                    markdown = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                                    linkTargets = linkTargets,
                                    onOpenItem = onOpenItem
                                )
                            }
                            else -> {
                                ComposeMarkdown(
                                    modifier = Modifier.fillMaxWidth(),
                                    markdown = knowledgeItem.contentMarkdown.ifBlank { "暂无内容" },
                                    onLinkClick = { openFile(context, it) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(60.dp))
                }
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF147EC5))
            }
        }
    } // close outer Column

        // Floating "AI 问一问" button. Same look as the rest of the
        // app's screens, but scoped to KNOWLEDGE_ITEM — the model
        // only sees this one item and must not generalize beyond it.
        item?.let { currentItem ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 20.dp)
            ) {
                Surface(
                    onClick = {
                        askViewModel.setScope(ScopeType.KNOWLEDGE_ITEM, currentItem.id)
                        askViewModel.startNewConversation(currentItem.title)
                        showAskSheet = true
                    },
                    shape = CircleShape,
                    color = Color(0xFF111827),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(12.dp, CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "AI",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showAskSheet) {
            AskSheet(askViewModel = askViewModel, onClose = { showAskSheet = false })
        }
    } // close outer Box
}

@Composable
private fun WikiMarkdownContent(
    markdown: String,
    linkTargets: Map<String, String>,
    onOpenItem: (String) -> Unit
) {
    // 把 frontmatter 里的 sources / related 列表都提取出来渲染成跳转
    // chips。原来的实现只渲染 sources,related 被 stripFrontMatter 一起
    // 剥掉——结果实体/概念页里"## 相关"段就算写了 wikilink,顶部的
    // related chip 也不会出现,体感就是"页面没有跳转标识"。补上 related
    // 之后,孤立实体也能从顶部跳到 overview.md。
    val sources = remember(markdown) { extractFrontMatterList(markdown, "sources") }
    val related = remember(markdown) { extractFrontMatterList(markdown, "related") }
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
        // related 单独渲染成 chip 流——和 sources 一样的样式,但用更轻量
        // 的方式(每个 chip 一行),让用户能快速跳到关联页面。空列表就
        // 跳过,避免空 Surface 浪费空间。
        if (related.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFF7FF), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "相关页面（${related.size}）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    related.forEach { ref ->
                        // related 的每一项就是一个页面标题(label)——直接当
                        // 整行文本送进 InternalLinkText,fallbackAsLink=true
                        // 表示即使没匹配上 linkTargets 也会按 plain 渲染,
                        // 不会留下[[...]]的转义符号。
                        InternalLinkText(
                            text = ref,
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
private fun KnowledgeBodyHeader(
    title: String,
    item: KnowledgeItemEntity
) {
    Text(
        text = title,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0F172A),
        lineHeight = 32.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniTag(item.status)
        MiniTag(item.sourceType)
        Text(
            formatTimestamp(item.updatedAt),
            fontSize = 12.sp,
            color = Color(0xFFA3A3A3)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(color = Color(0xFFF3F4F6))
    Spacer(modifier = Modifier.height(16.dp))
}

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
private fun PdfContentViewer(
    item: KnowledgeItemEntity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localPath = remember(item.sourceTraceJson) { extractJsonString(item.sourceTraceJson, "localPath") }
    var previewPages by remember(localPath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var previewError by remember(localPath) { mutableStateOf<String?>(null) }
    var showFullText by remember(item.id) { mutableStateOf(false) }
    val textChunks = remember(item.contentMarkdown, showFullText) {
        chunkLongMarkdown(
            markdown = item.contentMarkdown.ifBlank { "暂无解析文本" },
            maxChars = if (showFullText) 220_000 else 36_000
        )
    }
    val isTruncated = remember(item.contentMarkdown, showFullText) {
        !showFullText && item.contentMarkdown.length > 36_000
    }

    LaunchedEffect(localPath) {
        if (localPath.isNullOrBlank()) {
            previewError = "未找到 PDF 本地文件路径。"
            return@LaunchedEffect
        }
        val result = renderPdfPreviewPages(localPath)
        previewPages = result.getOrElse {
            previewError = it.localizedMessage ?: "PDF 预览渲染失败"
            emptyList()
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        item {
            KnowledgeBodyHeader(title = item.title, item = item)
        }
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7FBFF), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PDF 预览", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    previewError?.let {
                        Text(it, color = Color(0xFFDC2626), fontSize = 12.sp)
                    } ?: Text(
                        "为避免大文件卡顿，仅预览前 ${previewPages.size.coerceAtLeast(1).coerceAtMost(PDF_PREVIEW_PAGE_LIMIT)} 页；完整内容请查看下方解析文本。",
                        fontSize = 12.sp,
                        color = Color(0xFF5F87A3)
                    )
                    TextButton(onClick = { localPath?.let { openFile(context, "file://$it") } }) {
                        Text("打开原始 PDF", fontSize = 13.sp)
                    }
                }
            }
        }
        items(previewPages, key = { it.hashCode() }) { bitmap ->
            Surface(shape = RoundedCornerShape(8.dp), color = Color.White, shadowElevation = 1.dp) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF 预览页",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7FBFF), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("解析文本", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text(
                        "共 ${item.contentMarkdown.length} 字符，已分块懒加载显示。",
                        fontSize = 12.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        items(textChunks, key = { it.index }) { chunk ->
            Surface(shape = RoundedCornerShape(10.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "文本片段 ${chunk.index + 1}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    ComposeMarkdown(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        markdown = chunk.text,
                        onLinkClick = { openFile(context, it) }
                    )
                }
            }
        }
        if (isTruncated) {
            item {
                Button(
                    onClick = { showFullText = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5))
                ) {
                    Text("继续加载更多解析文本", color = Color.White)
                }
            }
        }
    }
}

private suspend fun renderPdfPreviewPages(path: String): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        require(file.exists()) { "PDF 文件不存在：$path" }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount.coerceAtMost(PDF_PREVIEW_PAGE_LIMIT)).map { pageIndex ->
                renderer.openPage(pageIndex).use { page ->
                    val width = 720
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

private data class MarkdownChunk(
    val index: Int,
    val text: String
)

private fun chunkLongMarkdown(
    markdown: String,
    maxChars: Int,
    chunkSize: Int = 6_000
): List<MarkdownChunk> {
    val limited = markdown.take(maxChars)
    val chunks = mutableListOf<MarkdownChunk>()
    var start = 0
    while (start < limited.length) {
        val targetEnd = (start + chunkSize).coerceAtMost(limited.length)
        val paragraphEnd = limited.lastIndexOf("\n\n", startIndex = targetEnd - 1)
            .takeIf { it > start + chunkSize / 2 }
            ?: limited.lastIndexOf('\n', startIndex = targetEnd - 1)
                .takeIf { it > start + chunkSize / 2 }
            ?: targetEnd
        val end = paragraphEnd.coerceIn(start + 1, limited.length)
        chunks += MarkdownChunk(chunks.size, limited.substring(start, end).trim())
        start = end
        while (start < limited.length && limited[start].isWhitespace()) start++
    }
    return chunks.ifEmpty { listOf(MarkdownChunk(0, "暂无解析文本")) }
}

private const val PDF_PREVIEW_PAGE_LIMIT = 3

private fun extractJsonString(raw: String, key: String): String? {
    val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(raw) ?: return null
    return match.groupValues[1]
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

@Composable
private fun ImagePlaceholder(contentMarkdown: String) {
    val context = LocalContext.current
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
                ComposeMarkdown(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    markdown = contentMarkdown,
                    onLinkClick = { openFile(context, it) }
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

/**
 * Whether the user can re-open this knowledge item in the text editor.
 * We only offer "edit" for items whose content is actually editable text
 * (markdown, txt, AI answers, hand-typed notes, processed wiki pages
 * produced by the ingest pipeline). PDFs, images, audio and binary
 * attachments stay read-only — those go through the source file.
 */
private fun canEditItem(item: KnowledgeItemEntity): Boolean {
    if (item.deletedAt != null) return false
    val t = item.sourceType.lowercase()
    if (t in setOf("pdf", "jpg", "jpeg", "png", "bmp", "gif", "webp", "audio", "video")) return false
    // Wiki pages emitted by the ingest pipeline are markdown too.
    if (t.startsWith("wiki_")) return true
    // text-ish types and notes from the inspiration editor
    if (t in setOf("md", "markdown", "txt", "text", "note", "ai_answer", "manual", "text")) return true
    // Fallback: if the content itself looks like markdown/text we still allow
    // editing, because the user may have imported a custom source type.
    val body = item.contentMarkdown
    return body.isNotBlank() && body.length < 1_000_000
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
