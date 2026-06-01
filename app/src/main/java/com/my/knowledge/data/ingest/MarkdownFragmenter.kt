package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.KnowledgeFragmentEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import java.util.UUID

class MarkdownFragmenter {
    fun split(parsed: ParsedContentEntity, knowledgeBaseId: String = ""): List<KnowledgeFragmentEntity> {
        val markdown = parsed.markdown
        if (markdown.isBlank()) return emptyList()

        val sections = mutableListOf<Section>()
        var currentHeading: String? = null
        var currentStart = 0
        val current = StringBuilder()

        markdown.lines().forEach { line ->
            val lineStart = markdown.indexOf(line, startIndex = currentStart).coerceAtLeast(currentStart)
            if (line.startsWith("#") && current.isNotBlank()) {
                sections += Section(currentHeading, current.toString().trim(), currentStart)
                current.clear()
                currentStart = lineStart
            }
            if (line.startsWith("#")) currentHeading = line.trimStart('#').trim()
            current.appendLine(line)
        }
        if (current.isNotBlank()) sections += Section(currentHeading, current.toString().trim(), currentStart)

        val chunks = sections.flatMap { section -> splitSection(section) }
        return chunks.mapIndexed { index, section ->
            val start = markdown.indexOf(section.content).takeIf { it >= 0 } ?: section.startOffset
            KnowledgeFragmentEntity(
                id = UUID.randomUUID().toString(),
                itemId = "",
                knowledgeBaseId = knowledgeBaseId,
                content = section.content,
                summary = null,
                tagsJson = "[]",
                sourceRef = parsed.sourceId,
                sourceManifestId = null,
                startOffset = start,
                endOffset = (start + section.content.length).coerceAtMost(markdown.length),
                createdAt = System.currentTimeMillis(),
                sourceId = parsed.sourceId,
                parsedContentId = parsed.id,
                knowledgeItemId = null,
                orderIndex = index,
                heading = section.heading,
                tokenCount = estimateTokens(section.content),
                embeddingId = null
            )
        }
    }

    private fun splitSection(section: Section): List<Section> {
        val maxChars = 3600
        val overlap = 450
        if (section.content.length <= maxChars) return listOf(section)

        val result = mutableListOf<Section>()
        var start = 0
        while (start < section.content.length) {
            val end = (start + maxChars).coerceAtMost(section.content.length)
            result += section.copy(content = section.content.substring(start, end), startOffset = section.startOffset + start)
            if (end == section.content.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return result
    }

    private fun estimateTokens(text: String): Int =
        (text.length / 2).coerceAtLeast(text.split(Regex("\\s+")).size)

    private data class Section(
        val heading: String?,
        val content: String,
        val startOffset: Int
    )
}
