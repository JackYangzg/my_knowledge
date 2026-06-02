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
}
