package com.my.knowledge.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.domain.fragment.FragmentGapDetector

/**
 * FRAG-1.4 (P13): best-effort gap resolution when a new item is
 * imported into a KB that has open chains. The detector asks the LLM
 * which existing gaps the new item can confidently cover, and marks
 * those resolved with `resolvedByItemId=itemId`.
 *
 * The pipeline is wired in two places:
 * - RELIAB-1 ingest completion (the worker enqueues a follow-up here)
 * - user-triggered "重新分析" button on the chain detail page
 *
 * Non-matches are deliberately left alone — we never close a gap
 * based on "this item doesn't talk about it" (P13 best-effort). If
 * the LLM output is malformed JSON, the gap rows are untouched.
 */
class NewItemGapMatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString("itemId") ?: return Result.failure()
        val db = AppDatabase.getInstance(applicationContext)
        if (db.knowledgeItemDao().getById(itemId) == null) {
            Log.d(TAG, "item $itemId not found, skipping")
            return Result.failure()
        }

        return try {
            val resolved = FragmentGapDetector(db).matchItemToGaps(itemId)
            Log.d(TAG, "matched item $itemId → ${resolved.size} gap(s) resolved: $resolved")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "match item $itemId failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NewItemGapMatchWorker"
    }
}
