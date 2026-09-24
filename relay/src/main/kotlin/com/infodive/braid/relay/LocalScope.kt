package com.infodive.braid.relay

import java.net.InetAddress

/**
 * Whether an address is on one of the phone's own local links. A pairing code
 * may only point there: a code on a web page or a poster could otherwise hand
 * this phone's mobile data to a machine anywhere on the internet.
 */
object LocalScope {
    data class Link(val interfaceName: String, val address: InetAddress, val prefixLength: Int)

    private val CELLULAR = listOf("rmnet", "ccmni", "v4-", "clat", "pdp", "wwan")

    fun isCellular(interfaceName: String) = CELLULAR.any { interfaceName.startsWith(it) }

    fun contains(links: List<Link>, host: InetAddress): Boolean =
        links.any { !isCellular(it.interfaceName) && sameSubnet(it.address, host, it.prefixLength) }

    fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefixLength !in 0..x.size * 8) return false
        for (bit in 0 until prefixLength) {
            val mask = 0x80 ushr (bit % 8)
            if ((x[bit / 8].toInt() and mask) != (y[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}
