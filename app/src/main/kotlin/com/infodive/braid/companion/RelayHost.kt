package com.infodive.braid.companion

import android.content.Context
import com.infodive.braid.relay.ControlPlane
import com.infodive.braid.relay.RelayServer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Everything that runs while sharing is on: the relay, its lanes and its
 * mDNS record, started and stopped together so that turning sharing off
 * releases the mobile data request and disappears from the network at once.
 */
object RelayHost {
    private var phone: AndroidPhone? = null
    private var advertiser: Advertiser? = null
    private var server: RelayServer? = null
    private var ticker: ScheduledFuture<*>? = null
    private val clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "relay-tick").apply { isDaemon = true } }
    private val watchers = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var onActiveChanged: ((Int) -> Unit)? = null

    @Synchronized
    fun phone(context: Context): AndroidPhone =
        phone ?: AndroidPhone(context.applicationContext).also { created ->
            phone = created
            created.lanes.onChange = ::changed
        }

    val isRunning: Boolean get() = server != null

    fun used(lane: String): Long = server?.traffic?.bytes(lane) ?: 0

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val device = phone(context)
        server = RelayServer(device, control = ControlPlane(device)).also {
            it.onActiveChanged = { count -> onActiveChanged?.invoke(count) }
            it.start()
        }
        device.lanes.start()
        advertiser = (advertiser ?: Advertiser(context.applicationContext)).also { it.start(device.name) }
        ticker = clock.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
        changed()
    }

    @Synchronized
    fun stop() {
        val running = server ?: return
        ticker?.cancel(false)
        advertiser?.stop()
        phone?.lanes?.stop()
        running.close()
        server = null
        changed()
    }

    /** Cuts the lane's open connections too, since a switch turned off should stop the data now, not when a download ends. */
    fun setWanted(context: Context, lane: String, wanted: Boolean) {
        phone(context).lanes.setWanted(lane, wanted)
        if (!wanted) server?.drop(lane)
    }

    fun forget(context: Context, pairingId: String) {
        val device = phone(context)
        device.pairings.forget(pairingId)
        server?.dropWhere { !device.pairings.isPaired(it.key) }
        changed()
    }

    fun watch(watcher: () -> Unit) = watchers.add(watcher)

    fun unwatch(watcher: () -> Unit) = watchers.remove(watcher)

    private fun changed() = watchers.forEach { it() }

    /** Enforces each lane's limit and publishes its usage as the note the desktop shows verbatim. */
    private fun tick() {
        val device = phone ?: return
        val relay = server ?: return
        for (lane in device.lanes.ids) {
            val used = relay.traffic.bytes(lane)
            val limit = device.lanes.limitMb(lane) * MB
            val over = limit > 0 && used >= limit
            if (over && !device.lanes.isExhausted(lane)) relay.drop(lane)
            device.lanes.setExhausted(lane, over)
            device.table.setNote(lane, note(used, limit))
        }
        changed()
    }

    fun note(used: Long, limit: Long): String =
        if (limit > 0) "${size(used)} used this session, ${size((limit - used).coerceAtLeast(0))} left of ${size(limit)}"
        else "${size(used)} used this session"

    fun size(bytes: Long): String = when {
        bytes >= 1000 * MB -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * MB))
        else -> String.format(Locale.US, "%.1f MB", bytes / MB.toDouble())
    }

    const val MB = 1024L * 1024
}
