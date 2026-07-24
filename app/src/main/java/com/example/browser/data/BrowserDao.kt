package com.example.browser.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserDao {

    // --- History Queries ---
    @Query("SELECT * FROM history_items ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history_items WHERE url = :url LIMIT 1")
    suspend fun getHistoryByUrl(url: String): HistoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)

    @Update
    suspend fun updateHistory(item: HistoryItem)

    @Delete
    suspend fun deleteHistory(item: HistoryItem)

    @Query("DELETE FROM history_items")
    suspend fun clearHistory()

    // --- Bookmarks Queries ---
    @Query("SELECT * FROM bookmark_items ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<BookmarkItem>>

    @Query("SELECT * FROM bookmark_items WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): BookmarkItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(item: BookmarkItem)

    @Update
    suspend fun updateBookmark(item: BookmarkItem)

    @Delete
    suspend fun deleteBookmark(item: BookmarkItem)

    @Query("SELECT * FROM bookmark_items WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchBookmarks(query: String): Flow<List<BookmarkItem>>

    // --- Downloads Queries ---
    @Query("SELECT * FROM download_items ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM download_items WHERE status = 'RUNNING' OR status = 'PENDING'")
    suspend fun getActiveDownloads(): List<DownloadItem>

    @Query("SELECT * FROM download_items WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: Long): DownloadItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(item: DownloadItem): Long

    @Update
    suspend fun updateDownload(item: DownloadItem)

    @Delete
    suspend fun deleteDownload(item: DownloadItem)

    @Query("DELETE FROM download_items WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)

    @Query("DELETE FROM download_items")
    suspend fun clearDownloads()
}
