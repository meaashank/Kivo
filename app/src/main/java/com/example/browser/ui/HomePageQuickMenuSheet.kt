package com.example.browser.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.models.BackgroundStyle
import com.example.browser.models.SearchBarPosition
import com.example.browser.models.ShortcutIconSize

@Composable
fun HomePageQuickMenuPopup(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onAddShortcutClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val homePageSettings by viewModel.homePageSettings.collectAsStateWithLifecycle()

    var showShortcutsDialog by remember { mutableStateOf(false) }
    var showNewsDialog by remember { mutableStateOf(false) }

    // Sub-pickers inside dialogs
    var showNewsSourcePicker by remember { mutableStateOf(false) }
    var showLangRegionPicker by remember { mutableStateOf(false) }

    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(x = 24, y = 100),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF2C2C2E), // Exact Soul Browser dark gray tone
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.width(210.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
            ) {
                // 1. Shortcuts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            showShortcutsDialog = true
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Shortcuts",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                // 2. News
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            showNewsDialog = true
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "News",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                // 3. Add
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onAddShortcutClick?.invoke()
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                // 5. At the bottom [ Switch ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "At the bottom",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Switch(
                        checked = homePageSettings.searchBarPosition == SearchBarPosition.BOTTOM,
                        onCheckedChange = { isBottom ->
                            val pos = if (isBottom) SearchBarPosition.BOTTOM else SearchBarPosition.TOP
                            viewModel.updateHomePageSettings(homePageSettings.copy(searchBarPosition = pos))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color(0xFF8E8E93),
                            uncheckedTrackColor = Color(0xFF3A3A3C)
                        )
                    )
                }
            }
        }
    }

    // --- SUB DIALOGS ---

    // 1. Shortcuts Settings Dialog
    if (showShortcutsDialog) {
        AlertDialog(
            onDismissRequest = { showShortcutsDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Shortcuts Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Enable / Disable Shortcuts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Shortcuts", color = Color.White, fontSize = 15.sp)
                        Switch(
                            checked = homePageSettings.showShortcuts,
                            onCheckedChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(showShortcuts = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }

                    // Large Icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Large Icons", color = Color.White, fontSize = 15.sp)
                        Switch(
                            checked = homePageSettings.shortcutIconSize == ShortcutIconSize.LARGE,
                            onCheckedChange = { isLarge ->
                                val size = if (isLarge) ShortcutIconSize.LARGE else ShortcutIconSize.SMALL
                                viewModel.updateHomePageSettings(homePageSettings.copy(shortcutIconSize = size))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }

                    // Columns Count
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Columns Count", color = Color.White, fontSize = 15.sp)
                            Text("${homePageSettings.shortcutColumns}", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        }
                        Slider(
                            value = homePageSettings.shortcutColumns.toFloat(),
                            onValueChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(shortcutColumns = it.toInt()))
                            },
                            valueRange = 4f..8f,
                            steps = 3,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShortcutsDialog = false }) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // 2. News Settings Dialog
    if (showNewsDialog) {
        AlertDialog(
            onDismissRequest = { showNewsDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("News Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Enable / Disable News
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable News", color = Color.White, fontSize = 15.sp)
                        Switch(
                            checked = homePageSettings.showNewsFeed,
                            onCheckedChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(showNewsFeed = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }

                    // News Source
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNewsSourcePicker = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("News Source", color = Color.White, fontSize = 15.sp)
                        Text(homePageSettings.newsSource, color = Color(0xFF8E8E93), fontSize = 14.sp)
                    }

                    // Reader Mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reader Mode", color = Color.White, fontSize = 15.sp)
                        Switch(
                            checked = homePageSettings.newsReaderModeDefault,
                            onCheckedChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(newsReaderModeDefault = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }

                    // Language & Region
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLangRegionPicker = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Language & Region", color = Color.White, fontSize = 15.sp)
                        Text("${homePageSettings.newsLanguage} (${homePageSettings.newsRegion})", color = Color(0xFF8E8E93), fontSize = 14.sp)
                    }

                    // News Translation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("News Translation", color = Color.White, fontSize = 15.sp)
                        Switch(
                            checked = homePageSettings.newsTranslationEnabled,
                            onCheckedChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(newsTranslationEnabled = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF8E8E93),
                                uncheckedTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }

                    // Number of Articles
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Number of Articles", color = Color.White, fontSize = 15.sp)
                            Text("${homePageSettings.newsItemCount}", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        }
                        Slider(
                            value = homePageSettings.newsItemCount.toFloat(),
                            onValueChange = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(newsItemCount = it.toInt()))
                            },
                            valueRange = 2f..12f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewsDialog = false }) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // News Source Picker
    if (showNewsSourcePicker) {
        val sources = listOf("Google News", "BBC News", "TechCrunch", "Reuters", "Bloomberg", "CNN", "The Verge")
        AlertDialog(
            onDismissRequest = { showNewsSourcePicker = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Select News Source", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sources.forEach { src ->
                        TextButton(
                            onClick = {
                                viewModel.updateHomePageSettings(homePageSettings.copy(newsSource = src))
                                showNewsSourcePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = src,
                                color = if (homePageSettings.newsSource == src) Color.White else Color(0xFF8E8E93),
                                fontWeight = if (homePageSettings.newsSource == src) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewsSourcePicker = false }) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }

    // Language & Region Picker
    if (showLangRegionPicker) {
        val languages = listOf("English", "Spanish", "French", "German", "Japanese", "Chinese")
        val regions = listOf("United States", "United Kingdom", "Global", "India", "Japan", "Germany")
        var selectedLang by remember { mutableStateOf(homePageSettings.newsLanguage) }
        var selectedRegion by remember { mutableStateOf(homePageSettings.newsRegion) }

        AlertDialog(
            onDismissRequest = { showLangRegionPicker = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Language & Region", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Language:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        languages.take(3).forEach { lang ->
                            FilterChip(
                                selected = selectedLang == lang,
                                onClick = { selectedLang = lang },
                                label = { Text(lang, fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Region:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        regions.take(3).forEach { reg ->
                            FilterChip(
                                selected = selectedRegion == reg,
                                onClick = { selectedRegion = reg },
                                label = { Text(reg, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateHomePageSettings(homePageSettings.copy(newsLanguage = selectedLang, newsRegion = selectedRegion))
                    showLangRegionPicker = false
                }) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLangRegionPicker = false }) {
                    Text("Cancel", color = Color(0xFF8E8E93))
                }
            }
        )
    }
}
