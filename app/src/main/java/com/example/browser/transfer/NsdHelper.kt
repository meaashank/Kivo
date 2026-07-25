package com.example.browser.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

class NsdHelper(private val context: Context) {

    private val serviceType = "_kivoshare._tcp."
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    interface DiscoveryCallback {
        fun onDeviceDiscovered(device: DiscoveredDevice)
        fun onDeviceLost(deviceId: String)
    }

    fun registerService(port: Int, myDeviceId: String, deviceName: String = getDeviceName()) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val cleanName = deviceName.replace("_", "-").replace(":", "-")
            val serviceNameStr = "KivoShare_${myDeviceId}_$cleanName"
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceNameStr
                this.serviceType = this@NsdHelper.serviceType
                this.port = port
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    Log.d("NsdHelper", "NSD Service registered: ${NsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                    Log.e("NsdHelper", "NSD Registration failed: $arg1")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun discoverServices(myDeviceId: String, myLocalIp: String?, myPort: Int, callback: DiscoveryCallback) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d("NsdHelper", "NSD Discovery started")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_kivoshare")) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e("NsdHelper", "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                val rawName = serviceInfo.serviceName

                                // Parse KivoShare_${deviceId}_${deviceName}
                                val parsed = parseServiceName(rawName)
                                val discoveredId = parsed.first
                                val discoveredName = parsed.second

                                // REQ 9: Strictly filter out self device
                                if (discoveredId == myDeviceId) {
                                    Log.d("NsdHelper", "Filtering self device by ID: $discoveredId")
                                    return
                                }
                                if (!myLocalIp.isNull_or_blank() && host == myLocalIp && port == myPort) {
                                    Log.d("NsdHelper", "Filtering self device by IP/Port: $host:$port")
                                    return
                                }

                                callback.onDeviceDiscovered(
                                    DiscoveredDevice(
                                        id = discoveredId,
                                        name = discoveredName,
                                        ipAddress = host,
                                        port = port,
                                        lastSeenTimestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        })
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    val parsed = parseServiceName(service.serviceName)
                    callback.onDeviceLost(parsed.first)
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    try {
                        nsdManager?.stopServiceDiscovery(this)
                    } catch (e: Exception) {}
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseServiceName(rawName: String): Pair<String, String> {
        // Format: KivoShare_${myDeviceId}_${deviceName}
        return if (rawName.startsWith("KivoShare_")) {
            val parts = rawName.substringAfter("KivoShare_").split("_", limit = 2)
            if (parts.size >= 2) {
                Pair(parts[0], parts[1])
            } else {
                Pair(parts[0], parts[0])
            }
        } else if (rawName.startsWith("KivoShare-")) {
            val name = rawName.removePrefix("KivoShare-")
            Pair(name, name)
        } else {
            Pair(rawName, rawName)
        }
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    fun stop() {
        try {
            if (registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
                registrationListener = null
            }
            if (discoveryListener != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                discoveryListener = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        fun getDeviceName(): String {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(manufacturer)) model else "$manufacturer $model"
        }
    }
}
