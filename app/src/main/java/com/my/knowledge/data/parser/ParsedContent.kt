package com.my.knowledge.data.parser

data class ParsedContent(
    val parserType: String,
    val markdown: String,
    val plainText: String,
    val metadataJson: String
)
