package com.infodive.braid.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import com.infodive.braid.relay.Egress
import com.infodive.braid.relay.LaneTable
import com.infodive.braid.relay.Upstream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps each lane in [table] bound to the Android Network it is named for.
 *
 * Mobile data is requested only while its lane is switched on, because
 * holding the request keeps the radio up while Wi-Fi is the default. Wi-Fi is
 * only listened for: asking for it could make the phone join a network.
 */
class NetworkLanes(context: Context, private val table: LaneTable) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val prefs = context.getSharedPreferences("lanes", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "egress").apply { isDaemon = true } }
    private val bound = HashMap<String, Network>()
    private var cellCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var onChange: (() -> Unit)? = null

    fun start() {
        val carrier = telephony?.simOperatorName?.takeIf { it.isNotBlank() }
        table.define(CELL, "cellular", if (carrier != null) "Mobile data ($carrier)" else "Mobile data")
        table.define(WIFI, "wifi", "Wi-Fi")
        cm.registerNetworkCallback(request(NetworkCapabilities.TRANSPORT_WIFI), callbackFor(WIFI))
        for (id in listOf(CELL, WIFI)) setEnabled(id, prefs.getBoolean(id, false))
        worker.scheduleWithFixedDelay(::refreshAll, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES)
    }

    fun isEnabled(id: String) = table.isEnabled(id)

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(id, enabled).apply()
        table.setEnabled(id, enabled)
        if (id == CELL) {
            if (enabled && cellCallback == null) {
                cellCallback = callbackFor(CELL).also { cm.requestNetwork(request(NetworkCapabilities.TRANSPORT_CELLULAR), it) }
            } else if (!enabled) {
                cellCallback?.let(cm::unregisterNetworkCallback)
                cellCallback = null
                detach(CELL, null)
            }
        }
        onChange?.invoke()
    }

    private fun request(transport: Int) = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private fun callbackFor(id: String) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = attach(id, network)
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = attach(id, network)
        override fun onLost(network: Network) = detach(id, network)
    }

    /** The same network changing its addresses keeps its current answer until a fresh one arrives. */
    @Synchronized
    private fun attach(id: String, network: Network) {
        if (bound[id] == network) {
            worker.execute { resolve(id, table.generation(id), network) }
            return
        }
        bound[id] = network
        val generation = table.attach(id, NetworkUpstream(network))
        onChange?.invoke()
        worker.execute { resolve(id, generation, network) }
    }

    /** Detaches only if [network] is still the one bound, so a late onLost cannot unbind its replacement. */
    @Synchronized
    private fun detach(id: String, network: Network?) {
        if (network != null && bound[id] != network) return
        bound.remove(id)
        table.attach(id, null)
        onChange?.invoke()
    }

    private fun resolve(id: String, generation: Long, network: Network) {
        val (v4, v6) = Egress.lookup(network::openConnection)
        table.setEgress(id, generation, v4, v6)
        onChange?.invoke()
    }

    private fun refreshAll() {
        val snapshot = synchronized(this) { HashMap(bound) }
        for ((id, network) in snapshot) resolve(id, table.generation(id), network)
    }

    /** Resolves and connects through [network] only; if it is gone, connecting fails rather than falling back. */
    private class NetworkUpstream(private val network: Network) : Upstream {
        override fun connect(host: String, port: Int): Socket =
            Upstream.connectFirst(network.getAllByName(host), port) { Socket().also(network::bindSocket) }
    }

    companion object {
        const val CELL = "cell"
        const val WIFI = "wifi"
        private const val REFRESH_MINUTES = 10L
    }
}
