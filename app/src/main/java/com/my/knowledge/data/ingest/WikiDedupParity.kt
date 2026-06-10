package com.my.knowledge.data.ingest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WikiEntitySummary(
    val slug: String,
    val path: String,
    val type: String,
    val title: String,
    val description: String?,
    val tags: List<String>,
)

data class WikiDuplicateGroup(
    val slugs: List<String>,
    val reason: String,
    val confidence: String,
)

data class WikiDedupPage(val slug: String, val path: String, val content: String)
data class WikiDedupRewrite(val path: String, val newContent: String)
data class WikiDedupBackup(val path: String, val content: String)

data class WikiDedupMergeResult(
    val canonicalContent: String,
    val canonicalPath: String,
    val rewrites: List<WikiDedupRewrite>,
    val pagesToDelete: List<String>,
    val backup: List<WikiDedupBackup>,
)

object WikiDedupParity {
    val detectorSystemPrompt = """
        You are a wiki maintenance assistant. You will receive a list of entity / concept pages from a wiki. Identify groups of slugs that likely refer to the same underlying topic under different names — for example:

        - Same name in two languages (English vs Chinese, etc.)
        - Plural vs singular form (e.g. "dpao" vs "dpaos")
        - Abbreviation vs full form (e.g. "vfa" vs "volatile-fatty-acids")
        - Synonyms in the same language
        - The same proper noun spelled differently

        Output ONLY valid JSON. No prose, no markdown fences, no explanation outside the JSON. The schema is:

        {"groups":[{"slugs":["slug-a","slug-b"],"reason":"Both refer to X.","confidence":"high"}]}

        Rules:
        - Only include groups of 2 or more slugs from the input list.
        - "high" = clearly the same entity, only naming differs.
        - "medium" = likely the same but context-dependent.
        - "low" = uncertain; user should review carefully.
        - Never invent slugs that aren't in the input.
        - If no duplicates exist, output {"groups": []}.
        - Pages of different `type` usually should NOT be grouped unless unambiguously identical.
    """.trimIndent()

    val mergerSystemPrompt = """
        You are a wiki maintenance assistant. You will be given several wiki pages that all describe the same entity or concept under different names. Merge them into a single coherent wiki page.

        Output the COMPLETE merged file (frontmatter + body). The first character of your response MUST be "-" (the opening of "---"). No preamble, no explanation outside the file.

        Rules:
        - Preserve every distinct factual claim from every input page.
        - Eliminate redundancy.
        - Reorganize sections into a logical unified topic.
        - Keep [[wikilink]] references intact.
        - Keep standard frontmatter fields. The caller deterministically unions sources / tags / related / updated.
        - Pick the most descriptive title, using the majority body language.
    """.trimIndent()

