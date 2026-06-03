package com.my.knowledge.data.ingest

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the analysis-JSON extraction path.
 *
 * Background: the previous IngestOrchestrator.analysisTask used the
 * LLM's raw text as `summary` and hard-coded
 *   entitiesJson = "[]"
 *   conceptsJson = tags.toJsonArray()
 *   relationsJson = "[]"
 * on the resulting AnalysisResultEntity row, which meant no real
 * entities, tag-named "concept" pages with empty descriptions, and
 * an empty knowledge graph. The fix is the new `parseAiAnalysisJson`
 * + `ParsedAnalysis.fromObj` flow that actually parses the LLM
 * output and fills the three columns with real data. These tests pin
 * down the contract.
 *
 * The full `parseAiAnalysisJson` method takes a real IngestOrchestrator
 * (which needs AppDatabase / LocalFileStore / KnowledgeRepository),
 * so we test the underlying contract via ParsedAnalysis and
 * IngestJsonValidator directly — both are now package-internal for
 * exactly this reason.
 */
class ParseAiAnalysisJsonTest {

    /**
     * Sample LLM response that the new analysis prompt is designed to
     * elicit. Mirrors the schema documented in `AiPromptTemplates.analysisPrompt`
     * and `IngestOrchestrator.ANALYSIS_SCHEMA`.
     *
     * P1: 实体用自由类型 `entityType`(不是 enum),概念用 `conceptCategory`。
     * 注意 Raft 的 entityType 是 "Algorithm"——这在老 enum
     * (Person|Organization|...) 里完全不在,但在新的自由类型设计下
     * 是合理且被保留的,UI 拿到后走 nodeColor / 分组映射表正确渲染。
     */
    private val validLlmOutput = """
        {
          "title": "深入理解 Raft 共识算法",
          "summary": "本文系统介绍了 Raft 共识算法的设计目标、状态机和成员变更机制，并通过与 Paxos 的对比说明其工程优势。",
          "tags": ["分布式系统", "共识算法", "Raft"],
          "entities": [
            {
              "name": "Raft",
              "entityType": "Algorithm",
              "description": "一种为可理解性设计的分布式共识算法。",
              "role_in_source": "central",
              "evidence": "Raft 是一种共识算法……",
              "related_concepts": ["领导者选举", "日志复制"],
              "related_entities": ["etcd", "Consul"]
            },
            {
              "name": "etcd",
              "entityType": "Project",
              "description": "使用 Raft 作为底层共识的分布式 KV 存储。",
              "role_in_source": "supporting",
              "evidence": "etcd 在内部使用 Raft 来同步多个节点的状态……"
            },
            {
              "name": "Diego Ongaro",
              "entityType": "Person",
              "description": "Raft 算法的设计者与论文作者。",
              "role_in_source": "supporting",
              "evidence": "Raft 的论文由 Diego Ongaro 在 2014 年发表……"
            }
          ],
          "concepts": [
            {
              "name": "领导者选举",
              "conceptCategory": "Mechanism",
              "definition": "在集群中通过投票机制选出唯一主节点的流程。",
              "why_it_matters": "是 Raft 维持单一权威日志源的关键步骤。",
              "related_entities": ["Raft", "etcd"]
            },
            {
              "name": "日志复制",
              "conceptCategory": "Mechanism",
              "definition": "主节点把客户端命令追加到本地日志并复制到从节点的过程。",
              "why_it_matters": "决定 Raft 的数据一致性和可用性。",
              "related_entities": ["Raft"]
            },
            {
              "name": "Paxos",
              "conceptCategory": "Theory",
              "definition": "Lamport 提出的早期共识算法。",
              "why_it_matters": "Raft 的设计动机之一就是比 Paxos 更易理解。",
              "related_entities": ["Raft"]
            }
          ],
          "relations": [
            {"source": "Raft", "target": "领导者选举", "type": "uses", "confidence": 0.95},
            {"source": "Raft", "target": "日志复制", "type": "uses", "confidence": 0.95},
            {"source": "etcd", "target": "Raft", "type": "uses", "confidence": 0.9},
            {"source": "Raft", "target": "Paxos", "type": "related_to", "confidence": 0.8}
          ],
          "claims": [
            {"claim": "Raft 在工程上比 Paxos 更易实现", "evidence": "Ongaro 2014 论文", "confidence": 0.9}
          ],
          "gaps": [
            {"gap": "缺少性能基准数据", "whyItMatters": "需要量化 Raft 的吞吐量与延迟", "suggestedAction": "web_research"}
          ],
          "archiveRecommendation": {
            "targetKnowledgeBaseId": null,
            "targetKnowledgeBaseName": "分布式系统",
            "confidence": 0.85,
            "reason": "内容与分布式系统主题强相关",
            "suggestCreateNewBase": false,
            "newBaseName": null
          },
          "confidence": 0.9,
          "needHumanReview": false,
          "reviewReasons": []
        }
    """.trimIndent()

