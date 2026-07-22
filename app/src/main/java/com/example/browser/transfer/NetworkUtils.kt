package com.example.browser.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(context: Context): String? {
        try {
            // First try WifiManager IP format
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && wifiManager.isWifiEnabled) {
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt != 0) {
                    val ip = String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    if (ip != "0.0.0.0") return ip
                }
            }

            // Fallback: iterate over all network interfaces (Hotspot, WLAN, Ethernet)
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("p2p") || name.contains("tun") || name.contains("dummy")) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getWifiSsid(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "")
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                ssid
            } else {
                "Local Network / Wi-Fi"
            }
        } catch (e: Exception) {
            "Local Network"
        }
    }

    fun findAvailablePort(startPort: Int = 8080): Int {
        for (port in startPort until startPort + 50) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (e: Exception) {
                // Port occupied, try next
            }
        }
        // Fallback to dynamic system port
        return try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            8080
        }
    }

    fun isConnectedToWifiOrHotspot(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    getLocalIpAddress(context) != null
        } else {
            val info = cm.activeNetworkInfo
            return info != null && info.isConnected
        }
    }
}
