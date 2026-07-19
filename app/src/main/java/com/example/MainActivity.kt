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
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import android.graphics.Bitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.browser.ui.ShortcutInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
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
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

@Composable
fun DialogEdgeToEdge(isDarkTheme: Boolean) {
    val dialogView = LocalView.current
    val isDarkIcons = !isDarkTheme
    DisposableEffect(dialogView) {
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            val insetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            insetsController.isAppearanceLightStatusBars = isDarkIcons
            insetsController.isAppearanceLightNavigationBars = isDarkIcons
        }
        onDispose {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAppScreen(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tabThumbnails by viewModel.tabThumbnails.collectAsStateWithLifecycle()
    
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
    val isDarkTheme = isIncognito || isDark
    val primaryColor = if (isIncognito) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary
    val containerBg = if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val barColor = if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.surface
    val cardBg = if (isIncognito) Color(0xFF141414) else MaterialTheme.colorScheme.surfaceVariant
    val iconColor = if (isIncognito) Color(0xFFE0E0E0) else MaterialTheme.colorScheme.onSurfaceVariant

    // Manage status and navigation bar icons with WindowInsetsControllerCompat
    val window = (LocalContext.current as? Activity)?.window
    if (window != null) {
        val insetsController = remember(window) {
            WindowCompat.getInsetsController(window, window.decorView)
        }
        val isDarkIconsNeeded = !isDarkTheme
        SideEffect {
            insetsController.isAppearanceLightStatusBars = isDarkIconsNeeded
            insetsController.isAppearanceLightNavigationBars = isDarkIconsNeeded
        }
    }

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
                barColor = barColor,
                iconColor = iconColor,
                onBack = { viewModel.navigateBackInActiveTab() },
                onForward = { viewModel.navigateForwardInActiveTab() },
                onHome = { viewModel.loadUrlInActiveTab("about:blank") },
                onOpenBookmarks = { showBookmarksSheet = true },
                onOpenTabManager = {
                    viewModel.captureActiveTabThumbnail()
                    showTabManager = true
                },
                onOpenMenu = { showMenuOptions = true }
            )
        }
    ) { innerPadding ->
        val findInPageActive by viewModel.findInPageActive.collectAsStateWithLifecycle()
        val findInPageQuery by viewModel.findInPageQuery.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (findInPageActive) {
                FindInPageToolbar(
                    query = findInPageQuery,
                    onQueryChange = { viewModel.setFindInPageQuery(it) },
                    onNext = { viewModel.findNext(true) },
                    onPrev = { viewModel.findNext(false) },
                    onClose = { viewModel.setFindInPageActive(false) },
                    cardBg = cardBg,
                    isDark = isDark
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (activeTab != null) {
                    if (activeTab.url == "about:blank") {
                        // Browser Homepage with Quick Shortcuts and beautiful grid
                        BrowserHomepage(
                            viewModel = viewModel,
                            isIncognito = isIncognito,
                            isDark = isDark,
                            cardBg = cardBg,
                            onLoadUrl = { viewModel.loadUrlInActiveTab(it) },
                            onOpenBookmarks = { showBookmarksSheet = true },
                            onOpenHistory = { showHistorySheet = true }
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
            DialogEdgeToEdge(isDarkTheme = isDarkTheme)
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (isIncognito) Color(0xFF121212) else MaterialTheme.colorScheme.background
            ) {
                TabManagerSheetContent(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    tabThumbnails = tabThumbnails,
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

    // 2. Main Menu Bottom Sheet Overlay
    if (showMenuOptions) {
        Dialog(
            onDismissRequest = { showMenuOptions = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            DialogEdgeToEdge(isDarkTheme = isDarkTheme)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { showMenuOptions = false }
            ) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .offset(y = 120.dp) // Positioned a little below the half of the screen
                        .clickable(enabled = false) {} // Prevent click propagation to background Box
                        .clip(RoundedCornerShape(16.dp)),
                    color = if (isIncognito || isDark) Color(0xFF141414) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    MenuOptionsSheetContent(
                        activeTab = activeTab,
                        settings = settings,
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
                                MenuAction.TOGGLE_NIGHT_MODE -> viewModel.toggleNightMode()
                                MenuAction.TOGGLE_ADBLOCK -> viewModel.toggleAdBlock()
                                MenuAction.SAVE_OFFLINE -> viewModel.savePageArchive()
                                MenuAction.FIND_IN_PAGE -> viewModel.setFindInPageActive(true)
                            }
                        },
                        onDismiss = { showMenuOptions = false }
                    )
                }
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
            DialogEdgeToEdge(isDarkTheme = isDarkTheme)
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
            DialogEdgeToEdge(isDarkTheme = isDarkTheme)
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
            DialogEdgeToEdge(isDarkTheme = isDarkTheme)
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
                .height(42.dp)
                .padding(horizontal = 6.dp)
        ) {
            // 1. Left: Bookmark/Favorites icon (outline or filled depending on state)
            IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier.size(34.dp),
                enabled = activeTab?.url != "about:blank" && activeTab != null
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Bookmark Page",
                    tint = if (isBookmarked) primaryColor else if (isDark) Color(0xFFE0E0E0) else Color(0xFF5F6368),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 2. Center: Address Text Input Container Box (rounded, 32dp height, subtle grey background)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        color = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF1F3F4),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 10.dp)
            ) {
                // HTTPS Lock or Search icon indicator inside search box
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
                        .size(14.dp)
                )

                // Address Input text
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isDark) Color.White else Color.Black,
                        fontSize = 14.sp
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
                                    fontSize = 14.sp,
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
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Address Bar",
                            tint = if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 3. Right: Refresh/Stop Loading Button
            IconButton(
                onClick = { if (activeTab?.isLoading == true) onStop() else onRefresh() },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (activeTab?.isLoading == true) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (activeTab?.isLoading == true) "Stop Loading" else "Reload page",
                    tint = if (isDark) Color(0xFFE0E0E0) else Color(0xFF5F6368),
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
    barColor: Color,
    iconColor: Color,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenTabManager: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        color = barColor,
        tonalElevation = 0.dp,
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
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = if (activeTab?.canGoBack == true) iconColor else iconColor.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2. Forward button
            IconButton(
                onClick = onForward,
                enabled = activeTab?.canGoForward == true,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (activeTab?.canGoForward == true) iconColor else iconColor.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 3. Home Button (house outline)
            IconButton(
                onClick = onHome,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = "Home Page",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 4. Bookmarks Button (star outline)
            IconButton(
                onClick = onOpenBookmarks,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.StarBorder,
                    contentDescription = "Bookmarks",
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 5. Tabs Manager overlay toggler (square with number)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onOpenTabManager)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(1.5.dp, iconColor, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabsCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = iconColor,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        modifier = Modifier.offset(
                            x = if (tabsCount == 1) 0.5.dp else 0.dp,
                            y = (-0.5).dp
                        )
                    )
                }
            }

            // 6. Main settings menu toggler
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "More Options Menu",
                    tint = iconColor,
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
    viewModel: BrowserViewModel,
    isIncognito: Boolean,
    isDark: Boolean,
    cardBg: Color,
    onLoadUrl: (String) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val scrollState = rememberScrollState()
    val primaryColor = if (isIncognito) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary

    val containerBg = if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val textPrimary = if (isIncognito) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.onBackground
    val textSecondary = if (isIncognito) Color(0xFFB3B3B3) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(containerBg)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // Center visual icon without name branding
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = cardBg,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isIncognito) Icons.Default.Security else Icons.Outlined.Explore,
                contentDescription = "Logo",
                modifier = Modifier.size(24.dp),
                tint = primaryColor
            )
        }
    }
}

