package com.my.knowledge.data.ingest

import java.text.Normalizer
import java.util.Locale

/**
 * Single source of truth for entity / concept name normalization.
 *
 * The previous dedup key was `name.lowercase()` only — which
 * catches case variation but NOT whitespace differences. LLMs
 * occasionally emit names with extra / non-ASCII whitespace:
 *   - leading or trailing spaces (" Foo Bar")
 *   - doubled internal spaces ("Foo  Bar")
 *   - NBSP between CJK / English tokens ("Foo Bar")
 *   - CJK ideographic space U+3000 ("一致性　哈希")
 *
 * Each of these produced a separate wiki page and a separate
 * graph node for what is logically the same entity. The
 * fix: this helper collapses all whitespace variants to ONE
 * canonical form, and a separate [dedupKey] adds the case
 * fold for identity checks.
 *
 * Display titles preserve the first-seen casing (so "iOS"
 * stays "iOS" on the wiki page) — [canonical] never
 * lowercases, only [dedupKey] does.
 */
object EntityName {

    /**
     * Normalize a name for storage and display:
     *   1. NFKC compatibility decomposition (so "ﬁ" → "fi",
     *      full-width "Ａ" → "A", etc., before whitespace
     *      processing).
     *   2. Every Unicode whitespace character (per
     *      [Char.isWhitespace], which covers NBSP, U+3000
     *      ideographic space, and the rest of the Unicode
     *      SPACE / LINE / PARAGRAPH separator categories) is
     *      replaced with a single ASCII space.
     *   3. Internal whitespace runs are collapsed to a single
     *      space; leading and trailing whitespace is trimmed.
     *
     * Casing is preserved. Returns "" for blank input; callers
     * should treat "" as "skip this name".
     */
    fun canonical(name: String): String {
        if (name.isEmpty()) return ""
        val nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val spaced = buildString(nfkc.length) {
            for (ch in nfkc) {
                append(if (ch.isWhitespace()) ' ' else ch)
            }
        }
        return spaced.replace(WHITESPACE_RUN, " ").trim()
    }

    /**
     * Identity key for dedup. Two names that should be treated
     * as the same entity / concept produce the same
     * [dedupKey]. Built from [canonical] + locale-root
     * lowercase so case differences don't fork identity.
     */
    fun dedupKey(name: String): String = canonical(name).lowercase(Locale.ROOT)

    private val WHITESPACE_RUN = Regex("\\s+")
}
