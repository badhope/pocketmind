package com.pocket.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class NativeEngineTest {

    private data class Response(
        val status: Int,
        val contentType: String,
        val body: String,
        val setCookie: List<String>,
    ) {
        /** 从 Set-Cookie 里抽出 name=value，模拟浏览器对 Cookie 头的组装。 */
        val cookiePair: String?
            get() = setCookie.firstOrNull()?.substringBefore(';')?.trim()
    }

    private fun call(
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            Response(code, conn.contentType ?: "", body, conn.headerFields["Set-Cookie"] ?: emptyList())
        } finally {
            conn.disconnect()
        }
    }

    private fun openSession(engine: NativeEngine): String {
        val url = engine.newDocumentUrl()!!
        val path = url.removePrefix("http://127.0.0.1:${engine.port}")
        val response = call(engine.port, "GET", path)
        assertEquals(200, response.status)
        val cookie = response.cookiePair
        assertTrue("document load must issue a session cookie", cookie != null && cookie.startsWith("${NativeEngine.SESSION_COOKIE}="))
        return cookie!!
    }

    private fun socketText(port: Int, request: String): String =
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }

    @Test
    fun documentRequiresSingleUseGrantAndIssuesHttpOnlyCookie() {
        val engine = NativeEngine(0, page = "<html>bootstrap</html>")
        try {
            assertTrue(engine.start())
            assertEquals(EngineState.RUNNING, engine.state)
            assertTrue(engine.address!!.isLoopbackAddress)

            val url = engine.newDocumentUrl()!!
            assertTrue(url, url.matches(Regex("http://127\\.0\\.0\\.1:\\d+/\\?navigation=[0-9a-f]{32}")))
            val path = url.removePrefix("http://127.0.0.1:${engine.port}")

            val loaded = call(engine.port, "GET", path)
            assertEquals(200, loaded.status)
            assertTrue(loaded.contentType.startsWith("text/html"))
            assertEquals("<html>bootstrap</html>", loaded.body)
            val cookie = loaded.setCookie.joinToString(" ").lowercase()
            assertTrue("cookie must be HttpOnly", cookie.contains("httponly"))
            assertTrue("cookie must be SameSite=Strict", cookie.replace(" ", "").contains("samesite=strict"))
            assertTrue("cookie must be path-scoped", cookie.contains("path=/"))
            val maxAge = Regex("max-age=(\\d+)").find(cookie)!!.groupValues[1].toInt()
            assertTrue("session TTL must be short (<= 15 min), was ${maxAge}s", maxAge in 1..900)
            assertFalse("cookie value must not be echoed in the body", loaded.body.contains(
                loaded.cookiePair!!.substringAfter('=')))

            assertEquals("nonce must be single use", 403, call(engine.port, "GET", path).status)
            assertEquals("a bare / without nonce is rejected", 403, call(engine.port, "GET", "/").status)

            assertFalse("no Bearer credential is ever required",
                url.contains("Bearer") || loaded.body.contains("Bearer"))
        } finally {
            engine.stop()
        }
        assertEquals(EngineState.STOPPED, engine.state)
    }

    @Test
    fun cookieAuthenticatesApiWithoutAnyAuthorizationHeader() {
        val engine = NativeEngine(0)
        try {
            assertTrue(engine.start())
            val cookie = openSession(engine)

            // 子请求（前端接入后由页面 JS 发出的同源 fetch）只带 cookie，不带任何 Bearer。
            val status = call(engine.port, "GET", ApiContract.STATUS, mapOf("Cookie" to cookie))
            assertEquals(200, status.status)
            assertTrue(status.body.contains("cookie-session"))

            val anonymous = call(engine.port, "GET", ApiContract.STATUS)
            assertEquals("no cookie means unauthenticated", 401, anonymous.status)
            assertEquals(401, call(engine.port, "GET", "/api/chat").status)
            assertEquals(401, call(engine.port, "GET", "/api/chat",
                mapOf("Authorization" to "Bearer anything")).status)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun expiredGrantIsRejected() {
        val engine = NativeEngine(0, grantTtlMillis = 1)
        try {
            assertTrue(engine.start())
            val url = engine.newDocumentUrl()!!
            Thread.sleep(50)
            assertEquals(403, call(engine.port, "GET", url.removePrefix("http://127.0.0.1:${engine.port}")).status)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun expiredSessionIsRejected() {
        val engine = NativeEngine(0, sessionTtlMillis = 1)
        try {
            assertTrue(engine.start())
            val cookie = openSession(engine)
            Thread.sleep(50)
            assertEquals(401, call(engine.port, "GET", ApiContract.STATUS, mapOf("Cookie" to cookie)).status)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun forgedAndReplayedCookiesAreRejected() {
        val engine = NativeEngine(0)
        try {
            assertTrue(engine.start())
            val cookie = openSession(engine)
            assertEquals(401, call(engine.port, "GET", ApiContract.STATUS,
                mapOf("Cookie" to "${NativeEngine.SESSION_COOKIE}=deadbeef")).status)
            assertEquals(401, call(engine.port, "GET", ApiContract.STATUS,
                mapOf("Cookie" to "${NativeEngine.SESSION_COOKIE}=".padEnd(9, 'a'))).status)
            assertEquals(200, call(engine.port, "GET", ApiContract.STATUS,
                mapOf("Cookie" to "other=1; $cookie; more=2")).status)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun unimplementedApisFailClosedWith503() {
        val engine = NativeEngine(0)
        try {
            assertTrue(engine.start())
            val cookie = openSession(engine)
            for (path in ApiContract.unimplemented) {
                val response = call(engine.port, "GET", path, mapOf("Cookie" to cookie))
                assertEquals("$path must not pretend to be migrated", 503, response.status)
                assertTrue("$path body must declare unimplemented", response.body.contains("not_implemented"))
            }
            val unknown = call(engine.port, "GET", "/api/does-not-exist", mapOf("Cookie" to cookie))
            assertEquals(503, unknown.status)
            assertTrue(unknown.body.contains("not_implemented"))
        } finally {
            engine.stop()
        }
    }

    @Test
    fun healthIsAnonymousAndReportsOnlyRealCapabilities() {
        val engine = NativeEngine(0)
        try {
            assertTrue(engine.start())
            val (status, _, body, _) = call(engine.port, "GET", ApiContract.HEALTH)
            assertEquals(200, status)
            assertTrue(body.contains("\"ready\":false"))
            assertTrue(body.contains("\"settings\":\"unavailable\""))
            assertTrue(body.contains("\"sessions\":\"unavailable\""))
            assertTrue(body.contains("\"health\":\"available\""))
            assertFalse("health must not leak credentials", body.contains("Bearer"))
        } finally {
            engine.stop()
        }
    }

    @Test
    fun hostMustBeExactLoopbackPort() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        try {
            val url = engine.newDocumentUrl()!!
            val path = url.removePrefix("http://127.0.0.1:${engine.port}")
            val host = "127.0.0.1:${engine.port}"
            assertTrue(socketText(engine.port, "GET $path HTTP/1.1\r\nHost: $host\r\nOrigin: http://$host\r\n\r\n")
                .startsWith("HTTP/1.1 200"))
            assertEquals("must reject localhost alias", "", socketText(engine.port,
                "GET $path HTTP/1.1\r\nHost: localhost:${engine.port}\r\n\r\n"))
            assertEquals("must reject attacker host", "", socketText(engine.port,
                "GET $path HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n"))
            assertEquals("must reject other ports", "", socketText(engine.port,
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n"))
            assertEquals("must reject cross-site origin", "", socketText(engine.port,
                "GET $path HTTP/1.1\r\nHost: $host\r\nOrigin: http://attacker.invalid\r\n\r\n"))
            assertEquals("", socketText(engine.port, "GET $path HTTP/1.1\r\nHost: $host\r\nHost: $host\r\n\r\n"))
        } finally {
            engine.stop()
        }
    }

    @Test
    fun concurrentGrantsAreUniqueAndEachWorksOnce() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        try {
            val urls = ConcurrentHashMap<String, String>()
            val gate = CountDownLatch(1)
            val threads = (1..8).map { i ->
                Thread {
                    gate.await()
                    // 只并发签发许可，不并发压服务：4 个工作线程 + SynchronousQueue
                    // 的饱和拒绝是另一条已验证的行为，混进来会让本用例变成 flaky。
                    urls["$i"] = engine.newDocumentUrl()!!
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach { it.join(5000) }

            assertEquals(8, urls.size)
            assertEquals("each grant must be distinct", 8, urls.values.toSet().size)

            for (url in urls.values) {
                val path = url.removePrefix("http://127.0.0.1:${engine.port}")
                assertEquals("grant must be usable once", 200, call(engine.port, "GET", path).status)
                assertEquals("grant must not be replayable", 403, call(engine.port, "GET", path).status)
            }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun stopClearsSessionsAndRestartInvalidatesOldPort() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        val cookie = openSession(engine)
        val oldPort = engine.port
        engine.stop()

        assertNull(engine.newDocumentUrl())
        assertTrue(engine.start())
        assertNotEquals(oldPort, engine.port)
        assertTrue(runCatching {
            call(oldPort, "GET", ApiContract.STATUS, mapOf("Cookie" to cookie))
        }.isFailure)
        assertEquals("old session must not survive restart", 401,
            call(engine.port, "GET", ApiContract.STATUS, mapOf("Cookie" to cookie)).status)
        engine.stop()
    }

    @Test
    fun occupiedPortFailsClosed() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val engine = NativeEngine(occupied.localPort)
            assertFalse(engine.start())
            assertEquals(EngineState.FAILED, engine.state)
            assertNull(engine.newDocumentUrl())
            engine.stop()
            assertEquals(EngineState.STOPPED, engine.state)
        }
    }

    @Test
    fun slowClientDoesNotBlockOtherRequests() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        val slow = java.net.Socket("127.0.0.1", engine.port)
        try {
            slow.soTimeout = 1000
            slow.getOutputStream().write("GET / HTTP/1.1\r\nHost:".toByteArray())
            slow.getOutputStream().flush()
            Thread.sleep(100)
            val started = System.nanoTime()
            assertEquals(200, call(engine.port, "GET", ApiContract.HEALTH).status)
            assertTrue("slow connection blocked health", (System.nanoTime() - started) / 1_000_000 < 700)
            engine.stop()
            assertEquals(-1, slow.getInputStream().read())
        } finally {
            slow.close()
            engine.stop()
        }
    }

    @Test
    fun trickleRequestIsClosedByOverallDeadline() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        val slow = java.net.Socket("127.0.0.1", engine.port)
        try {
            slow.soTimeout = 3500
            val output = slow.getOutputStream()
            output.write("GET / HTTP/1.1\r\nHost:".toByteArray())
            output.flush()
            val start = System.nanoTime()
            val writer = Thread {
                try {
                    repeat(10) { Thread.sleep(400); output.write('a'.code); output.flush() }
                } catch (_: java.io.IOException) {
                }
            }
            writer.isDaemon = true
            writer.start()
            assertEquals(-1, slow.getInputStream().read())
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
            writer.join(1500)
        } finally {
            slow.close()
            engine.stop()
        }
    }

    @Test
    fun saturationRecoversAfterSlowClientsExpire() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        val sockets = (1..4).map {
            java.net.Socket("127.0.0.1", engine.port).apply {
                soTimeout = 4000
                getOutputStream().write("GET / HTTP/1.1\r\nHost:".toByteArray())
                getOutputStream().flush()
            }
        }
        try {
            Thread.sleep(200)
            java.net.Socket("127.0.0.1", engine.port).use { extra ->
                extra.soTimeout = 1000
                assertEquals(-1, extra.getInputStream().read())
            }
            sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
            assertEquals(200, call(engine.port, "GET", ApiContract.HEALTH).status)
        } finally {
            sockets.forEach { it.close() }
            engine.stop()
        }
    }

    @Test
    fun concurrentStopAndRestartKeepsNewServerUsable() {
        val engine = NativeEngine(0)
        assertTrue(engine.start())
        repeat(20) {
            val gate = CountDownLatch(1)
            val stop = Thread { gate.await(); engine.stop() }
            val start = Thread { gate.await(); engine.start() }
            stop.start()
            start.start()
            gate.countDown()
            stop.join()
            start.join()
            assertTrue(engine.start())
            assertEquals(200, call(engine.port, "GET", ApiContract.HEALTH).status)
        }
        engine.stop()
    }
}
