package com.my.knowledge.domain.usecase

import com.my.knowledge.data.db.AppDatabase

class DeleteSourceLogUseCase(
    private val store: Store
) {
    interface Store {
        suspend fun sourceExists(sourceId: String): Boolean
        suspend fun cancelTasksBySource(sourceId: String, updatedAt: Long)
        suspend fun deleteTasksBySource(sourceId: String)
        suspend fun deleteSourceLogs(sourceId: String)
        suspend fun hideSourceLogRow(sourceId: String, updatedAt: Long)
    }

    suspend fun deleteSourceLog(sourceId: String): Boolean {
        if (!store.sourceExists(sourceId)) return false
        val now = System.currentTimeMillis()
        store.cancelTasksBySource(sourceId, now)
        store.deleteTasksBySource(sourceId)
        store.deleteSourceLogs(sourceId)
        store.hideSourceLogRow(sourceId, now)
        return true
    }

    companion object {
        fun fromDatabase(db: AppDatabase): DeleteSourceLogUseCase =
            DeleteSourceLogUseCase(
                object : Store {
                    override suspend fun sourceExists(sourceId: String): Boolean =
                        db.sourceDocumentDao().getById(sourceId) != null

                    override suspend fun cancelTasksBySource(sourceId: String, updatedAt: Long) {
                        db.processingTaskDao().cancelBySource(sourceId, updatedAt)
                    }

                    override suspend fun deleteTasksBySource(sourceId: String) {
                        db.processingTaskDao().deleteBySource(sourceId)
                    }

                    override suspend fun deleteSourceLogs(sourceId: String) {
                        db.processingTaskLogDao().deleteByTarget("source_document", sourceId)
                    }

                    override suspend fun hideSourceLogRow(sourceId: String, updatedAt: Long) {
                        db.sourceDocumentDao().markDeleted(sourceId, updatedAt)
                    }
                }
            )
    }
}
