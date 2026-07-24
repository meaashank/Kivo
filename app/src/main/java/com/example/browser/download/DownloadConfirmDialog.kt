package com.example.browser.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.example.browser.data.formatFileSize
import java.io.File
import java.net.URI

@Composable
fun DownloadConfirmDialog(
    request: DownloadRequest,
    onDismiss: () -> Unit,
    onChangeFolderClick: () -> Unit,
    onStartDownload: (
        fileName: String,
        saveDir: String,
        openAfterDownload: Boolean,
        useExternalDownloader: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { DownloadPreferences(context) }

    var fileName by remember {
        mutableStateOf(
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        )
    }
    var currentFolder by remember { mutableStateOf(prefs.downloadFolder) }
    var openAfterDownload by remember { mutableStateOf(prefs.openFileAfterDownload) }
    var useExternalDownloader by remember { mutableStateOf(false) }
    var askEveryTime by remember { mutableStateOf(prefs.askWhereToSave) }

    val domainName = remember(request.url) {
        try {
            val uri = URI(request.url)
            uri.host?.removePrefix("www.") ?: request.url
        } catch (e: Exception) {
            request.url
        }
    }

    val readableSize = remember(request.contentLength) {
        if (request.contentLength > 0) formatFileSize(request.contentLength) else "Unknown Size"
    }

    val readableFileType = remember(request.mimeType, fileName) {
        getHumanReadableFileType(request.mimeType, fileName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF18181E),
        titleContentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF14FFC2).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getFileTypeIcon(request.mimeType, fileName),
                            contentDescription = null,
                            tint = Color(0xFF14FFC2),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Download File",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = domainName,
                            fontSize = 12.sp,
                            color = Color(0xFF14FFC2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.6f))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // File Name Input Card
                Column {
                    Text(
                        text = "FILE NAME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF14FFC2),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF22222A),
                            unfocusedContainerColor = Color(0xFF22222A)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                // Metadata Details Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF22222A),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Type:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            Text(readableFileType, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Size:", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            Text(readableSize, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14FFC2))
                        }
                    }
                }

                // Destination Folder Card
                Column {
                    Text(
                        text = "SAVE LOCATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onChangeFolderClick() },
                        color = Color(0xFF22222A)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = Color(0xFF14FFC2),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentFolder,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Change",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF14FFC2)
                            )
                        }
                    }
                }

                // Quick Action Bar (Copy Link, Share Link, External Open)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy Link",
                        modifier = Modifier.weight(1f)
                    ) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Download URL", request.url))
                        Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    }

                    QuickActionButton(
                        icon = Icons.Outlined.Share,
                        label = "Share Link",
                        modifier = Modifier.weight(1f)
                    ) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, request.url)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Download Link"))
                    }

                    QuickActionButton(
                        icon = Icons.Outlined.OpenInNew,
                        label = "Open Link",
                        modifier = Modifier.weight(1f)
                    ) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app found to open URL", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Advanced Controls Toggles
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openAfterDownload = !openAfterDownload }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = openAfterDownload,
                            onCheckedChange = { openAfterDownload = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF14FFC2))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open file after download", fontSize = 13.sp, color = Color.White)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useExternalDownloader = !useExternalDownloader }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = useExternalDownloader,
                            onCheckedChange = { useExternalDownloader = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF14FFC2))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download using external app", fontSize = 13.sp, color = Color.White)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                askEveryTime = !askEveryTime
                                prefs.askWhereToSave = askEveryTime
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = askEveryTime,
                            onCheckedChange = {
                                askEveryTime = it
                                prefs.askWhereToSave = it
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF14FFC2))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ask before every download", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = fileName.trim().ifEmpty {
                        URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
                    }
                    if (useExternalDownloader) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).apply {
                                setDataAndType(Uri.parse(request.url), request.mimeType)
                            }
                            context.startActivity(Intent.createChooser(intent, "Download with..."))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot launch external downloader: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    } else {
                        onStartDownload(finalName, currentFolder, openAfterDownload, false)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF14FFC2),
                    contentColor = Color(0xFF0F0F14)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel", fontSize = 14.sp)
            }
        }
    )
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF22222A),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF14FFC2), modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = Color.White, maxLines = 1)
        }
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

private fun getHumanReadableFileType(mimeType: String, fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext == "apk" -> "Android App (APK)"
        ext == "pdf" -> "PDF Document"
        ext in listOf("zip", "rar", "7z", "tar", "gz") -> "Archive (${ext.uppercase()})"
        mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi") -> "Video File (${ext.uppercase()})"
        mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "gif", "webp") -> "Image File (${ext.uppercase()})"
        mimeType.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "flac") -> "Audio Track (${ext.uppercase()})"
        mimeType.contains("json") || mimeType.contains("xml") || ext in listOf("json", "xml", "html", "js", "txt") -> "Text/Code File"
        else -> mimeType.ifEmpty { "Binary File" }
    }
}
