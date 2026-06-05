package com.my.knowledge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.my.knowledge.ui.KnowledgeApp
import com.my.knowledge.ui.KnowledgeManager
import com.my.knowledge.ui.theme.My_knowledgeTheme

class MainActivity : FragmentActivity() {

    // RELIAB-1 PR-N1: request POST_NOTIFICATIONS on API 33+. The
    // launcher is the only natural place to ask — before the user
    // starts an import. Denial is non-fatal: the FG service still
    // boosts the process, we just don't render the "剩余 N 条" text.
    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — the service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // P1-D / Phase 6: must install the crash handler *before*
        // anything that can throw, otherwise the very crash we're
        // trying to diagnose never reaches the handler.
        installCrashLogger()
        super.onCreate(savedInstanceState)

        requestPostNotificationsIfNeeded()

        try {
            // Initialize the persistence layer (JSON DB + File System)
            KnowledgeManager.init(this)
        } catch (e: Exception) {
            // If the persistence layer can't come up (e.g. the
            // v10→v11 migration left the DB in a broken state, the
            // same "OS error -2" class the user reported), still
            // render the UI so the user can at least see what's
            // going on and re-import sources.
            Log.e("MainActivity", "KnowledgeManager.init failed", e)
        }

        setContent {
            My_knowledgeTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KnowledgeApp()
                }
            }
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "MyKnowledgeApp",
                "FATAL on thread ${thread.name} (id=${thread.id})",
                throwable
            )
            // Defer to the platform's default handler so the
            // process still dies — the log line above is what we
            // collect via `adb logcat` to diagnose the next crash.
            previous?.uncaughtException(thread, throwable)
        }
    }
}
