package com.my.knowledge.data.ingest

import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.viewmodel.InspirationThreadUi
import com.my.knowledge.viewmodel.LlmThreadDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2: 灵感脉络的 LLM 增量更新 —— prompt 生成、diff 解析、UI hint 注入。
 *
 * 跟前两批 P0/P1 测试一样,这些测试不调真实 LLM,只验:
 *   1. inspirationThreadPrompt 把所有上下文正确拼进 prompt
 *   2. LlmThreadDiff.parseFromLogSummary 能从 worker 写入的哨兵块
 *      恢复 newMainlineSegments / evolvedSegments / obsoleteSegments
 *   3. InspirationThreadUi.from 接收 diff 后,mainlineSegmentHints
 *      能正确标注 NEW / EVOLVED / OBSOLETE / UNCHANGED
 *
 * worker 本身需要 Room DAO,不是单测能覆盖的;这部分在 instrumented test
 * 阶段或运行时实跑 LLM 时再验。
 */
class InspirationThreadPromptTest {

    @Test
    fun `inspirationThreadPrompt includes the new inspiration full content`() {
        val prompt = AiPromptTemplates.inspirationThreadPrompt(
            kbName = "灵感空间",
            newInspiration = AiPromptTemplates.NewInspiration(
                id = "new-1",
                title = "今天想到一个产品 idea",
                tags = listOf("产品", "灵感"),
                summary = "围绕用户分享碎片的痛点",
                content = "碎片太多,找不回当时为什么这样想",
            ),
            historicalInspirationDigest = emptyList(),
            existingThread = null,
        )
        assertTrue("Prompt must include the new inspiration's title",
            prompt.contains("今天想到一个产品 idea"))
        assertTrue("Prompt must include the new inspiration's full content",
            prompt.contains("碎片太多,找不回当时为什么这样想"))
        assertTrue("Prompt must include the new tags",
            prompt.contains("产品") && prompt.contains("灵感"))
        assertTrue("Prompt must declare first-time / no-existing-thread mode",
            prompt.contains("灵感库尚无脉络,本次从零生成"))
    }

    @Test
    fun `inspirationThreadPrompt does not inject external wiki or source hints`() {
        val prompt = AiPromptTemplates.inspirationThreadPrompt(
            kbName = "灵感空间",
            newInspiration = AiPromptTemplates.NewInspiration(
                id = "new-2",
                title = "只分析这条灵感",
                tags = emptyList(),
                summary = "",
                content = "我想把碎片想法整理成可推进的主线。",
            ),
            historicalInspirationDigest = emptyList(),
            existingThread = null,
        )
        assertTrue("Prompt must include the inspiration content",
            prompt.contains("我想把碎片想法整理成可推进的主线。"))
        assertFalse("Prompt must not include a related wiki section",
            prompt.contains("关联到的知识条目"))
        assertFalse("Prompt must not instruct the model to use wiki entities",
            prompt.contains("wiki 实体"))
        assertFalse("Prompt must not introduce source/file provenance",
            prompt.contains("[来源:"))
    }

    @Test
    fun `inspirationThreadPrompt echoes existing thread as incremental anchor`() {
        val prompt = AiPromptTemplates.inspirationThreadPrompt(
            kbName = "灵感空间",
            newInspiration = AiPromptTemplates.NewInspiration(
                id = "new-3", title = "x", tags = emptyList(), summary = "", content = "c",
            ),
            historicalInspirationDigest = emptyList(),
            existingThread = AiPromptTemplates.ExistingThreadSnapshot(
                description = "灵感库在追踪分布式系统主题。",
                coreQuestion = "如何理解共识?",
                mainline = listOf("主线 1:Paxos 反思 → Raft 出现"),
                gaps = listOf("缺少性能基准数据"),
                nextSuggestions = listOf("跑一个 Raft 实现"),
            ),
        )
        assertTrue("Existing description must appear as incremental anchor",
            prompt.contains("灵感库在追踪分布式系统主题。"))
        assertTrue("Existing mainline must appear in prompt",
            prompt.contains("主线 1:Paxos 反思 → Raft 出现"))
        assertTrue("Existing gap must echo in prompt",
            prompt.contains("缺少性能基准数据"))
        assertFalse("First-time-mode hint must NOT appear when existing thread is present",
            prompt.contains("灵感库尚无脉络,本次从零生成"))
    }

    @Test
    fun `inspirationThreadPrompt pins language directive both head and tail`() {
        val prompt = AiPromptTemplates.inspirationThreadPrompt(
            kbName = "灵感空间",
            newInspiration = AiPromptTemplates.NewInspiration(
                id = "new-4", title = "x", tags = emptyList(), summary = "", content = "c",
            ),
            historicalInspirationDigest = emptyList(),
            existingThread = null,
            language = "中文",
        )
        // head + tail 双注入,跟 llm_wiki 的"最末指令优先"技巧一致
        val headIdx = prompt.indexOf("MANDATORY OUTPUT LANGUAGE")
        val tailIdx = prompt.lastIndexOf("MANDATORY OUTPUT LANGUAGE")
        assertTrue("Language directive must appear in head", headIdx >= 0)
        assertTrue("Language directive must appear in tail", tailIdx > headIdx)
    }

    // ---- LlmThreadDiff 解析 ----

    @Test
    fun `LlmThreadDiff parseFromLogSummary returns null when sentinel is absent`() {
        assertEquals(null, LlmThreadDiff.parseFromLogSummary("普通的日志,没哨兵"))
        assertEquals(null, LlmThreadDiff.parseFromLogSummary(null))
        assertEquals(null, LlmThreadDiff.parseFromLogSummary(""))
    }

