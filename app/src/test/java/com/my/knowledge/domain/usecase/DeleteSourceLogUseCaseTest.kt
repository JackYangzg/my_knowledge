package com.my.knowledge.domain.usecase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteSourceLogUseCaseTest {

    @Test
    fun `delete source log only touches log and task records`() = runBlocking {
        val store = RecordingStore(sourceExists = true)
        val useCase = DeleteSourceLogUseCase(store)

        assertTrue(useCase.deleteSourceLog("source-1"))

        assertEquals(
            listOf(
                "sourceExists:source-1",
                "cancelTasksBySource:source-1",
                "deleteTasksBySource:source-1",
                "deleteSourceLogs:source-1",
                "hideSourceLogRow:source-1"
            ),
            store.calls
        )
    }

    @Test
    fun `delete source log is a no op when source is missing`() = runBlocking {
        val store = RecordingStore(sourceExists = false)
        val useCase = DeleteSourceLogUseCase(store)

        assertFalse(useCase.deleteSourceLog("missing-source"))

        assertEquals(listOf("sourceExists:missing-source"), store.calls)
    }

    private class RecordingStore(
        private val sourceExists: Boolean
    ) : DeleteSourceLogUseCase.Store {
        val calls = mutableListOf<String>()

        override suspend fun sourceExists(sourceId: String): Boolean {
            calls += "sourceExists:$sourceId"
            return sourceExists
        }

        override suspend fun cancelTasksBySource(sourceId: String, updatedAt: Long) {
            calls += "cancelTasksBySource:$sourceId"
        }

        override suspend fun deleteTasksBySource(sourceId: String) {
            calls += "deleteTasksBySource:$sourceId"
        }

        override suspend fun deleteSourceLogs(sourceId: String) {
            calls += "deleteSourceLogs:$sourceId"
        }

        override suspend fun hideSourceLogRow(sourceId: String, updatedAt: Long) {
            calls += "hideSourceLogRow:$sourceId"
        }
    }
}
