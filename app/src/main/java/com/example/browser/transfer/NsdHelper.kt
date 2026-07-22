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
        fun onDeviceLost(deviceName: String)
    }

    fun registerService(port: Int, deviceName: String = getDeviceName()) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = "KivoShare-$deviceName"
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

    fun discoverServices(callback: DiscoveryCallback) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_kivoshare")) {
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                val name = serviceInfo.serviceName.removePrefix("KivoShare-")
                                callback.onDeviceDiscovered(
                                    DiscoveredDevice(
                                        name = name,
                                        ipAddress = host,
                                        port = port
                                    )
                                )
                            }
                        })
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    callback.onDeviceLost(service.serviceName.removePrefix("KivoShare-"))
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    nsdManager?.stopServiceDiscovery(this)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) model else "$manufacturer $model"
    }
}