    fun extractEntitySummary(path: String, content: String): WikiEntitySummary? {
        val frontmatter = frontmatterLines(content) ?: return null
        val body = stripFrontmatter(content)
        val slug = path.substringAfterLast('/').removeSuffix(".md")
        val title = scalar(frontmatter, "title") ?: slug
        val description = scalar(frontmatter, "description")
            ?: body.lineSequence().map(String::trim)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("|") }
        return WikiEntitySummary(
            slug = slug,
            path = path,
            type = scalar(frontmatter, "type") ?: "unknown",
            title = title,
            description = description?.let { if (it.length <= 200) it else it.take(199) + "…" },
            tags = array(frontmatter, "tags"),
        )
    }

    fun detectorUserMessage(summaries: List<WikiEntitySummary>): String {
        val lines = summaries.map { summary ->
            val tags = summary.tags.takeIf(List<String>::isNotEmpty)
                ?.joinToString(", ", prefix = " [", postfix = "]").orEmpty()
            val description = summary.description?.let { " — $it" }.orEmpty()
            "- type=${summary.type}, slug=${summary.slug}, title=${JSONObject.quote(summary.title)}$tags$description"
        }
        return "## Wiki pages to scan (${summaries.size} entries)\n\n" +
            lines.joinToString("\n") +
            "\n\nReturn duplicate groups as JSON only."
    }

    fun parseDetectorResponse(
        raw: String,
        validSlugs: Set<String>,
        notDuplicates: List<List<String>> = emptyList(),
    ): List<WikiDuplicateGroup> {
        val json = extractFirstJsonObject(raw)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        val blocked = notDuplicates.map(::normalizeGroupKey).toSet()
        val groups = json.optJSONArray("groups") ?: return emptyList()
        return buildList {
            for (index in 0 until groups.length()) {
                val item = groups.optJSONObject(index) ?: continue
                val slugs = item.optJSONArray("slugs").stringValues().filter(validSlugs::contains)
                if (slugs.size < 2 || normalizeGroupKey(slugs) in blocked) continue
                val confidence = item.optString("confidence")
                    .takeIf { it == "high" || it == "medium" } ?: "low"
                add(WikiDuplicateGroup(slugs, item.optString("reason"), confidence))
            }
        }
    }

    fun mergerUserMessage(group: List<WikiDedupPage>): String {
        val pages = group.mapIndexed { index, page ->
            "## Page ${index + 1} (slug: ${page.slug})\n\n${page.content}\n"
        }
        return "These ${group.size} wiki pages have been confirmed by the user to describe the same topic.\n" +
            "Merge them into a single coherent page (the canonical slug will be \"${group.first().slug}\" or whichever the caller chose).\n\n" +
            pages.joinToString("\n---\n\n") +
            "\n\nNow output the merged file. First character must be `-`."
    }

    fun mergeConfirmedGroup(
        group: List<WikiDedupPage>,
        canonicalSlug: String,
        otherWikiPages: List<WikiDedupPage>,
        llmOutput: String,
    ): WikiDedupMergeResult {
        require(group.size >= 2) { "mergeConfirmedGroup requires at least 2 pages" }
        val canonical = group.firstOrNull { it.slug == canonicalSlug }
            ?: error("canonicalSlug \"$canonicalSlug\" is not in the group")
        var merged = llmOutput
        group.forEach { page ->
            merged = unionFrontmatterArrays(merged, page.content)
        }
        merged = setFrontmatterScalar(merged, "updated", IngestParityCore.utcToday())
        val redirects = group.filterNot { it.slug == canonicalSlug }
            .associate { it.slug to canonicalSlug }
        val rewrites = otherWikiPages.mapNotNull { page ->
            val rewritten = rewriteCrossReferences(page.content, redirects)
            if (rewritten == page.content) null else WikiDedupRewrite(page.path, rewritten)
        }
        val backup = group.map { WikiDedupBackup(it.path, it.content) } +
            rewrites.mapNotNull { rewrite ->
                otherWikiPages.firstOrNull { it.path == rewrite.path }
                    ?.let { WikiDedupBackup(it.path, it.content) }
            }
        return WikiDedupMergeResult(
            canonicalContent = merged,
            canonicalPath = canonical.path,
            rewrites = rewrites,
            pagesToDelete = group.filterNot { it.slug == canonicalSlug }.map { it.path },
            backup = backup,
        )
    }

    fun rewriteCrossReferences(content: String, redirects: Map<String, String>): String {
        var out = content
        redirects.forEach { (oldSlug, newSlug) ->
            out = Regex("\\[\\[${Regex.escape(oldSlug)}(\\|[^\\]]+)?\\]\\]")
                .replace(out) { match -> "[[$newSlug${match.groupValues[1]}]]" }
        }
        val lines = frontmatterLines(out) ?: return out
        val related = array(lines, "related")
        if (related.isEmpty()) return out
        val rewritten = related.map { redirects[it] ?: it }
            .distinctBy(String::lowercase)
        return writeFrontmatterArray(out, "related", rewritten)
    }

    fun rewriteIndex(content: String, removedSlugs: Set<String>): String =
        content.lineSequence().filterNot { line ->
            removedSlugs.any { slug ->
                val escaped = Regex.escape(slug)
                Regex("\\[\\[$escaped(\\|[^\\]]*)?\\]\\]").containsMatchIn(line) ||
                    Regex("\\(([^)]*/)?$escaped\\.md\\)").containsMatchIn(line) ||
                    Regex("\\b$escaped\\.md\\b").containsMatchIn(line)
            }
        }.joinToString("\n")

    fun normalizeGroupKey(slugs: List<String>): String =
        slugs.map(String::lowercase).sorted().joinToString(",")

    private fun unionFrontmatterArrays(primary: String, reference: String): String {
        var out = primary
        listOf("sources", "tags", "related").forEach { key ->
            val values = (array(frontmatterLines(out).orEmpty(), key) +
                array(frontmatterLines(reference).orEmpty(), key))
                .distinctBy(String::lowercase)
            out = writeFrontmatterArray(out, key, values)
        }
        return out
    }

    private fun frontmatterLines(content: String): List<String>? {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null
        val end = normalized.indexOf("\n---", 4)
        if (end < 0) return null
        return normalized.substring(4, end).lines()
    }

    private fun stripFrontmatter(content: String): String {
        val normalized = content.replace("\r\n", "\n")
        val end = normalized.indexOf("\n---", 4)
        return if (!normalized.startsWith("---\n") || end < 0) normalized
        else normalized.substring((end + 4).coerceAtMost(normalized.length)).trimStart('\n')
    }

    private fun scalar(lines: List<String>, key: String): String? =
        lines.firstOrNull { it.substringBefore(':').trim() == key }
            ?.substringAfter(':')?.trim()?.trim('"', '\'')?.takeIf(String::isNotBlank)

    private fun array(lines: List<String>, key: String): List<String> {
        val inline = lines.firstOrNull { it.substringBefore(':').trim() == key }
            ?.substringAfter(':')?.trim().orEmpty()
        if (inline.startsWith("[") && inline.endsWith("]")) {
            return inline.removeSurrounding("[", "]").split(',')
                .map { it.trim().trim('"', '\'') }.filter(String::isNotBlank)
        }
        val index = lines.indexOfFirst { it.substringBefore(':').trim() == key }
        if (index < 0) return emptyList()
        return lines.drop(index + 1).takeWhile { it.trimStart().startsWith("- ") }
            .map { it.trim().removePrefix("- ").trim('"', '\'') }.filter(String::isNotBlank)
    }

    private fun writeFrontmatterArray(content: String, key: String, values: List<String>): String {
        val rendered = "$key: [${values.joinToString(", ")}]"
        val line = Regex("^${Regex.escape(key)}:\\s*.*$", RegexOption.MULTILINE)
        if (line.containsMatchIn(content)) return line.replace(content, rendered)
        val closing = content.indexOf("\n---", 4)
        return if (closing < 0) content else content.substring(0, closing) +
            "\n$rendered" + content.substring(closing)
    }

    private fun setFrontmatterScalar(content: String, key: String, value: String): String {
        val line = Regex("^${Regex.escape(key)}:\\s*(?!\\[).*$", RegexOption.MULTILINE)
        if (line.containsMatchIn(content)) return line.replace(content, "$key: $value")
        val closing = content.indexOf("\n---", 4)
        return if (closing < 0) content else content.substring(0, closing) +
            "\n$key: $value" + content.substring(closing)
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (index in start until text.length) {
            val char = text[index]
            if (escape) {
                escape = false
                continue
            }
            if (char == '\\') {
                escape = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (char == '{') depth++
            if (char == '}' && --depth == 0) return text.substring(start, index + 1)
        }
        return null
    }

    private fun JSONArray?.stringValues(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }
}

class WikiDedupWhitelistStore(rootDir: File) {
    private val file = File(rootDir, ".my-knowledge/dedup-not-duplicates.json")

    fun load(): List<List<String>> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            array.optJSONArray(index)?.let { group ->
                (0 until group.length()).mapNotNull { group.optString(it).takeIf(String::isNotBlank) }
            }
        }
    }.getOrDefault(emptyList())

    fun add(slugs: List<String>) {
        if (slugs.size < 2) return
        val current = load().toMutableList()
        val key = WikiDedupParity.normalizeGroupKey(slugs)
        if (current.any { WikiDedupParity.normalizeGroupKey(it) == key }) return
        current += slugs.sorted()
        file.parentFile?.mkdirs()
        file.writeText(JSONArray(current.map(::JSONArray)).toString(2))
    }
}
