package com.my.knowledge.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEmbeddingEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeGraphDao {
    @Query("SELECT * FROM knowledge_embedding WHERE knowledgeBaseId = :kbId")
    suspend fun getEmbeddingsByBase(kbId: String): List<KnowledgeEmbeddingEntity>

    @Query("SELECT * FROM knowledge_embedding WHERE itemId = :itemId")
    suspend fun getEmbeddingsByItem(itemId: String): List<KnowledgeEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbeddings(embeddings: List<KnowledgeEmbeddingEntity>)

    @Query("DELETE FROM knowledge_embedding WHERE itemId = :itemId")
    suspend fun deleteEmbeddingsByItem(itemId: String)

    @Query("SELECT * FROM knowledge_entity WHERE deletedAt IS NULL ORDER BY weight DESC, name ASC")
    fun observeAllEntities(): Flow<List<KnowledgeEntityEntity>>

    @Query("SELECT * FROM knowledge_relation WHERE deletedAt IS NULL ORDER BY confidence DESC")
    fun observeAllRelations(): Flow<List<KnowledgeRelationEntity>>

    @Query("SELECT * FROM knowledge_community WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAllCommunities(): Flow<List<KnowledgeCommunityEntity>>

    @Query("SELECT * FROM knowledge_entity WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL ORDER BY weight DESC, name ASC")
    fun observeEntities(kbId: String): Flow<List<KnowledgeEntityEntity>>

    @Query("SELECT * FROM knowledge_relation WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL ORDER BY confidence DESC")
    fun observeRelations(kbId: String): Flow<List<KnowledgeRelationEntity>>

    @Query("SELECT * FROM knowledge_community WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeCommunities(kbId: String): Flow<List<KnowledgeCommunityEntity>>

    @Query("SELECT COUNT(*) FROM knowledge_entity WHERE deletedAt IS NULL")
    fun observeEntityCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM knowledge_entity WHERE type = 'concept' AND deletedAt IS NULL")
    fun observeConceptCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntities(entities: List<KnowledgeEntityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelations(relations: List<KnowledgeRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCommunities(communities: List<KnowledgeCommunityEntity>)

    @Query("UPDATE knowledge_entity SET deletedAt = :now WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL")
    suspend fun clearEntities(kbId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_relation SET deletedAt = :now WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL")
    suspend fun clearRelations(kbId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_community SET deletedAt = :now WHERE knowledgeBaseId = :kbId AND deletedAt IS NULL")
    suspend fun clearCommunities(kbId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_entity SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun deleteEntities(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_relation SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun deleteRelations(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge_community SET deletedAt = :now WHERE id IN (:ids)")
    suspend fun deleteCommunities(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM knowledge_entity WHERE name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun getEntityByName(name: String): KnowledgeEntityEntity?

    @Query("SELECT * FROM knowledge_entity WHERE knowledgeBaseId = :kbId")
    suspend fun getAllEntitiesByKb(kbId: String): List<KnowledgeEntityEntity>

    @Query("SELECT * FROM knowledge_relation WHERE knowledgeBaseId = :kbId")
    suspend fun getAllRelationsByKb(kbId: String): List<KnowledgeRelationEntity>

    @Query("SELECT * FROM knowledge_community WHERE knowledgeBaseId = :kbId")
    suspend fun getAllCommunitiesByKb(kbId: String): List<KnowledgeCommunityEntity>

    // ─── Item migration helpers ────────────────────────────────────────────────
    // Used when moving an item to a different KB — updates kbId on all
    // embeddings linked to that item (they follow the fragment which follows the item).

    @Query("UPDATE knowledge_embedding SET knowledgeBaseId = :targetKbId WHERE itemId = :itemId")
    suspend fun updateEmbeddingsKbByItem(itemId: String, targetKbId: String)

    /**
     * Moves all entities whose `sourceItemIdsJson` contains the given `itemId`
     * to the target KB. An entity that spans multiple items is moved only if
     * every item it references is also moving to the same target KB — otherwise
     * the entity stays in the source KB and the rebuild on the source side will
     * re-materialise it for the items that remain.
     *
     * The JSON array is flattened in-memory for correctness (Room does not
     * bind path components as parameters).
     */
    @Query("""
        UPDATE knowledge_entity
        SET knowledgeBaseId = :targetKbId,
            updatedAt = :now
        WHERE id IN (
            SELECT id FROM knowledge_entity
            WHERE knowledgeBaseId = :sourceKbId
            AND json_valid(sourceItemIdsJson) = 1
            AND sourceItemIdsJson LIKE '%' || :itemId || '%'
        )
        AND NOT EXISTS (
            SELECT 1 FROM json_each(sourceItemIdsJson)
            WHERE value NOT IN (
                SELECT id FROM knowledge_item WHERE knowledgeBaseId = :targetKbId AND deletedAt IS NULL
            )
        )
    """)
    suspend fun moveExclusiveEntitiesByItem(itemId: String, sourceKbId: String, targetKbId: String, now: Long)

    /** Moves relations where both endpoints are now in the target KB. */
    @Query("""
        UPDATE knowledge_relation
        SET knowledgeBaseId = :targetKbId,
            updatedAt = :now
        WHERE knowledgeBaseId = :sourceKbId
        AND fromEntityId IN (SELECT id FROM knowledge_entity WHERE knowledgeBaseId = :targetKbId AND deletedAt IS NULL)
        AND toEntityId   IN (SELECT id FROM knowledge_entity WHERE knowledgeBaseId = :targetKbId AND deletedAt IS NULL)
    """)
    suspend fun moveRelationsToKbByEndpoints(sourceKbId: String, targetKbId: String, now: Long)

    /** Moves communities where every entity in `entityIdsJson` lives in the target KB. */
    @Query("""
        UPDATE knowledge_community
        SET knowledgeBaseId = :targetKbId,
            updatedAt = :now
        WHERE knowledgeBaseId = :sourceKbId
        AND NOT EXISTS (
            SELECT 1 FROM json_each(entityIdsJson) j
            WHERE j.value NOT IN (SELECT id FROM knowledge_entity WHERE knowledgeBaseId = :targetKbId AND deletedAt IS NULL)
        )
    """)
    suspend fun moveCommunitiesToKbByEntities(sourceKbId: String, targetKbId: String, now: Long)
}
