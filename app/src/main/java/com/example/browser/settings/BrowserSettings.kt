package com.example.browser.settings

import androidx.compose.ui.graphics.Color
import com.example.browser.models.SearchEngine

enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow System"),
    LIGHT("Light Theme"),
    DARK("Dark Theme")
}

enum class AppAccentColor(val displayName: String, val hexVal: Long) {
    SOUL_PURPLE("Soul Purple", 0xFF8E24AA),
    ELECTRIC_PURPLE("Electric Purple", 0xFFAB47BC),
    VIBRANT_BLUE("Vibrant Blue", 0xFF2196F3),
    NEON_CYAN("Neon Cyan", 0xFF00E5FF),
    TEAL_PREMIUM("Teal Premium", 0xFF14FFC2),
    SUNSET_CORAL("Sunset Coral", 0xFFFF6B6B),
    NEON_AMBER("Neon Amber", 0xFFFFB300),
    EMERALD_GREEN("Emerald Green", 0xFF00E676),
    CARBON_WHITE("Carbon White", 0xFFFFFFFF);

    val color: Color
        get() = Color(hexVal)
}

data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AppAccentColor = AppAccentColor.SOUL_PURPLE,
    val useDynamicColor: Boolean = false,
    val isDesktopModeByDefault: Boolean = false,
    val isAdBlockEnabled: Boolean = true,
    val isJavaScriptEnabled: Boolean = true,
    val isCookiesEnabled: Boolean = true,
    val clearCacheOnExit: Boolean = false,
    val clearHistoryOnExit: Boolean = false,
    val homepageUrl: String = "about:blank"
)


