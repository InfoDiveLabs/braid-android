package com.infodive.braid.relay

import java.io.ByteArrayOutputStream

/**
 * What a desktop's pairing QR carries:
 * `braid://pair?v=1&h=<host>&p=<port>&t=<token>&n=<name>`.
 *
 * Unknown parameters are ignored so the desktop can add fields without a
 * version bump. Everything that is present is checked, and each refusal says
 * what was wrong in words a person can act on.
 */
data class PairingCode(val host: String, val port: Int, val token: String, val desktop: String) {
    sealed interface Parsed {
        data class Ok(val code: PairingCode) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    companion object {
        private const val NOT_BRAID = "That isn't a Braid pairing code. Show the code from Braid's Add phone screen on your computer."
        private const val DAMAGED = "That code looks damaged. Show a new one on your computer and scan again."
        private const val MAX_NAME = 64

        fun parse(text: String): Parsed {
            val trimmed = text.trim()
            val prefix = "braid://pair?"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return Parsed.Invalid(NOT_BRAID)
            val params = HashMap<String, String>()
            for (pair in trimmed.substring(prefix.length).split('&')) {
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=')
                val value = decode(pair.substringAfter('=', "")) ?: return Parsed.Invalid(DAMAGED)
                params.putIfAbsent(name, value)
            }
            if (params["v"] != "1") return Parsed.Invalid("This code comes from a newer version of Braid. Update this app, then scan again.")

            val rawHost = params["h"].orEmpty()
            val host = if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
                Egress.parseV6(rawHost.substring(1, rawHost.length - 1))?.takeUnless(::isUnreachableV6)
            } else {
                Egress.parseV4(rawHost)?.takeUnless(::isUnreachableV4)
            } ?: return Parsed.Invalid("The code doesn't contain an address this phone can use. Show a new code on your computer.")

            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return Parsed.Invalid("The code has an invalid port. Show a new code on your computer.")
            val token = params["t"]?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: return Parsed.Invalid("The code is incomplete. Show a new code on your computer and scan again.")
            val desktop = params["n"]?.takeIf { it.isNotBlank() && it.length <= MAX_NAME }
                ?: return Parsed.Invalid("The code has no usable computer name. Show a new code on your computer.")
            return Parsed.Ok(PairingCode(host, port, token, desktop))
        }

        /**
         * Link-local needs a scope id, which only means something on the machine
         * that chose it; loopback and unspecified mean the desktop itself.
         */
        private fun isUnreachableV6(v6: String) = v6.lowercase().let {
            it == "::1" || it == "::" || it.startsWith("fe8") || it.startsWith("fe9") || it.startsWith("fea") || it.startsWith("feb")
        }

        /**
         * 192.0.0.0/29 is a 464XLAT translator's own range: every device on an
         * IPv6-only network has one and none can be reached at it, so it would
         * pass a same-subnet check and then fail with nothing to explain why.
         */
        private fun isUnreachableV4(v4: String): Boolean {
            val o = v4.split('.').map { it.toInt() }
            return o[0] == 127 || o[0] == 0 || (o[0] == 192 && o[1] == 0 && o[2] == 0 && o[3] < 8)
        }

        /** Percent-decoding only: `+` stays a plus, because the desktop encodes spaces as %20. */
        private fun decode(value: String): String? {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    out.write(value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}
