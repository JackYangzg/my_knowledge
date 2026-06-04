package com.my.knowledge.data.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestQueuePolicyTest {
    @Test
    fun `successful parse analysis and generation continue same source pipeline`() {
        assertTrue(IngestQueuePolicy.shouldClaimNextSameSourceTask("parse", taskSucceeded = true))
        assertTrue(IngestQueuePolicy.shouldClaimNextSameSourceTask("analysis", taskSucceeded = true))
        assertTrue(IngestQueuePolicy.shouldClaimNextSameSourceTask("generation", taskSucceeded = true))
    }

    @Test
    fun `failed tasks and terminal tasks do not continue same source pipeline`() {
        assertFalse(IngestQueuePolicy.shouldClaimNextSameSourceTask("parse", taskSucceeded = false))
        assertFalse(IngestQueuePolicy.shouldClaimNextSameSourceTask("analysis", taskSucceeded = false))
        assertFalse(IngestQueuePolicy.shouldClaimNextSameSourceTask("embedding", taskSucceeded = true))
    }
}
