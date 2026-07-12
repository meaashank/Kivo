package com.example.browser.data

import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val dao: BrowserDao) {

    val allHistory: Flow<List<HistoryItem>> = dao.getAllHistory()
    val allBookmarks: Flow<List<BookmarkItem>> = dao.getAllBookmarks()

    suspend fun getBookmarkByUrl(url: String): BookmarkItem? {
        return dao.getBookmarkByUrl(url)
    }

    suspend fun addHistoryEntry(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val existing = dao.getHistoryByUrl(url)
        if (existing != null) {
            dao.updateHistory(
                existing.copy(
                    title = if (title.isNotBlank()) title else existing.title,
                    timestamp = System.currentTimeMillis(),
                    visitCount = existing.visitCount + 1
                )
            )
        } else {
            dao.insertHistory(
                HistoryItem(
                    url = url,
                    title = if (title.isNotBlank()) title else url,
                    timestamp = System.currentTimeMillis(),
                    visitCount = 1
                )
            )
        }
    }

    suspend fun deleteHistory(item: HistoryItem) {
        dao.deleteHistory(item)
    }

    suspend fun clearHistory() {
        dao.clearHistory()
    }

    suspend fun addBookmark(url: String, title: String, folder: String = "Bookmarks") {
        if (url.isBlank() || url == "about:blank") return
        val existing = dao.getBookmarkByUrl(url)
        if (existing == null) {
            dao.insertBookmark(
                BookmarkItem(
                    url = url,
                    title = if (title.isNotBlank()) title else url,
                    folder = folder
                )
            )
        }
    }

    suspend fun removeBookmark(url: String) {
        val existing = dao.getBookmarkByUrl(url)
        if (existing != null) {
            dao.deleteBookmark(existing)
        }
    }

    suspend fun updateBookmark(item: BookmarkItem) {
        dao.updateBookmark(item)
    }

    suspend fun deleteBookmark(item: BookmarkItem) {
        dao.deleteBookmark(item)
    }

    fun searchBookmarks(query: String): Flow<List<BookmarkItem>> {
        return dao.searchBookmarks(query)
    }
}
