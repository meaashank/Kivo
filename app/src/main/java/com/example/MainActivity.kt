package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.browser.data.BookmarkItem
import com.example.browser.data.HistoryItem
import com.example.browser.models.BrowserTab
import com.example.browser.models.SearchEngine
import com.example.browser.settings.BrowserSettings
import com.example.browser.ui.BrowserViewModel
import com.example.browser.ui.JsDialogState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BrowserViewModel

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == Activity.RESULT_OK) {
            val dataUri = result.data?.data
            val clipData = result.data?.clipData
            if (clipData != null) {
                val list = mutableListOf<Uri>()
                for (i in 0 until clipData.itemCount) {
                    list.add(clipData.getItemAt(i).uri)
                }
                list.toTypedArray()
            } else if (dataUri != null) {
                arrayOf(dataUri)
            } else {
                null
            }
        } else {
            null
        }
        viewModel.fileChooserCallback?.onReceiveValue(results)
        viewModel.fileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            viewModel = viewModel<BrowserViewModel>()
            
            // Listen for web file-upload callbacks and start picker
            LaunchedEffect(Unit) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.fileUploadRequestEvent.collect { acceptTypes ->
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (acceptTypes.isNotEmpty() && acceptTypes[0].isNotBlank()) acceptTypes[0] else "*/*"
                                if (acceptTypes.size > 1) {
                                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                                }
                            }
                            fileChooserLauncher.launch(Intent.createChooser(intent, "Select File"))
                        }
                    }
                }
            }

            MyApplicationTheme {
                BrowserAppScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAppScreen(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    
    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val isIncognito = activeTab?.isIncognito ?: false

    // Request permissions dynamically
    val permissionsToRequest = remember {
        mutableStateListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionsLauncher.launch(ungranted.toTypedArray())
        }
    }

    // Intercept physical system back button to navigate inside WebView history first
    BackHandler(enabled = activeTab?.canGoBack == true) {
        viewModel.navigateBackInActiveTab()
    }

    // UI overlays/sheets controllers
    var showTabManager by remember { mutableStateOf(false) }
    var showMenuOptions by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Theme Color Swaps based on Private/Incognito Mode
    val primaryColor = if (isIncognito) Color(0xFF80CBC4) else MaterialTheme.colorScheme.primary
    val containerBg = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
    val barColor = if (isIncognito) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = containerBg,
        topBar = {
            TopAppBarContainer(
                activeTab = activeTab,
                isIncognito = isIncognito,
                primaryColor = primaryColor,
                barColor = barColor,
                onLoadUrl = { viewModel.loadUrlInActiveTab(it) },
                onRefresh = { viewModel.refreshActiveTab() },
                onStop = { viewModel.stopLoadingActiveTab() },
                onToggleBookmark = { viewModel.toggleBookmarkActiveTab() },
                isBookmarkedFlow = activeTab?.url?.let { viewModel.isBookmarked(it) } ?: flowOf(false)
            )
        },
        bottomBar = {
            BottomNavigationBar(
                activeTab = activeTab,
                tabsCount = tabs.size,
                primaryColor = primaryColor,
                onBack = { viewModel.navigateBackInActiveTab() },
                onForward = { viewModel.navigateForwardInActiveTab() },
                onHome = { viewModel.loadUrlInActiveTab("about:blank") },
                onOpenTabManager = { showTabManager = true },
                onOpenMenu = { showMenuOptions = true }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeTab != null) {
                if (activeTab.url == "about:blank") {
                    // Browser Homepage with Quick Shortcuts and beautiful grid
                    BrowserHomepage(
                        isIncognito = isIncognito,
                        onLoadUrl = { viewModel.loadUrlInActiveTab(it) },
                        onOpenBookmarks = { showBookmarksSheet = true },
                        onOpenHistory = { showHistorySheet = true },
                        bookmarks = viewModel.bookmarks.collectAsStateWithLifecycle().value
                    )
                } else {
                    // Cached/active WebView
                    TabWebViewContainer(
                        tabId = activeTab.id,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // JavaScript alerts integration overlay
            val jsDialog by viewModel.activeJsDialog.collectAsStateWithLifecycle()
            jsDialog?.let { dialogState ->
                JsAlertDialog(dialogState = dialogState, onDismiss = { viewModel.activeJsDialog.value = null })
            }

            // Fullscreen video custom view overlay
            val customView by viewModel.customVideoView.collectAsStateWithLifecycle()
            customView?.let { videoData ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable(enabled = false) {} // block click-throughs
                ) {
                    AndroidView(
                        factory = { videoData.view },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { viewModel.customVideoView.value = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Exit Fullscreen", tint = Color.White)
                    }
                }
            }
        }
    }

    // --- Bottom Sheet Overlays ---
    
    // 1. Tab Manager Sheet
    if (showTabManager) {
        ModalBottomSheet(
            onDismissRequest = { showTabManager = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            TabManagerSheetContent(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = {
                    viewModel.selectTab(it)
                    showTabManager = false
                },
                onCloseTab = { viewModel.closeTab(it) },
                onAddTab = {
                    viewModel.createNewTab("about:blank")
                    showTabManager = false
                },
                onAddIncognitoTab = {
                    viewModel.createNewTab("about:blank", isIncognito = true)
                    showTabManager = false
                },
                onRestoreClosedTab = { viewModel.restoreLastClosedTab() }
            )
        }
    }

    // 2. Main Menu Drawer
    if (showMenuOptions) {
        ModalBottomSheet(
            onDismissRequest = { showMenuOptions = false }
        ) {
            MenuOptionsSheetContent(
                activeTab = activeTab,
                onAction = { action ->
                    showMenuOptions = false
                    when (action) {
                        MenuAction.NEW_TAB -> viewModel.createNewTab("about:blank")
                        MenuAction.NEW_INCOGNITO -> viewModel.createNewTab("about:blank", isIncognito = true)
                        MenuAction.BOOKMARKS -> showBookmarksSheet = true
                        MenuAction.HISTORY -> showHistorySheet = true
                        MenuAction.TOGGLE_DESKTOP -> viewModel.toggleDesktopModeInActiveTab()
                        MenuAction.SETTINGS -> showSettingsSheet = true
                        MenuAction.RESTORE_TAB -> viewModel.restoreLastClosedTab()
                    }
                }
            )
        }
    }

    // 3. History Logs Sheet
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            HistorySheetContent(
                historyFlow = viewModel.history,
                onUrlClick = {
                    viewModel.loadUrlInActiveTab(it)
                    showHistorySheet = false
                },
                onDeleteItem = { viewModel.deleteHistoryItem(it) },
                onClearAll = { viewModel.clearAllHistory() }
            )
        }
    }

    // 4. Bookmarks Sheet
    if (showBookmarksSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarksSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            BookmarksSheetContent(
                bookmarksFlow = viewModel.bookmarks,
                onUrlClick = {
                    viewModel.loadUrlInActiveTab(it)
                    showBookmarksSheet = false
                },
                onDeleteItem = { viewModel.deleteBookmarkItem(it) }
            )
        }
    }

    // 5. Settings Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false }
        ) {
            SettingsSheetContent(
                settings = settings,
                onUpdateSettings = { viewModel.setSettings(it) }
            )
        }
    }
}

