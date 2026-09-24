package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    private val token = "0123456789abcdef".repeat(4)

    private fun ok(text: String) = (PairingCode.parse(text) as PairingCode.Parsed.Ok).code
    private fun reason(text: String) = (PairingCode.parse(text) as PairingCode.Parsed.Invalid).reason

    @Test
    fun readsAnIpv4Code() {
        assertEquals(
            PairingCode("192.168.1.24", 53011, token, "Suraj's MacBook"),
            ok("braid://pair?v=1&h=192.168.1.24&p=53011&t=$token&n=Suraj%27s%20MacBook"),
        )
    }

    @Test
    fun readsABracketedIpv6Host() {
        assertEquals(
            "2409:40e4:2004:769a::1",
            ok("braid://pair?v=1&h=%5B2409%3A40e4%3A2004%3A769a%3A%3A1%5D&p=53011&t=$token&n=Studio").host,
        )
    }

    @Test
    fun readsTheCodeARealDesktopProduced() {
        val real = "braid://pair?v=1&h=%5B2409%3A40e4%3A2004%3A769a%3A106f%3Aae98%3Adf51%3A42d3%5D&p=54965" +
            "&t=11d6bef929fdbf6606d7989e47612667f4767799c5e7e2ba32c344b0a761822c&n=Suraj%27s%20MacBook"
        assertEquals(
            PairingCode("2409:40e4:2004:769a:106f:ae98:df51:42d3", 54965, "11d6bef929fdbf6606d7989e47612667f4767799c5e7e2ba32c344b0a761822c", "Suraj's MacBook"),
            ok(real),
        )
    }

    @Test
    fun addressesNothingElseCanReachAreRefused() {
        for (h in listOf("192.0.0.2", "192.0.0.7", "127.0.0.1", "0.0.0.0", "%5B%3A%3A1%5D", "%5B%3A%3A%5D")) {
            assertTrue(h, reason("braid://pair?v=1&h=$h&p=9&t=$token&n=X").contains("address"))
        }
        assertEquals("192.0.0.8", ok("braid://pair?v=1&h=192.0.0.8&p=9&t=$token&n=X").host)
    }

    @Test
    fun aPlusInTheNameIsAPlusNotASpace() {
        assertEquals("C++ box", ok("braid://pair?v=1&h=10.0.0.2&p=1&t=$token&n=C%2B%2B+box".replace("+box", "%20box")).desktop)
        assertEquals("a+b", ok("braid://pair?v=1&h=10.0.0.2&p=1&t=$token&n=a+b").desktop)
    }

    @Test
    fun unknownParametersAreIgnoredAndOrderDoesNotMatter() {
        assertEquals("10.0.0.2", ok("braid://pair?n=X&future=1&t=$token&p=9&h=10.0.0.2&v=1").host)
    }

    @Test
    fun everyWayACodeCanBeWrongSaysWhy() {
        val base = "v=1&h=10.0.0.2&p=9&t=$token&n=X"
        assertTrue(reason("https://example.com/?$base").contains("Braid"))
        assertTrue(reason("braid://other?$base").contains("Braid"))
        assertTrue(reason("braid://pair?${base.replace("v=1", "v=2")}").contains("newer"))
        assertTrue(reason("braid://pair?${base.replace("h=10.0.0.2", "h=example.com")}").contains("address"))
        assertTrue(reason("braid://pair?${base.replace("h=10.0.0.2", "h=%5Bfe80%3A%3A1%5D")}").contains("address"))
        assertTrue(reason("braid://pair?${base.replace("p=9", "p=70000")}").contains("port"))
        assertTrue(reason("braid://pair?${base.replace(token, "abc")}").contains("incomplete"))
        assertTrue(reason("braid://pair?${base.replace(token, token.uppercase())}").contains("incomplete"))
        assertTrue(reason("braid://pair?${base.replace("&n=X", "")}").contains("name"))
        assertTrue(reason("braid://pair?${base.replace("n=X", "n=" + "x".repeat(65))}").contains("name"))
        assertTrue(reason("braid://pair?${base.replace("n=X", "n=%ZZ")}").contains("damaged"))
        assertTrue(reason("not a uri at all").contains("Braid"))
    }
}
