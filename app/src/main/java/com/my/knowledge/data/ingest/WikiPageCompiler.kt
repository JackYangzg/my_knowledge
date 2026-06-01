package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.entity.AnalysisResultEntity
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

data class WikiPageDraft(
    val type: String,
    val title: String,
    val sourceType: String,
    val markdown: String,
    val summary: String,
    val tagsJson: String,
    val sourceTraceJson: String
)

class WikiPageCompiler {
    fun compile(
        source: SourceDocumentEntity,
        parsed: ParsedContentEntity,
        analysis: AnalysisResultEntity
    ): List<WikiPageDraft> {
        val sourceTitle = source.title.trim().ifBlank { "未命名来源" }
        val entities = parseNamedObjects(analysis.entitiesJson, fallbackType = "entity")
        val concepts = parseNamedObjects(analysis.conceptsJson, fallbackType = "concept")
        val allNames = (entities + concepts).map { it.name }.distinct()
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val sourceRef = sourceTitle.escapeYaml()
        val tags = parseStringArray(analysis.tagsJson).take(8)
        val pages = mutableListOf<WikiPageDraft>()

        pages += WikiPageDraft(
            type = "source",
            title = sourceTitle,
            sourceType = "wiki_source",
            markdown = buildString {
                appendFrontMatter(
                    type = "source",
                    title = sourceTitle,
                    created = today,
                    updated = today,
                    tags = tags,
                    related = allNames,
                    sources = listOf(sourceRef)
                )
                appendLine("# $sourceTitle")
                appendLine()
                appendLine("## 摘要")
                appendLine(analysis.summary.ifBlank { parsed.plainText.take(240) })
                appendLine()
                if (allNames.isNotEmpty()) {
                    appendLine("## 相关页面")
                    allNames.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
                    appendLine()
                }
                appendLine("## 原始内容")
                appendLine(parsed.markdown.take(12_000))
            },
            summary = analysis.summary,
            tagsJson = analysis.tagsJson,
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/sources/${sourceTitle.slug()}.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "index.md",
            sourceType = "wiki_index",
            markdown = buildString {
                appendFrontMatter("synthesis", "index.md", today, today, tags, allNames, listOf(sourceRef))
                appendLine("# Wiki Index")
                appendLine()
                appendLine("## Sources")
                appendLine("- [[${sourceTitle.escapeWikiLink()}]]")
                appendLine()
                if (entities.isNotEmpty()) {
                    appendLine("## Entities")
                    entities.forEach { appendLine("- [[${it.name.escapeWikiLink()}]]") }
                    appendLine()
                }
                if (concepts.isNotEmpty()) {
                    appendLine("## Concepts")
                    concepts.forEach { appendLine("- [[${it.name.escapeWikiLink()}]]") }
                    appendLine()
                }
            },
            summary = "Wiki 索引",
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/index.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "overview.md",
            sourceType = "wiki_overview",
            markdown = buildString {
                appendFrontMatter("synthesis", "overview.md", today, today, tags, allNames, listOf(sourceRef))
                appendLine("# Overview")
                appendLine()
                appendLine("## 本次新增来源")
                appendLine("- [[${sourceTitle.escapeWikiLink()}]]：${analysis.summary}")
                appendLine()
                if (allNames.isNotEmpty()) {
                    appendLine("## 关键连接")
                    allNames.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
                }
            },
            summary = analysis.summary.take(180),
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/overview.md")
        )

        pages += WikiPageDraft(
            type = "synthesis",
            title = "log.md",
            sourceType = "wiki_log",
            markdown = buildString {
                appendFrontMatter("synthesis", "log.md", today, today, tags, listOf(sourceTitle), listOf(sourceRef))
                appendLine("# Ingest Log")
                appendLine()
                appendLine("## $today")
                appendLine("- Ingested [[${sourceTitle.escapeWikiLink()}]]")
                appendLine("- Generated ${entities.size} entity pages and ${concepts.size} concept pages")
                if (analysis.confidence < 0.6f) appendLine("- REVIEW: low confidence ${analysis.confidence}")
            },
            summary = "Ingest log for $sourceTitle",
            tagsJson = tags.toJsonArray(),
            sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/log.md")
        )

        entities.forEach { entity ->
            val related = (entity.related + concepts.map { it.name }).distinct().filterNot { it == entity.name }.take(12)
            pages += WikiPageDraft(
                type = entity.type.ifBlank { "entity" },
                title = entity.name,
                sourceType = "wiki_entity",
                markdown = buildEntityPage(entity, today, tags, sourceRef, related),
                summary = entity.description.ifBlank { "实体：${entity.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/entities/${entity.name.slug()}.md")
            )
        }

        concepts.forEach { concept ->
            val related = (concept.related + entities.map { it.name }).distinct().filterNot { it == concept.name }.take(12)
            pages += WikiPageDraft(
                type = "concept",
                title = concept.name,
                sourceType = "wiki_concept",
                markdown = buildConceptPage(concept, today, tags, sourceRef, related),
                summary = concept.description.ifBlank { "概念：${concept.name}" },
                tagsJson = tags.toJsonArray(),
                sourceTraceJson = sourceTrace(source, parsed, analysis, "wiki/concepts/${concept.name.slug()}.md")
            )
        }

        return pages
    }

