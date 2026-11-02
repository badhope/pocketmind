package com.pocket.agent.runtime

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 进程内的本机 HTTP 服务。
 *
 * 身份模型（audit follow-up：不再使用 Bearer）：
 *  - 监听端口由系统分配（requestedPort = 0），只绑定 127.0.0.1；
 *  - 主文档加载必须携带 [newDocumentUrl] 签发的 16 字节一次性 navigation nonce
 *    （10 秒 TTL、最多 32 个未用、用完即焚）；
 *  - 换取的是 HttpOnly 会话 cookie：32 字节 SecureRandom、绝对 TTL 15 分钟、
 *    同一时刻最多 8 个会话。cookie 不进 URL、不进响应体、不进日志，页面 JS 读不到。
 *  - 授权只来自 cookie。Authorization: Bearer 不再是任何接口的凭证，
 *    因此 WebView 的额外请求头（它会带到同源子请求）里没有任何明文凭据。
 *
 * 仅接受 `Host: 127.0.0.1:<port>`；`localhost` 与其他端口一律拒绝。
 */
class NativeEngine(
    private val requestedPort: Int,
    private val page: String = DEFAULT_PAGE,
    private val grantTtlMillis: Long = DEFAULT_GRANT_TTL_MS,
    private val sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MS,
) {
    @Volatile var state = EngineState.STOPPED
        private set
    @Volatile private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    /** navigation nonce → 过期时间戳（毫秒）。一次性，消费即删除。 */
    private val grants = ConcurrentHashMap<String, Long>()

    /** 会话 ID → 过期时间戳（毫秒）。服务停止时全部清空。 */
    private val sessions = ConcurrentHashMap<String, Long>()

    private var workers: ThreadPoolExecutor? = null
    private var deadlines: ScheduledExecutorService? = null

    val port: Int get() = server?.localPort ?: -1
    val address: InetAddress? get() = server?.inetAddress

    @Synchronized fun start(): Boolean {
        if (state == EngineState.RUNNING) return true
        state = EngineState.STARTING
        return try {
            val socket = ServerSocket(requestedPort, 8, InetAddress.getByName("127.0.0.1"))
            server = socket
            val pool = ThreadPoolExecutor(
                4, 4, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
                { task -> Thread(task, "native-client").apply { isDaemon = true } },
            )
            val timer = ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "native-deadline").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
            workers = pool
            deadlines = timer
            state = EngineState.RUNNING
            Thread({ acceptLoop(socket, pool, timer) }, "native-engine").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: java.io.IOException) {
            state = EngineState.FAILED
            false
        }
    }

    @Synchronized fun stop() {
        val socket = server
        server = null
        state = EngineState.STOPPED
        grants.clear()
        sessions.clear()
        try {
            socket?.close()
        } catch (_: java.io.IOException) {
        }
        clients.forEach { try { it.close() } catch (_: java.io.IOException) { } }
        clients.clear()
        workers?.shutdownNow()
        deadlines?.shutdownNow()
        workers = null
        deadlines = null
    }

    @Synchronized fun newDocumentUrl(): String? {
        if (state != EngineState.RUNNING) return null
        val socket = server ?: return null
        val now = System.currentTimeMillis()
        purgeExpired(grants, now)
        if (grants.size >= MAX_PENDING_GRANTS) return null
        val nonce = randomHex(NONCE_BYTES)
        grants[nonce] = now + grantTtlMillis
        return "http://127.0.0.1:${socket.localPort}/?navigation=$nonce"
    }

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor, timer: ScheduledExecutorService) {
        try {
            while (!socket.isClosed) {
                val client = socket.accept()
                val deadlineAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                synchronized(this) {
                    if (server !== socket) {
                        client.close()
                    } else {
                        clients.add(client)
                        try {
                            pool.execute {
                                try {
                                    val delay = (deadlineAt - System.nanoTime()).coerceAtLeast(0)
                                    val deadline = timer.schedule(
                                        { try { client.close() } catch (_: java.io.IOException) { } },
                                        delay,
                                        TimeUnit.NANOSECONDS,
                                    )
                                    try {
                                        client.use { handle(it) }
                                    } finally {
                                        deadline.cancel(false)
                                    }
                                } catch (_: RejectedExecutionException) {
                                    try { client.close() } catch (_: java.io.IOException) { }
                                } finally {
                                    clients.remove(client)
                                }
                            }
                        } catch (e: RejectedExecutionException) {
                            clients.remove(client)
                            client.close()
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            synchronized(this) {
                if (server === socket) {
                    stop()
                    state = EngineState.FAILED
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 1500
            if (!client.inetAddress.isLoopbackAddress) return
            val input = BufferedInputStream(client.getInputStream())
            fun line(): String? {
                val bytes = ArrayList<Byte>()
                while (bytes.size < 4096) {
                    val c = input.read()
                    if (c < 0) return null
                    if (c == 10) return bytes.toByteArray().toString(StandardCharsets.US_ASCII).trimEnd('\r')
                    bytes.add(c.toByte())
                }
                return null
            }
            val request = line()?.split(' ') ?: return
            if (request.size != 3) return
            val headers = mutableMapOf<String, String>()
            var total = 0
            while (true) {
                val header = line() ?: return
                total += header.length
                if (total > 16384) return
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon <= 0) return
                val name = header.substring(0, colon).lowercase(java.util.Locale.ROOT)
                if (headers.containsKey(name)) return
                headers[name] = header.substring(colon + 1).trim()
            }

            val ownPort = port
            // 只接受本机回环 + 精确端口；localhost 别名与其他端口一律拒绝。
            if (headers["host"] != "127.0.0.1:$ownPort") return
            val origin = headers["origin"]
            if (origin != null && origin != "http://127.0.0.1:$ownPort") return

            val target = request[1]
            val path = target.substringBefore('?')
            val authenticated = hasLiveSession(headers["cookie"])
            val method = request[0]

            var setCookie: String? = null
            val (code, type, body) = when {
                method == "GET" && path == "/" -> when {
                    !consumeGrant(queryParam(target, "navigation")) -> Triple(403, JSON, FORBIDDEN)
                    else -> {
                        val id = issueSession()
                        setCookie = "$SESSION_COOKIE=$id; Path=/; HttpOnly; SameSite=Strict; " +
                            "Max-Age=${sessionTtlMillis / 1000}"
                        Triple(200, HTML, page)
                    }
                }
                method == "GET" && path == ApiContract.HEALTH -> Triple(200, JSON, healthBody())
                method == "GET" && path == ApiContract.STATUS && authenticated ->
                    Triple(200, JSON, statusBody())
                path == "/api" || path.startsWith("/api/") ->
                    Triple(if (authenticated) 503 else 401, JSON, if (authenticated) UNAVAILABLE else UNAUTHORIZED)
                else -> Triple(404, JSON, NOT_FOUND)
            }

            val data = body.toByteArray(StandardCharsets.UTF_8)
            val reason = when (code) {
                200 -> "OK"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                else -> "Service Unavailable"
            }
            val builder = StringBuilder()
            builder.append("HTTP/1.1 $code $reason\r\n")
                .append("Content-Type: $type\r\n")
                .append("Content-Length: ${data.size}\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Pragma: no-cache\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; ")
                .append("base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n")
            if (setCookie != null) builder.append("Set-Cookie: ").append(setCookie).append("\r\n")
            builder.append("Connection: close\r\n\r\n")
            client.getOutputStream().write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
            client.getOutputStream().write(data)
        } catch (_: java.io.IOException) {
        }
    }

    private fun hasLiveSession(cookieHeader: String?): Boolean {
        val id = cookieValue(cookieHeader, SESSION_COOKIE) ?: return false
        val expiry = sessions[id] ?: return false
        if (expiry < System.currentTimeMillis()) {
            sessions.remove(id)
            return false
        }
        return true
    }

    @Synchronized private fun issueSession(): String {
        val now = System.currentTimeMillis()
        purgeExpired(sessions, now)
        if (sessions.size >= MAX_SESSIONS) {
            sessions.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        val id = randomHex(SESSION_BYTES)
        sessions[id] = now + sessionTtlMillis
        return id
    }

    private fun purgeExpired(map: ConcurrentHashMap<String, Long>, now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < now) iterator.remove()
        }
    }

    private fun consumeGrant(nonce: String?): Boolean {
        if (nonce == null || nonce.isEmpty() || nonce.length > MAX_NONCE_CHARS) return false
        val expiry = grants.remove(nonce) ?: return false
        return expiry >= System.currentTimeMillis()
    }

    private fun cookieValue(header: String?, name: String): String? {
        if (header == null || header.isEmpty()) return null
        for (part in header.split(';')) {
            val trimmed = part.trim()
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            if (trimmed.substring(0, equals) != name) continue
            val value = trimmed.substring(equals + 1)
            if (value.isEmpty() || value.length > MAX_COOKIE_CHARS) return null
            return value
        }
        return null
    }

    private fun queryParam(target: String, name: String): String? {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (pair.substring(0, separator) == name) return pair.substring(separator + 1)
        }
        return null
    }

    private fun healthBody(): String =
        "{\"ok\":true,\"ready\":false,\"mode\":\"native\"," +
            "\"components\":{\"native\":\"ok\",\"agent\":\"unavailable\"," +
            "\"settings\":\"unavailable\",\"sessions\":\"unavailable\"}," +
            "\"api\":{\"health\":\"available\",\"native_status\":\"available\"," +
            "\"chat\":\"unavailable\",\"tools\":\"unavailable\",\"settings\":\"unavailable\"," +
            "\"sessions\":\"unavailable\",\"export\":\"unavailable\",\"import\":\"unavailable\"," +
            "\"approval\":\"unavailable\",\"jobs\":\"unavailable\"}}"

    private fun statusBody(): String =
        "{\"engine\":\"running\",\"ready\":false,\"document\":\"cookie-session\"," +
            "\"implemented\":[\"/api/health\",\"/api/native/status\"]," +
            "\"unimplemented\":[\"/api/chat\",\"/api/tools\",\"/api/settings\",\"/api/sessions\"," +
            "\"/api/export\",\"/api/import\",\"/api/approval\",\"/api/jobs\"]}"

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SECURE_RANDOM.nextBytes(buffer)
        val out = StringBuilder(bytes * 2)
        for (b in buffer) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    companion object {
        const val DEFAULT_PAGE = "<!doctype html><title>PocketMind</title>"
        const val DEFAULT_GRANT_TTL_MS = 10_000L
        const val DEFAULT_SESSION_TTL_MS = 15 * 60 * 1000L
        const val SESSION_COOKIE = "pm_session"
        private const val NONCE_BYTES = 16
        private const val SESSION_BYTES = 32
        private const val MAX_NONCE_CHARS = 64
        private const val MAX_COOKIE_CHARS = 128
        private const val MAX_PENDING_GRANTS = 32
        private const val MAX_SESSIONS = 8
        private const val JSON = "application/json"
        private const val HTML = "text/html; charset=utf-8"
        private const val UNAUTHORIZED = "{\"error\":\"unauthorized\"}"
        private const val FORBIDDEN = "{\"error\":\"forbidden\"}"
        private const val NOT_FOUND = "{\"error\":\"not_found\"}"
        private const val UNAVAILABLE = "{\"error\":\"unavailable\",\"migration\":\"not_implemented\"}"
        private val SECURE_RANDOM = SecureRandom()
        private const val HEX = "0123456789abcdef"
    }
}
