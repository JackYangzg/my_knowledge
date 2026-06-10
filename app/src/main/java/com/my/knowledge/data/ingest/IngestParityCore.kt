package com.my.knowledge.data.ingest

import com.my.knowledge.data.ai.AiPromptTemplates
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure behavior mirror of llm_wiki's ingest core at
 * b86d81b0002afcee981169658c7602b8ec5f8905.
 *
 * Android-specific concerns (Room rows, WorkManager, notifications,
 * graph rebuilds) stay in [IngestOrchestrator]. Keeping prompt and
 * merge policy here makes parity testable without Android runtime.
 */
object IngestParityCore {
    const val UPSTREAM_COMMIT = "b86d81b0002afcee981169658c7602b8ec5f8905"
    private const val MAX_SOURCE_SUMMARY_SLUG_LENGTH = 120
    const val BODY_SHRINK_THRESHOLD = 0.70

    val generationWikiTypes = listOf(
        "source",
        "entity",
        "concept",
        "comparison",
        "query",
        "synthesis",
        "thesis",
        "methodology",
        "finding",
    )

    fun sourceIdentity(title: String, folderHint: String?): String {
        val folder = folderHint.orEmpty()
            .replace('\\', '/')
            .trim('/')
            .trim()
        return if (folder.isBlank()) title else "$folder/$title"
    }

