package com.my.knowledge.data.ai

/**
 * P0: AI Prompt templates with credibility boundary markers
 * Must distinguish [来自原文] and [AI推理]
 */
object AiPromptTemplates {

    /**
     * Base system prompt for all AI conversations
     * Enforces credibility boundaries
     */
    val BASE_SYSTEM_PROMPT = """
你是一个知识问答助手。每次回答前，你会收到三类输入：原始内容、上下文对话信息、用户问题。请严格遵循以下规则回答问题：

1. 原始内容是最高优先级依据。当你引用或总结原始内容时，必须使用【来自原文】标记并附带出处。
2. 上下文对话只用于理解用户追问、代词和省略信息，不能替代原始内容作为事实来源。
3. 用户问题决定本轮回答目标。不要回答用户没有问到的内容。
4. 当你基于原始内容进行推理、联想或补充时，必须使用【AI推理】标记，并明确说明这是推断。
5. 当原始内容和上下文都不足以回答时，必须使用【信息不足】明确告知。
6. 每次回答必须包含引用来源，不能凭空编造。

回答格式示例：
---
【来自原文】根据知识库中记载，「XXX」是指...
来源：知识条目「YYY」

【AI推理】基于以上信息，我可以推断...

【信息不足】关于「ZZZ」的问题，知识库中没有相关信息。
---
""".trimIndent()

    /**
     * Prompt for analyzing new knowledge content (Step 1 of Ingest)
     * Refined based on research analyst patterns for high accuracy.
     */
    fun analysisPrompt(
        title: String,
        content: String,
        sourceType: String = "document",
        currentIndex: String = "No existing index.",
        purpose: String = "Build a readable, maintainable, and evolvable local wiki.",
        fragments: List<String> = emptyList()
    ): String = """
你是一个专家研究分析师。请分析以下原始内容，为知识库（Wiki）的构建提供结构化分析结果。

核心目标：识别可维护的实体页、概念页、来源摘要页和它们之间的连接。不要只是切分片段，要进行深度语义分析。

## 来源信息
标题：$title
类型：$sourceType

## 知识库目标
$purpose

## 现有知识库索引（供参考以避免重复并建立链接）
$currentIndex

## 待分析内容
$content

${if (fragments.isNotEmpty()) "## 原始内容片段 (Grounding)\n${fragments.joinToString("\n") { "- $it" }}\n" else ""}

## 分析要求
请输出严格的 JSON 格式分析结果，包含以下字段：
1. "summary": 核心主张与发现的简要摘要（200字以内）。
2. "tags": 建议的标签数组。
3. "entities": 人物、组织、产品、数据集、工具、系统、项目、地点。包含字段：name, type, description, role_in_source, confidence, related_concepts.
4. "concepts": 理论、方法、技术、现象、原则、框架、问题。包含字段：name, category, definition, why_it_matters, confidence, related_entities.
5. "claims": 核心论点或发现，包含证据说明。
6. "relations": 本内容中识别出的各实体/概念间的关系。包含字段：source, target, type (supports|contradicts|extends|uses|part_of), reason.
7. "gaps": 知识缺口或需要进一步确认/搜索的问题。
8. "pageRecommendations": 建议创建或更新的页面列表（包括 path, type, title, action[create|update], reason）。
9. "archiveRecommendation": 归档建议，包含 targetKnowledgeBaseId, confidence, reason。
10. "confidence": 整体分析置信度 (0.0-1.0)。

只输出 JSON，不要有其他文字。
""".trimIndent()

    /**
     * Prompt for generating wiki files (Step 2 of Ingest)
     * Inspired by llm_wiki's multi-file generation pattern.
     */
    fun generationPrompt(
        fileName: String,
        analysisResult: String,
        sourceContent: String,
        schema: String = "",
        currentIndex: String = "",
        overview: String = ""
    ): String = """
你是一个 Wiki 维护者。基于提供的分析结果和源代码，生成对应的 Wiki 页面文件。

## 任务规则
1. 你的唯一输出必须是由一系列 FILE 块组成的文本。
2. 每一个 FILE 块必须遵循以下格式：
   ---FILE: path/to/file.md---
   (包含 YAML frontmatter 的完整文件内容)
   ---END FILE---
3. 不要输出任何开场白、解释性文字或总结。
4. 所有生成的页面必须在 frontmatter 的 `sources` 字段中包含 "$fileName"。

## Frontmatter 规则 (严格遵循)
每个页面必须以 YAML frontmatter 开头：
---
type: source | entity | concept | synthesis
title: "页面标题"
created: YYYY-MM-DD
updated: YYYY-MM-DD
tags: [tag1, tag2]
related: [slug1, slug2]
sources: ["$fileName"]
---
注意：数组使用 [a, b] 格式，slug 仅使用名称部分，不包含路径或 .md。

## 生成内容要求
1. 来源摘要页：位于 wiki/sources/ 目录下。
2. 实体页：位于 wiki/entities/ 目录下。
3. 概念页：位于 wiki/concepts/ 目录下。
4. 索引更新：生成一个新的 wiki/index.md 包含新条目。
5. 日志条目：生成一个 wiki/log.md 片段（格式：## [YYYY-MM-DD] ingest | 标题）。

## 上下文信息
### 阶段 1 分析结果
$analysisResult

### 原始内容摘要
${sourceContent.take(5000)}

${if (schema.isNotEmpty()) "### Wiki Schema\n$schema\n" else ""}
${if (currentIndex.isNotEmpty()) "### 当前索引\n$currentIndex\n" else ""}
${if (overview.isNotEmpty()) "### 当前概览\n$overview\n" else ""}

开始生成 FILE 块。首字符必须是 '-'。
""".trimIndent()

