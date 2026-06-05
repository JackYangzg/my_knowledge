package com.my.knowledge.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCH-7 / PR1 (§3.1). Locks the postmortem-strip contract for
 * [AiPromptTemplates.analysisPrompt]: the LLM-facing template must
 * NOT carry our internal bug-history commentary, but it MUST still
 * carry the at-least-1 hard rule (compressed to one line).
 */
class AiPromptTemplatesTest {
    private val title = "Test Source"
    private val emptyIndex = ""

    @Test
    fun analysisPrompt_omitsPostmortemComments() {
        val out = AiPromptTemplates.analysisPrompt(title = title, currentIndex = emptyIndex)
        assertFalse("Hard extraction rule (P1, fixed) block 1+2 must be gone",
            out.contains("Hard extraction rule (P1, fixed)"))
        assertFalse("'Why FREE-FORM' rationale must be gone",
            out.contains("are FREE-FORM, not enums"))
        assertFalse("Anti-empty-array guard (P1, fixed) must be gone",
            out.contains("Anti-empty-array guard (P1, fixed)"))
        // 防御性 broad marker: catch any P1 fix-log leak
        assertFalse("P1, fixed marker must not appear",
            out.contains("P1, fixed"))
    }

    @Test
    fun analysisPrompt_keepsHardRule() {
        val out = AiPromptTemplates.analysisPrompt(title = title, currentIndex = emptyIndex)
        assertTrue("HARD rule must remain (compressed to 1 line)",
            out.contains("at least 1 entity") && out.contains("at least 1 concept"))
    }

    @Test
    fun analysisPrompt_keepsLanguageDirective() {
        val out = AiPromptTemplates.analysisPrompt(title = title, currentIndex = emptyIndex)
        assertTrue("MANDATORY OUTPUT LANGUAGE directive must remain",
            out.contains("MANDATORY OUTPUT LANGUAGE"))
    }

    @Test
    fun analysisPrompt_sizeBelowThresholdWithNoIndex() {
        // §3.1 估：postmortem 删后 system prompt ≤ 4.3K；留余量到 5K
        val out = AiPromptTemplates.analysisPrompt(title = title, currentIndex = emptyIndex)
        assertTrue("system prompt must shrink after postmortem strip: actual=${out.length}",
            out.length < 5_000)
    }
}
