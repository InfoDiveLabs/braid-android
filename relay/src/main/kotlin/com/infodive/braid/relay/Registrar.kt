package com.infodive.braid.relay

import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Announces this phone to a desktop that showed a pairing code.
 *
 * A raw socket rather than HttpURLConnection, because the address the phone
 * reports has to be the one it reached the desktop from, and only the socket
 * knows that.
 */
object Registrar {
    sealed interface Outcome {
        data object Paired : Outcome
        data object Expired : Outcome
        data object Unreachable : Outcome
        data class Failed(val status: Int) : Outcome
    }

    private const val TIMEOUT_MS = 10_000

    /**
     * [chooseAddress] maps the socket's local address to the one to report,
     * so a caller can swap a rotating IPv6 privacy address for the stable one
     * on the same prefix.
     */
    fun register(
        code: PairingCode,
        deviceId: String,
        name: String,
        relayPort: Int,
        key: String,
        chooseAddress: (InetAddress) -> InetAddress = { it },
    ): Outcome {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(code.host), code.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val local = chooseAddress(socket.localAddress)
            val literal = local.hostAddress!!.substringBefore('%')
            val address = if (local is Inet6Address) "[$literal]:$relayPort" else "$literal:$relayPort"
            val body = Json.obj("token" to code.token, "device_id" to deviceId, "name" to name, "address" to address, "key" to key)
                .toByteArray(Charsets.UTF_8)
            val authority = if (':' in code.host) "[${code.host}]:${code.port}" else "${code.host}:${code.port}"
            val head = "POST /braid/register HTTP/1.1\r\nHost: $authority\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
            val status = RelayServer.readHead(BufferedInputStream(socket.getInputStream()))
                ?.substringAfter(' ')?.substringBefore(' ')?.toIntOrNull()
                ?: return Outcome.Unreachable
            when (status) {
                200 -> Outcome.Paired
                403 -> Outcome.Expired
                404 -> Outcome.Unreachable
                else -> Outcome.Failed(status)
            }
        } catch (e: IOException) {
            Outcome.Unreachable
        } finally {
            socket.close()
        }
    }
}
