package com.my.knowledge.data.ingest

/**
 * Parser for LLM review block output format.
 *
 * 1:1 alignment with llm_wiki's `parseReviewBlocks` (src/lib/ingest.ts:909-972).
 *
 * Format:
 * ```
 * ---REVIEW: type | Title---
 * Description
 * OPTIONS: Create Page | Skip
 * PAGES: wiki/page1.md, wiki/page2.md
 * SEARCH: keyword1 | keyword2 | keyword3
 * ---END REVIEW---
 * ```
 *
 * The recognised `type` set is `contradiction | duplicate | missing-page |
 * suggestion`; anything else is bucketed into a `confirm` fallback so the
 * rest of the parser still works.
 */
object ReviewBlockParser {
    data class ParsedReview(
        val type: String,
        val title: String,
        val description: String,
        val options: List<String>,
        val affectedPages: List<String>,
        val searchQueries: List<String> = emptyList()
    )

    private val KNOWN_TYPES = setOf("contradiction", "duplicate", "missing-page", "suggestion")

    fun parse(text: String): List<ParsedReview> {
        val normalized = text.replace("\r\n", "\n")
        val blocks = mutableListOf<ParsedReview>()
        val regex = Regex("---REVIEW:\\s*([A-Za-z][\\w-]*)\\s*\\|\\s*(.+?)\\s*---\\n([\\s\\S]*?)---END REVIEW---")

        regex.findAll(normalized).forEach { match ->
            val rawType = match.groupValues[1].trim().lowercase()
            val type = if (rawType in KNOWN_TYPES) rawType else "confirm"
            val title = match.groupValues[2].trim()
            val body = match.groupValues[3].trim()

            val optionsLine = body.lines().firstOrNull { it.startsWith("OPTIONS:") }
            val options = optionsLine
                ?.removePrefix("OPTIONS:")
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: listOf("Approve", "Skip")

            val pagesLine = body.lines().firstOrNull { it.startsWith("PAGES:") }
            val pages = pagesLine
                ?.removePrefix("PAGES:")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            // SEARCH is a list of web-search queries; the system adds a
            // "Deep Research" affordance on suggestion / missing-page
            // reviews, so we capture this even though it doesn't
            // currently drive a button on the Android side.
            val searchLine = body.lines().firstOrNull { it.startsWith("SEARCH:") }
            val searchQueries = searchLine
                ?.removePrefix("SEARCH:")
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            val description = body
                .replace(Regex("^OPTIONS:.*$", RegexOption.MULTILINE), "")
                .replace(Regex("^PAGES:.*$", RegexOption.MULTILINE), "")
                .replace(Regex("^SEARCH:.*$", RegexOption.MULTILINE), "")
                .trim()

            blocks.add(ParsedReview(type, title, description, options, pages, searchQueries))
        }

        return blocks
    }
}
