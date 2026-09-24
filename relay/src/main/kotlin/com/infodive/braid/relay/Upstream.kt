package com.infodive.braid.relay

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * How the relay reaches the internet for one lane. An implementation owns name
 * resolution as well as the socket, because resolving through the default
 * network and connecting through another measures, and leaks, the wrong path.
 *
 * It must fail rather than fall back to a different network.
 */
fun interface Upstream {
    fun connect(host: String, port: Int): Socket

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** Whatever the operating system would do by default. For tests and for Task 2. */
        val DIRECT = Upstream { host, port -> connectFirst(InetAddress.getAllByName(host), port) { Socket() } }

        /** Tries each address in turn with a fresh socket from [newSocket], returning the first that connects. */
        fun connectFirst(addresses: Array<InetAddress>, port: Int, newSocket: () -> Socket): Socket {
            var last: IOException? = null
            for (address in addresses) {
                val socket = newSocket()
                try {
                    socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    return socket
                } catch (e: IOException) {
                    socket.close()
                    last = e
                }
            }
            throw last ?: IOException("no addresses")
        }
    }
}
