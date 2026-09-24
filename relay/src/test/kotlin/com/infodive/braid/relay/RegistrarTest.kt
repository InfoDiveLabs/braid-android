package com.infodive.braid.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RegistrarTest {
    private val token = "ab".repeat(32)
    private val key = "cd".repeat(32)
    private val received = LinkedBlockingQueue<Pair<String, String>>()
    private var desktop: ServerSocket? = null

    @After
    fun tearDown() {
        desktop?.close()
    }

    private fun desktopAnswering(status: String): Int {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        desktop = server
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream().buffered()
                val head = readHead(input)!!
                val length = head.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                received.add(head to String(input.readNBytes(length), Charsets.UTF_8))
                s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}".toByteArray())
            }
        }
        return server.localPort
    }

    private fun code(port: Int) = PairingCode("127.0.0.1", port, token, "Studio")

    @Test
    fun postsTheFiveFieldsAndReportsTheAddressItConnectedFrom() {
        val outcome = Registrar.register(code(desktopAnswering("200 OK")), "dev1", "Pixel \"7\"", 8710, key)
        assertEquals(Registrar.Outcome.Paired, outcome)
        val (head, body) = received.poll(5, TimeUnit.SECONDS)!!
        assertEquals(true, head.startsWith("POST /braid/register HTTP/1.1\r\n"))
        assertEquals(true, head.contains("Content-Type: application/json"))
        val json = Json.parseObject(body)!!
        assertEquals(token, json["token"])
        assertEquals("dev1", json["device_id"])
        assertEquals("Pixel \"7\"", json["name"])
        assertEquals("127.0.0.1:8710", json["address"])
        assertEquals(key, json["key"])
    }

    @Test
    fun anIpv6AddressIsBracketedAndTheChosenStableAddressWins() {
        val outcome = Registrar.register(
            code(desktopAnswering("200 OK")), "dev1", "Pixel", 8710, key,
            chooseAddress = { InetAddress.getByName("2409:40e4:2004:769a:64c4:83ff:fecf:9ea5") },
        )
        assertEquals(Registrar.Outcome.Paired, outcome)
        val json = Json.parseObject(received.poll(5, TimeUnit.SECONDS)!!.second)!!
        assertEquals("[2409:40e4:2004:769a:64c4:83ff:fecf:9ea5]:8710", json["address"])
    }

    @Test
    fun aSpentTokenIsExpired() {
        assertEquals(Registrar.Outcome.Expired, Registrar.register(code(desktopAnswering("403 Forbidden")), "d", "P", 8710, key))
    }

    @Test
    fun aClosedListenerIsUnreachable() {
        assertEquals(Registrar.Outcome.Unreachable, Registrar.register(code(desktopAnswering("404 Not Found")), "d", "P", 8710, key))
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(Registrar.Outcome.Unreachable, Registrar.register(code(closed), "d", "P", 8710, key))
    }

    @Test
    fun anythingElseIsAFailureWithItsStatus() {
        assertEquals(Registrar.Outcome.Failed(500), Registrar.register(code(desktopAnswering("500 Oops")), "d", "P", 8710, key))
    }
}
