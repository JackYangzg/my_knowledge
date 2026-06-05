package com.my.knowledge.data.ai

import com.my.knowledge.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * P1-N2 / RELIAB-1: shared OkHttp client for the LLM gateway.
 *
 * Why a singleton: `HttpURLConnection` opened a brand-new TCP
 * socket per request (0-connection pool), and its default
 * `readTimeout=0` meant a dropped stream waited forever for the OS
 * to reap the socket. OkHttp gives us:
 *
 *  - 5-connection pool with 5min idle retention — reuses TLS
 *    handshakes across the 4 ingest lanes.
 *  - A bounded 10-minute read timeout so a half-dead SSE stream
 *    surfaces as a SocketTimeoutException instead of hanging the
 *    worker.
 *  - A `callTimeout=0` ("no overall cap") because a 10-minute
 *    stream + retries would otherwise trip the cap mid-response.
 *  - Built-in `retryOnConnectionFailure(true)` so a single TCP
 *    RST doesn't fail the call — the orchestrator's own retry
 *    layer is reserved for application-level failures.
 *
 * Logging: `HttpLoggingInterceptor` is added only in debug builds
 * (gated by `BuildConfig.DEBUG`). Release builds skip the
 * interceptor entirely — we don't even install a `NONE`-level
 * instance, both to keep the release APK smaller and to avoid
 * shipping the Bearer-token logging code path at all.
 */
object LlmHttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }
}
