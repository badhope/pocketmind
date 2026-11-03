package com.pocket.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.agent.ApprovalCenter
import com.pocket.agent.data.SettingsStore
import com.pocket.agent.data.Store
import com.pocket.agent.llm.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生引擎契约测试（v1.4 迁移核心）：
 * 在 JVM 上把整条链路跑起来 —— 引擎（回环端口 0）→ 路由 → 存储 → Agent 循环
 * → 脚本化假 LLM 客户端 —— 锁定与 Python agentd 对齐的对外行为。
 *
 * 覆盖：导航许可一次性、cookie 鉴权、health、设置打码回环、会话 CRUD、
 * 聊天 SSE（纯文本 / 工具调用 / 审批 / chat 模式）、工具清单、导出、fail-closed。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineContractTest {

    private lateinit var context: Context
    private lateinit var store: Store
    private lateinit var settings: SettingsStore
    private lateinit var engine: NativeEngine
    private lateinit var router: ApiRouter
    private lateinit var fakeLlm: FakeLlm
    private var cookie: String = ""

    /** 一轮对话的脚本：可选文本 + 可选工具调用。 */
    class FakeTurn(val text: String? = null, val calls: List<Triple<String, String, String>> = emptyList())

    /** 脚本化假 LLM：不碰网络，逐轮弹出脚本。 */
    class FakeLlm(cfg: SettingsStore.LlmConfig) : LlmClient(cfg) {
        val turns = ArrayDeque<FakeTurn>()

        override fun streamChat(
            messages: JSONArray,
            tools: JSONArray?,
            maxDepthTokens: Int,
            callbacks: StreamCallbacks,
            abortSignal: () -> Boolean,
        ): List<Triple<String, String, String>> {
            val turn = turns.removeFirstOrNull() ?: FakeTurn("(脚本耗尽)")
            turn.text?.let { callbacks.onText(it) }
            callbacks.onDone(LlmClient.Usage(10, 5, false), turn.calls)
            return turn.calls
        }

        override fun complete(messages: JSONArray, maxTokens: Int, temperature: Double): String = "测试会话"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 干净的配置/数据库：SettingsStore 与 Store 都写在 filesDir 下
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        store = Store(context)
        settings = SettingsStore(context)
        configureLlm()

        val approval = ApprovalCenter(context)
        AgentLoop.appContext = { context }
        fakeLlm = FakeLlm(SettingsStore.LlmConfig("http://fake.local", "sk-test", "fake-model", 0.7, 2048))
        val loop = AgentLoop(store, settings, approval) { fakeLlm }
        router = ApiRouter(context, store, settings, approval, loop) {
            engine?.takeIf { it.state == EngineState.RUNNING }
        }
        engine = NativeEngine(0, router)
        assertTrue("引擎应启动成功", engine.start())
    }

    @After
    fun tearDown() {
        engine.stop()
    }

    // ---------- HTTP helpers（直接打回环 socket） ----------

    private fun get(path: String): Response = request("GET", path, null)
    private fun send(method: String, path: String, body: String?): Response = request(method, path, body)

    class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String?): Response {
        val conn = URL("http://127.0.0.1:${engine.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        conn.headerFields.entries
            .firstOrNull { it.key.equals("set-cookie", true) }
            ?.value?.firstOrNull()?.let { cookie = it.substringBefore(';') }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Response(code, text)
    }

    private fun bootstrap() {
        // 与 Activity 相同的引导路径：领一次性导航许可 → 换会话 cookie
        val first = get("/?navigation=" + engine.newDocumentUrl()!!.substringAfter("navigation="))
        assertEquals("引导文档应 200：${first.body.take(120)}", 200, first.code)
        assertTrue(cookie.isNotEmpty())
    }

    private fun configureLlm(permissionMode: String = "approve") {
        settings.save(
            JSONObject()
                .put("llm", JSONObject()
                    .put("provider", "custom")
                    .put("base_url", "http://fake.local")
                    .put("api_key", "sk-test-1234567890")
                    .put("model", "fake-model"))
                .put("permission_mode", permissionMode)
        )
    }

    private fun newSession(): String =
        JSONObject(send("POST", "/api/sessions", "{}").body).getString("session_id")

    // ---------- 测试 ----------

    @Test
    fun `导航许可一次性 - 重放被拒绝`() {
        val url = engine.newDocumentUrl()!!
        val c1 = URL(url).openConnection() as HttpURLConnection
        assertEquals(200, c1.responseCode)
        c1.disconnect()
        val c2 = URL(url).openConnection() as HttpURLConnection
        assertEquals(403, c2.responseCode)
        c2.disconnect()
    }

    @Test
    fun `未认证的 api 请求返回 401`() {
        val conn = URL("http://127.0.0.1:${engine.port}/api/health").openConnection() as HttpURLConnection
        assertEquals(401, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun `health 反映模型配置状态`() {
        bootstrap()
        val h = JSONObject(get("/api/health").body)
        assertTrue(h.getBoolean("ready"))
        assertEquals("native", h.getString("mode"))

        // 清掉 llm 配置 → ready 应变 false
        settings.save(JSONObject().put("llm", JSONObject().put("api_key", "").put("base_url", "").put("model", "")))
        assertEquals(false, JSONObject(get("/api/health").body).getBoolean("ready"))
    }

    @Test
    fun `设置打码回环 - 保存打码值不覆盖真实 key`() {
        bootstrap()
        val masked = JSONObject(get("/api/settings").body).optJSONObject("llm")!!.optString("api_key")
        assertTrue("打码值应含 ****：$masked", masked.contains("****"))

        // 前端把打码值原样 PUT 回去：真实 key 必须原封不动
        send("PUT", "/api/settings", JSONObject().put("llm", JSONObject().put("api_key", masked)).toString())
        assertEquals("sk-test-1234567890", settings.llmConfig().apiKey)
    }

    @Test
    fun `会话 CRUD 与消息持久化`() {
        bootstrap()
        val sid = newSession()
        assertTrue(sid.isNotEmpty())

        store.addMessage(sid, "user", "你好")
        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))

        assertEquals(1, JSONArray(get("/api/sessions").body).length())

        val renamed = send("PUT", "/api/sessions/$sid", JSONObject().put("title", "改名").toString())
        assertEquals(200, renamed.code)
        assertEquals("改名", JSONArray(get("/api/sessions").body).getJSONObject(0).getString("title"))

        val deleted = send("DELETE", "/api/sessions/$sid", null)
        assertEquals(200, deleted.code)
        assertEquals(0, JSONArray(get("/api/sessions").body).length())
    }

    @Test
    fun `聊天 SSE - 纯文本回复并落库`() {
        bootstrap()
        fakeLlm.turns.addLast(FakeTurn(text = "你好"))
        val sid = newSession()
        val resp = send("POST", "/api/chat",
            JSONObject().put("session_id", sid).put("message", "打个招呼").toString())
        assertEquals(200, resp.code)
        assertTrue("SSE 内容应含 session 帧", resp.body.contains("\"type\":\"session\""))
        assertTrue(resp.body.contains("\"type\":\"text\""))
        assertTrue(resp.body.contains("\"text\":\"你好\""))
        assertTrue(resp.body.contains("\"stop_reason\":\"end_turn\""))

        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        assertEquals("user + assistant 落库", 2, msgs.length())
        assertEquals("你好", msgs.getJSONObject(1).getString("content"))
        // usage 进 meta（前端 restore 的契约）
        assertTrue(msgs.getJSONObject(1).optJSONObject("meta")!!.has("usage"))
    }

    @Test
    fun `聊天 SSE - 安全工具自动执行不弹审批`() {
        bootstrap()
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(Triple("call-1", "get_battery", "{}"))))
        fakeLlm.turns.addLast(FakeTurn(text = "电量情况如上。"))
        val sid = newSession()
        val resp = send("POST", "/api/chat",
            JSONObject().put("session_id", sid).put("message", "看下电量").toString())
        assertEquals(200, resp.code)
        assertTrue(resp.body.contains("\"type\":\"tool_start\""))
        assertTrue(resp.body.contains("\"name\":\"get_battery\""))
        // Robolectric 下 battery 服务可用，工具结果不应是 failed
        assertFalse(resp.body.contains("\"status\":\"failed\""))
        // tool 消息落库（前端 restore 契约：meta.tool_call_id + status）
        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        val toolMsg = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            .firstOrNull { it.getString("role") == "tool" }
        assertTrue("工具消息应落库", toolMsg != null)
        assertEquals("call-1", toolMsg!!.optJSONObject("meta")!!.optString("tool_call_id"))
    }

    @Test
    fun `审批流 - write 工具等待决策后执行`() {
        bootstrap()
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(Triple("call-9", "set_clipboard", "{\"text\":\"hello\"}"))))
        fakeLlm.turns.addLast(FakeTurn(text = "已写入剪贴板。"))
        val sid = newSession()

        // 聊天在后台线程跑（审批会阻塞到决策或超时）
        val chatThread = Thread {
            send("POST", "/api/chat",
                JSONObject().put("session_id", sid).put("message", "把 hello 放进剪贴板").toString())
        }
        chatThread.start()

        // 轮询等未决审批出现
        var pending = JSONArray()
        repeat(80) {
            Thread.sleep(100)
            pending = JSONObject(get("/api/approvals/pending").body).optJSONArray("pending") ?: JSONArray()
            if (pending.length() > 0) return@repeat
        }
        assertEquals("应出现一张未决审批卡", 1, pending.length())
        val p = pending.getJSONObject(0)
        assertEquals("set_clipboard", p.getString("name"))
        assertEquals("write", p.getString("risk"))

        // 批准 → 工具执行 → done
        assertEquals(200, send("POST", "/api/approval",
            JSONObject().put("session_id", sid).put("tool_call_id", p.getString("tool_call_id"))
                .put("decision", "allow_once").toString()).code)
        chatThread.join(30000)
        assertFalse("批准后聊天应结束", chatThread.isAlive)

        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        val toolMsg = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            .firstOrNull { it.getString("role") == "tool" }
        assertTrue("批准后工具消息应落库", toolMsg != null)
        assertEquals("completed", toolMsg!!.optJSONObject("meta")!!.optString("status"))
    }

    @Test
    fun `chat 模式 - 模型吐 tool_call 也一律拒绝`() {
        bootstrap()
        configureLlm(permissionMode = "chat")
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(Triple("call-x", "get_battery", "{}"))))
        fakeLlm.turns.addLast(FakeTurn(text = "抱歉，我不能操作设备。"))
        val sid = newSession()
        val resp = send("POST", "/api/chat",
            JSONObject().put("session_id", sid).put("message", "查电量").toString())
        assertTrue(resp.body.contains("\"status\":\"denied\""))
    }

    @Test
    fun `导出带 format_version 且含会话与消息`() {
        bootstrap()
        val sid = newSession()
        store.addMessage(sid, "user", "hello")
        val bundle = JSONObject(get("/api/export").body)
        assertEquals(1, bundle.getInt("format_version"))
        assertTrue(bundle.optJSONArray("sessions")!!.length() >= 1)
        assertTrue(bundle.optJSONArray("sessions")!!.getJSONObject(0)
            .optJSONArray("messages")!!.length() >= 1)
    }

    @Test
    fun `工具清单包含原生迁移的核心工具`() {
        bootstrap()
        val tools = JSONObject(get("/api/tools").body).optJSONArray("tools")!!
        val names = (0 until tools.length()).map { tools.getJSONObject(it).optString("name") }
        for (t in listOf("get_battery", "get_clipboard", "set_clipboard", "send_notification",
                "kb_add", "kb_search", "read_url", "tts_speak", "list_sensors", "run_shell")) {
            assertTrue("缺少工具 $t", t in names)
        }
    }

    @Test
    fun `未实现的 api 路径 fail-closed 503`() {
        bootstrap()
        assertEquals(503, get("/api/never-implemented").code)
    }
}
