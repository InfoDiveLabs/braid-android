package com.infodive.braid.relay

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * A lane's public addresses, asked of the same services the desktop asks.
 * The hostnames are part of the protocol: the desktop compares answers from
 * these exact services, and api.ipify.org can only ever answer in IPv4.
 */
object Egress {
    const val V4_SERVICE = "https://api.ipify.org"
    const val V6_SERVICE = "https://api6.ipify.org"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_ANSWER = 64

    /** [open] must reach the service through the lane being measured, name resolution included. */
    fun lookup(open: (URL) -> URLConnection): Pair<String?, String?> =
        fetch(V4_SERVICE, open)?.let(::parseV4) to fetch(V6_SERVICE, open)?.let(::parseV6)

    /**
     * The parsers below never touch InetAddress: on Android, text that fails to
     * parse as a numeric address is handed to DNS, so a hostile answer could
     * otherwise make the phone resolve a name of the answerer's choosing.
     */
    fun parseV4(answer: String): String? = answer.trim().takeIf(::isV4)

    fun parseV6(answer: String): String? = answer.trim().takeIf(::isV6)

    private fun isV4(text: String): Boolean {
        val octets = text.split('.')
        return octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
    }

    private fun isV6(text: String): Boolean {
        if (text.length > 45 || text.count { it == ':' } < 2) return false
        val halves = text.split("::")
        if (halves.size > 2) return false
        val groups = halves.map { half -> if (half.isEmpty()) emptyList() else half.split(':') }
        val all = groups.flatten()
        val tailIsV4 = all.lastOrNull()?.contains('.') == true
        val hex = if (tailIsV4) all.dropLast(1) else all
        if (tailIsV4 && !isV4(all.last())) return false
        if (!hex.all { it.length in 1..4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }) return false
        val count = hex.size + if (tailIsV4) 2 else 0
        return if (halves.size == 2) count < 8 else count == 8
    }

    private fun fetch(url: String, open: (URL) -> URLConnection): String? = try {
        val connection = open(URL(url)) as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { String(it.readNBytes(MAX_ANSWER), Charsets.US_ASCII) }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        null
    }
}
