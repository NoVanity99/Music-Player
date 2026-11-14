package com.novanity.musicplayer.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.novanity.musicplayer.MainActivity
import com.novanity.musicplayer.R
import com.novanity.musicplayer.appContainer
import com.novanity.musicplayer.data.DownloadProgress
import com.novanity.musicplayer.data.DownloadsRepository

/**
 * Downloads queued cloud songs in the background. Runs as a foreground job with a progress
 * notification, waits for a network connection, and is restarted by WorkManager if the app or
 * the phone is closed part-way (the queue itself is in the database).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        // Showing the notification can be refused (e.g. started from the background on Android 12+);
        // the download still runs as an ordinary job then.
        runCatching { setForeground(foregroundInfo(null)) }
        var lastUpdate = 0L
        val outcome = applicationContext.appContainer.downloads.processQueue { progress ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 500 || progress.progress >= 1f) {
                lastUpdate = now
                runCatching { manager.notify(NOTIFICATION_ID, notification(progress)) }
            }
        }
        return if (outcome == DownloadsRepository.Outcome.RETRY_LATER) Result.retry() else Result.success()
    }

    private fun foregroundInfo(progress: DownloadProgress?): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification(progress))
        }

    private fun notification(progress: DownloadProgress?): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of songs being saved for offline play"
                }
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext, 1, Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getBroadcast(
            applicationContext, 2, Intent(applicationContext, CancelDownloadsReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val more = progress?.remaining?.minus(1)?.takeIf { it > 0 }
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress?.title?.let { "Downloading “$it”" } ?: "Downloading songs")
            .setContentText(more?.let { if (it == 1) "1 more song queued" else "$it more songs queued" })
            .setProgress(100, ((progress?.progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4201
        private const val WORK_NAME = "song-downloads"

        /**
         * Starts (or queues another pass of) the download job. Appending means songs added while a
         * pass is finishing are still picked up.
         */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** The notification's "Cancel" button: stops and forgets everything that hasn't finished. */
class CancelDownloadsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.appContainer.downloads.cancelAll()
    }
}
