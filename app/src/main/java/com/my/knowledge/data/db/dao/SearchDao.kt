package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.KnowledgeSearchResult
import com.my.knowledge.data.search.SemanticSearchCandidate
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    // ─── Item FTS / LIKE ─────────────────────────────────────────────
    // These stay as Flow: AskViewModel chains them through
    // `flatMapLatest` over a debounced query StateFlow, so reactivity
    // is the actual contract — the FTS rewrite (PERF-7) only swapped
    // the SQL, not the data shape.

    @Query("""
        SELECT knowledge_item.* FROM knowledge_item
        JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
        WHERE knowledge_item_fts MATCH :query
        AND knowledge_item.deletedAt IS NULL
        ORDER BY knowledge_item.updatedAt DESC
    """)
    fun ftsSearchAll(query: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT knowledge_item.* FROM knowledge_item
        JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
        WHERE knowledge_item_fts MATCH :query
        AND knowledge_item.knowledgeBaseId = :kbId
        AND knowledge_item.deletedAt IS NULL
        ORDER BY knowledge_item.updatedAt DESC
    """)
    fun ftsSearchByKb(query: String, kbId: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT * FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        AND deletedAt IS NULL
    """)
    fun searchAll(query: String): Flow<List<KnowledgeItemEntity>>

    @Query("""
        SELECT * FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        AND knowledgeBaseId = :kbId
        AND deletedAt IS NULL
    """)
    fun searchByKb(query: String, kbId: String): Flow<List<KnowledgeItemEntity>>

    // ─── Result shapes for Ask pipeline ─────────────────────────────
    // PERF-8: drop the Flow wrapper. Every caller of SearchEngine
    // .searchResults treated the Flow as a one-shot (`firstOrNull()`),
    // which means the per-row InvalidationTracker subscription that
    // Room sets up for a Flow return type was pure overhead — it
    // re-ran the query on every write to the table even though the
    // caller never re-collected. A `suspend fun ... : List` is one
    // query, no observer.

    @Query("""
        SELECT
            ki.id AS itemId,
            kf.id AS fragmentId,
            ki.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            kf.content AS snippet,
            ki.sourceType AS sourceType,
            2.0 AS score,
            'fragment' AS matchType
        FROM knowledge_fragment kf
        INNER JOIN knowledge_item ki ON ki.id = kf.itemId
        WHERE (kf.content LIKE '%' || :query || '%' OR kf.summary LIKE '%' || :query || '%' OR kf.tagsJson LIKE '%' || :query || '%')
        AND ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchFragmentsAll(query: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            ki.id AS itemId,
            kf.id AS fragmentId,
            ki.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            kf.content AS snippet,
            ki.sourceType AS sourceType,
            2.0 AS score,
            'fragment' AS matchType
        FROM knowledge_fragment_fts fts
        INNER JOIN knowledge_fragment kf ON kf.rowid = fts.rowid
        INNER JOIN knowledge_item ki ON ki.id = kf.itemId
        WHERE knowledge_fragment_fts MATCH :query
        AND ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun ftsSearchFragmentsAll(query: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            ki.id AS itemId,
            kf.id AS fragmentId,
            ki.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            kf.content AS snippet,
            ki.sourceType AS sourceType,
            2.0 AS score,
            'fragment' AS matchType
        FROM knowledge_fragment kf
        INNER JOIN knowledge_item ki ON ki.id = kf.itemId
        WHERE (kf.content LIKE '%' || :query || '%' OR kf.summary LIKE '%' || :query || '%' OR kf.tagsJson LIKE '%' || :query || '%')
        AND ki.knowledgeBaseId = :kbId
        AND ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchFragmentsByKb(query: String, kbId: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            ki.id AS itemId,
            kf.id AS fragmentId,
            ki.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            kf.content AS snippet,
            ki.sourceType AS sourceType,
            2.0 AS score,
            'fragment' AS matchType
        FROM knowledge_fragment_fts fts
        INNER JOIN knowledge_fragment kf ON kf.rowid = fts.rowid
        INNER JOIN knowledge_item ki ON ki.id = kf.itemId
        WHERE knowledge_fragment_fts MATCH :query
        AND ki.knowledgeBaseId = :kbId
        AND ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun ftsSearchFragmentsByKb(query: String, kbId: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            id AS itemId,
            NULL AS fragmentId,
            knowledgeBaseId AS knowledgeBaseId,
            title AS title,
            CASE
                WHEN summary IS NOT NULL AND summary != '' THEN summary
                ELSE excerpt
            END AS snippet,
            sourceType AS sourceType,
            1.0 AS score,
            'item' AS matchType
        FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tagsJson LIKE '%' || :query || '%')
        AND deletedAt IS NULL
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchItemsAsResultsAll(query: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            id AS itemId,
            NULL AS fragmentId,
            knowledgeBaseId AS knowledgeBaseId,
            title AS title,
            CASE
                WHEN summary IS NOT NULL AND summary != '' THEN summary
                ELSE excerpt
            END AS snippet,
            sourceType AS sourceType,
            1.0 AS score,
            'item' AS matchType
        FROM knowledge_item
        WHERE (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tagsJson LIKE '%' || :query || '%')
        AND knowledgeBaseId = :kbId
        AND deletedAt IS NULL
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchItemsAsResultsByKb(query: String, kbId: String, limit: Int): List<KnowledgeSearchResult>

    @Query("""
        SELECT
            ke.itemId AS itemId,
            ke.fragmentId AS fragmentId,
            ke.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            COALESCE(kf.content, ki.excerpt) AS snippet,
            ki.sourceType AS sourceType,
            ke.embeddingJson AS embeddingJson
        FROM knowledge_embedding ke
        INNER JOIN knowledge_item ki ON ki.id = ke.itemId
        LEFT JOIN knowledge_fragment kf ON kf.id = ke.fragmentId
        WHERE ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun semanticCandidatesAll(limit: Int): List<SemanticSearchCandidate>

    @Query("""
        SELECT
            ke.itemId AS itemId,
            ke.fragmentId AS fragmentId,
            ke.knowledgeBaseId AS knowledgeBaseId,
            ki.title AS title,
            COALESCE(kf.content, ki.excerpt) AS snippet,
            ki.sourceType AS sourceType,
            ke.embeddingJson AS embeddingJson
        FROM knowledge_embedding ke
        INNER JOIN knowledge_item ki ON ki.id = ke.itemId
        LEFT JOIN knowledge_fragment kf ON kf.id = ke.fragmentId
        WHERE ke.knowledgeBaseId = :kbId
        AND ki.deletedAt IS NULL
        ORDER BY ki.updatedAt DESC
        LIMIT :limit
    """)
    suspend fun semanticCandidatesByKb(kbId: String, limit: Int): List<SemanticSearchCandidate>
}