// --- Tab Manager Overlay Content ---
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
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
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
                        .height(180.dp) // Taller card for rich visualization
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = borderBrush,
                    colors = CardDefaults.cardColors(
                        containerColor = if (tab.isIncognito) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
                    ),
                    onClick = { onSelectTab(tab.id) }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (tab.isIncognito) Color(0xFF2C2C2C) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tab.isIncognito) Icons.Default.Security else Icons.Outlined.Explore,
                                    contentDescription = null,
                                    tint = if (tab.isIncognito) Color(0xFF80CBC4) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (tab.isIncognito) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = if (tab.isIncognito) Color.LightGray else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        // Divider
                        HorizontalDivider(
                            color = if (tab.isIncognito) Color(0xFF333333) else MaterialTheme.colorScheme.outlineVariant,
                            thickness = 1.dp
                        )

                        // Visual Preview Area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(if (tab.isIncognito) Color(0xFF141414) else MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            val bitmap = tabThumbnails[tab.id]
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Tab preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                TabPreviewPlaceholder(tab = tab, isDark = tab.isIncognito)
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
    settings: BrowserSettings,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    val isIncognito = activeTab?.isIncognito ?: false
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val primaryColor = if (isIncognito) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary
    val containerBg = if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val cardBg = if (isIncognito) Color(0xFF141414) else MaterialTheme.colorScheme.surfaceVariant
    val textPrimary = if (isIncognito) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.onBackground
    val textSecondary = if (isIncognito) Color(0xFFB3B3B3) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerBg)
            .navigationBarsPadding()
            .padding(vertical = 16.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Browser Options",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textPrimary
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Options",
                    tint = textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        val menuItems = listOf(
            GridMenuAction("New Standard Tab", Icons.Default.Add, MenuAction.NEW_TAB, false),
            GridMenuAction("New Private Tab", Icons.Default.Security, MenuAction.NEW_INCOGNITO, false),
            GridMenuAction("Open Bookmarks", Icons.Default.Bookmarks, MenuAction.BOOKMARKS, false),
            GridMenuAction("Open History Logs", Icons.Default.History, MenuAction.HISTORY, false),
            GridMenuAction(
                if (activeTab?.isDesktopMode == true) "Mobile View" else "Desktop View",
                if (activeTab?.isDesktopMode == true) Icons.Default.StayCurrentPortrait else Icons.Default.Laptop,
                MenuAction.TOGGLE_DESKTOP,
                activeTab?.isDesktopMode == true
            ),
            GridMenuAction(
                "Night Mode",
                Icons.Default.DarkMode,
                MenuAction.TOGGLE_NIGHT_MODE,
                isDark
            ),
            GridMenuAction(
                "Adblocker",
                Icons.Default.Shield,
                MenuAction.TOGGLE_ADBLOCK,
                settings.isAdBlockEnabled
            ),
            GridMenuAction("Save Offline", Icons.Default.OfflineShare, MenuAction.SAVE_OFFLINE, false),
            GridMenuAction("Find in Page", Icons.Default.Search, MenuAction.FIND_IN_PAGE, false),
            GridMenuAction("Undo Closed Tab", Icons.Default.Restore, MenuAction.RESTORE_TAB, false),
            GridMenuAction("Settings", Icons.Default.Settings, MenuAction.SETTINGS, false)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        ) {
            lazyItems(menuItems) { item ->
                val cardBorder = if (item.isActive) {
                    BorderStroke(1.5.dp, primaryColor)
                } else {
                    BorderStroke(1.dp, if (isDark) Color(0xFF242424) else Color(0xFFE2E2E2))
                }

                Card(
                    onClick = { onAction(item.action) },
                    modifier = Modifier
                        .width(76.dp)
                        .height(78.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = cardBorder,
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.isActive && isDark) Color(0xFF1C2C3C) else cardBg
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (item.isActive) primaryColor else textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 11.sp
                            ),
                            color = textPrimary,
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
    NEW_TAB, NEW_INCOGNITO, BOOKMARKS, HISTORY, TOGGLE_DESKTOP, RESTORE_TAB, SETTINGS,
    TOGGLE_NIGHT_MODE, TOGGLE_ADBLOCK, SAVE_OFFLINE, FIND_IN_PAGE
}

data class GridMenuAction(
    val label: String,
    val icon: ImageVector,
    val action: MenuAction,
    val isActive: Boolean = false
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
            .systemBarsPadding()
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
            .systemBarsPadding()
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
            .systemBarsPadding()
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

// --- Find in Page Toolbar ---
@Composable
fun FindInPageToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    cardBg: Color,
    isDark: Boolean
) {
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textPrimary, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Find in page...", color = textSecondary, fontSize = 14.sp)
                    }
                    innerTextField()
                }
            }
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = "Previous Match", tint = textSecondary, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Next Match", tint = textSecondary, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close Find", tint = textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun TabPreviewPlaceholder(tab: BrowserTab, isDark: Boolean) {
    val gradientColors = if (isDark) {
        listOf(Color(0xFF2C3E50), Color(0xFF000000))
    } else {
        listOf(
            Color(0xFFE0F7FA),
            Color(0xFFE8EAF6)
        )
    }
    
    val accentColor = if (tab.isIncognito) Color(0xFF80CBC4) else MaterialTheme.colorScheme.primary
    val initial = if (tab.title.isNotBlank()) tab.title.take(1).uppercase() else "W"
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant large circular letter logo representing the site
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isDark) Color(0xFF212121) else Color.White,
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.5f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = accentColor
                )
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Mock browser search box
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(12.dp)
                    .background(
                        color = if (isDark) Color(0xFF333333) else Color(0xFFECEFF1),
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Mock visual page skeleton bars
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(6.dp)
                        .background(
                            color = (if (isDark) Color(0xFF333333) else Color(0xFFECEFF1)).copy(alpha = 0.7f),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
                Box(
                    modifier = Modifier
                        .width(25.dp)
                        .height(6.dp)
                        .background(
                            color = (if (isDark) Color(0xFF333333) else Color(0xFFECEFF1)).copy(alpha = 0.7f),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}
