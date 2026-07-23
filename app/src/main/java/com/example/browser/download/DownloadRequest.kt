package com.example.browser.download

data class DownloadRequest(
    val url: String,
    val userAgent: String = "",
    val contentDisposition: String = "",
    val mimeType: String = "application/octet-stream",
    val contentLength: Long = 0L
)
