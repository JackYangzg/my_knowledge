package com.my.knowledge.data.parser

import android.graphics.BitmapFactory
import com.my.knowledge.data.ai.AiGateway
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.zip.ZipFile

class PlainTextParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "text" ||
            sourceType == "note" ||
            mimeType?.startsWith("text/") == true ||
            mimeType?.contains("json") == true ||
            mimeType?.contains("csv") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val text = source.localPath?.let { File(it).takeIf { f -> f.exists() }?.readText() }.orEmpty()
        return ParsedContent(
            parserType = "plain_text",
            markdown = text,
            plainText = text.stripMarkdown(),
            metadataJson = """{"title":"${source.title.escapeJson()}","sourceType":"${source.sourceType}"}"""
        )
    }
}

class MarkdownParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        val path = mimeType.orEmpty()
        return sourceType == "markdown" || path.contains("markdown")
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val markdown = source.localPath?.let { File(it).takeIf { f -> f.exists() }?.readText() }.orEmpty()
        return ParsedContent(
            parserType = "markdown",
            markdown = markdown,
            plainText = markdown.stripMarkdown(),
            metadataJson = """{"title":"${source.title.escapeJson()}","sourceType":"${source.sourceType}"}"""
        )
    }
}

class MetadataOnlyParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean = true

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val markdown = buildString {
            appendLine("# ${source.title}")
            appendLine()
            appendLine("- 来源类型：${source.sourceType}")
            appendLine("- MIME：${source.mimeType ?: "unknown"}")
            appendLine("- SHA256：${source.sha256}")
            source.originalUri?.let { appendLine("- 原始 URI：$it") }
            source.localPath?.let { appendLine("- 本地路径：$it") }
            appendLine()
            appendLine("> 当前来源已完成本地注册。未命中特定解析器时，会保留来源元数据并继续进入 Review。")
        }
        return ParsedContent(
            parserType = when (source.sourceType) {
                "image" -> "image_metadata"
                "audio" -> "audio_metadata"
                "pdf" -> "pdf_metadata"
                "docx" -> "docx_metadata"
                "web" -> "webpage_metadata"
                else -> "metadata_only"
            },
            markdown = markdown,
            plainText = markdown.stripMarkdown(),
            metadataJson = """{"title":"${source.title.escapeJson()}","mimeType":"${source.mimeType.orEmpty().escapeJson()}"}"""
        )
    }
}

fun defaultParsers(): List<ContentParser> = listOf(
    MarkdownParser(),
    ImageOcrParser(),
    PdfTextParser(),
    DocxParser(),
    HtmlWebParser(),
    AudioTranscriptParser(),
    PlainTextParser(),
    MetadataOnlyParser()
)

class ImageOcrParser : ContentParser {
    private val ai = AiGateway()

    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "image" || mimeType?.startsWith("image/") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val file = source.localPath?.let { File(it) }
            ?: throw IllegalStateException("图片本地路径为空，无法调用图片分析 API")
        if (!file.exists()) {
            throw IllegalStateException("图片文件不存在：${file.absolutePath}")
        }
        val imageBytes = file.readBytes()
        if (imageBytes.isEmpty()) {
            throw IllegalStateException("图片文件为空：${file.absolutePath}")
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val text = bitmap?.let { recognizeText(it).trim() }.orEmpty()
        val mimeType = source.mimeType?.takeIf { it.startsWith("image/") } ?: guessImageMimeType(file)
        val imageDescription = ai.analyzeImage(
            imageBytes = imageBytes,
            mimeType = mimeType,
            title = source.title,
            ocrText = text
        ).trim()
        val markdown = buildString {
            appendLine("# ${source.title}")
            appendLine()
            if (bitmap != null) {
                appendLine("- 图片尺寸：${bitmap.width} x ${bitmap.height}")
            } else {
                appendLine("- 图片尺寸：无法本地解码")
            }
            appendLine("- SHA256：${source.sha256}")
            appendLine()
            appendLine("## 图片分析")
            appendLine()
            appendLine(imageDescription)
            appendLine()
            if (text.isNotBlank()) {
                appendLine("## OCR 文本")
                appendLine()
                appendLine(text)
            }
        }
        return ParsedContent(
            parserType = "image_api_analysis",
            markdown = markdown,
            plainText = listOf(imageDescription, text).filter { it.isNotBlank() }.joinToString("\n\n"),
            metadataJson = buildString {
                append("{")
                append("\"title\":\"${source.title.escapeJson()}\"")
                append(",\"sourceType\":\"image\"")
                append(",\"mimeType\":\"${mimeType.escapeJson()}\"")
                append(",\"imageApiStatus\":\"success\"")
                if (bitmap != null) {
                    append(",\"width\":${bitmap.width},\"height\":${bitmap.height}")
                }
                append(",\"ocrChars\":${text.length},\"analysisChars\":${imageDescription.length}}")
            }
        )
    }

    private suspend fun recognizeText(bitmap: android.graphics.Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (continuation.isActive) continuation.resume("")
                }
                .addOnCanceledListener {
                    recognizer.close()
                    if (continuation.isActive) continuation.resume("")
                }
        }

    private fun guessImageMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }
}

class PdfTextParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "pdf" || mimeType?.contains("pdf") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val file = source.localPath?.let { File(it) }
        val raw = file?.takeIf { it.exists() }?.readBytes()?.toString(Charsets.ISO_8859_1).orEmpty()
        val extracted = extractPdfText(raw)
        val pageCount = Regex("/Type\\s*/Page\\b").findAll(raw).count().coerceAtLeast(1)
        val markdown = buildString {
            appendLine("# ${source.title}")
            appendLine()
            appendLine("- PDF 页数估计：$pageCount")
            appendLine("- SHA256：${source.sha256}")
            appendLine()
            if (extracted.isNotBlank()) {
                appendLine("## 抽取文本")
                appendLine()
                appendLine(extracted)
            } else {
                appendLine("> PDF 已保存到本地，但未检测到可直接抽取的文本流。扫描版 PDF 需要 OCR。")
            }
        }
        return ParsedContent(
            parserType = if (extracted.isBlank()) "pdf_metadata" else "pdf_text",
            markdown = markdown,
            plainText = extracted.ifBlank { markdown.stripMarkdown() },
            metadataJson = """{"title":"${source.title.escapeJson()}","sourceType":"pdf","estimatedPages":$pageCount,"extractedChars":${extracted.length}}"""
        )
    }

    private fun extractPdfText(raw: String): String {
        val literalStrings = Regex("\\((?:\\\\.|[^\\\\)]){2,}\\)")
            .findAll(raw)
            .map { it.value.removePrefix("(").removeSuffix(")").decodePdfLiteral() }
            .filter { it.count { c -> c.isLetterOrDigit() } >= 2 }
            .toList()
        val hexStrings = Regex("<([0-9A-Fa-f]{4,})>")
            .findAll(raw)
            .mapNotNull { decodePdfHex(it.groupValues[1]) }
            .filter { it.count { c -> c.isLetterOrDigit() } >= 2 }
            .toList()
        return (literalStrings + hexStrings)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200_000)
    }

    private fun String.decodePdfLiteral(): String =
        replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\\t", "\t")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")

    private fun decodePdfHex(hex: String): String? {
        return runCatching {
            val clean = if (hex.length % 2 == 0) hex else "${hex}0"
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val utf16 = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            } else {
                bytes.toString(Charsets.ISO_8859_1)
            }
            utf16
        }.getOrNull()
    }
}

class DocxParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "docx" ||
            mimeType?.contains("wordprocessingml.document") == true ||
            mimeType?.contains("msword") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val file = source.localPath?.let { File(it) }
        val text = if (file != null && file.extension.equals("docx", ignoreCase = true)) {
            extractDocxText(file)
        } else {
            ""
        }
        val markdown = if (text.isNotBlank()) {
            "# ${source.title}\n\n$text"
        } else {
            "# ${source.title}\n\n> Word 文件已注册，但当前文件不是可直接解包的 DOCX，保留来源供后续解析。"
        }
        return ParsedContent(
            parserType = "docx",
            markdown = markdown,
            plainText = markdown.stripMarkdown(),
            metadataJson = """{"title":"${source.title.escapeJson()}","sourceType":"docx","extractedChars":${text.length}}"""
        )
    }

    private fun extractDocxText(file: File): String {
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return@use ""
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    .replace(Regex("<w:p[^>]*>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
            }
        }.getOrDefault("")
    }
}

class HtmlWebParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "web" ||
            mimeType?.contains("html") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val raw = source.localPath?.let { File(it).takeIf { f -> f.exists() }?.readText() }.orEmpty()
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.decodeHtml()?.trim().orEmpty()
        val body = raw
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>|</div>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .decodeHtml()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        val markdown = buildString {
            appendLine("# ${title.ifBlank { source.title }}")
            appendLine()
            if (source.originalUri != null) appendLine("来源：${source.originalUri}\n")
            appendLine(body.ifBlank { "网页来源已保存，但未检测到可解析正文。" })
        }
        return ParsedContent(
            parserType = "webpage",
            markdown = markdown,
            plainText = body,
            metadataJson = """{"title":"${title.escapeJson()}","url":"${source.originalUri.orEmpty().escapeJson()}"}"""
        )
    }
}

class AudioTranscriptParser : ContentParser {
    override fun supports(mimeType: String?, sourceType: String): Boolean {
        return sourceType == "audio" || mimeType?.startsWith("audio/") == true
    }

    override suspend fun parse(source: SourceDocumentEntity): ParsedContent {
        val sidecar = source.localPath?.let { File(it).resolveSibling("${File(it).nameWithoutExtension}.txt") }
        val transcript = sidecar?.takeIf { it.exists() }?.readText().orEmpty()
        val markdown = if (transcript.isNotBlank()) {
            "# ${source.title}\n\n## 语音转写\n\n$transcript"
        } else {
            "# ${source.title}\n\n> 音频已保存到本地。请在语音输入结束时写入同名 .txt 转写文件，管道会自动解析为知识片段。"
        }
        return ParsedContent(
            parserType = if (transcript.isNotBlank()) "audio_asr" else "audio_asr_pending",
            markdown = markdown,
            plainText = transcript.ifBlank { markdown.stripMarkdown() },
            metadataJson = """{"title":"${source.title.escapeJson()}","hasTranscript":${transcript.isNotBlank()}}"""
        )
    }
}

private fun String.stripMarkdown(): String =
    replace(Regex("#{1,6}\\s*"), "")
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~`]"), "")

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun String.decodeHtml(): String =
    replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
