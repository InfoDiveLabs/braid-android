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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps each lane in [table] bound to the Android Network it is named for,
 * while sharing is running.
 *
 * Mobile data is requested only while sharing runs and its lane is switched
 * on, because holding the request keeps the radio up while Wi-Fi is the
 * default. Wi-Fi is only listened for: asking for it could make the phone
 * join a network.
 *
 * A lane is offered when the person switched it on and it has not used up
 * its limit. The two are kept apart so that reaching a limit never flips the
 * person's own switch.
 */
class NetworkLanes(context: Context, private val table: LaneTable) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val prefs = context.getSharedPreferences("lanes", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "egress").apply { isDaemon = true } }
    private val bound = HashMap<String, Network>()
    private val exhausted = HashSet<String>()
    private val callbacks = HashMap<String, ConnectivityManager.NetworkCallback>()
    private var refresh: ScheduledFuture<*>? = null
    private var running = false

    @Volatile
    var onChange: (() -> Unit)? = null

    init {
        val carrier = telephony?.simOperatorName?.takeIf { it.isNotBlank() }
        table.define(CELL, "cellular", if (carrier != null) "Mobile data ($carrier)" else "Mobile data")
        table.define(WIFI, "wifi", "Wi-Fi")
    }

    val ids get() = listOf(CELL, WIFI)

    fun label(id: String) = if (id == CELL) "Mobile data" else "Wi-Fi"

    @Synchronized
    fun start() {
        if (running) return
        running = true
        exhausted.clear()
        ids.forEach(::apply)
        refresh = worker.scheduleWithFixedDelay(::refreshAll, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        refresh?.cancel(false)
        ids.forEach(::apply)
    }

    fun isWanted(id: String) = prefs.getBoolean(id, false)

    @Synchronized
    fun setWanted(id: String, wanted: Boolean) {
        prefs.edit().putBoolean(id, wanted).apply()
        apply(id)
    }

    @Synchronized
    fun isExhausted(id: String) = id in exhausted

    @Synchronized
    fun setExhausted(id: String, value: Boolean) {
        if (value == id in exhausted) return
        if (value) exhausted.add(id) else exhausted.remove(id)
        apply(id)
    }

    /** Mebibytes, or 0 for no limit. */
    fun limitMib(id: String): Long = prefs.getLong("limit_$id", 0)

    fun setLimitMib(id: String, mib: Long) {
        prefs.edit().putLong("limit_$id", mib).apply()
        onChange?.invoke()
    }

    fun isOffered(id: String) = table.offered().any { it.id == id }

    private fun apply(id: String) {
        val on = running && isWanted(id) && id !in exhausted
        table.setEnabled(id, on)
        val listening = callbacks[id] != null
        if (on && !listening) {
            val callback = callbackFor(id)
            callbacks[id] = callback
            if (id == CELL) {
                cm.requestNetwork(request(NetworkCapabilities.TRANSPORT_CELLULAR), callback)
            } else {
                cm.registerNetworkCallback(request(NetworkCapabilities.TRANSPORT_WIFI), callback)
            }
        } else if (!on && listening) {
            cm.unregisterNetworkCallback(callbacks.remove(id)!!)
            detach(id, null)
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

    /**
     * The same network changing its addresses keeps its current answer until a
     * fresh one arrives. A callback that fires after its lane was switched off
     * is ignored, so it cannot bind a lane nobody is listening for.
     */
    @Synchronized
    private fun attach(id: String, network: Network) {
        if (callbacks[id] == null) return
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
