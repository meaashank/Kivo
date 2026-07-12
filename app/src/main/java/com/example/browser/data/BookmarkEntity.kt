package com.example.browser.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmark_items")
data class BookmarkItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val folder: String = "Bookmarks", // Supports folders as requested
    val timestamp: Long = System.currentTimeMillis()
)
