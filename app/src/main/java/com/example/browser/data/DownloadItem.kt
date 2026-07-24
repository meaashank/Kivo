package com.example.browser.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "download_items")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: String = DownloadStatus.PENDING.name,
    val localPath: String = "",
    val userAgent: String = "",
    val contentDisposition: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val startTime: Long = System.currentTimeMillis(),
    val elapsedTimeMillis: Long = 0L,
    val networkType: String = "Wi-Fi",
    val errorMessage: String? = null,
    val etag: String? = null,
    val openAfterDownload: Boolean = false
) {
    val statusEnum: DownloadStatus
        get() = try {
            DownloadStatus.valueOf(status)
        } catch (e: Exception) {
            DownloadStatus.FAILED
        }

    val progressPercent: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val speedFormatted: String
        get() = "${formatFileSize(speedBytesPerSec)}/s"

    val sizeFormatted: String
        get() = if (totalBytes > 0) {
            "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}"
        } else {
            formatFileSize(downloadedBytes)
        }

    val domainName: String
        get() = try {
            val uri = java.net.URI(url)
            uri.host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }

    val etaFormatted: String
        get() {
            if (speedBytesPerSec <= 0 || totalBytes <= 0 || downloadedBytes >= totalBytes) return "--"
            val remainingBytes = totalBytes - downloadedBytes
            val secondsLeft = remainingBytes / speedBytesPerSec
            return when {
                secondsLeft < 60 -> "${secondsLeft}s left"
                secondsLeft < 3600 -> "${secondsLeft / 60}m ${secondsLeft % 60}s left"
                else -> "${secondsLeft / 3600}h ${(secondsLeft % 3600) / 60}m left"
            }
        }

    val elapsedFormatted: String
        get() {
            val totalSeconds = elapsedTimeMillis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    if (exp == 0) return "$bytes B"
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
