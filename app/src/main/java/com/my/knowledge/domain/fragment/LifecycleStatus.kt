package com.my.knowledge.domain.fragment

/**
 * FRAG-1: lifecycle state of a knowledge fragment chain. Single source of
 * truth for the 4-tab FragmentOrganizeScreen filter (FRAG-1.3). Persisted
 * in `knowledge_fragment_chain.status` as the enum `.name` string.
 */
enum class LifecycleStatus {
    /** 待完善：chain 至少有一个未解决的 gap。 */
    NEED_REVIEW,
    /** 可提炼：gapsJson 为空 ∧ entity 平均度数 ≥ 2 ∧ 含 wiki_index/overview。 */
    DISTILL_READY,
    /** 提炼完成，等待用户确认归档。 */
    RECOMMEND_READY,
    /** 已归档：用户已确认进入知识库；标星/分享在 ARCHIVED 上才能用。 */
    ARCHIVED;

    companion object {
        fun fromName(name: String?): LifecycleStatus =
            entries.firstOrNull { it.name == name } ?: NEED_REVIEW

        /** Tab → status filter mapping for FragmentOrganizeScreen. */
        val TAB_ALL: Set<LifecycleStatus> = entries.toSet()
        val TAB_NEED_REVIEW: Set<LifecycleStatus> = setOf(NEED_REVIEW)
        val TAB_DISTILL_READY: Set<LifecycleStatus> = setOf(DISTILL_READY)
        val TAB_ARCHIVED: Set<LifecycleStatus> = setOf(RECOMMEND_READY, ARCHIVED)
    }
}
