package com.my.knowledge.data.ai

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure budget allocator for LLM context assembly.
 *
 * 1:1 port of llm_wiki/src/lib/context-budget.ts (99 lines). The math
 * uses characters (not tokens); char/3 ≈ token is a rough enough proxy
 * for budget sizing without a tokenizer in the call path.
 *
 * Two consumers:
 *   - AskViewModel: caps `originals` length so the prompt fits the
 *     model's effective context window + leaves room for response.
 *   - IngestOrchestrator: caps `sourceBudget` so long-source chunking
 *     uses a model-aware threshold instead of the flat 30K cap.
 *
 * Why char units: llm_wiki uses char, char/3 ≈ token is close enough
 * for reserve math, and tokens would force a tokenizer in the hot
 * path. If we ever need exact-token budgets, swap the math here and
 * keep all callers intact.
 */
data class ContextBudget(
    val maxCtx: Int,
    val responseReserve: Int,
    val indexBudget: Int,
    val pageBudget: Int,
    val maxPageSize: Int,
)

object ContextBudgetCalculator {
    private const val DEFAULT_MAX_CTX = 204_800
    private const val RESPONSE_RESERVE_FRAC = 0.15
    private const val INDEX_BUDGET_FRAC = 0.05
    private const val PAGE_BUDGET_FRAC = 0.50
    private const val PER_PAGE_FRAC = 0.30
    private const val PER_PAGE_FLOOR = 5_000
    const val INGEST_LATENCY_BUDGET_FAST = 24_000
    const val INGEST_LATENCY_BUDGET_BALANCED = 36_000
    const val INGEST_LATENCY_BUDGET_QUALITY = 60_000

    /**
     * Compute character budgets from the LLM's max context window.
     * Falsy `maxContextSize` (0 / NaN / undefined) falls back to
     * [DEFAULT_MAX_CTX] so existing configs without the new field
     * keep working.
     */
    fun compute(maxContextSize: Int): ContextBudget {
        val maxCtx = if (maxContextSize > 0) maxContextSize else DEFAULT_MAX_CTX
        val responseReserve = floor(maxCtx * RESPONSE_RESERVE_FRAC).toInt()
        val indexBudget = floor(maxCtx * INDEX_BUDGET_FRAC).toInt()
        val pageBudget = floor(maxCtx * PAGE_BUDGET_FRAC).toInt()
        val maxPageSize = min(
            pageBudget,
            max(PER_PAGE_FLOOR, floor(pageBudget * PER_PAGE_FRAC).toInt()),
        )
        return ContextBudget(maxCtx, responseReserve, indexBudget, pageBudget, maxPageSize)
    }

    /**
     * Max tokens for an LLM ingest-generation response, scaled by the
     * model's context window. Mirrors llm_wiki's
     * `computeIngestGenerationMaxTokens` step function. 8K config
     * stays at 8192 (legacy); 128K jumps to 16K so wiki pages can
     * actually fit.
     */
    fun computeIngestGenerationMaxTokens(maxContextSize: Int): Int =
        when {
            maxContextSize >= 512_000 -> 32_768
            maxContextSize >= 256_000 -> 24_576
            maxContextSize >= 128_000 -> 16_384
            else -> 8_192
        }

    /**
     * Ask responses are typically shorter than wiki pages, so this
     * caps the response reserve at 2K-8K tokens. Legacy 8K config
     * gets 2K (intentional tightening — previous hardcoded 8192 was
     * over-budget on small contexts).
     */
    fun computeAskMaxTokens(maxContextSize: Int): Int =
        (compute(maxContextSize).responseReserve / 3).coerceIn(2_048, 8_192)

    /**
     * Per-source character budget for ingest analysis. Reserves room
     * for the stable context (schema/purpose/index/overview) and the
     * instruction prompt, then gives the remainder to the source,
     * clamped to [LONG_SOURCE_MIN, min(300K, max(LONG_SOURCE_MIN, 60% of maxCtx))].
     *
     * @param stableContextLength total chars of schema+purpose+index+overview.
     *   Capped at `maxCtx * 0.25` so a runaway 200K index doesn't eat
     *   the entire source budget.
     */
    fun computeIngestSourceBudget(maxContextSize: Int, stableContextLength: Int): Int {
        val b = compute(maxContextSize)
        val stableReserve = min(
            floor(b.maxCtx * 0.25).toInt(),
            max(12_000, stableContextLength.coerceAtLeast(0)),
        )
        val instructionReserve = max(12_000, floor(b.maxCtx * 0.08).toInt())
        val available = b.maxCtx - b.responseReserve - stableReserve - instructionReserve
        val upper = min(300_000, max(8_000, floor(b.maxCtx * 0.6).toInt()))
        return available.coerceIn(8_000, upper)
    }

    fun computeIngestAnalysisLimit(
        maxContextSize: Int,
        stableContextLength: Int = 0,
        latencyBudget: Int = INGEST_LATENCY_BUDGET_BALANCED,
    ): Int = min(
        computeIngestSourceBudget(maxContextSize, stableContextLength),
        latencyBudget.coerceAtLeast(8_000),
    )
}