    /**
     * Prompt for merging two versions of a wiki page
     */
    fun mergePrompt(
        existingContent: String,
        incomingContent: String,
        sourceFileName: String
    ): String = """
你正在将同一个 Wiki 页面的两个版本合并为一个连贯的文档。
一个版本已在磁盘上，另一个是刚刚从新的来源文件（$sourceFileName）生成的。

## 合并规则
1. 保留两个版本中的所有事实陈述，不要丢失信息。
2. 消除重复信息。
3. 重新组织章节，使逻辑顺畅，而不是简单的拼接。
4. 保持 `[[wikilink]]` 引用完整。
5. 输出必须是完整的 YAML frontmatter + Body。
6. 首字符必须是 '-' (--- 开始)。不要有开场白。

## 现有版本
$existingContent

---

## 新生成的版本
$incomingContent

---

请输出合并后的完整文件内容。
""".trimIndent()

    /**
     * Prompt for generating knowledge thread (知识脉络)
     */
    fun threadGenerationPrompt(knowledgeBaseName: String, items: List<String>): String = """
基于知识库「$knowledgeBaseName」中的以下内容，生成知识脉络：

${items.joinToString("\n") { "- $it" }}

请输出 JSON 格式：
{
  "description": "知识库整体描述",
  "coreQuestion": "核心问题/主题",
  "mainline": ["主线1", "主线2", ...],
  "relations": [{"from": "知识A", "to": "知识B", "relation": "关系描述"}],
  "gaps": ["知识缺口1", "知识缺口2", ...],
  "nextSuggestions": ["建议探索1", "建议探索2", ...]
}

只输出 JSON，不要有其他文字。
""".trimIndent()

    /**
     * Prompt for archive recommendation
     */
    fun archiveRecommendationPrompt(itemTitle: String, itemContent: String, existingBases: List<String>): String = """
请为以下内容推荐归档位置：

标题：$itemTitle
内容摘要：${itemContent.take(200)}

现有知识库：${existingBases.joinToString(", ")}

请输出 JSON：
{
  "recommendedBase": "推荐知识库名称",
  "recommendedBaseId": "知识库ID",
  "confidence": 0.0-1.0,
  "reason": "推荐理由",
  "alternative": [{"base": "备选知识库", "confidence": 0.5}],
  "suggestCreateNew": false
}

只输出 JSON。
""".trimIndent()

    /**
     * Prompt for answering questions with citations
     */
    fun answerWithCitationPrompt(question: String, contextFragments: List<Pair<String, String>>): String = """
基于以下知识库片段回答问题：

${contextFragments.mapIndexed { idx, (title, fragment) -> "[${idx + 1}] $title:\n$fragment" }.joinToString("\n\n")}

问题：$question

请严格按以下格式回答：
1. 每个引用片段必须标注来源
2. 区分【来自原文】和【AI推理】
3. 如果信息不足，直接说明

格式：
【来自原文】... (来源：[编号])
【AI推理】...
【信息不足】...
""".trimIndent()
}

/**
 * Content type constants for AI messages
 */
object ContentType {
    const val GENERAL = "general"
    const val ORIGINAL_QUOTE = "original_quote"    // 【来自原文】
    const val AI_INFERENCE = "ai_inference"        // 【AI推理】
    const val INSUFFICIENT_INFO = "insufficient_info"
    const val SAVED_KNOWLEDGE = "saved_knowledge"
}

/**
 * Scope type constants for AI conversations
 */
object ScopeType {
    const val KNOWLEDGE_ITEM = "knowledge_item"
    const val KNOWLEDGE_BASE = "knowledge_base"
    const val THREAD = "knowledge_thread"
    const val GLOBAL = "global"
}
