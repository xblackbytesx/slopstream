package com.xblackbytesx.slopstream.data.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xblackbytesx.slopstream.data.db.DownloadEntity
import com.xblackbytesx.slopstream.data.db.DownloadState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Works the download queue in the foreground (a progress notification), so it carries on with
 * the screen off. Needs a network; when it goes away mid-file the worker retries later and
 * the part files are continued.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: Downloads,
) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val startedAt = System.currentTimeMillis()
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        channel()
        // Refused when the app is in the background on Android 12+: the work still runs.
        runCatching { setForeground(info(null, 0)) }
        val finished = downloads.runQueue { row, done ->
            val now = System.currentTimeMillis()
            if (now - lastNotified >= 1_000) {
                lastNotified = now
                runCatching { setForeground(info(row, done)) }
            }
        }
        notifyFinished()
        return if (finished) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(null, 0)

    private fun info(row: DownloadEntity?, done: Long): ForegroundInfo {
        val b = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(row?.title ?: "Downloading")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (row != null && row.sizeBytes > 0) {
            val percent = (done * 100 / row.sizeBytes).toInt().coerceIn(0, 100)
            b.setProgress(100, percent, false).setContentText("$percent %")
        } else {
            b.setProgress(0, 0, true)
        }
        return ForegroundInfo(PROGRESS_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** One quiet line once the queue is empty: what is ready to watch offline. */
    private suspend fun notifyFinished() {
        val allowed = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val done = downloads.finishedSince(startedAt)
        if (done.isEmpty()) return
        val text = if (done.size == 1) done.single().title else "${done.size} videos"
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloaded")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notifications.notify(DONE_ID, n)
    }

    private fun channel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val NAME = "downloads"
        private const val CHANNEL = "downloads"
        private const val PROGRESS_ID = 4201
        private const val DONE_ID = 4202

        /** Runs the queue; a worker already running picks new downloads up itself. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** The finished ones among [rows], for the notification. */
internal fun List<DownloadEntity>.finishedAfter(at: Long) = filter { it.state == DownloadState.DONE.name && (it.finishedAt ?: 0) >= at }
