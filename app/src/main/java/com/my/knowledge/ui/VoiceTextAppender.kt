package com.my.knowledge.ui

/**
 * Voice-to-text editor append helper.
 *
 * The editor receives one finalized voice utterance at a time via
 * [com.my.knowledge.data.ai.VolcengineVoiceService.finalTranscriptFlow].
 * Each utterance must be appended to the editor's CURRENT content with
 * no overlap detection — previous policies attempted "smart" merging
 * (overlap stripping, substring deduplication) and that logic was the
 * root cause of editor text disappearing when a second recording
 * session began: if the new partial happened to start with or contain
 * the prior text, the prior text was silently dropped.
 *
 * Policy now: plain concatenation with a context-aware separator.
 *
 *  - Existing text empty             → return the addition unchanged
 *  - Existing text ends with `\n`    → no separator (already on a new line)
 *  - Existing text ends with a CJK   → no separator (Chinese / Japanese /
 *                                      Korean ideographs don't take
 *                                      inter-word spaces)
 *  - Existing text ends with a       → single space (English word boundary)
 *    letter or digit
 *  - Anything else (punctuation,     → newline (start a new sentence)
 *    whitespace, symbol)
 */
fun appendVoiceText(current: String, addition: String): String {
    if (current.isEmpty()) return addition
    if (addition.isEmpty()) return current
    val last = current.last()
    val separator = when {
        last == '\n' -> ""
        last.code in 0x4E00..0x9FFF -> ""    // CJK ideograph
        last.isLetterOrDigit() -> " "
        else -> "\n"
    }
    return current + separator + addition
}
