package com.example.browser.transfer

import android.net.Uri

data class SharedFileItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val uri: Uri? = null,
    val localFilePath: String? = null
) {
    val sizeFormatted: String
        get() = formatFileSize(sizeBytes)
}

enum class TransferDirection {
    UPLOAD, DOWNLOAD
}

enum class TransferStatus {
    PENDING, IN_PROGRESS, COMPLETED, CANCELLED, FAILED
}

data class TransferItem(
    val id: String,
    val fileName: String,
    val totalBytes: Long,
    val bytesTransferred: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val direction: TransferDirection,
    val clientIp: String = "",
    val status: TransferStatus = TransferStatus.PENDING,
    val errorMessage: String? = null
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val speedFormatted: String
        get() = "${formatFileSize(speedBytesPerSec)}/s"

    val sizeFormatted: String
        get() = "${formatFileSize(bytesTransferred)} / ${formatFileSize(totalBytes)}"
}

data class DiscoveredDevice(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
