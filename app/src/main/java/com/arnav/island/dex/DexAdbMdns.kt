package com.arnav.island.dex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/** Discovers the local wireless debugging pairing/connect port via mDNS (Android 11+). */
@RequiresApi(Build.VERSION_CODES.R)
class DexAdbMdns(
    context: Context,
    private val serviceType: String,
    private val onPortChanged: (Int) -> Unit,
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                running = false
                Log.w(TAG, "discoverServices failed", it)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        unregister()
    }

    private fun unregister() {
        if (!registered) return
        registered = false
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discovery listener already unregistered", e)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
        if (!running) unregister()
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    @Suppress("DEPRECATION")
    private fun onServiceFound(info: NsdServiceInfo) {
        runCatching { nsdManager.resolveService(info, ResolveListener(this)) }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPortChanged(-1)
    }

    @Suppress("DEPRECATION")
    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val hostAddress = resolvedService.host?.hostAddress ?: return
        val localMatch = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.any { iface ->
                    iface.inetAddresses.asSequence().any { hostAddress == it.hostAddress }
                } ?: false
        }.getOrDefault(false)

        if (running && localMatch && isPortInUse(resolvedService.port)) {
            serviceName = resolvedService.serviceName
            onPortChanged(resolvedService.port)
        }
    }

    private fun isPortInUse(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    private class DiscoveryListener(private val mdns: DexAdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = mdns.onDiscoveryStart()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStopped(serviceType: String) = mdns.onDiscoveryStop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = mdns.onServiceFound(serviceInfo)
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = mdns.onServiceLost(serviceInfo)
    }

    private class ResolveListener(private val mdns: DexAdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) = mdns.onServiceResolved(nsdServiceInfo)
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "DexAdbMdns"
    }
}