    fun merge(existingMarkdown: String, incomingMarkdown: String): String {
        if (existingMarkdown.isBlank()) return incomingMarkdown
        if (incomingMarkdown.contains("title: log.md")) {
            val incomingBody = incomingMarkdown.substringAfter("---", incomingMarkdown).substringAfter("---", incomingMarkdown).trim()
            return existingMarkdown.trimEnd() + "\n\n" + incomingBody.substringAfter("# Ingest Log").trim()
        }
        val existingSources = extractFrontMatterList(existingMarkdown, "sources")
        val incomingSources = extractFrontMatterList(incomingMarkdown, "sources")
        val mergedSources = (existingSources + incomingSources).distinct()
        val existingBody = existingMarkdown.substringAfter("---", existingMarkdown).substringAfter("---", existingMarkdown).trim()
        val incomingBody = incomingMarkdown.substringAfter("---", incomingMarkdown).substringAfter("---", incomingMarkdown).trim()
        if (existingBody.contains(incomingBody.take(120))) return existingMarkdown
        return incomingMarkdown.replaceFirst(
            Regex("sources:\\s*\\[[^\\]]*]"),
            "sources: ${mergedSources.toYamlArray()}"
        ).trim() + "\n\n## 合并自旧页面\n\n" + existingBody
    }

    private fun buildEntityPage(
        entity: WikiObject,
        today: String,
        tags: List<String>,
        sourceRef: String,
        related: List<String>
    ): String = buildString {
        appendFrontMatter(entity.type.ifBlank { "entity" }, entity.name, today, today, tags, related, listOf(sourceRef))
        appendLine("# ${entity.name}")
        appendLine()
        appendLine("## 定义")
        appendLine(entity.description.ifBlank { "从来源资料中识别出的实体。" })
        appendLine()
        appendLine("## 在来源中的作用")
        appendLine(entity.role.ifBlank { "与来源主题相关。" })
        appendLine()
        if (entity.evidence.isNotBlank()) {
            appendLine("## 证据")
            appendLine("> ${entity.evidence}")
            appendLine()
        }
        if (related.isNotEmpty()) {
            appendLine("## 相关")
            related.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
        }
    }

