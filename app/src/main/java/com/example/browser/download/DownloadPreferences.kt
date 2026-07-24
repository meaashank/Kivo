package com.example.browser.download

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

class DownloadPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kivo_download_prefs", Context.MODE_PRIVATE)

    companion object {
        fun getDefaultDownloadDir(context: Context): String {
            return try {
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pubDir != null && (pubDir.exists() || pubDir.mkdirs())) {
                    pubDir.absolutePath
                } else {
                    val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    appDir?.absolutePath ?: "/storage/emulated/0/Download"
                }
            } catch (e: Exception) {
                val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                appDir?.absolutePath ?: "/storage/emulated/0/Download"
            }
        }
    }

    var downloadFolder: String
        get() {
            val saved = prefs.getString("download_folder", null)
            if (!saved.isNullOrEmpty()) {
                val file = java.io.File(saved)
                if (file.exists() || file.mkdirs()) {
                    return saved
                }
            }
            val defaultPath = getDefaultDownloadDir(context)
            prefs.edit().putString("download_folder", defaultPath).apply()
            return defaultPath
        }
        set(value) = prefs.edit().putString("download_folder", value).apply()

    var askWhereToSave: Boolean
        get() = prefs.getBoolean("ask_where_to_save", true)
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
