package com.my.knowledge.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the budget math for ContextBudgetCalculator. Mirrors
 * llm_wiki/src/lib/context-budget.test.ts (4 corner cases) plus
 * defensive tests for the new Kotlin-specific concerns (negative
 * inputs, Int.MAX_VALUE).
 */
class ContextBudgetTest {
    @Test
    fun compute_zeroFallsBackToDefault() {
        val b = ContextBudgetCalculator.compute(0)
        // DEFAULT_MAX_CTX = 204_800
        assertEquals(204_800, b.maxCtx)
        // 15% responseReserve, 5% index, 50% page
        assertEquals(30_720, b.responseReserve) // floor(204800 * 0.15)
        assertEquals(10_240, b.indexBudget)
        assertEquals(102_400, b.pageBudget)
        // maxPageSize = min(pageBudget, max(5_000, floor(102400 * 0.30)))
        //               = min(102400, max(5000, 30720)) = 30_720
        assertEquals(30_720, b.maxPageSize)
    }

    @Test
    fun compute_hugeConfigScalesLinearly() {
        val b = ContextBudgetCalculator.compute(512_000)
        assertEquals(512_000, b.maxCtx)
        assertEquals(76_800, b.responseReserve) // floor(512000 * 0.15)
        assertEquals(256_000, b.pageBudget)
        // maxPageSize = min(256000, max(5000, floor(256000 * 0.30)))
        //               = min(256000, 76800) = 76_800
        assertEquals(76_800, b.maxPageSize)
    }

    @Test
    fun compute_tinyConfigFallsToFloor() {
        // 8K config: pageBudget = floor(8192 * 0.5) = 4096.
        // maxPageSize floor rule: min(pageBudget, max(5000, 1228))
        //                        = min(4096, 5000) = 4096.
        // So a tiny config's per-page cap equals its total page budget —
        // the caller either gets one short page or none.
        val b = ContextBudgetCalculator.compute(8_192)
        assertEquals(8_192, b.maxCtx)
        assertEquals(4_096, b.pageBudget)
        assertEquals(4_096, b.maxPageSize)
    }

    @Test
    fun compute_negativeFallsBackToDefault() {
        // Defensive: a negative maxContextSize shouldn't crash.
        val b = ContextBudgetCalculator.compute(-1)
        assertEquals(204_800, b.maxCtx)
    }

    @Test
    fun computeIngestGenerationMaxTokens_stepFunction() {
        assertEquals(8_192, ContextBudgetCalculator.computeIngestGenerationMaxTokens(0))
        assertEquals(8_192, ContextBudgetCalculator.computeIngestGenerationMaxTokens(64_000))
        assertEquals(16_384, ContextBudgetCalculator.computeIngestGenerationMaxTokens(128_000))
        assertEquals(24_576, ContextBudgetCalculator.computeIngestGenerationMaxTokens(256_000))
        assertEquals(32_768, ContextBudgetCalculator.computeIngestGenerationMaxTokens(512_000))
    }

    @Test
    fun computeAskMaxTokens_clampsTo2kTo8k() {
        // 8K config: responseReserve = floor(8192*0.15) = 1228; /3 = 409; clamp to 2K
        assertEquals(2_048, ContextBudgetCalculator.computeAskMaxTokens(8_192))
        // 128K config: responseReserve = floor(128000*0.15) = 19200; /3 = 6400
        assertEquals(6_400, ContextBudgetCalculator.computeAskMaxTokens(128_000))
        // 512K config: responseReserve = 76800; /3 = 25600; clamp to 8K
        assertEquals(8_192, ContextBudgetCalculator.computeAskMaxTokens(512_000))
    }

    @Test
    fun computeIngestSourceBudget_clampsAndReserves() {
        // Zero stable context, 128K model.
        // b = compute(128000) → maxCtx=128000, responseReserve=19200
        // stableReserve = min(floor(128000 * 0.25)=32000, max(12000, 0)=12000) = 12000
        // instructionReserve = max(12000, floor(128000 * 0.08)=10240) = 12000
        // available = 128000 - 19200 - 12000 - 12000 = 84800
        // upper = min(300000, max(8000, floor(128000*0.6)=76800)) = 76800
        // result = 84800.coerceIn(8000, 76800) = 76800
        assertEquals(76_800, ContextBudgetCalculator.computeIngestSourceBudget(128_000, 0))

        // Large stable context: index/purpose/schema = 60K chars.
        // stableReserve = min(32000, max(12000, 60000)) = 32000
        // instructionReserve = 12000
        // available = 128000 - 19200 - 32000 - 12000 = 64800
        // upper = 76800
        // result = 64800.coerceIn(8000, 76800) = 64800
        assertEquals(64_800, ContextBudgetCalculator.computeIngestSourceBudget(128_000, 60_000))
    }

    @Test
    fun computeIngestSourceBudget_negativeStableTreatedAsZero() {
        // Defensive: a negative stableContextLength shouldn't make the budget explode.
        val b = ContextBudgetCalculator.computeIngestSourceBudget(128_000, -1)
        assertTrue("budget should be > 0", b > 0)
        assertTrue("budget should be reasonable", b <= 200_000)
    }
}