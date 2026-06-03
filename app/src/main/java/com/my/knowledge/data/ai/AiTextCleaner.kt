package com.my.knowledge.data.ai

/**
 * Shared text-cleaning helpers used by view-models and ingest code paths.
 *
 * Originally these helpers lived as `private fun` inside `NoteEditorViewModel`,
 * which meant `AskViewModel.saveAnswerAsKnowledge` could not reuse them and
 * ended up saving the raw model output (including the `<think>` block) into
 * the knowledge base. Promoting them to a public object fixes that leak.
 */
object AiTextCleaner {

    /**
     * All the "model is reasoning out loud" tags we have seen in the wild.
     * Keeping the list in one place ensures every code path strips the same
     * set — important because the `cleanModelOutput` contract guarantees
     * "what's persisted never contains the chain-of-thought".
     *
     * - `<think>`     — DeepSeek-R1, Qwen-QwQ, OpenAI o-series
     * - `<thinking>`  — some Anthropic-style adapters
     * - `<reasoning>` — a handful of community gateways
     * - `<reflection>`— older custom fine-tunes
     *
     * The regex is DOT_MATCHES_ALL so multi-line blocks disappear in one go,
     * and IGNORE_CASE so models that write `<THINK>...</THINK>` or mixed
     * case are also handled.
     */
    private val THINK_OPEN_TAGS = listOf("<think>", "<thinking>", "<reasoning>", "<reflection>")
    private val THINK_CLOSE_TAGS = listOf("</think>", "</thinking>", "</reasoning>", "</reflection>")

    private val thinkBlockRegex: Regex by lazy {
        // Build a single regex that matches ANY of the open/close pairs.
        // Using alternation keeps the order in the original text — no risk
        // of swallowing the wrong block when a model emits nested tags.
        val open = THINK_OPEN_TAGS.joinToString("|") { Regex.escape(it) }
        val close = THINK_CLOSE_TAGS.joinToString("|") { Regex.escape(it) }
        Regex("($open).*?($close)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    }

    private val danglingOpenRegex: Regex by lazy {
        // A reasoning block that never closed (truncation, streaming
        // disconnect). Discard everything from the open tag to the end —
        // the model was still thinking and produced no real content after.
        val open = THINK_OPEN_TAGS.joinToString("|") { Regex.escape(it) }
        Regex("($open).*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    }

    /**
     * Strips out the model's hidden reasoning block. Supports the four
     * common tag variants (`<think>` / `<thinking>` / `<reasoning>` /
     * `<reflection>`), case-insensitive, including dangling opens.
     */
    fun String.removeThinkBlock(): String =
        this.replace(thinkBlockRegex, "")
            .replace(danglingOpenRegex, "")
            .trim()

    /**
     * Strips a leading / trailing markdown code fence, e.g.
     *   ```markdown
     *   ...content...
     *   ```
     */
    fun String.removeMarkdownFence(): String {
        var text = this.trim()
        if (text.startsWith("```")) {
            text = text.replace(Regex("^```(?:markdown)?\\n?", RegexOption.IGNORE_CASE), "")
        }
        if (text.endsWith("```")) {
            text = text.replace(Regex("\\n?```$", RegexOption.IGNORE_CASE), "")
        }
        return text.trim()
    }

    /**
     * Convenience: think + fence strip in one call. Used by every code path
     * that takes a model response and either displays it or persists it as
     * knowledge content.
     */
    fun String.cleanModelOutput(): String =
        this.removeThinkBlock().removeMarkdownFence()
}
