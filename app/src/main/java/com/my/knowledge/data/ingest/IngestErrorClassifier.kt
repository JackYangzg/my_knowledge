package com.my.knowledge.data.ingest

/**
 * P1-A.3: classifier for the parse-task error stream. Lifted out of
 * [IngestOrchestrator] so it can be unit-tested without standing up
 * a full `AppDatabase` / source DAO.
 *
 * The classifier drives the retry-vs-fail-immediately decision in
 * `IngestOrchestrator.shouldFailImmediately`. Five distinct outcomes
 * matter for the retry policy:
 *
 *  1. **DNS / name resolution failure** — retry (network is flaky)
 *  2. **Connection-level failure** (connect refused, reset, aborted, SSL)
 *     — retry
 *  3. **Read / write timeout** — retry
 *  4. **Upstream AI / HTTP 5xx** — retry
 *  5. **Anything else** (parse bug, schema mismatch, user-cancelled,
 *     OOM) — image sources fail immediately, everything else is
 *     allowed to use up its retry budget.
 *
 * The classifier is a pure string match against a fixed keyword set;
 * the source-typing branch is handled in
 * [IngestOrchestrator.shouldFailImmediately] so it stays next to the
 * `AppDatabase` call it makes.
 */
internal fun String?.isRetryableAiOrNetworkFailure(): Boolean {
    val value = this?.lowercase().orEmpty()
    return listOf(
        "dns",
        "unable to resolve",
        "连接失败",
        "failed to connect",
        "connection reset",
        "connection abort",
        "connection aborted",
        "connection refused",
        "software caused connection abort",
        "ssl",
        "超时",
        "timeout",
        "timed out",
        "ai 调用",
        "ai调用",
        "http 5"
    ).any { value.contains(it) }
}
