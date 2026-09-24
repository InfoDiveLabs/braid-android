package com.infodive.braid.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalScopeTest {
    private fun a(text: String) = InetAddress.getByName(text)

    private val hotspot = listOf(
        LocalScope.Link("wlan0", a("2409:40e4:2004:769a:64c4:83ff:fecf:9ea5"), 64),
        LocalScope.Link("wlan0", a("192.168.1.30"), 24),
        LocalScope.Link("rmnet1", a("2409:40e4:110a:6fcb:8000::"), 64),
    )

    @Test
    fun aComputerOnTheSameWifiIsLocal() {
        assertTrue(LocalScope.contains(hotspot, a("2409:40e4:2004:769a:f81a:202c:25f6:c0f0")))
        assertTrue(LocalScope.contains(hotspot, a("192.168.1.24")))
    }

    @Test
    fun anAddressOutsideThosePrefixesIsNot() {
        assertFalse(LocalScope.contains(hotspot, a("2409:40e4:2004:769b::1")))
        assertFalse(LocalScope.contains(hotspot, a("192.168.2.24")))
        assertFalse(LocalScope.contains(hotspot, a("203.0.113.7")))
    }

    @Test
    fun theMobileDataPrefixNeverCountsAsLocal() {
        assertFalse(LocalScope.contains(hotspot, a("2409:40e4:110a:6fcb::99")))
    }

    @Test
    fun familiesNeverMatchEachOther() {
        assertFalse(LocalScope.contains(listOf(LocalScope.Link("wlan0", a("::"), 0)), a("10.0.0.1")))
    }

    @Test
    fun prefixesThatAreNotWholeBytesAreRespected() {
        val tether = listOf(LocalScope.Link("rndis0", a("192.168.42.129"), 26))
        assertTrue(LocalScope.contains(tether, a("192.168.42.150")))
        assertFalse(LocalScope.contains(tether, a("192.168.42.200")))
    }
}
