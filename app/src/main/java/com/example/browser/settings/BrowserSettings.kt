package com.example.browser.settings

import com.example.browser.models.SearchEngine

enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow System"),
    LIGHT("Light Theme"),
    DARK("Dark Theme")
}

data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val isDesktopModeByDefault: Boolean = false,
    val isAdBlockEnabled: Boolean = true,
    val isJavaScriptEnabled: Boolean = true,
    val isCookiesEnabled: Boolean = true,
    val clearCacheOnExit: Boolean = false,
    val clearHistoryOnExit: Boolean = false,
    val homepageUrl: String = "about:blank"
)

