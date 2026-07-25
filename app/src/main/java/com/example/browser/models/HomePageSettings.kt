package com.example.browser.models

enum class ShortcutIconSize(val displayName: String, val dpSize: Int) {
    SMALL("Small", 44),
    LARGE("Large", 56)
}

enum class SearchBarPosition(val displayName: String) {
    TOP("Top"),
    BOTTOM("Bottom")
}

enum class NewsLayout(val displayName: String) {
    CARD("Card Grid"),
    COMPACT("Compact List")
}

enum class BackgroundStyle(val displayName: String) {
    AMOLED("AMOLED Black"),
    DARK("Solid Dark Gray"),
    GRADIENT_CYAN("Cyan Nebula"),
    GRADIENT_PURPLE("Cosmic Purple"),
    GRADIENT_GOLD("Aero Gold")
}

enum class HomeSection(val id: String, val title: String, val iconName: String) {
    GREETING_WEATHER("greeting_weather", "Greeting & Weather", "WbSunny"),
    SEARCH_BAR("search_bar", "Search Bar", "Search"),
    SHORTCUTS("shortcuts", "Shortcuts", "GridOn"),
    CONTINUE_BROWSING("continue_browsing", "Continue Browsing", "Tab"),
    RECENTLY_VISITED("recently_visited", "Recently Visited", "History"),
    BOOKMARKS("bookmarks", "Bookmarks", "BookmarkBorder"),
    DOWNLOADS("downloads", "Downloads", "FileDownload"),
    READING_LIST("reading_list", "Reading List", "MenuBook"),
    NEWS_FEED("news_feed", "News Feed", "Newspaper")
}

data class HomePageSettings(
    // Section Visibility
    val showSearchBar: Boolean = true,
    val showGreetingWeather: Boolean = true,
    val showShortcuts: Boolean = true,
    val showNewsFeed: Boolean = true,
    val showRecentlyVisited: Boolean = true,
    val showDownloads: Boolean = true,
    val showBookmarks: Boolean = true,
    val showReadingList: Boolean = true,
    val showContinueBrowsing: Boolean = true,

    // Section Display Order (IDs)
    val sectionOrder: List<String> = listOf(
        HomeSection.GREETING_WEATHER.id,
        HomeSection.SEARCH_BAR.id,
        HomeSection.SHORTCUTS.id,
        HomeSection.CONTINUE_BROWSING.id,
        HomeSection.RECENTLY_VISITED.id,
        HomeSection.BOOKMARKS.id,
        HomeSection.DOWNLOADS.id,
        HomeSection.READING_LIST.id,
        HomeSection.NEWS_FEED.id
    ),

    // Appearance
    val backgroundStyle: BackgroundStyle = BackgroundStyle.AMOLED,
    val blurAmountDp: Int = 0,
    val cornerRadiusDp: Int = 20,

    // Shortcuts
    val shortcutColumns: Int = 5, // 4 to 8 columns
    val shortcutIconSize: ShortcutIconSize = ShortcutIconSize.LARGE,
    val showShortcutLabels: Boolean = true,
    val showShortcutShadow: Boolean = true,

    // News
    val newsLanguage: String = "English",
    val newsRegion: String = "United States",
    val newsReaderModeDefault: Boolean = false,
    val newsItemCount: Int = 6,
    val newsLayout: NewsLayout = NewsLayout.CARD,
    val showFloatingSearchButton: Boolean = true,

    // Search Bar
    val searchBarPosition: SearchBarPosition = SearchBarPosition.TOP,
    val showVoiceSearch: Boolean = true,
    val showQrScanner: Boolean = true,
    val showClipboardSuggestion: Boolean = true
)
