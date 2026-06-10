package com.my.knowledge.data.ingest

/**
 * P5-merge: entity / concept / wiki page identity primitives.
 *
 * Replaces the title-based identity model that caused "两个同名文件"
 * in long-PDF re-ingest (see plan §B). The slug here is the stable
 * key used by the M2 (MERGE-1 PR-M2) upsert path in
 * [IngestOrchestrator.runGenerationTask] to find an existing wiki
 * page across re-analysis runs even when the LLM produces a slightly
 * different [entity.name] (e.g. "Accumulibacter" vs
 * "Candidatus Accumulibacter" — both slug to the same `accumulibacter`
 * after CJK + parenthetical normalisation).
 *
 * Different from [LongSourceCheckpointStore.slugify] in two ways:
 *   1. **Preserves CJK** — entity names from Chinese PDFs are mostly
 *      CJK; collapsing them to `_` (as `LongSourceCheckpointStore.slugify`
 *      does for filesystem-safe filenames) would lose identity entirely.
 *   2. **Hyphens not underscores** — matches the llm_wiki convention
 *      (`wiki/entities/<slug>.md`) and the slug shape llm_wiki's own
 *      dedup module expects.
 */
object Slug {
    /**
     * Filesystem-and-wikilink-safe slug. Keeps CJK letters intact,
     * collapses whitespace and `/\\:?&'"<>|` to `-`, lowercases ASCII
     * runs, trims leading/trailing hyphens. Empty result falls back
     * to [fallback].
     */
    fun slugify(input: String, fallback: String = "entity"): String {
        if (input.isBlank()) return fallback
        val sb = StringBuilder(input.length)
        var lastWasHyphen = true // treat start as a hyphen boundary
        for (ch in input) {
            val mapped: Char? = when {
                ch.isLetterOrDigit() -> if (ch in 'A'..'Z') ch.lowercaseChar() else ch
                ch.isWhitespace() || ch == '_' || ch == '-' -> if (!lastWasHyphen) '-' else null
                ch == '/' || ch == '\\' || ch == ':' || ch == '?' ||
                    ch == '&' || ch == '\'' || ch == '"' || ch == '<' ||
                    ch == '>' || ch == '|' || ch == '*' || ch == '`' -> if (!lastWasHyphen) '-' else null
                else -> null
            }
            if (mapped != null) {
                sb.append(mapped)
                lastWasHyphen = mapped == '-'
            }
        }
        while (sb.isNotEmpty() && sb.last() == '-') sb.deleteCharAt(sb.length - 1)
        return if (sb.isEmpty()) fallback else sb.toString()
    }

    fun wikiEntitySlug(name: String): String = slugify(name.trim(), fallback = "entity")
    fun wikiConceptSlug(name: String): String = slugify(name.trim(), fallback = "concept")
}
