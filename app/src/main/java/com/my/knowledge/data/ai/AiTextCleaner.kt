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
     * Strips out the model's hidden reasoning block.
     *
     * The regex is DOT_MATCHES_ALL so a multi-line <think>...</think> block
     * is removed in one go, and `RegexOption.IGNORE_CASE` is set so models
     * that write `<THINK>...</THINK>` or mixed case are also handled.
     */
    private val thinkBlockRegex = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val danglingOpenRegex = Regex("<think>.*", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

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
