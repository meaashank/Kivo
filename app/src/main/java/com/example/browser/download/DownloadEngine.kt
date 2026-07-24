package com.example.browser.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.content.FileProvider
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

    init {
        restoreDownloadsOnStartup()
    }

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

    private fun restoreDownloadsOnStartup() {
        scope.launch {
            try {
                val activeList = dao.getActiveDownloads()
                for (item in activeList) {
                    if (prefs.autoResume) {
                        startDownloadTask(item)
                    } else {
                        val paused = item.copy(status = DownloadStatus.PAUSED.name, speedBytesPerSec = 0)
                        dao.updateDownload(paused)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getCurrentNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Wi-Fi"
        }
    }

    suspend fun enqueueDownload(
        url: String,
        userAgent: String = "",
        contentDisposition: String = "",
        mimeType: String = "",
        customFileName: String? = null,
        customSaveDirectory: String? = null,
        openAfterDownload: Boolean = false
    ): Long {
        val guessedName = if (!customFileName.isNullOrEmpty()) {
            customFileName
        } else {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        var targetDir = customSaveDirectory ?: prefs.downloadFolder
        var folder = File(targetDir)
        if (!folder.exists() && !folder.mkdirs()) {
            // Fallback to default public Downloads directory
            targetDir = DownloadPreferences.getDefaultDownloadDir(context)
            prefs.downloadFolder = targetDir
            folder = File(targetDir)
            if (!folder.exists()) {
                folder.mkdirs()
            }
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
            timestamp = System.currentTimeMillis(),
            startTime = System.currentTimeMillis(),
            networkType = getCurrentNetworkType(),
            openAfterDownload = openAfterDownload
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
        val startTs = if (initialItem.startTime > 0) initialItem.startTime else System.currentTimeMillis()
        var currentItem = initialItem.copy(
            status = DownloadStatus.RUNNING.name,
            startTime = startTs,
            networkType = getCurrentNetworkType()
        )
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
                val errorMsg = when (responseCode) {
                    404 -> "File not found on server (HTTP 404)"
                    403 -> "Server access denied (HTTP 403)"
                    401 -> "Authentication required (HTTP 401)"
                    in 500..599 -> "Server temporary error (HTTP $responseCode)"
                    else -> "Download server returned HTTP error $responseCode"
                }
                throw Exception(errorMsg)
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
                        speedBytesPerSec = speed,
                        elapsedTimeMillis = now - currentItem.startTime
                    )
                    dao.updateDownload(currentItem)
                    notificationManager.updateNotification(currentItem)
                    _downloadEvents.emit(currentItem)
                }
            }

            outputStream.flush()

            if (coroutineContext.isActive) {
                val now = System.currentTimeMillis()
                currentItem = currentItem.copy(
                    downloadedBytes = totalRead,
                    totalBytes = if (currentItem.totalBytes > 0) currentItem.totalBytes else totalRead,
                    status = DownloadStatus.COMPLETED.name,
                    speedBytesPerSec = 0,
                    elapsedTimeMillis = now - currentItem.startTime
                )
                dao.updateDownload(currentItem)
                notificationManager.updateNotification(currentItem)
                _downloadEvents.emit(currentItem)

                // Auto open if configured
                if (currentItem.openAfterDownload || prefs.openFileAfterDownload) {
                    openDownloadedFile(context, currentItem)
                }
            }

        } catch (e: CancellationException) {
            // Task paused or cancelled
        } catch (e: Exception) {
            val userFriendlyError = when {
                e is java.io.FileNotFoundException -> "Storage access is required to save downloads."
                e is java.net.UnknownHostException || e is java.net.ConnectException -> "Network connection lost. Tap to retry."
                e is java.net.SocketTimeoutException -> "Connection timed out. Tap to retry."
                e.message?.contains("permission", ignoreCase = true) == true -> "Storage write permission needed."
                e.message?.contains("ENOSPC", ignoreCase = true) == true -> "Device storage is full."
                !e.localizedMessage.isNullOrEmpty() -> e.localizedMessage
                else -> "Download failed due to network error."
            }

            currentItem = currentItem.copy(
                status = DownloadStatus.FAILED.name,
                errorMessage = userFriendlyError,
                speedBytesPerSec = 0,
                elapsedTimeMillis = System.currentTimeMillis() - currentItem.startTime
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

    private fun openDownloadedFile(context: Context, item: DownloadItem) {
        try {
            val file = File(item.localPath)
            if (!file.exists()) return
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
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
}
