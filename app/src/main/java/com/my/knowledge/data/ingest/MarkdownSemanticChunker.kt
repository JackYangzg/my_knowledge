package com.my.knowledge.data.ingest

/**
 * Markdown-aware recursive chunker for long source analysis.
 *
 * Mirrors the contract of `src/lib/text-chunker.ts` and
 * `splitSourceIntoSemanticChunks` in `src/lib/ingest.ts` from llm_wiki
 * so the Kotlin ingest pipeline splits long documents on the same
 * boundaries the JS pipeline does. The non-goal is byte-for-byte
 * parity — the goal is "split on the same semantic units" so a Chinese
 * PDF imported into both systems chunks at the same paragraph /
 * heading / code-block boundaries.
 *
 * Design constraints (each one has at least one test in
 * `MarkdownSemanticChunkerTest`):
 *
 *  1. Split priority (markdown-tuned recursive splitter):
 *        (a) heading-defined sections (#, ##, ###, …, ######)
 *        (b) paragraph boundaries (`\n\n` / `\r\n\r\n`)
 *        (c) line breaks (`\n`)
 *        (d) sentence terminators (`. ` / `。` / `! ` / `！` / `? `
 *            / `？` / `; ` / `；`)
 *        (e) whitespace (` ` / `　` / `\t`)
 *        (f) hard char slice (last resort)
 *     Each level only kicks in when the level above produces a piece
 *     that still exceeds `maxChars`.
 *
 *  2. Never splits inside a fenced code block (``` … ``` / ~~~ … ~~~).
 *     A code block larger than `maxChars` becomes one oversized chunk
 *     on its own rather than being torn.
 *
 *  3. Never splits inside a single markdown table (consecutive `|`
 *     lines). Tables over `maxChars` are kept intact for the same
 *     reason — tearing a table produces semantic garbage.
 *
 *  4. Heading path breadcrumb (e.g. "## Intro > ### Usage") is
 *     carried on every emitted chunk so the downstream LLM prompt can
 *     see structural context for a short chunk that would otherwise
 *     be ambiguous on its own.
 *
 *  5. Overlap is applied as a tail-suffix of the previous chunk
 *     (`SourceChunk.overlapBefore`) so a concept that spans a chunk
 *     boundary still has the tail of the prior chunk for context.
 *     Overlap is snapped to a paragraph / sentence boundary when
 *     possible (mirroring `overlapSuffix` in llm_wiki's ingest.ts).
 *
 *  6. Pure & deterministic: same input ⇒ same output. No randomness,
 *     no I/O, no singleton state. Unit tests verify this with fixed
 *     input strings.
 *
 *  7. Indivisible units (oversized code / table / paragraph that
 *     can't be broken by sentence rules) flow through as their own
 *     chunk; we don't drop them on the floor. The orchestrator's
 *     source-budget check upstream guarantees the LLM gets enough
 *     context headroom to accept the oversize.
 */
