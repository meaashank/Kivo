package com.example.browser.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.browser.models.BrowserTab

enum class ViewMode {
    GRID, LIST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabManagerSheetContent(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    tabThumbnails: Map<String, Bitmap>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onAddIncognitoTab: () -> Unit,
    onRestoreClosedTab: () -> Unit,
    onClearAllTabs: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTogglePin: (String) -> Unit = {},
    onRenameTab: (String, String) -> Unit = { _, _ -> },
    onDuplicateTab: (String) -> Unit = {},
    onCloseOthers: (String) -> Unit = {},
    onAutoGroup: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Detect initial tab category based on active tab
    var isIncognitoSelected by remember {
        mutableStateOf(
            activeTabId?.let { id -> tabs.find { it.id == id }?.isIncognito } == true
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var selectedGroupFilter by remember { mutableStateOf<String?>(null) }

    // Quick Actions tab menu target
    var actionTargetTab by remember { mutableStateOf<BrowserTab?>(null) }
    var tabToRename by remember { mutableStateOf<BrowserTab?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Filter tabs by category, group, and search query
    val categoryTabs = remember(tabs, isIncognitoSelected) {
        tabs.filter { it.isIncognito == isIncognitoSelected }
    }

    val availableGroups = remember(categoryTabs) {
        categoryTabs.mapNotNull { it.groupName }.distinct()
    }

    val filteredTabs = remember(categoryTabs, selectedGroupFilter, searchQuery) {
        categoryTabs.filter { tab ->
            val matchesGroup = selectedGroupFilter == null || tab.groupName == selectedGroupFilter
            val matchesSearch = searchQuery.isBlank() ||
                    tab.displayTitle.contains(searchQuery, ignoreCase = true) ||
                    tab.url.contains(searchQuery, ignoreCase = true) ||
                    tab.domain.contains(searchQuery, ignoreCase = true)
            matchesGroup && matchesSearch
        }
    }

    val normalCount = remember(tabs) { tabs.count { !it.isIncognito } }
    val incognitoCount = remember(tabs) { tabs.count { it.isIncognito } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // --- TOP TOOLBAR ---
            TopTabSwitcherBar(
                isIncognitoSelected = isIncognitoSelected,
                normalCount = normalCount,
                incognitoCount = incognitoCount,
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                viewMode = viewMode,
                onCategoryChange = {
                    isIncognitoSelected = it
                    selectedGroupFilter = null
                },
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                },
                onSearchQueryChange = { searchQuery = it },
                onToggleViewMode = {
                    viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                },
                onAutoGroup = {
                    onAutoGroup()
                    Toast.makeText(context, "Grouped open tabs by domain", Toast.LENGTH_SHORT).show()
                },
                onRestoreClosed = onRestoreClosedTab,
                onClearAll = { showDeleteConfirmDialog = true },
                onDismiss = onDismiss
            )

            // --- TAB GROUPS FILTER CHIPS ---
            if (availableGroups.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedGroupFilter == null,
                            onClick = { selectedGroupFilter = null },
                            label = { Text("All (${categoryTabs.size})", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF14FFC2),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF16161E),
                                labelColor = Color.White
                            ),
                            border = null,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                    items(availableGroups) { group ->
                        val groupCount = categoryTabs.count { it.groupName == group }
                        FilterChip(
                            selected = selectedGroupFilter == group,
                            onClick = {
                                selectedGroupFilter = if (selectedGroupFilter == group) null else group
                            },
                            label = { Text("$group ($groupCount)", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3EA6FF),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF16161E),
                                labelColor = Color.White
                            ),
                            border = null,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            // --- MAIN TABS CONTENT ---
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                val gridColumns = when {
                    maxWidth < 380.dp -> 2
                    maxWidth < 600.dp -> 2
                    maxWidth < 900.dp -> 3
                    else -> 4
                }

                if (filteredTabs.isEmpty()) {
                    EmptyTabsPlaceholder(
                        isIncognito = isIncognitoSelected,
                        searchQuery = searchQuery,
                        onAddTab = {
                            if (isIncognitoSelected) onAddIncognitoTab() else onAddTab()
                        }
                    )
                } else {
                    AnimatedContent(
                        targetState = viewMode,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                        },
                        label = "ViewModeTransition"
                    ) { currentViewMode ->
                        if (currentViewMode == ViewMode.GRID) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridColumns),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = filteredTabs,
                                    key = { it.id }
                                ) { tab ->
                                    val isActive = tab.id == activeTabId
                                    val thumbnail = tabThumbnails[tab.id]

                                    TabGridCard(
                                        tab = tab,
                                        isActive = isActive,
                                        thumbnail = thumbnail,
                                        onSelect = { onSelectTab(tab.id) },
                                        onClose = { onCloseTab(tab.id) },
                                        onLongClick = { actionTargetTab = tab }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = filteredTabs,
                                    key = { it.id }
                                ) { tab ->
                                    val isActive = tab.id == activeTabId
                                    val thumbnail = tabThumbnails[tab.id]

                                    TabListRow(
                                        tab = tab,
                                        isActive = isActive,
                                        thumbnail = thumbnail,
                                        onSelect = { onSelectTab(tab.id) },
                                        onClose = { onCloseTab(tab.id) },
                                        onLongClick = { actionTargetTab = tab }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- FLOATING TRANSLUCENT BOTTOM BAR ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            FloatingBottomControls(
                activeCount = if (isIncognitoSelected) incognitoCount else normalCount,
                isIncognito = isIncognitoSelected,
                onDeleteAll = { showDeleteConfirmDialog = true },
                onNewTab = {
                    if (isIncognitoSelected) onAddIncognitoTab() else onAddTab()
                }
            )
        }

        // --- QUICK ACTIONS BOTTOM SHEET ---
        actionTargetTab?.let { target ->
            TabQuickActionsSheet(
                tab = target,
                onDismiss = { actionTargetTab = null },
                onSelect = {
                    actionTargetTab = null
                    onSelectTab(target.id)
                },
                onClose = {
                    actionTargetTab = null
                    onCloseTab(target.id)
                },
                onTogglePin = {
                    actionTargetTab = null
                    onTogglePin(target.id)
                },
                onDuplicate = {
                    actionTargetTab = null
                    onDuplicateTab(target.id)
                },
                onRename = {
                    renameInput = target.displayTitle
                    tabToRename = target
                    actionTargetTab = null
                },
                onCopyUrl = {
                    actionTargetTab = null
                    clipboardManager.setText(AnnotatedString(target.url))
                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                onShare = {
                    actionTargetTab = null
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, target.url)
                        type = "text/plain"
                    }
                    context.startActivity(android.content.Intent.createChooser(sendIntent, "Share URL"))
                },
                onCloseOthers = {
                    actionTargetTab = null
                    onCloseOthers(target.id)
                    Toast.makeText(context, "Closed other tabs", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // --- RENAME TAB DIALOG ---
        tabToRename?.let { target ->
            AlertDialog(
                onDismissRequest = { tabToRename = null },
                containerColor = Color(0xFF181820),
                title = { Text("Rename Tab", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3EA6FF),
                            unfocusedBorderColor = Color(0xFF333342)
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (renameInput.isNotBlank()) {
                                onRenameTab(target.id, renameInput.trim())
                            }
                            tabToRename = null
                        }
                    ) {
                        Text("Save", color = Color(0xFF14FFC2), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { tabToRename = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }

        // --- DELETE ALL CONFIRMATION DIALOG ---
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                containerColor = Color(0xFF181820),
                title = {
                    Text(
                        text = "Close All ${if (isIncognitoSelected) "Private" else "Standard"} Tabs?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "This will close all ${if (isIncognitoSelected) incognitoCount else normalCount} open tabs in this mode.",
                        color = Color(0xFFA1A1AA),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onClearAllTabs(isIncognitoSelected)
                            Toast.makeText(
                                context,
                                "Closed all ${if (isIncognitoSelected) "private" else "standard"} tabs",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text("Close All", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }
    }
}

// --- MEMORY USAGE INDICATOR COMPOSABLE ---
@Composable
private fun MemoryUsageIndicator() {
    var usedMemoryMb by remember { mutableStateOf(0L) }
    var maxMemoryMb by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            val runtime = Runtime.getRuntime()
            val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val max = runtime.maxMemory() / (1024 * 1024)
            usedMemoryMb = used
            maxMemoryMb = max
            kotlinx.coroutines.delay(3000)
        }
    }

    val memoryRatio = if (maxMemoryMb > 0) usedMemoryMb.toFloat() / maxMemoryMb.toFloat() else 0f
    val indicatorColor = when {
        memoryRatio > 0.85f -> Color(0xFFFF5252)
        memoryRatio > 0.65f -> Color(0xFFFFB74D)
        else -> Color(0xFF14FFC2)
    }

    Surface(
        color = Color(0xFF161622),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28283A)),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Icon(
                imageVector = Icons.Outlined.Analytics,
                contentDescription = "RAM Usage",
                tint = Color(0xFFA1A1AA),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "${usedMemoryMb}MB",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

// --- TOP BAR COMPOSABLE ---
@Composable
private fun TopTabSwitcherBar(
    isIncognitoSelected: Boolean,
    normalCount: Int,
    incognitoCount: Int,
    isSearchActive: Boolean,
    searchQuery: String,
    viewMode: ViewMode,
    onCategoryChange: (Boolean) -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onAutoGroup: () -> Unit,
    onRestoreClosed: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Back button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back to web page",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        // Center: Mode Segmented Pill Switcher or Search Bar
        if (isSearchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search tabs...", color = Color.Gray, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3EA6FF),
                    unfocusedBorderColor = Color(0xFF282836),
                    focusedContainerColor = Color(0xFF121218),
                    unfocusedContainerColor = Color(0xFF121218)
                ),
                shape = RoundedCornerShape(22.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )
        } else {
            // Segmented Mode Control
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF16161E))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Normal Tabs Switch
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (!isIncognitoSelected) Color(0xFF2A2A38) else Color.Transparent)
                        .clickable { onCategoryChange(false) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Tab,
                            contentDescription = "Standard Tabs",
                            tint = if (!isIncognitoSelected) Color(0xFF14FFC2) else Color(0xFFA1A1AA),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Tabs ($normalCount)",
                            fontSize = 12.sp,
                            fontWeight = if (!isIncognitoSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (!isIncognitoSelected) Color.White else Color(0xFFA1A1AA)
                        )
                    }
                }

                // Incognito Tabs Switch
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isIncognitoSelected) Color(0xFF2A2A38) else Color.Transparent)
                        .clickable { onCategoryChange(true) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = "Private Tabs",
                            tint = if (isIncognitoSelected) Color(0xFF3EA6FF) else Color(0xFFA1A1AA),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Private ($incognitoCount)",
                            fontSize = 12.sp,
                            fontWeight = if (isIncognitoSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isIncognitoSelected) Color.White else Color(0xFFA1A1AA)
                        )
                    }
                }
            }
        }

        // Right Action Icons
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemoryUsageIndicator()

            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = "Search Tabs",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onToggleViewMode,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (viewMode == ViewMode.GRID) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = "Toggle View Mode",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1C1C26))
                        .border(1.dp, Color(0xFF2E2E3E), RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Group Tabs by Domain", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color(0xFF14FFC2), modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showMoreMenu = false
                            onAutoGroup()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Restore Last Closed Tab", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Restore, contentDescription = null, tint = Color(0xFF3EA6FF), modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showMoreMenu = false
                            onRestoreClosed()
                        }
                    )
                    HorizontalDivider(color = Color(0xFF2E2E3E))
                    DropdownMenuItem(
                        text = { Text("Close All Tabs", color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showMoreMenu = false
                            onClearAll()
                        }
                    )
                }
            }
        }
    }
}