    private val fallbackTags = listOf("分布式", "知识")

    @Test
    fun `IngestJsonValidator parses well-formed LLM output`() {
        val obj = IngestJsonValidator.parseObjectOrNull(validLlmOutput)
        assertTrue("Expected valid JSON to parse", obj != null)
        assertEquals("深入理解 Raft 共识算法", IngestJsonValidator.string(obj!!, "title", ""))
        assertEquals(0.9f, IngestJsonValidator.float(obj, "confidence", 0f), 0.001f)
        val tags = IngestJsonValidator.arrayAsJson(obj, "tags")
        assertTrue("Tags must be a non-empty array", tags.contains("分布式系统"))
        val entities = JSONArray(IngestJsonValidator.arrayAsJson(obj, "entities"))
        assertEquals(3, entities.length()) // P1: sample 加了 Diego Ongarro 验证 Person 类型
    }

    @Test
    fun `IngestJsonValidator validates complete analysis JSON`() {
        assertTrue(
            "Complete analysis JSON must pass validateAnalysisJson",
            IngestJsonValidator.validateAnalysisJson(validLlmOutput)
        )
    }

    @Test
    fun `IngestJsonValidator rejects JSON missing required fields`() {
        val partial = """{"title": "X", "summary": "Y", "tags": []}"""
        assertFalse(
            "JSON missing entities / concepts / relations must fail validation",
            IngestJsonValidator.validateAnalysisJson(partial)
        )
    }

