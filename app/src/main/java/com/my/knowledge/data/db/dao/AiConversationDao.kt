package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.AiConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiConversationDao {
    @Query("""
        SELECT * FROM ai_conversation
        WHERE deletedAt IS NULL
        ORDER BY updatedAt DESC
    """)
    fun observeAll(): Flow<List<AiConversationEntity>>

    @Query("""
        SELECT * FROM ai_conversation
        WHERE scopeType = :scopeType AND scopeId = :scopeId AND deletedAt IS NULL
        ORDER BY updatedAt DESC
    """)
    fun observeByScope(scopeType: String, scopeId: String): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversation WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): AiConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: AiConversationEntity)

    @Update
    suspend fun update(conversation: AiConversationEntity)

    @Query("UPDATE ai_conversation SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("DELETE FROM ai_conversation WHERE id = :id")
    suspend fun hardDelete(id: String)
}
