package com.example.browser.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.browser.data.BrowserDatabase
import com.example.browser.data.DownloadItem
import com.example.browser.data.DownloadStatus
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerSheet(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestStoragePermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { BrowserDatabase.getDatabase(context) }
    val dao = remember { db.browserDao() }
    val engine = remember { DownloadEngine.getInstance(context) }

    val downloads by dao.getAllDownloads().collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: All, 1: Active, 2: Completed
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var renameTargetItem by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteTargetItem by remember { mutableStateOf<DownloadItem?>(null) }

    // Filter logic
    val filteredDownloads = downloads.filter { item ->
        val matchesTab = when (selectedTab) {
            1 -> item.statusEnum == DownloadStatus.RUNNING || item.statusEnum == DownloadStatus.PAUSED || item.statusEnum == DownloadStatus.PENDING
            2 -> item.statusEnum == DownloadStatus.COMPLETED
            else -> true
        }
        val matchesSearch = searchQuery.isBlank() ||
                item.fileName.contains(searchQuery, ignoreCase = true) ||
                item.url.contains(searchQuery, ignoreCase = true)
        matchesTab && matchesSearch
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121216),
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            // Header / Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = { Text("Search downloads...", color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF14FFC2),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                searchQuery = ""
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White)
                            }
                        }
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            tint = Color(0xFF14FFC2),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Downloads",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Color.White)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Download Settings", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }
            }

            // Tabs Bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF14FFC2),
                divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.1f)) }
            ) {
                val activeCount = downloads.count { it.statusEnum == DownloadStatus.RUNNING || it.statusEnum == DownloadStatus.PAUSED }
                val completedCount = downloads.count { it.statusEnum == DownloadStatus.COMPLETED }

                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("All (${downloads.size})", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Active ($activeCount)", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Completed ($completedCount)", fontWeight = FontWeight.Bold) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Download List
            if (filteredDownloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.DownloadDone,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No downloads matching '$searchQuery'" else "No downloads found",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredDownloads, key = { it.id }) { item ->
                        DownloadItemCard(
                            item = item,
                            onPause = { engine.pauseDownload(item.id) },
                            onResume = { engine.resumeDownload(item.id) },
                            onCancel = { engine.cancelDownload(item.id) },
                            onRetry = { engine.resumeDownload(item.id) },
                            onOpen = { openDownloadedFile(context, item) },
                            onShare = { shareDownloadedFile(context, item) },
                            onCopyPath = {
                                copyToClipboard(context, "File Path", item.localPath)
                            },
                            onRenameClick = { renameTargetItem = item },
                            onDeleteClick = { deleteTargetItem = item }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    renameTargetItem?.let { target ->
        var newName by remember { mutableStateOf(target.fileName) }
        AlertDialog(
            onDismissRequest = { renameTargetItem = null },
            containerColor = Color(0xFF1E1E24),
            titleContentColor = Color.White,
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF14FFC2),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty() && trimmed != target.fileName) {
                            scope.launch {
                                try {
                                    val oldFile = File(target.localPath)
                                    if (oldFile.exists()) {
                                        val newFile = File(oldFile.parentFile, trimmed)
                                        if (oldFile.renameTo(newFile)) {
                                            dao.updateDownload(target.copy(fileName = trimmed, localPath = newFile.absolutePath))
                                            Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Rename failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        renameTargetItem = null
                    }
                ) {
                    Text("Rename", color = Color(0xFF14FFC2), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetItem = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    // Delete Confirmation Dialog
    deleteTargetItem?.let { target ->
        var deleteDiskFile by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { deleteTargetItem = null },
            containerColor = Color(0xFF1E1E24),
            titleContentColor = Color.White,
            title = { Text("Delete Download") },
            text = {
                Column {
                    Text("Remove '${target.fileName}' from download history?", color = Color.White.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteDiskFile = !deleteDiskFile }
                    ) {
                        Checkbox(
                            checked = deleteDiskFile,
                            onCheckedChange = { deleteDiskFile = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF5252))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Also delete file from storage", color = Color.White, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        engine.deleteDownloadRecord(target.id, deleteDiskFile)
                        deleteTargetItem = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetItem = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                if (item.statusEnum == DownloadStatus.COMPLETED) {
                    onOpen()
                }
            },
        color = Color(0xFF1E1E24)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(getFileTypeBgColor(item.mimeType, item.fileName)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getFileTypeIcon(item.mimeType, item.fileName),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(status = item.statusEnum)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.sizeFormatted,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        if (item.statusEnum == DownloadStatus.RUNNING) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• ${item.speedFormatted}",
                                fontSize = 12.sp,
                                color = Color(0xFF14FFC2),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Action buttons or overflow menu
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.statusEnum) {
                        DownloadStatus.RUNNING -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Outlined.PauseCircle, contentDescription = "Pause", tint = Color(0xFFFF9800))
                            }
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Outlined.Cancel, contentDescription = "Cancel", tint = Color(0xFFFF5252))
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Outlined.PlayCircle, contentDescription = "Resume", tint = Color(0xFF14FFC2))
                            }
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Outlined.Cancel, contentDescription = "Cancel", tint = Color(0xFFFF5252))
                            }
                        }
                        DownloadStatus.FAILED -> {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Retry", tint = Color(0xFF14FFC2))
                            }
                            IconButton(onClick = onDeleteClick) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            IconButton(onClick = onShare) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White.copy(alpha = 0.8f))
                            }
                            Box {
                                IconButton(onClick = { expandedMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White.copy(alpha = 0.8f))
                                }
                                DropdownMenu(
                                    expanded = expandedMenu,
                                    onDismissRequest = { expandedMenu = false },
                                    modifier = Modifier.background(Color(0xFF282832))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Open File", color = Color.White) },
                                        onClick = {
                                            expandedMenu = false
                                            onOpen()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = Color.White) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share", color = Color.White) },
                                        onClick = {
                                            expandedMenu = false
                                            onShare()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy Path", color = Color.White) },
                                        onClick = {
                                            expandedMenu = false
                                            onCopyPath()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename", color = Color.White) },
                                        onClick = {
                                            expandedMenu = false
                                            onRenameClick()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color(0xFFFF5252)) },
                                        onClick = {
                                            expandedMenu = false
                                            onDeleteClick()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF5252)) }
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            // Progress bar for active / paused
            if (item.statusEnum == DownloadStatus.RUNNING || item.statusEnum == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = if (item.statusEnum == DownloadStatus.PAUSED) Color(0xFFFF9800) else Color(0xFF14FFC2),
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(item.progressPercent * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = item.etaFormatted,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (item.statusEnum == DownloadStatus.FAILED && !item.errorMessage.isNull_or_empty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Error: ${item.errorMessage}",
                    fontSize = 12.sp,
                    color = Color(0xFFFF5252),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus) {
    val (label, bg, fg) = when (status) {
        DownloadStatus.RUNNING -> Triple("Downloading", Color(0xFF14FFC2).copy(alpha = 0.15f), Color(0xFF14FFC2))
        DownloadStatus.PAUSED -> Triple("Paused", Color(0xFFFF9800).copy(alpha = 0.15f), Color(0xFFFF9800))
        DownloadStatus.COMPLETED -> Triple("Completed", Color(0xFF4CAF50).copy(alpha = 0.15f), Color(0xFF4CAF50))
        DownloadStatus.FAILED -> Triple("Failed", Color(0xFFFF5252).copy(alpha = 0.15f), Color(0xFFFF5252))
        DownloadStatus.CANCELLED -> Triple("Cancelled", Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.6f))
        DownloadStatus.PENDING -> Triple("Queued", Color(0xFF2196F3).copy(alpha = 0.15f), Color(0xFF2196F3))
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun getFileTypeIcon(mimeType: String, fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "gif", "webp") -> Icons.Outlined.Image
        mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi") -> Icons.Outlined.VideoFile
        mimeType.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "flac") -> Icons.Outlined.AudioFile
        mimeType == "application/pdf" || ext == "pdf" -> Icons.Outlined.PictureInPicture
        ext in listOf("zip", "rar", "7z", "tar", "gz") -> Icons.Outlined.FolderZip
        ext == "apk" -> Icons.Outlined.Android
        ext in listOf("txt", "json", "html", "js", "kt", "xml") -> Icons.Outlined.Code
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun getFileTypeBgColor(mimeType: String, fileName: String): Color {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "gif", "webp") -> Color(0xFF9C27B0)
        mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi") -> Color(0xFFE91E63)
        mimeType.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "flac") -> Color(0xFF2196F3)
        mimeType == "application/pdf" || ext == "pdf" -> Color(0xFFFF5722)
        ext in listOf("zip", "rar", "7z", "tar", "gz") -> Color(0xFFFF9800)
        ext == "apk" -> Color(0xFF4CAF50)
        else -> Color(0xFF607D8B)
    }
}

private fun openDownloadedFile(context: Context, item: DownloadItem) {
    try {
        val file = File(item.localPath)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist on disk", Toast.LENGTH_SHORT).show()
            return
        }
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
        Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareDownloadedFile(context: Context, item: DownloadItem) {
    try {
        val file = File(item.localPath)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist on disk", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share File"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun String?.isNull_or_empty(): Boolean {
    return this == null || this.trim().isEmpty()
}
