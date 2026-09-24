package com.infodive.braid.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

class RelayServerTest {
    private val payload = Random(7).nextBytes(8 * 1024 * 1024)
    private lateinit var origin: TestOrigin
    private lateinit var relay: RelayServer

    @Before
    fun setUp() {
        origin = TestOrigin(payload)
        relay = RelayServer(Upstream.DIRECT, InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        relay.start()
    }

    @After
    fun tearDown() {
        relay.close()
        origin.close()
    }

    private fun get(target: String, vararg headers: String): Response {
        val head = buildString {
            append("GET $target HTTP/1.1\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        return exchange(relay.port, head.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun forwardsAbsoluteUriAsOriginFormAndBodyIsByteIdentical() {
        val response = get(
            "http://127.0.0.1:${origin.port}/files/big.bin?x=1",
            "Host: 127.0.0.1:${origin.port}",
        )
        assertEquals(200, response.status)
        assertArrayEquals(payload, response.body)
        assertTrue(origin.nextHead().startsWith("GET /files/big.bin?x=1 HTTP/1.1\r\n"))
    }

    @Test
    fun rangedRequestReturnsExactlyTheRequestedBytes() {
        val response = get(
            "http://127.0.0.1:${origin.port}/big.bin",
            "Host: 127.0.0.1:${origin.port}",
            "Range: bytes=100-199",
        )
        assertEquals(206, response.status)
        assertEquals(100, response.body.size)
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun stripsProxyAndHopByHopHeadersAndChangesNothingElse() {
        get(
            "http://127.0.0.1:${origin.port}/a",
            "Host: 127.0.0.1:${origin.port}",
            "Proxy-Authorization: Basic Y2VsbDpzZWNyZXQ=",
            "Proxy-Connection: keep-alive",
            "Connection: keep-alive, X-Hop",
            "Keep-Alive: timeout=5",
            "X-Hop: gone",
            "X-Custom:  spaced  value ",
            "Range: bytes=0-9",
        )
        val lines = origin.nextHead().split("\r\n").filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "GET /a HTTP/1.1",
                "Host: 127.0.0.1:${origin.port}",
                "X-Custom:  spaced  value ",
                "Range: bytes=0-9",
                "Connection: close",
            ),
            lines,
        )
    }

    @Test
    fun neverAddsAcceptEncoding() {
        get("http://127.0.0.1:${origin.port}/a", "Host: 127.0.0.1:${origin.port}")
        assertFalse(origin.nextHead().contains("accept-encoding", ignoreCase = true))
    }

    @Test
    fun addsHostWhenTheClientOmittedIt() {
        get("http://127.0.0.1:${origin.port}/a")
        assertTrue(origin.nextHead().contains("\r\nHost: 127.0.0.1:${origin.port}\r\n"))
    }

    @Test
    fun forwardsARequestBody() {
        val body = "hello=world".toByteArray()
        val request = "POST http://127.0.0.1:${origin.port}/form HTTP/1.1\r\n" +
            "Host: 127.0.0.1:${origin.port}\r\nContent-Length: ${body.size}\r\n\r\n"
        val response = exchange(relay.port, request.toByteArray() + body)
        assertEquals(200, response.status)
        assertArrayEquals(body, response.body)
    }

    @Test
    fun connectTunnelsRawBytesBothWays() {
        EchoServer().use { echo ->
            Socket(InetAddress.getLoopbackAddress(), relay.port).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                out.write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\nHost: 127.0.0.1:${echo.port}\r\n\r\n".toByteArray())
                out.flush()
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input)!!
                assertTrue(head, head.startsWith("HTTP/1.1 200"))

                val bytes = Random(3).nextBytes(256 * 1024)
                val writer = Thread { out.write(bytes); out.flush() }.apply { start() }
                val echoed = input.readNBytes(bytes.size)
                writer.join()
                assertArrayEquals(bytes, echoed)
            }
        }
    }

    @Test
    fun unreachableUpstreamIsA502() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val response = get("http://127.0.0.1:$closedPort/", "Host: 127.0.0.1:$closedPort")
        assertEquals(502, response.status)
    }

    @Test
    fun originFormRequestIsNotProxied() {
        assertEquals(404, get("/braid/nothing", "Host: phone").status)
        assertEquals(0, origin.heads.size)
    }

    @Test
    fun garbageIsA400() {
        assertEquals(400, exchange(relay.port, "NONSENSE\r\n\r\n".toByteArray()).status)
    }

    @Test
    fun httpsAbsoluteUriIsRefusedRatherThanTerminated() {
        assertEquals(501, get("https://127.0.0.1:${origin.port}/", "Host: x").status)
        assertEquals(0, origin.heads.size)
    }
}
