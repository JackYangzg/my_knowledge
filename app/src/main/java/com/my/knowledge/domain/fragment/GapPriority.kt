package com.my.knowledge.domain.fragment

/** FRAG-1: gap priority bucket. HIGH renders first in the detail page. */
enum class GapPriority {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromName(name: String?): GapPriority = entries.firstOrNull { it.name == name }
            ?: MEDIUM
    }
}
