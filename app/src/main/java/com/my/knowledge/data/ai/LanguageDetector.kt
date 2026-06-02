package com.my.knowledge.data.ai

import java.util.Locale

/**
 * Tiny language detector used to honour the "outputLanguage: auto"
 * contract from llm_wiki's prompt. We don't need a full Unicode-script
 * table — for the ingest pipeline the only realistic outputs are
 * Chinese / English / Japanese / Korean (CJK in general) so a single
 * pass over the first 4 KB of source content is enough to pick the
 * dominant script.
 *
 * The thresholds are deliberately loose: anything that's ≥ 30% CJK
 * characters is treated as Chinese, otherwise English. Languages that
 * happen to share the Latin script (e.g. French, German) are bucketed
 * under "English" — the prompts already default to "中文" and a
 * follow-up LLM call won't care which European language the assistant
 * was told to use.
 */
object LanguageDetector {

    /**
     * Returns one of:
     *  - "中文"    — when CJK Han characters dominate the sample
     *  - "English" — otherwise (default fallback)
     *
     * Always returns a non-blank string so callers can pass the result
     * directly into [AiPromptTemplates.languageDirective] without a
     * null-check.
     */
    fun detect(text: String?): String {
        if (text.isNullOrBlank()) return "English"
        val sample = text.take(SAMPLE_SIZE)
        var cjk = 0
        var latin = 0
        var hiraganaKatakana = 0
        var hangul = 0
        for (ch in sample) {
            when {
                isCjkHan(ch) -> cjk++
                isHiraganaOrKatakana(ch) -> hiraganaKatakana++
                isHangul(ch) -> hangul++
                ch.isLetter() && ch.code < 0x250 -> latin++
            }
        }
        val total = cjk + hiraganaKatakana + hangul + latin
        if (total == 0) return "English"
        return when {
            cjk.toDouble() / total >= 0.30 -> "中文"
            (hiraganaKatakana + cjk).toDouble() / total >= 0.30 -> "日本語"
            hangul.toDouble() / total >= 0.30 -> "한국어"
            else -> "English"
        }
    }

    /** CJK Unified Ideographs: U+4E00..U+9FFF (basic) + extensions. */
    private fun isCjkHan(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF ||
            c in 0x3400..0x4DBF ||
            c in 0x20000..0x2A6DF ||
            c in 0x2A700..0x2B73F ||
            c in 0x2B740..0x2B81F ||
            c in 0x2B820..0x2CEAF
    }

    private fun isHiraganaOrKatakana(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x309F || c in 0x30A0..0x30FF
    }

    private fun isHangul(ch: Char): Boolean {
        val c = ch.code
        return c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F
    }

    private const val SAMPLE_SIZE = 4_096
}