// --- Top Address Bar Component ---
@Composable
fun TopAppBarContainer(
    activeTab: BrowserTab?,
    isIncognito: Boolean,
    primaryColor: Color,
    barColor: Color,
    onLoadUrl: (String) -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onToggleBookmark: () -> Unit,
    isBookmarkedFlow: Flow<Boolean>
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isBookmarked by isBookmarkedFlow.collectAsStateWithLifecycle(initialValue = false)

    var urlInput by remember(activeTab?.url) {
        mutableStateOf(if (activeTab?.url == "about:blank") "" else activeTab?.url ?: "")
    }

    Column(
        modifier = Modifier
            .background(barColor)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // HTTPS Lock icon indicator
            Icon(
                imageVector = when {
                    activeTab?.url == "about:blank" -> Icons.Default.Search
                    activeTab?.url?.startsWith("https://") == true -> Icons.Default.Lock
                    else -> Icons.Default.Language
                },
                contentDescription = "Security State Indicator",
                tint = if (activeTab?.url?.startsWith("https://") == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(20.dp)
            )

            // Address Text Input
            TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("Search or type URL", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onLoadUrl(urlInput)
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("address_input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface
                )
            )

            // Dynamic Right Controls inside search box
            if (urlInput.isNotBlank()) {
                IconButton(
                    onClick = { urlInput = "" },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear Address Bar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Quick Bookmark toggle
            if (activeTab?.url != "about:blank" && activeTab != null) {
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark Page",
                        tint = if (isBookmarked) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Refresh/Stop Loading Button
            IconButton(
                onClick = { if (activeTab?.isLoading == true) onStop() else onRefresh() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (activeTab?.isLoading == true) "Stop Loading" else "Reload page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Animated Web Page loading linear progress bar
        AnimatedVisibility(
            visible = activeTab?.isLoading == true && activeTab.progress < 100,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                progress = { (activeTab?.progress ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(top = 4.dp),
                color = primaryColor
            )
        }
    }
}

// --- Bottom Command Navigation Bar ---
@Composable
fun BottomNavigationBar(
    activeTab: BrowserTab?,
    tabsCount: Int,
    primaryColor: Color,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenTabManager: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Back button
            IconButton(
                onClick = onBack,
                enabled = activeTab?.canGoBack == true
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = if (activeTab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // 2. Forward button
            IconButton(
                onClick = onForward,
                enabled = activeTab?.canGoForward == true
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (activeTab?.canGoForward == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }

            // 3. Home / Default empty page
            IconButton(onClick = onHome) {
                Icon(imageVector = Icons.Default.Home, contentDescription = "Home Page")
            }

            // 4. Tab Manager overlay toggler
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable(onClick = onOpenTabManager)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabsCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 5. Main settings menu toggler
            IconButton(onClick = onOpenMenu) {
                Icon(imageVector = Icons.Default.Menu, contentDescription = "More Options Menu")
            }
        }
    }
}

// --- Lazy WebView Container ---
@Composable
fun TabWebViewContainer(
    tabId: String,
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webView = remember(tabId) { viewModel.getOrCreateWebView(tabId, context) }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = { /* The WebView client properties are managed entirely inside the ViewModel */ }
    )
}

// --- Browser Custom Native M3 Homepage ---
@Composable
fun BrowserHomepage(
    isIncognito: Boolean,
    onLoadUrl: (String) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    bookmarks: List<BookmarkItem>
) {
    val scrollState = rememberScrollState()
    val primaryColor = if (isIncognito) Color(0xFF80CBC4) else MaterialTheme.colorScheme.primary
    
    // Background Radial Gradient for extremely premium visual depth
    val bgBrush = if (isIncognito) {
        Brush.verticalGradient(listOf(Color(0xFF1E1E1E), Color(0xFF121212)))
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.background
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Large Custom Dynamic Icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    if (isIncognito) Color(0xFF263238) else MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isIncognito) Icons.Default.Security else Icons.Default.Explore,
                contentDescription = "Kivo Logo Logo",
                modifier = Modifier.size(38.dp),
                tint = if (isIncognito) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title Header
        Text(
            text = if (isIncognito) "Incognito Session" else "Kivo",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = if (isIncognito) "Browsing privately without database logs" else "Lightweight, secure, and blazing fast",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Beautiful Grid of Quick Link Shortcuts
        val shortcuts = listOf(
            ShortcutItem("Google", "https://www.google.com", Icons.Default.Search, Color(0xFF4285F4)),
            ShortcutItem("YouTube", "https://www.youtube.com", Icons.Default.PlayArrow, Color(0xFFFF0000)),
            ShortcutItem("Wikipedia", "https://www.wikipedia.org", Icons.Default.Language, Color(0xFF607D8B)),
            ShortcutItem("GitHub", "https://www.github.com", Icons.Default.Code, Color(0xFF212121)),
            ShortcutItem("Reddit", "https://www.reddit.com", Icons.Default.Forum, Color(0xFFFF4500)),
            ShortcutItem("StackOverflow", "https://stackoverflow.com", Icons.Default.Layers, Color(0xFFF48024))
        )

        Text(
            text = "QUICK SHUTTLE",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(shortcuts) { shortcut ->
                Card(
                    onClick = { onLoadUrl(shortcut.url) },
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isIncognito) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(shortcut.color.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = shortcut.icon,
                                contentDescription = shortcut.label,
                                tint = shortcut.color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = shortcut.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Homepage Quick Bookmarks and History navigation shortcuts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onOpenBookmarks,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Bookmarks, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bookmarks")
            }

            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.History, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("History Logs")
            }
        }

        if (bookmarks.isNotEmpty() && !isIncognito) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "RECENT SAVED SITES",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            bookmarks.take(3).forEach { b ->
                Card(
                    onClick = { onLoadUrl(b.url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isIncognito) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = b.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = b.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ShortcutItem(
    val label: String,
    val url: String,
    val icon: ImageVector,
    val color: Color
)

// --- Tab Manager Overlay Content ---
@Composable
fun TabManagerSheetContent(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onAddIncognitoTab: () -> Unit,
    onRestoreClosedTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Browser Tabs (${tabs.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )

            // Restore closed tab button
            TextButton(onClick = onRestoreClosedTab) {
                Icon(imageVector = Icons.Default.RestorePage, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Undo Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grid of tab cards
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(tabs) { tab ->
                val isActive = tab.id == activeTabId
                val borderBrush = if (isActive) {
                    BorderStroke(2.dp, if (tab.isIncognito) Color(0xFF80CBC4) else MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                }

                Card(
                    modifier = Modifier
                        .height(130.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = borderBrush,
                    colors = CardDefaults.cardColors(
                        containerColor = if (tab.isIncognito) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
                    ),
                    onClick = { onSelectTab(tab.id) }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                // Incognito flag badge
                                if (tab.isIncognito) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color(0xFF263238))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Private", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00E676))
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                // Close tab button
                                IconButton(
                                    onClick = { onCloseTab(tab.id) },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            // Meta texts
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (tab.url == "about:blank") "Homepage" else tab.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Multi-add controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onAddTab,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Standard Tab")
            }

            Button(
                onClick = onAddIncognitoTab,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF212121), contentColor = Color(0xFF80CBC4)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Private Tab")
            }
        }
    }
}

// --- Drawer Action List Content ---
@Composable
fun MenuOptionsSheetContent(
    activeTab: BrowserTab?,
    onAction: (MenuAction) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Text(
            text = "Browser Options",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        val menuItems = listOf(
            GridMenuAction("New Standard Tab", Icons.Default.Add, MenuAction.NEW_TAB),
            GridMenuAction("New Private Tab", Icons.Default.Security, MenuAction.NEW_INCOGNITO),
            GridMenuAction("Open Bookmarks", Icons.Default.Bookmarks, MenuAction.BOOKMARKS),
            GridMenuAction("Open History Logs", Icons.Default.History, MenuAction.HISTORY),
            GridMenuAction(
                if (activeTab?.isDesktopMode == true) "Mobile View" else "Desktop View",
                if (activeTab?.isDesktopMode == true) Icons.Default.StayCurrentPortrait else Icons.Default.Laptop,
                MenuAction.TOGGLE_DESKTOP
            ),
            GridMenuAction("Undo Closed Tab", Icons.Default.Restore, MenuAction.RESTORE_TAB),
            GridMenuAction("Modular Settings", Icons.Default.Settings, MenuAction.SETTINGS)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height(240.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(menuItems) { item ->
                Card(
                    onClick = { onAction(item.action) },
                    modifier = Modifier.fillMaxSize(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

enum class MenuAction {
    NEW_TAB, NEW_INCOGNITO, BOOKMARKS, HISTORY, TOGGLE_DESKTOP, RESTORE_TAB, SETTINGS
}

data class GridMenuAction(
    val label: String,
    val icon: ImageVector,
    val action: MenuAction
)

// --- History Overlay List Content ---
@Composable
fun HistorySheetContent(
    historyFlow: Flow<List<HistoryItem>>,
    onUrlClick: (String) -> Unit,
    onDeleteItem: (HistoryItem) -> Unit,
    onClearAll: () -> Unit
) {
    val items by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var searchInput by remember { mutableStateOf("") }
    val filteredItems = remember(items, searchInput) {
        if (searchInput.isBlank()) items else items.filter {
            it.title.contains(searchInput, ignoreCase = true) || it.url.contains(searchInput, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History Logs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )

            if (items.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Filter
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            placeholder = { Text("Search logs...") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.HistoryToggleOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No history items found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lazyItems(filteredItems) { item ->
                    Card(
                        onClick = { onUrlClick(item.url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteItem(item) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete from history",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Bookmarks Overlay List Content ---
@Composable
fun BookmarksSheetContent(
    bookmarksFlow: Flow<List<BookmarkItem>>,
    onUrlClick: (String) -> Unit,
    onDeleteItem: (BookmarkItem) -> Unit
) {
    val items by bookmarksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var searchInput by remember { mutableStateOf("") }
    val filteredItems = remember(items, searchInput) {
        if (searchInput.isBlank()) items else items.filter {
            it.title.contains(searchInput, ignoreCase = true) || it.url.contains(searchInput, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(16.dp)
    ) {
        Text(
            text = "Bookmarks Manager",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Search Filter
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            placeholder = { Text("Search bookmarks...") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No saved bookmarks found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lazyItems(filteredItems) { item ->
                    Card(
                        onClick = { onUrlClick(item.url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteItem(item) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove bookmark",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Browser Custom Modular Settings Overlay ---
@Composable
fun SettingsSheetContent(
    settings: BrowserSettings,
    onUpdateSettings: (BrowserSettings) -> Unit
) {
    val context = LocalContext.current
    var searchEngineMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Browser Settings",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 1. Search Engine Preference Row
        ListItem(
            headlineContent = { Text("Default Search Engine") },
            supportingContent = { Text(settings.searchEngine.displayName) },
            leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingContent = {
                Box {
                    Button(onClick = { searchEngineMenuExpanded = true }) {
                        Text("Select")
                    }
                    DropdownMenu(
                        expanded = searchEngineMenuExpanded,
                        onDismissRequest = { searchEngineMenuExpanded = false }
                    ) {
                        SearchEngine.entries.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.displayName) },
                                onClick = {
                                    onUpdateSettings(settings.copy(searchEngine = engine))
                                    searchEngineMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 2. JavaScript toggle
        ListItem(
            headlineContent = { Text("Enable JavaScript") },
            supportingContent = { Text("Required for complex dynamic websites") },
            leadingContent = { Icon(Icons.Default.Javascript, contentDescription = null) },
            trailingContent = {
                Switch(
                    checked = settings.isJavaScriptEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(isJavaScriptEnabled = it)) }
                )
            }
        )

        // 3. Cookies toggle
        ListItem(
            headlineContent = { Text("Allow Cookies") },
            supportingContent = { Text("Saves logged-in sessions and site states") },
            leadingContent = { Icon(Icons.Default.Cookie, contentDescription = null) },
            trailingContent = {
                Switch(
                    checked = settings.isCookiesEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(isCookiesEnabled = it)) }
                )
            }
        )

        // 4. Adblock placeholder indication
        ListItem(
            headlineContent = { Text("Kivo Ad-Blocker") },
            supportingContent = { Text("Bypass trackers and ads natively") },
            leadingContent = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
            trailingContent = {
                Switch(
                    checked = settings.isAdBlockEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(isAdBlockEnabled = it)) }
                )
            }
        )

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        // 5. Hard Cache/Cookies Wiping Actions
        Text(
            text = "Storage & Privacy",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    try {
                        WebView(context).clearCache(true)
                        Toast.makeText(context, "Chromium cache wiped successfully", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cache clear failed", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.CleaningServices, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Wipe Cache")
            }

            Button(
                onClick = {
                    try {
                        CookieManager.getInstance().removeAllCookies { success ->
                            if (success) {
                                Toast.makeText(context, "All cookies successfully deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cookies deletion failed", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.PrivacyTip, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Wipe Cookies")
            }
        }
    }
}

// --- JavaScript Dialog Modal Overlay ---
@Composable
fun JsAlertDialog(dialogState: JsDialogState, onDismiss: () -> Unit) {
    var promptInput by remember { mutableStateOf((dialogState as? JsDialogState.Prompt)?.defaultValue ?: "") }

    AlertDialog(
        onDismissRequest = {
            dialogState.result?.cancel()
            onDismiss()
        },
        title = {
            Text(
                text = when (dialogState) {
                    is JsDialogState.Alert -> "Alert"
                    is JsDialogState.Confirm -> "Confirm Action"
                    is JsDialogState.Prompt -> "Input Required"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(text = dialogState.message)
                if (dialogState is JsDialogState.Prompt) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (dialogState is JsDialogState.Prompt) {
                        dialogState.promptResult?.confirm(promptInput)
                    } else {
                        dialogState.result?.confirm()
                    }
                    onDismiss()
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            if (dialogState !is JsDialogState.Alert) {
                TextButton(
                    onClick = {
                        dialogState.result?.cancel()
                        onDismiss()
                    }
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}
