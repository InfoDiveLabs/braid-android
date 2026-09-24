package com.infodive.braid.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class TrafficTest {
    private val key = "k"
    private val payload = ByteArray(300_000) { it.toByte() }
    private val origin = TestOrigin(payload)
    private val activity = CopyOnWriteArrayList<Int>()
    private val relay = RelayServer(
        Router { c -> if (c?.key == key) Route.Via(Upstream.DIRECT) else Route.Refused },
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
    ).also {
        it.onActiveChanged = { count -> activity.add(count) }
        it.start()
    }

    @After
    fun tearDown() {
        relay.close()
        origin.close()
    }

    private fun auth(lane: String) = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("$lane:$key".toByteArray())

    private fun fetch(lane: String): Response = exchange(
        relay.port,
        ("GET http://127.0.0.1:${origin.port}/f HTTP/1.1\r\nHost: x\r\n${auth(lane)}\r\n\r\n").toByteArray(),
    )

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun bytesAreCountedAgainstTheLaneThatCarriedThem() {
        assertEquals(200, fetch("cell").status)
        waitFor { relay.traffic.bytes("cell") >= payload.size }
        assertTrue(relay.traffic.bytes("cell") < payload.size + 2_000)
        assertEquals(0L, relay.traffic.bytes("wifi"))
    }

    @Test
    fun refusedRequestsCostNothing() {
        exchange(relay.port, "GET http://127.0.0.1:${origin.port}/f HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertEquals(0L, relay.traffic.bytes(""))
    }

    @Test
    fun resetStartsANewSession() {
        fetch("cell")
        waitFor { relay.traffic.bytes("cell") > 0 }
        relay.traffic.reset()
        assertEquals(0L, relay.traffic.bytes("cell"))
    }

    @Test
    fun droppingALaneCutsItsOpenTunnelsAndNoOthers() {
        EchoServer().use { echo ->
            val cell = openTunnel(echo.port, "cell")
            val wifi = openTunnel(echo.port, "wifi")
            relay.drop("cell")
            assertTrue(isClosed(cell))
            wifi.getOutputStream().write(1)
            assertEquals(1, wifi.getInputStream().read())
            cell.close()
            wifi.close()
        }
    }

    @Test
    fun droppingByCredentialsCutsOnlyTheMatchingConnections() {
        EchoServer().use { echo ->
            val cell = openTunnel(echo.port, "cell")
            val wifi = openTunnel(echo.port, "wifi")
            relay.dropWhere { it.lane == "wifi" && it.key == key }
            assertTrue(isClosed(wifi))
            cell.getOutputStream().write(1)
            assertEquals(1, cell.getInputStream().read())
            cell.close()
        }
    }

    @Test
    fun activeConnectionsAreReportedUpAndBackDown() {
        EchoServer().use { echo ->
            val tunnel = openTunnel(echo.port, "cell")
            waitFor { activity.lastOrNull() == 1 }
            tunnel.close()
            waitFor { activity.lastOrNull() == 0 }
        }
    }

    private fun openTunnel(port: Int, lane: String): Socket {
        val s = Socket(InetAddress.getLoopbackAddress(), relay.port)
        s.soTimeout = 5_000
        s.getOutputStream().write("CONNECT 127.0.0.1:$port HTTP/1.1\r\n${auth(lane)}\r\n\r\n".toByteArray())
        val head = readHead(BufferedInputStream(s.getInputStream(), 1))
        check(head!!.startsWith("HTTP/1.1 200")) { head }
        return s
    }

    private fun isClosed(s: Socket): Boolean = try {
        s.getOutputStream().write(1)
        s.getInputStream().read() == -1
    } catch (e: IOException) {
        true
    }
}
