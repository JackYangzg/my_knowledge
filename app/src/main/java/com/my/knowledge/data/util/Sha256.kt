package com.my.knowledge.data.util

import java.io.File
import java.security.MessageDigest

/**
 * PERF-9: central place for "SHA-256 + lowercase hex" so the same
 * 3-line boilerplate stops being copy-pasted into 10 files.
 *
 * `MessageDigest` is **not** thread-safe, so we always allocate a
 * fresh instance per call (the JDK's provider lookup is cached, so
 * the cost of `getInstance` is dominated by object allocation —
 * microseconds for a 1KB string). The hex-encoding loop is the only
 * hot piece and lives in [HEX_FORMAT] / [toHex].
 *
 * This is a stability key, not a security primitive — same disclaimer
 * the existing `LongSourceCheckpointStore.sha256Hex` carried.
 */
object Sha256 {
    private val HEX_FORMAT = "%02x".toString()

    fun hex(content: String): String =
        hex(content.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = digest.digest(bytes)
        return toHex(out)
    }

    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX_FORMAT.format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
