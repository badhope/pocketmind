package com.pocket.agent.runtime

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 一次 HTTP 交换：请求信息 + 响应写出。由 [NativeEngine] 构造并交给 handler。 */
class Exchange(
    val method: String,
    val path: String,
    /** 目标 URI 的 query 原文（键值解码后见 [query]。 */
    val queryRaw: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    private val out: OutputStream,
    private val deadline: DeadlineHandle?,
) {
    val query: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        if (queryRaw.isNotEmpty()) {
            for (pair in queryRaw.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                m[urlDecode(pair.substring(0, i))] = urlDecode(pair.substring(i + 1))
            }
        }
        m
    }

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)

    /** 普通响应（自动带安全响应头与 Content-Length）。 */
    fun respond(code: Int, contentType: String, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        deadline?.cancel()
        val data = body.toByteArray(StandardCharsets.UTF_8)
        val b = StringBuilder()
        b.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(data.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("X-Frame-Options: DENY\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; ")
            .append("base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n")
        for ((k, v) in extraHeaders) b.append(k).append(": ").append(v).append("\r\n")
        b.append("Connection: close\r\n\r\n")
        out.write(b.toString().toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        out.flush()
    }

    /**
     * SSE 流式响应：不写 Content-Length，靠 Connection: close 定界；
     * 返回的 writer 带 flush，供逐事件推送。调用后 handler 不得再写其他响应。
     */
    fun startSse(): SseWriter {
        deadline?.cancel()
        val b = StringBuilder()
        b.append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: text/event-stream; charset=utf-8\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Accel-Buffering: no\r\n")
            .append("Connection: close\r\n\r\n")
        out.write(b.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        return SseWriter(out)
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    private fun urlDecode(s: String): String = try {
        // 不能用 URLDecoder.decode(String, Charset)：那个重载要 API 33，
        // minSdk 是 26，旧机上会 NoSuchMethodError。带 charset 名的版本从 API 1 就在。
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        s
    }
}

/** SSE 帧写出器：`data: <payload>\n\n`，与 Python 侧 sse-starlette 帧格式一致。 */
class SseWriter(private val out: OutputStream) {
    @Volatile var broken = false
        private set

    @Synchronized fun send(payload: String): Boolean {
        if (broken) return false
        return try {
            val frame = "data: " + payload.replace("\n", "\ndata: ") + "\n\n"
            out.write(frame.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            true
        } catch (_: IOException) {
            broken = true
            false
        }
    }

    fun close() {
        try { out.flush() } catch (_: IOException) { }
    }
}

/** 已调度但可撤销的连接超时任务句柄。 */
class DeadlineHandle(private val future: java.util.concurrent.ScheduledFuture<*>) {
    fun cancel() {
        future.cancel(false)
    }
}

/**
 * 原生引擎的传输层：回环监听、一次性导航许可、HttpOnly cookie 会话、请求解析、
 * 连接超时。**不做任何业务处理** —— 认证通过后把 [Exchange] 交给构造时传入的
 * [handler]。未实现 /api 路径的 fail-closed 由路由层负责。
 *
 * 身份模型（与 Slice 2 一致，audit 通过）：
 *  - 只绑 127.0.0.1、端口系统分配；
 *  - 主文档必须携带 16 字节一次性 navigation nonce（10 s TTL、最多 32 个未用）；
 *  - 换取 HttpOnly 会话 cookie（32 字节、15 分钟、最多 8 个并发会话）；
 *  - 授权只来自 cookie；URL / 日志 / 响应体里不出现任何凭据。
 */
class NativeEngine(
    private val requestedPort: Int,
    private val handler: Handler,
    private val grantTtlMillis: Long = DEFAULT_GRANT_TTL_MS,
    private val sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MS,
) {
    /** 业务路由接口：认证通过后调用；SSE 长连接不受 2 s 连接超时影响。 */
    fun interface Handler {
        fun handle(ex: Exchange)
    }

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
            val socket = ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"))
            server = socket
            val pool = ThreadPoolExecutor(
                4, 16, 30, TimeUnit.SECONDS, SynchronousQueue(),
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
        } catch (_: IOException) {
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
        } catch (_: IOException) {
        }
        clients.forEach { try { it.close() } catch (_: IOException) { } }
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
                                    val deadlineHandle = timer.schedule(
                                        Runnable { try { client.close() } catch (_: IOException) { } },
                                        delay,
                                        TimeUnit.NANOSECONDS,
                                    )
                                    val deadline = DeadlineHandle(deadlineHandle)
                                    try {
                                        client.use { handle(it, deadline) }
                                    } finally {
                                        deadline.cancel()
                                    }
                                } catch (_: RejectedExecutionException) {
                                    try { client.close() } catch (_: IOException) { }
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
        } catch (_: IOException) {
            synchronized(this) {
                if (server === socket) {
                    stop()
                    state = EngineState.FAILED
                }
            }
        }
    }

    private fun handle(client: Socket, deadline: DeadlineHandle) {
        try {
            client.soTimeout = 1500
            if (!client.inetAddress.isLoopbackAddress) return
            val input = BufferedInputStream(client.getInputStream())
            fun line(): String? {
                val bytes = ArrayList<Byte>()
                while (bytes.size < 16384) {
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
                if (total > 32768) return
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon <= 0) return
                val name = header.substring(0, colon).lowercase(Locale.ROOT)
                if (headers.containsKey(name)) return
                headers[name] = header.substring(colon + 1).trim()
            }

            val ownPort = port
            // 只接受本机回环 + 精确端口；localhost 别名与其他端口一律拒绝。
            if (headers["host"] != "127.0.0.1:$ownPort") return
            val origin = headers["origin"]
            if (origin != null && origin != "http://127.0.0.1:$ownPort") return

            // 请求体：Content-Length 有界读取（上限 8 MB，chat / import 够用）。
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }

            val target = request[1]
            val path = target.substringBefore('?')
            val method = request[0]
            // 鉴权边界：只有「GET /」允许无会话通过 —— 它靠一次性导航许可换取
            // 会话 cookie（校验在路由层）。其余一切路径必须有活 cookie。
            val authenticated = hasLiveSession(headers["cookie"]) ||
                (method == "GET" && path == "/")

            if (!authenticated) {
                deadline.cancel()
                respondUnauthorized(client)
                return
            }

            val ex = Exchange(method, path, target.substringAfter('?', ""), headers, body, client.getOutputStream(), deadline)
            try {
                handler.handle(ex)
            } catch (_: Exception) {
                // handler 抛错不能裸断连接：给一个显式 500（若响应已开始则只能断开）
                try { ex.respond(500, "application/json", "{\"error\":\"internal_error\"}") } catch (_: IOException) { }
            }
        } catch (_: IOException) {
        }
    }

    private fun respondUnauthorized(client: Socket) {
        val b = StringBuilder()
            .append("HTTP/1.1 401 Unauthorized\r\n")
            .append("Content-Type: application/json\r\n")
            .append("Content-Length: ${UNAUTHORIZED.length}\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n\r\n")
        client.getOutputStream().write(b.toString().toByteArray(StandardCharsets.US_ASCII))
        client.getOutputStream().write(UNAUTHORIZED.toByteArray(StandardCharsets.UTF_8))
        client.getOutputStream().flush()
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

    @Synchronized fun issueSessionCookie(): String {
        val now = System.currentTimeMillis()
        purgeExpired(sessions, now)
        if (sessions.size >= MAX_SESSIONS) {
            sessions.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        val id = randomHex(SESSION_BYTES)
        sessions[id] = now + sessionTtlMillis
        return id
    }

    fun cookieHeaderFor(id: String): String =
        "$SESSION_COOKIE=$id; Path=/; HttpOnly; SameSite=Strict; Max-Age=${sessionTtlMillis / 1000}"

    private fun purgeExpired(map: ConcurrentHashMap<String, Long>, now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < now) iterator.remove()
        }
    }

    /** 主文档导航：校验并消费一次性 nonce，成功则签发会话 cookie。 */
    fun consumeGrant(nonce: String?): Boolean {
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
        const val DEFAULT_GRANT_TTL_MS = 10_000L
        const val DEFAULT_SESSION_TTL_MS = 15 * 60 * 1000L
        const val SESSION_COOKIE = "pm_session"
        private const val NONCE_BYTES = 16
        private const val SESSION_BYTES = 32
        private const val MAX_NONCE_CHARS = 64
        private const val MAX_COOKIE_CHARS = 128
        private const val MAX_PENDING_GRANTS = 32
        private const val MAX_SESSIONS = 8
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val UNAUTHORIZED = "{\"error\":\"unauthorized\"}"
        private val SECURE_RANDOM = SecureRandom()
        private const val HEX = "0123456789abcdef"
    }
}
