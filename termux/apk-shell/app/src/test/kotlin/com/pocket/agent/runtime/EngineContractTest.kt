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
    private lateinit var approval: ApprovalCenter
    private lateinit var loop: AgentLoop
    private var cookie: String = ""

    /** 一轮对话的脚本：可选文本 + 可选工具调用。 */
    class FakeTurn(val text: String? = null, val calls: List<Triple<String, String, String>> = emptyList())

    /** 脚本化假 LLM：不碰网络，逐轮弹出脚本。 */
    class FakeLlm(cfg: SettingsStore.LlmConfig) : LlmClient(cfg) {
        val turns = ArrayDeque<FakeTurn>()
        /** 最近一次 streamChat 收到的完整 messages，用于断言上下文注入。 */
        var lastMessages: List<JSONObject> = emptyList()

        override fun streamChat(
            messages: JSONArray,
            tools: JSONArray?,
            maxDepthTokens: Int,
            callbacks: StreamCallbacks,
            abortSignal: () -> Boolean,
        ): List<Triple<String, String, String>> {
            lastMessages = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
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

        approval = ApprovalCenter(context)
        AgentLoop.appContext = { context }
        fakeLlm = FakeLlm(SettingsStore.LlmConfig("http://fake.local", "sk-test", "fake-model", 0.7, 2048))
        loop = AgentLoop(store, settings, approval) { fakeLlm }
        router = ApiRouter(context, store, settings, approval, loop, {
            engine?.takeIf { it.state == EngineState.RUNNING }
        }, NativeScheduler(context, store, loop))
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

    // ---------- 定时任务（1.5：原生 AlarmManager 调度） ----------

    @Test
    fun `定时任务 CRUD - 创建校验表达式与非法输入`() {
        bootstrap()
        // 缺字段 → 400
        assertEquals(400, send("POST", "/api/jobs", "{}").code)
        // 非法 cron → 400
        assertEquals(400, send("POST", "/api/jobs", JSONObject()
            .put("name", "坏任务").put("trigger_type", "cron").put("expr", "not a cron")
            .put("message", "x").toString()).code)
        // 非法 interval（inf）→ 400
        assertEquals(400, send("POST", "/api/jobs", JSONObject()
            .put("name", "坏间隔").put("trigger_type", "interval").put("expr", "inf")
            .put("message", "x").toString()).code)

        val created = send("POST", "/api/jobs", JSONObject()
            .put("name", "每日总结").put("trigger_type", "cron").put("expr", "0 8 * * *")
            .put("message", "总结昨天").toString())
        assertEquals(200, created.code)
        val job = JSONObject(created.body)
        val id = job.getString("id")
        assertTrue("应算出下次触发时间", !job.isNull("next_run"))

        val list = JSONObject(get("/api/jobs").body).getJSONArray("jobs")
        assertEquals(1, list.length())
        assertEquals("每日总结", list.getJSONObject(0).getString("name"))

        val off = JSONObject(send("PUT", "/api/jobs/$id",
            JSONObject().put("enabled", false).toString()).body)
        assertEquals(false, off.getBoolean("enabled"))
        assertTrue("停用后无下次触发", off.isNull("next_run"))

        assertEquals(200, send("DELETE", "/api/jobs/$id", null).code)
        assertEquals(0, JSONObject(get("/api/jobs").body).getJSONArray("jobs").length())
        assertEquals(404, send("DELETE", "/api/jobs/$id", null).code)
    }

    @Test
    fun `定时任务 - 配方可列出且可应用`() {
        bootstrap()
        val recipes = JSONObject(get("/api/recipes").body).getJSONArray("recipes")
        assertEquals(4, recipes.length())
        assertTrue("配方应带 applied 字段", recipes.getJSONObject(0).has("applied"))

        val applied = send("POST", "/api/recipes/daily_summary/apply", "{}")
        assertEquals(200, applied.code)
        val job = JSONObject(applied.body)
        assertEquals("每日总结", job.getString("name"))
        assertEquals("0 8 * * *", job.getString("expr"))

        val again = JSONObject(get("/api/recipes").body).getJSONArray("recipes")
        val ds = (0 until again.length()).map { again.getJSONObject(it) }
            .first { it.getString("id") == "daily_summary" }
        assertEquals(true, ds.getBoolean("applied"))

        assertEquals(404, send("POST", "/api/recipes/nope/apply", "{}").code)
    }

    @Test
    fun `定时任务 - 立即执行一次会把消息写进会话`() {
        bootstrap()
        val sid = newSession()
        val created = JSONObject(send("POST", "/api/jobs", JSONObject()
            .put("name", "手动跑").put("trigger_type", "interval").put("expr", "3600")
            .put("message", "现在跑一次").put("session_id", sid).toString()).body)
        val id = created.getString("id")

        assertEquals(200, send("POST", "/api/jobs/$id/run", "{}").code)
        // 后台线程执行：轮询等 user 消息落库
        var found = false
        for (i in 0 until 100) {
            Thread.sleep(100)
            val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
            found = (0 until msgs.length()).any {
                msgs.getJSONObject(it).getString("role") == "user" &&
                    msgs.getJSONObject(it).getString("content") == "现在跑一次"
            }
            if (found) break
        }
        assertTrue("立即执行应把任务消息写进目标会话", found)
    }

    // ---------- 撤销（1.5：写/删前自动备份） ----------

    @Test
    fun `撤销 - 覆盖写可还原，重复撤销被拒绝`() {
        bootstrap()
        configureLlm(permissionMode = "auto")
        val sid = newSession()
        val target = java.io.File(context.filesDir, "undo-demo.txt")
        target.writeText("原始内容")

        fakeLlm.turns.addLast(FakeTurn(calls = listOf(
            Triple("call-w1", "write_file", "{\"path\":\"undo-demo.txt\",\"content\":\"新内容\"}"))))
        fakeLlm.turns.addLast(FakeTurn(text = "已写入"))
        val resp = send("POST", "/api/chat",
            JSONObject().put("session_id", sid).put("message", "改一下文件").toString())
        assertTrue("写入应成功：${resp.body.take(200)}", resp.body.contains("\"status\":\"completed\""))
        assertTrue("应标记可撤销", resp.body.contains("\"undoable\":true"))
        assertEquals("新内容", target.readText())

        val undone = JSONObject(send("POST", "/api/undo",
            JSONObject().put("session_id", sid).put("tool_call_id", "call-w1").toString()).body)
        assertTrue("撤销应成功：$undone", undone.has("ok"))
        assertEquals("原始内容", target.readText())

        val twice = JSONObject(send("POST", "/api/undo",
            JSONObject().put("session_id", sid).put("tool_call_id", "call-w1").toString()).body)
        assertTrue("重复撤销应报错：$twice", twice.has("error"))
    }

    @Test
    fun `撤销 - 无备份的调用返回错误而不是崩溃`() {
        bootstrap()
        val r = JSONObject(send("POST", "/api/undo",
            JSONObject().put("session_id", "nope").put("tool_call_id", "nope").toString()).body)
        assertTrue(r.has("error"))
        assertEquals(400, send("POST", "/api/undo", "{}").code)
    }

    // ---------- run_shell（1.5：原生 /system/bin/sh） ----------

    @Test
    fun `run_shell - 原生可执行并返回退出码与输出`() {
        bootstrap()
        val env = com.pocket.agent.tools.DeviceEnv(context, store, "")
        val tool = com.pocket.agent.tools.ToolRegistry.byName("run_shell")!!
        val r = tool.handler(JSONObject().put("command", "echo pm-shell-ok"), env)
        assertFalse("原生 shell 不应再返回 capability_unavailable：$r",
            r.optString("error_code") == "capability_unavailable")
        assertEquals("退出码应为 0：$r", 0, r.optInt("exit_code", -99))
        assertTrue("stdout 应含命令输出：$r", r.optString("stdout").contains("pm-shell-ok"))
        assertTrue(r.optBoolean("ok"))
    }

    @Test
    fun `run_shell - 非零退出码如实上报`() {
        bootstrap()
        val env = com.pocket.agent.tools.DeviceEnv(context, store, "")
        val r = com.pocket.agent.tools.ToolRegistry.byName("run_shell")!!
            .handler(JSONObject().put("command", "exit 3"), env)
        assertEquals("非零退出码应原样返回：$r", 3, r.optInt("exit_code", -99))
        assertFalse("非零退出不应报 ok", r.optBoolean("ok"))
    }

    // ---------- 会话搜索 / 压缩（1.5.1：前端一直在调，原生此前 404） ----------

    @Test
    fun `会话搜索 - 跨会话命中正文与工具输出并标注来源`() {
        bootstrap()
        val a = newSession()
        val b = newSession()
        store.addMessage(a, "user", "明天记得买牛奶")
        store.addMessage(b, "assistant", "这里是无关内容")
        store.addMessage(b, "tool", "{\"battery\":42}", JSONObject().put("tool_call_id", "c1"))

        val resp = JSONObject(get("/api/sessions/search?q=" + java.net.URLEncoder.encode("牛奶", "UTF-8")).body)
        assertEquals(1, resp.getInt("count"))
        val hit = resp.getJSONArray("items").getJSONObject(0)
        assertEquals(a, hit.getString("session_id"))
        assertEquals("message", hit.getString("matched"))
        assertTrue("摘要应含关键词：$hit", hit.getString("snippet").contains("牛奶"))

        // 工具输出也要能搜到（1.3 起的契约）
        val toolHit = JSONObject(get("/api/sessions/search?q=battery").body)
        assertEquals(1, toolHit.getInt("count"))
        assertEquals("tool_output", toolHit.getJSONArray("items").getJSONObject(0).getString("matched"))

        // 空查询返回空集而不是全量
        assertEquals(0, JSONObject(get("/api/sessions/search?q=").body).getInt("count"))
    }

    @Test
    fun `会话压缩 - 归档早期消息并把摘要注入后续上下文`() {
        bootstrap()
        val sid = newSession()
        // 12 条消息：压缩后保留最近 4 条
        for (i in 1..12) {
            store.addMessage(sid, if (i % 2 == 1) "user" else "assistant", "第 $i 条消息")
        }
        val resp = JSONObject(send("POST", "/api/sessions/$sid/compact",
            JSONObject().put("keep", 4).toString()).body)
        assertTrue("压缩应成功：$resp", resp.optBoolean("ok"))
        assertEquals(8, resp.getInt("archived_messages"))
        assertEquals(4, resp.getInt("kept"))
        assertTrue("应产出摘要：$resp", resp.optString("summary").isNotEmpty())

        // 水位推进：再压一次应报「已归档」
        val again = JSONObject(send("POST", "/api/sessions/$sid/compact",
            JSONObject().put("keep", 4).toString()).body)
        assertEquals(false, again.optBoolean("ok"))

        // 消息本身不能被删（压缩是归档不是截断）
        assertEquals(12, JSONArray(get("/api/sessions/$sid/messages").body).length())

        // 摘要要进后续对话的 system prompt，否则模型仍然失忆
        fakeLlm.turns.addLast(FakeTurn(text = "好的"))
        send("POST", "/api/chat", JSONObject().put("session_id", sid).put("message", "继续说").toString())
        val sent = fakeLlm.lastMessages
        assertTrue("system prompt 应含归档摘要，实际：${sent.take(300)}",
            sent.any { it.optString("role") == "system" && it.optString("content").contains("早期对话摘要") ||
                it.optString("content").contains("摘要") })
    }

    @Test
    fun `会话压缩 - 消息不足时报无需压缩`() {
        bootstrap()
        val sid = newSession()
        store.addMessage(sid, "user", "只有一条")
        val r = JSONObject(send("POST", "/api/sessions/$sid/compact", "{}").body)
        assertEquals(false, r.optBoolean("ok"))
        assertTrue(r.optString("reason").contains("无需压缩"))
        // 不存在的会话 → 404
        assertEquals(404, send("POST", "/api/sessions/nope/compact", "{}").code)
    }

    // ---------- 契约表自检（防止文档再次与实现脱节） ----------

    @Test
    fun `接口契约 - 列在 implemented 的端点都不返回 503 not_implemented`() {
        bootstrap()
        // 逐个打真实路径：只要返回 `migration:not_implemented` 就说明契约表撒谎。
        // 用 GET 探测只读端点；写端点用最小请求体，断言"不是 503 未实现"而非具体成功。
        val probes = mapOf(
            "/api/health" to "GET", "/api/native/status" to "GET",
            "/api/tools" to "GET", "/api/settings" to "GET", "/api/providers" to "GET",
            "/api/sessions" to "GET", "/api/approvals" to "GET",
            "/api/approvals/pending" to "GET", "/api/jobs" to "GET",
            "/api/recipes" to "GET", "/api/kb" to "GET", "/api/skills" to "GET",
            "/api/stats" to "GET", "/api/metrics" to "GET", "/api/diagnostics" to "GET",
            "/api/export" to "GET", "/api/memory/profile" to "GET",
        )
        for ((path, method) in probes) {
            val r = send(method, path, null)
            assertFalse("$path 出现在契约表里却返回 503 未实现：${r.body.take(120)}",
                r.code == 503 && r.body.contains("not_implemented"))
        }
        // 搜索与压缩：前端一直在调，曾经是 404/503 的黑洞
        assertEquals("搜索不应 404", 200, get("/api/sessions/search?q=x").code)
        val sid = newSession()
        assertEquals("压缩不应 404", 200,
            send("POST", "/api/sessions/$sid/compact", "{}").code)
        // 真正没实现的路径必须仍然 fail-closed，不能因为加了自检就放开
        assertEquals(503, get("/api/never-implemented").code)
    }

    // ---------- 凭据泄露防线（read_file 是 safe 级，永不弹审批） ----------

    @Test
    fun `read_file 不能读到 config_json 里的 API Key`() {
        bootstrap()
        configureLlm()
        val env = com.pocket.agent.tools.DeviceEnv(context, store, "")
        val read = com.pocket.agent.tools.ToolRegistry.byName("read_file")!!
        val list = com.pocket.agent.tools.ToolRegistry.byName("list_dir")!!

        // 工具用 ToolAbort 表达"拒绝"，与 AgentLoop 的捕获路径一致。
        fun call(tool: com.pocket.agent.tools.ToolDef, args: JSONObject): JSONObject =
            try {
                tool.handler(args, env)
            } catch (e: com.pocket.agent.tools.ToolAbort) {
                e.result
            }

        // 直接读 / 相对路径读 / 用 .. 归一后读：三种写法都必须被拒
        for (p in listOf("config.json", "./config.json", "sub/../config.json")) {
            val r = call(read, JSONObject().put("path", p))
            assertTrue("$p 必须被拒绝，实际：$r", r.has("error"))
            assertFalse("拒绝理由不能回显密钥：$r", r.toString().contains("sk-test-1234567890"))
        }

        // 列目录时也不能暴露这些文件名
        val names = (call(list, JSONObject().put("path", ".")).optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
        } ?: emptyList())
        assertFalse("list_dir 不应列出 config.json：$names", "config.json" in names)
        assertFalse("list_dir 不应列出 rules.json：$names", "rules.json" in names)

        // 写删同样受限：先覆盖再读的迂回路径也不行
        for (name in listOf("write_file", "delete_file")) {
            val tool = com.pocket.agent.tools.ToolRegistry.byName(name)!!
            val r = call(tool, JSONObject().put("path", "config.json").put("content", "{}"))
            assertTrue("$name 对 config.json 必须被拒：$r", r.has("error"))
        }
    }

    @Test
    fun `read_file 仍能读普通用户文件`() {
        bootstrap()
        val env = com.pocket.agent.tools.DeviceEnv(context, store, "")
        java.io.File(context.filesDir, "notes.txt").writeText("普通内容")
        val r = com.pocket.agent.tools.ToolRegistry.byName("read_file")!!
            .handler(JSONObject().put("path", "notes.txt"), env)
        assertEquals("普通文件必须照常可读", "普通内容", r.optString("content"))
        // 普通写入也必须照常工作
        val w = com.pocket.agent.tools.ToolRegistry.byName("write_file")!!
            .handler(JSONObject().put("path", "notes.txt").put("content", "改过了"), env)
        assertTrue("普通写入应成功：$w", w.optBoolean("ok"))
        assertEquals("改过了", java.io.File(context.filesDir, "notes.txt").readText())
    }

    // ---------- 上下文预算（1.6：工具结果不该把长会话撑爆） ----------

    @Test
    fun `工具结果超限时只裁剪进上下文 落库仍是全文`() {
        bootstrap()
        configureLlm(permissionMode = "auto")
        val sid = newSession()
        // 造一个 9000 字符的工具结果（超过未配置预算时的 6000 上限）
        val big = "x".repeat(9000)
        java.io.File(context.filesDir, "big.txt").writeText(big)
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(
            Triple("call-big", "read_file", "{\"path\":\"big.txt\"}")))
        )
        fakeLlm.turns.addLast(FakeTurn(text = "读完了"))
        send("POST", "/api/chat", JSONObject().put("session_id", sid).put("message", "读大文件").toString())

        // 送给模型的 tool 消息必须被截断
        val toolMsg = fakeLlm.lastMessages.firstOrNull { it.optString("role") == "tool" }
        assertTrue("应有一条 tool 消息", toolMsg != null)
        val sent = toolMsg!!.optString("content")
        assertTrue("进上下文的内容应被截断（实际 ${sent.length} 字）", sent.length < 9000)
        assertTrue("截断后应带说明", sent.contains("已截断"))

        // 但落库的全文必须完整（用户回看不能丢内容）
        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        val stored = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            .firstOrNull { it.getString("role") == "tool" }
        assertTrue("落库应保留全文（实际 ${stored!!.getString("content").length} 字）",
            stored.getString("content").contains(big))
    }

    @Test
    fun `配置预算后按比例收紧单条工具结果上限`() {
        bootstrap()
        configureLlm(permissionMode = "auto")
        // 预算 10000 token → 单条上限 = 10000*0.15*4 = 6000 字符
        settings.save(JSONObject().put("context", JSONObject().put("budget_tokens", 10000)))
        val sid = newSession()
        val big = "y".repeat(9000)
        java.io.File(context.filesDir, "big2.txt").writeText(big)
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(
            Triple("call-b2", "read_file", "{\"path\":\"big2.txt\"}")))
        )
        fakeLlm.turns.addLast(FakeTurn(text = "ok"))
        send("POST", "/api/chat", JSONObject().put("session_id", sid).put("message", "读").toString())
        val sent = fakeLlm.lastMessages.firstOrNull { it.optString("role") == "tool" }
            ?.optString("content") ?: ""
        assertTrue("截断后长度应接近 6000（实际 ${sent.length}）", sent.length in 5000..7000)
    }

    @Test
    fun `历史超预算时丢掉最旧的 但 system 与最后一条用户消息保留`() {
        bootstrap()
        // 预算设小一点，逼出裁剪
        settings.save(JSONObject().put("context", JSONObject().put("budget_tokens", 500)))
        val sid = newSession()
        // 20 条各 200 字的历史 → 远超 500 token
        for (i in 1..20) {
            store.addMessage(sid, if (i % 2 == 1) "user" else "assistant", "第${i}条".padEnd(200, 'z'))
        }
        fakeLlm.turns.addLast(FakeTurn(text = "收到"))
        send("POST", "/api/chat", JSONObject().put("session_id", sid).put("message", "最新一问").toString())

        val sentMsgs = fakeLlm.lastMessages
        assertEquals("首条必须是 system", "system", sentMsgs.first().optString("role"))
        assertTrue("最后一条必须是本轮用户消息",
            sentMsgs.last().optString("role") == "user" &&
                sentMsgs.last().optString("content") == "最新一问")
        assertTrue("应提示历史被省略",
            sentMsgs.any { it.optString("content").contains("超出上下文预算已省略") })
        assertTrue("消息条数应明显少于 21（实际 ${sentMsgs.size}）", sentMsgs.size < 15)
    }

    // ---------- 定时任务的无人审批策略（1.6） ----------

    @Test
    fun `定时任务遇需审批工具直接跳过 不等 120 秒`() {
        bootstrap()
        configureLlm(permissionMode = "approve")  // 默认就是逐次审批
        val sid = newSession()
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(
            Triple("call-h1", "write_file", "{\"path\":\"x.txt\",\"content\":\"y\"}")))
        )
        fakeLlm.turns.addLast(FakeTurn(text = "做完了"))

        val started = System.currentTimeMillis()
        // headless=true 等价于定时任务触发
        loop.chat(sid, "后台任务", com.pocket.agent.runtime.SseWriter(java.io.ByteArrayOutputStream()),
            headless = true)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("不应等待审批超时（耗时 ${elapsed}ms）", elapsed < 20_000)
        // 结果里应有明确的跳过理由，而不是笼统的"用户拒绝"
        val msgs = JSONArray(get("/api/sessions/$sid/messages").body)
        val toolMsg = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            .firstOrNull { it.getString("role") == "tool" }
        assertTrue("工具消息应落库", toolMsg != null)
        assertTrue("应说明是后台无审批人：${toolMsg!!.optString("content")}",
            toolMsg.optString("content").contains("定时任务在后台运行"))
        assertEquals("状态应为 denied", "denied",
            toolMsg.optJSONObject("meta")?.optString("status"))
    }

    // ---------- 删会话的级联清理（1.6） ----------

    @Test
    fun `删会话会清掉归档 任务 备份与审批白名单`() {
        bootstrap()
        val sid = newSession()
        store.addMessage(sid, "user", "hello")
        store.appendArchive(sid, "旧摘要")
        store.insertJob(JSONObject().put("name", "任务").put("trigger_type", "interval")
            .put("expr", "3600").put("message", "m").put("session_id", sid))

        // 造一条检查点记录 + 对应的备份文件
        val bak = java.io.File(context.filesDir, "checkpoints/bak.txt")
        bak.parentFile?.mkdirs(); bak.writeText("旧内容")
        store.recordCheckpoint(sid, "call-1", "write_file", "/some/path", bak.absolutePath, "overwrite")

        // 给该会话加一条「始终允许」
        approval.decide(sid, "nonexistent", "allow_once")
        // 直接调接口删
        assertEquals(200, send("DELETE", "/api/sessions/$sid", null).code)

        assertEquals("会话应删除", null, store.getSession(sid))
        assertEquals("归档应清空", 0, store.getArchives(sid, 10).length())
        assertEquals("该会话的任务应删除", 0,
            JSONObject(get("/api/jobs").body).getJSONArray("jobs").length())
        assertFalse("备份文件应被删盘", bak.exists())
    }

    // ---------- 审批审计（1.6：卖点是审批，就必须能追溯） ----------

    @Test
    fun `审批决策会写入审计日志 且不泄露命令内容`() {
        bootstrap()
        // 走一次真实的审批流：write 工具 → 等卡片 → 批准
        fakeLlm.turns.addLast(FakeTurn(calls = listOf(
            Triple("call-audit", "set_clipboard", "{\"text\":\"敏感内容SECRET\"}")))
        )
        fakeLlm.turns.addLast(FakeTurn(text = "完成"))
        val sid = newSession()
        val t = Thread {
            send("POST", "/api/chat", JSONObject().put("session_id", sid)
                .put("message", "复制").toString())
        }
        t.start()
        var pending = JSONArray()
        for (i in 0 until 80) {
            Thread.sleep(100)
            pending = JSONObject(get("/api/approvals/pending").body)
                .optJSONArray("pending") ?: JSONArray()
            if (pending.length() > 0) break
        }
        assertEquals(1, pending.length())
        send("POST", "/api/approval", JSONObject().put("session_id", sid)
            .put("tool_call_id", pending.getJSONObject(0).getString("tool_call_id"))
            .put("decision", "allow_once").toString())
        t.join(30000)

        val audit = JSONObject(get("/api/approvals/audit").body)
        assertTrue("应有审计记录：$audit", audit.getInt("count") >= 1)
        val rec = audit.getJSONArray("items").getJSONObject(0)
        assertEquals("set_clipboard", rec.getString("tool"))
        assertEquals("write", rec.getString("risk"))
        assertEquals("allow_once", rec.getString("decision"))
        assertTrue("应带时间戳", rec.getDouble("ts") > 0)
        // 关键：不能把工具参数（可能含剪贴板内容/手机号）写进审计
        assertFalse("审计不应包含工具参数内容：$rec",
            rec.toString().contains("敏感内容SECRET"))
        assertFalse("审计不应包含 API Key：$rec", rec.toString().contains("sk-test"))
    }

    @Test
    fun `超时未决也记审计 且审计接口受鉴权保护`() {
        bootstrap()
        // 直接构造一次超时：approval.awaitDecision 用最短 10 秒太久，改用 0 → 被收敛到 10
        // 这里改为验证「未认证拿不到审计」，以及审计文件路径不在 read_file 白名单内
        val noAuth = java.net.URL("http://127.0.0.1:${engine.port}/api/approvals/audit")
            .openConnection() as java.net.HttpURLConnection
        assertEquals("审计接口必须鉴权", 401, noAuth.responseCode)
        noAuth.disconnect()

        val env = com.pocket.agent.tools.DeviceEnv(context, store, "")
        val tool = com.pocket.agent.tools.ToolRegistry.byName("read_file")!!
        val r = try {
            tool.handler(JSONObject().put("path", "audit/approvals.jsonl"), env)
        } catch (e: com.pocket.agent.tools.ToolAbort) {
            e.result
        }
        assertTrue("审计日志不应能被 read_file 读走：$r", r.has("error"))
    }
}
