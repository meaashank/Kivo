package com.example.browser.download

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.browser.data.BrowserDatabase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsSheet(
    onDismiss: () -> Unit,
    onChangeFolderClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { DownloadPreferences(context) }

    var folderPath by remember { mutableStateOf(prefs.downloadFolder) }
    var askWhereToSave by remember { mutableStateOf(prefs.askWhereToSave) }
    var downloadWifiOnly by remember { mutableStateOf(prefs.downloadWifiOnly) }
    var maxSimultaneous by remember { mutableFloatStateOf(prefs.maxSimultaneousDownloads.toFloat()) }
    var autoResume by remember { mutableStateOf(prefs.autoResume) }
    var smartFileNaming by remember { mutableStateOf(prefs.smartFileNaming) }
    var autoDeleteFailed by remember { mutableStateOf(prefs.autoDeleteFailed) }
    var openFileAfterDownload by remember { mutableStateOf(prefs.openFileAfterDownload) }

    var showClearHistoryDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121216),
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Download Settings",
                        tint = Color(0xFF14FFC2)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Download Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Download Folder Section
            Text(
                text = "STORAGE LOCATION",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF14FFC2),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onChangeFolderClick() },
                color = Color(0xFF1E1E24)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = Color(0xFF14FFC2),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download Directory",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = folderPath,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Button(
                        onClick = onChangeFolderClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF14FFC2).copy(alpha = 0.15f),
                            contentColor = Color(0xFF14FFC2)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Change", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Download Options
            Text(
                text = "PREFERENCES & AUTOMATION",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF14FFC2),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E24))
            ) {
                SettingSwitchRow(
                    title = "Ask where to save each file",
                    subtitle = "Prompt for filename & location before downloading",
                    checked = askWhereToSave,
                    onCheckedChange = {
                        askWhereToSave = it
                        prefs.askWhereToSave = it
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "Download over Wi-Fi only",
                    subtitle = "Pause active tasks when using cellular data",
                    checked = downloadWifiOnly,
                    onCheckedChange = {
                        downloadWifiOnly = it
                        prefs.downloadWifiOnly = it
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "Auto-resume downloads",
                    subtitle = "Automatically retry interrupted tasks when online",
                    checked = autoResume,
                    onCheckedChange = {
                        autoResume = it
                        prefs.autoResume = it
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "Smart file naming",
                    subtitle = "Automatically clean and format downloaded file names",
                    checked = smartFileNaming,
                    onCheckedChange = {
                        smartFileNaming = it
                        prefs.smartFileNaming = it
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "Open file after download",
                    subtitle = "Automatically launch completed files",
                    checked = openFileAfterDownload,
                    onCheckedChange = {
                        openFileAfterDownload = it
                        prefs.openFileAfterDownload = it
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "Auto-delete failed downloads",
                    subtitle = "Remove incomplete tasks on failure",
                    checked = autoDeleteFailed,
                    onCheckedChange = {
                        autoDeleteFailed = it
                        prefs.autoDeleteFailed = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Limits
            Text(
                text = "CONCURRENCY LIMITS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF14FFC2),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = Color(0xFF1E1E24)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Max Simultaneous Downloads",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "${maxSimultaneous.toInt()} tasks",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF14FFC2)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = maxSimultaneous,
                        onValueChange = {
                            maxSimultaneous = it
                            prefs.maxSimultaneousDownloads = it.toInt()
                        },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF14FFC2),
                            activeTrackColor = Color(0xFF14FFC2),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Clear history button
            OutlinedButton(
                onClick = { showClearHistoryDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Outlined.DeleteSweep, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Download History", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            containerColor = Color(0xFF1E1E24),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text("Clear Download History") },
            text = { Text("Are you sure you want to clear all download records? Downloaded files on storage will not be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        scope.launch {
                            val db = BrowserDatabase.getDatabase(context)
                            db.browserDao().clearDownloads()
                        }
                    }
                ) {
                    Text("Clear", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF121216),
                checkedTrackColor = Color(0xFF14FFC2),
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}
