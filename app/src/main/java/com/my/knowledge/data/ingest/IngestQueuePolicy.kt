package com.my.knowledge.data.ingest

internal object IngestQueuePolicy {
    private val chainableTaskTypes = setOf("parse", "analysis", "generation")

    fun shouldClaimNextSameSourceTask(taskType: String, taskSucceeded: Boolean): Boolean =
        taskSucceeded && taskType in chainableTaskTypes
}
