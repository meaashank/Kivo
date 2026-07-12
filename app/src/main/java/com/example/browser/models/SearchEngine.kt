package com.example.browser.models

enum class SearchEngine(val displayName: String, val searchUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p=");

    companion object {
        fun fromName(name: String): SearchEngine {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GOOGLE
        }
    }
}
