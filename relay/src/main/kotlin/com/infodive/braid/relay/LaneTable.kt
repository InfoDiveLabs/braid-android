package com.infodive.braid.relay

/**
 * The lanes this phone can offer, and which network each is bound to right now.
 *
 * A lane serves only through its own upstream. When that network is gone or
 * the lane is switched off, requests for it are [Route.Unavailable]; nothing
 * here ever substitutes another lane, because a cellular lane quietly served
 * over Wi-Fi is a lie the desktop cannot detect.
 */
class LaneTable {
    private class Entry(var kind: String?, var label: String) {
        var enabled = false
        var upstream: Upstream? = null
        var generation = 0L
        var egress: String? = null
        var egress6: String? = null
        var note: String? = null
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun define(id: String, kind: String?, label: String) {
        val entry = entries.getOrPut(id) { Entry(kind, label) }
        entry.kind = kind
        entry.label = label
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        entries[id]?.enabled = enabled
    }

    @Synchronized
    fun isEnabled(id: String): Boolean = entries[id]?.enabled == true

    /**
     * Binds [id] to a network, or to none. Returns a generation that an egress
     * lookup started now must present, so one that finishes after the network
     * changed cannot describe the new network with the old one's address.
     */
    @Synchronized
    fun attach(id: String, upstream: Upstream?): Long {
        val entry = entries[id] ?: return -1
        entry.upstream = upstream
        entry.egress = null
        entry.egress6 = null
        return ++entry.generation
    }

    /** The current generation of [id], for refreshing its egress without detaching it. */
    @Synchronized
    fun generation(id: String): Long = entries[id]?.takeIf { it.upstream != null }?.generation ?: -1

    @Synchronized
    fun setEgress(id: String, generation: Long, egress: String?, egress6: String?) {
        val entry = entries[id] ?: return
        if (entry.generation != generation) return
        entry.egress = egress
        entry.egress6 = egress6
    }

    @Synchronized
    fun setNote(id: String, note: String?) {
        entries[id]?.note = note
    }

    @Synchronized
    fun offered(): List<Lane> = entries
        .filter { (_, e) -> e.enabled && e.upstream != null }
        .map { (id, e) -> Lane(id, e.kind, e.label, e.egress, e.egress6, e.note) }

    @Synchronized
    fun route(credentials: Credentials?, isPaired: (String) -> Boolean): Route {
        if (credentials == null || !isPaired(credentials.key)) return Route.Refused
        val entry = entries[credentials.lane]?.takeIf { it.enabled } ?: return Route.Unavailable
        return entry.upstream?.let { Route.Via(it) } ?: Route.Unavailable
    }
}
