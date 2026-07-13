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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.foundation.text.BasicTextField
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
import com.example.browser.settings.ThemeMode
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

            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val useDynamicColor = settings.useDynamicColor

            MyApplicationTheme(
                darkTheme = darkTheme,
                dynamicColor = useDynamicColor
            ) {
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

    // Theme Color Swaps based on Private/Incognito Mode & Theme settings
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val primaryColor = if (isIncognito) Color(0xFF3EA6FF) else if (isDark) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary
    val containerBg = if (isIncognito || isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val barColor = if (isIncognito || isDark) Color(0xFF181818) else Color(0xFFFFFFFF)
    val cardBg = if (isIncognito || isDark) Color(0xFF1B1B1B) else Color(0xFFF5F5F5)

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
                        isDark = isDark,
                        cardBg = cardBg,
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

    // --- Fullscreen Overlays ---
    
    // 1. Tab Manager Fullscreen Overlay
    if (showTabManager) {
        Dialog(
            onDismissRequest = { showTabManager = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
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
                    onRestoreClosedTab = { viewModel.restoreLastClosedTab() },
                    onDismiss = { showTabManager = false }
                )
            }
        }
    }

    // 2. Main Menu Fullscreen Overlay
    if (showMenuOptions) {
        Dialog(
            onDismissRequest = { showMenuOptions = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
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
                    },
                    onDismiss = { showMenuOptions = false }
                )
            }
        }
    }

    // 3. History Logs Fullscreen Overlay
    if (showHistorySheet) {
        Dialog(
            onDismissRequest = { showHistorySheet = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
            ) {
                HistorySheetContent(
                    historyFlow = viewModel.history,
                    onUrlClick = {
                        viewModel.loadUrlInActiveTab(it)
                        showHistorySheet = false
                    },
                    onDeleteItem = { viewModel.deleteHistoryItem(it) },
                    onClearAll = { viewModel.clearAllHistory() },
                    onDismiss = { showHistorySheet = false }
                )
            }
        }
    }

    // 4. Bookmarks Fullscreen Overlay
    if (showBookmarksSheet) {
        Dialog(
            onDismissRequest = { showBookmarksSheet = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
            ) {
                BookmarksSheetContent(
                    bookmarksFlow = viewModel.bookmarks,
                    onUrlClick = {
                        viewModel.loadUrlInActiveTab(it)
                        showBookmarksSheet = false
                    },
                    onDeleteItem = { viewModel.deleteBookmarkItem(it) },
                    onDismiss = { showBookmarksSheet = false }
                )
            }
        }
    }

    // 5. Settings Fullscreen Overlay
    if (showSettingsSheet) {
        Dialog(
            onDismissRequest = { showSettingsSheet = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
            ) {
                SettingsSheetContent(
                    settings = settings,
                    onUpdateSettings = { viewModel.setSettings(it) },
                    onDismiss = { showSettingsSheet = false }
                )
            }
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

    val isDark = isSystemInDarkTheme() || isIncognito

    Column(
        modifier = Modifier
            .background(barColor)
            .statusBarsPadding()
            .fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp)
        ) {
            // Address Text Input Container Box
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        color = if (isDark) Color(0xFF242424) else Color(0xFFF1F3F4),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 8.dp)
            ) {
                // HTTPS Lock or Search icon indicator
                Icon(
                    imageVector = when {
                        activeTab?.url == "about:blank" -> Icons.Default.Search
                        activeTab?.url?.startsWith("https://") == true -> Icons.Default.Lock
                        else -> Icons.Default.Language
                    },
                    contentDescription = "Security State Indicator",
                    tint = if (activeTab?.url?.startsWith("https://") == true) Color(0xFF4CAF50) else if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp)
                )

                // Address Input text
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isDark) Color.White else Color.Black
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
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (urlInput.isEmpty()) {
                                Text(
                                    text = "Search or type URL",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // Clear button inside search box
                if (urlInput.isNotBlank()) {
                    IconButton(
                        onClick = { urlInput = "" },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Address Bar",
                            tint = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Quick Bookmark toggle
            if (activeTab?.url != "about:blank" && activeTab != null) {
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmark Page",
                        tint = if (isBookmarked) primaryColor else if (isDark) Color(0xFFFFFFFF) else Color(0xFF191C1C),
                        modifier = Modifier.size(20.dp)
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
                    tint = if (isDark) Color(0xFFFFFFFF) else Color(0xFF191C1C),
                    modifier = Modifier.size(20.dp)
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
                    .height(2.dp),
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
        tonalElevation = 1.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Back button
            IconButton(
                onClick = onBack,
                enabled = activeTab?.canGoBack == true,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = if (activeTab?.canGoBack == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2. Forward button
            IconButton(
                onClick = onForward,
                enabled = activeTab?.canGoForward == true,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (activeTab?.canGoForward == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 3. Home / Default empty page
            IconButton(
                onClick = onHome,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home Page",
                    modifier = Modifier.size(20.dp)
                )
            }

            // 4. Tab Manager overlay toggler
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onOpenTabManager)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabsCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 5. Main settings menu toggler
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "More Options Menu",
                    modifier = Modifier.size(20.dp)
                )
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
    isDark: Boolean,
    cardBg: Color,
    onLoadUrl: (String) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    bookmarks: List<BookmarkItem>
) {
    val scrollState = rememberScrollState()
    val primaryColor = if (isIncognito || isDark) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary

    val bgBrush = Brush.verticalGradient(
        listOf(
            if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF),
            if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Single Row elegant centered Title Logo
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1F3F4),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isIncognito) Icons.Default.Security else Icons.Default.Explore,
                    contentDescription = "Kivo Logo",
                    modifier = Modifier.size(22.dp),
                    tint = primaryColor
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isIncognito) "Incognito" else "Kivo",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = if (isDark) Color.White else Color.Black
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Center search/URL box right on the homepage
        var homeQuery by remember { mutableStateOf("") }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    color = if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1F3F4),
                    shape = RoundedCornerShape(22.dp)
                )
                .border(
                    width = 1.dp,
                    color = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = homeQuery,
                onValueChange = { homeQuery = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (isDark) Color.White else Color.Black
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (homeQuery.isNotBlank()) {
                            onLoadUrl(homeQuery)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (homeQuery.isEmpty()) {
                            Text(
                                text = "Search or type URL",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (homeQuery.isNotBlank()) {
                IconButton(
                    onClick = { homeQuery = "" },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Beautiful, tiny, round shortcut buttons in 4 columns
        val shortcuts = listOf(
            ShortcutItem("Google", "https://www.google.com", Icons.Default.Search, Color(0xFF4285F4)),
            ShortcutItem("YouTube", "https://www.youtube.com", Icons.Default.PlayArrow, Color(0xFFFF0000)),
            ShortcutItem("Reddit", "https://www.reddit.com", Icons.Default.Forum, Color(0xFFFF4500)),
            ShortcutItem("GitHub", "https://www.github.com", Icons.Default.Code, Color(0xFF212121))
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(shortcuts) { shortcut ->
                Box(
                    modifier = Modifier
                        .clickable { onLoadUrl(shortcut.url) }
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = if (isDark) Color(0xFF1B1B1B) else Color(0xFFF5F5F5),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = shortcut.icon,
                                contentDescription = shortcut.label,
                                tint = if (isDark) Color.White else shortcut.color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = shortcut.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (isDark) Color(0xFF9E9E9E) else Color(0xFF555555),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Bookmarks & History low-profile button row with lots of breathing room
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onOpenBookmarks,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = primaryColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Bookmarks",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isDark) Color.White else Color(0xFF555555)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = primaryColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "History",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isDark) Color.White else Color(0xFF555555)
                )
            }
        }

        if (bookmarks.isNotEmpty() && !isIncognito) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "RECENT SAVED SITES",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
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
                        containerColor = if (isDark) Color(0xFF1B1B1B) else Color(0xFFF9F9F9)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = b.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                color = if (isDark) Color.White else Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = b.url,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
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
    onRestoreClosedTab: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Tab Manager")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Browser Tabs (${tabs.size})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
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
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Options")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Browser Options",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

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
            modifier = Modifier.fillMaxWidth().weight(1f),
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
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
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
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close History")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "History Logs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
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
    onDeleteItem: (BookmarkItem) -> Unit,
    onDismiss: () -> Unit
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
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Bookmarks")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Bookmarks Manager",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

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
    onUpdateSettings: (BrowserSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchEngineMenuExpanded by remember { mutableStateOf(false) }
    var themeModeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Settings")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Browser Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }

        // Theme Selection Preference Row
        ListItem(
            headlineContent = { Text("App Theme") },
            supportingContent = { Text(settings.themeMode.displayName) },
            leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) },
            trailingContent = {
                Box {
                    Button(onClick = { themeModeExpanded = true }) {
                        Text("Select")
                    }
                    DropdownMenu(
                        expanded = themeModeExpanded,
                        onDismissRequest = { themeModeExpanded = false }
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    onUpdateSettings(settings.copy(themeMode = mode))
                                    themeModeExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        // Dynamic Color (Android 12+) toggle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ListItem(
                headlineContent = { Text("Material You Dynamic Color") },
                supportingContent = { Text("Apply wallpaper colors to the app UI") },
                leadingContent = { Icon(Icons.Default.ColorLens, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = settings.useDynamicColor,
                        onCheckedChange = { onUpdateSettings(settings.copy(useDynamicColor = it)) }
                    )
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

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
