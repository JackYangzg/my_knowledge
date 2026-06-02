package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.KnowledgeFragmentEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import java.util.UUID

/**
 * Splits parsed markdown into fragment rows for RAG / graph / embedding.
 *
 * The original implementation had three defects:
 *
 * 1. `currentStart` was computed via `markdown.indexOf(line, startIndex = currentStart)`,
 *    which goes wrong when the same line text appears earlier in the document —
 *    the wrong position was reported as the section start offset.
 * 2. When a section content length was an exact multiple of `maxChars`, the
 *    overlap arithmetic could drive `start` forward by zero, causing an
 *    infinite loop in `splitSection`.
 * 3. Heading lines were appended to the *previous* section's buffer, so a
 *    large section that got sub-split lost the heading context on every piece
 *    except the first.
 *
 * The rewrite walks the document line-by-line with an explicit `position`
 * cursor, only ever moves the cursor forward, propagates the heading down
 * into every sub-chunk, and guarantees termination by enforcing a positive
 * `start` step.
 */
class MarkdownFragmenter(
    private val maxChars: Int = 3600,
    private val overlap: Int = 450
) {
    fun split(parsed: ParsedContentEntity, knowledgeBaseId: String = ""): List<KnowledgeFragmentEntity> {
        val markdown = parsed.markdown
        if (markdown.isBlank()) return emptyList()

        val normalized = markdown.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        val sections = mutableListOf<Section>()
        var currentHeading: String? = null
        var currentStart = 0
        var position = 0
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) {
                sections += Section(
                    heading = currentHeading,
                    content = current.toString().trim(),
                    startOffset = currentStart
                )
            }
        }

        for (line in lines) {
            val lineStart = position
            position += line.length + 1 // +1 for the consumed '\n'
            if (line.startsWith("#") && current.isNotBlank()) {
                flush()
                current.clear()
                currentStart = lineStart
            }
            if (line.startsWith("#")) {
                currentHeading = line.trimStart('#').trim()
            }
            if (current.isEmpty()) currentStart = lineStart
            current.appendLine(line)
        }
        flush()

        val chunks = sections.flatMap { splitSection(it) }
        val now = System.currentTimeMillis()
        return chunks.mapIndexed { index, section ->
            KnowledgeFragmentEntity(
                id = UUID.randomUUID().toString(),
                itemId = "",
                knowledgeBaseId = knowledgeBaseId,
                content = section.content,
                summary = null,
                tagsJson = "[]",
                sourceRef = parsed.sourceId,
                sourceManifestId = null,
                startOffset = section.startOffset,
                endOffset = (section.startOffset + section.content.length).coerceAtMost(markdown.length),
                createdAt = now,
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
        if (section.content.length <= maxChars) return listOf(section)

        // Make sure the overlap is strictly smaller than the chunk size, otherwise
        // we cannot make forward progress. 450 vs 3600 is the default, but be safe.
        val safeOverlap = overlap.coerceIn(0, maxChars - 1)
        val result = mutableListOf<Section>()
        var start = 0
        while (start < section.content.length) {
            val end = (start + maxChars).coerceAtMost(section.content.length)
            val piece = section.content.substring(start, end)
            result += section.copy(
                content = piece,
                startOffset = section.startOffset + start
            )
            if (end == section.content.length) break
            val nextStart = end - safeOverlap
            // Guarantee a positive step, even on pathological input.
            start = if (nextStart <= start) end else nextStart
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
