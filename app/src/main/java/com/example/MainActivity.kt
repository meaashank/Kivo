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
import android.webkit.URLUtil
import android.webkit.WebView
import android.print.PrintManager
import android.print.PrintAttributes
import android.widget.Toast
import com.example.browser.download.DownloadEngine
import com.example.browser.download.DownloadManagerSheet
import com.example.browser.download.DownloadPreferences
import com.example.browser.download.DownloadRequest
import com.example.browser.download.DownloadSettingsSheet
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
import com.example.browser.transfer.FileTransferHubSheet
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
    var showDownloadsSheet by remember { mutableStateOf(false) }
    var showDownloadSettingsSheet by remember { mutableStateOf(false) }
    var activeDownloadPromptRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    val scope = rememberCoroutineScope()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {}
            val path = it.path ?: it.toString()
            DownloadPreferences(context).downloadFolder = path
            Toast.makeText(context, "Download directory set", Toast.LENGTH_SHORT).show()
        }
    }

    val downloadPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            Toast.makeText(context, "Storage permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Storage access is required to save downloads", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.downloadRequestEvent.collect { req ->
            val prefs = DownloadPreferences(context)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val readOk = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                val writeOk = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (!readOk || !writeOk) {
                    downloadPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                    Toast.makeText(context, "Storage access is required to save downloads", Toast.LENGTH_LONG).show()
                    return@collect
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    downloadPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }

            if (prefs.askWhereToSave) {
                activeDownloadPromptRequest = req
            } else {
                val engine = DownloadEngine.getInstance(context)
                engine.enqueueDownload(
                    url = req.url,
                    userAgent = req.userAgent,
                    contentDisposition = req.contentDisposition,
                    mimeType = req.mimeType
                )
                Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Theme Color Swaps based on Private/Incognito Mode & Theme settings
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val isOnHomepage = activeTab?.url == "about:blank"
    val isDarkTheme = isIncognito || isDark || isOnHomepage
    val primaryColor = if (isIncognito) Color(0xFF3EA6FF) else MaterialTheme.colorScheme.primary
    val containerBg = if (isOnHomepage) Color(0xFF000000) else if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.background
    val barColor = if (isOnHomepage) Color(0xFF000000) else if (isIncognito) Color(0xFF000000) else MaterialTheme.colorScheme.surface
    val cardBg = if (isOnHomepage) Color(0xFF121212) else if (isIncognito) Color(0xFF141414) else MaterialTheme.colorScheme.surfaceVariant
    val iconColor = if (isOnHomepage) Color.White else if (isIncognito) Color(0xFFE0E0E0) else MaterialTheme.colorScheme.onSurfaceVariant

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
            if (!isOnHomepage) {
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
            }
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
                color = Color(0xFF000000) // Pure AMOLED Black!
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
                    onClearAllTabs = { isIncognito -> viewModel.closeAllTabsOfCategory(isIncognito) },
                    onDismiss = { showTabManager = false }
                )
            }
        }
    }

    // 2. Main Menu Bottom Sheet Overlay
    if (showMenuOptions) {
        val context = LocalContext.current
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
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showMenuOptions = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}, // Prevent click propagation to background Box
                    color = Color(0xFF000000), // AMOLED black!
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    MenuOptionsSheetContent(
                        activeTab = activeTab,
                        settings = settings,
                        viewModel = viewModel,
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
                                MenuAction.DOWNLOADS -> {
                                    showDownloadsSheet = true
                                }
                                MenuAction.ADD_BOOKMARK -> {
                                    viewModel.toggleBookmarkActiveTab()
                                    Toast.makeText(context, "Added / Removed from Bookmarks", Toast.LENGTH_SHORT).show()
                                }
                                MenuAction.TRANSLATE -> {
                                    Toast.makeText(context, "Translating page...", Toast.LENGTH_SHORT).show()
                                }
                                MenuAction.READING_MODE -> {
                                    viewModel.toggleReadingMode()
                                }
                                MenuAction.TEXT_SIZE -> {
                                    Toast.makeText(context, "Text size: 100% (Adjust in Settings -> Accessibility)", Toast.LENGTH_LONG).show()
                                }
                                MenuAction.SHARE -> {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, activeTab?.url ?: "about:blank")
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share URL"))
                                }
                                MenuAction.PRINT_PDF -> {
                                    try {
                                        val webView = activeTab?.id?.let { viewModel.getOrCreateWebView(it, context) }
                                        if (webView != null) {
                                            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                                            val printAdapter = webView.createPrintDocumentAdapter("Document")
                                            printManager?.print("Kivo Page", printAdapter, PrintAttributes.Builder().build())
                                        } else {
                                            Toast.makeText(context, "No active web page to print", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Print failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
                    onDismiss = { showSettingsSheet = false },
                    onOpenDownloads = { showDownloadsSheet = true },
                    onChangeDownloadFolder = { folderPickerLauncher.launch(null) }
                )
            }
        }
    }

    // 6. Downloads Sheet Overlay
    if (showDownloadsSheet) {
        DownloadManagerSheet(
            onDismiss = { showDownloadsSheet = false },
            onOpenSettings = {
                showDownloadsSheet = false
                showDownloadSettingsSheet = true
            },
            onRequestStoragePermission = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    downloadPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            }
        )
    }

    // 7. Download Settings Sheet
    if (showDownloadSettingsSheet) {
        DownloadSettingsSheet(
            onDismiss = { showDownloadSettingsSheet = false },
            onChangeFolderClick = {
                folderPickerLauncher.launch(null)
            }
        )
    }

    // 8. Ask Where to Save Prompt Dialog
    activeDownloadPromptRequest?.let { req ->
        var customName by remember {
            mutableStateOf(
                URLUtil.guessFileName(req.url, req.contentDisposition, req.mimeType)
            )
        }
        val prefs = remember { DownloadPreferences(context) }

        AlertDialog(
            onDismissRequest = { activeDownloadPromptRequest = null },
            containerColor = Color(0xFF1E1E24),
            titleContentColor = Color.White,
            title = { Text("Choose Download Details") },
            text = {
                Column {
                    Text("File Name:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF14FFC2),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Folder Path:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(prefs.downloadFolder, fontSize = 12.sp, color = Color(0xFF14FFC2))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val engine = DownloadEngine.getInstance(context)
                            engine.enqueueDownload(
                                url = req.url,
                                userAgent = req.userAgent,
                                contentDisposition = req.contentDisposition,
                                mimeType = req.mimeType,
                                customFileName = customName
                            )
                        }
                        activeDownloadPromptRequest = null
                        Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Download", color = Color(0xFF14FFC2), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDownloadPromptRequest = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
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
                    .size(44.dp)
                    .clickable(onClick = onOpenTabManager)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF1F1F1F), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabsCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White
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
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val tab = tabs.find { it.id == tabId }
    val isLoading = tab?.isLoading ?: false

    AndroidView(
        factory = { ctx ->
            androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
                setColorSchemeColors(
                    android.graphics.Color.parseColor("#3EA6FF"),
                    android.graphics.Color.parseColor("#14FFC2")
                )
                setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#222222"))
                setOnRefreshListener {
                    webView.reload()
                }
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                addView(
                    webView,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { swipeLayout ->
            swipeLayout.isRefreshing = isLoading
        }
    )
}

// --- Browser Custom Native M3 Homepage Grid Item ---
sealed class HomepageGridItem {
    data class Shortcut(val info: ShortcutInfo) : HomepageGridItem()
    object FileTransfer : HomepageGridItem()
    object Add : HomepageGridItem()
}

@Composable
fun ShortcutLogo(label: String, url: String) {
    val cleanUrl = url.lowercase()
    when {
        cleanUrl.contains("google.com") -> {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "G",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = Color(0xFF4285F4)
                    )
                )
            }
        }
        cleanUrl.contains("youtube.com") -> {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "YouTube",
                tint = Color(0xFFFF0000),
                modifier = Modifier.size(24.dp)
            )
        }
        cleanUrl.contains("facebook.com") -> {
            Text(
                text = "f",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color(0xFF1877F2)
                )
            )
        }
        cleanUrl.contains("instagram.com") -> {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFFF9ED69), Color(0xFFF08A5D), Color(0xFFB83B5E), Color(0xFF6A2C70))
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(1.5.dp, Color.White, RoundedCornerShape(3.dp))
                )
            }
        }
        cleanUrl.contains("x.com") || cleanUrl.contains("twitter.com") -> {
            Text(
                text = "𝕏",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White
                )
            )
        }
        else -> {
            val firstLetter = label.firstOrNull()?.uppercase() ?: "W"
            Text(
                text = firstLetter,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
fun ShortcutIconItem(
    shortcut: ShortcutInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF121212), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF1F1F1F), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            ShortcutLogo(label = shortcut.label, url = shortcut.url)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = shortcut.label,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun FileTransferShortcutItem(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF121212), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF1F1F1F), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "F",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color(0xFFFF5252)
                )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "file transfer",
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AddShortcutItem(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF121212), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF1F1F1F), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Add",
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
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
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()

    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var showShortcutOptionsDialog by remember { mutableStateOf(false) }
    var showFileTransferDialog by remember { mutableStateOf(false) }
    var selectedShortcutForAction by remember { mutableStateOf<ShortcutInfo?>(null) }

    // Forms
    var newShortcutLabel by remember { mutableStateOf("") }
    var newShortcutUrl by remember { mutableStateOf("") }

    var editShortcutLabel by remember { mutableStateOf("") }
    var editShortcutUrl by remember { mutableStateOf("") }
    var showEditFieldsInOptions by remember { mutableStateOf(false) }

    val gridItems = remember(shortcuts) {
        shortcuts.map { HomepageGridItem.Shortcut(it) } +
                HomepageGridItem.FileTransfer +
                HomepageGridItem.Add
    }
    val chunkedGrid = gridItems.chunked(5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // 1. TOP BAR (HEADER) - exact same layout metrics as browsing toolbar
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(42.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bookmarks / Star icon on left
            IconButton(
                onClick = onOpenBookmarks,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Bookmarks",
                    tint = Color(0xFFE0E0E0),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Search/URL bar in center (rounded)
            var searchInput by remember { mutableStateOf("") }
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        color = Color(0xFF1A1A1A),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (searchInput.isNotBlank()) {
                                onLoadUrl(searchInput)
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchInput.isEmpty()) {
                                Text(
                                    text = "Search or type URL",
                                    style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 14.sp)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (searchInput.isNotEmpty()) {
                    IconButton(
                        onClick = { searchInput = "" },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = Color(0xFF9E9E9E),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Refresh button
            IconButton(
                onClick = { viewModel.refreshActiveTab() },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = Color(0xFFE0E0E0),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Horizontal line under header
        HorizontalDivider(color = Color(0xFF121212), thickness = 1.dp)

        // 2. MAIN SCROLLABLE AREA
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // SHORTCUTS SECTION
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                chunkedGrid.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top
                    ) {
                        rowItems.forEach { item ->
                            when (item) {
                                is HomepageGridItem.Shortcut -> {
                                    ShortcutIconItem(
                                        shortcut = item.info,
                                        onClick = { onLoadUrl(item.info.url) },
                                        onLongClick = {
                                            selectedShortcutForAction = item.info
                                            editShortcutLabel = item.info.label
                                            editShortcutUrl = item.info.url
                                            showEditFieldsInOptions = false
                                            showShortcutOptionsDialog = true
                                        }
                                    )
                                }
                                HomepageGridItem.FileTransfer -> {
                                    FileTransferShortcutItem(onClick = { showFileTransferDialog = true })
                                }
                                HomepageGridItem.Add -> {
                                    AddShortcutItem(onClick = {
                                        newShortcutLabel = ""
                                        newShortcutUrl = ""
                                        showAddShortcutDialog = true
                                    })
                                }
                            }
                        }
                        if (rowItems.size < 5) {
                            val emptyCount = 5 - rowItems.size
                            repeat(emptyCount) {
                                Spacer(modifier = Modifier.width(56.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS ---

    // 1. Add Shortcut Dialog
    if (showAddShortcutDialog) {
        AlertDialog(
            onDismissRequest = { showAddShortcutDialog = false },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text(
                    text = "Add Shortcut",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newShortcutLabel,
                        onValueChange = { newShortcutLabel = it },
                        label = { Text("Label", color = Color(0xFFA1A1AA)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3EA6FF),
                            unfocusedBorderColor = Color(0xFF1F1F1F),
                            focusedContainerColor = Color(0xFF000000),
                            unfocusedContainerColor = Color(0xFF000000),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newShortcutUrl,
                        onValueChange = { newShortcutUrl = it },
                        label = { Text("URL", color = Color(0xFFA1A1AA)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF3EA6FF),
                            unfocusedBorderColor = Color(0xFF1F1F1F),
                            focusedContainerColor = Color(0xFF000000),
                            unfocusedContainerColor = Color(0xFF000000),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newShortcutLabel.isNotBlank() && newShortcutUrl.isNotBlank()) {
                            viewModel.addCustomShortcut(newShortcutLabel, newShortcutUrl)
                            showAddShortcutDialog = false
                        }
                    }
                ) {
                    Text("Add", color = Color(0xFF3EA6FF), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddShortcutDialog = false }) {
                    Text("Cancel", color = Color(0xFFA1A1AA))
                }
            }
        )
    }

    // 2. Shortcut Options Dialog
    if (showShortcutOptionsDialog && selectedShortcutForAction != null) {
        val shortcut = selectedShortcutForAction!!
        AlertDialog(
            onDismissRequest = { showShortcutOptionsDialog = false },
            containerColor = Color(0xFF121212),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text(
                    text = if (showEditFieldsInOptions) "Edit Shortcut" else shortcut.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                if (showEditFieldsInOptions) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = editShortcutLabel,
                            onValueChange = { editShortcutLabel = it },
                            label = { Text("Label", color = Color(0xFFA1A1AA)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3EA6FF),
                                unfocusedBorderColor = Color(0xFF1F1F1F),
                                focusedContainerColor = Color(0xFF000000),
                                unfocusedContainerColor = Color(0xFF000000),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = editShortcutUrl,
                            onValueChange = { editShortcutUrl = it },
                            label = { Text("URL", color = Color(0xFFA1A1AA)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3EA6FF),
                                unfocusedBorderColor = Color(0xFF1F1F1F),
                                focusedContainerColor = Color(0xFF000000),
                                unfocusedContainerColor = Color(0xFF000000),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = "Choose an action for this shortcut:\n${shortcut.url}",
                        fontSize = 14.sp,
                        color = Color(0xFFA1A1AA)
                    )
                }
            },
            confirmButton = {
                if (showEditFieldsInOptions) {
                    TextButton(
                        onClick = {
                            if (editShortcutLabel.isNotBlank() && editShortcutUrl.isNotBlank()) {
                                viewModel.deleteShortcut(shortcut)
                                viewModel.addCustomShortcut(editShortcutLabel, editShortcutUrl)
                                showShortcutOptionsDialog = false
                            }
                        }
                    ) {
                        Text("Save", color = Color(0xFF3EA6FF), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                showEditFieldsInOptions = true
                            }
                        ) {
                            Text("Edit", color = Color(0xFF3EA6FF))
                        }
                        TextButton(
                            onClick = {
                                viewModel.deleteShortcut(shortcut)
                                showShortcutOptionsDialog = false
                            }
                        ) {
                            Text("Remove", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showShortcutOptionsDialog = false }
                ) {
                    Text("Close", color = Color(0xFFA1A1AA))
                }
            }
        )
    }

    // 3. Production Local Wi-Fi File Sharing Hub
    if (showFileTransferDialog) {
        FileTransferHubSheet(
            onDismiss = { showFileTransferDialog = false }
        )
    }
}

enum class TabCategory {
    NORMAL, INCOGNITO
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
    onClearAllTabs: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember {
        mutableStateOf(
            if (activeTabId?.let { id -> tabs.find { it.id == id }?.isIncognito } == true) {
                TabCategory.INCOGNITO
            } else {
                TabCategory.NORMAL
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .systemBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Back button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // Center: Switches
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Normal Tabs Switch
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { selectedCategory = TabCategory.NORMAL }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mood,
                        contentDescription = "Standard Tabs",
                        tint = if (selectedCategory == TabCategory.NORMAL) Color(0xFF3EA6FF) else Color(0xFFE6E6E6),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .background(
                                color = if (selectedCategory == TabCategory.NORMAL) Color(0xFF3EA6FF) else Color.Transparent,
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Incognito Tabs Switch
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { selectedCategory = TabCategory.INCOGNITO }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Private Tabs",
                        tint = if (selectedCategory == TabCategory.INCOGNITO) Color(0xFF3EA6FF) else Color(0xFFE6E6E6),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .background(
                                color = if (selectedCategory == TabCategory.INCOGNITO) Color(0xFF3EA6FF) else Color.Transparent,
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
            }

            // Right: MoreVert options
            var showMoreMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    modifier = Modifier.background(Color(0xFF141414))
                ) {
                    DropdownMenuItem(
                        text = { Text("Restore closed tab", color = Color.White) },
                        onClick = {
                            showMoreMenu = false
                            onRestoreClosedTab()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Close all tabs", color = Color.White) },
                        onClick = {
                            showMoreMenu = false
                            onClearAllTabs(selectedCategory == TabCategory.INCOGNITO)
                        }
                    )
                }
            }
        }

        // Thumbnail Grid Area
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            val columns = when {
                maxWidth < 400.dp -> 2
                maxWidth < 600.dp -> 2
                maxWidth < 900.dp -> 3
                else -> 4
            }
            
            val filteredTabs = tabs.filter { it.isIncognito == (selectedCategory == TabCategory.INCOGNITO) }
            
            if (filteredTabs.isEmpty()) {
                // Empty State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Explore,
                        contentDescription = "No tabs yet",
                        tint = Color(0xFFE6E6E6),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No tabs yet",
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Let's explore the web",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color(0xFFA1A1AA)
                        )
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    gridItems(filteredTabs) { tab ->
                        val isActive = tab.id == activeTabId
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTab(tab.id) }
                        ) {
                            // Thumbnail Wrapper
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF141414))
                                    .let {
                                        if (isActive) {
                                            it.border(2.dp, Color(0xFF3EA6FF), RoundedCornerShape(12.dp))
                                        } else {
                                            it
                                        }
                                    },
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
                                    TabPreviewPlaceholder(tab = tab, isDark = true)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            // Bottom Title Row below Thumbnail
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
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
                                        tint = if (isActive) Color(0xFF3EA6FF) else Color(0xFFA1A1AA),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tab.title,
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = if (isActive) Color(0xFF3EA6FF) else Color.White
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onCloseTab(tab.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        tint = Color(0xFFA1A1AA),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Bar
        HorizontalDivider(
            color = Color(0xFF1A1A1A),
            thickness = 1.dp
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Delete all button
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        onClearAllTabs(selectedCategory == TabCategory.INCOGNITO)
                        Toast.makeText(context, "All ${if (selectedCategory == TabCategory.INCOGNITO) "private" else "standard"} tabs closed", Toast.LENGTH_SHORT).show()
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete all",
                    tint = Color(0xFFE6E6E6),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Delete all",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                )
            }
            
            // Vertical Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color(0xFF1A1A1A))
            )
            
            // New tab button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        if (selectedCategory == TabCategory.NORMAL) {
                            onAddTab()
                        } else {
                            onAddIncognitoTab()
                        }
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "New tab",
                    tint = Color(0xFFE6E6E6),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New tab",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                )
            }
        }
    }
}

// --- Drawer Action List Content ---
@Composable
fun RowScope.MenuItemButton(
    label: String,
    icon: ImageVector,
    isActive: Boolean = false,
    activeColor: Color = Color(0xFF3EA6FF),
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) activeColor else Color(0xFFE6E6E6),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MenuOptionsSheetContent(
    activeTab: BrowserTab?,
    settings: BrowserSettings,
    viewModel: BrowserViewModel,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    val isIncognito = activeTab?.isIncognito ?: false
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val isReadingModeActive by viewModel.isReadingModeActive.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF000000))
            .navigationBarsPadding()
            .padding(
                top = 10.dp,
                bottom = 16.dp,
                start = 16.dp,
                end = 16.dp
            )
    ) {
        // Subtle drag handle at top
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .background(Color(0xFF2F2F2F), RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Row 1 (Section 1): History, Downloads, New tab, Incognito tab
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuItemButton(
                label = "History",
                icon = Icons.Outlined.History,
                onClick = { onAction(MenuAction.HISTORY) }
            )
            MenuItemButton(
                label = "Downloads",
                icon = Icons.Outlined.Download,
                onClick = { onAction(MenuAction.DOWNLOADS) }
            )
            MenuItemButton(
                label = "New tab",
                icon = Icons.Outlined.AddBox,
                onClick = { onAction(MenuAction.NEW_TAB) }
            )
            MenuItemButton(
                label = "Incognito tab",
                icon = Icons.Outlined.Security,
                onClick = { onAction(MenuAction.NEW_INCOGNITO) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp)) // Row spacing: 24dp

        // Row 2 (Section 2): Bookmarks, Add bookmark, Find in page, Translate
        val isBookmarked by activeTab?.url?.let { viewModel.isBookmarked(it).collectAsStateWithLifecycle(false) } ?: remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuItemButton(
                label = "Bookmarks",
                icon = Icons.Outlined.Bookmarks,
                onClick = { onAction(MenuAction.BOOKMARKS) }
            )
            MenuItemButton(
                label = "Add bookmark",
                icon = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                isActive = isBookmarked,
                onClick = { onAction(MenuAction.ADD_BOOKMARK) }
            )
            MenuItemButton(
                label = "Find in page",
                icon = Icons.Outlined.Search,
                onClick = { onAction(MenuAction.FIND_IN_PAGE) }
            )
            MenuItemButton(
                label = "Translate",
                icon = Icons.Outlined.Translate,
                onClick = { onAction(MenuAction.TRANSLATE) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp)) // Row spacing: 24dp

        // Row 3 (Section 3): Desktop site, Dark mode, Reading mode, Text size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuItemButton(
                label = "Desktop site",
                icon = Icons.Outlined.Laptop,
                isActive = activeTab?.isDesktopMode == true,
                onClick = { onAction(MenuAction.TOGGLE_DESKTOP) }
            )
            MenuItemButton(
                label = "Dark mode",
                icon = Icons.Outlined.DarkMode,
                isActive = isDark,
                onClick = { onAction(MenuAction.TOGGLE_NIGHT_MODE) }
            )
            MenuItemButton(
                label = "Reading mode",
                icon = Icons.Outlined.Description,
                isActive = isReadingModeActive,
                onClick = { onAction(MenuAction.READING_MODE) }
            )
            MenuItemButton(
                label = "Text size",
                icon = Icons.Outlined.TextFields,
                onClick = { onAction(MenuAction.TEXT_SIZE) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp)) // Row spacing: 24dp

        // Row 4 (Section 4): Share, Save page, Print / PDF, Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuItemButton(
                label = "Share",
                icon = Icons.Outlined.Share,
                onClick = { onAction(MenuAction.SHARE) }
            )
            MenuItemButton(
                label = "Save page",
                icon = Icons.Outlined.Save,
                onClick = { onAction(MenuAction.SAVE_OFFLINE) }
            )
            MenuItemButton(
                label = "Print / PDF",
                icon = Icons.Outlined.Print,
                onClick = { onAction(MenuAction.PRINT_PDF) }
            )
            MenuItemButton(
                label = "Settings",
                icon = Icons.Outlined.Settings,
                onClick = { onAction(MenuAction.SETTINGS) }
            )
        }
    }
}

enum class MenuAction {
    NEW_TAB, NEW_INCOGNITO, BOOKMARKS, HISTORY, TOGGLE_DESKTOP, RESTORE_TAB, SETTINGS,
    TOGGLE_NIGHT_MODE, TOGGLE_ADBLOCK, SAVE_OFFLINE, FIND_IN_PAGE,
    DOWNLOADS, ADD_BOOKMARK, TRANSLATE, READING_MODE, TEXT_SIZE, SHARE, PRINT_PDF
}

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

// --- Browser Custom Modular Settings Hub ---

data class SettingOption(
    val key: String,
    val title: String,
    val summary: String,
    val type: String, // "toggle", "dropdown", "slider", "action", "info"
    val options: List<String> = emptyList(),
    val range: ClosedFloatingPointRange<Float> = 0f..100f,
    val unit: String = "",
    val defaultValue: Any,
    val isReal: Boolean = false
)

data class SettingCategory(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val options: List<SettingOption>
)

data class SettingsRowItem(
    val id: String = "",
    val title: String = "",
    val icon: ImageVector = androidx.compose.material.icons.Icons.Outlined.Settings,
    val category: SettingCategory? = null,
    val action: (() -> Unit)? = null,
    val isSectionGap: Boolean = false
)

@Composable
fun SettingsSheetContent(
    settings: BrowserSettings,
    onUpdateSettings: (BrowserSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloads: (() -> Unit)? = null,
    onChangeDownloadFolder: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var currentCategory by remember { mutableStateOf<SettingCategory?>(null) }
    
    // Local interactive state map for non-persisted options
    val localState = remember { mutableStateMapOf<String, Any>() }
    
    // Custom dialog states
    var showUrlInputDialog by remember { mutableStateOf<String?>(null) } // Key of setting to change
    var showOptionSelectorDialog by remember { mutableStateOf<SettingOption?>(null) }
    var customUrlInput by remember { mutableStateOf("") }
    
    // Reset Settings Dialog state
    var showResetConfirmationDialog by remember { mutableStateOf(false) }
    var understandResetChecked by remember { mutableStateOf(false) }
    var clearCacheChecked by remember { mutableStateOf(true) }

    // Helper to get real/local state values
    fun getOptionValue(option: SettingOption): Any {
        if (option.isReal) {
            return when (option.key) {
                "search_engine" -> settings.searchEngine.displayName
                "theme_mode" -> settings.themeMode.displayName
                "dynamic_color" -> settings.useDynamicColor
                "desktop_by_default" -> settings.isDesktopModeByDefault
                "adblock_enabled" -> settings.isAdBlockEnabled
                "js_enabled" -> settings.isJavaScriptEnabled
                "cookies_enabled" -> settings.isCookiesEnabled
                "clear_cache_exit" -> settings.clearCacheOnExit
                "clear_history_exit" -> settings.clearHistoryOnExit
                "homepage_url" -> settings.homepageUrl
                else -> option.defaultValue
            }
        }
        return localState[option.key] ?: option.defaultValue
    }

    // Helper to set option value
    fun setOptionValue(option: SettingOption, newValue: Any) {
        if (option.isReal) {
            when (option.key) {
                "search_engine" -> {
                    val engine = SearchEngine.entries.find { it.displayName == newValue.toString() } ?: SearchEngine.GOOGLE
                    onUpdateSettings(settings.copy(searchEngine = engine))
                }
                "theme_mode" -> {
                    val mode = ThemeMode.entries.find { it.displayName == newValue.toString() } ?: ThemeMode.SYSTEM
                    onUpdateSettings(settings.copy(themeMode = mode))
                }
                "dynamic_color" -> onUpdateSettings(settings.copy(useDynamicColor = newValue as Boolean))
                "desktop_by_default" -> onUpdateSettings(settings.copy(isDesktopModeByDefault = newValue as Boolean))
                "adblock_enabled" -> onUpdateSettings(settings.copy(isAdBlockEnabled = newValue as Boolean))
                "js_enabled" -> onUpdateSettings(settings.copy(isJavaScriptEnabled = newValue as Boolean))
                "cookies_enabled" -> onUpdateSettings(settings.copy(isCookiesEnabled = newValue as Boolean))
                "clear_cache_exit" -> onUpdateSettings(settings.copy(clearCacheOnExit = newValue as Boolean))
                "clear_history_exit" -> onUpdateSettings(settings.copy(clearHistoryOnExit = newValue as Boolean))
                "homepage_url" -> onUpdateSettings(settings.copy(homepageUrl = newValue.toString()))
            }
        } else {
            localState[option.key] = newValue
        }
    }

    // 25 Category definitions
    val categories = remember(settings) {
        listOf(
            SettingCategory(
                id = "general",
                title = "General Settings",
                description = "Configure homepage, search engines, and user agents",
                icon = Icons.Default.Tune,
                options = listOf(
                    SettingOption("homepage_url", "Homepage", "Select your startup landing page", "action", defaultValue = settings.homepageUrl, isReal = true),
                    SettingOption("startup_page", "Startup page", "Configure tab reload on relaunch", "dropdown", listOf("New Tab Page", "Continue Last Session", "Custom Homepage"), defaultValue = "New Tab Page"),
                    SettingOption("search_engine", "Search Engine", "Configure default search engine provider", "dropdown", SearchEngine.entries.map { it.displayName }, defaultValue = settings.searchEngine.displayName, isReal = true),
                    SettingOption("default_browser", "Default Browser", "Set Kivo Browser as default browser", "toggle", defaultValue = false),
                    SettingOption("language", "Language", "Configure app interface language", "dropdown", listOf("English", "Español", "Français", "Deutsch", "日本語"), defaultValue = "English"),
                    SettingOption("downloads_location", "Downloads location", "Define internal folders target", "action", defaultValue = "/storage/emulated/0/Download"),
                    SettingOption("notification_settings", "Notification settings", "Configure background and alerts popups", "toggle", defaultValue = true),
                    SettingOption("clipboard", "Clipboard integration", "Allow reading links from system clipboard", "toggle", defaultValue = true),
                    SettingOption("external_apps", "External apps", "Open app links in external programs", "toggle", defaultValue = true),
                    SettingOption("file_handling", "File handling", "Configure actions on downloading files", "toggle", defaultValue = true),
                    SettingOption("backup_restore", "Backup & Restore", "Export settings configurations locally", "action", defaultValue = ""),
                    SettingOption("sync", "Sync Status", "Access cross-device cloud profiles", "toggle", defaultValue = false),
                    SettingOption("auto_update", "Auto update", "Query updates automatically on Wi-Fi", "toggle", defaultValue = true),
                    SettingOption("default_zoom", "Default zoom", "Adjust initial view size magnification", "slider", range = 50f..200f, unit = "%", defaultValue = 100f),
                    SettingOption("user_agent", "User Agent", "Select target web browser profile headers", "dropdown", listOf("Default Mobile", "Default Desktop", "Chrome on Windows", "Safari on macOS", "Firefox on Linux"), defaultValue = "Default Mobile")
                )
            ),
            SettingCategory(
                id = "advanced",
                title = "Advanced Controls",
                description = "Unlock experimental web engines and multi-threading",
                icon = Icons.Default.Build,
                options = listOf(
                    SettingOption("experimental_features", "Experimental Features", "Enable preview browser layout engines", "toggle", defaultValue = false),
                    SettingOption("gpu_rendering", "GPU Rendering", "Force hardware canvas graphics rendering", "toggle", defaultValue = true),
                    SettingOption("hardware_acceleration", "Hardware Acceleration", "Accelerate scrolling with device layers", "toggle", defaultValue = true),
                    SettingOption("webgl", "WebGL", "Allow 3D viewport canvas generation", "toggle", defaultValue = true),
                    SettingOption("webgl2", "WebGL2", "Unlock modern WebGL2 standard specifications", "toggle", defaultValue = true),
                    SettingOption("vulkan_rendering", "Vulkan rendering", "High efficiency Vulkan visual composite layout", "info", defaultValue = "Future support"),
                    SettingOption("parallel_downloading", "Parallel downloading", "Accelerate download speed using split parts", "toggle", defaultValue = true),
                    SettingOption("multi_thread_networking", "Multi-thread networking", "Request network packets in multiple threads", "toggle", defaultValue = true),
                    SettingOption("dns_over_https", "DNS over HTTPS", "Query site domains securely via HTTPS protocol", "toggle", defaultValue = true),
                    SettingOption("dns_over_tls", "DNS over TLS", "Query site domains securely via TLS protocol", "toggle", defaultValue = false),
                    SettingOption("js_engine", "JavaScript Engine", "Configure JIT interpreter optimizations tier", "dropdown", listOf("Standard JIT", "V8 Lite", "Interpreter Only"), defaultValue = "Standard JIT"),
                    SettingOption("cache_strategy", "Cache strategy", "Toggle caching on Cellular networks", "dropdown", listOf("Default Cache", "Wi-Fi Only Cache", "Disable Cache"), defaultValue = "Default Cache"),
                    SettingOption("memory_optimization", "Memory optimization", "Force reclaim inactive page memory pools", "toggle", defaultValue = true),
                    SettingOption("background_tabs", "Background tabs limit", "Enforce freezing limits on suspended windows", "toggle", defaultValue = false),
                    SettingOption("idle_tab_suspension", "Idle tab suspension", "Unload inactive tabs after 15 idle minutes", "toggle", defaultValue = true),
                    SettingOption("battery_optimization", "Battery optimization", "Limit rendering rates on low battery reserves", "toggle", defaultValue = true),
                    SettingOption("developer_mode", "Developer mode", "Enable system web inspection logging utilities", "toggle", defaultValue = false),
                    SettingOption("remote_debugging", "Remote debugging", "Expose web ports for chrome inspect tools", "toggle", defaultValue = false),
                    SettingOption("flags_page", "Flags page", "Configure internal browser experimental parameters", "action", defaultValue = ""),
                    SettingOption("network_diagnostics", "Network diagnostics", "Diagnose connectivity latency and DNS speeds", "action", defaultValue = ""),
                    SettingOption("crash_logs", "Crash logs", "Review recent platform error debugging files", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "site_settings",
                title = "Site Settings",
                description = "Manage camera, location, and storage permissions per site",
                icon = Icons.Default.Public,
                options = listOf(
                    SettingOption("perm_camera", "Camera", "Allow websites to query camera feeds", "toggle", defaultValue = false),
                    SettingOption("perm_mic", "Microphone", "Allow websites to record microphone signals", "toggle", defaultValue = false),
                    SettingOption("perm_location", "Location", "Allow websites to request GPS coordinates", "toggle", defaultValue = true),
                    SettingOption("perm_storage", "Storage", "Permit local database cookies storage", "toggle", defaultValue = true),
                    SettingOption("perm_clipboard", "Clipboard", "Allow reading of clip buffers dynamically", "toggle", defaultValue = false),
                    SettingOption("perm_notifications", "Notifications", "Expose background system push alerts", "toggle", defaultValue = true),
                    SettingOption("perm_popups", "Popups", "Block automatic popup browser windows", "toggle", defaultValue = true),
                    SettingOption("perm_ads", "Ads blocker", "Enforce aggressive filter list rule blockers", "toggle", defaultValue = true),
                    SettingOption("perm_bg_sync", "Background Sync", "Allow site scripts syncing after closing pages", "toggle", defaultValue = true),
                    SettingOption("perm_cookies", "Cookies", "Saves logged site profile databases", "toggle", defaultValue = true),
                    SettingOption("perm_third_party_cookies", "Third-party Cookies", "Allow trackers cookies tracking networks", "toggle", defaultValue = false),
                    SettingOption("perm_js", "JavaScript", "Enable interactive web scripts running", "toggle", defaultValue = true),
                    SettingOption("perm_images", "Images", "Automatically fetch image graphic assets", "toggle", defaultValue = true),
                    SettingOption("perm_videos", "Videos", "Allow auto-playing webpage video media files", "toggle", defaultValue = true),
                    SettingOption("perm_sound", "Sound", "Enable sound output on tabs", "toggle", defaultValue = true),
                    SettingOption("perm_sensors", "Sensors", "Allow motion sensors like accelerometer", "toggle", defaultValue = true),
                    SettingOption("perm_usb", "USB", "Allow WebUSB hardware access tools", "toggle", defaultValue = false),
                    SettingOption("perm_bluetooth", "Bluetooth", "Allow scanning for Bluetooth controllers", "toggle", defaultValue = false),
                    SettingOption("perm_nfc", "NFC", "Allow scanning for RFID tag inputs", "toggle", defaultValue = false),
                    SettingOption("perm_midi", "MIDI", "Connect external synthesizer keys", "toggle", defaultValue = false),
                    SettingOption("perm_downloads", "Downloads", "Prompt prior to starting automatic downloads", "toggle", defaultValue = true),
                    SettingOption("perm_pdf", "PDF handling", "Open PDFs in native local viewer instead of downloading", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "backup",
                title = "Backup & Restore",
                description = "Save and import bookmarks, settings, and password vaults",
                icon = Icons.Default.Backup,
                options = listOf(
                    SettingOption("export_bookmarks", "Export bookmarks", "Generate backup JSON file of web bookmarks", "action", defaultValue = ""),
                    SettingOption("import_bookmarks", "Import bookmarks", "Import bookmarks from standard backup file", "action", defaultValue = ""),
                    SettingOption("export_settings", "Export settings", "Backup custom layouts and settings to disk", "action", defaultValue = ""),
                    SettingOption("restore_settings", "Restore settings", "Import and load backup settings profile file", "action", defaultValue = ""),
                    SettingOption("export_passwords", "Export passwords", "Saves saved login passwords into secure text file", "action", defaultValue = ""),
                    SettingOption("restore_passwords", "Restore passwords", "Load backup password list file", "action", defaultValue = ""),
                    SettingOption("export_history", "Export browsing history", "Generate visit logs backup file", "action", defaultValue = ""),
                    SettingOption("cloud_backup", "Cloud backup (future)", "Sync profiles onto cloud storage accounts", "info", defaultValue = "Premium Account Required"),
                    SettingOption("local_backup", "Local backup", "Export total system settings backup zip bundle", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "purchase",
                title = "Upgrade Premium",
                description = "Unlock customized themes, cloud backup, and AI summarizes",
                icon = Icons.Default.ShoppingCart,
                options = listOf(
                    SettingOption("premium_tier", "Premium License", "Register or acquire active license status", "info", defaultValue = "Standard Edition"),
                    SettingOption("themes_pack", "Themes", "Select premium visual palettes theme accent", "dropdown", listOf("Standard Teal", "Emerald Forest", "Midnight Purple", "Solarized Amber", "Cyberpunk Pink"), defaultValue = "Standard Teal"),
                    SettingOption("premium_cloud_sync", "Cloud Sync", "Activate permanent cross platform cloud accounts sync", "toggle", defaultValue = false),
                    SettingOption("premium_ai_features", "AI Features", "Enable smart web summary and article keywords tags", "toggle", defaultValue = false),
                    SettingOption("premium_ad_free", "Ad-Free version", "Disable non-disruptive native promotional logs", "action", defaultValue = ""),
                    SettingOption("premium_donate", "Donate", "Support ongoing Kivo Browser open development", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "web_cleaner",
                title = "Web Cleaner",
                description = "Configure ad blockers, anti-fingerprinting, and custom filters",
                icon = Icons.Default.Shield,
                options = listOf(
                    SettingOption("adblock_enabled", "Ad blocker", "Natively block trackers and annoying popups", "toggle", defaultValue = settings.isAdBlockEnabled, isReal = true),
                    SettingOption("tracker_blocker", "Tracker blocker", "Intercept telemetry systems analytics trackers", "toggle", defaultValue = true),
                    SettingOption("cookie_blocker", "Cookie blocker", "Auto-dismiss and bypass annoying cookie panels", "toggle", defaultValue = false),
                    SettingOption("anti_popup", "Anti popup", "Halt opening of hidden redirection browser windows", "toggle", defaultValue = true),
                    SettingOption("anti_redirect", "Anti redirect", "Block fast redirects from unknown domain sources", "toggle", defaultValue = true),
                    SettingOption("anti_mining", "Anti mining", "Intercept page cryptomining scripts dynamically", "toggle", defaultValue = true),
                    SettingOption("anti_fingerprint", "Anti fingerprint", "Obfuscate device user agent properties", "toggle", defaultValue = false),
                    SettingOption("malware_protection", "Malware protection", "Halt loads of verified malicious domains", "toggle", defaultValue = true),
                    SettingOption("https_only_mode", "HTTPS only mode", "Enforce encrypted SSL connections on load", "toggle", defaultValue = true),
                    SettingOption("safe_browsing", "Safe browsing", "Verify safety logs using Google threat APIs", "toggle", defaultValue = true),
                    SettingOption("custom_filters", "Custom filter lists", "Define personalized ad-block lists URLs", "action", defaultValue = ""),
                    SettingOption("whitelist", "Whitelist", "Permitted sites bypassed by filter engines", "action", defaultValue = ""),
                    SettingOption("blacklist", "Blacklist", "Enforced blocked list domains", "action", defaultValue = ""),
                    SettingOption("cosmetic_filtering", "Cosmetic filtering", "Auto-collapse blank spaces left by blocked banners", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "downloads",
                title = "Downloads Engine",
                description = "Configure multi-threaded downloads acceleration",
                icon = Icons.Default.Download,
                options = listOf(
                    SettingOption("download_manager", "Download manager", "Override standard system downloads engine", "toggle", defaultValue = true),
                    SettingOption("parallel_downloads", "Parallel downloads", "Query segments in parallel connections", "toggle", defaultValue = true),
                    SettingOption("max_threads", "Maximum threads", "Maximum connection splits per download task", "slider", range = 1f..16f, unit = " segments", defaultValue = 4f),
                    SettingOption("download_acceleration", "Download acceleration", "Adjust socket buffers dynamically based on speed", "toggle", defaultValue = true),
                    SettingOption("smart_resume", "Smart resume", "Auto reconnect interrupted downloads on network restored", "toggle", defaultValue = true),
                    SettingOption("schedule_downloads", "Schedule downloads", "Queue larger items for off-peak hours schedule", "toggle", defaultValue = false),
                    SettingOption("wifi_only_downloads", "WiFi only downloads", "Restrict transfers when on cell data plans", "toggle", defaultValue = false),
                    SettingOption("background_downloading", "Background downloading", "Maintain downloads tasks active after closing Kivo", "toggle", defaultValue = true),
                    SettingOption("download_notifications", "Download notifications", "Show transfer speeds progress in status alerts", "toggle", defaultValue = true),
                    SettingOption("completed_downloads", "Completed downloads", "Inspect folder log of historical local transfers", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "media",
                title = "Media Options",
                description = "Configure video downloader, PiP, and background playback",
                icon = Icons.Default.PlayCircle,
                options = listOf(
                    SettingOption("video_downloader", "Video downloader", "Auto sniff downloadable stream URLs", "toggle", defaultValue = true),
                    SettingOption("audio_downloader", "Audio downloader", "Extract and download audio tracks exclusively", "toggle", defaultValue = false),
                    SettingOption("media_detector", "Media detector", "Show float prompt when video media sources load", "toggle", defaultValue = true),
                    SettingOption("background_playback", "Background playback", "Keep media sounds running in sleeping tabs", "toggle", defaultValue = true),
                    SettingOption("pip_mode", "Picture in Picture", "Trigger picture-in-picture when pressing Home", "toggle", defaultValue = true),
                    SettingOption("floating_player", "Floating player", "Render customizable float video player overlay", "toggle", defaultValue = false),
                    SettingOption("subtitle_support", "Subtitle support", "Fetch and render online SRT subtitle files", "toggle", defaultValue = true),
                    SettingOption("video_quality", "Video quality preference", "Target preferred video stream resolutions", "dropdown", listOf("Auto-Detect", "1080p FHD", "720p HD", "480p SD"), defaultValue = "Auto-Detect"),
                    SettingOption("hardware_decoding", "Hardware decoding", "Accelerate frame decode processes via GPU hardware", "toggle", defaultValue = true),
                    SettingOption("playback_speed", "Playback speed", "Configure standard video playback speed", "slider", range = 0.5f..2.0f, unit = "x", defaultValue = 1.0f),
                    SettingOption("cast_settings", "Cast settings", "Setup Miracast and streaming cast connections", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "memory_saving",
                title = "Memory Management",
                description = "Configure automatic tab sleeping and RAM optimization",
                icon = Icons.Default.Memory,
                options = listOf(
                    SettingOption("auto_tab_sleeping", "Auto tab sleeping", "Suspend webviews when inactive for 20 minutes", "toggle", defaultValue = true),
                    SettingOption("auto_cache_cleanup", "Auto cache cleanup", "Wipe expired storage cache segments when low on RAM", "toggle", defaultValue = false),
                    SettingOption("image_compression", "Image compression", "Compress web images payloads to reduce RAM load", "toggle", defaultValue = false),
                    SettingOption("lazy_loading", "Lazy loading", "Hold loading images until scrolling close", "toggle", defaultValue = true),
                    SettingOption("lite_mode", "Lite mode", "Disable complex responsive scripts on low memory", "toggle", defaultValue = false),
                    SettingOption("data_saver", "Data Saver", "Compress resources over safe cloud proxies", "toggle", defaultValue = false),
                    SettingOption("low_ram_mode", "Low RAM mode", "Limit tab activities when device RAM limit is low", "toggle", defaultValue = false),
                    SettingOption("performance_monitor", "Performance monitor", "Render FPS and RAM diagnostics graph status", "toggle", defaultValue = false),
                    SettingOption("cache_limits", "Cache limits", "Define limit for browser cache database storage", "dropdown", listOf("100 MB", "500 MB", "1 GB", "Unlimited"), defaultValue = "500 MB")
                )
            ),
            SettingCategory(
                id = "display",
                title = "Display Settings",
                description = "Choose theme, AMOLED dark mode, and page zoom",
                icon = Icons.Default.Palette,
                options = listOf(
                    SettingOption("theme_mode", "Theme", "Adjust light and dark app color profiles", "dropdown", ThemeMode.entries.map { it.displayName }, defaultValue = settings.themeMode.displayName, isReal = true),
                    SettingOption("amoled_mode", "AMOLED mode", "Render pure black backgrounds (#000000) inside dark pages", "toggle", defaultValue = true),
                    SettingOption("accent_color", "Accent color", "Change interface highlighting theme colors", "dropdown", listOf("Teal Premium", "Space Blue", "Mystic Rose", "Emerald Green", "Carbon Monochrome"), defaultValue = "Teal Premium"),
                    SettingOption("font_size", "Font size", "Adjust scaling layout sizes of browser pages", "slider", range = 10f..26f, unit = "sp", defaultValue = 16f),
                    SettingOption("page_zoom", "Page zoom", "Enforce default zoom multiplier across tabs", "slider", range = 50f..200f, unit = "%", defaultValue = 100f),
                    SettingOption("toolbar_transparency", "Toolbar transparency", "Control top and bottom bars opacity level", "slider", range = 0f..100f, unit = "%", defaultValue = 0f),
                    SettingOption("status_bar", "Status bar", "Show or hide the system clock and notify drawer bar", "toggle", defaultValue = true),
                    SettingOption("nav_bar", "Navigation bar", "Toggle visibility of bottom gesture navigation handles", "toggle", defaultValue = true),
                    SettingOption("reader_mode", "Reader mode", "Render minimal layout for simple textual articles", "toggle", defaultValue = true),
                    SettingOption("animations", "Animations", "Display elegant fluid layout motion transitions", "toggle", defaultValue = true),
                    SettingOption("dynamic_color", "Dynamic colors", "Sync with dynamic Material You system wallpapers colors", "toggle", defaultValue = settings.useDynamicColor, isReal = true),
                    SettingOption("dark_mode_scheduling", "Dark mode scheduling", "Automate light and dark transitions cycles", "dropdown", listOf("Manual", "Sunset to Sunrise", "Custom Schedule"), defaultValue = "Manual")
                )
            ),
            SettingCategory(
                id = "layout",
                title = "Layout & Toolbars",
                description = "Configure address bar position and compact modes",
                icon = Icons.Default.Layers,
                options = listOf(
                    SettingOption("bottom_toolbar", "Bottom toolbar", "Expose actions key navigation bars on bottom edge", "toggle", defaultValue = true),
                    SettingOption("top_toolbar", "Top toolbar", "Expose primary address bar on top edge", "toggle", defaultValue = true),
                    SettingOption("address_bar_pos", "Address bar position", "Define active layout target for URL address bar", "dropdown", listOf("Top of Screen", "Bottom of Screen"), defaultValue = "Bottom of Screen"),
                    SettingOption("compact_mode", "Compact mode", "Render condensed spacing between toolbar actions buttons", "toggle", defaultValue = false),
                    SettingOption("tablet_layout", "Tablet layout", "Enable desktop multi-column viewport tab structures", "toggle", defaultValue = false),
                    SettingOption("landscape_mode", "Landscape mode", "Allow rotation of active browser displays in landscape", "toggle", defaultValue = true),
                    SettingOption("tab_strip", "Tab strip", "Show elegant tab strip beneath address bars", "toggle", defaultValue = false),
                    SettingOption("floating_buttons", "Floating buttons", "Show floating overlay controls inside web pages", "toggle", defaultValue = false),
                    SettingOption("side_panels", "Side panels", "Allow sliding panels drawers for bookmarks management", "toggle", defaultValue = true),
                    SettingOption("one_handed_mode", "One handed mode", "Bring top actions elements down within thumb reach", "toggle", defaultValue = false)
                )
            ),
            SettingCategory(
                id = "menu_customization",
                title = "Menu Customization",
                description = "Rearrange action buttons and custom shortcuts",
                icon = Icons.Default.Menu,
                options = listOf(
                    SettingOption("add_shortcuts", "Add shortcuts", "Pin customized system actions onto menu layouts", "action", defaultValue = ""),
                    SettingOption("remove_shortcuts", "Remove shortcuts", "Clear redundant system actions from lists", "action", defaultValue = ""),
                    SettingOption("rearrange_menu", "Rearrange menu", "Drag and drop to structure custom action lists", "action", defaultValue = ""),
                    SettingOption("custom_quick_actions", "Create custom quick actions", "Map features buttons on long clicks of bars", "action", defaultValue = ""),
                    SettingOption("customize_toolbar", "Customize toolbar buttons", "Modify toolbar items layout slots", "action", defaultValue = ""),
                    SettingOption("customize_homepage_shortcuts", "Customize homepage shortcuts", "Adjust icons counts inside launch homepage panels", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "tabs",
                title = "Tab Management",
                description = "Configure tab grids, groups, and auto-closing",
                icon = Icons.Default.Tab,
                options = listOf(
                    SettingOption("tab_groups", "Tab groups", "Group open browser sessions into sub-tabs structures", "toggle", defaultValue = true),
                    SettingOption("vertical_tabs", "Vertical tabs", "Display active browser windows inside side strips lists", "toggle", defaultValue = false),
                    SettingOption("grid_tabs", "Grid tabs", "Select grid layout styling for active window lists", "toggle", defaultValue = true),
                    SettingOption("list_tabs", "List tabs", "Select compact listing layout for active windows lists", "toggle", defaultValue = false),
                    SettingOption("unlimited_tabs", "Unlimited tabs", "Bypass standard maximum open tab thresholds limits", "toggle", defaultValue = true),
                    SettingOption("auto_close_inactive", "Auto close inactive tabs", "Automatically close background tabs", "dropdown", listOf("Never", "After 1 Day", "After 1 Week", "After 1 Month"), defaultValue = "Never"),
                    SettingOption("duplicate_tab", "Duplicate tab", "Allow duplicate sessions URLs in secondary tabs", "toggle", defaultValue = true),
                    SettingOption("lock_tab", "Lock tab", "Lock tabs pages to prevent accidental close swipe actions", "toggle", defaultValue = false),
                    SettingOption("pin_tab", "Pin tab", "Secure active web pages at the peak of tabs menus", "toggle", defaultValue = false),
                    SettingOption("archive_tabs", "Archive tabs", "Export background tabs sessions onto storage databases", "toggle", defaultValue = false),
                    SettingOption("recently_closed", "Recently closed", "Review historic open logs of closed tabs", "action", defaultValue = ""),
                    SettingOption("session_restore", "Session restore", "Automatically re-load tabs upon application relaunch", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "browser_composition",
                title = "Browser Composition",
                description = "Customize navigation bars and dynamic layout widgets",
                icon = Icons.Default.Dashboard,
                options = listOf(
                    SettingOption("comp_toolbar_buttons", "Toolbar buttons", "Assign active operations buttons onto bottom bar", "action", defaultValue = ""),
                    SettingOption("comp_address_bar_layout", "Address bar layout", "Add refresh button inside address entry fields", "action", defaultValue = ""),
                    SettingOption("comp_bottom_navigation", "Bottom navigation", "Define custom actions in bottom navigation docks", "action", defaultValue = ""),
                    SettingOption("comp_homepage_widgets", "Homepage widgets", "Configure weather, clocks, or news feeds on homepages", "action", defaultValue = ""),
                    SettingOption("comp_search_widget", "Search widget", "Assign search engines selection inside home widget", "action", defaultValue = ""),
                    SettingOption("comp_gesture_buttons", "Gesture buttons", "Map navigation operations on sliding triggers keys", "action", defaultValue = ""),
                    SettingOption("comp_floating_tools", "Floating tools", "Show custom floating toolkit grids on scrolling", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "web_content",
                title = "Web Content",
                description = "Toggle images, autoplay videos, and custom fonts",
                icon = Icons.Default.Article,
                options = listOf(
                    SettingOption("desktop_by_default", "Desktop mode", "Request standard desktop browser layouts by default", "toggle", defaultValue = settings.isDesktopModeByDefault, isReal = true),
                    SettingOption("reader_mode", "Reader mode", "Automatically trigger reading modes on textual articles", "toggle", defaultValue = false),
                    SettingOption("force_dark_pages", "Force dark pages", "Inject dark stylesheets inside white pages natively", "toggle", defaultValue = true),
                    SettingOption("js_enabled", "JavaScript", "Enable running of interactive webpage script elements", "toggle", defaultValue = settings.isJavaScriptEnabled, isReal = true),
                    SettingOption("images_enabled", "Images", "Automatically load webpage image graphic elements", "toggle", defaultValue = true),
                    SettingOption("videos_enabled", "Videos", "Allow automatic video streams buffering and play", "toggle", defaultValue = true),
                    SettingOption("fonts", "Fonts", "Choose custom font types for displaying web articles", "dropdown", listOf("Default Sans", "Classic Serif", "Developer Monospace"), defaultValue = "Default Sans"),
                    SettingOption("cookies_enabled", "Cookies", "Permit websites storing logged in credentials cookie databases", "toggle", defaultValue = settings.isCookiesEnabled, isReal = true),
                    SettingOption("tracking_protection", "Tracking protection", "Prevent tracking elements collecting visitor logs profiles", "toggle", defaultValue = true),
                    SettingOption("popups", "Popups", "Block unwanted popup screens during web interactions", "toggle", defaultValue = true),
                    SettingOption("mixed_content", "Mixed content", "Permit non SSL HTTP assets queries on secure pages", "toggle", defaultValue = false),
                    SettingOption("accessibility", "Accessibility", "Enforce dynamic text scaling configurations for vision", "action", defaultValue = ""),
                    SettingOption("auto_translate", "Auto translate", "Offer translation of foreign text immediately", "toggle", defaultValue = false)
                )
            ),
            SettingCategory(
                id = "gestures",
                title = "Gestures & Swipes",
                description = "Configure swipe navigation, pull-to-refresh, and zooms",
                icon = Icons.Default.Gesture,
                options = listOf(
                    SettingOption("swipe_back", "Swipe back", "Swipe left edge of screen to navigate backwards", "toggle", defaultValue = true),
                    SettingOption("swipe_forward", "Swipe forward", "Swipe right edge of screen to navigate forwards", "toggle", defaultValue = true),
                    SettingOption("pull_to_refresh", "Pull to refresh", "Pull down from top of webpage to reload active page", "toggle", defaultValue = true),
                    SettingOption("double_tap_zoom", "Double tap zoom", "Double click layout elements to auto wrap and zoom", "toggle", defaultValue = true),
                    SettingOption("edge_swipe", "Edge swipe", "Limit back swipe actions to bezel zones only", "toggle", defaultValue = false),
                    SettingOption("bottom_gestures", "Bottom gestures", "Swipe toolbar horizontal slider to cycle tabs list", "toggle", defaultValue = true),
                    SettingOption("custom_gesture_actions", "Custom gesture actions", "Map personal actions on custom multi finger gestures", "action", defaultValue = ""),
                    SettingOption("long_press_shortcuts", "Long press shortcuts", "Expose operations shortcuts lists on long clicks", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "translator",
                title = "Language Translator",
                description = "Configure translation providers and offline packs",
                icon = Icons.Default.Translate,
                options = listOf(
                    SettingOption("trans_auto_translate", "Auto translate", "Auto convert foreign scripts on load", "toggle", defaultValue = false),
                    SettingOption("trans_preferred_languages", "Preferred languages", "Languages list requiring no translation", "action", defaultValue = ""),
                    SettingOption("trans_offline_packs", "Offline language packs", "Download local dictionaries for translation offline", "action", defaultValue = ""),
                    SettingOption("trans_provider", "Translation provider", "Translator machine engine database backend", "dropdown", listOf("Google Translate", "Bing Translator", "DeepL Engine"), defaultValue = "Google Translate"),
                    SettingOption("trans_page_translation", "Page translation", "Allow complete layout rendering conversion", "toggle", defaultValue = true),
                    SettingOption("trans_selection_translation", "Selection translation", "Show hover floating translate button on text selections", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "text_to_speech",
                title = "Text to Speech (TTS)",
                description = "Configure reading speeds and background voices",
                icon = Icons.Default.VolumeUp,
                options = listOf(
                    SettingOption("tts_voice", "Voice selection", "Select preferred vocal personality profile", "dropdown", listOf("Voice Alpha Male", "Voice Beta Female", "Elegant Voice Standard", "Robotic Accent"), defaultValue = "Elegant Voice Standard"),
                    SettingOption("tts_speed", "Speed", "Adjust speech speed rate", "slider", range = 0.5f..3.0f, unit = "x", defaultValue = 1.0f),
                    SettingOption("tts_pitch", "Pitch", "Adjust synthesis pitch tone", "slider", range = 0.5f..2.0f, unit = "x", defaultValue = 1.0f),
                    SettingOption("tts_language", "Language", "Speech synthesizers native vocabulary base", "dropdown", listOf("English (US)", "Spanish (ES)", "German (DE)", "French (FR)", "Japanese (JP)"), defaultValue = "English (US)"),
                    SettingOption("tts_highlight", "Highlight while reading", "Color highlight active spoken words dynamically", "toggle", defaultValue = true),
                    SettingOption("tts_background", "Background reading", "Continue playing audio reading when system screen locks", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "tv_cast",
                title = "TV Cast & Streaming",
                description = "Configure Chromecast, DLNA, and subtitles casting",
                icon = Icons.Default.Cast,
                options = listOf(
                    SettingOption("cast_chromecast", "Chromecast", "Auto look for Google Cast stream targets", "toggle", defaultValue = true),
                    SettingOption("cast_dlna", "DLNA", "Scan local Wi-Fi networks for DLNA screens", "toggle", defaultValue = false),
                    SettingOption("cast_smart_tv", "Smart TV support", "Enable streaming integrations with smart TVs", "toggle", defaultValue = true),
                    SettingOption("cast_auto_discovery", "Auto discovery", "Continuously poll local network for receivers", "toggle", defaultValue = true),
                    SettingOption("cast_quality", "Quality selection", "Define stream video resolution quality target", "dropdown", listOf("Auto-Negotiate", "1080p Stream", "720p Stream", "Original Source"), defaultValue = "Auto-Negotiate"),
                    SettingOption("cast_audio_routing", "Audio routing", "Direct audio soundtrack paths out to device speakers", "dropdown", listOf("Play on TV Screen", "Keep on Mobile Phone"), defaultValue = "Play on TV Screen"),
                    SettingOption("cast_subtitles", "Subtitle casting", "Upload subtitle files beside casted streams", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "security",
                title = "Security Hub",
                description = "Manage biometrics, certificate viewer, and credentials",
                icon = Icons.Default.Security,
                options = listOf(
                    SettingOption("sec_https_only", "HTTPS Only Mode", "Reject unencrypted HTTP page requests", "toggle", defaultValue = true),
                    SettingOption("sec_safe_browsing", "Safe Browsing", "Reference threat blacklists databases before load", "toggle", defaultValue = true),
                    SettingOption("sec_biometrics", "Biometrics", "Prompt fingerprint check upon launching Kivo Browser", "toggle", defaultValue = false),
                    SettingOption("sec_fingerprint", "Fingerprint Unlock", "Secure cached passwords directories behind fingerprint", "toggle", defaultValue = false),
                    SettingOption("sec_face", "Face Unlock", "Use face ID checks to unlock private bookmarks folders", "toggle", defaultValue = false),
                    SettingOption("sec_certificates", "Certificate Viewer", "Review secure socket SSL certification databases", "action", defaultValue = ""),
                    SettingOption("sec_pwd_mgr", "Password Manager", "Access secure encrypted credentials records vaults", "action", defaultValue = ""),
                    SettingOption("sec_passkeys", "Passkeys", "Review registered hardware keys tokens listings", "action", defaultValue = ""),
                    SettingOption("sec_dashboard", "Security Dashboard", "Audit password strength across cached logins databases", "action", defaultValue = ""),
                    SettingOption("sec_clear_creds", "Clear credentials", "Wipe cached credentials tokens from sandboxes", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "incognito",
                title = "Incognito Rules",
                description = "Configure locks, separate download, and cookies",
                icon = Icons.Default.VisibilityOff,
                options = listOf(
                    SettingOption("inc_lock", "Lock Incognito", "Require system credentials verification before restoring private tabs", "toggle", defaultValue = false),
                    SettingOption("inc_auto_delete", "Auto delete on exit", "Automatically clear all private cookies and history logs upon closing", "toggle", defaultValue = true),
                    SettingOption("inc_block_screenshots", "Block screenshots", "Disallow screenshot capture actions inside private sheets", "toggle", defaultValue = true),
                    SettingOption("inc_separate_downloads", "Separate downloads", "Isolate directories of downloaded items from private sessions", "toggle", defaultValue = false),
                    SettingOption("inc_separate_cookies", "Separate cookies", "Isolate cookies containers pools during private tab sessioning", "toggle", defaultValue = true),
                    SettingOption("inc_temp_perms", "Temporary permissions", "Auto expire site permissions granted within private tabs", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "passwords",
                title = "Password Vault",
                description = "Manage password lists, imports, and leak detections",
                icon = Icons.Default.VpnKey,
                options = listOf(
                    SettingOption("pwd_manager_main", "Password Manager", "Inspect and edit encrypted logins directories databases", "action", defaultValue = ""),
                    SettingOption("pwd_passkeys_main", "Passkeys", "Review hardware bound passwordless key listings", "action", defaultValue = ""),
                    SettingOption("pwd_import", "Import", "Import third-party login credential listings CSV formats", "action", defaultValue = ""),
                    SettingOption("pwd_export", "Export", "Export local passwords database into unencrypted file", "action", defaultValue = ""),
                    SettingOption("pwd_leak_detection", "Leak detection", "Check local database passwords against public data leaks registries", "toggle", defaultValue = true),
                    SettingOption("pwd_generator", "Password generator", "Configure rules for automated complex string generators", "action", defaultValue = ""),
                    SettingOption("pwd_autofill", "Autofill", "Auto inject saved logins credentials onto compatible web forms", "toggle", defaultValue = true)
                )
            ),
            SettingCategory(
                id = "clear_data",
                title = "Clear User Data",
                description = "Purge cookies, history, and databases individually",
                icon = Icons.Default.DeleteForever,
                options = listOf(
                    SettingOption("clear_cache", "Cache Files", "Wipe browser caches database storage instantly", "action", defaultValue = ""),
                    SettingOption("clear_cookies", "Cookies", "Wipe stored logged in sessions and tracking cookies", "action", defaultValue = ""),
                    SettingOption("clear_history", "Browsing History", "Purge all historic URLs and visited logs entries", "action", defaultValue = ""),
                    SettingOption("clear_downloads", "Downloads Logs", "Clear the listings database of download actions", "action", defaultValue = ""),
                    SettingOption("clear_passwords", "Saved Passwords", "Delete all encrypted credentials records storage databases", "action", defaultValue = ""),
                    SettingOption("clear_form_data", "Form Autofill Data", "Wipe cached text inputs of browser forms fields", "action", defaultValue = ""),
                    SettingOption("clear_local_storage", "Local Storage", "Purge local storage directories datasets of sites", "action", defaultValue = ""),
                    SettingOption("clear_indexeddb", "IndexedDB", "Purge site database structures files from storage", "action", defaultValue = ""),
                    SettingOption("clear_service_workers", "Service Workers", "Deregister background site scripts workers", "action", defaultValue = ""),
                    SettingOption("clear_site_perms", "Site Permissions", "Restore all site access permission settings to default", "action", defaultValue = ""),
                    SettingOption("clear_everything", "Wipe Everything", "Perform full factory clear of Kivo databases", "action", defaultValue = "")
                )
            ),
            SettingCategory(
                id = "dns",
                title = "DNS Server Profiles",
                description = "Select Cloudflare, Google, AdGuard, or DoH",
                icon = Icons.Default.Dns,
                options = listOf(
                    SettingOption("dns_provider", "DNS Server Provider", "Select domain name resolver server", "dropdown", listOf("ISP Automatic", "Cloudflare Secure (1.1.1.1)", "Google DNS (8.8.8.8)", "Quad9 Shield (9.9.9.9)", "AdGuard Filter (No Ads)", "NextDNS Custom Endpoint", "Custom Manual IP"), defaultValue = "ISP Automatic"),
                    SettingOption("dns_over_https_pref", "DNS over HTTPS", "Enable DNS querying tunnel secure HTTP port protocols", "toggle", defaultValue = true),
                    SettingOption("dns_over_tls_pref", "DNS over TLS", "Enable DNS querying tunnel secure TLS port protocols", "toggle", defaultValue = false)
                )
            ),
            SettingCategory(
                id = "about",
                title = "About Kivo Browser",
                description = "Check for updates, feedback, and open source licenses",
                icon = Icons.Default.Info,
                options = listOf(
                    SettingOption("about_rate", "Rate App", "Leave feedback review on the Play Store app directory", "action", defaultValue = ""),
                    SettingOption("about_share", "Share App", "Send Kivo download links to secondary devices", "action", defaultValue = ""),
                    SettingOption("about_feedback", "Send Feedback", "Directly message developers bug suggestions notes", "action", defaultValue = ""),
                    SettingOption("about_bug", "Report Bug", "Create technical crash logs on support platforms", "action", defaultValue = ""),
                    SettingOption("about_changelog", "Changelog", "Inspect recently added premium layout capabilities logs", "action", defaultValue = ""),
                    SettingOption("about_privacy", "Privacy Policy", "Review standard user data encryption guarantees", "action", defaultValue = ""),
                    SettingOption("about_terms", "Terms", "Review conditions governing the browser engine usage", "action", defaultValue = ""),
                    SettingOption("about_licenses", "Licenses", "List open source integrated frameworks codebases credits", "action", defaultValue = ""),
                    SettingOption("about_version", "Version Code", "Display installed application release bundle version number", "info", defaultValue = "Kivo v2.8.5-Stable"),
                    SettingOption("about_developer", "Developer", "Maintainer team and organization behind project", "info", defaultValue = "Kivo Software Labs"),
                    SettingOption("about_github", "GitHub Repository", "Review browser codebase project files online", "action", defaultValue = ""),
                    SettingOption("about_libraries", "Open Source Libraries", "Review dynamic integrated libraries credentials", "action", defaultValue = ""),
                    SettingOption("about_updates", "Check for Updates", "Send query signals to servers for newest releases", "action", defaultValue = "")
                )
            )
        )
    }

    // Filter items based on query for instant global search
    val filteredSearchResults = remember(searchQuery, categories) {
        if (searchQuery.isBlank()) emptyList<Pair<SettingCategory, SettingOption>>()
        else {
            val results = mutableListOf<Pair<SettingCategory, SettingOption>>()
            categories.forEach { cat ->
                cat.options.forEach { opt ->
                    if (opt.title.contains(searchQuery, ignoreCase = true) || 
                        opt.summary.contains(searchQuery, ignoreCase = true) || 
                        cat.title.contains(searchQuery, ignoreCase = true)) {
                        results.add(cat to opt)
                    }
                }
            }
            results
        }
    }

    // Pure AMOLED Black theme values
    val amoledBg = Color(0xFF000000)
    val cardColor = Color(0xFF0E0E10)
    val dividerColor = Color(0xFF1F1F22)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF8E8E93)
    val accentColorVal = Color(0xFF14FFC2) // Gorgeous premium flagship teal monochrome accent
    
    // HTML / Action handlers
    val handleAction = { opt: SettingOption ->
        when (opt.key) {
            "homepage_url" -> {
                customUrlInput = getOptionValue(opt).toString()
                showUrlInputDialog = opt.key
            }
            "clear_cache" -> {
                try {
                    WebView(context).clearCache(true)
                    Toast.makeText(context, "Browser cache wiped successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Wipe failed", Toast.LENGTH_SHORT).show()
                }
            }
            "clear_cookies" -> {
                try {
                    CookieManager.getInstance().removeAllCookies { success ->
                        if (success) Toast.makeText(context, "All cookies successfully deleted", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Wipe failed", Toast.LENGTH_SHORT).show()
                }
            }
            "clear_everything" -> {
                try {
                    WebView(context).clearCache(true)
                    CookieManager.getInstance().removeAllCookies { success ->
                        Toast.makeText(context, "Factory clear complete. All caches and cookies wiped.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Full wipe failed", Toast.LENGTH_SHORT).show()
                }
            }
            "completed_downloads" -> {
                onDismiss()
                onOpenDownloads?.invoke()
            }
            "downloads_location" -> {
                onChangeDownloadFolder?.invoke()
            }
            "clear_downloads" -> {
                scope.launch {
                    val db = com.example.browser.data.BrowserDatabase.getDatabase(context)
                    db.browserDao().clearDownloads()
                    Toast.makeText(context, "Download history cleared", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                Toast.makeText(context, "Action: ${opt.title} configured successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val settingsItemsList = remember(categories) {
        val items = mutableListOf<SettingsRowItem>()
        
        // Group 1
        categories.find { it.id == "general" }?.let { items.add(SettingsRowItem(id = "general", title = "General", icon = Icons.Outlined.Settings, category = it)) }
        categories.find { it.id == "advanced" }?.let { items.add(SettingsRowItem(id = "advanced", title = "Advanced", icon = Icons.Outlined.Build, category = it)) }
        categories.find { it.id == "site_settings" }?.let { items.add(SettingsRowItem(id = "site_settings", title = "Site", icon = Icons.Outlined.Public, category = it)) }
        categories.find { it.id == "backup" }?.let { items.add(SettingsRowItem(id = "backup", title = "Backup", icon = Icons.Outlined.CloudUpload, category = it)) }
        
        items.add(SettingsRowItem(isSectionGap = true))
        
        // Group 2
        categories.find { it.id == "purchase" }?.let { items.add(SettingsRowItem(id = "purchase", title = "Purchase", icon = Icons.Outlined.CreditCard, category = it)) }
        categories.find { it.id == "web_cleaner" }?.let { items.add(SettingsRowItem(id = "web_cleaner", title = "Web cleaner", icon = Icons.Outlined.Shield, category = it)) }
        categories.find { it.id == "downloads" }?.let { items.add(SettingsRowItem(id = "downloads", title = "Download", icon = Icons.Outlined.Download, category = it)) }
        categories.find { it.id == "media" }?.let { items.add(SettingsRowItem(id = "media", title = "Media", icon = Icons.Outlined.PlayCircle, category = it)) }
        categories.find { it.id == "memory_saving" }?.let { items.add(SettingsRowItem(id = "memory_saving", title = "Memory saving", icon = Icons.Outlined.Memory, category = it)) }
        
        items.add(SettingsRowItem(isSectionGap = true))
        
        // Group 3
        categories.find { it.id == "display" }?.let { items.add(SettingsRowItem(id = "display", title = "Display", icon = Icons.Outlined.Palette, category = it)) }
        categories.find { it.id == "layout" }?.let { items.add(SettingsRowItem(id = "layout", title = "Layout", icon = Icons.Outlined.Layers, category = it)) }
        categories.find { it.id == "menu_customization" }?.let { items.add(SettingsRowItem(id = "menu_customization", title = "Menu", icon = Icons.Outlined.Menu, category = it)) }
        categories.find { it.id == "tabs" }?.let { items.add(SettingsRowItem(id = "tabs", title = "Tab", icon = Icons.Outlined.Tab, category = it)) }
        categories.find { it.id == "browser_composition" }?.let { items.add(SettingsRowItem(id = "browser_composition", title = "Composition", icon = Icons.Outlined.Dashboard, category = it)) }
        categories.find { it.id == "web_content" }?.let { items.add(SettingsRowItem(id = "web_content", title = "Web content", icon = Icons.Outlined.Article, category = it)) }
        categories.find { it.id == "gestures" }?.let { items.add(SettingsRowItem(id = "gestures", title = "Gesture", icon = Icons.Outlined.Gesture, category = it)) }
        categories.find { it.id == "translator" }?.let { items.add(SettingsRowItem(id = "translator", title = "Translator", icon = Icons.Outlined.Translate, category = it)) }
        categories.find { it.id == "text_to_speech" }?.let { items.add(SettingsRowItem(id = "text_to_speech", title = "Text to Speech", icon = Icons.Outlined.VolumeUp, category = it)) }
        categories.find { it.id == "tv_cast" }?.let { items.add(SettingsRowItem(id = "tv_cast", title = "TV Cast", icon = Icons.Outlined.Cast, category = it)) }
        
        items.add(SettingsRowItem(isSectionGap = true))
        
        // Group 4
        categories.find { it.id == "security" }?.let { items.add(SettingsRowItem(id = "security", title = "Security", icon = Icons.Outlined.Security, category = it)) }
        categories.find { it.id == "incognito" }?.let { items.add(SettingsRowItem(id = "incognito", title = "Incognito mode", icon = Icons.Outlined.VisibilityOff, category = it)) }
        categories.find { it.id == "passwords" }?.let { items.add(SettingsRowItem(id = "passwords", title = "Password", icon = Icons.Outlined.Lock, category = it)) }
        categories.find { it.id == "clear_data" }?.let { items.add(SettingsRowItem(id = "clear_data", title = "Clear data", icon = Icons.Outlined.Delete, category = it)) }
        categories.find { it.id == "dns" }?.let { items.add(SettingsRowItem(id = "dns", title = "DNS", icon = Icons.Outlined.Dns, category = it)) }
        
        items.add(SettingsRowItem(isSectionGap = true))
        
        // Group 5
        val aboutCategory = categories.find { it.id == "about" }
        val rateOpt = aboutCategory?.options?.find { it.key == "about_rate" }
        val shareOpt = aboutCategory?.options?.find { it.key == "about_share" }
        val feedbackOpt = aboutCategory?.options?.find { it.key == "about_feedback" }
        
        items.add(SettingsRowItem(id = "rate_app", title = "Rate app", icon = Icons.Outlined.ThumbUp, action = {
            rateOpt?.let { handleAction(it) } ?: Toast.makeText(context, "Rate App", Toast.LENGTH_SHORT).show()
        }))
        items.add(SettingsRowItem(id = "recommend", title = "Recommend to a friend", icon = Icons.Outlined.FavoriteBorder, action = {
            shareOpt?.let { handleAction(it) } ?: Toast.makeText(context, "Recommend to a friend", Toast.LENGTH_SHORT).show()
        }))
        items.add(SettingsRowItem(id = "send_feedback", title = "Send feedback", icon = Icons.Outlined.Email, action = {
            feedbackOpt?.let { handleAction(it) } ?: Toast.makeText(context, "Send feedback", Toast.LENGTH_SHORT).show()
        }))
        aboutCategory?.let { items.add(SettingsRowItem(id = "about", title = "Information", icon = Icons.Outlined.Info, category = it)) }
        
        items
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // REDESIGNED HEADER BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color.Black)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchActive) {
                    IconButton(
                        onClick = { 
                            isSearchActive = false
                            searchQuery = ""
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Exit Search", tint = Color.White)
                    }
                    
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .testTag("settings_search_input"),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        singleLine = true,
                        cursorBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text("Search settings...", color = Color(0xFF8E8E93), fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )
                    
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color.White)
                        }
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (currentCategory != null) {
                                currentCategory = null
                            } else {
                                onDismiss()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        text = currentCategory?.title ?: "Settings",
                        style = TextStyle(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    
                    IconButton(
                        onClick = {
                            understandResetChecked = false
                            clearCacheChecked = true
                            showResetConfirmationDialog = true
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reset Settings", tint = Color.White)
                    }
                    
                    IconButton(
                        onClick = { isSearchActive = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Color.White)
                    }
                }
            }

            // MAIN LISTING VIEW
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Scenario A: Filtering Search Results Active
                if (searchQuery.isNotEmpty() || isSearchActive) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (filteredSearchResults.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No matching settings found", color = Color(0xFF8E8E93))
                                }
                            }
                        } else {
                            items(filteredSearchResults.size) { index ->
                                val (cat, opt) = filteredSearchResults[index]
                                SettingsOptionItemRow(
                                    option = opt,
                                    currentValue = getOptionValue(opt),
                                    onToggle = { setOptionValue(opt, it) },
                                    onDropdownClick = { showOptionSelectorDialog = opt },
                                    onSliderChange = { setOptionValue(opt, it) },
                                    onActionClick = { handleAction(opt) }
                                )
                            }
                        }
                    }
                }
                // Scenario B: Category Detail View Expanded
                else if (currentCategory != null) {
                    val category = currentCategory!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(category.options.size) { idx ->
                            val opt = category.options[idx]
                            SettingsOptionItemRow(
                                option = opt,
                                currentValue = getOptionValue(opt),
                                onToggle = { setOptionValue(opt, it) },
                                onDropdownClick = { showOptionSelectorDialog = opt },
                                onSliderChange = { setOptionValue(opt, it) },
                                onActionClick = { handleAction(opt) }
                            )
                        }
                    }
                }
                // Scenario C: Dashboard Sectioned List
                else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(settingsItemsList.size) { index ->
                            val item = settingsItemsList[index]
                            if (item.isSectionGap) {
                                Spacer(modifier = Modifier.height(12.dp))
                            } else {
                                SettingsListItem(
                                    title = item.title,
                                    icon = item.icon,
                                    onClick = {
                                        if (item.category != null) {
                                            currentCategory = item.category
                                        } else {
                                            item.action?.invoke()
                                        }
                                    },
                                    modifier = Modifier.testTag("category_card_${item.id}")
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    // Dynamic Option Picker Dialog (Dropdown fallback for details)
    if (showOptionSelectorDialog != null) {
        val option = showOptionSelectorDialog!!
        val currentValue = getOptionValue(option).toString()
        
        AlertDialog(
            onDismissRequest = { showOptionSelectorDialog = null },
            title = { Text(option.title, color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = Color(0xFF121214),
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    option.options.forEach { item ->
                        val isSelected = item == currentValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF1C1C1F) else Color.Transparent)
                                .clickable {
                                    setOptionValue(option, item)
                                    showOptionSelectorDialog = null
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    setOptionValue(option, item)
                                    showOptionSelectorDialog = null
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF3EA6FF), unselectedColor = Color(0xFF8E8E93))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(item, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Action Input Custom URL dialog
    if (showUrlInputDialog != null) {
        val key = showUrlInputDialog!!
        AlertDialog(
            onDismissRequest = { showUrlInputDialog = null },
            title = { Text("Configure Homepage URL", color = Color.White, fontWeight = FontWeight.Bold) },
            containerColor = Color(0xFF121214),
            text = {
                OutlinedTextField(
                    value = customUrlInput,
                    onValueChange = { customUrlInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3EA6FF),
                        unfocusedBorderColor = Color(0xFF1C1C1E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalUrl = if (customUrlInput.isBlank()) "about:blank" else customUrlInput
                        if (key == "homepage_url") {
                            onUpdateSettings(settings.copy(homepageUrl = finalUrl))
                        }
                        showUrlInputDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3EA6FF), contentColor = Color.Black)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlInputDialog = null }) {
                    Text("Cancel", color = Color(0xFF8E8E93))
                }
            }
        )
    }

    // Reset Settings Confirmation dialog
    if (showResetConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmationDialog = false },
            containerColor = Color(0xFF121214),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF4D4D)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset Settings?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "This will restore browser preferences, toolbar layout, homepage, appearance settings, and site permissions to their factory defaults.",
                        color = Color(0xFFE0E0E0),
                        fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Your bookmarks, downloads, saved passwords, and browsing history will not be deleted.",
                        color = Color(0xFFB0B0B0),
                        fontSize = 13.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    HorizontalDivider(color = Color(0xFF2C2C2E), thickness = 1.dp)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Clear cache and temporary files checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { clearCacheChecked = !clearCacheChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = clearCacheChecked,
                            onCheckedChange = { clearCacheChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF3EA6FF),
                                uncheckedColor = Color(0xFF8E8E93),
                                checkmarkColor = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Clear cache and temporary files (recommended)",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Understand cannot be undone checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { understandResetChecked = !understandResetChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = understandResetChecked,
                            onCheckedChange = { understandResetChecked = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFFF4D4D),
                                uncheckedColor = Color(0xFF8E8E93),
                                checkmarkColor = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "I understand this action cannot be undone.",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (understandResetChecked) {
                            if (clearCacheChecked) {
                                try {
                                    android.webkit.WebView(context).clearCache(true)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                            onUpdateSettings(BrowserSettings())
                            Toast.makeText(context, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                            showResetConfirmationDialog = false
                        }
                    },
                    enabled = understandResetChecked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF4D4D),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFFF4D4D).copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmationDialog = false }
                ) {
                    Text("Cancel", color = Color(0xFF8E8E93), fontWeight = FontWeight.Medium)
                }
            }
        )
    }
}

@Composable
fun SettingsListItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Normal
                ),
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(
            color = Color(0xFF1A1A1A),
            thickness = 1.dp
        )
    }
}

@Composable
fun SettingsOptionItemRow(
    option: SettingOption,
    currentValue: Any,
    onToggle: (Boolean) -> Unit,
    onDropdownClick: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onActionClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            when (option.type) {
                "toggle" -> {
                    Switch(
                        checked = currentValue as? Boolean ?: false,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF3EA6FF),
                            uncheckedThumbColor = Color(0xFF8E8E93),
                            uncheckedTrackColor = Color(0xFF1C1C1E)
                        )
                    )
                }
                "dropdown" -> {
                    TextButton(
                        onClick = onDropdownClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3EA6FF)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(currentValue.toString(), fontSize = 14.sp, fontWeight = FontWeight.Normal)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF3EA6FF))
                    }
                }
                "action" -> {
                    IconButton(
                        onClick = onActionClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Perform Action", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                "info" -> {
                    Text(
                        text = currentValue.toString(),
                        color = Color(0xFFB3B3B3),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
        
        if (option.type == "slider") {
            val floatVal = (currentValue as? Float) ?: option.defaultValue as Float
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                Slider(
                    value = floatVal,
                    onValueChange = onSliderChange,
                    valueRange = option.range,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF3EA6FF),
                        activeTrackColor = Color(0xFF3EA6FF),
                        inactiveTrackColor = Color(0xFF1C1C1E)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "${floatVal.toInt()}${option.unit}",
                    color = Color(0xFF3EA6FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.widthIn(min = 36.dp)
                )
            }
        }
        
        HorizontalDivider(
            color = Color(0xFF1A1A1A),
            thickness = 1.dp
        )
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
