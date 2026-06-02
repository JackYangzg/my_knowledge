package com.my.knowledge.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun ComposeMarkdown(
    markdown: String,
    onLinkClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lines = markdown.split("\n")
        var inCodeBlock = false
        val codeLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    renderCodeBlock(codeLines.joinToString("\n"))
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeLines.add(line)
                continue
            }

            when {
                trimmed.startsWith("### ") -> renderHeading(trimmed.removePrefix("### "), 3, onLinkClick)
                trimmed.startsWith("## ") -> renderHeading(trimmed.removePrefix("## "), 2, onLinkClick)
                trimmed.startsWith("# ") -> renderHeading(trimmed.removePrefix("# "), 1, onLinkClick)
                trimmed.startsWith("---") || trimmed.startsWith("***") ->
                    HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(vertical = 8.dp))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val bullet = trimmed.removePrefix("- ").removePrefix("* ")
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text("•", fontSize = 16.sp, color = Color(0xFF374151), modifier = Modifier.width(16.dp))
                        InlineMarkdownText(bullet, onLinkClick, modifier = Modifier.weight(1f))
                    }
                }
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val prefix = trimmed.substringBefore(" ")
                    val rest = trimmed.removePrefix("$prefix ")
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text("$prefix ", fontSize = 16.sp, color = Color(0xFF374151))
                        InlineMarkdownText(rest, onLinkClick, modifier = Modifier.weight(1f))
                    }
                }
                trimmed.startsWith("> ") -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row {
                            Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(Color(0xFFCBD5E1)))
                            InlineMarkdownText(trimmed.removePrefix("> "), onLinkClick, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
                trimmed.isEmpty() -> Spacer(modifier = Modifier.height(6.dp))
                trimmed.startsWith("!") && trimmed.contains("](") ->
                    ImageBlock(trimmed, onLinkClick)
                trimmed.matches(Regex("^\\[.+\\]\\(.+\\)$")) ->
                    AttachmentBlock(trimmed, onLinkClick)
                else -> InlineMarkdownText(trimmed, onLinkClick)
            }
        }

        if (inCodeBlock && codeLines.isNotEmpty()) {
            renderCodeBlock(codeLines.joinToString("\n"))
        }
    }
}

@Composable
private fun renderCodeBlock(code: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = code,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE2E8F0),
            modifier = Modifier.padding(12.dp),
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun renderHeading(text: String, level: Int, onLinkClick: (String) -> Unit) {
    val fontSize = when (level) { 1 -> 22.sp; 2 -> 18.sp; else -> 16.sp }
    val topPad = when (level) { 1 -> 16.dp; 2 -> 12.dp; else -> 8.dp }
    InlineMarkdownText(
        text = text, onLinkClick = onLinkClick,
        modifier = Modifier.padding(top = topPad, bottom = 4.dp),
        style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
    )
}

@Composable
fun ImageBlock(raw: String, onLinkClick: (String) -> Unit) {
    val alt = raw.substringAfter("![").substringBefore("]")
    val uri = raw.substringAfter("](").substringBefore(")")
    val context = LocalContext.current
    val file = remember(uri) { uri.removePrefix("file://").let { File(it).takeIf { it.exists() } } }
    val bitmap = remember(uri) {
        try {
            if (file != null) {
                BitmapFactory.decodeFile(file!!.absolutePath)?.let { bmp ->
                    val maxDim = 1200
                    if (bmp.width > maxDim || bmp.height > maxDim) {
                        val s = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
                        Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                    } else bmp
                }
            } else {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (_: Exception) { null }
    }

    Surface(
        onClick = { onLinkClick(uri) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = alt,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.FillWidth)
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(36.dp), tint = Color(0xFFCBD5E1))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(alt.ifEmpty { "Image" }, fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
fun AttachmentBlock(raw: String, onLinkClick: (String) -> Unit) {
    val name = raw.substringAfter("[").substringBefore("]")
    val uri = raw.substringAfter("(").substringBefore(")")
    val isPdf = uri.endsWith(".pdf", ignoreCase = true)
    Surface(onClick = { onLinkClick(uri) }, shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF8FAFC), border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isPdf) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.AttachFile,
                contentDescription = null, modifier = Modifier.size(24.dp), tint = Color(0xFF64748B))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = name, fontSize = 14.sp, color = Color(0xFF1E293B),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFCBD5E1))
        }
    }
}

@Composable
private fun InlineMarkdownText(
    text: String, onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, color = Color(0xFF262626))
) {
    val annotated = buildAnnotatedString {
        val all = Regex("(\\*\\*(.+?)\\*\\*)|(\\*(.+?)\\*)|(`([^`]+)`)|(\\[(.+?)\\]\\((.+?)\\))")
        var last = 0
        all.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first)); last = m.range.last + 1
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[2]!!.value) }
                m.groups[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[4]!!.value) }
                m.groups[5] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF1F5F9))) { append(m.groups[6]!!.value) }
                m.groups[7] != null -> {
                    withStyle(SpanStyle(color = Color(0xFF147EC5), textDecoration = TextDecoration.Underline)) {
                        pushStringAnnotation("link", m.groups[9]!!.value); append(m.groups[8]!!.value); pop()
                    }
                }
            }
        }
        append(text.substring(last))
    }
    ClickableText(text = annotated, modifier = modifier.fillMaxWidth(), style = style) { offset ->
        annotated.getStringAnnotations("link", offset, offset).firstOrNull()?.let { onLinkClick(it.item) }
    }
}

fun openFile(context: android.content.Context, uriString: String) {
    try {
        val path = uriString.removePrefix("file://")
        val file = File(path)
        if (file.exists()) {
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            val mime = context.contentResolver.getType(fileUri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "打开文件"))
        } else {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "打开文件"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
