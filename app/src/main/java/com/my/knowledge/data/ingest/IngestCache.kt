package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.dao.AnalysisResultDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.entity.ProcessingTaskEntity

/**
 * Compatibility shell for the old Room-backed cache fast path.
 * A content-hash-only hit is unsafe under llm_wiki semantics because
 * source identity is part of generated frontmatter. It therefore
 * returns a miss until Room persists sourceIdentity + filesWritten
 * and can verify every generated artifact still exists.
 */
class IngestCache(
    @Suppress("UNUSED_PARAMETER") sourceDao: SourceDocumentDao,
    @Suppress("UNUSED_PARAMETER") analysisDao: AnalysisResultDao,
) {
    suspend fun isHit(@Suppress("UNUSED_PARAMETER") task: ProcessingTaskEntity): Boolean {
        // llm_wiki keys ingest completion by source path identity, not
        // content hash alone. Android's old sibling-sha fast path skipped
        // Stage 1/2 for two different files with identical bytes, which
        // lost the second filename in frontmatter `sources`. Until Room
        // stores the full source-identity artifact set, a safe hit cannot
        // be proven here, so the parity behavior is an explicit miss.
        return false
    }
}
