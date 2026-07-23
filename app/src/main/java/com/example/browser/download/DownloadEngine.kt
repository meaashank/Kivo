package com.example.browser.download

import android.content.Context
import android.os.Environment
import android.webkit.URLUtil
import com.example.browser.data.BrowserDatabase
import com.example.browser.data.DownloadItem
import com.example.browser.data.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class DownloadEngine private constructor(private val context: Context) {

    private val db = BrowserDatabase.getDatabase(context)
    private val dao = db.browserDao()
    private val prefs = DownloadPreferences(context)
    private val notificationManager = DownloadNotificationManager(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobMap = ConcurrentHashMap<Long, Job>()

    private val _downloadEvents = MutableSharedFlow<DownloadItem>(extraBufferCapacity = 64)
    val downloadEvents: SharedFlow<DownloadItem> = _downloadEvents.asSharedFlow()

    companion object {
        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadEngine(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun enqueueDownload(
        url: String,
        userAgent: String = "",
        contentDisposition: String = "",
        mimeType: String = "",
        customFileName: String? = null,
        customSaveDirectory: String? = null
    ): Long {
        val guessedName = if (!customFileName.isNullOrEmpty()) {
            customFileName
        } else {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        val targetDir = customSaveDirectory ?: prefs.downloadFolder
        val folder = File(targetDir)
        if (!folder.exists()) {
            folder.mkdirs()
        }

        // Handle duplicate file names
        val finalFileName = getUniqueFileName(folder, guessedName)
        val targetFile = File(folder, finalFileName)

        val item = DownloadItem(
            url = url,
            fileName = finalFileName,
            mimeType = mimeType.ifEmpty { "application/octet-stream" },
            localPath = targetFile.absolutePath,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            status = DownloadStatus.PENDING.name,
            timestamp = System.currentTimeMillis()
        )

        val id = dao.insertDownload(item)
        val newItem = item.copy(id = id)

        startDownloadTask(newItem)
        return id
    }

    fun startDownloadTask(item: DownloadItem) {
        if (activeJobMap.containsKey(item.id)) return

        val job = scope.launch {
            runDownloadLoop(item)
        }
        activeJobMap[item.id] = job
    }

    fun pauseDownload(id: Long) {
        activeJobMap[id]?.cancel()
        activeJobMap.remove(id)

        scope.launch {
            val item = dao.getDownloadById(id) ?: return@launch
            val updated = item.copy(
                status = DownloadStatus.PAUSED.name,
                speedBytesPerSec = 0
            )
            dao.updateDownload(updated)
            notificationManager.updateNotification(updated)
            _downloadEvents.emit(updated)
        }
    }

    fun resumeDownload(id: Long) {
        scope.launch {
            val item = dao.getDownloadById(id) ?: return@launch
            if (item.statusEnum == DownloadStatus.PAUSED || item.statusEnum == DownloadStatus.FAILED) {
                startDownloadTask(item)
            }
        }
    }

    fun cancelDownload(id: Long) {
        activeJobMap[id]?.cancel()
        activeJobMap.remove(id)

        scope.launch {
            val item = dao.getDownloadById(id) ?: return@launch
            val updated = item.copy(
                status = DownloadStatus.CANCELLED.name,
                speedBytesPerSec = 0
            )
            dao.updateDownload(updated)
            notificationManager.cancelNotification(id)
            _downloadEvents.emit(updated)

            // Delete partial file
            try {
                val file = File(item.localPath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteDownloadRecord(id: Long, deleteFileFromDisk: Boolean) {
        cancelDownload(id)
        scope.launch {
            val item = dao.getDownloadById(id)
            if (item != null && deleteFileFromDisk) {
                try {
                    val file = File(item.localPath)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            dao.deleteDownloadById(id)
        }
    }

    private suspend fun runDownloadLoop(initialItem: DownloadItem) {
        var currentItem = initialItem.copy(status = DownloadStatus.RUNNING.name)
        dao.updateDownload(currentItem)
        notificationManager.updateNotification(currentItem)
        _downloadEvents.emit(currentItem)

        val targetFile = File(currentItem.localPath)
        val existingLength = if (targetFile.exists()) targetFile.length() else 0L

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            val url = URL(currentItem.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true

            if (currentItem.userAgent.isNotEmpty()) {
                connection.setRequestProperty("User-Agent", currentItem.userAgent)
            }

            // Check range support if resuming
            if (existingLength > 0) {
                connection.setRequestProperty("Range", "bytes=$existingLength-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            val isOk = responseCode == HttpURLConnection.HTTP_OK

            if (!isOk && !isPartial) {
                throw Exception("HTTP Server Error $responseCode: ${connection.responseMessage}")
            }

            val totalContentLength = if (isPartial) {
                existingLength + connection.contentLengthLong
            } else {
                connection.contentLengthLong
            }

            val actualStartByte = if (isPartial) existingLength else 0L

            currentItem = currentItem.copy(
                totalBytes = if (totalContentLength > 0) totalContentLength else currentItem.totalBytes,
                downloadedBytes = actualStartByte
            )
            dao.updateDownload(currentItem)

            outputStream = FileOutputStream(targetFile, isPartial)
            inputStream = connection.inputStream

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalRead = actualStartByte
            var lastSpeedCalcTime = System.currentTimeMillis()
            var bytesInWindow = 0L

            while (coroutineContext.isActive) {
                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                bytesInWindow += bytesRead

                val now = System.currentTimeMillis()
                val delta = now - lastSpeedCalcTime

                if (delta >= 500) {
                    val speed = (bytesInWindow * 1000) / delta
                    lastSpeedCalcTime = now
                    bytesInWindow = 0

                    currentItem = currentItem.copy(
                        downloadedBytes = totalRead,
                        speedBytesPerSec = speed
                    )
                    dao.updateDownload(currentItem)
                    notificationManager.updateNotification(currentItem)
                    _downloadEvents.emit(currentItem)
                }
            }

            outputStream.flush()

            if (coroutineContext.isActive) {
                currentItem = currentItem.copy(
                    downloadedBytes = totalRead,
                    totalBytes = if (currentItem.totalBytes > 0) currentItem.totalBytes else totalRead,
                    status = DownloadStatus.COMPLETED.name,
                    speedBytesPerSec = 0
                )
                dao.updateDownload(currentItem)
                notificationManager.updateNotification(currentItem)
                _downloadEvents.emit(currentItem)
            }

        } catch (e: CancellationException) {
            // Task paused or cancelled
        } catch (e: Exception) {
            currentItem = currentItem.copy(
                status = DownloadStatus.FAILED.name,
                errorMessage = e.localizedMessage ?: "Network error",
                speedBytesPerSec = 0
            )
            dao.updateDownload(currentItem)
            notificationManager.updateNotification(currentItem)
            _downloadEvents.emit(currentItem)
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            try { outputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
            activeJobMap.remove(initialItem.id)
        }
    }

    private fun getUniqueFileName(folder: File, fileName: String): String {
        var file = File(folder, fileName)
        if (!file.exists()) return fileName

        val dotIndex = fileName.lastIndexOf('.')
        val name = if (dotIndex != -1) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex != -1) fileName.substring(dotIndex) else ""

        var count = 1
        while (file.exists()) {
            val newName = "$name ($count)$ext"
            file = File(folder, newName)
            count++
        }
        return file.name
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }
}
