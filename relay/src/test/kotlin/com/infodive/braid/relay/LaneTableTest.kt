package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.Socket

class LaneTableTest {
    private val cellUpstream = Upstream { _, _ -> Socket() }
    private val wifiUpstream = Upstream { _, _ -> Socket() }
    private val key = "k"
    private val paired = { k: String -> k == key }

    private fun table() = LaneTable().apply {
        define("cell", "cellular", "Mobile data")
        define("wifi", "wifi", "Wi-Fi")
    }

    @Test
    fun aLaneIsOfferedOnlyWhenSwitchedOnAndItsNetworkIsPresent() {
        val t = table()
        assertEquals(emptyList<Lane>(), t.offered())
        t.attach("cell", cellUpstream)
        assertEquals(emptyList<Lane>(), t.offered())
        t.setEnabled("cell", true)
        assertEquals(listOf("cell"), t.offered().map { it.id })
        t.attach("cell", null)
        assertEquals(emptyList<Lane>(), t.offered())
    }

    @Test
    fun routesToTheUpstreamOfTheLaneTheUsernameNames() {
        val t = table()
        t.setEnabled("cell", true)
        t.setEnabled("wifi", true)
        t.attach("cell", cellUpstream)
        t.attach("wifi", wifiUpstream)
        assertSame(cellUpstream, (t.route(Credentials("cell", key), paired) as Route.Via).upstream)
        assertSame(wifiUpstream, (t.route(Credentials("wifi", key), paired) as Route.Via).upstream)
    }

    @Test
    fun credentialsAreCheckedBeforeTheLane() {
        val t = table()
        assertEquals(Route.Refused, t.route(null, paired))
        assertEquals(Route.Refused, t.route(Credentials("cell", "wrong"), paired))
        assertEquals(Route.Refused, t.route(Credentials("nonsense", "wrong"), paired))
    }

    @Test
    fun aLaneThatCannotServeIsUnavailableNeverAnotherLane() {
        val t = table()
        t.setEnabled("wifi", true)
        t.attach("wifi", wifiUpstream)
        t.attach("cell", cellUpstream)
        assertEquals(Route.Unavailable, t.route(Credentials("cell", key), paired))
        t.setEnabled("cell", true)
        t.attach("cell", null)
        assertEquals(Route.Unavailable, t.route(Credentials("cell", key), paired))
        assertEquals(Route.Unavailable, t.route(Credentials("ethernet", key), paired))
    }

    @Test
    fun statusCarriesEgressAndOmitsWhatIsNotKnown() {
        val t = table()
        t.setEnabled("cell", true)
        val generation = t.attach("cell", cellUpstream)
        assertEquals(Lane("cell", "cellular", "Mobile data"), t.offered().single())
        t.setEgress("cell", generation, "152.59.146.101", "2409:40e4:110a:6fcb::1")
        assertEquals(
            Lane("cell", "cellular", "Mobile data", egress = "152.59.146.101", egress6 = "2409:40e4:110a:6fcb::1"),
            t.offered().single(),
        )
    }

    @Test
    fun anEgressResolvedForAPreviousNetworkIsDiscarded() {
        val t = table()
        t.setEnabled("wifi", true)
        val old = t.attach("wifi", wifiUpstream)
        val current = t.attach("wifi", Upstream { _, _ -> Socket() })
        t.setEgress("wifi", old, "198.51.100.4", null)
        assertNull(t.offered().single().egress)
        t.setEgress("wifi", current, "203.0.113.9", null)
        assertEquals("203.0.113.9", t.offered().single().egress)
    }

    @Test
    fun aRefreshUsesTheCurrentGenerationAndKeepsTheOldAnswerUntilThen() {
        val t = table()
        t.setEnabled("cell", true)
        t.setEgress("cell", t.attach("cell", cellUpstream), "152.59.146.141", "2409:40e4:110a:6fcb::1")
        t.setEgress("cell", t.generation("cell"), "152.59.146.101", "2409:40e4:110a:6fcb::1")
        assertEquals("152.59.146.101", t.offered().single().egress)
        t.attach("cell", null)
        assertEquals(-1L, t.generation("cell"))
    }

    @Test
    fun losingTheNetworkForgetsItsEgress() {
        val t = table()
        t.setEnabled("wifi", true)
        t.setEgress("wifi", t.attach("wifi", wifiUpstream), "198.51.100.4", "2001:db8::4")
        t.attach("wifi", null)
        t.attach("wifi", wifiUpstream)
        assertEquals(Lane("wifi", "wifi", "Wi-Fi"), t.offered().single())
    }

    @Test
    fun redefiningKeepsStateButUpdatesTheLabel() {
        val t = table()
        t.setEnabled("cell", true)
        t.attach("cell", cellUpstream)
        t.define("cell", "cellular", "Mobile data (Jio)")
        assertEquals("Mobile data (Jio)", t.offered().single().label)
    }

    @Test
    fun theNoteIsCarriedVerbatim() {
        val t = table()
        t.setEnabled("cell", true)
        t.attach("cell", cellUpstream)
        t.setNote("cell", "1.4 GB left this month")
        assertEquals("1.4 GB left this month", t.offered().single().note)
    }
}