// --- GRID TAB CARD COMPOSABLE ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabGridCard(
    tab: BrowserTab,
    isActive: Boolean,
    thumbnail: Bitmap?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onLongClick: () -> Unit
) {
    val scaleAnim by animateFloatAsState(
        targetValue = if (isActive) 1.03f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "CardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scaleAnim)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongClick
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color(0xFF3EA6FF) else Color(0xFF22222E),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 6.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Card Header Info (Favicon, Title, Domain, Pin/Badges)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Domain Favicon Avatar / Initial
                    FaviconBadge(domain = tab.domain, isIncognito = tab.isIncognito)

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tab.displayTitle,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = tab.domain,
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = Color(0xFFA1A1AA)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Badges
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tab.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = Color(0xFF14FFC2),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (tab.isIncognito) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = "Incognito",
                            tint = Color(0xFF3EA6FF),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Thumbnail Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A0A0E)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Tab preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    TabPreviewPlaceholder(tab = tab)
                }

                // Top-Right Compact Circular Close Button Inside Thumbnail
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Active Thin Accent Indicator Line at Bottom of Thumbnail
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF3EA6FF))
                    )
                }
            }

            // Footer info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (tab.url == "about:blank") "Home" else "Active",
                    fontSize = 10.sp,
                    color = Color(0xFF71717A)
                )

                if (tab.groupName != null) {
                    Text(
                        text = tab.groupName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF14FFC2),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F2620))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// --- LIST TAB ROW COMPOSABLE ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabListRow(
    tab: BrowserTab,
    isActive: Boolean,
    thumbnail: Bitmap?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongClick
            )
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color(0xFF3EA6FF) else Color(0xFF22222E),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121218))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail Preview Box
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0A0E)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    FaviconBadge(domain = tab.domain, isIncognito = tab.isIncognito, size = 28.dp)
                }
            }

            // Info Column
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = tab.displayTitle,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (tab.isPinned) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = Color(0xFF14FFC2), modifier = Modifier.size(12.dp))
                    }
                }

                Text(
                    text = tab.domain,
                    fontSize = 11.sp,
                    color = Color(0xFFA1A1AA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFFA1A1AA),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// --- FAVICON BADGE ---
@Composable
private fun FaviconBadge(
    domain: String,
    isIncognito: Boolean,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    val initial = domain.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "W"
    val bgColor = if (isIncognito) Color(0xFF1E2638) else Color(0xFF1E2824)
    val textColor = if (isIncognito) Color(0xFF3EA6FF) else Color(0xFF14FFC2)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            fontSize = (size.value * 0.55f).sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

// --- PLACEHOLDER WHEN NO THUMBNAIL ---
@Composable
private fun TabPreviewPlaceholder(tab: BrowserTab) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1A26), Color(0xFF0C0C12))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (tab.isIncognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Tab,
                contentDescription = null,
                tint = if (tab.isIncognito) Color(0xFF3EA6FF) else Color(0xFF14FFC2),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tab.domain,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFA1A1AA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- EMPTY TABS PLACEHOLDER ---
@Composable
private fun EmptyTabsPlaceholder(
    isIncognito: Boolean,
    searchQuery: String,
    onAddTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isIncognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Tab,
            contentDescription = "No tabs",
            tint = if (isIncognito) Color(0xFF3EA6FF) else Color(0xFF14FFC2),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (searchQuery.isNotBlank()) "No matching tabs found" else if (isIncognito) "No Private Tabs Open" else "No Standard Tabs Open",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (searchQuery.isNotBlank()) "Try searching for a different keyword" else "Tap below to open a fast, secure new tab",
            fontSize = 13.sp,
            color = Color(0xFFA1A1AA),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAddTab,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isIncognito) Color(0xFF3EA6FF) else Color(0xFF14FFC2),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open New Tab", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// --- FLOATING TRANSLUCENT BOTTOM CONTROLS ---
@Composable
private fun FloatingBottomControls(
    activeCount: Int,
    isIncognito: Boolean,
    onDeleteAll: () -> Unit,
    onNewTab: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(58.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .border(1.dp, Color(0xFF2A2A38), RoundedCornerShape(32.dp)),
        color = Color(0xDC121218), // Translucent Glassmorphism
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Clear all button
            TextButton(
                onClick = onDeleteAll,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Close All", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete All", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // Center: Tab count indicator
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF222230))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "$activeCount ${if (isIncognito) "Private" else "Tabs"}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Right: Floating circular Primary Accent FAB
            FloatingActionButton(
                onClick = onNewTab,
                containerColor = if (isIncognito) Color(0xFF3EA6FF) else Color(0xFF14FFC2),
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Tab", modifier = Modifier.size(24.dp))
            }
        }
    }
}

