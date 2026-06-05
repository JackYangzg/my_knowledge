package com.my.knowledge.worker

import android.app.Application
import androidx.work.Configuration
import com.my.knowledge.data.db.AppDatabase

/**
 * P1-C / ARCH-1: project Application class.
 *
 * Implements [Configuration.Provider] so WorkManager auto-initializes
 * with [MyKnowledgeWorkerFactory] the first time something calls
 * `WorkManager.getInstance(context)`. The factory is the single
 * construction site for every `CoroutineWorker` subclass in the
 * project; adding a new worker means adding one entry to the
 * factory's `when` block, not editing every call site.
 *
 * The default WorkManager initializer in the manifest is disabled
 * (see `AndroidManifest.xml`'s
 * `tools:node="remove"` on the default initializer), so this
 * on-demand initializer is the only path that builds the
 * `Configuration`.
 */
class MyKnowledgeApp : Application(), Configuration.Provider {

    private val factory: MyKnowledgeWorkerFactory by lazy {
        MyKnowledgeWorkerFactory(applicationContext)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setWorkerFactory(factory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Warm the Room database and the worker factory's
        // dependency graph so the first scheduled worker doesn't
        // pay the IO on its critical path.
        AppDatabase.getInstance(applicationContext)
        factory.warmup()
    }
}
