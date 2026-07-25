package com.example.browser.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

class FileSharingManager(private val context: Context) : LocalFileServer.ServerListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pruneJob: Job? = null

    private var fileServer: LocalFileServer? = null
    private var nsdHelper: NsdHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val myDeviceId: String = getOrCreateDeviceId()

    // State flows
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _localIp = MutableStateFlow("")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _wifiSsid = MutableStateFlow("")
    val wifiSsid: StateFlow<String> = _wifiSsid.asStateFlow()

    private val _sharedFiles = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFileItem>> = _sharedFiles.asStateFlow()

    private val _activeTransfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val activeTransfers: StateFlow<List<TransferItem>> = _activeTransfers.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _keepScreenAwake = MutableStateFlow(true)
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshNetworkState()
    }

    fun refreshNetworkState() {
        val ip = NetworkUtils.getLocalIpAddress(context)
        val ssid = NetworkUtils.getWifiSsid(context)
        _localIp.value = ip ?: ""
        _wifiSsid.value = ssid
    }

    fun startSharingServer() {
        refreshNetworkState()
        val ip = _localIp.value
        if (ip.isEmpty()) {
            _errorMessage.value = "No local Wi-Fi or Hotspot connection detected. Please connect to Wi-Fi."
            return
        }

        _errorMessage.value = null
        val port = NetworkUtils.findAvailablePort(8080)
        _serverPort.value = port

        fileServer = LocalFileServer(context, port, this).apply {
            _sharedFiles.value.forEach { item ->
                sharedFilesMap[item.id] = item
            }
            start(ip)
        }

        // Start mDNS / NSD service discovery
        nsdHelper = NsdHelper(context).apply {
            registerService(port, myDeviceId)
            discoverServices(myDeviceId, ip, port, object : NsdHelper.DiscoveryCallback {
                override fun onDeviceDiscovered(device: DiscoveredDevice) {
                    // REQ 9: Strictly filter out self
                    if (device.id == myDeviceId) return
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.id == device.id || (it.ipAddress == device.ipAddress && it.port == device.port) }
                    current.add(device)
                    _discoveredDevices.value = current
                }

                override fun onDeviceLost(deviceId: String) {
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.id == deviceId }
                    _discoveredDevices.value = current
                }
            })
        }

        startDevicePruner()

        if (_keepScreenAwake.value) {
            acquireWakeLock()
        }
    }

    fun stopSharingServer() {
        pruneJob?.cancel()
        fileServer?.stop()
        fileServer = null
        nsdHelper?.stop()
        nsdHelper = null
        releaseWakeLock()
        _isServerRunning.value = false
        _serverUrl.value = ""
    }

    // REQ 9: Automatically prune stale/offline devices in real time
    private fun startDevicePruner() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(4000)
                val now = System.currentTimeMillis()
                val current = _discoveredDevices.value.toMutableList()
                val removed = current.removeAll { now - it.lastSeenTimestamp > 12000 }
                if (removed) {
                    _discoveredDevices.value = current
                }
            }
        }
    }

    fun addSharedUri(uri: Uri) {
        val nameAndSize = getUriNameAndSize(uri)
        val item = SharedFileItem(
            id = UUID.randomUUID().toString(),
            name = nameAndSize.first,
            sizeBytes = nameAndSize.second,
            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
            uri = uri
        )
        val list = _sharedFiles.value.toMutableList()
        list.add(item)
        _sharedFiles.value = list

        fileServer?.sharedFilesMap?.put(item.id, item)
    }

    fun removeSharedFile(fileId: String) {
        val list = _sharedFiles.value.toMutableList()
        list.removeAll { it.id == fileId }
        _sharedFiles.value = list
        fileServer?.sharedFilesMap?.remove(fileId)
    }

    // REQ 1, 2, 3, 4, 5, 6, 7: Send files directly to remote nearby device
    fun sendFilesToDevice(targetDevice: DiscoveredDevice, itemsToSend: List<SharedFileItem> = _sharedFiles.value) {
        if (itemsToSend.isEmpty()) {
            Toast.makeText(context, "Please select files to send first!", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            itemsToSend.forEach { item ->
                val transferId = UUID.randomUUID().toString()
                var transferItem = TransferItem(
                    id = transferId,
                    fileName = item.name,
                    totalBytes = item.sizeBytes,
                    bytesTransferred = 0L,
                    direction = TransferDirection.UPLOAD,
                    clientIp = targetDevice.ipAddress,
                    status = TransferStatus.IN_PROGRESS
                )
                updateTransferItem(transferItem)

                var attempts = 0
                var success = false
                var bytesSentSoFar = 0L

                while (attempts < 3 && !success && isActive) {
                    attempts++
                    try {
                        val url = URL("http://${targetDevice.ipAddress}:${targetDevice.port}/api/upload-stream")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 30000
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setChunkedStreamingMode(128 * 1024)

                        val encodedName = URLEncoder.encode(item.name, "UTF-8")
                        conn.setRequestProperty("X-File-Name", encodedName)
                        conn.setRequestProperty("X-File-Size", item.sizeBytes.toString())
                        conn.setRequestProperty("Content-Type", item.mimeType)
                        if (bytesSentSoFar > 0) {
                            conn.setRequestProperty("Range", "bytes=$bytesSentSoFar-")
                        }

                        val inputStream = if (item.uri != null) {
                            context.contentResolver.openInputStream(item.uri)
                        } else if (item.localFilePath != null) {
                            FileInputStream(File(item.localFilePath))
                        } else null

                        if (inputStream == null) {
                            transferItem = transferItem.copy(status = TransferStatus.FAILED, errorMessage = "File unavailable")
                            updateTransferItem(transferItem)
                            break
                        }

                        if (bytesSentSoFar > 0) {
                            inputStream.skip(bytesSentSoFar)
                        }

                        val out = BufferedOutputStream(conn.outputStream)
                        val digest = MessageDigest.getInstance("SHA-256")

                        val buffer = ByteArray(128 * 1024)
                        var read: Int
                        var lastTime = System.currentTimeMillis()
                        var windowBytes = 0L

                        while (inputStream.read(buffer).also { read = it } != -1 && isActive) {
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesSentSoFar += read
                            windowBytes += read

                            val now = System.currentTimeMillis()
                            val delta = now - lastTime
                            if (delta >= 500) {
                                val speed = (windowBytes * 1000) / delta
                                val remaining = item.sizeBytes - bytesSentSoFar
                                val eta = if (speed > 0) remaining / speed else 0L

                                lastTime = now
                                windowBytes = 0

                                transferItem = transferItem.copy(
                                    bytesTransferred = bytesSentSoFar,
                                    speedBytesPerSec = speed,
                                    etaSeconds = eta
                                )
                                updateTransferItem(transferItem)
                            }
                        }
                        out.flush()
                        inputStream.close()

                        val responseCode = conn.responseCode
                        if (responseCode == 200) {
                            val sha256Hex = bytesToHex(digest.digest())
                            transferItem = transferItem.copy(
                                bytesTransferred = item.sizeBytes,
                                status = TransferStatus.COMPLETED,
                                speedBytesPerSec = 0,
                                etaSeconds = 0,
                                checksumSha256 = sha256Hex
                            )
                            updateTransferItem(transferItem)
                            success = true
                        } else {
                            throw IOException("Server error $responseCode")
                        }
                    } catch (e: Exception) {
                        if (attempts >= 3) {
                            transferItem = transferItem.copy(
                                status = TransferStatus.FAILED,
                                errorMessage = e.localizedMessage ?: "Transfer failed"
                            )
                            updateTransferItem(transferItem)
                        } else {
                            delay(1000) // retry delay
                        }
                    }
                }
            }
        }
    }

    private fun updateTransferItem(item: TransferItem) {
        scope.launch {
            val list = _activeTransfers.value.toMutableList()
            val index = list.indexOfFirst { it.id == item.id }
            if (index != -1) {
                list[index] = item
            } else {
                list.add(0, item)
            }
            _activeTransfers.value = list
        }
    }

    fun copyServerUrlToClipboard() {
        val url = _serverUrl.value
        if (url.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Kivo Share URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "URL copied: $url", Toast.LENGTH_SHORT).show()
    }

    fun shareServerUrlNative() {
        val url = _serverUrl.value
        if (url.isEmpty()) return
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Connect to Kivo File Share on local Wi-Fi:\n$url")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Kivo File Share Link")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun toggleKeepScreenAwake(enabled: Boolean) {
        _keepScreenAwake.value = enabled
        if (enabled && _isServerRunning.value) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "KivoBrowser:FileShareWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getUriNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "Shared_File_${System.currentTimeMillis()}"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(name, size)
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("kivo_transfer_prefs", Context.MODE_PRIVATE)
        val storedId = prefs.getString("device_uuid", null)
        if (storedId != null && storedId.isNotBlank()) {
            return storedId
        }
        val newId = UUID.randomUUID().toString().take(8)
        prefs.edit().putString("device_uuid", newId).apply()
        return newId
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    // Server Callbacks
    override fun onServerStarted(ipAddress: String, port: Int) {
        scope.launch {
            _isServerRunning.value = true
            _serverUrl.value = "http://$ipAddress:$port"
        }
    }

    override fun onServerStopped() {
        scope.launch {
            _isServerRunning.value = false
            _serverUrl.value = ""
        }
    }

    override fun onError(message: String) {
        scope.launch {
            _errorMessage.value = message
            _isServerRunning.value = false
        }
    }

    override fun onTransferProgress(item: TransferItem) {
        updateTransferItem(item)
    }

    override fun onTransferCompleted(item: TransferItem) {
        updateTransferItem(item)
    }
}
