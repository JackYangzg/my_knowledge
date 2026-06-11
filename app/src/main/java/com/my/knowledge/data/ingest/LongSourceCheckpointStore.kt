package com.my.knowledge.data.ingest

import com.my.knowledge.data.util.Sha256
import java.io.File

/**
 * Persists long-source analysis progress to a per-source JSON file
 * under `${filesDir}/.my-knowledge/ingest-progress/`.
 *
 * Mirrors the contract of llm_wiki's `loadLongSourceCheckpoint` /
 * `saveLongSourceCheckpoint` in `src/lib/ingest.ts`. The store is a
 * tiny pure file wrapper — it does not orchestrate chunking, does not
 * call the LLM, and does not depend on Android framework types
 * (besides the [rootDir] the caller hands in). The orchestrator
 * composes the store with [MarkdownSemanticChunker] inside
 * `analysisTask`.
 *
 * Design constraints:
 *
 *  1. **Atomic write.** The checkpoint file is written to
 *     `<path>.tmp` first, then `renameTo` swaps it into place. A
 *     crash mid-write leaves the previous good checkpoint on disk
 *     rather than a half-formed JSON the next resume would choke on.
 *
 *  2. **Best-effort I/O.** [save] and [clear] swallow exceptions
 *     after a `try { … } catch` and just return `false` / `Unit` —
 *     a corrupt or read-only file system must not block the
 *     long-source pipeline. The spec is explicit: "checkpoint 文件
 *     失败 silently 继续,不阻塞".
 *
 *  3. **Compatibility gating on load.** [load] returns `null` when
 *     the file is missing, unparseable, or its key fields don't
 *     match the current run's [LongSourceCheckpointParams]. The
 *     compat check mirrors `isCompatibleLongSourceCheckpoint` in
 *     ingest.ts (sourceIdentity / sourceHash / sourceLength /
 *     sourceBudget / targetChars / overlapChars / chunkTotal all
 *     must match exactly). A resume that mismatches any of these
 *     silently starts over from chunk 1.
 *
 *  4. **Stable file name.** The path is
 *     `<rootDir>/.my-knowledge/ingest-progress/<slug>-<sha256>.json`
 *     where `slug` is a filesystem-safe version of the source title
 *     and `sha256` is the hex digest of the source content. Two
 *     re-imports of the same file share the same checkpoint file,
 *     so the second import can resume from where the first got
 *     cancelled.
 *
 *  5. **No Android dependency.** The constructor takes a plain
 *     [File] so the unit test can use a JUnit `TemporaryFolder`
 *     without `Robolectric` or `android.util.Log` shims.
 */
