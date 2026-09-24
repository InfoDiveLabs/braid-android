package com.infodive.braid.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue

private const val GOOD_KEY = "f00d"

class ControlPlaneTest {
    private class FakePhone(
        override val name: String = "Pixel",
        private val lanes: List<Lane> = emptyList(),
        private val answer: String? = null,
    ) : Phone {
        override val deviceId = "9f2c1a"
        val asked = LinkedBlockingQueue<String>()
        val askedFrom = LinkedBlockingQueue<String>()
        override fun lanes() = lanes
        override fun isPaired(key: String) = key == GOOD_KEY
        override fun pair(desktop: String, from: String): String? {
            asked.add(desktop)
            askedFrom.add(from)
            return answer
        }
    }

    private var relay: RelayServer? = null

    @After
    fun tearDown() {
        relay?.close()
    }

    private fun start(phone: Phone): RelayServer =
        RelayServer(Router { Route.Refused }, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ControlPlane(phone))
            .also { it.start(); relay = it }

    private fun send(server: RelayServer, line: String, body: String = "", key: String? = null): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val auth = key?.let { "X-Braid-Key: $it\r\n" } ?: ""
        val head = "$line\r\nHost: phone\r\n${auth}Content-Length: ${bytes.size}\r\n\r\n"
        return exchange(server.port, head.toByteArray(Charsets.ISO_8859_1) + bytes)
    }

    private val Response.json get() = Json.parseObject(body.toString(Charsets.UTF_8))!!

    @Test
    fun helloNamesThePhoneWithoutCredentials() {
        val response = send(start(FakePhone()), "GET /braid/hello HTTP/1.1")
        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type")!!.startsWith("application/json"))
        assertEquals("{\"name\":\"Pixel\",\"device_id\":\"9f2c1a\",\"version\":1}", response.body.toString(Charsets.UTF_8))
    }

    @Test
    fun aNameThatWouldBreakTheJsonSurvives() {
        val name = "Suraj = \"phone\" 📱"
        val response = send(start(FakePhone(name = name)), "GET /braid/hello HTTP/1.1")
        assertEquals(name, response.json["name"])
        assertEquals(response.body.size.toString(), response.header("Content-Length"))
    }

    @Test
    fun statusListsLanesAndOmitsWhatIsUnknown() {
        val lanes = listOf(
            Lane("cell", "cellular", "Mobile data", egress = "203.0.113.7", egress6 = "2001:db8::1", note = "1.4 GB left"),
            Lane("wifi", null, "Home Wi-Fi"),
        )
        val body = send(start(FakePhone(lanes = lanes)), "GET /braid/status HTTP/1.1", key = GOOD_KEY).body.toString(Charsets.UTF_8)
        assertEquals(
            "{\"lanes\":[" +
                "{\"id\":\"cell\",\"kind\":\"cellular\",\"label\":\"Mobile data\",\"egress\":\"203.0.113.7\"," +
                "\"egress6\":\"2001:db8::1\",\"note\":\"1.4 GB left\"}," +
                "{\"id\":\"wifi\",\"label\":\"Home Wi-Fi\"}]}",
            body,
        )
    }

    @Test
    fun statusWithNothingOfferedIsAnEmptyList() {
        assertEquals("{\"lanes\":[]}", send(start(FakePhone()), "GET /braid/status HTTP/1.1", key = GOOD_KEY).body.toString(Charsets.UTF_8))
    }

    @Test
    fun pairShowsTheDesktopNameAndReturnsTheKeyOnAccept() {
        val phone = FakePhone(answer = "k3y")
        val response = send(start(phone), "POST /braid/pair HTTP/1.1", "{\"desktop\":\"Suraj's \\\"MacBook\\\"\"}")
        assertEquals(200, response.status)
        assertEquals("k3y", response.json["key"])
        assertEquals("Suraj's \"MacBook\"", phone.asked.poll())
        assertEquals("127.0.0.1", phone.askedFrom.poll())
    }

    @Test
    fun statusWithoutAValidKeyIs401() {
        val server = start(FakePhone(lanes = listOf(Lane("cell", "cellular", "Mobile data", egress = "203.0.113.7"))))
        val bare = send(server, "GET /braid/status HTTP/1.1")
        assertEquals(401, bare.status)
        assertEquals(0, bare.body.size)
        assertEquals(401, send(server, "GET /braid/status HTTP/1.1", key = "wrong").status)
        assertEquals(401, send(server, "GET /braid/status HTTP/1.1", key = "").status)
    }

    @Test
    fun helloNeedsNoKeyEvenWhenAWrongOneIsSent() {
        assertEquals(200, send(start(FakePhone()), "GET /braid/hello HTTP/1.1", key = "wrong").status)
    }

    @Test
    fun pairRefusedIsNotASuccess() {
        assertEquals(403, send(start(FakePhone(answer = null)), "POST /braid/pair HTTP/1.1", "{\"desktop\":\"Studio\"}").status)
    }

    @Test
    fun pairWithoutADesktopNameIsABadRequestAndAsksNobody() {
        val phone = FakePhone(answer = "k3y")
        val server = start(phone)
        assertEquals(400, send(server, "POST /braid/pair HTTP/1.1", "not json").status)
        assertEquals(400, send(server, "POST /braid/pair HTTP/1.1", "{\"name\":\"Studio\"}").status)
        assertEquals(400, send(server, "POST /braid/pair HTTP/1.1", "{\"desktop\":7}").status)
        assertEquals(0, phone.asked.size)
    }

    @Test
    fun wrongMethodIs405() {
        val server = start(FakePhone(answer = "k3y"))
        assertEquals(405, send(server, "GET /braid/pair HTTP/1.1").status)
        assertEquals(405, send(server, "POST /braid/hello HTTP/1.1").status)
    }

    @Test
    fun unknownControlPathIs404() {
        assertEquals(404, send(start(FakePhone()), "GET /braid/nope HTTP/1.1").status)
        assertEquals(404, send(start(FakePhone()), "GET /braid/hello/extra HTTP/1.1").status)
    }

    @Test
    fun queryStringDoesNotChangeThePath() {
        assertEquals(200, send(start(FakePhone()), "GET /braid/hello?x=1 HTTP/1.1").status)
    }
}
