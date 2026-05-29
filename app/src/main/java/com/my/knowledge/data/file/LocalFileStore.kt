package com.my.knowledge.data.file

import android.content.Context
import java.io.File
import java.util.*

class LocalFileStore(private val context: Context) {
    private val markdownDir = File(context.filesDir, "markdown").apply { if (!exists()) mkdirs() }
    private val imagesDir = File(context.filesDir, "images").apply { if (!exists()) mkdirs() }
    private val attachmentsDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
    private val audioDir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }

    fun getMarkdownFile(id: String): File = File(markdownDir, "note_$id.md")
    
    fun writeMarkdown(id: String, content: String) {
        getMarkdownFile(id).writeText(content)
    }

    fun readMarkdown(id: String): String {
        val file = getMarkdownFile(id)
        return if (file.exists()) file.readText() else ""
    }

    fun generateNoteId(): String = UUID.randomUUID().toString()
}
