package com.example.browser.download

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

class DownloadPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kivo_download_prefs", Context.MODE_PRIVATE)

    var downloadFolder: String
        get() = prefs.getString(
            "download_folder",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        ) ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        set(value) = prefs.edit().putString("download_folder", value).apply()

    var askWhereToSave: Boolean
        get() = prefs.getBoolean("ask_where_to_save", false)
        set(value) = prefs.edit().putBoolean("ask_where_to_save", value).apply()

    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi_only", false)
        set(value) = prefs.edit().putBoolean("download_wifi_only", value).apply()

    var maxSimultaneousDownloads: Int
        get() = prefs.getInt("max_simultaneous_downloads", 3)
        set(value) = prefs.edit().putInt("max_simultaneous_downloads", value).apply()

    var autoResume: Boolean
        get() = prefs.getBoolean("auto_resume", true)
        set(value) = prefs.edit().putBoolean("auto_resume", value).apply()

    var smartFileNaming: Boolean
        get() = prefs.getBoolean("smart_file_naming", true)
        set(value) = prefs.edit().putBoolean("smart_file_naming", value).apply()

    var duplicateAction: String
        get() = prefs.getString("duplicate_action", "RENAME") ?: "RENAME"
        set(value) = prefs.edit().putString("duplicate_action", value).apply()

    var autoDeleteFailed: Boolean
        get() = prefs.getBoolean("auto_delete_failed", false)
        set(value) = prefs.edit().putBoolean("auto_delete_failed", value).apply()

    var openFileAfterDownload: Boolean
        get() = prefs.getBoolean("open_file_after_download", false)
        set(value) = prefs.edit().putBoolean("open_file_after_download", value).apply()
}
