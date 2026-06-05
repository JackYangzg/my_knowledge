package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.search.KnowledgeSearchResult
import com.my.knowledge.data.search.SemanticSearchCandidate
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    // ─── Item FTS ─────────────────────────────────────────────────────
    // PERF-7: the FTS4 `knowledge_item_fts` and
    // `knowledge_fragment_fts` virtual tables are NOT Room entities
    // (see AppDatabase.entities and the shadow
    // `KnowledgeItemFts` / `KnowledgeFragmentFts` data classes).
    // Room therefore refuses to compile a `@Query` that references
    // them — the kapt schema validator sees "no such table". We use
    // `@RawQuery` with a hand-built `SupportSQLiteQuery` so the SQL
    // string reaches SQLite untouched. The actual FTS virtual
    // tables are created by AppDatabase.rebuildFtsWithDiacritics
    // (called from FtsDiacriticsCallback.onCreate for fresh
    // installs and from MIGRATION_10_11 for upgrades) once all
    // regular entity tables exist.

    @RawQuery(observedEntities = [KnowledgeItemEntity::class])
    fun ftsSearchAll(query: SupportSQLiteQuery): Flow<List<KnowledgeItemEntity>>

    @RawQuery(observedEntities = [KnowledgeItemEntity::class])
    fun ftsSearchByKb(query: SupportSQLiteQuery): Flow<List<KnowledgeItemEntity>>

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

    @RawQuery(observedEntities = [KnowledgeItemEntity::class])
    suspend fun ftsSearchFragmentsAll(query: SupportSQLiteQuery): List<KnowledgeSearchResult>

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

    @RawQuery(observedEntities = [KnowledgeItemEntity::class])
    suspend fun ftsSearchFragmentsByKb(query: SupportSQLiteQuery): List<KnowledgeSearchResult>

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

    companion object {
        // SQL strings the FTS @RawQuery methods need. The `:query`
        // and `:kbId` parameters are bound positionally by
        // SimpleSQLiteQuery.bind* — Room's kapt validator never
        // touches these (which is the whole point of moving the
        // FTS queries to @RawQuery).
        internal const val FTS_ITEMS_ALL_SQL = """
            SELECT knowledge_item.* FROM knowledge_item
            JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
            WHERE knowledge_item_fts MATCH ?
            AND knowledge_item.deletedAt IS NULL
            ORDER BY knowledge_item.updatedAt DESC
        """

        internal const val FTS_ITEMS_BY_KB_SQL = """
            SELECT knowledge_item.* FROM knowledge_item
            JOIN knowledge_item_fts ON knowledge_item.rowid = knowledge_item_fts.rowid
            WHERE knowledge_item_fts MATCH ?
            AND knowledge_item.knowledgeBaseId = ?
            AND knowledge_item.deletedAt IS NULL
            ORDER BY knowledge_item.updatedAt DESC
        """

        internal const val FTS_FRAGMENTS_ALL_SQL = """
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
            WHERE knowledge_fragment_fts MATCH ?
            AND ki.deletedAt IS NULL
            ORDER BY ki.updatedAt DESC
            LIMIT ?
        """

        internal const val FTS_FRAGMENTS_BY_KB_SQL = """
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
            WHERE knowledge_fragment_fts MATCH ?
            AND ki.knowledgeBaseId = ?
            AND ki.deletedAt IS NULL
            ORDER BY ki.updatedAt DESC
            LIMIT ?
        """

        fun ftsItemsAllQuery(query: String): SupportSQLiteQuery =
            SimpleSQLiteQuery(FTS_ITEMS_ALL_SQL, arrayOf<Any>(query))

        fun ftsItemsByKbQuery(query: String, kbId: String): SupportSQLiteQuery =
            SimpleSQLiteQuery(FTS_ITEMS_BY_KB_SQL, arrayOf<Any>(query, kbId))

        fun ftsFragmentsAllQuery(query: String, limit: Int): SupportSQLiteQuery =
            SimpleSQLiteQuery(FTS_FRAGMENTS_ALL_SQL, arrayOf<Any>(query, limit))

        fun ftsFragmentsByKbQuery(query: String, kbId: String, limit: Int): SupportSQLiteQuery =
            SimpleSQLiteQuery(FTS_FRAGMENTS_BY_KB_SQL, arrayOf<Any>(query, kbId, limit))
    }
}
