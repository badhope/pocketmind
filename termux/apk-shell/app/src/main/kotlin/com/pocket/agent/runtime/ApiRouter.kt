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
    /** 定时任务调度器；单测里可传一个不碰 AlarmManager 的实例。 */
    private val scheduler: NativeScheduler = NativeScheduler(context, store, agent),
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
            // 必须排在 startsWith("/api/sessions/") 之前，否则会被当成会话 id="search"。
            path == "/api/sessions/search" && ex.method == "GET" -> searchSessions(ex)
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
            path == "/api/undo" && ex.method == "POST" -> {
                val body = bodyJson(ex)
                val sid = body.optString("session_id")
                val tid = body.optString("tool_call_id")
                if (sid.isEmpty() || tid.isEmpty()) {
                    return ex.respond(400, JSON, "{\"error\":\"需要 session_id 与 tool_call_id\"}")
                }
                // 语义与 Python 一致：业务失败用 200 + error 字段，前端按 error 展示。
                ex.respond(200, JSON, agent.undo(sid, tid).toString())
            }
            path == "/api/jobs" && ex.method == "GET" ->
                ex.respond(200, JSON, JSONObject().put("jobs", scheduler.listJson()).toString())
            path == "/api/jobs" && ex.method == "POST" -> createJob(ex)
            path == "/api/recipes" && ex.method == "GET" ->
                ex.respond(200, JSON, JSONObject().put("recipes", Recipes.list(store)).toString())
            path.startsWith("/api/recipes/") && path.endsWith("/apply") && ex.method == "POST" ->
                applyRecipe(ex, path.removePrefix("/api/recipes/").removeSuffix("/apply"))
            path == "/api/memory/profile" && ex.method == "GET" -> profileGet(ex)
            path == "/api/memory/profile" && ex.method == "PUT" -> profilePut(ex)
            path.startsWith("/api/jobs/") -> jobDetail(ex, path)
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
            parts.getOrNull(1) == "compact" && ex.method == "POST" -> compactSession(ex, sid)
            else -> ex.respond(404, JSON, "{\"error\":\"not_found\"}")
        }
    }

    private fun searchSessions(ex: Exchange) {
        val q = (ex.query["q"] ?: "").trim()
        val limit = (ex.query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        if (q.isEmpty()) return ex.respond(200, JSON, "{\"query\":\"\",\"count\":0,\"items\":[]}")
        val items = store.searchMessages(q, limit)
        ex.respond(200, JSON, JSONObject()
            .put("query", q).put("count", items.length()).put("items", items).toString())
    }

    /**
     * 手动压缩会话：把较早的历史归档成摘要，保留最近 keep 条原文。
     *
     * 与 Python 一样，LLM 不可用时降级为「最后一条用户消息」占位摘要——
     * 压缩的价值是别丢上下文，为此让整个操作失败没有意义。
     * 水位（archived_upto）只在摘要真正落库后推进，失败不丢历史。
     */
    private fun compactSession(ex: Exchange, sid: String) {
        if (store.getSession(sid) == null) return ex.respond(404, JSON, "{\"error\":\"会话不存在\"}")
        val keep = bodyJson(ex).optInt("keep", 10)
        ex.respond(200, JSON, agent.compact(sid, keep).toString())
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
        val shell = try { ToolRegistry.byName("run_shell") != null } catch (_: Exception) { false }
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
                    // 原生 shell 走 /system/bin/sh，身份是 app uid（非 root、非 Termux）
                    .put("shell", if (shell) "restricted" else "unavailable")
                    .put("scheduler", "ok")
                    .put("scheduler_exact", if (scheduler.canScheduleExact()) "ok" else "inexact")
                    .put("undo", "ok"))
                .put("note", "shell 以 app 身份运行 /system/bin/sh（无 root、无 pkg/apt）；" +
                    "定时任务用 AlarmManager，未授予精确闹钟权限时可能延迟数分钟")
                .toString()
        )
    }

    // ---------- 定时任务 ----------

    private fun createJob(ex: Exchange) {
        val body = bodyJson(ex)
        val name = body.optString("name").trim().take(60)
        val tt = body.optString("trigger_type").trim()
        val expr = body.optString("expr").trim()
        val message = body.optString("message").trim()
        if (name.isEmpty() || tt !in setOf("cron", "interval", "date") || expr.isEmpty() || message.isEmpty()) {
            return ex.respond(400, JSON,
                "{\"error\":\"缺少必要字段：name / trigger_type(cron|interval|date) / expr / message\"}")
        }
        val validation = validateExpr(tt, expr)
        if (validation != null) return ex.respond(400, JSON, JSONObject().put("error", validation).toString())
        val cond = body.optString("condition").trim()
        if (cond.isNotEmpty() && !Regex("^\\s*battery\\s*(<=|>=|==|<|>)\\s*[0-9.]+\\s*$", RegexOption.IGNORE_CASE).matches(cond)) {
            return ex.respond(400, JSON, "{\"error\":\"条件格式：battery < 20（仅支持电池电量阈值）\"}")
        }
        val job = store.insertJob(JSONObject()
            .put("name", name).put("trigger_type", tt).put("expr", expr).put("message", message)
            .put("session_id", body.optString("session_id")).put("condition", cond)
            .put("enabled", body.optBoolean("enabled", true)))
        scheduler.armJob(job.optString("id"))
        ex.respond(200, JSON, withNextRun(job).toString())
    }

    /** `/api/jobs/{id}` 的 PUT / DELETE，以及 `/api/jobs/{id}/run`。 */
    private fun jobDetail(ex: Exchange, path: String) {
        val rest = path.removePrefix("/api/jobs/")
        val id = rest.substringBefore("/")
        if (id.isEmpty()) return ex.respond(404, JSON, "{\"error\":\"任务不存在\"}")
        val runNow = rest.endsWith("/run")
        val existing = store.getJob(id) ?: return ex.respond(404, JSON, "{\"error\":\"任务不存在\"}")
        when {
            runNow && ex.method == "POST" -> {
                scheduler.runNow(existing)
                ex.respond(200, JSON, JSONObject().put("ok", true).put("job_id", id).toString())
            }
            ex.method == "PUT" -> {
                val patch = bodyJson(ex)
                if (patch.length() == 0) return ex.respond(400, JSON, "{\"error\":\"请求体不能为空\"}")
                val tt = if (patch.has("trigger_type")) patch.optString("trigger_type") else existing.optString("trigger_type")
                val expr = if (patch.has("expr")) patch.optString("expr") else existing.optString("expr")
                if (patch.has("expr") || patch.has("trigger_type")) {
                    val v = validateExpr(tt, expr)
                    if (v != null) return ex.respond(400, JSON, JSONObject().put("error", v).toString())
                }
                val updated = store.updateJob(id, patch) ?: return ex.respond(404, JSON, "{\"error\":\"任务不存在\"}")
                if (updated.optBoolean("enabled")) scheduler.armJob(id) else scheduler.cancel(id)
                ex.respond(200, JSON, withNextRun(updated).toString())
            }
            ex.method == "DELETE" -> {
                scheduler.cancel(id)
                ex.respond(200, JSON, JSONObject().put("ok", store.deleteJob(id)).toString())
            }
            else -> ex.respond(404, JSON, "{\"error\":\"not_found\"}")
        }
    }

    private fun applyRecipe(ex: Exchange, recipeId: String) {
        val recipe = Recipes.get(recipeId)
            ?: return ex.respond(404, JSON, "{\"error\":\"配方不存在\"}")
        val job = store.insertJob(JSONObject()
            .put("name", recipe.optString("name"))
            .put("trigger_type", recipe.optString("trigger_type"))
            .put("expr", recipe.optString("expr"))
            .put("message", recipe.optString("message"))
            .put("condition", recipe.optString("condition"))
            .put("enabled", recipe.optBoolean("enabled", true)))
        scheduler.armJob(job.optString("id"))
        ex.respond(200, JSON, withNextRun(job).toString())
    }

    private fun withNextRun(job: JSONObject): JSONObject {
        val out = JSONObject(job.toString())
        val next = if (job.optBoolean("enabled")) scheduler.nextRunAt(job) else null
        out.put("next_run", if (next != null) {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(next))
        } else JSONObject.NULL)
        return out
    }

    /** 表达式校验：与 Python routes.validate_job_payload 的规则保持一致。 */
    private fun validateExpr(tt: String, expr: String): String? = when (tt) {
        "interval" -> {
            val v = expr.toDoubleOrNull()
            if (v == null || v.isNaN() || v.isInfinite() || v <= 0 || v > 365.0 * 86400) {
                "interval 表达式需为 1~31536000 之间的秒数（如 3600）"
            } else null
        }
        "date" -> {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            fmt.isLenient = false
            try { fmt.parse(expr); null } catch (_: Exception) { "date 表达式格式：YYYY-MM-DD HH:MM" }
        }
        "cron" -> if (CronExpr.parse(expr) == null) "cron 表达式无法解析（需 5 字段：分 时 日 月 周）" else null
        else -> "trigger_type 必须是 cron / interval / date"
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
