package com.my.knowledge.data.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-A.3: 5-category classifier contract.
 *
 * The classifier sits on the parse-task error path. It returns
 * `true` (= retry) for transient network / upstream-AI failures and
 * `false` (= candidate for fail-immediately, gated on source type)
 * for everything else.
 *
 * Categories under test:
 *   1. DNS / name-resolution failure
 *   2. Connection-level failure (refused / reset / aborted / SSL)
 *   3. Timeout
 *   4. Upstream AI / HTTP 5xx
 *   5. Non-retryable (parse bug, schema mismatch, OOM, null)
 */
class IngestErrorClassifierTest {

    // --- Category 1: DNS / name resolution -----------------------------
    @Test
    fun `dns failure is retryable`() {
        assertTrue("DNS error: query timed out".isRetryableAiOrNetworkFailure())
        assertTrue("DNS_PROBE_FINISHED_NXDOMAIN".isRetryableAiOrNetworkFailure())
        assertTrue("Unable to resolve host \"api.openai.com\"".isRetryableAiOrNetworkFailure())
    }

    // --- Category 2: Connection-level failure --------------------------
    @Test
    fun `connection-level failures are retryable`() {
        assertTrue("failed to connect to /127.0.0.1:8080".isRetryableAiOrNetworkFailure())
        assertTrue("Connection refused".isRetryableAiOrNetworkFailure())
        assertTrue("Connection reset by peer".isRetryableAiOrNetworkFailure())
        assertTrue("Connection aborted".isRetryableAiOrNetworkFailure())
        assertTrue("Software caused connection abort: socket write error".isRetryableAiOrNetworkFailure())
        assertTrue("javax.net.ssl.SSLHandshakeException: chain validation failed".isRetryableAiOrNetworkFailure())
    }

    @Test
    fun `chinese connection failure wording is retryable`() {
        assertTrue("连接失败,请检查网络".isRetryableAiOrNetworkFailure())
    }

    // --- Category 3: Timeout -------------------------------------------
    @Test
    fun `timeouts are retryable`() {
        assertTrue("Read timed out".isRetryableAiOrNetworkFailure())
        assertTrue("connect timed out after 5000ms".isRetryableAiOrNetworkFailure())
        assertTrue("请求超时".isRetryableAiOrNetworkFailure())
    }

    // --- Category 4: Upstream AI / HTTP 5xx ----------------------------
    @Test
    fun `ai call failure and http 5xx are retryable`() {
        assertTrue("[AI 调用失败] upstream returned 502".isRetryableAiOrNetworkFailure())
        assertTrue("AI调用异常: 速率限制".isRetryableAiOrNetworkFailure())
        assertTrue("HTTP 502 Bad Gateway from upstream".isRetryableAiOrNetworkFailure())
        assertTrue("HTTP 503 Service Unavailable".isRetryableAiOrNetworkFailure())
        assertTrue("HTTP 504 Gateway Timeout".isRetryableAiOrNetworkFailure())
    }

    // --- Category 5: Non-retryable -------------------------------------
    @Test
    fun `parse and schema errors are not retryable`() {
        assertFalse("kotlinx.serialization.SerializationException: missing field 'entities'".isRetryableAiOrNetworkFailure())
        assertFalse("OutOfMemoryError: Java heap space".isRetryableAiOrNetworkFailure())
        assertFalse("IllegalArgumentException: unsupported mime type".isRetryableAiOrNetworkFailure())
        assertFalse("Parse error: unbalanced code fence".isRetryableAiOrNetworkFailure())
    }

    @Test
    fun `null and blank messages are not retryable`() {
        assertFalse(null.isRetryableAiOrNetworkFailure())
        assertFalse("".isRetryableAiOrNetworkFailure())
        assertFalse("   ".isRetryableAiOrNetworkFailure())
    }

    @Test
    fun `case-insensitive match`() {
        assertTrue("CONNECTION RESET BY PEER".isRetryableAiOrNetworkFailure())
        assertTrue("DNS Lookup Failure".isRetryableAiOrNetworkFailure())
        assertTrue("Read Timed Out".isRetryableAiOrNetworkFailure())
    }
}
