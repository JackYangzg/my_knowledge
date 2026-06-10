package com.my.knowledge.data.ingest

/**
 * Pre-write sanitizer for LLM-generated wiki page content.
 *
 * 1:1 port of llm_wiki/src/lib/ingest-sanitize.ts. Runs in
 * [WikiPageCompiler] before any file hits disk so we don't have
 * to paper over corrupt frontmatter at read time forever.
 *
 * Four patterns, applied in order:
 *
 *   1. stripOuterCodeFence       — drop ```yaml / ```md / ``` ... ```
 *                                  wrapping the whole document
 *   2. stripFrontmatterKeyPrefix — drop leading `frontmatter:` key
 *                                  that prefixes the real `---` block
 *   2.5 addMissingOpeningFence   — when model emits frontmatter lines
 *                                  but forgot the opening `---`, add one
 *   3. repairWikilinkLists       — fix `key: [[a]], [[b]], [[c]]` to
 *                                  `key: ["a", "b", "c"]` inside frontmatter
 *
 * Conservative: each pattern is anchored at the document start (or at
 * the top-level frontmatter scope), so a legitimate fenced code block
 * deep in the body is untouched.
 */
object Sanitize {

    fun sanitize(content: String): String {
        var c = stripOuterCodeFence(content)
        c = stripFrontmatterKeyPrefix(c)
        c = addMissingOpeningFrontmatterFence(c)
        c = repairWikilinkListsInFrontmatter(c)
        return c
    }

    // (1) Strip outer ```yaml / ```md / ``` ... ``` wrapper.
    //     TS: /^[ \t]*```(?:yaml|md|markdown)?[ \t]*\r?\n/ + close variant
    //     Kotlin: content is already \r\n-normalized by the caller,
    //     so we use raw \n in the regex.
    //     The closer regex stops at `\n` before the ``` so the
    //     content between them is kept, and the closing `\n` after
    //     the closer is preserved by anchoring at line start + lookahead.
    private fun stripOuterCodeFence(c: String): String {
        val open = Regex("""^[ \t]*```(?:yaml|md|markdown)?[ \t]*\n""").find(c) ?: return c
        val afterOpen = c.substring(open.range.last + 1)
        // Match the closer line (newline + ``` + optional spaces) without
        // consuming any trailing newline that comes after.
        val close = Regex("""\n[ \t]*```[ \t]*(?=\n)""").find(afterOpen) ?: return c
        // Keep everything up to (not including) the closer's leading newline,
        // then re-append a single trailing newline if the original had one.
        val innerEnd = close.range.first
        val inner = afterOpen.substring(0, innerEnd)
        val suffix = afterOpen.substring(close.range.last + 1)  // everything after ```
        return if (suffix.startsWith("\n")) inner + "\n" else inner + suffix
    }

    // (2) Strip leading `frontmatter:` line that prefixes the real
    //     `---` block. Some prompts make the model emit a YAML doc
    //     with a `frontmatter` key instead of a markdown frontmatter
    //     block.
    private fun stripFrontmatterKeyPrefix(c: String): String {
        val m = Regex("""^[ \t]*frontmatter\s*:\s*\n(?=[ \t]*---\s*\n)""").find(c) ?: return c
        return c.substring(m.range.last + 1)
    }

    // (2.5) Repair missing opening frontmatter fence.
    //     When the LLM starts with `type: foo` followed by content
    //     and a closing `---` (no opening), prepend `---\n`.
    private fun addMissingOpeningFrontmatterFence(c: String): String {
        if (Regex("""^[ \t]*---\s*(\n|$)""").containsMatchIn(c)) return c
        val lines = c.split("\n")
        val firstContentIdx = lines.indexOfFirst { it.trim().isNotEmpty() }
        if (firstContentIdx < 0) return c
        val first = lines[firstContentIdx].trim()
        if (!Regex("""^(type|title|created|updated|tags|related|sources)\s*:""", RegexOption.IGNORE_CASE)
                .containsMatchIn(first)) {
            return c
        }
        val searchEnd = minOf(lines.size, firstContentIdx + 30)
        for (i in (firstContentIdx + 1) until searchEnd) {
            if (lines[i].trim() == "---") return "---\n" + lines.subList(firstContentIdx, lines.size).joinToString("\n")
            if (Regex("""^#{1,6}\s+""").containsMatchIn(lines[i])) break
        }
        return c
    }

    // (3) Repair `key: [[a]], [[b]], [[c]]` inside the frontmatter
    //     block to `key: ["a", "b", "c"]` (valid YAML flow syntax).
    //     Body wikilinks are left alone.
    private fun repairWikilinkListsInFrontmatter(c: String): String {
        val m = Regex("""^---\s*\n([\s\S]*?)\n---\s*(\n|$)""").find(c) ?: return c
        val payload = m.groupValues[1]
        val repaired = payload.split("\n").joinToString("\n") { line ->
            val lm = Regex("""^(\s*[A-Za-z_][\w-]*\s*:\s*)(\[\[[^\]]+\]\](?:\s*,\s*\[\[[^\]]+\]\])+)\s*$""")
                .find(line) ?: return@joinToString line
            val items = lm.groupValues[2].split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // Strip the wikilink brackets: [[a]] → a
                .map { it.removePrefix("[[").removeSuffix("]]") }
                .joinToString(", ") { "\"$it\"" }
            "${lm.groupValues[1]}[$items]"
        }
        return c.substring(0, m.range.first + 4) + repaired + c.substring(m.range.first + 4 + m.groupValues[1].length)
    }
}