    private fun buildConceptPage(
        concept: WikiObject,
        today: String,
        tags: List<String>,
        sourceRef: String,
        related: List<String>
    ): String = buildString {
        appendFrontMatter("concept", concept.name, today, today, tags, related, listOf(sourceRef))
        appendLine("# ${concept.name}")
        appendLine()
        appendLine("## 定义")
        appendLine(concept.description.ifBlank { "从来源资料中识别出的概念。" })
        appendLine()
        appendLine("## 为什么重要")
        appendLine(concept.role.ifBlank { "它帮助组织和连接来源资料中的关键观点。" })
        appendLine()
        if (concept.evidence.isNotBlank()) {
            appendLine("## 来源语境")
            appendLine("> ${concept.evidence}")
            appendLine()
        }
        if (related.isNotEmpty()) {
            appendLine("## 相关")
            related.forEach { appendLine("- [[${it.escapeWikiLink()}]]") }
        }
    }

    private fun StringBuilder.appendFrontMatter(
        type: String,
        title: String,
        created: String,
        updated: String,
        tags: List<String>,
        related: List<String>,
        sources: List<String>
    ) {
        appendLine("---")
        appendLine("type: ${type.escapeYaml()}")
        appendLine("title: ${title.escapeYaml()}")
        appendLine("created: $created")
        appendLine("updated: $updated")
        appendLine("tags: ${tags.toYamlArray()}")
        appendLine("related: ${related.toYamlArray()}")
        appendLine("sources: ${sources.toYamlArray()}")
        appendLine("---")
    }

    private fun parseNamedObjects(json: String, fallbackType: String): List<WikiObject> {
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                when (val value = array.opt(index)) {
                    is JSONObject -> {
                        val name = value.optString("name").ifBlank { value.optString("title") }.trim()
                        if (name.isBlank()) null else WikiObject(
                            name = name,
                            type = value.optString("type", fallbackType).ifBlank { fallbackType },
                            description = value.optString("description").ifBlank { value.optString("definition") },
                            role = value.optString("role_in_source").ifBlank { value.optString("why_it_matters") },
                            evidence = value.optString("evidence").ifBlank { value.optString("source_context") },
                            related = parseStringArray(value.optJSONArray("related_concepts")) +
                                parseStringArray(value.optJSONArray("related_entities"))
                        )
                    }
                    is String -> WikiObject(name = value, type = fallbackType)
                    else -> null
                }
            }
        }.getOrElse {
            parseStringArray(json).map { WikiObject(name = it, type = fallbackType) }
        }.distinctBy { it.name.lowercase() }.take(24)
    }

    private fun parseStringArray(json: String): List<String> =
        runCatching { parseStringArray(JSONArray(json)) }.getOrDefault(emptyList())

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { value -> value.isNotBlank() } }
    }

    private fun extractFrontMatterList(markdown: String, key: String): List<String> {
        val line = markdown.lines().firstOrNull { it.trimStart().startsWith("$key:") } ?: return emptyList()
        return line.substringAfter("[").substringBefore("]").split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
    }

    private fun sourceTrace(source: SourceDocumentEntity, parsed: ParsedContentEntity, analysis: AnalysisResultEntity, path: String): String =
        """{"wikiPath":"${path.escapeJson()}","sourceId":"${source.id}","parsedContentId":"${parsed.id}","analysisResultId":"${analysis.id}","sourceTitle":"${source.title.escapeJson()}"}"""

    private data class WikiObject(
        val name: String,
        val type: String = "entity",
        val description: String = "",
        val role: String = "",
        val evidence: String = "",
        val related: List<String> = emptyList()
    )
}

private fun List<String>.toYamlArray(): String =
    distinct().filter { it.isNotBlank() }.joinToString(", ", "[", "]") { "\"${it.escapeYaml()}\"" }

private fun List<String>.toJsonArray(): String =
    distinct().filter { it.isNotBlank() }.joinToString(",", "[", "]") { "\"${it.escapeJson()}\"" }

private fun String.slug(): String {
    val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase()
    return normalized.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "page" }
}

private fun String.escapeWikiLink(): String = replace("[[", "").replace("]]", "").trim()

private fun String.escapeYaml(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
