package com.infodive.braid.relay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An HTTP forward proxy on one port.
 *
 * It never changes the bytes it carries. Request heads are rewritten only as a
 * proxy must: absolute-form becomes origin-form, hop-by-hop headers are
 * dropped, and `Connection: close` is added so that each client connection
 * carries exactly one request. That last rule keeps a later request on the
 * same connection, with its Proxy-Authorization, from being spliced unread to
 * whichever origin the first one named. Bodies, and everything inside a
 * CONNECT tunnel, are copied without being looked at.
 */
class RelayServer(
    private val router: Router,
    private val bindAddress: InetSocketAddress = InetSocketAddress(DEFAULT_PORT),
    private val control: ControlPlane? = null,
) : Closeable {
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "relay").apply { isDaemon = true }
    }
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val spliced = ConcurrentHashMap<Socket, Credentials>()
    private val active = AtomicInteger()

    val traffic = Traffic()

    /** Called with the number of proxied connections whenever it changes, so the host can hold a wake lock only while it is non-zero. */
    @Volatile
    var onActiveChanged: ((Int) -> Unit)? = null

    @Volatile
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: error("not started")

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(bindAddress, 128)
        server = socket
        pool.execute { acceptLoop(socket) }
    }

    /** Closes every connection carrying [lane], for a lane switched off or out of allowance mid-transfer. */
    fun drop(lane: String) = dropWhere { it.lane == lane }

    /** Closes every connection whose credentials match, for a desktop forgotten while it was downloading. */
    fun dropWhere(match: (Credentials) -> Boolean) {
        spliced.filterValues(match).keys.forEach { it.closeQuietly() }
    }

    override fun close() {
        server?.close()
        open.forEach { it.closeQuietly() }
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            open.add(client)
            pool.execute {
                try {
                    handle(client)
                } catch (e: IOException) {
                } finally {
                    client.closeQuietly()
                    open.remove(client)
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = HEAD_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
        val out = client.getOutputStream()
        val text = readHead(input) ?: return reply(out, 400, "Bad Request")
        val head = RequestHead.parse(text) ?: return reply(out, 400, "Bad Request")

        when (val target = head.target) {
            is Target.Local -> {
                val plane = control ?: return reply(out, 404, "Not Found")
                val body = readBody(head, input) ?: return reply(out, 400, "Bad Request")
                val from = client.inetAddress.hostAddress.substringBefore('%')
                val answer = plane.respond(head, target.path, body, from)
                reply(out, answer.code, answer.reason, answer.json)
            }
            Target.Unsupported -> reply(out, 501, "Not Implemented")
            is Target.Tunnel -> {
                val (credentials, upstream) = routeOrReply(head, out) ?: return
                val up = connectOrNull(upstream, target.host, target.port) ?: return reply(out, 502, "Bad Gateway")
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                splice(credentials, client, input, up)
            }
            is Target.Forward -> {
                val (credentials, upstream) = routeOrReply(head, out) ?: return
                val up = connectOrNull(upstream, target.host, target.port) ?: return reply(out, 502, "Bad Gateway")
                val request = originForm(head, target)
                up.getOutputStream().write(request)
                traffic.add(credentials.lane, request.size)
                splice(credentials, client, input, up)
            }
        }
    }

    private fun routeOrReply(head: RequestHead, out: OutputStream): Pair<Credentials, Upstream>? {
        val credentials = Credentials.parse(head.header("proxy-authorization"))
        return when (val route = router.route(credentials)) {
            is Route.Via -> (credentials ?: Credentials("", "")) to route.upstream
            Route.Refused -> null.also {
                reply(out, 407, "Proxy Authentication Required", extra = "Proxy-Authenticate: Basic realm=\"braid\"\r\n")
            }
            Route.Unavailable -> null.also { reply(out, 503, "Service Unavailable") }
        }
    }

    private fun connectOrNull(upstream: Upstream, host: String, port: Int): Socket? = try {
        upstream.connect(host, port).also {
            it.tcpNoDelay = true
            open.add(it)
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Copies both directions until both have ended. Half-closes are passed on,
     * so a tunnel behaves like the TCP connection it stands in for.
     */
    private fun splice(credentials: Credentials, client: Socket, clientIn: InputStream, up: Socket) {
        val lane = credentials.lane
        client.soTimeout = 0
        spliced[client] = credentials
        spliced[up] = credentials
        onActiveChanged?.invoke(active.incrementAndGet())
        try {
            val toUpstream = pool.submit {
                try {
                    copy(clientIn, up.getOutputStream()) { traffic.add(lane, it) }
                    up.shutdownOutput()
                } catch (e: IOException) {
                    up.closeQuietly()
                    client.closeQuietly()
                }
            }
            try {
                copy(up.getInputStream(), client.getOutputStream()) { traffic.add(lane, it) }
                client.shutdownOutput()
            } catch (e: IOException) {
                up.closeQuietly()
                client.closeQuietly()
            }
            toUpstream.get(DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
        } finally {
            up.closeQuietly()
            open.remove(up)
            spliced.remove(up)
            spliced.remove(client)
            onActiveChanged?.invoke(active.decrementAndGet())
        }
    }

    companion object {
        const val DEFAULT_PORT = 8710
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEAD = 64 * 1024
        private const val MAX_CONTROL_BODY = 16 * 1024
        private const val HEAD_TIMEOUT_MS = 30_000
        private const val DRAIN_TIMEOUT_S = 30L

        /** The request as the origin should see it. */
        internal fun originForm(head: RequestHead, target: Target.Forward): ByteArray {
            val named = head.header("connection").orEmpty()
                .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val kept = head.headerLines.filter { line ->
                val name = line.substringBefore(':').trim().lowercase()
                !name.startsWith("proxy-") && name !in named && name != "connection" && name != "keep-alive"
            }
            val sb = StringBuilder()
            sb.append(head.method).append(' ').append(target.path).append(' ').append(head.version).append("\r\n")
            if (kept.none { it.substringBefore(':').trim().equals("host", ignoreCase = true) }) {
                sb.append("Host: ").append(hostHeader(target)).append("\r\n")
            }
            kept.forEach { sb.append(it).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun hostHeader(target: Target.Forward): String {
            val host = if (':' in target.host) "[${target.host}]" else target.host
            return if (target.port == 80) host else "$host:${target.port}"
        }

        /** Reads through the blank line ending a head, or null on EOF or an oversized head. */
        internal fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0 || buf.size() >= MAX_HEAD) return null
                buf.write(b)
                matched = when {
                    b == "\r\n\r\n"[matched].code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            return buf.toString(Charsets.ISO_8859_1)
        }

        private fun readBody(head: RequestHead, input: InputStream): ByteArray? {
            val length = head.header("content-length")?.let { it.toIntOrNull() ?: return null } ?: 0
            if (length !in 0..MAX_CONTROL_BODY) return null
            return input.readNBytes(length).takeIf { it.size == length }
        }

        private fun reply(out: OutputStream, code: Int, reason: String, json: String? = null, extra: String = "") {
            val body = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val type = if (json != null) "Content-Type: application/json; charset=utf-8\r\n" else ""
            out.write("HTTP/1.1 $code $reason\r\n$type${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }

        private fun copy(from: InputStream, to: OutputStream, counted: (Int) -> Unit) {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.read(buf)
                if (n < 0) return
                to.write(buf, 0, n)
                to.flush()
                counted(n)
            }
        }

        private fun Socket.closeQuietly() {
            try {
                close()
            } catch (e: IOException) {
            }
        }
    }
}