// --- QUICK ACTIONS MODAL SHEET ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabQuickActionsSheet(
    tab: BrowserTab,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onCopyUrl: () -> Unit,
    onShare: () -> Unit,
    onCloseOthers: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161E),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF38384A))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                FaviconBadge(domain = tab.domain, isIncognito = tab.isIncognito, size = 32.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(tab.displayTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tab.url, fontSize = 12.sp, color = Color(0xFFA1A1AA), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider(color = Color(0xFF282838), modifier = Modifier.padding(bottom = 12.dp))

            // Action Items
            QuickActionRow(icon = Icons.Default.OpenInNew, title = "Open Tab", onClick = onSelect)
            QuickActionRow(
                icon = if (tab.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                title = if (tab.isPinned) "Unpin Tab" else "Pin Tab",
                onClick = onTogglePin
            )
            QuickActionRow(icon = Icons.Outlined.ContentCopy, title = "Duplicate Tab", onClick = onDuplicate)
            QuickActionRow(icon = Icons.Outlined.Edit, title = "Rename Tab", onClick = onRename)
            QuickActionRow(icon = Icons.Outlined.Link, title = "Copy URL", onClick = onCopyUrl)
            QuickActionRow(icon = Icons.Outlined.Share, title = "Share Link", onClick = onShare)
            QuickActionRow(icon = Icons.Outlined.ClearAll, title = "Close Other Tabs", onClick = onCloseOthers)
            QuickActionRow(icon = Icons.Default.Close, title = "Close Tab", isDestructive = true, onClick = onClose)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) Color(0xFFFF5252) else Color(0xFF14FFC2),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDestructive) Color(0xFFFF5252) else Color.White
        )
    }
}