    fun sourceSummarySlug(sourceIdentity: String): String {
        val withoutExt = sourceIdentity.replace(Regex("\\.[^/.]+$"), "")
        val parts = withoutExt.split('/').map(String::trim).filter(String::isNotBlank)
        if (parts.size <= 1) return parts.firstOrNull() ?: "source"

        val hash = stableSlugHash(sourceIdentity)
        val encoded = parts.joinToString("--") { part ->
            val value = URLEncoder.encode(part, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace(Regex("[!'()*]")) { match ->
                    "%%%02X".format(match.value[0].code)
                }
            "${value.length}-$value"
        }
        val full = "$encoded--$hash"
        if (full.length <= MAX_SOURCE_SUMMARY_SLUG_LENGTH) return full
        val readableLimit = MAX_SOURCE_SUMMARY_SLUG_LENGTH - hash.length - 2
        val prefix = encoded.take(readableLimit)
            .replace(Regex("%(?:[0-9A-F])?$", RegexOption.IGNORE_CASE), "")
            .trimEnd('-')
            .ifBlank { "source" }
        return "$prefix--$hash"
    }

    private fun stableSlugHash(value: String): String {
        var hash = 0x811c9dc5.toInt()
        value.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return hash.toUInt().toString(36)
    }

    fun longSourceHash(value: String): String {
        var hash = 0xcbf29ce484222325uL
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    fun analysisPrompt(
        purpose: String,
        index: String,
        language: String,
    ): String = listOf(
        "You are an expert research analyst. Read the source document and produce a structured analysis.",
        "Do not output chain-of-thought, hidden reasoning, or a thinking transcript. Reason internally and write only the concise final analysis.",
        "",
        AiPromptTemplates.languageDirective(language),
        "",
        "Your analysis should cover:",
        "",
        "## Key Entities",
        "List people, organizations, products, datasets, tools mentioned. For each:",
        "- Name and type",
        "- Role in the source (central vs. peripheral)",
        "- Whether it likely already exists in the wiki (check the index)",
        "",
        "## Key Concepts",
        "List theories, methods, techniques, phenomena. For each:",
        "- Name and brief definition",
        "- Why it matters in this source",
        "- Whether it likely already exists in the wiki",
        "",
        "## Main Arguments & Findings",
        "- What are the core claims or results?",
        "- What evidence supports them?",
        "- How strong is the evidence?",
        "",
        "## Connections to Existing Wiki",
        "- What existing pages does this source relate to?",
        "- Does it strengthen, challenge, or extend existing knowledge?",
        "",
        "## Contradictions & Tensions",
        "- Does anything in this source conflict with existing wiki content?",
        "- Are there internal tensions or caveats?",
        "",
        "## Recommendations",
        "- What wiki pages should be created or updated?",
        "- What should be emphasized vs. de-emphasized?",
        "- Any open questions worth flagging for the user?",
        "",
        "Be thorough but concise. Focus on what's genuinely important.",
        "",
        "If a folder context is provided, use it as a hint for categorization — the folder structure often reflects the user's organizational intent (e.g., 'papers/energy' suggests the file is an energy-related paper).",
        "",
        purpose.takeIf(String::isNotBlank)?.let { "## Wiki Purpose (for context)\n$it" }.orEmpty(),
        index.takeIf(String::isNotBlank)?.let { "## Current Wiki Index (for checking existing content)\n$it" }.orEmpty(),
    ).filter(String::isNotEmpty).joinToString("\n")

    fun analysisUserMessage(
        sourceIdentity: String,
        folderContext: String?,
        sourceContent: String,
    ): String = buildString {
        append("Analyze this source document:\n\n**File:** ")
        append(sourceIdentity)
        if (!folderContext.isNullOrBlank()) append("\n**Folder context:** ").append(folderContext)
        append("\n\n---\n\n")
        append(sourceContent)
    }

    fun generationPrompt(
        schema: String,
        purpose: String,
        index: String,
        sourceIdentity: String,
        overview: String,
        language: String,
        sourceSummaryPath: String,
    ): String = listOf(
        "You are a wiki maintainer. Based on the analysis provided, generate wiki files.",
        "Do not output chain-of-thought, hidden reasoning, or explanatory preamble. Reason internally and output only the requested FILE/REVIEW blocks.",
        "",
        AiPromptTemplates.languageDirective(language),
        "",
        "## IMPORTANT: Source File",
        "The original source file is: **$sourceIdentity**",
        "All wiki pages generated from this source MUST include this filename in their frontmatter `sources` field.",
        "",
        if (schema.isNotBlank()) listOf(
            "## Project Schema and Routing (AUTHORITATIVE)",
            schema,
            "",
            "Use this schema as the primary routing rule for page types and directories.",
            "If it defines custom folders or distinctions (for example people, technologies, organizations, methods, or cases), write pages into those schema-defined folders instead of forcing them into wiki/entities/ or wiki/concepts/.",
            "Use wiki/entities/ and wiki/concepts/ only when the schema does not provide a more specific destination.",
        ).joinToString("\n") else "",
        "",
        "## What to generate",
        "",
        "1. A source summary page at **$sourceSummaryPath** (MUST use this exact path)",
        "2. Entity or schema-defined typed pages for key named things identified in the analysis. Prefer schema-defined directories when present; otherwise use wiki/entities/.",
        "3. Concept or schema-defined typed pages for key ideas, methods, techniques, and abstractions. Prefer schema-defined directories when present; otherwise use wiki/concepts/.",
        "4. An updated wiki/index.md — add new entries to existing categories, preserve all existing entries",
        "5. A log entry for wiki/log.md (just the new entry to append, format: ## [YYYY-MM-DD] ingest | Title)",
        "6. An updated wiki/overview.md — a high-level summary of what the entire wiki covers, updated to reflect the newly ingested source. This should be a comprehensive 2-5 paragraph overview of ALL topics in the wiki, not just the new source.",
        "",
        "## Frontmatter Rules (CRITICAL — parser is strict)",
        "",
        "Every page begins with a YAML frontmatter block. Format rules, in order of importance:",
        "",
        "1. The VERY FIRST line of the file MUST be exactly `---` (three hyphens, nothing else).",
        "   Do NOT wrap the file in a ```yaml ... ``` code fence.",
        "   Do NOT prefix it with a `frontmatter:` key or any other line.",
        "2. Each frontmatter line is a `key: value` pair on its own line.",
        "3. The frontmatter ends with another `---` line on its own.",
        "4. The next line after the closing `---` is the start of the page body.",
        "5. Arrays use the standard YAML inline form `[a, b, c]` (no outer brackets around each item).",
        "   Wikilinks belong in the BODY only — never write `related: [[a]], [[b]]` (invalid YAML);",
        "   write `related: [a, b]` with bare slugs.",
        "",
        "Required fields and types:",
        "  • type     — one of the known types (${generationWikiTypes.joinToString(" | ")}), or a custom type explicitly defined by the project schema",
        "  • title    — string (quote it if it contains a colon, e.g. `title: \"Foo: Bar\"`)",
        "  • created  — date in YYYY-MM-DD form (no quotes)",
        "  • updated  — same as created",
        "  • tags     — array of bare strings: `tags: [microbiology, ai]`",
        "  • related  — array of bare wiki page slugs: `related: [foo, bar-baz]`. Do NOT include",
        "               `wiki/`, `.md`, or `[[…]]` here — slugs only.",
        "  • sources  — array of source filenames; MUST include \"$sourceIdentity\".",
        "",
        "Concrete example of a complete, parseable page (everything between the two `---` lines",
        "is the frontmatter; the heading and prose below are the body):",
        "",
        "    ---",
        "    type: entity",
        "    title: Example Entity",
        "    created: 2026-04-29",
        "    updated: 2026-04-29",
        "    tags: [example, demo]",
        "    related: [related-slug-1, related-slug-2]",
        "    sources: [\"$sourceIdentity\"]",
        "    ---",
        "",
        "    # Example Entity",
        "",
        "    Body content goes here. Use [[wikilink]] syntax in the body for cross-references.",
        "",
        "Other rules:",
        "- Use [[wikilink]] syntax in the BODY for cross-references between pages",
        "- If you include images, use wiki-root-relative paths such as `media/source-slug/image.png`; never output absolute filesystem paths.",
        "- Use kebab-case filenames",
        "- Follow the analysis recommendations on what to emphasize",
        "- If the analysis found connections to existing pages, add cross-references",
        "",
        "## Review block types",
        "",
        "After all FILE blocks, optionally emit REVIEW blocks for anything that needs human judgment:",
        "",
        "- contradiction: the analysis found conflicts with existing wiki content",
        "- duplicate: an entity/concept might already exist under a different name in the index",
        "- missing-page: an important concept is referenced but has no dedicated page",
        "- suggestion: ideas for further research, related sources to look for, or connections worth exploring",
        "",
        "Only create reviews for things that genuinely need human input. Don't create trivial reviews.",
        "",
        "## OPTIONS allowed values (only these predefined labels):",
        "",
        "- contradiction: OPTIONS: Create Page | Skip",
        "- duplicate: OPTIONS: Create Page | Skip",
        "- missing-page: OPTIONS: Create Page | Skip",
        "- suggestion: OPTIONS: Create Page | Skip",
        "",
        "The user also has a 'Deep Research' button (auto-added by the system) that triggers web search.",
        "Do NOT invent custom option labels. Only use 'Create Page' and 'Skip'.",
        "",
        "For suggestion and missing-page reviews, the SEARCH field must contain 2-3 web search queries",
        "(keyword-rich, specific, suitable for a search engine — NOT titles or sentences). Example:",
        "  SEARCH: automated technical debt detection AI generated code | software quality metrics LLM code generation | static analysis tools agentic software development",
        "",
        purpose.takeIf(String::isNotBlank)?.let { "## Wiki Purpose\n$it" }.orEmpty(),
        index.takeIf(String::isNotBlank)?.let { "## Current Wiki Index (preserve all existing entries, add new ones)\n$it" }.orEmpty(),
        overview.takeIf(String::isNotBlank)?.let { "## Current Overview (update this to reflect the new source)\n$it" }.orEmpty(),
        "",
        "## Output Format (MUST FOLLOW EXACTLY — this is how the parser reads your response)",
        "",
        "Your ENTIRE response consists of FILE blocks followed by optional REVIEW blocks. Nothing else.",
        "",
        "FILE block template:",
        "```",
        "---FILE: wiki/path/to/page.md---",
        "(complete file content with YAML frontmatter)",
        "---END FILE---",
        "```",
        "",
        "REVIEW block template (optional, after all FILE blocks):",
        "```",
        "---REVIEW: type | Title---",
        "Description of what needs the user's attention.",
        "OPTIONS: Create Page | Skip",
        "PAGES: wiki/page1.md, wiki/page2.md",
        "SEARCH: query 1 | query 2 | query 3",
        "---END REVIEW---",
        "```",
        "",
        "## Output Requirements (STRICT — deviations will cause parse failure)",
        "",
        "1. The FIRST character of your response MUST be `-` (the opening of `---FILE:`).",
        "2. DO NOT output any preamble such as \"Here are the files:\", \"Based on the analysis...\", or any introductory prose.",
        "3. DO NOT echo or restate the analysis — that was stage 1's job. Your job is to emit FILE blocks.",
        "4. DO NOT output markdown tables, bullet lists, or headings outside of FILE/REVIEW blocks.",
        "5. DO NOT output any trailing commentary after the last `---END FILE---` or `---END REVIEW---`.",
        "6. Between blocks, use only blank lines — no prose.",
        "7. EVERY FILE block's content (titles, body, descriptions) MUST be in the mandatory output language specified below. No exceptions — not even for page names or section headings.",
        "",
        "If you start with anything other than `---FILE:`, the entire response will be discarded.",
        "",
        "---",
        "",
        AiPromptTemplates.languageDirective(language),
    ).filter(String::isNotEmpty).joinToString("\n")

    fun generationUserMessage(
        sourceIdentity: String,
        analysis: String,
        sourceContext: String,
    ): String = listOf(
        "Source document to process: **$sourceIdentity**",
        "",
        "The Stage 1 analysis below is CONTEXT to inform your output. Do NOT echo",
        "its tables, bullet points, or prose. Your output must be FILE/REVIEW",
        "blocks as specified in the system prompt — nothing else.",
        "",
        "## Stage 1 Analysis (context only — do not repeat)",
        "",
        analysis,
        "",
        "## Source Context",
        "",
        sourceContext,
        "",
        "---",
        "",
        "Now emit the FILE blocks for the wiki files derived from **$sourceIdentity**.",
        "Your response MUST begin with `---FILE:` as the very first characters.",
        "No preamble. No analysis prose. Start immediately.",
    ).joinToString("\n")

    fun chunkAnalysisSystemPrompt(
        purpose: String,
        schema: String,
        index: String,
        language: String,
    ): String = listOf(
        "You are analyzing a long source document for a personal wiki.",
        "Do not output chain-of-thought, hidden reasoning, or a thinking transcript.",
        "Analyze only the current MAIN CHUNK. Use overlap and digest for context only.",
        "Keep stable names consistent with the existing wiki and prior digest.",
        "",
        AiPromptTemplates.languageDirective(language),
        "",
        "Output exactly two markdown sections:",
        "",
        "## Chunk Analysis",
        "- Concise summary of the main chunk",
        "- New or updated entities",
        "- New or updated concepts",
        "- Claims, findings, evidence, contradictions",
        "- Open questions or research gaps",
        "",
        "## Updated Global Digest",
        "A compact document-level digest that incorporates this chunk and preserves prior cross-chunk context.",
        "Keep this digest structured under: Summary, Entities, Concepts, Claims, Evidence, Contradictions, Open Questions, Cross-Chunk Relations.",
        "",
        "Stable project context follows. It changes rarely and should be treated as background:",
        purpose.takeIf(String::isNotBlank)?.let { "## Wiki Purpose\n$it" }.orEmpty(),
        schema.takeIf(String::isNotBlank)?.let { "## Wiki Schema\n$it" }.orEmpty(),
        index.takeIf(String::isNotBlank)?.let { "## Current Wiki Index\n${trimLongText(it, 40_000)}" }.orEmpty(),
    ).filter(String::isNotEmpty).joinToString("\n")

    fun chunkAnalysisUserPrompt(
        sourceIdentity: String,
        folderContext: String?,
        chunk: SourceChunk,
        globalDigest: String,
    ): String = listOf(
        "Source file: $sourceIdentity",
        folderContext?.takeIf(String::isNotBlank)?.let { "Folder context: $it" }.orEmpty(),
        "Chunk: ${chunk.index}/${chunk.total}",
        chunk.headingPath.takeIf(String::isNotBlank)?.let { "Heading path: $it" }.orEmpty(),
        "",
        "## Current Global Digest",
        globalDigest.ifBlank { "(No prior digest yet.)" },
        "",
        chunk.overlapBefore.takeIf(String::isNotBlank)
            ?.let { "## Previous Overlap Context\n$it" }.orEmpty(),
        "",
        "## MAIN CHUNK TO ANALYZE",
        chunk.main,
        "",
        "Return only the two requested sections. Do not repeat overlap-only facts unless the main chunk supports them.",
    ).filter(String::isNotEmpty).joinToString("\n")

    fun splitSourceIntoSemanticChunks(
        content: String,
        targetChars: Int,
        overlapChars: Int,
    ): List<SourceChunk> {
        val target = maxOf(1_000, targetChars)
        val blocks = semanticBlocks(content, target)
        if (blocks.isEmpty()) return emptyList()

        data class RawChunk(val main: String, val headingPath: String)
        val rawChunks = mutableListOf<RawChunk>()
        var current = mutableListOf<String>()
        var currentLength = 0
        var currentHeading = blocks.first().second

        fun flush() {
            val main = current.joinToString("\n\n").trim()
            if (main.isNotBlank()) rawChunks += RawChunk(main, currentHeading)
            current = mutableListOf()
            currentLength = 0
        }

        blocks.forEach { (text, headingPath) ->
            val nextLength = currentLength + text.length + if (current.isNotEmpty()) 2 else 0
            if (current.isNotEmpty() && nextLength > target) flush()
            if (current.isEmpty()) currentHeading = headingPath
            current += text
            currentLength += text.length + if (current.size > 1) 2 else 0
        }
        flush()

        return rawChunks.mapIndexed { index, chunk ->
            SourceChunk(
                id = "chunk-${index + 1}",
                index = index + 1,
                total = rawChunks.size,
                headingPath = chunk.headingPath,
                overlapBefore = if (index > 0) overlapSuffix(rawChunks[index - 1].main, overlapChars) else "",
                main = chunk.main,
            )
        }
    }

    private fun semanticBlocks(content: String, targetChars: Int): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        val headingStack = mutableListOf<String>()
        var paragraph = mutableListOf<String>()
        var paragraphHeading = ""

        fun headingPath(): String = headingStack.filter(String::isNotBlank).joinToString(" > ")
        fun flushParagraph() {
            val text = paragraph.joinToString("\n").trim()
            if (text.isNotBlank()) {
                splitOversizedBlock(text, targetChars).forEach { blocks += it to paragraphHeading }
            }
            paragraph = mutableListOf()
        }

        content.replace("\r\n", "\n").split('\n').forEach { line ->
            val heading = Regex("^(#{1,6})\\s+(.+?)\\s*$").matchEntire(line)
            if (heading != null) {
                flushParagraph()
                val depth = heading.groupValues[1].length
                while (headingStack.size > depth - 1) headingStack.removeAt(headingStack.lastIndex)
                while (headingStack.size < depth - 1) headingStack += ""
                if (headingStack.size == depth - 1) headingStack += heading.groupValues[2].trim()
                else headingStack[depth - 1] = heading.groupValues[2].trim()
                blocks += line.trim() to headingPath()
                paragraphHeading = headingPath()
            } else if (line.isBlank()) {
                flushParagraph()
                paragraphHeading = headingPath()
            } else {
                if (paragraph.isEmpty()) paragraphHeading = headingPath()
                paragraph += line
            }
        }
        flushParagraph()
        return blocks
    }

    private fun splitOversizedBlock(block: String, targetChars: Int): List<String> {
        if (block.length <= targetChars * 1.25) return listOf(block)
        val pieces = Regex("[^.!?。！？\\n]+[.!?。！？]?|\\n+")
            .findAll(block).map { it.value }.toList().ifEmpty { listOf(block) }
        val out = mutableListOf<String>()
        var current = ""
        pieces.forEach { piece ->
            if (current.isNotBlank() && current.length + piece.length > targetChars) {
                out += current.trim()
                current = ""
            }
            if (piece.length > targetChars) {
                piece.chunked(targetChars).map(String::trim).filter(String::isNotBlank).forEach(out::add)
            } else {
                current += piece
            }
        }
        if (current.isNotBlank()) out += current.trim()
        return out
    }

    private fun overlapSuffix(text: String, maxChars: Int): String {
        if (text.isBlank() || maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val raw = text.takeLast(maxChars)
        val paragraph = Regex("\\n\\s*\\n").find(raw)?.range?.first ?: -1
        if (paragraph > 0 && raw.length - paragraph > maxChars * 0.4) {
            return raw.substring(paragraph).trim()
        }
        val sentence = Regex("[.!?。！？]\\s+").find(raw)?.range?.first ?: -1
        if (sentence > 0 && raw.length - sentence > maxChars * 0.4) {
            return raw.substring(sentence + 1).trim()
        }
        return raw.trim()
    }

    fun extractMarkedSection(raw: String, heading: String): String {
        val escaped = Regex.escape(heading)
        return Regex("(?:^|\\n)##\\s+$escaped\\s*\\n([\\s\\S]*?)(?=\\n##\\s|$)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun consolidatedLongAnalysis(
        sourceIdentity: String,
        chunks: List<SourceChunk>,
        analyses: List<String>,
        globalDigest: String,
        sourceBudget: Int,
    ): Pair<String, String> {
        val labeled = analyses.mapIndexed { index, analysis ->
            val chunk = chunks[index]
            "## Chunk ${chunk.index}/${chunk.total}" +
                (if (chunk.headingPath.isNotBlank()) " — ${chunk.headingPath}" else "") +
                "\n${trimLongText(analysis, 40_000)}"
        }
        val fullAnalysis = listOf(
            "# Consolidated Long-Document Analysis",
            "",
            "## Final Global Digest",
            globalDigest.ifBlank { "(No digest produced.)" },
            "",
            "## Per-Chunk Analyses",
            labeled.joinToString("\n\n"),
        ).joinToString("\n")
        val sourceContext = listOf(
            "# Long Source Context: $sourceIdentity",
            "",
            "The original source was analyzed in ${chunks.size} semantic chunks with paragraph/section boundaries and overlap. Use this consolidated context instead of assuming the raw document ended early.",
            "",
            "## Final Global Digest",
            globalDigest.ifBlank { "(No digest produced.)" },
            "",
            "## Chunk Analysis Notes",
            trimLongText(labeled.joinToString("\n\n"), maxOf(sourceBudget, 40_000)),
        ).joinToString("\n")
        return fullAnalysis to sourceContext
    }

    fun trimLongText(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text
        else "${text.take(maxChars).trimEnd()}\n\n[...trimmed for prompt budget...]"

    fun bodyLength(markdown: String): Int = splitFrontmatter(markdown).second.length

    fun hasFrontmatter(markdown: String): Boolean = splitFrontmatter(markdown).first != null

    fun acceptsLlmMerge(existing: String, incoming: String, candidate: String): Boolean {
        if (!hasFrontmatter(candidate)) return false
        val threshold = maxOf(bodyLength(existing), bodyLength(incoming)) * BODY_SHRINK_THRESHOLD
        return bodyLength(candidate) >= threshold
    }

    fun utcToday(): String =
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun splitFrontmatter(markdown: String): Pair<String?, String> {
        val normalized = markdown.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null to normalized
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) return null to normalized
        val bodyStart = (end + 4).coerceAtMost(normalized.length)
        return normalized.substring(0, bodyStart) to normalized.substring(bodyStart).trimStart('\n')
    }
}
