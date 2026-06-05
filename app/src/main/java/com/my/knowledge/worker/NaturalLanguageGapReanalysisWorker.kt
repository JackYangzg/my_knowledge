package com.my.knowledge.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.domain.fragment.FragmentGapDetector

/**
 * FRAG-1.4 (P12): runs after the user types a natural-language update
 * in `FragmentChainDetailScreen`. The detector classifies each
 * existing gap as `resolved` / `still_open` and may add new gaps
 * surfaced by the user's text. LLM output is treated as a reader of
 * user statements, not a truth-verifier — see FRAG-1 §3 P12.
 *
 * Idempotency: REPLACE on the `replaceForChain` transaction means a
 * re-tap on "重新分析" collapses to the latest write. No special
 * guard needed.
 *
 * Failure: try/catch returns `Result.retry()` for transient LLM
 * failures (network / 5xx). Persistent failures stay in
 * `Result.failure()` only if the chain was deleted (chainId
 * missing).
 */
class NaturalLanguageGapReanalysisWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chainId = inputData.getString("chainId") ?: return Result.failure()
        val userText = inputData.getString("userText").orEmpty()
        if (userText.isBlank()) {
            Log.w(TAG, "chain $chainId: empty userText, skipping")
            return Result.failure()
        }
        val db = AppDatabase.getInstance(applicationContext)
        val chain = db.fragmentChainDao().getById(chainId) ?: return Result.failure()

        return try {
            val result = FragmentGapDetector(db).reanalyzeWithText(chain.id, userText)
            Log.d(
                TAG,
                "reanalysed chain=${chain.id} unresolved=${result.unresolvedCount} " +
                    "added=${result.newGapCount}",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "reanalyse chain=${chain.id} failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NaturalLanguageGapReanalysisWorker"
    }
}
