package com.infodive.braid.relay

import java.util.Base64

/** `Proxy-Authorization: Basic base64("<lane>:<key>")`: the username names the lane, the password is the key. */
data class Credentials(val lane: String, val key: String) {
    companion object {
        fun parse(header: String?): Credentials? {
            val value = header?.trim() ?: return null
            val space = value.indexOf(' ')
            if (space < 0 || !value.substring(0, space).equals("Basic", ignoreCase = true)) return null
            val decoded = try {
                String(Base64.getDecoder().decode(value.substring(space + 1).trim()), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val colon = decoded.indexOf(':')
            if (colon < 0) return null
            return Credentials(decoded.substring(0, colon), decoded.substring(colon + 1))
        }
    }
}

sealed interface Route {
    data class Via(val upstream: Upstream) : Route

    /** Answered with 407, so the client knows its credentials are the problem. */
    data object Refused : Route

    /**
     * Paired, but the named lane is off or its network is gone. Answered with
     * 503 rather than 407 so a desktop parks the lane instead of concluding it
     * has been forgotten.
     */
    data object Unavailable : Route
}

/** Decides, for every proxied request, whether to serve it and over which network. */
fun interface Router {
    fun route(credentials: Credentials?): Route
}
