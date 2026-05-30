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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AiMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AiMessageEntity>)

    @Update
    suspend fun update(message: AiMessageEntity)

    @Query("DELETE FROM ai_message WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
