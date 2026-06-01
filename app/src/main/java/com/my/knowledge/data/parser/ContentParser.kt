package com.my.knowledge.data.parser

import com.my.knowledge.data.db.entity.SourceDocumentEntity

interface ContentParser {
    fun supports(mimeType: String?, sourceType: String): Boolean
    suspend fun parse(source: SourceDocumentEntity): ParsedContent
}
