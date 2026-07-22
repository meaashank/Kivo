package com.example.browser.ui

import android.app.Application
import android.app.DownloadManager
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.BookmarkItem
import com.example.browser.data.BrowserDatabase
import com.example.browser.data.BrowserRepository
import com.example.browser.data.HistoryItem
import com.example.browser.models.BrowserTab
import com.example.browser.models.SearchEngine
import com.example.browser.settings.BrowserSettings
import com.example.browser.settings.ThemeMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.UUID

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = BrowserDatabase.getDatabase(application)
    private val repository = BrowserRepository(db.browserDao())

    // Tabs & Active Tab
    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    // Settings
    private val _settings = MutableStateFlow(BrowserSettings())
    val settings: StateFlow<BrowserSettings> = _settings.asStateFlow()

    // Closed tabs stack to support "Restore closed tabs"
    private val closedTabsStack = ArrayDeque<BrowserTab>()

    // WebView Caching and context management
    private val webViewMap = mutableMapOf<String, WebView>()
    private val contextWrapperMap = mutableMapOf<String, MutableContextWrapper>()

    // Database flows for UI
    val history: StateFlow<List<HistoryItem>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkItem>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dialog state for JS Alert/Confirm/Prompt
    var activeJsDialog = MutableStateFlow<JsDialogState?>(null)
        private set

    // File chooser state for file uploads
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Fullscreen custom view for HTML5 video
    var customVideoView = MutableStateFlow<CustomViewData?>(null)
        private set

    // Shortcuts Flow
    private val _shortcuts = MutableStateFlow<List<ShortcutInfo>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutInfo>> = _shortcuts.asStateFlow()

    // Reading Mode Flow
    private val _isReadingModeActive = MutableStateFlow(false)
    val isReadingModeActive: StateFlow<Boolean> = _isReadingModeActive.asStateFlow()

    fun toggleReadingMode() {
        _isReadingModeActive.value = !_isReadingModeActive.value
        Toast.makeText(getApplication(), if (_isReadingModeActive.value) "Reading mode activated" else "Reading mode deactivated", Toast.LENGTH_SHORT).show()
    }

    // Tab thumbnail previews map
    private val _tabThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val tabThumbnails: StateFlow<Map<String, Bitmap>> = _tabThumbnails.asStateFlow()

    private val sharedPrefs = application.getSharedPreferences("kivo_browser_shortcuts", Context.MODE_PRIVATE)

    init {
        loadShortcuts()
        // Create an initial homepage tab
        createNewTab("about:blank")
    }

    private fun loadShortcuts() {
        val serialized = sharedPrefs.getString("shortcuts_list", null)
        if (serialized == null) {
            val defaults = listOf(
                ShortcutInfo("Google", "https://www.google.com", "Search", "0xFF4285F4"),
                ShortcutInfo("YouTube", "https://www.youtube.com", "Play", "0xFFFF0000"),
                ShortcutInfo("Facebook", "https://www.facebook.com", "Forum", "0xFF3B5998"),
                ShortcutInfo("Instagram", "https://www.instagram.com", "Image", "0xFFE1306C"),
                ShortcutInfo("X", "https://www.x.com", "Code", "0xFF111111")
            )
            saveShortcutsToPrefs(defaults)
            _shortcuts.value = defaults
        } else {
            val list = mutableListOf<ShortcutInfo>()
            serialized.split(";;").forEach { itemStr ->
                if (itemStr.isNotBlank()) {
                    val parts = itemStr.split("||")
                    if (parts.size >= 4) {
                        list.add(ShortcutInfo(parts[0], parts[1], parts[2], parts[3]))
                    }
                }
            }
            _shortcuts.value = list
        }
    }

    private fun saveShortcutsToPrefs(list: List<ShortcutInfo>) {
        val builder = java.lang.StringBuilder()
        list.forEach { item ->
            builder.append(item.label).append("||")
                .append(item.url).append("||")
                .append(item.iconName).append("||")
                .append(item.colorHex).append(";;")
        }
        sharedPrefs.edit().putString("shortcuts_list", builder.toString()).apply()
    }

    fun addCustomShortcut(label: String, url: String) {
        val parsedUrl = parseUrl(url)
        val newItem = ShortcutInfo(label, parsedUrl, "Default", "0xFF3EA6FF")
        val newList = _shortcuts.value + newItem
        _shortcuts.value = newList
        saveShortcutsToPrefs(newList)
        Toast.makeText(getApplication(), "Shortcut added", Toast.LENGTH_SHORT).show()
    }

    fun deleteShortcut(item: ShortcutInfo) {
        val newList = _shortcuts.value.filter { it.url != item.url || it.label != item.label }
        _shortcuts.value = newList
        saveShortcutsToPrefs(newList)
        Toast.makeText(getApplication(), "Shortcut removed", Toast.LENGTH_SHORT).show()
    }

    // --- Tab Lifecycle ---
    fun createNewTab(url: String, isIncognito: Boolean = false, select: Boolean = true): String {
        val tabId = UUID.randomUUID().toString()
        val initialUrl = if (url.isBlank() || url == "about:blank") "about:blank" else parseUrl(url)
        val newTab = BrowserTab(
            id = tabId,
            url = initialUrl,
            isIncognito = isIncognito,
            title = if (initialUrl == "about:blank") "New Tab" else "Loading..."
        )
        
        _tabs.value = _tabs.value + newTab
        if (select) {
            _activeTabId.value = tabId
        }
        return tabId
    }

    fun closeTab(tabId: String) {
        val currentTabs = _tabs.value
        val tabToClose = currentTabs.firstOrNull { it.id == tabId } ?: return

        // Save to closed tab history if not incognito
        if (!tabToClose.isIncognito && tabToClose.url != "about:blank") {
            closedTabsStack.addLast(tabToClose)
            if (closedTabsStack.size > 15) {
                closedTabsStack.removeFirst()
            }
        }

        // Clean up the WebView to prevent leaks
        cleanupWebView(tabId)

        // Clean up tab thumbnail bitmap to prevent memory leak
        val currentThumbnails = _tabThumbnails.value.toMutableMap()
        currentThumbnails.remove(tabId)
        _tabThumbnails.value = currentThumbnails

        val updatedTabs = currentTabs.filter { it.id != tabId }
        _tabs.value = updatedTabs

        if (_activeTabId.value == tabId) {
            if (updatedTabs.isNotEmpty()) {
                // Switch to the nearest tab
                _activeTabId.value = updatedTabs.last().id
            } else {
                // If no tabs left, create a fresh homepage tab
                createNewTab("about:blank")
            }
        }
    }

    fun closeAllTabsOfCategory(isIncognito: Boolean) {
        val currentTabs = _tabs.value
        val tabsToClose = currentTabs.filter { it.isIncognito == isIncognito }
        
        tabsToClose.forEach { tab ->
            cleanupWebView(tab.id)
        }

        val currentThumbnails = _tabThumbnails.value.toMutableMap()
        tabsToClose.forEach { tab ->
            currentThumbnails.remove(tab.id)
        }
        _tabThumbnails.value = currentThumbnails

        val remainingTabs = currentTabs.filter { it.isIncognito != isIncognito }
        _tabs.value = remainingTabs

        val activeId = _activeTabId.value
        val isActiveTabClosed = tabsToClose.any { it.id == activeId }
        
        if (isActiveTabClosed || activeId == null) {
            if (remainingTabs.isNotEmpty()) {
                _activeTabId.value = remainingTabs.last().id
            } else {
                createNewTab("about:blank", isIncognito = isIncognito)
            }
        } else if (remainingTabs.isEmpty()) {
            createNewTab("about:blank", isIncognito = isIncognito)
        }
    }

    // Ad and Tracker blocker domains for fast request interception
    private val adDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "advertising.com", "taboola.com",
        "outbrain.com", "criteo.com", "amazon-adsystem.com", "popads.net",
        "popcash.net", "exoclick.com", "propellerads.com", "adsterra.com",
        "syndication.exoclick.com", "serving-sys.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "mediavine.com", "adroll.com"
    )

    private fun isAdOrTracker(url: String): Boolean {
        if (!_settings.value.isAdBlockEnabled) return false
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (host.isEmpty()) return false
        for (adDomain in adDomains) {
            if (host == adDomain || host.endsWith(".$adDomain")) {
                return true
            }
        }
        return false
    }

    fun captureThumbnail(tabId: String, webView: WebView) {
        try {
            val width = webView.width
            val height = webView.height
            if (width > 0 && height > 0) {
                // Scaled down thumbnail size for memory efficiency
                val targetWidth = 320
                val targetHeight = (height.toFloat() / width.toFloat() * targetWidth).toInt().coerceIn(100, 500)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(targetWidth.toFloat() / width.toFloat(), targetHeight.toFloat() / height.toFloat())
                webView.draw(canvas)
                
                // Recycle old thumbnail bitmap to prevent memory leak
                val current = _tabThumbnails.value.toMutableMap()
                current[tabId]?.recycle()
                current[tabId] = bitmap
                _tabThumbnails.value = current
            }
        } catch (e: Exception) {
            Log.e("BrowserViewModel", "Failed to capture thumbnail for $tabId", e)
        }
    }

    fun captureActiveTabThumbnail() {
        val activeId = _activeTabId.value ?: return
        val webView = webViewMap[activeId] ?: return
        captureThumbnail(activeId, webView)
    }

    fun restoreLastClosedTab() {
        if (closedTabsStack.isNotEmpty()) {
            val tab = closedTabsStack.removeLast()
            createNewTab(tab.url, isIncognito = tab.isIncognito)
        } else {
            Toast.makeText(getApplication(), "No recently closed tabs to restore", Toast.LENGTH_SHORT).show()
        }
    }

    fun duplicateTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        createNewTab(tab.url, isIncognito = tab.isIncognito)
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        // Pause/resume WebViews to optimize battery and background CPU
        webViewMap.forEach { (id, webView) ->
            if (id == tabId) {
                webView.onResume()
            } else {
                webView.onPause()
            }
        }
    }

    // --- WebView Engine & Management ---
    fun getOrCreateWebView(tabId: String, context: Context): WebView {
        val existingWrapper = contextWrapperMap[tabId]
        if (existingWrapper != null) {
            // Update context wrapper to prevent leaks and make UI dialogs work
            existingWrapper.baseContext = context
            return webViewMap[tabId]!!
        }

        // Setup a mutable context wrapper to handle activity recreation safely
        val wrapper = MutableContextWrapper(context)
        val webView = WebView(wrapper)
        
        contextWrapperMap[tabId] = wrapper
        webViewMap[tabId] = webView

        configureWebView(tabId, webView)
        
        // Initial load
        val tab = _tabs.value.firstOrNull { it.id == tabId }
        if (tab != null && tab.url != "about:blank") {
            webView.loadUrl(tab.url)
        }

        return webView
    }

    private fun configureWebView(tabId: String, webView: WebView) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val settingsState = _settings.value

        // Enable hardware acceleration on WebView layer
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        // Prevent white screen flash before page renders
        webView.setBackgroundColor(android.graphics.Color.parseColor("#121212"))

        webView.settings.apply {
            javaScriptEnabled = settingsState.isJavaScriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            
            // Rendering & Offscreen pre-raster optimizations
            offscreenPreRaster = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true

            // Fast text autosizing
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            
            // Desktop mode agent configuration
            if (tab.isDesktopMode) {
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            } else {
                userAgentString = null // Use default native agent
            }
        }

        // WebStorage and Cookie configuration
        CookieManager.getInstance().apply {
            setAcceptCookie(settingsState.isCookiesEnabled)
            setAcceptThirdPartyCookies(webView, settingsState.isCookiesEnabled)
            flush()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (isAdOrTracker(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Intercept custom intents/schemes
                if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        intent.addCategory(Intent.CATEGORY_BROWSABLE)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        getApplication<Application>().startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Log.e("BrowserEngine", "Failed to launch intent scheme: $url", e)
                        // Handle standard protocols natively if intent parsing fails
                        if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("geo:") || url.startsWith("market:")) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                getApplication<Application>().startActivity(intent)
                                return true
                            } catch (ex: Exception) {
                                Toast.makeText(getApplication(), "No app found to handle link", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { updateTabState(tabId, url = it, isLoading = true, progress = 10) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val title = view?.title ?: "New Tab"
                url?.let {
                    updateTabState(
                        tabId = tabId,
                        url = it,
                        title = if (it == "about:blank") "New Tab" else title,
                        isLoading = false,
                        progress = 100,
                        canGoBack = view?.canGoBack() ?: false,
                        canGoForward = view?.canGoForward() ?: false
                    )
                    
                    // Save history entry if not incognito
                    if (!tab.isIncognito && it != "about:blank" && !it.startsWith("file://")) {
                        viewModelScope.launch {
                            repository.addHistoryEntry(it, title)
                        }
                    }
                }
                
                // Capture thumbnail after page rendering has finished and settled
                view?.postDelayed({
                    view?.let { captureThumbnail(tabId, it) }
                }, 500)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only show custom error for main frame requests
                if (request?.isForMainFrame == true) {
                    val errorHtml = """
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                            <style>
                                body { font-family: -apple-system, sans-serif; padding: 32px; background-color: #f7f9fc; color: #1c1b1f; text-align: center; }
                                h1 { font-size: 22px; margin-top: 48px; color: #ba1a1a; }
                                p { font-size: 15px; color: #49454f; line-height: 1.5; }
                                .btn { display: inline-block; background-color: #0061a4; color: white; padding: 12px 24px; border-radius: 20px; text-decoration: none; font-weight: 500; margin-top: 24px; font-size: 14px; }
                            </style>
                        </head>
                        <body>
                            <h1>Connection Error</h1>
                            <p>Unable to load page. Check your internet connection or verify the URL.</p>
                            <p><small>${error?.description ?: "DNS or Connection Failure"}</small></p>
                            <a class="btn" href="${view?.url}">Retry</a>
                        </body>
                        </html>
                    """.trimIndent()
                    view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                updateTabState(tabId, progress = newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                title?.let { updateTabState(tabId, title = it) }
            }

            // JavaScript Dialog Interceptions
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                activeJsDialog.value = JsDialogState.Alert(message ?: "", result)
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                activeJsDialog.value = JsDialogState.Confirm(message ?: "", result)
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                activeJsDialog.value = JsDialogState.Prompt(message ?: "", defaultValue ?: "", result)
                return true
            }

            // File Chooser for Upload Support
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                // Signal UI layer to open native file chooser
                _fileUploadRequestEvent.tryEmit(fileChooserParams?.acceptTypes ?: arrayOf("*/*"))
                return true
            }

            // Fullscreen video support
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    customVideoView.value = CustomViewData(view, callback)
                }
            }

            override fun onHideCustomView() {
                customVideoView.value = null
            }
        }

        // Intercept download system
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading via Kivo")
                    val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setTitle(guessedName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                }
                val downloadManager = getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(getApplication(), "Starting download...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("BrowserEngine", "Download failed", e)
                Toast.makeText(getApplication(), "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val _fileUploadRequestEvent = MutableSharedFlow<Array<String>>(extraBufferCapacity = 1)
    val fileUploadRequestEvent: SharedFlow<Array<String>> = _fileUploadRequestEvent.asSharedFlow()

    // --- Tab state modification ---
    private fun updateTabState(
        tabId: String,
        url: String? = null,
        title: String? = null,
        progress: Int? = null,
        isLoading: Boolean? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null
    ) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(
                    url = url ?: tab.url,
                    title = title ?: tab.title,
                    progress = progress ?: tab.progress,
                    isLoading = isLoading ?: tab.isLoading,
                    canGoBack = canGoBack ?: tab.canGoBack,
                    canGoForward = canGoForward ?: tab.canGoForward
                )
            } else {
                tab
            }
        }
    }

    private fun cleanupWebView(tabId: String) {
        webViewMap.remove(tabId)?.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        contextWrapperMap.remove(tabId)
    }

    override fun onCleared() {
        super.onCleared()
        // Prevent all memory leaks on ViewModel destruction
        webViewMap.keys.toList().forEach { cleanupWebView(it) }
    }

    // --- Browser Navigation API ---
    fun loadUrlInActiveTab(url: String) {
        val activeId = _activeTabId.value ?: return
        val parsedUrl = parseUrl(url)
        
        val webView = webViewMap[activeId]
        if (webView != null) {
            webView.loadUrl(parsedUrl)
        } else {
            // If WebView has not been attached yet, just update state so factory will load it
            updateTabState(activeId, url = parsedUrl)
        }
    }

    fun navigateBackInActiveTab() {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }

    fun navigateForwardInActiveTab() {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.let {
            if (it.canGoForward()) {
                it.goForward()
            }
        }
    }

    fun refreshActiveTab() {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.reload()
    }

    fun stopLoadingActiveTab() {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.stopLoading()
    }

    fun toggleDesktopModeInActiveTab() {
        val activeId = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == activeId) {
                val newDesktop = !tab.isDesktopMode
                webViewMap[activeId]?.settings?.userAgentString = if (newDesktop) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    null
                }
                webViewMap[activeId]?.reload()
                tab.copy(isDesktopMode = newDesktop)
            } else {
                tab
            }
        }
    }

    // --- Settings, Bookmarks, and History Operations ---
    fun setSettings(newSettings: BrowserSettings) {
        _settings.value = newSettings
        // Propagate settings to active WebViews dynamically
        webViewMap.values.forEach { webView ->
            webView.settings.javaScriptEnabled = newSettings.isJavaScriptEnabled
            CookieManager.getInstance().apply {
                setAcceptCookie(newSettings.isCookiesEnabled)
                setAcceptThirdPartyCookies(webView, newSettings.isCookiesEnabled)
            }
        }
    }

    // Find in Page states
    private val _findInPageActive = MutableStateFlow(false)
    val findInPageActive: StateFlow<Boolean> = _findInPageActive.asStateFlow()

    private val _findInPageQuery = MutableStateFlow("")
    val findInPageQuery: StateFlow<String> = _findInPageQuery.asStateFlow()

    fun setFindInPageActive(active: Boolean) {
        _findInPageActive.value = active
        if (!active) {
            _findInPageQuery.value = ""
            clearFind()
        }
    }

    fun setFindInPageQuery(query: String) {
        _findInPageQuery.value = query
        findInPage(query)
    }

    fun findInPage(text: String) {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.findAllAsync(text)
    }

    fun findNext(forward: Boolean) {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.findNext(forward)
    }

    fun clearFind() {
        val activeId = _activeTabId.value ?: return
        webViewMap[activeId]?.clearMatches()
    }

    fun toggleAdBlock() {
        val current = _settings.value
        setSettings(current.copy(isAdBlockEnabled = !current.isAdBlockEnabled))
        Toast.makeText(getApplication(), "Ad blocker " + (if (_settings.value.isAdBlockEnabled) "Enabled" else "Disabled"), Toast.LENGTH_SHORT).show()
    }

    fun toggleNightMode() {
        val current = _settings.value
        val newMode = if (current.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        setSettings(current.copy(themeMode = newMode))
        Toast.makeText(getApplication(), "Theme switched", Toast.LENGTH_SHORT).show()
    }

    fun savePageArchive() {
        val activeId = _activeTabId.value ?: return
        val tab = _tabs.value.firstOrNull { it.id == activeId } ?: return
        val webView = webViewMap[activeId] ?: return
        if (tab.url == "about:blank") return

        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val cleanTitle = tab.title.replace("[^a-zA-Z0-9]".toRegex(), "_").take(30)
        val file = java.io.File(folder, "$cleanTitle.mhtml")
        try {
            webView.saveWebArchive(file.absolutePath)
            Toast.makeText(getApplication(), "Saved page as MHTML in Downloads", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(getApplication(), "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleBookmarkActiveTab() {
        val activeId = _activeTabId.value ?: return
        val tab = _tabs.value.firstOrNull { it.id == activeId } ?: return
        if (tab.url == "about:blank") return

        viewModelScope.launch {
            val existing = repository.getBookmarkByUrl(tab.url)
            if (existing != null) {
                repository.deleteBookmark(existing)
                Toast.makeText(getApplication(), "Removed from Bookmarks", Toast.LENGTH_SHORT).show()
            } else {
                repository.addBookmark(tab.url, tab.title)
                Toast.makeText(getApplication(), "Saved to Bookmarks", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun isBookmarked(url: String): Flow<Boolean> {
        return bookmarks.map { list -> list.any { it.url == url } }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            repository.deleteHistory(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun deleteBookmarkItem(item: BookmarkItem) {
        viewModelScope.launch {
            repository.deleteBookmark(item)
        }
    }

    // --- Helper URL parser ---
    private fun parseUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == "about:blank") return "about:blank"
        
        // Check if standard URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://")) {
            return trimmed
        }

        // Domain verification regex (matches e.g., google.com, example.co.uk)
        val domainPattern = "^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$".toRegex()
        if (trimmed.matches(domainPattern) && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }

        // Default: Search term via search engine
        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
        return _settings.value.searchEngine.searchUrl + encodedQuery
    }
}

// Dialog architectures helper states
sealed class JsDialogState {
    abstract val message: String
    abstract val result: JsResult?

    data class Alert(override val message: String, override val result: JsResult?) : JsDialogState()
    data class Confirm(override val message: String, override val result: JsResult?) : JsDialogState()
    data class Prompt(override val message: String, val defaultValue: String, val promptResult: JsPromptResult?) : JsDialogState() {
        override val result: JsResult? = promptResult
    }
}

data class CustomViewData(val view: android.view.View, val callback: WebChromeClient.CustomViewCallback)

data class ShortcutInfo(
    val label: String,
    val url: String,
    val iconName: String,
    val colorHex: String
)
