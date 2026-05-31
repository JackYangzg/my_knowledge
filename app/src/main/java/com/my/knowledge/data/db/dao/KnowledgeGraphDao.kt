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

    @Query("SELECT * FROM knowledge_entity WHERE knowledgeBaseId = :kbId ORDER BY weight DESC, name ASC")
    fun observeEntities(kbId: String): Flow<List<KnowledgeEntityEntity>>

    @Query("SELECT * FROM knowledge_relation WHERE knowledgeBaseId = :kbId ORDER BY confidence DESC")
    fun observeRelations(kbId: String): Flow<List<KnowledgeRelationEntity>>

    @Query("SELECT * FROM knowledge_community WHERE knowledgeBaseId = :kbId ORDER BY updatedAt DESC")
    fun observeCommunities(kbId: String): Flow<List<KnowledgeCommunityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntities(entities: List<KnowledgeEntityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelations(relations: List<KnowledgeRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCommunities(communities: List<KnowledgeCommunityEntity>)

    @Query("DELETE FROM knowledge_entity WHERE knowledgeBaseId = :kbId")
    suspend fun clearEntities(kbId: String)

    @Query("DELETE FROM knowledge_relation WHERE knowledgeBaseId = :kbId")
    suspend fun clearRelations(kbId: String)

    @Query("DELETE FROM knowledge_community WHERE knowledgeBaseId = :kbId")
    suspend fun clearCommunities(kbId: String)
}
