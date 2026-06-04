package com.my.knowledge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.ingest.ThreadEvolutionRunner
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository

/**
 * Thin WorkManager adapter around [ThreadEvolutionRunner].
 *
 * P0-1: the rebuild path is no longer enqueued by the WorkManager
 * unique-work chain. The orchestrator / scheduler go through
 * `RebuildDebouncer` instead (which calls [ThreadEvolutionRunner]
 * directly), so this worker only fires if some other code path still
 * enqueues a `thread_update_<kbId>` job (e.g. an external future
 * trigger or a hand-written WorkRequest). It keeps the WorkManager
 * Result.success / Result.failure contract intact for those callers.
 *
 * The body is intentionally minimal: a few lines that just hand off
 * to the shared `suspend fun` and translate the outcome to a
 * `Worker.Result`. Anything fancier belongs in
 * [ThreadEvolutionRunner.runEvolution].
 */
class ThreadEvolutionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kbId = inputData.getString("knowledgeBaseId") ?: return Result.failure()
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val repository = getRepository(db)
            ThreadEvolutionRunner.runEvolution(db, repository, kbId)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    private fun getRepository(db: AppDatabase): KnowledgeRepository {
        return KnowledgeRepositoryImpl(
            db.knowledgeBaseDao(), db.knowledgeItemDao(),
            db.processingTaskDao(), db.archiveRecommendationDao(),
            db.aiConversationDao(), db.aiMessageDao(),
            db.knowledgeThreadDao(), db.knowledgeThreadLogDao(),
            db.sourceManifestDao(), db.knowledgeFragmentDao(),
            db.processingTaskLogDao(), db.askCitationDao(),
            db.knowledgeGraphDao(), db.reviewItemDao(),
            db.analysisResultDao(),
            db.parsedContentDao(),
            db.sourceDocumentDao()
        )
    }
}
