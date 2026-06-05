package com.my.knowledge.worker

import android.content.Context
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.domain.repository.KnowledgeRepository

/**
 * P1-C / ARCH-1: single point of truth for worker dependency wiring.
 *
 * Before this, every `CoroutineWorker` subclass re-derived its own
 * `KnowledgeRepositoryImpl` constructor call with all 18 DAO
 * arguments. Adding a new DAO meant editing every worker. Moving
 * the constructor to one place means new workers only need
 * `WorkerDependencies.from(applicationContext).repository`.
 *
 * `MyKnowledgeWorkerFactory` is the matching WorkerFactory that
 * lets WorkManager construct these workers without a Hilt
 * dependency.
 */
class WorkerDependencies private constructor(
    val appContext: Context,
    val database: AppDatabase,
    val repository: KnowledgeRepository,
) {
    companion object {
        fun from(context: Context): WorkerDependencies {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val repo: KnowledgeRepository = KnowledgeRepositoryImpl(
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
            return WorkerDependencies(appContext, db, repo)
        }
    }
}
