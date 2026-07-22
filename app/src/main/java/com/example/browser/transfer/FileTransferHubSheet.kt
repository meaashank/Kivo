package com.example.browser.transfer

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferHubSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val manager = remember { FileSharingManager(context) }

    val isRunning by manager.isServerRunning.collectAsStateWithLifecycle()
    val serverUrl by manager.serverUrl.collectAsStateWithLifecycle()
    val localIp by manager.localIp.collectAsStateWithLifecycle()
    val serverPort by manager.serverPort.collectAsStateWithLifecycle()
    val wifiSsid by manager.wifiSsid.collectAsStateWithLifecycle()
    val sharedFiles by manager.sharedFiles.collectAsStateWithLifecycle()
    val activeTransfers by manager.activeTransfers.collectAsStateWithLifecycle()
    val discoveredDevices by manager.discoveredDevices.collectAsStateWithLifecycle()
    val keepAwake by manager.keepScreenAwake.collectAsStateWithLifecycle()
    val errorMessage by manager.errorMessage.collectAsStateWithLifecycle()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            manager.addSharedUri(uri)
        }
    }

    // Automatically start server on open
    DisposableEffect(Unit) {
        manager.startSharingServer()
        onDispose {
            manager.stopSharingServer()
        }
    }

    var selectedTab by remember { mutableStateOf(0) } // 0: Hosting Web, 1: Nearby LAN, 2: Active Transfers

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF08080A))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF14FFC2).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = Color(0xFF14FFC2),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Share Files Over Wi-Fi",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Fast, secure local sharing. No internet required.",
                                fontSize = 11.sp,
                                color = Color(0xFFA1A1AA)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF1C1C24), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Error alert if Wi-Fi disabled or IP missing
                errorMessage?.let { msg ->
                    Surface(
                        color = Color(0xFFFF5252).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF5252)
                            )
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Tab Selector (Web Host, Nearby LAN, Transfers)
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF14141A),
                    contentColor = Color(0xFF14FFC2),
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF14FFC2)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .padding(bottom = 16.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Web Host & QR", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Nearby Devices (${discoveredDevices.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Transfers (${activeTransfers.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }

                // Main Tab Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        0 -> WebHostTabContent(
                            isRunning = isRunning,
                            serverUrl = serverUrl,
                            localIp = localIp,
                            serverPort = serverPort,
                            wifiSsid = wifiSsid,
                            sharedFiles = sharedFiles,
                            keepAwake = keepAwake,
                            onPickFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                            onRemoveFile = { manager.removeSharedFile(it) },
                            onCopyUrl = { manager.copyServerUrlToClipboard() },
                            onShareUrl = { manager.shareServerUrlNative() },
                            onToggleKeepAwake = { manager.toggleKeepScreenAwake(it) },
                            onRestartServer = { manager.startSharingServer() }
                        )
                        1 -> NearbyDevicesTabContent(
                            discoveredDevices = discoveredDevices,
                            onConnectDevice = { device ->
                                try {
                                    uriHandler.openUri("http://${device.ipAddress}:${device.port}")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        )
                        2 -> TransfersTabContent(
                            transfers = activeTransfers
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebHostTabContent(
    isRunning: Boolean,
    serverUrl: String,
    localIp: String,
    serverPort: Int,
    wifiSsid: String,
    sharedFiles: List<SharedFileItem>,
    keepAwake: Boolean,
    onPickFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onCopyUrl: () -> Unit,
    onShareUrl: () -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onRestartServer: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Status & URL Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22222E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val pulseAnim = rememberInfiniteTransition(label = "pulse")
                        val alphaPulse by pulseAnim.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )

                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF14FFC2).copy(alpha = alphaPulse) else Color(0xFFFF5252))
                        )
                        Text(
                            text = if (isRunning) "🟢 READY TO SHARE OVER WI-FI" else "🔴 SERVER STOPPED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isRunning) Color(0xFF14FFC2) else Color(0xFFFF5252),
                            letterSpacing = 1.sp
                        )
                    }

                    if (isRunning && serverUrl.isNotEmpty()) {
                        Text(
                            text = "Enter this web address in any computer, tablet or phone on the same Wi-Fi:",
                            fontSize = 12.sp,
                            color = Color(0xFFA1A1AA),
                            textAlign = TextAlign.Center
                        )

                        // Highlighted Server Address Box
                        Surface(
                            color = Color(0xFF1C1C28),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3EA6FF).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = serverUrl,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color(0xFF3EA6FF),
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IconButton(
                                        onClick = onCopyUrl,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFF282838), RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy URL",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = onShareUrl,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFF282838), RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Share,
                                            contentDescription = "Share URL",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Generated QR Code
                        val qrBitmap = remember(serverUrl) {
                            QrCodeGenerator.generateQrBitmap(serverUrl, 380)
                        }

                        qrBitmap?.let { bitmap ->
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 6.dp)
                                    .size(170.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Scan QR Code to connect",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Network: $wifiSsid", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                            Text(text = "IP: $localIp", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                            Text(text = "Port: $serverPort", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                        }
                    } else {
                        Button(
                            onClick = onRestartServer,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14FFC2), contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Start File Share Server", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Shared Files Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22222E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shared Files (${sharedFiles.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Button(
                            onClick = onPickFiles,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF14FFC2).copy(alpha = 0.15f),
                                contentColor = Color(0xFF14FFC2)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Select Files", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (sharedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .background(Color(0xFF0D0D12), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF1C1C24), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No files selected yet. Click 'Select Files' to expose files for download.",
                                fontSize = 12.sp,
                                color = Color(0xFFA1A1AA),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sharedFiles.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0D0D12), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF1C1C24), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = Color(0xFF3EA6FF),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.sizeFormatted,
                                            fontSize = 11.sp,
                                            color = Color(0xFFA1A1AA)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveFile(item.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Remove",
                                            tint = Color(0xFFFF5252),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Toggles & Settings
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121218), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF22222E), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep Screen Awake", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text("Prevents phone sleep while file server is running", fontSize = 11.sp, color = Color(0xFFA1A1AA))
                }
                Switch(
                    checked = keepAwake,
                    onCheckedChange = onToggleKeepAwake,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF14FFC2), checkedTrackColor = Color(0xFF14FFC2).copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun NearbyDevicesTabContent(
    discoveredDevices: List<DiscoveredDevice>,
    onConnectDevice: (DiscoveredDevice) -> Unit
) {
    if (discoveredDevices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF14FFC2), modifier = Modifier.size(32.dp))
                Text("Searching for nearby devices on LAN...", color = Color.White, fontSize = 14.sp)
                Text("Ensure other phones/tablets have Kivo File Share open.", color = Color(0xFFA1A1AA), fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(discoveredDevices) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121218), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF22222E), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3EA6FF).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                tint = Color(0xFF3EA6FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(device.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            Text("${device.ipAddress}:${device.port}", fontSize = 12.sp, color = Color(0xFFA1A1AA))
                        }
                    }

                    Button(
                        onClick = { onConnectDevice(device) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14FFC2), contentColor = Color.Black),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransfersTabContent(
    transfers: List<TransferItem>
) {
    if (transfers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No active or recent transfers.", color = Color(0xFFA1A1AA), fontSize = 13.sp)
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(transfers) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22222E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (item.direction == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = if (item.direction == TransferDirection.UPLOAD) Color(0xFF14FFC2) else Color(0xFF3EA6FF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = item.fileName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = when (item.status) {
                                    TransferStatus.COMPLETED -> "COMPLETED"
                                    TransferStatus.IN_PROGRESS -> item.speedFormatted
                                    TransferStatus.FAILED -> "FAILED"
                                    else -> "PENDING"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = when (item.status) {
                                    TransferStatus.COMPLETED -> Color(0xFF14FFC2)
                                    TransferStatus.IN_PROGRESS -> Color(0xFF3EA6FF)
                                    else -> Color(0xFFFF5252)
                                }
                            )
                        }

                        LinearProgressIndicator(
                            progress = { item.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF14FFC2),
                            trackColor = Color(0xFF222230)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.sizeFormatted, fontSize = 11.sp, color = Color(0xFFA1A1AA))
                            Text("${(item.progressPercent * 100).toInt()}%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
