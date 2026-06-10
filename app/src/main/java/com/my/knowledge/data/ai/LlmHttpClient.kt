package com.my.knowledge.data.ai

import com.my.knowledge.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Socket
import javax.net.SocketFactory
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
            // P1-REL: HTTP/2 PING every 30s. Without this the TCP
            // socket sits idle while the model is "thinking" between
            // SSE chunks, and most NATs / carrier gateways silently
            // drop the connection after ~60-300s of silence. With
            // HTTP/2 PING the connection stays warm and a silent
            // half-open surfaces as a failed stream instead of a
            // 10-minute read timeout. Falls back to no-op on HTTP/1.1.
            .pingInterval(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            // P1-REL: TCP-level keep-alive on every socket OkHttp opens.
            // The default Android SocketFactory doesn't enable it, so a
            // long SSE stream sitting idle between model "thinking"
            // chunks gets silently dropped by NAT / carrier gateways.
            // OkHttp's `pingInterval` above covers HTTP/2; this covers
            // HTTP/1.1 fallbacks (some upstream providers still serve
            // the `/chat/completions` endpoint over 1.1).
            .socketFactory(KeepAliveSocketFactory)
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

/**
 * P1-REL: SocketFactory that flips `SO_KEEPALIVE` (and a short
 * `SO_LINGER`) on every socket OkHttp opens. We delegate creation to
 * the platform default factory — only the keep-alive options are
 * ours — so DNS resolution, TCP setup, and SSL/TLS handshake all
 * stay on the standard Android path.
 *
 * `SO_KEEPALIVE` alone uses the OS default idle probe cadence
 * (typically 2 hours on Linux), which is far too long for an SSE
 * stream. We rely on OkHttp's `pingInterval(30s)` to keep HTTP/2
 * connections warm; for HTTP/1.1, the OS keep-alive is a last-ditch
 * safety net that catches cases where the gateway drops a silent
 * socket without sending RST. Better to surface a fast EOF than
 * wait out the 10-minute read timeout.
 */
private object KeepAliveSocketFactory : SocketFactory() {
    private val delegate: SocketFactory = SocketFactory.getDefault()

    private fun Socket.applyKeepAlive() {
        try {
            keepAlive = true
            // SO_LINGER 5s — force a clean FIN close when the
            // gateway hangs up, instead of a half-open socket that
            // confuses OkHttp's connection pool for the next 5
            // minutes.
            setSoLinger(true, 5)
        } catch (_: Throwable) {
            // Some socket implementations reject setSoLinger
            // (e.g. already-closed streams); keep-alive is a best-
            // effort hint, never a hard requirement.
        }
    }

    override fun createSocket(): Socket = delegate.createSocket().apply { applyKeepAlive() }
    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).apply { applyKeepAlive() }
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).apply { applyKeepAlive() }
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).apply { applyKeepAlive() }
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).apply { applyKeepAlive() }
}
