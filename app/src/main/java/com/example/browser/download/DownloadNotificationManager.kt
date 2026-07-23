package com.example.browser.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.browser.data.DownloadItem
import com.example.browser.data.DownloadStatus
import java.io.File

class DownloadNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "kivo_downloads_channel"
        const val ACTION_PAUSE = "com.example.browser.download.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.browser.download.ACTION_RESUME"
        const val ACTION_CANCEL = "com.example.browser.download.ACTION_CANCEL"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Browser Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for ongoing downloads"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(item: DownloadItem) {
        val notificationId = item.id.toInt()

        // Content Intent: Open Browser MainActivity -> Downloads page
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_downloads", true)
        }
        val pendingMainIntent = PendingIntent.getActivity(
            context,
            notificationId,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when (item.statusEnum) {
            DownloadStatus.RUNNING -> {
                val pauseIntent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_DOWNLOAD_ID, item.id)
                }
                val pendingPause = PendingIntent.getService(
                    context,
                    (item.id * 10 + 1).toInt(),
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val cancelIntent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_DOWNLOAD_ID, item.id)
                }
                val pendingCancel = PendingIntent.getService(
                    context,
                    (item.id * 10 + 2).toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val progress = (item.progressPercent * 100).toInt()

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Downloading ${item.fileName}")
                    .setContentText("${item.sizeFormatted} • ${item.speedFormatted} • ${item.etaFormatted}")
                    .setProgress(100, progress, item.totalBytes <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pendingMainIntent)
                    .addAction(android.R.drawable.ic_media_pause, "Pause", pendingPause)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)

                notificationManager.notify(notificationId, builder.build())
            }

            DownloadStatus.PAUSED -> {
                val resumeIntent = Intent(context, DownloadService::class.java).apply {
                    action = ACTION_RESUME
                    putExtra(EXTRA_DOWNLOAD_ID, item.id)
                }
                val pendingResume = PendingIntent.getService(
                    context,
                    (item.id * 10 + 3).toInt(),
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Paused: ${item.fileName}")
                    .setContentText("${item.sizeFormatted} • Click to resume")
                    .setOngoing(false)
                    .setContentIntent(pendingMainIntent)
                    .addAction(android.R.drawable.ic_media_play, "Resume", pendingResume)

                notificationManager.notify(notificationId, builder.build())
            }

            DownloadStatus.COMPLETED -> {
                val openIntent = getOpenFileIntent(item)
                val pendingOpen = if (openIntent != null) {
                    PendingIntent.getActivity(
                        context,
                        (item.id * 10 + 4).toInt(),
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else pendingMainIntent

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download Complete")
                    .setContentText(item.fileName)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingOpen)

                notificationManager.notify(notificationId, builder.build())
            }

            DownloadStatus.FAILED -> {
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Download Failed")
                    .setContentText("${item.fileName}: ${item.errorMessage ?: "Unknown error"}")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingMainIntent)

                notificationManager.notify(notificationId, builder.build())
            }

            DownloadStatus.CANCELLED -> {
                notificationManager.cancel(notificationId)
            }

            else -> {}
        }
    }

    fun cancelNotification(id: Long) {
        notificationManager.cancel(id.toInt())
    }

    private fun getOpenFileIntent(item: DownloadItem): Intent? {
        return try {
            val file = File(item.localPath)
            if (!file.exists()) return null
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            null
        }
    }
}
