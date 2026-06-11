package com.my.knowledge.data.ingest

import com.my.knowledge.ui.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 migration test — locks the contract that
 * [IngestOrchestrator.sourceBudget] delegates to
 * [com.my.knowledge.data.ai.ContextBudgetCalculator.computeIngestSourceBudget]
 * with the model's effective context window.
 *
 * Replaces the previous implicit "30K chars" cap with model-aware math.
 * For the default 128K model with no stable context, the new budget is
 * 76_800 chars — 2.5× the legacy cap.
 */
class IngestOrchestratorSourceBudgetTest {

    private fun model(maxCtx: Int) = ModelConfig(maxContextSize = maxCtx)

    @Test
    fun sourceBudget_defaultModelUses204k() {
        // ModelConfig() default = maxContextSize=0 → effectiveMaxContextSize = DEFAULT_MAX_CTX=204_800.
        // computeIngestSourceBudget(204800, 0): responseReserve=30720, instructionReserve=16384,
        // stableReserve=12000, available=204800-30720-16384-12000=145696, upper=floor(204800*0.6)=122880
        // → clamp 145696.coerceIn(8000, 122880) = 122_880
        assertEquals(122_880, IngestOrchestrator.sourceBudget(ModelConfig()))
    }

    @Test
    fun sourceBudget_128kModelReturns76800() {
        // math: maxCtx=128000, responseReserve=19200, stableReserve=12000, instructionReserve=12000
        //       available = 84800, upper = 76800 → clamp to 76800
        assertEquals(76_800, IngestOrchestrator.sourceBudget(model(128_000)))
    }

    @Test
    fun sourceBudget_smallModelDropsTo8kFloor() {
        // 8K config: maxCtx=8192, responseReserve=1228, instructionReserve=max(12000, floor(8192*0.08)=655)=12000
        // available = 8192 - 1228 - 12000 - 12000 < 0, clamped to floor 8_000
        assertEquals(8_000, IngestOrchestrator.sourceBudget(model(8_192)))
    }

    @Test
    fun sourceBudget_largeStableContextShrinksBudget() {
        // 128K model with 60K of stable context (schema+purpose+index+overview).
        // stableReserve = min(32000, max(12000, 60000)) = 32000
        // available = 128000 - 19200 - 32000 - 12000 = 64800 → clamp within [8000, 76800]
        val b128k = IngestOrchestrator.sourceBudget(model(128_000), stableContextLength = 60_000)
        assertTrue("large stable context must shrink budget (got $b128k)", b128k < 76_800)
        assertTrue("budget must still be at least 8K (got $b128k)", b128k >= 8_000)
    }

    @Test
    fun sourceBudget_zeroMaxContextFallsBackToDefault() {
        // ModelConfig(maxContextSize = 0) → effective = DEFAULT_MAX_CTX = 204_800.
        // Same as the default-model case above.
        val a = IngestOrchestrator.sourceBudget(ModelConfig(maxContextSize = 0))
        val b = IngestOrchestrator.sourceBudget(ModelConfig())
        assertEquals(a, b)
    }

    @Test
    fun sourceBudget_scalesWithContextSize() {
        // Monotonic: bigger ctx → equal or larger budget (modulo the upper clamp).
        val small = IngestOrchestrator.sourceBudget(model(8_192))
        val mid = IngestOrchestrator.sourceBudget(model(128_000))
        val big = IngestOrchestrator.sourceBudget(model(512_000))
        assertTrue("8K=$small, 128K=$mid, 512K=$big must be monotonically non-decreasing",
            small <= mid && mid <= big)
    }

    @Test
    fun analysisLimit_usesBalancedLatencyBudgetByDefault() {
        assertEquals(36_000, IngestOrchestrator.analysisLimit(ModelConfig()))
        assertEquals(36_000, IngestOrchestrator.analysisLimit(model(128_000)))
    }

    @Test
    fun analysisLimit_neverExceedsSmallModelCapacity() {
        assertEquals(8_000, IngestOrchestrator.analysisLimit(model(8_192)))
    }
}
