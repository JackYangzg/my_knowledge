package com.my.knowledge.data.db.dao

import androidx.room.*
import com.my.knowledge.data.db.entity.AiMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMessageDao {
    @Query("""
        SELECT * FROM ai_message
        WHERE conversationId = :conversationId
        ORDER BY createdAt ASC
    """)
    fun observeByConversation(conversationId: String): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM ai_message WHERE id = :id")
    suspend fun getById(id: String): AiMessageEntity?

    @Query("""
        SELECT * FROM ai_message
        WHERE conversationId = :conversationId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentMessages(conversationId: String, limit: Int = 20): List<AiMessageEntity>

    /**
     * GROUP-BY message count keyed by conversationId, restricted to a
     * given scope. Powers the "n 条消息" badge in AskHistorySheet without
     * requiring a per-conversation query.
     */
    @Query("""
        SELECT conversationId, COUNT(*) as count
        FROM ai_message
        WHERE role IN ('user', 'assistant')
          AND conversationId IN (
            SELECT id FROM ai_conversation
            WHERE scopeType = :scopeType AND scopeId = :scopeId AND deletedAt IS NULL
        )
        GROUP BY conversationId
    """)
    fun observeCountsByScope(scopeType: String, scopeId: String): Flow<List<ConversationMessageCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AiMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AiMessageEntity>)

    @Update
    suspend fun update(message: AiMessageEntity)

    @Query("DELETE FROM ai_message WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

data class ConversationMessageCount(
    val conversationId: String,
    val count: Int
)
