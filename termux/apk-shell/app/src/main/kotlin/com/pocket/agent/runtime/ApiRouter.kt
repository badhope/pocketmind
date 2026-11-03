package com.pocket.agent.runtime

import android.content.Context
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.agent.ApprovalCenter
import com.pocket.agent.data.SettingsStore
import com.pocket.agent.data.Store
import com.pocket.agent.llm.LlmClient
import com.pocket.agent.tools.DeviceEnv
import com.pocket.agent.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 业务路由：把 Python agentd 的 REST/SSE 契约逐端点搬到原生引擎。
 *
 * 范围声明（写入 /api/diagnostics，不伪迁移）：
 *  - 已实现：健康/设置/会话/消息/聊天 SSE/审批/工具/知识库/导出导入/统计/诊断；
 *  - 显式降级：jobs/recipes（原生引擎 v1 无调度器，返回空集）、
 *    undo（原生文件工具不做 checkpoint）、skills（v1 无技能目录）。
 */
class ApiRouter(
    private val context: Context,
    val store: Store,
    val settings: SettingsStore,
    val approval: ApprovalCenter,
    val agent: AgentLoop,
    private val engine: () -> NativeEngine?,
) : NativeEngine.Handler {

    private val startedAt = System.currentTimeMillis()
    @Volatile private var requestCount = 0

    override fun handle(ex: Exchange) {
        requestCount += 1
        val path = ex.path
        try {
            when {
                ex.method == "GET" && path == "/" -> serveDocument(ex)
                ex.method == "GET" && path.startsWith("/assets/") -> serveAsset(ex)
                ex.method == "GET" && path == "/favicon.ico" ->
                    ex.respond(404, "application/json", "{\"error\":\"not_found\"}")
                path == "/api" || path.startsWith("/api/") -> routeApi(ex, path)
                else -> ex.respond(404, "application/json", "{\"error\":\"not_found\"}")
            }
        } catch (e: Exception) {
            ex.respond(500, "application/json", "{\"error\":\"internal_error\",\"message\":\"${esc(e.message ?: "")}\"}")
        }
    }

    // ---------- 主文档与静态资源 ----------

    private fun serveDocument(ex: Exchange) {
        val engine = engine() ?: return ex.respond(503, "application/json", "{\"error\":\"engine_down\"}")
        val nonce = ex.query["navigation"]
        val hasLiveCookie = ex.headers["cookie"]?.contains(NativeEngine.SESSION_COOKIE) == true
        val authorized = engine.consumeGrant(nonce) || hasLiveCookie
        // 首次加载必须持一次性 nonce；已带活 cookie 的 JS reload 免 nonce（避免 403 死角），
        // 无 nonce 且无会话一律 403 —— 不给同机其他进程任何探测面。
        if (!authorized) {
            ex.respond(403, "application/json", "{\"error\":\"forbidden\"}")
            return
        }
        val html = readAsset("web/index.html")
            ?: return ex.respond(500, "application/json", "{\"error\":\"dist_missing\"}")
        val cookie = engine.issueSessionCookie()
        ex.respond(200, "text/html; charset=utf-8", html,
            mapOf("Set-Cookie" to engine.cookieHeaderFor(cookie)))
    }

    private fun serveAsset(ex: Exchange) {
        val rel = ex.path.removePrefix("/")
        if (rel.contains("..")) return ex.respond(404, "application/json", "{\"error\":\"not_found\"}")
        val body = readAsset("web/$rel")
            ?: return ex.respond(404, "application/json", "{\"error\":\"not_found\"}")
        ex.respond(200, mimeOf(rel), body, mapOf("Cache-Control" to "public, max-age=86400"))
    }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "text/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".json") || path.endsWith(".map") -> "application/json"
        else -> "application/octet-stream"
    }

    // ---------- /api/* ----------

    private fun routeApi(ex: Exchange, path: String) {
        when {
            path == "/api/health" && ex.method == "GET" -> health(ex)
            path == "/api/native/status" && ex.method == "GET" -> status(ex)
            path == "/api/settings" && ex.method == "GET" ->
                ex.respond(200, JSON, settings.snapshot().toString())
            path == "/api/settings" && ex.method == "PUT" ->
                ex.respond(200, JSON, settings.save(bodyJson(ex)).toString())
            path == "/api/providers" && ex.method == "GET" ->
                ex.respond(200, JSON, "{\"providers\":${SettingsStore.PROVIDERS.trimIndent()}}")
            path == "/api/llm/test" && ex.method == "POST" -> llmTest(ex)
            path == "/api/sessions" && ex.method == "GET" ->
                ex.respond(200, JSON, store.listSessions().toString())
            path == "/api/sessions" && ex.method == "POST" -> {
                val body = bodyJson(ex)
                val s = store.createSession(body.optString("title", "新会话").ifEmpty { "新会话" })
                ex.respond(201, JSON, JSONObject().put("session_id", s.optString("id")).put("session", s).toString())
            }
            path.startsWith("/api/sessions/") -> sessions(ex, path)
            path == "/api/chat" && ex.method == "POST" -> chat(ex)
            path == "/api/stop" && ex.method == "POST" -> {
                val sid = bodyJson(ex).optString("session_id")
                ex.respond(200, JSON, JSONObject().put("ok", agent.cancelRun(sid)).toString())
            }
            path == "/api/approval" && ex.method == "POST" -> postApproval(ex)
            path == "/api/approvals/pending" && ex.method == "GET" -> pendingApprovals(ex)
            path == "/api/approvals" && ex.method == "GET" ->
                ex.respond(200, JSON, approval.listRules().toString())
            path == "/api/approvals" && ex.method == "DELETE" -> {
                // scope 参数：deny / global / session:<id>；tool 空串清整组
                val scope = ex.query["scope"] ?: ""
                val tool = ex.query["tool"] ?: ""
                clearRules(scope, tool)
                ex.respond(200, JSON, JSONObject().put("ok", true).put("rules", approval.listRules()).toString())
            }
            path == "/api/tools" && ex.method == "GET" ->
                ex.respond(200, JSON, JSONObject().put("tools", ToolRegistry.listJson()).toString())
            path == "/api/diagnostics" && ex.method == "GET" -> diagnostics(ex)
            path == "/api/kb" && ex.method == "GET" ->
                ex.respond(200, JSON, JSONObject().put("items", store.kbList()).toString())
            path.startsWith("/api/kb/") && ex.method == "DELETE" -> {
                val id = path.removePrefix("/api/kb/").toLongOrNull()
                    ?: return ex.respond(400, JSON, "{\"error\":\"id 必须是整数\"}")
                ex.respond(200, JSON, JSONObject().put("ok", store.kbDelete(id)).put("id", id).toString())
            }
            path == "/api/skills" && ex.method == "GET" ->
                ex.respond(200, JSON, "{\"skills\":[],\"dir\":\"\"}")
            path == "/api/stats" && ex.method == "GET" -> stats(ex)
            path == "/api/metrics" && ex.method == "GET" ->
                ex.respond(200, "text/plain; charset=utf-8", metricsText())
            path == "/api/export" && ex.method == "GET" ->
                ex.respond(200, JSON, store.exportAll().toString())
            path == "/api/import" && ex.method == "POST" -> import(ex)
            path == "/api/undo" && ex.method == "POST" ->
                ex.respond(503, JSON, "{\"error\":\"undo 在原生引擎上不可用\",\"error_code\":\"capability_unavailable\"}")
            path == "/api/jobs" && ex.method == "GET" ->
                ex.respond(200, JSON, "{\"jobs\":[],\"note\":\"原生引擎 v1 未内置定时调度\"}")
            path == "/api/recipes" && ex.method == "GET" ->
                ex.respond(200, JSON, "{\"recipes\":[]}")
            path.startsWith("/api/recipes/") ->
                ex.respond(503, JSON, "{\"error\":\"配方在原生引擎上暂不可用\",\"error_code\":\"capability_unavailable\"}")
            path == "/api/memory/profile" && ex.method == "GET" -> profileGet(ex)
            path == "/api/memory/profile" && ex.method == "PUT" -> profilePut(ex)
            path.startsWith("/api/jobs/") ->
                ex.respond(503, JSON, "{\"error\":\"定时任务在原生引擎 v1 上暂不可用\",\"error_code\":\"capability_unavailable\"}")
            else -> // fail-closed：契约里登记过但没实现的 /api 路径，绝不 200
                ex.respond(503, JSON, "{\"error\":\"unavailable\",\"migration\":\"not_implemented\"}")
        }
    }

    private fun sessions(ex: Exchange, path: String) {
        val parts = path.removePrefix("/api/sessions/").split("/")
        val sid = parts.getOrNull(0) ?: ""
        when {
            parts.size == 1 && ex.method == "PUT" -> {
                val body = bodyJson(ex)
                val ok = store.updateSession(sid,
                    body.optString("title", "").ifEmpty { null },
                    if (body.has("pinned")) body.optBoolean("pinned") else null)
                if (ok) ex.respond(200, JSON, JSONObject().put("ok", true)
                    .put("title", store.getSession(sid)?.optString("title") ?: "").toString())
                else ex.respond(404, JSON, "{\"error\":\"会话不存在\"}")
            }
            parts.size == 1 && ex.method == "DELETE" -> {
                if (store.deleteSession(sid)) ex.respond(200, JSON, "{\"ok\":true}")
                else ex.respond(404, JSON, "{\"error\":\"会话不存在\"}")
            }
            parts.getOrNull(1) == "messages" && ex.method == "GET" -> {
                if (store.getSession(sid) == null) return ex.respond(404, JSON, "{\"error\":\"会话不存在\"}")
                ex.respond(200, JSON, store.getMessages(sid).toString())
            }
            parts.getOrNull(1) == "messages" && ex.method == "DELETE" && parts.size == 3 -> {
                val mid = parts[2].toLongOrNull()
                if (mid == null) {
                    ex.respond(400, JSON, "{\"error\":\"消息 id 必须是整数\"}")
                } else {
                    val n = store.truncateFrom(sid, mid)
                    ex.respond(200, JSON, JSONObject().put("removed", n).toString())
                }
            }
            parts.getOrNull(1) == "todos" && ex.method == "GET" ->
                ex.respond(200, JSON, JSONObject().put("todos", store.getTodos(sid)).toString())
            parts.getOrNull(1) == "todos" && ex.method == "DELETE" -> {
                store.clearTodos(sid)
                ex.respond(200, JSON, "{\"ok\":true}")
            }
            else -> ex.respond(404, JSON, "{\"error\":\"not_found\"}")
        }
    }

    private fun health(ex: Exchange) {
        val ready = settings.llmReady()
        ex.respond(
            200, JSON,
            JSONObject()
                .put("ok", true)
                .put("ready", ready)
                .put("mock", false)
                .put("mode", "native")
                .put("components", JSONObject()
                    .put("db", "ok").put("engine", "native")
                    .put("agent", if (ready) "ok" else "unconfigured"))
                .toString()
        )
    }

    private fun status(ex: Exchange) {
        ex.respond(
            200, JSON,
            JSONObject()
                .put("engine", "running")
                .put("ready", settings.llmReady())
                .put("document", "cookie-session")
                .put("tools", ToolRegistry.all().size)
                .toString()
        )
    }

    private fun llmTest(ex: Exchange) {
        if (!settings.llmReady()) {
            val missing = settings.llmMissing().joinToString("、")
            return ex.respond(200, JSON,
                JSONObject().put("ok", false).put("error", "尚未配置模型（缺少$missing）").toString())
        }
        val client = LlmClient(settings.llmConfig())
        val msgs = JSONArray().put(
            JSONObject().put("role", "user").put("content", "ping，只回复 pong")
        )
        try {
            val reply = client.complete(msgs, maxTokens = 10)
            ex.respond(200, JSON,
                JSONObject().put("ok", true)
                    .put("model", settings.llmConfig().model)
                    .put("reply", reply.take(50)).toString())
        } catch (e: Exception) {
            ex.respond(200, JSON,
                JSONObject().put("ok", false).put("error", e.message ?: "连接失败").toString())
        }
    }

    private fun chat(ex: Exchange) {
        val body = bodyJson(ex)
        val message = body.optString("message")
        if (message.isBlank()) return ex.respond(400, JSON, "{\"error\":\"消息不能为空\"}")
        if (message.length > 100_000) return ex.respond(400, JSON, "{\"error\":\"消息过长（最多 10 万字符）\"}")
        var sid = body.optString("session_id")
        if (sid.isBlank()) {
            sid = store.createSession().optString("id")
        } else if (store.getSession(sid) == null) {
            return ex.respond(404, JSON, "{\"error\":\"会话不存在\"}")
        }
        val sse = ex.startSse()
        try {
            agent.chat(sid, message, sse)
        } finally {
            sse.close()
        }
    }

    private fun postApproval(ex: Exchange) {
        val body = bodyJson(ex)
        val sid = body.optString("session_id")
        val tid = body.optString("tool_call_id")
        val decision = body.optString("decision")
        if (decision !in setOf("allow_once", "allow_always", "allow_global", "deny", "deny_always")) {
            return ex.respond(400, JSON, "{\"error\":\"decision 必须是 allow_once / allow_always / allow_global / deny / deny_always\"}")
        }
        val ok = approval.decide(sid, tid, decision)
        if (!ok) return ex.respond(404, JSON, "{\"error\":\"该审批已过期或不存在\"}")
        ex.respond(200, JSON, JSONObject().put("ok", true).put("rules", approval.listRules()).toString())
    }

    private fun pendingApprovals(ex: Exchange) {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        approval.pendingList().forEach { p ->
            val remaining = ((p.deadlineAt - now) / 1000).toInt().coerceAtLeast(0)
            arr.put(p.json(remaining))
        }
        ex.respond(200, JSON, JSONObject().put("pending", arr).toString())
    }

    private fun clearRules(scope: String, tool: String) {
        when {
            scope == "deny" -> approval.replaceRules(deny = emptyList())
            scope == "global" -> approval.replaceRules(globalAllow = emptyList())
            scope.startsWith("session:") -> approval.replaceRules(sessionAllow = emptyMap())
            else -> {}
        }
        // v1：单工具清理退化为整组清空（不产生错误结果；精确清理进 v1.1）
    }

    private fun diagnostics(ex: Exchange) {
        val env = DeviceEnv(context, store, "")
        val battery = try { ToolRegistry.byName("get_battery")!!.handler(JSONObject(), env).has("error").not() } catch (_: Exception) { false }
        ex.respond(
            200, JSON,
            JSONObject()
                .put("ok", true)
                .put("mode", "native")
                .put("tools", ToolRegistry.all().size)
                .put("capabilities", JSONObject()
                    .put("chat", "ok")
                    .put("tools", "ok")
                    .put("battery", if (battery) "ok" else "unavailable")
                    .put("shell", "unavailable")
                    .put("scheduler", "unavailable"))
                .put("note", "run_shell/定时任务在原生引擎上不可用；需要时安装 Termux 版")
                .toString()
        )
    }

    private fun stats(ex: Exchange) {
        ex.respond(
            200, JSON,
            JSONObject()
                .put("use_litellm", false)
                .put("use_mem0", false)
                .put("metrics", JSONObject()
                    .put("uptime_seconds", (System.currentTimeMillis() - startedAt) / 1000)
                    .put("requests", JSONObject()
                        .put("total", requestCount)
                        .put("by_status", JSONObject()))
                    .put("tools", JSONObject())
                    .put("llm", agent.statsJson().optJSONObject("llm") ?: JSONObject())
                    .put("errors", JSONObject()))
                .toString()
        )
    }

    private fun metricsText(): String {
        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        return buildString {
            append("# TYPE pocketmind_uptime_seconds gauge\n")
            append("pocketmind_uptime_seconds $uptime\n")
            append("# TYPE pocketmind_requests_total counter\n")
            append("pocketmind_requests_total $requestCount\n")
            append("# TYPE pocketmind_llm_tokens_total counter\n")
            val llm = agent.statsJson().optJSONObject("llm") ?: JSONObject()
            val promptTokens = llm.optInt("prompt_tokens")
            val completionTokens = llm.optInt("completion_tokens")
            append("pocketmind_llm_tokens_total{type=\"prompt\"} ")
            append(promptTokens).append('\n')
            append("pocketmind_llm_tokens_total{type=\"completion\"} ")
            append(completionTokens).append('\n')
        }
    }

    private fun import(ex: Exchange) {
        val payload = bodyJson(ex)
        val fmt = payload.optInt("format_version", 1)
        if (fmt != 1) {
            return ex.respond(400, JSON,
                JSONObject().put("detail", "备份格式版本过新（$fmt > 1），请先升级本机应用").toString())
        }
        ex.respond(200, JSON, store.importAll(payload).toString())
    }

    private fun profileGet(ex: Exchange) {
        val facts = store.listFacts()
        val sb = StringBuilder()
        for (i in 0 until facts.length()) sb.append("- ").append(facts.optJSONObject(i)?.optString("content")).append("\n")
        ex.respond(200, JSON,
            JSONObject().put("enabled", true).put("user", "").put("facts", sb.toString())
                .put("journal_lines", facts.length()).toString())
    }

    private fun profilePut(ex: Exchange) {
        val body = bodyJson(ex)
        val fact = body.optString("fact")
        if (fact.isNotEmpty()) store.addFact(fact)
        ex.respond(200, JSON,
            JSONObject().put("ok", true).put("user", body.optString("user")).put("facts", fact).toString())
    }

    // ---------- helpers ----------

    private fun bodyJson(ex: Exchange): JSONObject =
        if (ex.body.isEmpty()) JSONObject()
        else try { JSONObject(ex.bodyText()) } catch (_: Exception) { JSONObject() }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        private const val JSON = "application/json"
    }
}
