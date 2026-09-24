package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestHeadTest {
    private fun parse(line: String) = RequestHead.parse("$line\r\nHost: h\r\n\r\n")

    @Test
    fun absoluteUriWithDefaultPort() {
        val t = parse("GET http://example.com/a/b?c=d HTTP/1.1")!!.target as Target.Forward
        assertEquals(Target.Forward("example.com", 80, "/a/b?c=d"), t)
    }

    @Test
    fun absoluteUriWithPortAndNoPath() {
        assertEquals(Target.Forward("example.com", 8080, "/"), parse("GET http://example.com:8080 HTTP/1.1")!!.target)
    }

    @Test
    fun absoluteUriWithQueryButNoPath() {
        assertEquals(Target.Forward("example.com", 80, "/?q"), parse("GET http://example.com?q HTTP/1.1")!!.target)
    }

    @Test
    fun ipv6LiteralKeepsItsBracketsOutOfTheHost() {
        assertEquals(Target.Forward("::1", 81, "/x"), parse("GET http://[::1]:81/x HTTP/1.1")!!.target)
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals(Target.Forward("h", 80, "/"), parse("GET HTTP://h/ HTTP/1.1")!!.target)
    }

    @Test
    fun connectAuthority() {
        assertEquals(Target.Tunnel("example.com", 443), parse("CONNECT example.com:443 HTTP/1.1")!!.target)
        assertEquals(Target.Tunnel("::1", 443), parse("CONNECT [::1]:443 HTTP/1.1")!!.target)
    }

    @Test
    fun connectWithoutPortIsMalformed() {
        assertNull(parse("CONNECT example.com HTTP/1.1"))
    }

    @Test
    fun originFormIsLocal() {
        assertEquals(Target.Local("/braid/hello"), parse("GET /braid/hello HTTP/1.1")!!.target)
    }

    @Test
    fun otherSchemesAreUnsupported() {
        assertEquals(Target.Unsupported, parse("GET https://h/ HTTP/1.1")!!.target)
        assertEquals(Target.Unsupported, parse("GET ftp://h/ HTTP/1.1")!!.target)
    }

    @Test
    fun userinfoInAuthorityIsMalformed() {
        assertNull(parse("GET http://u:p@h/ HTTP/1.1"))
    }

    @Test
    fun malformedRequestLines() {
        assertNull(parse("GET"))
        assertNull(parse("GET http://h/"))
        assertNull(parse("GET http://h/ FTP/1.0"))
        assertNull(parse("GET http://h:notaport/ HTTP/1.1"))
        assertNull(parse("GET http:///x HTTP/1.1"))
    }

    @Test
    fun headersKeepTheirExactBytes() {
        val head = RequestHead.parse("GET /x HTTP/1.1\r\nX-A:  two  spaces \r\nB: é\r\n\r\n")!!
        assertEquals(listOf("X-A:  two  spaces ", "B: é"), head.headerLines)
        assertEquals("two  spaces", head.header("x-a"))
    }
}
