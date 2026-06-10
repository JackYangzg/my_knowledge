package com.my.knowledge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression suite for [appendVoiceText].
 *
 * The original bug:
 *
 *   - User has text "你好" in the editor (typed, or from voice session 1).
 *   - User starts a second voice session.
 *   - During the second session, the editor mirrored
 *     `mergeWithOverlap(prev, partial)`. The merge ran
 *     `if (right.startsWith(left)) return right`, so when the partial
 *     coincidentally started with "你好" (or contained it as a substring
 *     under the InspirationScreen variant), the user's prior text was
 *     silently dropped from the editor.
 *
 * The new policy: voice never mirrors partials into the editor — only
 * finalized utterances are appended via [appendVoiceText], and the
 * function performs no overlap detection. These tests pin that policy
 * so a future "let me re-add smart merging" PR fails CI.
 */
class VoiceTextAppenderTest {

    @Test
    fun `empty current returns the addition unchanged`() {
        assertEquals("hello", appendVoiceText("", "hello"))
    }

    @Test
    fun `empty addition returns the current unchanged`() {
        assertEquals("hello", appendVoiceText("hello", ""))
    }

    @Test
    fun `appends CJK utterance to CJK prefix with no inserted space`() {
        // Chinese readers don't expect a space between ideographs.
        assertEquals("你好世界", appendVoiceText("你好", "世界"))
    }

    @Test
    fun `appends English word with a single space boundary`() {
        assertEquals("Hello world", appendVoiceText("Hello", "world"))
    }

    @Test
    fun `keeps existing trailing newline without doubling`() {
        // "Note line 1\n" + "line 2" → "Note line 1\nline 2"
        assertEquals("line 1\nline 2", appendVoiceText("line 1\n", "line 2"))
    }

    @Test
    fun `uses newline boundary after sentence-ending punctuation`() {
        assertEquals("第一句。\n第二句", appendVoiceText("第一句。", "第二句"))
    }

    @Test
    fun `regression - prior text is preserved when addition starts with prior content`() {
        // Old `mergeWithOverlap` triggered `if (right.startsWith(left)) return right`
        // here and silently dropped "你好" from the editor. New policy keeps it.
        val current = "你好"
        val addition = "你好今天天气真好"
        // Plain append (no overlap stripping) — the dup itself is the
        // voice service's responsibility, not the editor's.
        assertEquals("你好你好今天天气真好", appendVoiceText(current, addition))
    }

    @Test
    fun `regression - prior text is preserved when prior is a substring of addition`() {
        // InspirationScreen's `mergeWithOverlap` had
        // `if (containsSimilar(s2, s1)) return s2`, which deleted the
        // user's prior text whenever the new utterance happened to
        // contain it after punctuation-stripping. Now preserved.
        val current = "Hello"
        val addition = "Hello again, friend"
        assertEquals("Hello Hello again, friend", appendVoiceText(current, addition))
    }

    @Test
    fun `regression - second voice session appends, never replaces`() {
        // Full flow: editor starts with session-1's "你好世界",
        // user records session 2 and the final utterance "今天天气真好"
        // arrives. The expected behavior is concatenation, NOT overwrite.
        val session1Result = "你好世界"
        val session2Final = "今天天气真好"
        val combined = appendVoiceText(session1Result, session2Final)
        assertEquals("你好世界今天天气真好", combined)
        // And the prior session's text is still present:
        assert(combined.startsWith(session1Result)) {
            "Prior voice session output must be preserved, got: $combined"
        }
    }

    @Test
    fun `mixed CJK and Latin - last char rules the separator`() {
        // Last char is Latin → space.
        assertEquals("hello 世界", appendVoiceText("hello", "世界"))
        // Last char is CJK → no separator.
        assertEquals("世界hello", appendVoiceText("世界", "hello"))
    }

    @Test
    fun `whitespace-only ending falls through to newline branch`() {
        // A trailing space is whitespace but not a letter/digit and not
        // '\n', so the separator branch resolves to "\n".
        assertEquals("hello \nworld", appendVoiceText("hello ", "world"))
    }
}
