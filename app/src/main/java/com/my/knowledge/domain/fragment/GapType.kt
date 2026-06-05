package com.my.knowledge.domain.fragment

/**
 * FRAG-1: 8 structured gap categories, 1:1 with the 8 rules in
 * `ThreadEvolutionRunner.detectGaps` (see FRAG-1 design §1.6). Persisted
 * in `knowledge_fragment_gap.gapType` as the enum `.name` string.
 */
enum class GapType(val priority: GapPriority) {
    KB_EMPTY(GapPriority.HIGH),
    NO_WIKI_PAGES(GapPriority.HIGH),
    MISSING_SYNTHESIS(GapPriority.HIGH),
    NO_RELATIONS(GapPriority.HIGH),
    LOW_CONFIDENCE(GapPriority.HIGH),
    NO_MAINLINE(GapPriority.MEDIUM),
    MISSING_TAGS(GapPriority.MEDIUM),
    MISSING_SUMMARY(GapPriority.LOW);

    companion object {
        fun fromName(name: String?): GapType = entries.firstOrNull { it.name == name }
            ?: MISSING_TAGS
    }
}
