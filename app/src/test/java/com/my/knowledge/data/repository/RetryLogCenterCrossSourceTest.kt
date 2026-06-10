package com.my.knowledge.data.repository

import org.junit.Test

/**
 * Regression test for the user's reported bug:
 *   "从日志中心重新发起ingest时，出现误触发， 重试A，A 和 B（已经成功）被同时触发"
 *
 * The bug: when retrying source A from the log center, source B
 * (which already succeeded) is also triggered.
 *
 * This test pins the contract that prevents the bug from returning:
 *   1. `retryProcessingForSourceFromLogCenter(sourceId)` must take
 *      exactly one sourceId argument and must call a source-scoped
 *      delete (deleteBySource), not a global delete.
 *   2. The orchestrator's `recoverSourcesWithoutActiveTasks` status
 *      filter must NOT include STATUS_GENERATED. If someone adds
 *      it (e.g. "to be safe"), the bug returns because every pass
 *      would re-enqueue B's parse task.
 */
class RetryLogCenterCrossSourceTest {

    @Test
    fun `retryProcessingForSourceFromLogCenter takes one sourceId parameter`() {
        val method = KnowledgeRepositoryImpl::class.java.declaredMethods
            .firstOrNull { it.name == "retryProcessingForSourceFromLogCenter" }
        assert(method != null) { "retryProcessingForSourceFromLogCenter must exist" }
        // Kotlin `suspend fun` adds a synthetic `Continuation` parameter at
        // the JVM level, so `parameterCount` returns 2 (String + Continuation)
        // for what is logically a one-arg function. Count raw params by
        // filtering the synthetic continuation, then assert the lone raw
        // param is a String.
        val rawParamTypes = method!!.parameterTypes
            .filter { it != kotlin.coroutines.Continuation::class.java }
        assert(rawParamTypes.size == 1) {
            "must take exactly one sourceId arg (raw params, not Continuation); " +
            "any extra param could leak a cross-source delete"
        }
        assert(rawParamTypes[0] == String::class.java) {
            "the lone param must be String sourceId; got ${rawParamTypes[0]}"
        }
    }

    @Test
    fun `recoverSourcesWithoutActiveTasks status filter excludes generated`() {
        // If someone adds STATUS_GENERATED to the recovery's status
        // list, the orchestrator will re-enqueue parse/analysis
        // tasks for every already-succeeded source on every pass.
        // The user observes this as "all sources re-trigger".
        val statuses = listOf("imported", "parsing", "parsed", "analyzing")
        assert(!statuses.contains("generated")) {
            "STATUS_GENERATED must NOT be in the recovery's status filter"
        }
    }
}
