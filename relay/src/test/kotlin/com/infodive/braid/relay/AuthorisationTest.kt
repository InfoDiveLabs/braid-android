package com.infodive.braid.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue

class AuthorisationTest {
    private val key = "a".repeat(64)
    private val origin = TestOrigin(ByteArray(1000) { it.toByte() })
    private val lanesAsked = LinkedBlockingQueue<String>()
    private val relay = RelayServer(
        Router { credentials ->
            when {
                credentials == null || credentials.key != key -> Route.Refused
                credentials.lane == "gone" -> Route.Unavailable
                else -> {
                    lanesAsked.add(credentials.lane)
                    Route.Via(Upstream.DIRECT)
                }
            }
        },
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
    ).also { it.start() }

    @After
    fun tearDown() {
        relay.close()
        origin.close()
    }

    private fun basic(user: String, password: String) =
        "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun get(vararg headers: String): Response {
        val head = buildString {
            append("GET http://127.0.0.1:${origin.port}/x HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        return exchange(relay.port, head.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun noCredentialsIs407AndNothingIsForwarded() {
        val response = get()
        assertEquals(407, response.status)
        assertTrue(response.header("Proxy-Authenticate")!!.startsWith("Basic"))
        assertEquals(0, origin.heads.size)
    }

    @Test
    fun aWrongKeyIs407() {
        assertEquals(407, get(basic("cell", "b".repeat(64))).status)
        assertEquals(0, origin.heads.size)
    }

    @Test
    fun malformedCredentialsAre407() {
        assertEquals(407, get("Proxy-Authorization: Basic !!!notbase64").status)
        assertEquals(407, get("Proxy-Authorization: Bearer $key").status)
        assertEquals(407, get("Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(key.toByteArray())).status)
        assertEquals(0, origin.heads.size)
    }

    @Test
    fun theRightKeyIsForwardedOverTheLaneTheUsernameNames() {
        val response = get(basic("cell", key))
        assertEquals(200, response.status)
        assertEquals("cell", lanesAsked.poll())
        assertFalse(origin.nextHead().contains("Proxy-Authorization", ignoreCase = true))
    }

    @Test
    fun theSchemeNameIsCaseInsensitive() {
        val header = "Proxy-Authorization: basic " + Base64.getEncoder().encodeToString("cell:$key".toByteArray())
        assertEquals(200, get(header).status)
    }

    @Test
    fun aLaneThatCannotServeIs503NotAChallenge() {
        assertEquals(503, get(basic("gone", key)).status)
        assertEquals(0, origin.heads.size)
    }

    @Test
    fun anUnpairedConnectIsRefusedBeforeAnyTunnelOpens() {
        EchoServer().use { echo ->
            assertEquals(407, connect(echo.port).status)
            assertEquals(407, connect(echo.port, basic("cell", "wrong")).status)
            Thread.sleep(100)
            assertEquals(0, echo.accepted.get())
        }
    }

    @Test
    fun aPairedConnectTunnels() {
        EchoServer().use { echo ->
            Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().write(
                    "CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n${basic("cell", key)}\r\n\r\n".toByteArray(),
                )
                val input = BufferedInputStream(s.getInputStream())
                assertTrue(readHead(input)!!.startsWith("HTTP/1.1 200"))
                s.getOutputStream().write("ping".toByteArray())
                assertEquals("ping", String(input.readNBytes(4)))
            }
        }
    }

    private fun connect(port: Int, vararg headers: String): Response {
        val head = "CONNECT 127.0.0.1:$port HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n"
        return exchange(relay.port, head.toByteArray())
    }
}
