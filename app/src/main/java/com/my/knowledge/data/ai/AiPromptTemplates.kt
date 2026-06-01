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
     * Prompt for analyzing new knowledge content
     * Two-step ingest - Step 1: Analysis
     */
    fun analysisPrompt(title: String, content: String): String = """
请分析以下内容，提取关键信息：

标题：$title

内容：
$content

请输出 JSON 格式的分析结果，包含：
{
  "summary": "简要摘要（50字以内）",
  "tags": ["标签1", "标签2", ...],
  "entities": ["实体1", "实体2", ...],
  "concepts": ["概念1", "概念2", ...],
  "fragments": ["知识片段1", "知识片段2", ...],
  "archiveRecommendation": "归档建议",
  "relatedKnowledgeIds": ["相关知识ID1", ...]
}

只输出 JSON，不要有其他文字。
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
