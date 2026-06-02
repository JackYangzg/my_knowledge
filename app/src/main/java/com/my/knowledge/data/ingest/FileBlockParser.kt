package com.my.knowledge.data.ingest

/**
 * Parser for LLM multi-file output format.
 *
 *     ---FILE: path/to/file.md---
 *     (content)
 *     ---END FILE---
 *
 * Defenses the original parser was missing:
 *
 * 1. CRLF normalization. Models running on Windows / curl pipelines emit
 *    `\r\n`; matching against `^---FILE:...$` against `\r`-terminated lines
 *    silently failed and the whole response was dropped.
 * 2. Marker case / whitespace variations. Models sometimes write
 *    `--- File: x ---` or `--- end file ---`; we treat the marker loosely.
 * 3. Fence-aware boundary detection. If the file body contains a nested
 *    ``` fence, a `---END FILE---` token inside the fence must NOT close
 *    the block. We track fence depth in CommonMark style.
 * 4. Stream truncation tolerance. If the response was cut off before the
 *    `---END FILE---` arrived, the partial file is still emitted with a
 *    `truncated=true` flag so the orchestrator can decide whether to retry
 *    instead of silently dropping it.
 * 5. Path safety. Paths must be relative, must not contain `..` segments,
 *    must not be absolute, and must point to a `.md` file. Anything else
 *    is rejected and recorded in `unsafePaths` — a follow-up to
 *    llm_wiki's `isSafeIngestPath` check.
 */
object FileBlockParser {

    data class ParsedBlock(
        val path: String,
        val content: String,
        val truncated: Boolean = false
    )

    data class ParseResult(
        val blocks: List<ParsedBlock>,
        val unsafePaths: List<String>,
        val truncated: Boolean
    )

    private val FILE_OPENER = Regex("^\\s*---\\s*FILE\\s*:\\s*(.+?)\\s*---\\s*$", RegexOption.IGNORE_CASE)
    private val FILE_CLOSER = Regex("^\\s*---\\s*END\\s*FILE\\s*---\\s*$", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ParsedBlock> = parseDetailed(text).blocks

    fun parseDetailed(text: String): ParseResult {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split("\n")
        val blocks = mutableListOf<ParsedBlock>()
        val unsafePaths = mutableListOf<String>()

        var i = 0
        var anyTruncated = false
        while (i < lines.size) {
            val opener = FILE_OPENER.matchEntire(lines[i])
            if (opener != null) {
                val rawPath = opener.groupValues[1].trim()
                val safePath = sanitizePath(rawPath)
                i++
                val contentLines = mutableListOf<String>()
                var closed = false
                var fenceOpen = false
                var fenceMarker: String? = null
                while (i < lines.size) {
                    val raw = lines[i]
                    val trimmed = raw.trim()
                    // Track fenced code blocks so the closer inside one is ignored.
                    val fenceMatch = Regex("^(\\s*)(`{3,}|~{3,})(.*)$").matchEntire(raw)
                    if (fenceMatch != null) {
                        val marker = fenceMatch.groupValues[2]
                        if (!fenceOpen) {
                            fenceOpen = true
                            fenceMarker = marker
                        } else if (fenceMarker != null && trimmed.startsWith(fenceMarker)) {
                            fenceOpen = false
                            fenceMarker = null
                        }
                    }
                    if (!fenceOpen && FILE_CLOSER.matchEntire(raw) != null) {
                        closed = true
                        i++
                        break
                    }
                    contentLines.add(raw)
                    i++
                }
                if (safePath != null) {
                    if (closed) {
                        blocks += ParsedBlock(safePath, contentLines.joinToString("\n").trim())
                    } else {
                        anyTruncated = true
                        // Keep what we have so the caller can decide to retry.
                        blocks += ParsedBlock(safePath, contentLines.joinToString("\n").trim(), truncated = true)
                    }
                } else {
                    unsafePaths += rawPath
                }
            } else {
                i++
            }
        }
        return ParseResult(blocks, unsafePaths, anyTruncated)
    }

    /**
     * Returns the path if it is safe, or null if it should be rejected.
     * The rules mirror llm_wiki's `isSafeIngestPath`:
     *
     *   - must be relative (no leading `/`, no `\` separator)
     *   - no `..` segments
     *   - must live under `wiki/`
     *   - must end in `.md`
     *   - no NUL / control characters
     *   - length <= 256
     */
    private fun sanitizePath(raw: String): String? {
        if (raw.isBlank() || raw.length > 256) return null
        if (raw.contains('\u0000') || raw.any { it.isISOControl() }) return null
        if (raw.startsWith("/") || raw.startsWith("\\")) return null
        if (Regex("^[a-zA-Z]:").containsMatchIn(raw)) return null
        val normalized = raw.replace('\\', '/').trim()
        val segments = normalized.split("/")
        if (segments.any { it == ".." }) return null
        if (segments.any { !isSafePathSegment(it) }) return null
        if (!normalized.startsWith("wiki/")) return null
        if (!raw.endsWith(".md", ignoreCase = true)) return null
        return normalized
    }

    private fun isSafePathSegment(segment: String): Boolean {
        if (segment.isBlank()) return false
        if (segment.any { it in listOf('<', '>', ':', '"', '|', '?', '*') }) return false
        if (segment.endsWith(" ") || segment.endsWith(".")) return false
        val stem = segment.substringBefore(".").uppercase()
        if (stem in setOf("CON", "PRN", "AUX", "NUL")) return false
        if (Regex("^COM[1-9]$").matches(stem)) return false
        if (Regex("^LPT[1-9]$").matches(stem)) return false
        return true
    }
}
