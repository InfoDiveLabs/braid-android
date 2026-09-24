package com.infodive.braid.relay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bytes carried per lane, both directions together, since the last [reset]. */
class Traffic {
    private val bytes = ConcurrentHashMap<String, AtomicLong>()

    fun bytes(lane: String): Long = bytes[lane]?.get() ?: 0

    fun add(lane: String, count: Int) {
        bytes.getOrPut(lane) { AtomicLong() }.addAndGet(count.toLong())
    }

    fun reset() = bytes.clear()
}
