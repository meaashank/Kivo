package com.example.browser.settings

import com.example.browser.models.SearchEngine

data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val isDesktopModeByDefault: Boolean = false,
    val isAdBlockEnabled: Boolean = true,
    val isJavaScriptEnabled: Boolean = true,
    val isCookiesEnabled: Boolean = true,
    val clearCacheOnExit: Boolean = false,
    val clearHistoryOnExit: Boolean = false,
    val homepageUrl: String = "about:blank"
)