    @Test
    fun `LlmThreadDiff parseFromLogSummary extracts new evolved obsolete from sentinel block`() {
        val diffJson = """
            {"newMainlineSegments":["新主线 A"],
             "evolvedSegments":[{"label":"主线 X","before":"旧","after":"新"}],
             "obsoleteSegments":["已弃用的 Y"]}
        """.trimIndent()
        val summary = "LLM 灵感脉络更新:2 条主线,1 条关联\n$DIFF_SENTINEL$diffJson-->"
        val diff = LlmThreadDiff.parseFromLogSummary(summary)
        assertNotNull("Sentinel-bearing summary must parse to a diff", diff)
        assertEquals(listOf("新主线 A"), diff!!.newMainlineSegments)
        assertEquals(1, diff.evolvedSegments.size)
        assertEquals("主线 X", diff.evolvedSegments[0].label)
        assertEquals("旧", diff.evolvedSegments[0].before)
        assertEquals("新", diff.evolvedSegments[0].after)
        assertEquals(listOf("已弃用的 Y"), diff.obsoleteSegments)
    }

    @Test
    fun `LlmThreadDiff parseFromLogSummary tolerates malformed inner JSON`() {
        val summary = "ok\n$DIFF_SENTINEL{not valid json-->"
        // 容错:解析失败 → null(worker 不会写入垃圾日志,但读取端也要稳)
        assertEquals(null, LlmThreadDiff.parseFromLogSummary(summary))
    }

    // ---- InspirationThreadUi diff 注入 ----

    @Test
    fun `InspirationThreadUi from maps diff to mainline segment hints`() {
        val mainlines = listOf("旧主线 1(没变)", "新主线 A", "主线 X 演变了", "已弃用的 Y")
        val diff = LlmThreadDiff(
            newMainlineSegments = listOf("新主线 A"),
            evolvedSegments = listOf(
                InspirationThreadUi.EvolvedSegment(label = "主线 X", before = "旧", after = "新")
            ),
            obsoleteSegments = listOf("已弃用的 Y"),
        )
        val thread = fakeThread(mainlinesJson = """["${mainlines.joinToString("\",\"")}"]""")
        val ui = InspirationThreadUi.from(
            items = listOf(fakeItem("i1", mainlines.first())),
            thread = thread,
            latestDiff = diff,
        )
        // 4 个 mainline,4 个 hint,1:1 对应
        assertEquals(4, ui.mainlines.size)
        assertEquals(4, ui.diff.mainlineSegmentHints.size)
        assertEquals(InspirationThreadUi.SegmentHint.UNCHANGED, ui.diff.mainlineSegmentHints[0])
        assertEquals(InspirationThreadUi.SegmentHint.NEW, ui.diff.mainlineSegmentHints[1])
        assertEquals(InspirationThreadUi.SegmentHint.EVOLVED, ui.diff.mainlineSegmentHints[2])
        assertEquals(InspirationThreadUi.SegmentHint.OBSOLETE, ui.diff.mainlineSegmentHints[3])
    }

    @Test
    fun `InspirationThreadUi from without diff marks all segments UNCHANGED`() {
        val ui = InspirationThreadUi.from(
            items = listOf(fakeItem("i1", "主线 1")),
            thread = null,
            latestDiff = null,
        )
        assertTrue(
            "Without diff, every hint must be UNCHANGED (so UI 退回到无角标)",
            ui.diff.mainlineSegmentHints.all { it == InspirationThreadUi.SegmentHint.UNCHANGED }
        )
    }

    @Test
    fun `InspirationThreadUi empty() has UNCHANGED hints and empty diff`() {
        val ui = InspirationThreadUi.empty()
        assertTrue(ui.diff.newMainlineSegments.isEmpty())
        assertTrue(ui.diff.evolvedSegments.isEmpty())
        assertTrue(ui.diff.obsoleteSegments.isEmpty())
        assertTrue(ui.diff.mainlineSegmentHints.isEmpty())
    }

    // ---- helpers ----

    private fun fakeItem(id: String, title: String) =
        com.my.knowledge.data.db.entity.KnowledgeItemEntity(
            id = id,
            sourceId = "src-$id",
            knowledgeBaseId = "insp",
            title = title,
            contentMarkdown = "",
            excerpt = "",
            sourceType = "inspiration",
            status = com.my.knowledge.data.db.entity.KnowledgeItemEntity.STATUS_ARCHIVED,
            contentHash = "",
            sourceTraceJson = "{}",
            confidence = 0f,
            summary = null,
            tagsJson = "[]",
            rawNoteId = null,
            importance = 1,
            createdAt = 0L,
            updatedAt = 0L,
            processedAt = 0L,
            archivedAt = null,
            deletedAt = null,
        )

    private fun fakeThread(mainlinesJson: String) =
        com.my.knowledge.data.db.entity.KnowledgeThreadEntity(
            id = "thread-1",
            knowledgeBaseId = "insp",
            description = "desc",
            coreQuestion = "q",
            mainlineJson = mainlinesJson,
            relationsJson = "[]",
            gapsJson = "[]",
            nextSuggestionsJson = "[]",
            inputHash = null,
            version = 1,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private companion object {
        // 跟 LlmInspirationThreadWorker.DIFF_SENTINEL 同步
        const val DIFF_SENTINEL = "<!--DIFF-V1:"
    }
}
