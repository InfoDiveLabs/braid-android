package com.infodive.braid.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.infodive.braid.relay.RelayServer

/** Announces the relay as `_braid-relay._tcp` so the desktop's Scan finds it with nothing typed in. */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(name: String) {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            port = RelayServer.DEFAULT_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: IllegalArgumentException) {
            }
        }
        registration = null
    }

    companion object {
        const val SERVICE_TYPE = "_braid-relay._tcp"
    }
}