class MarkdownSemanticChunker(
    private val targetChars: Int = 4_000,
    private val maxChars: Int = 6_000,
    private val minChars: Int = 800,
    private val overlapChars: Int = 400
) {
    init {
        require(targetChars > 0) { "targetChars must be > 0" }
        require(minChars > 0) { "minChars must be > 0" }
        require(overlapChars >= 0) { "overlapChars must be >= 0" }
        require(maxChars >= targetChars) {
            "maxChars ($maxChars) must be >= targetChars ($targetChars)"
        }
    }

    /**
     * Split the markdown [content] into a list of [SourceChunk]s.
     *
     * @return ordered chunks with stable 1-based [SourceChunk.index]
     *   and the matching [SourceChunk.total]. An empty / blank input
     *   returns an empty list.
     */
    fun split(content: String): List<SourceChunk> {
        if (content.isBlank()) return emptyList()
        val normalized = content.replace("\r\n", "\n")
        val sections = splitIntoSections(normalized)
        if (sections.isEmpty()) return emptyList()

        // Pack sections into chunks no larger than targetChars.
        val rawPacked = packSections(sections, targetChars)

        // Merge tiny chunks (below minChars) into their next sibling
        // when the combined size still fits in maxChars. Mirrors
        // `mergeSmall` in text-chunker.ts.
        val merged = mergeSmall(rawPacked, minChars, maxChars)

        // Apply overlap: prepend a tail-suffix of the previous chunk
        // so concepts that span a boundary aren't torn. The overlap
        // is "before" the current chunk, exposed via
        // [SourceChunk.overlapBefore] so the prompt can render it
        // separately from the main chunk text.
        val withOverlap = applyOverlap(merged, overlapChars)

        // Final pass: assign 1-based index/total and stable ids.
        val total = withOverlap.size
        return withOverlap.mapIndexed { i, piece ->
            SourceChunk(
                id = "chunk-${i + 1}",
                index = i + 1,
                total = total,
                headingPath = piece.headingPath,
                overlapBefore = piece.overlapBefore,
                main = piece.text
            )
        }
    }

    // ── Section segmentation ──────────────────────────────────────────

    /**
     * Walk the document line by line, tracking the current heading
     * stack. A heading line cuts a new section; blank lines cut
     * paragraphs but stay inside the same section.
     *
     * Fenced code blocks are treated as opaque — a line starting
     * with ``` toggles "inside-fence" mode and no headings inside
     * that block trigger a section cut. Same for ``` or ~~~.
     */
    private fun splitIntoSections(body: String): List<Section> {
        val lines = body.split("\n")
        val sections = mutableListOf<Section>()
        // heading stack keyed by level: headings[lvl] = text of last
        // heading at that level. Deeper levels are cleared when a
        // shallower heading appears (a level-2 heading resets
        // level-3, level-4, …).
        val headings = mutableMapOf<Int, String>()

        var currentLines = mutableListOf<String>()
        var currentStart = 0
        var currentHeading = ""
        var inFence = false
        var fenceMarker = ""
        var cursor = 0

        fun flush() {
            val text = currentLines.joinToString("\n")
            if (text.isNotBlank()) {
                sections += Section(
                    text = text,
                    bodyStart = currentStart,
                    headingPath = currentHeading
                )
            }
        }

        for (i in lines.indices) {
            val line = lines[i]
            val lineLen = line.length + if (i < lines.size - 1) 1 else 0

            // Fenced code block detection (```` or ~~~). Inside a
            // fence nothing else matters — we just accumulate the
            // line and move on.
            val fenceMatch = FENCE_OPEN.matchEntire(line.trimStart())
            if (fenceMatch != null) {
                if (!inFence) {
                    inFence = true
                    fenceMarker = fenceMatch.groupValues[1]
                } else if (line.trim() == fenceMarker) {
                    inFence = false
                }
                if (currentLines.isEmpty()) currentStart = cursor
                currentLines.add(line)
                cursor += lineLen
                continue
            }
            if (inFence) {
                if (currentLines.isEmpty()) currentStart = cursor
                currentLines.add(line)
                cursor += lineLen
                continue
            }

            // Heading detection (outside fences only).
            val hMatch = HEADING.matchEntire(line)
            if (hMatch != null) {
                flush()
                currentLines = mutableListOf()
                val level = hMatch.groupValues[1].length
                val title = hMatch.groupValues[2].trim()
                headings[level] = title
                for (lvl in (level + 1)..6) headings.remove(lvl)
                currentStart = cursor
                currentHeading = renderHeadingPath(headings)
                currentLines.add(line)
                cursor += lineLen
                continue
            }

            if (currentLines.isEmpty()) currentStart = cursor
            currentLines.add(line)
            cursor += lineLen
        }
        flush()
        return sections
    }

    private fun renderHeadingPath(headings: Map<Int, String>): String {
        val parts = mutableListOf<String>()
        for (lvl in 1..6) {
            headings[lvl]?.takeIf { it.isNotBlank() }?.let { parts.add("${"#".repeat(lvl)} $it") }
        }
        return parts.joinToString(" > ")
    }

    // ── Atom tokenization ──────────────────────────────────────────────

    /**
     * Convert a section's text into "atoms" — indivisible units
     * (fenced code, table) or further-splittable paragraphs. Each
     * atom knows its offset inside the section so we can re-attach
     * a heading breadcrumb.
     */
    private fun tokenizeAtoms(sectionText: String): List<Atom> {
        val atoms = mutableListOf<Atom>()
        val lines = sectionText.split("\n")
        var cursor = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block (3+ backticks or tildes, opening
            // line; we accept anything that *starts* with the
            // marker, matching text-chunker.ts).
            val fenceMatch = FENCE_OPEN.find(line.trimStart())
            if (fenceMatch != null) {
                val marker = fenceMatch.value
                val start = cursor
                val bodyLines = mutableListOf(line)
                cursor += line.length + 1
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    bodyLines.add(next)
                    cursor += next.length + 1
                    if (next.trim() == marker) {
                        j++
                        break
                    }
                    j++
                }
                atoms += Atom(
                    text = bodyLines.joinToString("\n"),
                    offset = start,
                    indivisible = true,
                    kind = AtomKind.CODE
                )
                i = j
                continue
            }

            // Table: consecutive lines starting with `|`. Need at
            // least 2 such lines (header + separator / data row)
            // to be a real table; otherwise treat as a paragraph.
            if (line.startsWith("|")) {
                var j = i
                while (j < lines.size && lines[j].startsWith("|")) j++
                if (j - i >= 2) {
                    val start = cursor
                    val content = lines.subList(i, j).joinToString("\n")
                    val consumed = content.length + if (j < lines.size) 1 else 0
                    cursor += consumed
                    atoms += Atom(
                        text = content,
                        offset = start,
                        indivisible = true,
                        kind = AtomKind.TABLE
                    )
                    i = j
                    continue
                }
            }

            // Regular paragraph: accumulate consecutive non-blank,
            // non-fence, non-table-prefix lines.
            if (line.isBlank()) {
                cursor += line.length + 1
                i++
                continue
            }
            val start = cursor
            val bodyLines = mutableListOf<String>()
            while (i < lines.size &&
                lines[i].isNotBlank() &&
                !lines[i].startsWith("|") &&
                FENCE_OPEN.find(lines[i].trimStart()) == null
            ) {
                bodyLines.add(lines[i])
                cursor += lines[i].length + 1
                i++
            }
            atoms += Atom(
                text = bodyLines.joinToString("\n"),
                offset = start,
                indivisible = false,
                kind = AtomKind.PARAGRAPH
            )
        }
        return atoms
    }

    // ── Recursive split ────────────────────────────────────────────────

    /**
     * Break every splittable atom into pieces no larger than
     * [targetChars] using the recursive split ladder
     * (paragraph → line → sentence → space → hard slice).
     * Indivisible atoms (code / table) pass through unchanged.
     */
    private fun splitAtomsToPieces(atoms: List<Atom>, target: Int): List<Piece> {
        val pieces = mutableListOf<Piece>()
        for (atom in atoms) {
            if (atom.indivisible) {
                if (atom.text.isNotEmpty()) {
                    pieces += Piece(text = atom.text, offset = atom.offset)
                }
                continue
            }
            if (atom.text.isEmpty()) continue
            if (atom.text.length <= target) {
                pieces += Piece(text = atom.text, offset = atom.offset)
                continue
            }
            pieces += recursiveSplit(atom.text, atom.offset, target)
        }
        return pieces
    }

    /**
     * Top-down recursion: try splitting by bigger-grained separator
     * first (double-newline paragraphs), only descending to finer
     * separators if any resulting piece still exceeds the target.
     */
    private fun recursiveSplit(text: String, baseOffset: Int, target: Int): List<Piece> {
        val out = mutableListOf<Piece>()
        var cursor = baseOffset
        for (paragraph in splitKeepingSep(text, PARAGRAPH_SPLIT)) {
            if (paragraph.isEmpty()) continue
            if (paragraph.length <= target) {
                out += Piece(text = paragraph, offset = cursor)
                cursor += paragraph.length
                continue
            }
            // Try splitting by lines, then by sentence terminators,
            // then by whitespace. We stop at the first separator
            // where every resulting piece fits inside `target`.
            var handled = false
            for ((_, splitter) in SPLITTERS) {
                val subs = splitter(paragraph)
                if (subs.size > 1 && subs.all { it.length <= target }) {
                    var subCursor = cursor
                    for (s in subs) {
                        if (s.isEmpty()) continue
                        out += Piece(text = s, offset = subCursor)
                        subCursor += s.length
                    }
                    cursor += paragraph.length
                    handled = true
                    break
                }
            }
            if (handled) continue
            // Fall back to hard char slicing — last resort.
            var sliceCursor = cursor
            var i = 0
            while (i < paragraph.length) {
                val end = (i + target).coerceAtMost(paragraph.length)
                val piece = paragraph.substring(i, end)
                out += Piece(text = piece, offset = sliceCursor)
                sliceCursor += piece.length
                i = end
            }
            cursor += paragraph.length
        }
        return out
    }

    // ── Section → packed chunks ────────────────────────────────────────

    private data class Section(
        val text: String,
        val bodyStart: Int,
        val headingPath: String
    )

    private enum class AtomKind { CODE, TABLE, PARAGRAPH }

    private data class Atom(
        val text: String,
        val offset: Int,
        val indivisible: Boolean,
        val kind: AtomKind
    )

    private data class Piece(
        val text: String,
        val offset: Int
    )

    private data class Packed(
        val text: String,
        val offset: Int,
        val headingPath: String,
        val overlapBefore: String = ""
    )

    private fun packSections(sections: List<Section>, target: Int): List<Packed> {
        val out = mutableListOf<Packed>()
        for (section in sections) {
            if (section.text.length <= target) {
                out += Packed(
                    text = section.text,
                    offset = section.bodyStart,
                    headingPath = section.headingPath
                )
                continue
            }
            val atoms = tokenizeAtoms(section.text)
            val pieces = splitAtomsToPieces(atoms, target)
            // Greedy pack pieces into chunks ≤ target.
            var buf = StringBuilder()
            var bufOffset: Int? = null
            for (p in pieces) {
                if (p.text.isEmpty()) continue
                if (p.text.length > target) {
                    if (buf.isNotEmpty() && bufOffset != null) {
                        out += Packed(buf.toString(), bufOffset, section.headingPath)
                        buf = StringBuilder()
                        bufOffset = null
                    }
                    out += Packed(p.text, p.offset, section.headingPath)
                    continue
                }
                val wouldExceed = buf.isNotEmpty() &&
                    bufOffset != null &&
                    buf.length + p.text.length > target
                if (wouldExceed) {
                    out += Packed(buf.toString(), bufOffset!!, section.headingPath)
                    buf = StringBuilder(p.text)
                    bufOffset = p.offset
                } else {
                    if (buf.isEmpty()) bufOffset = p.offset
                    buf.append(p.text)
                }
            }
            if (buf.isNotEmpty() && bufOffset != null) {
                out += Packed(buf.toString(), bufOffset, section.headingPath)
            }
        }
        return out
    }

    private fun mergeSmall(pieces: List<Packed>, minChars: Int, maxChars: Int): List<Packed> {
        if (pieces.size < 2) return pieces
        val out = pieces.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < out.size - 1) {
                val cur = out[i]
                val nxt = out[i + 1]
                if (cur.text.length < minChars &&
                    cur.text.length + nxt.text.length <= maxChars
                ) {
                    out[i] = cur.copy(text = cur.text + nxt.text)
                    out.removeAt(i + 1)
                    merged = true
                } else {
                    i++
                }
            }
        }
        return out
    }

    private fun applyOverlap(pieces: List<Packed>, overlapChars: Int): List<Packed> {
        if (overlapChars <= 0 || pieces.size < 2) return pieces
        val out = mutableListOf(pieces[0])
        for (i in 1 until pieces.size) {
            val prev = pieces[i - 1]
            val cur = pieces[i]
            val overlap = overlapSuffix(prev.text, overlapChars)
            out += cur.copy(overlapBefore = overlap)
        }
        return out
    }

    /**
     * Pull at most [maxChars] of trailing context from [text],
     * snapped to a paragraph / sentence boundary when possible.
     * Mirrors `overlapSuffix` in llm_wiki's ingest.ts.
     */
    private fun overlapSuffix(text: String, maxChars: Int): String {
        if (text.isEmpty() || maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val raw = text.substring(text.length - maxChars)
        val paraBreak = raw.indexOf("\n\n")
        if (paraBreak > 0 && raw.length - paraBreak > maxChars * 0.4) {
            return raw.substring(paraBreak).trim()
        }
        val sentMatch = SENTENCE_BOUNDARY.find(raw)
        if (sentMatch != null && raw.length - sentMatch.range.last > maxChars * 0.4) {
            return raw.substring(sentMatch.range.last + 1).trim()
        }
        return raw.trim()
    }

    // ── Shared regex / split helpers ───────────────────────────────────

    companion object {
        private val FENCE_OPEN = Regex("^(`{3,}|~{3,})")
        private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*$")
        private val PARAGRAPH_SPLIT = Regex("(\\n{2,})")
        private val LINE_SPLIT = Regex("(\\n+)")
        private val SENTENCE_SPLIT = Regex("((?<=[。！？!?；;])\\s*|\\.\\s+)")
        private val WHITESPACE_SPLIT = Regex("(\\s+)")
        private val SENTENCE_BOUNDARY = Regex("[.!?。！？;；]\\s+")

        private val SPLITTERS: List<Pair<String, (String) -> List<String>>> = listOf(
            "lines" to { t -> splitKeepingSep(t, LINE_SPLIT) },
            "sentences" to { t -> splitKeepingSep(t, SENTENCE_SPLIT) },
            "spaces" to { t -> splitKeepingSep(t, WHITESPACE_SPLIT) },
        )

        private fun splitKeepingSep(text: String, sep: Regex): List<String> {
            if (text.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var last = 0
            for (m in sep.findAll(text)) {
                val end = m.range.last + 1
                if (end > last) {
                    out += text.substring(last, end)
                    last = end
                }
            }
            if (last < text.length) out += text.substring(last)
            return out.filter { it.isNotEmpty() }
        }
    }
}

/**
 * One emitted chunk of the long source.
 *
 * @property id stable id (`chunk-N`, 1-based). Useful for log
 *   breadcrumb lines so the user can match "正在分析第 3 段" against
 *   a saved checkpoint.
 * @property index 1-based position in the emission order.
 * @property total total number of chunks in the same emission —
 *   lets the LLM prompt say "Chunk 3/12" without re-counting.
 * @property headingPath breadcrumb of the containing markdown
 *   headings (e.g. "## Intro > ### Usage"), empty string when the
 *   chunk lives above any heading.
 * @property overlapBefore tail of the previous chunk, snapped to a
 *   paragraph / sentence boundary. The orchestrator's chunk-prompt
 *   template renders this as "Previous Overlap Context" so the LLM
 *   sees cross-boundary concepts without re-reading the whole prior
 *   chunk.
 * @property main the visible content of the chunk (no frontmatter,
 *   no overlap). This is what the LLM actually analyzes.
 */
data class SourceChunk(
    val id: String,
    val index: Int,
    val total: Int,
    val headingPath: String,
    val overlapBefore: String,
    val main: String
)
