package com.example.browser.models

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "New Tab",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isIncognito: Boolean = false,
    val isPinned: Boolean = false,
    val lastVisitedTime: Long = System.currentTimeMillis(),
    val isAudioPlaying: Boolean = false,
    val groupName: String? = null,
    val customTitle: String? = null
) {
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() } ?: title

    val domain: String
        get() = when {
            url.isBlank() || url == "about:blank" -> "New Tab"
            else -> try {
                val host = java.net.URI(url).host ?: url
                if (host.startsWith("www.")) host.substring(4) else host
            } catch (e: Exception) {
                url.substringBefore("/").take(25)
            }
        }
}
