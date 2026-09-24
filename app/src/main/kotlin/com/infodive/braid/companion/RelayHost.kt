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

    private val history = HashMap<String, LongArray>()
    private val counted = HashMap<String, Long>()

    @Volatile
    var sessionStart = 0L
        private set

    @Volatile
    var peak = 0L
        private set

    @Volatile
    var connections = 0
        private set

    /** Bytes per second for each of the last [SAMPLES] seconds, oldest first. */
    @Synchronized
    fun history(lane: String): LongArray = history[lane]?.copyOf() ?: LongArray(SAMPLES)

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
        history.clear()
        counted.clear()
        peak = 0
        connections = 0
        sessionStart = android.os.SystemClock.elapsedRealtime()
        server = RelayServer(device, control = ControlPlane(device)).also {
            it.onActiveChanged = { count ->
                connections = count
                onActiveChanged?.invoke(count)
            }
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
        var total = 0L
        synchronized(this) {
            for (lane in device.lanes.ids) {
                val used = relay.traffic.bytes(lane)
                val rate = (used - (counted[lane] ?: used)).coerceAtLeast(0)
                counted[lane] = used
                val samples = history.getOrPut(lane) { LongArray(SAMPLES) }
                System.arraycopy(samples, 1, samples, 0, SAMPLES - 1)
                samples[SAMPLES - 1] = rate
                total += rate
            }
        }
        peak = maxOf(peak, total)
        for (lane in device.lanes.ids) {
            val used = relay.traffic.bytes(lane)
            val limit = device.lanes.limitMib(lane) * MIB
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
        bytes >= 1024 * MIB -> String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * MIB))
        else -> String.format(Locale.US, "%.1f MiB", bytes / MIB.toDouble())
    }

    /** Binary, and labelled as such, matching the desktop so one transfer reads the same on both screens. */
    const val MIB = 1024L * 1024
    const val SAMPLES = 60
}
