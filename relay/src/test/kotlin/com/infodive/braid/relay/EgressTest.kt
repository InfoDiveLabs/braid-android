package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EgressTest {
    @Test
    fun acceptsAnIpv4AnswerForTheIpv4Service() {
        assertEquals("152.59.146.101", Egress.parseV4("152.59.146.101\n"))
    }

    @Test
    fun acceptsAnIpv6AnswerForTheIpv6Service() {
        assertEquals("2409:40e4:110a:6fcb:8000::", Egress.parseV6(" 2409:40e4:110a:6fcb:8000:: "))
    }

    @Test
    fun theWrongFamilyIsNotAnAnswer() {
        assertNull(Egress.parseV4("2409:40e4::1"))
        assertNull(Egress.parseV6("152.59.146.101"))
    }

    @Test
    fun aCaptivePortalPageIsNotAnAddress() {
        listOf("<html>Log in</html>", "", "example.com", "999.1.1.1", "1.2.3", "1.2.3.4.5", "::g", "1.2.3.4 5.6.7.8")
            .forEach {
                assertNull(it, Egress.parseV4(it))
                assertNull(it, Egress.parseV6(it))
            }
    }

    @Test
    fun everyIpv6FormAServiceMightWriteIsAccepted() {
        listOf("::1", "::", "2001:db8::", "2001:0db8:0000:0000:0000:0000:0000:0001", "fe80::1:2:3:4", "::ffff:192.0.2.1", "64:ff9b::681a:ccd")
            .forEach { assertEquals(it, Egress.parseV6(it)) }
    }

    @Test
    fun malformedIpv6IsRejected() {
        listOf("1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1::2::3", "12345::", ":::", "1:2:3:4:5:6:7:8::", "::1.2.3", "::1.2.3.4:5", "fe80::1%wlan0")
            .forEach { assertNull(it, Egress.parseV6(it)) }
    }

    @Test
    fun malformedIpv4IsRejected() {
        listOf("256.1.1.1", "1.2.3.04444", "1..2.3", "+1.2.3.4", "1.2.3.4 ").forEach { assertNull(it, Egress.parseV4(it.replace(" ", "x"))) }
    }

    @Test
    fun anAnswerTooLongToBeAnAddressIsRejectedUnread() {
        assertNull(Egress.parseV6("1:".repeat(100)))
    }
}
