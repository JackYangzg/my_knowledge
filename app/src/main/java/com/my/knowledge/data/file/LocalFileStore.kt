package com.my.knowledge.data.file

import android.content.Context
import android.net.Uri
import com.my.knowledge.data.util.Sha256
import java.io.File
import java.util.*

class LocalFileStore(private val context: Context) {
    private val markdownDir = File(context.filesDir, "markdown").apply { if (!exists()) mkdirs() }
    private val imagesDir = File(context.filesDir, "images").apply { if (!exists()) mkdirs() }
    private val attachmentsDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
    private val audioDir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }
    private val backupsDir = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
    private val sourcesDir = File(context.filesDir, "sources").apply { if (!exists()) mkdirs() }
    private val parsedDir = File(context.filesDir, "parsed").apply { if (!exists()) mkdirs() }
    private val assetsDir = File(context.filesDir, "assets").apply { if (!exists()) mkdirs() }

    fun getMarkdownFile(id: String): File = File(markdownDir, "note_$id.md")
    
    fun writeMarkdown(id: String, content: String) {
        getMarkdownFile(id).writeText(content)
    }

    fun readMarkdown(id: String): String {
        val file = getMarkdownFile(id)
        return if (file.exists()) file.readText() else ""
    }

    fun writeBackup(name: String, content: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(backupsDir, safeName).also { it.writeText(content) }
    }

    fun saveTextSource(sourceId: String, text: String): File {
        val dir = File(sourcesDir, sourceId).apply { mkdirs() }
        return File(dir, "original.txt").also { it.writeText(text) }
    }

    fun copyUriSource(sourceId: String, uri: Uri, displayName: String): File {
        val dir = File(sourcesDir, sourceId).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "original.bin" }
        val target = File(dir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open source uri: $uri")
        return target
    }

    fun writeSourceManifest(sourceId: String, json: String): File {
        val dir = File(sourcesDir, sourceId).apply { mkdirs() }
        return File(dir, "manifest.json").also { it.writeText(json) }
    }

    fun writeParsedMarkdown(sourceId: String, markdown: String): File {
        val dir = File(parsedDir, sourceId).apply { mkdirs() }
        return File(dir, "content.md").also { it.writeText(markdown) }
    }

    fun writeParsedMetadata(sourceId: String, json: String): File {
        val dir = File(parsedDir, sourceId).apply { mkdirs() }
        return File(dir, "metadata.json").also { it.writeText(json) }
    }

    fun sourceAssetDir(sourceId: String): File = File(assetsDir, sourceId).apply { mkdirs() }

    fun deleteSourceFiles(sourceId: String) {
        File(sourcesDir, sourceId).deleteRecursively()
        File(parsedDir, sourceId).deleteRecursively()
        File(assetsDir, sourceId).deleteRecursively()
    }

    fun sha256(file: File): String = Sha256.hex(file)

    fun sha256Text(content: String): String = Sha256.hex(content)

    fun generateNoteId(): String = UUID.randomUUID().toString()
}