    @Test
    fun `ParsedAnalysis fromObj populates every column from a complete JSON object`() {
        val obj = IngestJsonValidator.parseObjectOrNull(validLlmOutput)!!
        val parsed = ParsedAnalysis.fromObj(
            obj = obj,
            fallbackTags = fallbackTags,
            fallbackConfidence = 0.42f,
            aiSucceeded = true,
        )

        assertEquals("深入理解 Raft 共识算法", IngestJsonValidator.string(obj, "title", ""))
        assertEquals(
            "本文系统介绍了 Raft 共识算法的设计目标、状态机和成员变更机制，并通过与 Paxos 的对比说明其工程优势。",
            parsed.summary
        )
        assertTrue("Tags column should contain AI tags", parsed.tagsJson.contains("分布式系统"))
        assertTrue(
            "Entities column must contain Raft + etcd (regression: was hard-coded to [])",
            parsed.entitiesJson.contains("\"Raft\"") && parsed.entitiesJson.contains("\"etcd\"")
        )
        // P1: 自由类型的 entityType("Algorithm"/"Project"/"Person" 等,完全
        // 在老 enum 之外)必须被完整保留到 entitiesJson 字段——这是修复
        // "实体、概念内容不正确"的核心断言。
        assertTrue(
            "Entity entityType=Algorithm must be preserved verbatim (free-form, not enum-silenced)",
            parsed.entitiesJson.contains("\"entityType\":\"Algorithm\"") ||
                parsed.entitiesJson.contains("\"entityType\": \"Algorithm\"")
        )
        assertTrue(
            "Entity entityType=Person must be preserved verbatim",
            parsed.entitiesJson.contains("\"entityType\":\"Person\"") ||
                parsed.entitiesJson.contains("\"entityType\": \"Person\"")
        )
        assertTrue(
            "Concepts column must contain 领导者选举 / 日志复制 / Paxos (regression: was hard-coded to tags)",
            parsed.conceptsJson.contains("领导者选举") &&
                parsed.conceptsJson.contains("日志复制") &&
                parsed.conceptsJson.contains("Paxos")
        )
        // P1: 概念的 conceptCategory 字段("Mechanism"/"Theory" 等)必须被
        // 完整保留,不能被老 enum 抹平。
        assertTrue(
            "Concept conceptCategory=Mechanism must be preserved verbatim",
            parsed.conceptsJson.contains("\"conceptCategory\":\"Mechanism\"") ||
                parsed.conceptsJson.contains("\"conceptCategory\": \"Mechanism\"")
        )
        assertTrue(
            "Concept conceptCategory=Theory must be preserved verbatim",
            parsed.conceptsJson.contains("\"conceptCategory\":\"Theory\"") ||
                parsed.conceptsJson.contains("\"conceptCategory\": \"Theory\"")
        )
        // Regression check: the previous code set
        //   conceptsJson = tags.toJsonArray()
        // which meant a "concept" page was synthesized for each
        // tag. The fix puts real concept objects in here instead.
        // We assert by looking at each concept's top-level `name`
        // (NOT the raw string match, because concepts reference
        // entity names like "Raft" inside `related_entities`, so
        // string-contains is too coarse here).
        val conceptNames = JSONArray(parsed.conceptsJson).let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
        }
        val tagNames = setOf("分布式系统", "共识算法", "Raft")
        assertTrue(
            "Top-level concept names must NOT equal raw tag names (regression: was tags.toJsonArray())",
            conceptNames.none { it in tagNames }
        )
        assertTrue(
            "Relations column must contain Raft→Paxos etc. (regression: was hard-coded to [])",
            parsed.relationsJson.contains("\"Raft\"") &&
                parsed.relationsJson.contains("\"Paxos\"") &&
                parsed.relationsJson.contains("uses")
        )
        assertTrue("Claims column must be present", parsed.claimsJson.contains("Paxos"))
        assertTrue("Gaps column must be present", parsed.gapsJson.contains("性能基准"))
        assertEquals(0.9f, parsed.confidence, 0.001f)
        assertEquals(3, parsed.entityCount)
        assertEquals(3, parsed.conceptCount)
        assertEquals(4, parsed.relationCount)
    }

    /**
     * P1: backward-compat — 老的 LLM 输出(只有 `type` 字段没有
     * `entityType`、只有 `category` 字段没有 `conceptCategory`)仍然能
     * 被正确解析。`ParsedAnalysis.fromObj` 不应该强制要求新字段。
     */
    @Test
    fun `ParsedAnalysis fromObj accepts legacy type and category fields as alias`() {
        val legacy = """
            {
              "title": "Legacy",
              "summary": "S",
              "tags": ["x"],
              "entities": [
                {"name": "Raft", "type": "Algorithm", "description": "d"}
              ],
              "concepts": [
                {"name": "Election", "category": "Mechanism", "definition": "d"}
              ],
              "relations": [],
              "claims": [],
              "gaps": [],
              "archiveRecommendation": {"targetKnowledgeBaseId":null,"targetKnowledgeBaseName":"","confidence":0.5,"reason":"x","suggestCreateNewBase":false,"newBaseName":null},
              "confidence": 0.5,
              "needHumanReview": false,
              "reviewReasons": []
            }
        """.trimIndent()
        val obj = IngestJsonValidator.parseObjectOrNull(legacy)!!
        val parsed = ParsedAnalysis.fromObj(obj, fallbackTags = emptyList(), fallbackConfidence = 0.5f, aiSucceeded = true)
        // 老的 type / category 字段必须完整保留——因为 `entitiesJson` / `conceptsJson`
        // 是直接存储 LLM 输出,不修改;下游 `WikiPageCompiler.parseNamedObjects` 才
        // 做双字段兼容 fallback。
        val entities = JSONArray(parsed.entitiesJson)
        val raft = entities.getJSONObject(0)
        assertEquals("Algorithm", raft.optString("type"))
        val concepts = JSONArray(parsed.conceptsJson)
        val election = concepts.getJSONObject(0)
        assertEquals("Mechanism", election.optString("category"))
        assertEquals(1, parsed.entityCount)
        assertEquals(1, parsed.conceptCount)
    }

    @Test
    fun `ParsedAnalysis fromObj filters out entities and relations without required names`() {
        val dirty = """
            {
              "title": "T",
              "summary": "S",
              "tags": ["a", "b"],
              "entities": [
                {"name": "Good", "type": "Person", "description": "x"},
                {"name": "", "type": "Person", "description": "missing-name should drop"},
                {"type": "Person", "description": "no-name at all should drop"}
              ],
              "concepts": [],
              "relations": [
                {"source": "Good", "target": "Good", "type": "uses", "confidence": 0.5},
                {"source": "Good", "target": "", "type": "uses", "confidence": 0.5},
                {"source": "", "target": "Good", "type": "uses", "confidence": 0.5},
                {"source": "Good", "target": "Other", "type": "made_up_type", "confidence": 0.5}
              ],
              "claims": [],
              "gaps": [],
              "archiveRecommendation": {"targetKnowledgeBaseId": null, "targetKnowledgeBaseName": "", "confidence": 0.5, "reason": "x", "suggestCreateNewBase": false, "newBaseName": null},
              "confidence": 0.5,
              "needHumanReview": false,
              "reviewReasons": []
            }
        """.trimIndent()
        val obj = IngestJsonValidator.parseObjectOrNull(dirty)!!
        val parsed = ParsedAnalysis.fromObj(obj, fallbackTags, 0.5f, aiSucceeded = true)

        val entities = JSONArray(parsed.entitiesJson)
        assertEquals("Only 1 entity should survive name validation", 1, entities.length())
        assertEquals("Good", entities.getJSONObject(0).getString("name"))

        val relations = JSONArray(parsed.relationsJson)
        assertEquals(
            "Only 1 relation should survive (the others are self-loop, missing target/source, or invalid type — but our sanitizer rewrites invalid type to 'related_to' so we keep the Good->Other row)",
            1,
            relations.length()
        )
        val only = relations.getJSONObject(0)
        assertEquals("Good", only.getString("source"))
        assertEquals("Other", only.getString("target"))
        assertEquals("related_to", only.getString("type"))
    }

    @Test
    fun `ParsedAnalysis fromObj falls back to local tags when AI tags are empty`() {
        val noTags = """
            {
              "title": "T",
              "summary": "S",
              "tags": [],
              "entities": [],
              "concepts": [],
              "relations": [],
              "claims": [],
              "gaps": [],
              "archiveRecommendation": {"targetKnowledgeBaseId": null, "targetKnowledgeBaseName": "", "confidence": 0.5, "reason": "x", "suggestCreateNewBase": false, "newBaseName": null},
              "confidence": 0.5,
              "needHumanReview": false,
              "reviewReasons": []
            }
        """.trimIndent()
        val obj = IngestJsonValidator.parseObjectOrNull(noTags)!!
        val parsed = ParsedAnalysis.fromObj(obj, fallbackTags, 0.5f, aiSucceeded = true)
        assertTrue("Tags must fall back to local", parsed.tagsJson.contains("分布式"))
    }

    @Test
    fun `ParsedAnalysis fromFallback keeps entities and relations empty (no tag pollution)`() {
        val parsed = ParsedAnalysis.fromFallback(fallbackTags, fallbackConfidence = 0.42f)
        assertEquals("[]", parsed.entitiesJson)
        assertEquals("[]", parsed.conceptsJson)
        assertEquals("[]", parsed.relationsJson)
        // The fallback must NOT invent entities from tags. This is the
        // exact regression that produced tag-named "concept" pages with
        // empty descriptions in the previous implementation.
        assertFalse(
            "Fallback must not put tags into entities column",
            parsed.entitiesJson.contains("分布式")
        )
        assertFalse(
            "Fallback must not put tags into concepts column (regression check)",
            parsed.conceptsJson.contains("分布式")
        )
        assertTrue(parsed.tagsJson.contains("分布式"))
    }

    @Test
    fun `IngestJsonValidator normalizeAnalysisJson merges raw with fallback when fields missing`() {
        val partial = """{"title": "Partial", "summary": "Only summary", "tags": ["x"]}"""
        val fallback = IngestJsonValidator.fallbackAnalysisJson(
            title = "fallback-title",
            summary = "fallback-summary",
            tagsJson = """["a", "b"]""",
            confidence = 0.5f,
            reviewReason = null
        )
        val normalized = IngestJsonValidator.normalizeAnalysisJson(partial, fallback)
        val obj = IngestJsonValidator.parseObjectOrNull(normalized)!!
        assertEquals("Raw title wins when present", "Partial", IngestJsonValidator.string(obj, "title", ""))
        assertEquals("Raw tags win when present", "[\"x\"]", IngestJsonValidator.arrayAsJson(obj, "tags"))
        // Fallback fills missing required fields so validateAnalysisJson
        // passes after normalization.
        assertTrue("Normalized must pass validateAnalysisJson", IngestJsonValidator.validateAnalysisJson(normalized))
    }

    @Test
    fun `IngestJsonValidator fallbackAnalysisJson yields parseable JSON with required fields`() {
        val fallback = IngestJsonValidator.fallbackAnalysisJson(
            title = "F",
            summary = "S",
            tagsJson = """["t1"]""",
            confidence = 0.3f,
            reviewReason = "short"
        )
        val obj = IngestJsonValidator.parseObjectOrNull(fallback)
        assertTrue("Fallback JSON must parse", obj != null)
        assertTrue("Fallback must pass validation", IngestJsonValidator.validateAnalysisJson(fallback))
        // A confidence < 0.6 means needHumanReview must be true and
        // reviewReasons must contain the review reason.
        val needsReview = obj!!.toString().contains("\"needHumanReview\": true") ||
            obj.toString().contains("\"needHumanReview\":true")
        assertTrue("Low confidence must trigger needHumanReview", needsReview)
    }

    @Test
    fun `ParsedAnalysis fromObj with parse error note appends to gaps`() {
        val obj = IngestJsonValidator.parseObjectOrNull(validLlmOutput)!!
        val note = "AI 未能返回有效 JSON,已使用本地摘要兜底。原始输出前 200 字符: foo"
        val parsed = ParsedAnalysis.fromObj(
            obj = obj,
            fallbackTags = fallbackTags,
            fallbackConfidence = 0.5f,
            aiSucceeded = false,
            parseErrorNote = note,
        )
        // Original gaps from the JSON + the parse error note must both
        // be present in the final gaps column.
        assertTrue("Original gap must survive", parsed.gapsJson.contains("性能基准"))
        assertTrue("Parse error note must be appended", parsed.gapsJson.contains("AI 未能返回有效 JSON"))
    }

    @Test
    fun `regression the broken hard-coded values are gone`() {
        // Direct check that no string literal `"[]"` shows up as the
        // default in any test that runs the LLM-success path. The
        // previous orchestrator code wrote exactly these hard-coded
        // values into the AnalysisResultEntity columns.
        val obj = IngestJsonValidator.parseObjectOrNull(validLlmOutput)!!
        val parsed = ParsedAnalysis.fromObj(obj, fallbackTags, 0.5f, aiSucceeded = true)
        assertNotEquals("Entities column must not be the bug's hard-coded empty array", "[]", parsed.entitiesJson)
        assertNotEquals("Concepts column must not be the bug's hard-coded tags array", parsed.tagsJson, parsed.conceptsJson)
        assertNotEquals("Relations column must not be the bug's hard-coded empty array", "[]", parsed.relationsJson)
    }
}
