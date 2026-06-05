package com.my.knowledge.worker

import android.app.Application
import android.util.Log
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
 *
 * P1-D / Phase 6: `onCreate` is now crash-safe. The previous
 * version called `AppDatabase.getInstance(...)` directly; if the
 * on-disk DB was in a bad state (e.g. a v10→v11 migration left
 * FTS triggers pointing at a half-populated content table, the
 * classic "OS error -2" the user reported), Room would throw at
 * first open and the process would die before any UI was shown.
 * We now catch that, log it, delete the corrupted DB, and let
 * Room re-create it from the schema. Same recovery as
 * `fallbackToDestructiveMigration` but opt-in, only triggered
 * when the open itself fails.
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
        try {
            // Warm the Room database and the worker factory's
            // dependency graph so the first scheduled worker doesn't
            // pay the IO on its critical path.
            AppDatabase.getInstance(applicationContext)
            factory.warmup()
        } catch (e: Exception) {
            // Database won't open — most likely a stale FTS index
            // from a previous v10→v11 migration that left
            // `knowledge_*_fts` in a half-populated state (the same
            // class of failure that surfaced as
            // "SQL logic error (OS error -2)" in the generation
            // stage). Wipe the on-disk DB and let Room re-create
            // it from the v11 schema. Existing sources that haven't
            // been re-imported are lost; everything else (KB
            // metadata, knowledge items, fragments) lives in the
            // same DB and is wiped together — there's no
            // partial-recovery path that doesn't risk a half-broken
            // FTS index coming back.
            Log.e("MyKnowledgeApp", "AppDatabase open failed, wiping and recreating", e)
            runCatching { deleteDatabase("knowledge_db") }
            try {
                AppDatabase.getInstance(applicationContext)
                factory.warmup()
            } catch (recover: Exception) {
                // If even the second open fails, log and continue —
                // the UI must still come up so the user can at least
                // see the empty state and re-import.
                Log.e("MyKnowledgeApp", "AppDatabase still failed after wipe", recover)
            }
        }
    }
}
