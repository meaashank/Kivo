package com.example.browser.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class FileSharingManager(private val context: Context) : LocalFileServer.ServerListener {

    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var fileServer: LocalFileServer? = null
    private var nsdHelper: NsdHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
            // Register already selected shared files
            _sharedFiles.value.forEach { item ->
                sharedFilesMap[item.id] = item
            }
            start(ip)
        }

        // Start mDNS/NSD service discovery & registration
        nsdHelper = NsdHelper(context).apply {
            registerService(port)
            discoverServices(object : NsdHelper.DiscoveryCallback {
                override fun onDeviceDiscovered(device: DiscoveredDevice) {
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.name == device.name }
                    current.add(device)
                    _discoveredDevices.value = current
                }

                override fun onDeviceLost(deviceName: String) {
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.name == deviceName }
                    _discoveredDevices.value = current
                }
            })
        }

        if (_keepScreenAwake.value) {
            acquireWakeLock()
        }
    }

    fun stopSharingServer() {
        fileServer?.stop()
        fileServer = null
        nsdHelper?.stop()
        nsdHelper = null
        releaseWakeLock()
        _isServerRunning.value = false
        _serverUrl.value = ""
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
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max
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

    override fun onTransferCompleted(item: TransferItem) {
        onTransferProgress(item)
    }
}
