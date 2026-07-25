package com.example.browser.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalFileServer(
    private val context: Context,
    val port: Int,
    private val serverListener: ServerListener
) {
    interface ServerListener {
        fun onServerStarted(ipAddress: String, port: Int)
        fun onServerStopped()
        fun onError(message: String)
        fun onTransferProgress(item: TransferItem)
        fun onTransferCompleted(item: TransferItem)
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    // Shared files exposed for download
    val sharedFilesMap = ConcurrentHashMap<String, SharedFileItem>()
    // Transfer history & active transfers
    val activeTransfers = ConcurrentHashMap<String, TransferItem>()

    fun start(ipAddress: String) {
        if (isRunning) return
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                isRunning = true
                serverListener.onServerStarted(ipAddress, port)

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e("LocalFileServer", "Error starting server: ${e.message}")
                serverListener.onError("Failed to start server on port $port: ${e.localizedMessage}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning && serverSocket == null) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        serverListener.onServerStopped()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000 // 30s timeout
            val rawInput = socket.getInputStream()
            val rawOutput = socket.getOutputStream()

            // REQ 1 & 8: Byte-level HTTP Header Reader without over-reading stream
            val headerString = readHttpHeader(rawInput) ?: return
            val headerLines = headerString.split("\r\n")
            if (headerLines.isEmpty()) return

            val requestLine = headerLines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullUrl = parts[1]
            val clientIp = socket.inetAddress?.hostAddress ?: "Client"

            // Parse Headers
            val headers = HashMap<String, String>()
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                if (line.isEmpty()) break
                val hp = line.split(":", limit = 2)
                if (hp.size == 2) {
                    headers[hp[0].trim().lowercase()] = hp[1].trim()
                }
            }

            val path = fullUrl.substringBefore("?")

            when {
                method == "OPTIONS" -> {
                    handleOptions(rawOutput)
                }
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    serveWebDashboard(rawOutput)
                }
                method == "GET" && path == "/api/files" -> {
                    serveFileListJson(rawOutput)
                }
                method == "GET" && (path.startsWith("/download") || path.startsWith("/api/download")) -> {
                    val fileId = Uri.parse(fullUrl).getQueryParameter("id")
                    handleFileDownload(fileId, headers, rawOutput, clientIp)
                }
                method == "POST" && path == "/api/upload-stream" -> {
                    handleStreamUpload(rawInput, rawOutput, headers, clientIp)
                }
                method == "POST" && path == "/upload" -> {
                    handleMultipartUpload(rawInput, rawOutput, headers, clientIp)
                }
                else -> {
                    sendResponse(rawOutput, "404 Not Found", "text/plain", "Endpoint not found.")
                }
            }
            rawOutput.flush()
        } catch (e: Exception) {
            Log.d("LocalFileServer", "Client handling finished: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * Reads HTTP headers from InputStream byte-by-byte up to \r\n\r\n
     * Prevents losing binary payload bytes!
     */
    private fun readHttpHeader(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b: Int
        var consecutiveEols = 0
        while (input.read().also { b = it } != -1) {
            baos.write(b)
            if (b == '\r'.code || b == '\n'.code) {
                consecutiveEols++
            } else {
                consecutiveEols = 0
            }

            val bytes = baos.toByteArray()
            val len = bytes.size
            if (len >= 4 &&
                bytes[len - 4] == '\r'.code.toByte() &&
                bytes[len - 3] == '\n'.code.toByte() &&
                bytes[len - 2] == '\r'.code.toByte() &&
                bytes[len - 1] == '\n'.code.toByte()
            ) {
                return String(bytes, 0, len - 4, Charsets.UTF_8)
            } else if (len >= 2 &&
                bytes[len - 2] == '\n'.code.toByte() &&
                bytes[len - 1] == '\n'.code.toByte()
            ) {
                return String(bytes, 0, len - 2, Charsets.UTF_8)
            }
            if (baos.size() > 64 * 1024) break // Header overflow protection
        }
        val bytes = baos.toByteArray()
        return if (bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else null
    }

    private fun handleOptions(output: OutputStream) {
        val headers = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
    }

    private fun serveWebDashboard(output: OutputStream) {
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Kivo Local File Share</title>
                <style>
                    :root {
                        --bg-color: #0A0A0C;
                        --card-bg: #141418;
                        --border-color: #24242C;
                        --accent-color: #14FFC2;
                        --text-primary: #FFFFFF;
                        --text-secondary: #9A9AB0;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
                    body { background: var(--bg-color); color: var(--text-primary); padding: 20px; display: flex; justify-content: center; }
                    .container { max-width: 600px; width: 100%; }
                    .header { text-align: center; margin-bottom: 24px; padding: 20px; background: var(--card-bg); border-radius: 18px; border: 1px solid var(--border-color); }
                    .header h1 { font-size: 22px; color: var(--accent-color); margin-bottom: 6px; }
                    .header p { font-size: 13px; color: var(--text-secondary); }
                    .section { background: var(--card-bg); border-radius: 18px; border: 1px solid var(--border-color); padding: 20px; margin-bottom: 20px; }
                    .section-title { font-size: 15px; font-weight: 600; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; }
                    .file-item { display: flex; align-items: center; justify-content: space-between; padding: 12px; background: #0A0A0C; border-radius: 12px; margin-bottom: 10px; border: 1px solid #1E1E26; }
                    .file-info { display: flex; flex-direction: column; gap: 4px; overflow: hidden; }
                    .file-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 320px; }
                    .file-size { font-size: 12px; color: var(--text-secondary); }
                    .btn { background: var(--accent-color); color: #000; font-weight: 700; padding: 8px 16px; border-radius: 10px; text-decoration: none; font-size: 13px; border: none; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
                    .btn:hover { opacity: 0.9; }
                    .drop-zone { border: 2px dashed #2E2E3C; border-radius: 14px; padding: 30px 20px; text-align: center; cursor: pointer; transition: 0.2s; background: #0F0F13; }
                    .drop-zone:hover { border-color: var(--accent-color); background: #121816; }
                    .progress-bar-bg { width: 100%; height: 8px; background: #22222E; border-radius: 4px; overflow: hidden; margin-top: 10px; }
                    .progress-bar-fill { height: 100%; background: var(--accent-color); width: 0%; transition: width 0.1s; }
                    #status-text { font-size: 12px; color: var(--text-secondary); margin-top: 8px; text-align: center; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>⚡ Kivo Local File Share</h1>
                        <p>Connected to <strong>$deviceName</strong> over Wi-Fi</p>
                    </div>

                    <div class="section">
                        <div class="section-title">
                            <span>📥 Shared Files from Phone</span>
                            <button class="btn" style="background:#242430; color:#fff;" onclick="loadFiles()">Refresh</button>
                        </div>
                        <div id="file-list">Loading shared files...</div>
                    </div>

                    <div class="section">
                        <div class="section-title">📤 Upload Files to Phone</div>
                        <div class="drop-zone" onclick="document.getElementById('file-input').click()">
                            <p style="font-size:14px; font-weight:600; margin-bottom:4px;">Click or Drag & Drop files here</p>
                            <p style="font-size:12px; color:var(--text-secondary);">Direct zero-speed-limit transfer to phone</p>
                            <input type="file" id="file-input" style="display:none" onchange="uploadFiles(this.files)" multiple>
                        </div>
                        <div class="progress-bar-bg" id="prog-bg" style="display:none;">
                            <div class="progress-bar-fill" id="prog-fill"></div>
                        </div>
                        <div id="status-text"></div>
                    </div>
                </div>

                <script>
                    function loadFiles() {
                        fetch('/api/files')
                            .then(res => res.json())
                            .then(files => {
                                const container = document.getElementById('file-list');
                                if (!files || files.length === 0) {
                                    container.innerHTML = '<p style="font-size:13px; color:var(--text-secondary); text-align:center; padding:10px;">No files currently shared from phone.</p>';
                                    return;
                                }
                                container.innerHTML = files.map(f => `
                                    <div class="file-item">
                                        <div class="file-info">
                                            <span class="file-name">${'$'}{f.name}</span>
                                            <span class="file-size">${'$'}{f.sizeFormatted}</span>
                                        </div>
                                        <a href="/download?id=${'$'}{f.id}" class="btn">Download</a>
                                    </div>
                                `).join('');
                            })
                            .catch(err => {
                                document.getElementById('file-list').innerHTML = '<p style="color:#FF5252; font-size:13px;">Error loading files.</p>';
                            });
                    }

                    function uploadFiles(files) {
                        if (!files || files.length === 0) return;
                        const file = files[0];
                        const xhr = new XMLHttpRequest();
                        const formData = new FormData();
                        formData.append("file", file);

                        document.getElementById('prog-bg').style.display = 'block';
                        const fill = document.getElementById('prog-fill');
                        const status = document.getElementById('status-text');

                        const startTime = Date.now();

                        xhr.upload.onprogress = function(e) {
                            if (e.lengthComputable) {
                                const percent = Math.round((e.loaded / e.total) * 100);
                                fill.style.width = percent + '%';
                                const elapsedSec = (Date.now() - startTime) / 1000;
                                const speedMB = (e.loaded / (1024 * 1024) / Math.max(0.1, elapsedSec)).toFixed(1);
                                status.innerText = `Uploading ${'$'}{file.name} - ${'$'}{percent}% (${'$'}{speedMB} MB/s)`;
                            }
                        };

                        xhr.onload = function() {
                            if (xhr.status === 200) {
                                status.innerText = `✅ Successfully uploaded ${'$'}{file.name} to phone!`;
                                fill.style.width = '100%';
                                setTimeout(() => {
                                    document.getElementById('prog-bg').style.display = 'none';
                                    fill.style.width = '0%';
                                }, 3000);
                            } else {
                                status.innerText = `❌ Upload failed: ${'$'}{xhr.statusText}`;
                            }
                        };

                        xhr.onerror = function() {
                            status.innerText = `❌ Upload connection error.`;
                        };

                        xhr.open("POST", "/upload", true);
                        xhr.send(formData);
                    }

                    loadFiles();
                </script>
            </body>
            </html>
        """.trimIndent()

        sendResponse(output, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun serveFileListJson(output: OutputStream) {
        val filesList = sharedFilesMap.values.joinToString(",", "[", "]") { f ->
            """{"id":"${f.id}","name":"${escapeJson(f.name)}","sizeBytes":${f.sizeBytes},"sizeFormatted":"${f.sizeFormatted}","mimeType":"${f.mimeType}"}"""
        }
        sendResponse(output, "200 OK", "application/json", filesList)
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }

    // REQ 1, 2, 3, 5, 6: Streaming File Download with Range Resuming & On-the-fly SHA-256
    private fun handleFileDownload(fileId: String?, headers: Map<String, String>, output: OutputStream, clientIp: String) {
        if (fileId.isNullOrEmpty() || !sharedFilesMap.containsKey(fileId)) {
            sendResponse(output, "404 Not Found", "text/plain", "File not found.")
            return
        }

        val item = sharedFilesMap[fileId]!!
        val fileInputStream: InputStream? = try {
            if (item.uri != null) {
                context.contentResolver.openInputStream(item.uri)
            } else if (item.localFilePath != null) {
                FileInputStream(File(item.localFilePath))
            } else null
        } catch (e: Exception) {
            null
        }

        if (fileInputStream == null) {
            sendResponse(output, "404 Not Found", "text/plain", "Cannot open file stream.")
            return
        }

        val transferId = UUID.randomUUID().toString()
        var transferItem = TransferItem(
            id = transferId,
            fileName = item.name,
            totalBytes = item.sizeBytes,
            direction = TransferDirection.DOWNLOAD,
            clientIp = clientIp,
            status = TransferStatus.IN_PROGRESS
        )
        activeTransfers[transferId] = transferItem
        serverListener.onTransferProgress(transferItem)

        try {
            val rangeHeader = headers["range"]
            var startByte = 0L
            var endByte = item.sizeBytes - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val ranges = rangeHeader.substring(6).split("-")
                startByte = ranges[0].toLongOrNull() ?: 0L
                if (ranges.size > 1 && ranges[1].isNotEmpty()) {
                    endByte = ranges[1].toLongOrNull() ?: endByte
                }
            }

            val contentLength = endByte - startByte + 1
            val statusHeader = if (rangeHeader != null) "206 Partial Content" else "200 OK"

            val headerText = StringBuilder()
            headerText.append("HTTP/1.1 $statusHeader\r\n")
            headerText.append("Content-Type: ${item.mimeType}\r\n")
            headerText.append("Content-Length: $contentLength\r\n")
            headerText.append("Content-Disposition: attachment; filename=\"${item.name.replace("\"", "")}\"\r\n")
            headerText.append("Accept-Ranges: bytes\r\n")
            headerText.append("Access-Control-Allow-Origin: *\r\n")
            if (rangeHeader != null) {
                headerText.append("Content-Range: bytes $startByte-$endByte/${item.sizeBytes}\r\n")
            }
            headerText.append("Connection: close\r\n\r\n")

            output.write(headerText.toString().toByteArray(Charsets.UTF_8))

            if (startByte > 0) {
                fileInputStream.skip(startByte)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024) // 128KB buffer
            var bytesRead: Int
            var totalSent = 0L
            var lastSpeedCalcTime = System.currentTimeMillis()
            var bytesInWindow = 0L

            while (isRunning && totalSent < contentLength) {
                val maxToRead = Math.min(buffer.size.toLong(), contentLength - totalSent).toInt()
                bytesRead = fileInputStream.read(buffer, 0, maxToRead)
                if (bytesRead == -1) break

                output.write(buffer, 0, bytesRead)
                digest.update(buffer, 0, bytesRead)
                totalSent += bytesRead
                bytesInWindow += bytesRead

                val now = System.currentTimeMillis()
                val delta = now - lastSpeedCalcTime
                if (delta >= 500) {
                    val speed = (bytesInWindow * 1000) / delta
                    val remaining = item.sizeBytes - (startByte + totalSent)
                    val eta = if (speed > 0) remaining / speed else 0L

                    lastSpeedCalcTime = now
                    bytesInWindow = 0

                    transferItem = transferItem.copy(
                        bytesTransferred = startByte + totalSent,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                    activeTransfers[transferId] = transferItem
                    serverListener.onTransferProgress(transferItem)
                }
            }

            val checksum = bytesToHex(digest.digest())

            transferItem = transferItem.copy(
                bytesTransferred = item.sizeBytes,
                status = TransferStatus.COMPLETED,
                speedBytesPerSec = 0,
                etaSeconds = 0,
                checksumSha256 = checksum
            )
            activeTransfers[transferId] = transferItem
            serverListener.onTransferCompleted(transferItem)
        } catch (e: Exception) {
            transferItem = transferItem.copy(
                status = TransferStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Client disconnected"
            )
            activeTransfers[transferId] = transferItem
            serverListener.onTransferProgress(transferItem)
        } finally {
            try { fileInputStream.close() } catch (e: Exception) {}
        }
    }

    // REQ 1, 2, 3, 5, 6: Direct Binary Stream Upload (App-to-App) with Resume & Verification
    private fun handleStreamUpload(input: InputStream, output: OutputStream, headers: Map<String, String>, clientIp: String) {
        val rawFileName = headers["x-file-name"] ?: "file_${System.currentTimeMillis()}.bin"
        val fileName = try { URLDecoder.decode(rawFileName, "UTF-8") } catch (e: Exception) { rawFileName }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: headers["x-file-size"]?.toLongOrNull() ?: 0L
        val expectedSha256 = headers["x-checksum-sha256"]
        val rangeHeader = headers["range"]

        var startByte = 0L
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            startByte = rangeHeader.substring(6).substringBefore("-").toLongOrNull() ?: 0L
        }

        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val kivoDir = File(downloadsDir, "KivoShare")
        if (!kivoDir.exists()) kivoDir.mkdirs()

        val tempFile = File(kivoDir, "$fileName.tmp")
        val transferId = UUID.randomUUID().toString()

        var transferItem = TransferItem(
            id = transferId,
            fileName = fileName,
            totalBytes = if (contentLength > 0) contentLength + startByte else 0L,
            bytesTransferred = startByte,
            direction = TransferDirection.UPLOAD,
            clientIp = clientIp,
            status = TransferStatus.IN_PROGRESS
        )
        activeTransfers[transferId] = transferItem
        serverListener.onTransferProgress(transferItem)

        try {
            val fileOut = FileOutputStream(tempFile, startByte > 0)
            val digest = MessageDigest.getInstance("SHA-256")

            // If resuming, digest existing bytes first if possible
            if (startByte > 0 && tempFile.exists()) {
                FileInputStream(tempFile).use { fis ->
                    val b = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(b).also { read = it } != -1) {
                        digest.update(b, 0, read)
                    }
                }
            }

            val buffer = ByteArray(128 * 1024)
            var totalRead = startByte
            var bytesRead: Int
            var lastSpeedTime = System.currentTimeMillis()
            var windowBytes = 0L

            while (isRunning && (contentLength <= 0 || totalRead - startByte < contentLength)) {
                val toRead = if (contentLength > 0) Math.min(buffer.size.toLong(), contentLength - (totalRead - startByte)).toInt() else buffer.size
                bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                fileOut.write(buffer, 0, bytesRead)
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                windowBytes += bytesRead

                val now = System.currentTimeMillis()
                val delta = now - lastSpeedTime
                if (delta >= 500) {
                    val speed = (windowBytes * 1000) / delta
                    val remaining = if (contentLength > 0) (contentLength + startByte) - totalRead else 0L
                    val eta = if (speed > 0) remaining / speed else 0L

                    lastSpeedTime = now
                    windowBytes = 0

                    transferItem = transferItem.copy(
                        bytesTransferred = totalRead,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                    activeTransfers[transferId] = transferItem
                    serverListener.onTransferProgress(transferItem)
                }
            }
            fileOut.flush()
            fileOut.close()

            // REQ 6: Integrity Verification Step
            transferItem = transferItem.copy(status = TransferStatus.VERIFYING, speedBytesPerSec = 0)
            activeTransfers[transferId] = transferItem
            serverListener.onTransferProgress(transferItem)

            val actualSha256 = bytesToHex(digest.digest())
            val finalFile = File(kivoDir, fileName)

            if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
                transferItem = transferItem.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = "SHA-256 Checksum verification failed!"
                )
                activeTransfers[transferId] = transferItem
                serverListener.onTransferProgress(transferItem)
                sendResponse(output, "400 Bad Request", "application/json", """{"status":"error","message":"Checksum mismatch"}""")
                return
            }

            if (finalFile.exists()) finalFile.delete()
            tempFile.renameTo(finalFile)

            transferItem = transferItem.copy(
                fileName = finalFile.name,
                bytesTransferred = finalFile.length(),
                totalBytes = finalFile.length(),
                status = TransferStatus.COMPLETED,
                speedBytesPerSec = 0,
                etaSeconds = 0,
                checksumSha256 = actualSha256
            )
            activeTransfers[transferId] = transferItem
            serverListener.onTransferCompleted(transferItem)

            sendResponse(output, "200 OK", "application/json", """{"status":"success","fileName":"${finalFile.name}","sha256":"$actualSha256"}""")
        } catch (e: Exception) {
            transferItem = transferItem.copy(status = TransferStatus.FAILED, errorMessage = e.localizedMessage ?: "Upload stream failed")
            activeTransfers[transferId] = transferItem
            serverListener.onTransferProgress(transferItem)
            sendResponse(output, "500 Server Error", "text/plain", "Upload error: ${e.message}")
        }
    }

    // Web Browser Multipart Form Upload
    private fun handleMultipartUpload(input: InputStream, output: OutputStream, headers: Map<String, String>, clientIp: String) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val kivoDir = File(downloadsDir, "KivoShare")
        if (!kivoDir.exists()) kivoDir.mkdirs()

        val transferId = UUID.randomUUID().toString()
        val tempFile = File(kivoDir, "web_upload_${System.currentTimeMillis()}.tmp")

        var transferItem = TransferItem(
            id = transferId,
            fileName = "Web Upload ($clientIp)",
            totalBytes = contentLength,
            direction = TransferDirection.UPLOAD,
            clientIp = clientIp,
            status = TransferStatus.IN_PROGRESS
        )
        activeTransfers[transferId] = transferItem
        serverListener.onTransferProgress(transferItem)

        try {
            val fileOut = FileOutputStream(tempFile)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var totalRead = 0L
            var bytesRead: Int
            var lastSpeedTime = System.currentTimeMillis()
            var windowBytes = 0L

            while (isRunning && (contentLength <= 0 || totalRead < contentLength)) {
                val toRead = if (contentLength > 0) Math.min(buffer.size.toLong(), contentLength - totalRead).toInt() else buffer.size
                bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                fileOut.write(buffer, 0, bytesRead)
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                windowBytes += bytesRead

                val now = System.currentTimeMillis()
                val delta = now - lastSpeedTime
                if (delta >= 500) {
                    val speed = (windowBytes * 1000) / delta
                    val remaining = contentLength - totalRead
                    val eta = if (speed > 0) remaining / speed else 0L

                    lastSpeedTime = now
                    windowBytes = 0

                    transferItem = transferItem.copy(
                        bytesTransferred = totalRead,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                    activeTransfers[transferId] = transferItem
                    serverListener.onTransferProgress(transferItem)
                }
            }
            fileOut.flush()
            fileOut.close()

            val actualSha256 = bytesToHex(digest.digest())
            val destFile = File(kivoDir, "KivoUpload_${System.currentTimeMillis()}.bin")
            tempFile.renameTo(destFile)

            transferItem = transferItem.copy(
                fileName = destFile.name,
                bytesTransferred = destFile.length(),
                totalBytes = destFile.length(),
                status = TransferStatus.COMPLETED,
                speedBytesPerSec = 0,
                checksumSha256 = actualSha256
            )
            activeTransfers[transferId] = transferItem
            serverListener.onTransferCompleted(transferItem)

            sendResponse(output, "200 OK", "application/json", """{"status":"success","fileName":"${destFile.name}"}""")
        } catch (e: Exception) {
            transferItem = transferItem.copy(status = TransferStatus.FAILED, errorMessage = e.localizedMessage)
            activeTransfers[transferId] = transferItem
            serverListener.onTransferProgress(transferItem)
            sendResponse(output, "500 Server Error", "text/plain", "Upload failed: ${e.message}")
        }
    }

    private fun sendResponse(output: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
