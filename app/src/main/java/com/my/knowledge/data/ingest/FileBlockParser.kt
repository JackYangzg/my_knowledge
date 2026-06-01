package com.my.knowledge.data.ingest

/**
 * Parser for LLM multi-file output format.
 * Format:
 * ---FILE: path/to/file---
 * (content)
 * ---END FILE---
 */
object FileBlockParser {
    private val OPENER_REGEX = Regex("^---FILE:\\s*(.+?)\\s*---$", RegexOption.MULTILINE)
    private val CLOSER_REGEX = Regex("^---END\\s+FILE---$", RegexOption.MULTILINE)

    data class ParsedBlock(val path: String, val content: String)

    fun parse(text: String): List<ParsedBlock> {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        val blocks = mutableListOf<ParsedBlock>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("---FILE:") && line.endsWith("---")) {
                val path = line.removePrefix("---FILE:").removeSuffix("---").trim()
                i++
                val contentLines = mutableListOf<String>()
                var closed = false
                while (i < lines.size) {
                    val innerLine = lines[i]
                    if (innerLine.trim().equals("---END FILE", ignoreCase = true) || 
                        innerLine.trim().equals("---END FILE---", ignoreCase = true)) {
                        closed = true
                        i++
                        break
                    }
                    contentLines.add(innerLine)
                    i++
                }
                if (closed) {
                    blocks.add(ParsedBlock(path, contentLines.joinToString("\n").trim()))
                }
            } else {
                i++
            }
        }
        return blocks
    }
}