class LongSourceCheckpointStore(
    private val rootDir: File
) {
    private val progressDir: File = File(rootDir, PROGRESS_SUBDIR).apply {
        if (!exists()) mkdirs()
    }

    /**
     * Compute the on-disk checkpoint path for a source.
     * Exposed so the orchestrator can include it in log lines and
     * pass it back into [load] / [save].
     */
    fun checkpointPath(sourceSlug: String, sourceSha256: String): File =
        File(progressDir, "${slugify(sourceSlug)}-${sourceSha256}.json")

    /**
     * Load the checkpoint for a source and verify it matches [params].
     *
     * @return the parsed [LongSourceCheckpoint] when the file
     *   exists, parses, and is compatible with [params]; `null` in
     *   every other case (missing file, parse error, version
     *   mismatch, identity / hash / shape mismatch). Callers should
     *   treat `null` as "start from chunk 1".
     */
    fun load(file: File, params: LongSourceCheckpointParams): LongSourceCheckpoint? {
        return try {
            if (!file.exists()) return null
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            val parsed = LongSourceCheckpointJson.decode(raw) ?: return null
            if (!isCompatible(parsed, params)) return null
            parsed
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persist [state] to [file]. Returns `true` on success, `false`
     * on any I/O failure (caller should not block on this — see
     * "best-effort I/O" in the class doc).
     */
    fun save(file: File, state: LongSourceCheckpoint): Boolean {
        return try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            file.parentFile?.mkdirs()
            tmp.writeText(LongSourceCheckpointJson.encode(state), Charsets.UTF_8)
            // Atomic-ish swap. On POSIX, rename is atomic within the
            // same filesystem; on Windows / Android scoped storage
            // `renameTo` can fail across mount points, so fall back
            // to a copy + delete.
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Best-effort delete. Used by the orchestrator when a long-source
     * run finishes successfully so the next re-ingest of the same
     * file starts from chunk 1 instead of resuming stale work.
     */
    fun clear(file: File) {
        try {
            if (file.exists()) file.delete()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
        } catch (e: Exception) {
            // Swallow — best-effort.
        }
    }

    companion object {
        const val PROGRESS_SUBDIR = ".my-knowledge/ingest-progress"
        const val CHECKPOINT_VERSION = 2
        /**
         * Hex SHA-256 of the source content. Used in the checkpoint
         * file name and as the [LongSourceCheckpoint.sourceHash]
         * field. Matches the JS side's `hashTextHex` semantic
         * (stability key, not a security primitive — see ingest.ts
         * for the same disclaimer).
         */
        fun sha256Hex(content: String): String = Sha256.hex(content)

        /**
         * Filesystem-safe version of a free-form source title.
         * Mirrors `LocalFileStore.writeBackup`'s safe-name policy:
         * non-[A-Za-z0-9._-] runs collapse to a single `_`.
         */
        fun slugify(input: String): String {
            val replaced = input.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val collapsed = replaced.replace(Regex("_+"), "_").trim('_')
            return collapsed.ifBlank { "source" }
        }

        /**
         * Compatibility check — every field that affects chunk
         * shape (count, size, overlap) and source identity must
         * match the current run's parameters, otherwise we return
         * `null` so the orchestrator restarts from chunk 1. The
         * `completedThrough` must also be inside `[0, chunkTotal]`
         * and `analyses.size == completedThrough` so a corrupted
         * file (one where the array got out of sync with the
         * counter) doesn't slip through.
         */
        internal fun isCompatible(
            checkpoint: LongSourceCheckpoint,
            params: LongSourceCheckpointParams
        ): Boolean {
            if (checkpoint.version != CHECKPOINT_VERSION) return false
            if (checkpoint.sourceIdentity != params.sourceIdentity) return false
            if (checkpoint.sourceHash != params.sourceHash) return false
            if (checkpoint.sourceLength != params.sourceLength) return false
            if (checkpoint.sourceBudget != params.sourceBudget) return false
            if (checkpoint.targetChars != params.targetChars) return false
            if (checkpoint.overlapChars != params.overlapChars) return false
            if (checkpoint.chunkTotal != params.chunkTotal) return false
            if (checkpoint.completedThrough < 0) return false
            if (checkpoint.completedThrough > params.chunkTotal) return false
            if (checkpoint.completedChunkIndexes.any { it !in 1..params.chunkTotal }) return false
            if (checkpoint.completedChunkIndexes.distinct().size != checkpoint.completedChunkIndexes.size) return false
            val contiguous = (1..params.chunkTotal)
                .takeWhile { it in checkpoint.completedChunkIndexes }
                .size
            if (checkpoint.completedThrough != contiguous) return false
            if (checkpoint.analysisChunkIndexes != checkpoint.completedChunkIndexes) return false
            if (checkpoint.analyses.size != checkpoint.analysisChunkIndexes.size) return false
            if (checkpoint.globalDigestBytes < 0) return false
            return true
        }
    }
}

/**
 * Identity / shape of a long-source run. The orchestrator builds one
 * of these from the parsed source content + LLM config; the
 * checkpoint store uses it to decide whether a saved file is still
 * safe to resume from.
 *
 * All fields are part of the on-disk schema, so renaming them
 * requires bumping [LongSourceCheckpointStore.CHECKPOINT_VERSION] —
 * otherwise a stale checkpoint could silently pass the compat check.
 */
data class LongSourceCheckpointParams(
    val sourceIdentity: String,
    val sourceHash: String,
    val sourceLength: Int,
    val sourceBudget: Int,
    val targetChars: Int,
    val overlapChars: Int,
    val chunkTotal: Int
)

/**
 * Persisted state of an in-flight long-source analysis. One
 * checkpoint file per source; the orchestrator loads it on entry
 * (when present + compatible) and rewrites it after every parallel
 * chunk window finishes.
 *
 * The "int + List<String>" shape mirrors the JS `LongSourceCheckpoint`
 * interface in `src/lib/ingest.ts` so a future cross-system
 * migration of checkpoints stays tractable.
 */
data class LongSourceCheckpoint(
    val version: Int,
    val sourceIdentity: String,
    val sourceHash: String,
    val sourceLength: Int,
    val sourceBudget: Int,
    val targetChars: Int,
    val overlapChars: Int,
    val chunkTotal: Int,
    /**
     * Largest continuously completed prefix. Parallel windows can
     * also persist later indexes, represented explicitly below.
     */
    val completedThrough: Int,
    val completedChunkIndexes: List<Int> = (1..completedThrough).toList(),
    val analysisChunkIndexes: List<Int> = completedChunkIndexes,
    /**
     * The most recent "Updated Global Digest" the LLM produced.
     * Pre-rendered into the user prompt of every subsequent chunk
     * so cross-chunk context survives the resume boundary.
     */
    val globalDigest: String,
    /**
     * One entry per analysed chunk, in order. Each entry is the
     * "## Chunk Analysis" markdown the LLM returned for that chunk.
     * Order is significant — the orchestrator joins them with
     * `## Per-Chunk Analyses` headings downstream.
     */
    val analyses: List<String>,
    val updatedAt: Long
) {
    /**
     * Tracked length of [globalDigest] in *characters* (UTF-16
     * units, the same as `String.length` in Kotlin / JS). Kept
     * alongside the digest itself for quick "is this over budget?"
     * checks without re-counting on every prompt build. Not part of
     * the on-disk JSON — derived from `globalDigest.length` on
     * encode.
     */
    val globalDigestBytes: Int get() = globalDigest.length
}

/**
 * Tiny JSON codec for [LongSourceCheckpoint]. We deliberately don't
 * pull in `kotlinx.serialization` for one struct — `org.json` is
 * already in the classpath (used by [IngestOrchestrator] itself)
 * and the schema is small enough to hand-roll.
 *
 * Encoding rules:
 *  - All fields are JSON primitives or arrays of strings.
 *  - `analyses` is a JSON array; each entry is a JSON string with
 *    its content escaped (no embedded newlines / quotes).
 *  - `version` is always an int; the compat check rejects anything
 *    that isn't `1` so future schema bumps are safe to introduce.
 */
internal object LongSourceCheckpointJson {
    fun encode(state: LongSourceCheckpoint): String {
        val sb = StringBuilder()
        sb.append('{')
        appendInt(sb, "version", state.version); sb.append(',')
        appendString(sb, "sourceIdentity", state.sourceIdentity); sb.append(',')
        appendString(sb, "sourceHash", state.sourceHash); sb.append(',')
        appendInt(sb, "sourceLength", state.sourceLength); sb.append(',')
        appendInt(sb, "sourceBudget", state.sourceBudget); sb.append(',')
        appendInt(sb, "targetChars", state.targetChars); sb.append(',')
        appendInt(sb, "overlapChars", state.overlapChars); sb.append(',')
        appendInt(sb, "chunkTotal", state.chunkTotal); sb.append(',')
        appendInt(sb, "completedThrough", state.completedThrough); sb.append(',')
        appendIntArray(sb, "completedChunkIndexes", state.completedChunkIndexes); sb.append(',')
        appendIntArray(sb, "analysisChunkIndexes", state.analysisChunkIndexes); sb.append(',')
        appendString(sb, "globalDigest", state.globalDigest); sb.append(',')
        appendStringArray(sb, "analyses", state.analyses); sb.append(',')
        appendLong(sb, "updatedAt", state.updatedAt)
        sb.append('}')
        return sb.toString()
    }

    fun decode(raw: String): LongSourceCheckpoint? {
        return try {
            val obj = org.json.JSONObject(raw)
            val analysesArr = obj.optJSONArray("analyses") ?: return null
            val analyses = ArrayList<String>(analysesArr.length())
            for (i in 0 until analysesArr.length()) {
                analyses.add(analysesArr.optString(i, ""))
            }
            val rawVersion = obj.optInt("version", -1)
            val completedThrough = obj.optInt("completedThrough", -1)
            val completedIndexes = obj.optJSONArray("completedChunkIndexes")?.toIntList()
                ?: if (completedThrough >= 0) (1..completedThrough).toList() else emptyList()
            val analysisIndexes = obj.optJSONArray("analysisChunkIndexes")?.toIntList()
                ?: completedIndexes
            LongSourceCheckpoint(
                version = if (rawVersion == 1) LongSourceCheckpointStore.CHECKPOINT_VERSION else rawVersion,
                sourceIdentity = obj.optString("sourceIdentity", ""),
                sourceHash = obj.optString("sourceHash", ""),
                sourceLength = obj.optInt("sourceLength", -1),
                sourceBudget = obj.optInt("sourceBudget", -1),
                targetChars = obj.optInt("targetChars", -1),
                overlapChars = obj.optInt("overlapChars", -1),
                chunkTotal = obj.optInt("chunkTotal", -1),
                completedThrough = completedThrough,
                completedChunkIndexes = completedIndexes,
                analysisChunkIndexes = analysisIndexes,
                globalDigest = obj.optString("globalDigest", ""),
                analyses = analyses,
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun appendInt(sb: StringBuilder, key: String, value: Int) {
        sb.append('"').append(key).append('"').append(':').append(value)
    }

    private fun appendLong(sb: StringBuilder, key: String, value: Long) {
        sb.append('"').append(key).append('"').append(':').append(value)
    }

    private fun appendString(sb: StringBuilder, key: String, value: String) {
        sb.append('"').append(key).append('"').append(':')
            .append('"').append(escape(value)).append('"')
    }

    private fun appendStringArray(sb: StringBuilder, key: String, values: List<String>) {
        sb.append('"').append(key).append('"').append(':').append('[')
        for ((i, v) in values.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(escape(v)).append('"')
        }
        sb.append(']')
    }

    private fun appendIntArray(sb: StringBuilder, key: String, values: List<Int>) {
        sb.append('"').append(key).append('"').append(':').append('[')
        for ((i, value) in values.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(value)
        }
        sb.append(']')
    }

    private fun org.json.JSONArray.toIntList(): List<Int> =
        (0 until length()).map { optInt(it, -1) }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
