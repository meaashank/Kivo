package com.example.browser.download

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val downloadId = intent?.getLongExtra(DownloadNotificationManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L

        val engine = DownloadEngine.getInstance(this)

        if (downloadId != -1L) {
            when (action) {
                DownloadNotificationManager.ACTION_PAUSE -> engine.pauseDownload(downloadId)
                DownloadNotificationManager.ACTION_RESUME -> engine.resumeDownload(downloadId)
                DownloadNotificationManager.ACTION_CANCEL -> engine.cancelDownload(downloadId)
            }
        }

        return START_NOT_STICKY
    }
}
