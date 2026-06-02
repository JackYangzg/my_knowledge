package com.my.knowledge.data.ingest

import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.db.entity.ReviewItemEntity
import kotlinx.coroutines.flow.firstOrNull

/**
 * Mirrors llm_wiki's `sweepResolvedReviews` (src/lib/sweep-reviews.ts).
 *
 * After a rebuild of the knowledge graph we walk every pending review
 * and decide whether its underlying concern is now resolved by the
 * freshly-generated content. Resolved reviews are soft-deleted; the
 * rest stay for the user to act on.
 *
 * "Resolved" is intentionally conservative: a review is auto-closed only
 * when the heuristic is unambiguous (the target page now exists, or the
 * community is now a single source). Anything fuzzy stays pending.
 */
class SweepReviews(
    private val db: AppDatabase
) {
    suspend fun sweep(knowledgeBaseId: String? = null) {
        val pending = if (knowledgeBaseId == null) {
            db.reviewItemDao().observePending().firstOrNull().orEmpty()
        } else {
            // We don't have a per-base variant of the review query
            // (reviews are global); filter in-memory by the affected
            // pages' source-id binding.
            db.reviewItemDao().observePending().firstOrNull().orEmpty().filter { review ->
                reviewBelongsToBase(review, knowledgeBaseId)
            }
        }
        for (review in pending) {
            if (isResolved(review)) {
                db.reviewItemDao().resolve(
                    review.id,
                    ReviewItemEntity.STATUS_RESOLVED,
                    System.currentTimeMillis()
                )
            }
        }
    }

    private suspend fun reviewBelongsToBase(
        review: ReviewItemEntity,
        knowledgeBaseId: String
    ): Boolean {
        // The review payload encodes the affected pages. We resolve
        // each page back to its item, then check the item's
        // knowledgeBaseId. Cheap because the list is typically <10.
        val affectedIds = extractAffectedItemIds(review)
        if (affectedIds.isEmpty()) return false
        return affectedIds.any { id ->
            db.knowledgeItemDao().getById(id)?.knowledgeBaseId == knowledgeBaseId
        }
    }

    private suspend fun isResolved(review: ReviewItemEntity): Boolean {
        val affectedIds = extractAffectedItemIds(review)
        if (affectedIds.isEmpty()) return false
        // "missing-page" / "duplicate": if the named page now exists in
        // any knowledge item title (case-insensitive substring) anywhere
        // in the same base, the concern is satisfied.
        if (review.type == "missing-page" || review.type == "duplicate") {
            val needle = review.title.trim().lowercase()
            if (needle.isBlank()) return false
            return affectedIds.any { id ->
                val item = db.knowledgeItemDao().getById(id) ?: return@any false
                val haystack = item.title.lowercase()
                haystack.contains(needle)
            }
        }
        // "contradiction" / "suggestion" / "confirm" / others: keep
        // for human review; we don't auto-resolve.
        return false
    }

    private fun extractAffectedItemIds(review: ReviewItemEntity): List<String> {
        val raw = review.payloadJson
        if (raw.isBlank() || raw == "{}") return emptyList()
        // We accept either a JSON array of ids, or an object with an
        // "affectedPages" array. Keep this parsing loose to match
        // whichever writer produced the review.
        return runCatching {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("[") -> {
                    org.json.JSONArray(trimmed).let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                    }
                }
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    val arr = obj.optJSONArray("affectedPages") ?: obj.optJSONArray("affectedItems")
                    if (arr != null) {
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                    } else emptyList()
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }
}
