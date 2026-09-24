package com.infodive.braid.relay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A deliberately small HTTP origin on loopback. It records every request head
 * it receives, byte for byte, and serves [payload] with single-range support.
 * A request body announced by Content-Length is read and echoed back instead.
 */
class TestOrigin(private val payload: ByteArray) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val heads = LinkedBlockingQueue<String>()

    init {
        thread(isDaemon = true, name = "test-origin") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { socket.use { serve(it) } }
            }
        }
    }

    fun nextHead(): String = heads.poll(5, TimeUnit.SECONDS) ?: error("origin saw no request")

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input) ?: return
        heads.add(head)
        val headers = head.split("\r\n").drop(1).filter { it.isNotEmpty() }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val out = socket.getOutputStream()

        val length = headers["content-length"]?.toInt()
        if (length != null) {
            val body = input.readNBytes(length)
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
            return
        }

        val range = headers["range"]
        if (range != null) {
            val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
            val slice = payload.copyOfRange(from, to + 1)
            out.write(
                ("HTTP/1.1 206 Partial Content\r\nContent-Length: ${slice.size}\r\n" +
                    "Content-Range: bytes $from-$to/${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(slice)
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(payload)
        }
        out.flush()
    }

    override fun close() = server.close()
}

/** Accepts connections and writes back every byte it reads, for CONNECT tests. */
class EchoServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val accepted = AtomicInteger()

    init {
        thread(isDaemon = true, name = "echo") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                accepted.incrementAndGet()
                thread(isDaemon = true) { socket.use { it.getInputStream().transferTo(it.getOutputStream()) } }
            }
        }
    }

    override fun close() = server.close()
}

/** Reads up to and including the blank line that ends an HTTP head. */
fun readHead(input: InputStream): String? {
    val buf = ByteArrayOutputStream()
    var matched = 0
    while (matched < 4) {
        val b = input.read()
        if (b < 0) return null
        buf.write(b)
        matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
    }
    return buf.toString(Charsets.ISO_8859_1)
}

class Response(val head: String, val body: ByteArray) {
    val status: Int get() = head.substringAfter(' ').substringBefore(' ').toInt()
    fun header(name: String): String? = head.split("\r\n").drop(1)
        .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
        ?.substringAfter(':')?.trim()
}

/** Sends [request] verbatim to the proxy and reads the response until the connection closes. */
fun exchange(proxyPort: Int, request: ByteArray): Response {
    Socket(InetAddress.getLoopbackAddress(), proxyPort).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().apply { write(request); flush() }
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: error("proxy closed without a response")
        return Response(head, input.readAllBytes())
    }
}
