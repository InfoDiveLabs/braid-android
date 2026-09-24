package com.infodive.braid.relay

/** Where a request is going, decided entirely by the form of its request target. */
sealed interface Target {
    /** Absolute-form `http://host[:port]/path`: forward as origin-form to host:port. */
    data class Forward(val host: String, val port: Int, val path: String) : Target

    /** `CONNECT host:port`: open a raw tunnel. */
    data class Tunnel(val host: String, val port: Int) : Target

    /** Origin-form `/path`: addressed to this phone, never proxied. */
    data class Local(val path: String) : Target

    /** Absolute-form with a scheme other than http. Terminating TLS here would mean rewriting bytes. */
    data object Unsupported : Target
}

/**
 * A parsed request head. Header lines are kept exactly as received, decoded
 * as ISO-8859-1 so re-encoding them reproduces the original bytes.
 */
class RequestHead(
    val method: String,
    val target: Target,
    val version: String,
    val headerLines: List<String>,
) {
    fun header(name: String): String? = headerLines
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')?.trim()

    companion object {
        /** Parses a head ending in a blank line, or returns null if it is not a request we can act on. */
        fun parse(head: String): RequestHead? {
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3) return null
            val (method, rawTarget, version) = parts
            if (method.isEmpty() || !version.startsWith("HTTP/1.")) return null
            val target = parseTarget(method, rawTarget) ?: return null
            return RequestHead(method, target, version, lines.drop(1).takeWhile { it.isNotEmpty() })
        }

        private fun parseTarget(method: String, raw: String): Target? {
            if (method == "CONNECT") {
                val (host, port) = parseAuthority(raw, defaultPort = null) ?: return null
                return Target.Tunnel(host, port)
            }
            if (raw.startsWith("/")) return Target.Local(raw)
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) return null
            if (!raw.substring(0, schemeEnd).equals("http", ignoreCase = true)) return Target.Unsupported
            val rest = raw.substring(schemeEnd + 3)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val (host, port) = parseAuthority(rest.substring(0, authorityEnd), defaultPort = 80) ?: return null
            val path = rest.substring(authorityEnd).let {
                when {
                    it.isEmpty() -> "/"
                    it.startsWith("?") -> "/$it"
                    else -> it
                }
            }
            return Target.Forward(host, port, path)
        }

        /** `host`, `host:port`, `[v6]` or `[v6]:port`. No userinfo. A null default makes the port required. */
        private fun parseAuthority(authority: String, defaultPort: Int?): Pair<String, Int>? {
            if (authority.isEmpty() || '@' in authority) return null
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> return null
                }
            } else {
                if (authority.count { it == ':' } > 1) return null
                host = authority.substringBefore(':')
                portText = if (':' in authority) authority.substringAfter(':') else null
            }
            if (host.isEmpty()) return null
            val port = when (portText) {
                null -> defaultPort ?: return null
                else -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            return host to port
        }
    }
}
