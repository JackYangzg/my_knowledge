package com.my.knowledge.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.my.knowledge.R

/**
 * RELIAB-1 PR-N1: foreground service that keeps the ingest process
 * alive across Doze / OEM background-killers. The user sees a
 * low-importance, ongoing notification so the system treats the
 * process as user-visible and the LLM streaming response has a
 * chance to finish when the screen is off.
 *
 * The notification is updated externally (via [Companion.notify]
 * + the `pendingCount` StateFlow on [IngestRuntime]) so this class
 * stays a thin wrapper around `startForeground` / `stopForeground`.
 */
class IngestForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingCount = intent?.getIntExtra(EXTRA_PENDING_COUNT, 0) ?: 0
        // FOREGROUND_SERVICE_TYPE_DATA_SYNC is required on API 30+;
        // the manifest also declares `dataSync` so the platform can
        // validate the type matches the actual workload (ingest = sync).
        startForeground(
            NOTIFICATION_ID,
            buildNotification(pendingCount),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        // Idempotent: NotificationManager dedupes by channel id.
        // IMPORTANCE_LOW keeps the notification silent (no sound / no
        // peek) which matches the operator-facing "battery indicator"
        // tone — the user knows the import is running but isn't pestered.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ingest_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(pendingCount: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.ingest_service_title))
            .setContentText(getString(R.string.ingest_service_pending, pendingCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "ingest_progress"
        const val EXTRA_PENDING_COUNT = "pending_count"

        /**
         * Promote the process to a dataSync foreground service. Safe
         * to call multiple times — `startForeground` is idempotent on
         * the same notification id, and a no-op when the service is
         * already running.
         */
        fun start(context: Context, pendingCount: Int) {
            val intent = Intent(context, IngestForegroundService::class.java)
                .putExtra(EXTRA_PENDING_COUNT, pendingCount)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Demote back to background and dismiss the notification. Called
         * from [IngestRuntime.cancel] and the runOnce `finally` block
         * when no more work is queued, so the service never lingers
         * past its useful lifetime (Google Play audits "ghost" FG
         * services).
         */
        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, IngestForegroundService::class.java))
        }

        /**
         * Update the notification text with the current pending count
         * without restarting the service. Used by the orchestrator on
         * each item completion (wired in PR-N2 / RELIAB-1).
         */
        fun notify(context: Context, pendingCount: Int) {
            val ctx = appContext(context)
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(ctx.getString(R.string.ingest_service_title))
                .setContentText(ctx.getString(R.string.ingest_service_pending, pendingCount))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun appContext(context: Context): Context = context.applicationContext
    }
}
