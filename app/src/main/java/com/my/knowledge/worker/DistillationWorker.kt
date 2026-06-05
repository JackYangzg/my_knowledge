package com.my.knowledge.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.domain.fragment.DistillationEngine
import com.my.knowledge.domain.fragment.LifecycleStatus

/**
 * FRAG-1.3: chains `DISTILL_READY → RECOMMEND_READY` by running the LLM
 * distillation. The engine writes the new `wiki_synthesis` item and
 * flips the chain status; this worker is the WorkManager wrapper that
 * owns retry / failure semantics.
 *
 * Idempotency: if the chain has already moved past `DISTILL_READY`
 * (e.g. a prior run succeeded but the result-fanout failed), the
 * worker returns `Result.success()` without re-running. This is
 * important because the LLM cost of distillation is non-trivial.
 *
 * Failure: try/catch returns `Result.retry()` so WorkManager applies
 * the default exponential backoff. After exhaustion the chain stays
 * in `DISTILL_READY` and the user can re-trigger via the detail page.
 */
class DistillationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val chainId = inputData.getString("chainId") ?: return Result.failure()
        val db = AppDatabase.getInstance(applicationContext)
        val chain = db.fragmentChainDao().getById(chainId) ?: return Result.failure()
        if (chain.status != LifecycleStatus.DISTILL_READY.name) {
            Log.d(TAG, "chain $chainId not in DISTILL_READY (status=${chain.status}); skipping")
            return Result.success()
        }

        return try {
            val engine = DistillationEngine(db)
            val result = engine.distill(chainId)
            Log.d(
                TAG,
                "distilled chain=$chainId item=${result.distilledItemId} " +
                    "md=${result.markdownLength} src=${result.sourcePageCount}",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "distill chain=$chainId failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DistillationWorker"
    }
}
