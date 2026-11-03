package com.pocket.agent.agent

import com.pocket.agent.data.SettingsStore
import com.pocket.agent.data.Store
import com.pocket.agent.llm.LlmClient
import com.pocket.agent.runtime.SseWriter
import com.pocket.agent.tools.DeviceEnv
import com.pocket.agent.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 主循环（Kotlin 版）：与 Python agent.py 的对外行为逐事件对齐。
 *
 * 事件序列（前端 useAgentStore / partReducer 的契约）：
 *   session → (text | thinking)* → (tool_start → approval? → tool_update)* → done | error
 *
 * 审批语义（与 _decide_tool 一致）：
 *   chat 模式一律拒绝；approve 模式下 write/danger 需确认；
 *   全局 allow / deny 命中不弹卡；未知工具按 danger 处理。
 */
class AgentLoop(
    private val store: Store,
    private val settings: SettingsStore,
    private val approval: ApprovalCenter,
    /** LLM 客户端工厂：生产环境走真端点；测试注入脚本化假客户端（不碰网络）。 */
    val llmFactory: (SettingsStore.LlmConfig) -> LlmClient = { LlmClient(it) },
) {
    /** 每会话进行中的循环：用于排队与 /api/stop。 */
    class Run(val pending: ArrayDeque<String> = ArrayDeque()) {
        @Volatile var cancelled = false
        @Volatile var awaitingApproval: PendingApproval? = null
    }

    class PendingApproval(
        val toolCallId: String,
        val name: String,
        val summary: String,
        val risk: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    val runs = ConcurrentHashMap<String, Run>()
    /** plan 模式产出的待执行计划：批准后同文本重发即以 act 模式执行一次。 */
    private val pendingPlans = ConcurrentHashMap<String, String>()

    private var totalPrompt = 0
    private var totalCompletion = 0
    private var llmCalls = 0

    fun isRunning(sessionId: String): Boolean = runs.containsKey(sessionId)

    fun cancelRun(sessionId: String): Boolean {
        val r = runs[sessionId] ?: return false
        r.cancelled = true
        return true
    }

    fun pendingFor(sessionId: String): List<PendingApproval> =
        runs.values.flatMap { listOfNotNull(it.awaitingApproval) }

    fun statsJson(): JSONObject =
        JSONObject()
            .put("llm", JSONObject()
                .put("calls", llmCalls)
                .put("prompt_tokens", totalPrompt)
                .put("completion_tokens", totalCompletion)
                .put("total_tokens", totalPrompt + totalCompletion))

    /**
     * 处理一条用户消息（阻塞，占用连接线程直到 done/error）。
     * emit 直接写 SSE；审批等待期间流保持打开（帧不发，连接不断）。
     */
    fun chat(sessionId: String, message: String, sse: SseWriter) {
        sse.send(JSONObject().put("type", "session").put("session_id", sessionId).toString())
        val run = Run()
        val existing = runs.putIfAbsent(sessionId, run)
        if (existing != null) {
            existing.pending.addLast(message)
            sse.send(JSONObject().put("type", "queued")
                .put("message", "上一轮回复还在进行中，这条已排队，完成后自动执行").toString())
            sse.send(JSONObject().put("type", "done").put("stop_reason", "queued").toString())
            return
        }
        try {
            var current = message
            do {
                runOne(sessionId, current, run, sse)
                current = ""
                synchronized(run.pending) {
                    if (run.pending.isNotEmpty()) {
                        current = run.pending.removeFirst()
                        // 续跑的消息补发 queued 语义的会话帧，前端重置占位
                        sse.send(JSONObject().put("type", "session").put("session_id", sessionId).toString())
                    }
                }
            } while (current.isNotEmpty() && !run.cancelled)
        } finally {
            runs.remove(sessionId)
        }
    }

    private fun runOne(sessionId: String, message: String, run: Run, sse: SseWriter) {
        val llmReady = settings.llmReady()
        if (!llmReady) {
            val missing = settings.llmMissing().joinToString("、")
            store.addMessage(sessionId, "user", message)
            val note = "（未能执行：尚未配置模型，缺少$missing。请打开「设置 → 模型」填写后重试。）"
            store.addMessage(sessionId, "assistant", note)
            sse.send(JSONObject().put("type", "error")
                .put("message", "尚未配置模型（缺少$missing）：请打开「设置 → 模型」填写后重试").toString())
            sse.send(JSONObject().put("type", "done").put("stop_reason", "unconfigured").toString())
            return
        }

        // 新会话自动命名（失败不阻塞）
        if (store.getMessages(sessionId, 400).length() == 0) {
            try {
                val title = titleFor(message)
                store.touchSession(sessionId, title)
            } catch (_: Exception) {
            }
        }
        store.addMessage(sessionId, "user", message)

        val cfg = settings.llmConfig()
        val client = llmFactory(cfg)
        val permissionMode = settings.permissionMode()
        // plan 模式批准重发：同会话同文本 → 本次以 act 执行
        val planApproved = pendingPlans.remove(sessionId) == message
        val planMode = settings.agentMode() == "plan" && !planApproved

        // 组装上下文：system + 最近历史
        val history = store.getMessages(sessionId, 40)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(permissionMode)))
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            when (val role = m.optString("role")) {
                "user" -> messages.put(JSONObject().put("role", "user").put("content", m.optString("content")))
                "assistant" -> {
                    val a = JSONObject().put("role", "assistant").put("content", m.optString("content"))
                    val tcs = m.optJSONObject("meta")?.optJSONArray("tool_calls")
                    if (tcs != null && tcs.length() > 0) {
                        // 存储格式 {id,name,args} → OpenAI wire 格式
                        val wire = JSONArray()
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            wire.put(
                                JSONObject()
                                    .put("id", tc.optString("id"))
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", tc.optString("name"))
                                        .put("arguments", tc.optString("args", "{}")))
                            )
                        }
                        a.put("tool_calls", wire)
                    }
                    messages.put(a)
                }
                "tool" -> messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.optJSONObject("meta")?.optString("tool_call_id") ?: "")
                        .put("content", m.optString("content"))
                )
                else -> {}
            }
        }

        val tools = if (permissionMode == "chat") null else ToolRegistry.schemaJson()
        var depth = 0
        try {
            while (depth < MAX_DEPTH && !run.cancelled) {
                depth += 1
                val acc = LlmClient.ToolCallAccumulator()
                val textBuf = StringBuilder()
                var usage: LlmClient.Usage? = null
                val calls = client.streamChat(messages, tools, callbacks = object : LlmClient.StreamCallbacks {
                    override fun onText(delta: String) {
                        textBuf.append(delta)
                        sse.send(JSONObject().put("type", "text").put("text", delta).toString())
                    }

                    override fun onReasoning(delta: String) {
                        sse.send(JSONObject().put("type", "thinking").put("text", delta).toString())
                    }

                    override fun onDone(u: LlmClient.Usage?, tcs: List<Triple<String, String, String>>) {
                        usage = u
                        tcs.forEach { acc.ids[acc.parts.size] = it.first; acc.names[acc.parts.size] = it.second; acc.parts[acc.parts.size] = StringBuilder(it.third) }
                    }
                }, abortSignal = { run.cancelled })

                if (run.cancelled) {
                    sse.send(JSONObject().put("type", "done").put("stop_reason", "cancelled").toString())
                    return
                }

                val assistantMeta = JSONObject()
                if (usage != null) {
                    assistantMeta.put("usage", usage!!.json())
                    totalPrompt += usage!!.promptTokens
                    totalCompletion += usage!!.completionTokens
                }
                llmCalls += 1

                if (calls.isEmpty()) {
                    val content = textBuf.toString()
                    store.addMessage(sessionId, "assistant", content, assistantMeta)
                    sse.send(JSONObject().put("type", "done")
                        .put("stop_reason", "end_turn")
                        .put("usage", (usage ?: LlmClient.Usage(0, 0, true).also {
                            // 端点未回报 usage：按字数粗估，标 estimated
                            totalCompletion += content.length / 3
                        }).json()).toString())
                    return
                }

                // 记录 assistant（含 tool_calls，存储契约 {id,name,args}）
                val wireCalls = JSONArray()
                for ((id, name, args) in calls) {
                    wireCalls.put(JSONObject().put("id", id).put("name", name).put("args", args))
                }
                assistantMeta.put("tool_calls", wireCalls)
                store.addMessage(sessionId, "assistant", textBuf.toString(), assistantMeta)

                // Plan 模式：只预告不执行
                if (planMode && depth == 1) {
                    val planned = JSONArray()
                    for ((_, name, args) in calls) {
                        planned.put(JSONObject().put("name", name)
                            .put("arguments", runCatching { JSONObject(args) }.getOrDefault(JSONObject())))
                    }
                    sse.send(JSONObject().put("type", "plan")
                        .put("plan", textBuf.toString().ifEmpty { "我计划执行以下步骤：" })
                        .put("tool_calls", planned).toString())
                    pendingPlans[sessionId] = message
                    sse.send(JSONObject().put("type", "done").put("stop_reason", "plan").toString())
                    return
                }

                // OpenAI wire 格式回填进上下文（下一轮模型需要看到自己发起的调用）
                val wireForContext = JSONArray()
                for ((id, name, args) in calls) {
                    wireForContext.put(
                        JSONObject().put("id", id).put("type", "function")
                            .put("function", JSONObject().put("name", name).put("arguments", args))
                    )
                }
                messages.put(JSONObject().put("role", "assistant")
                    .put("content", textBuf.toString()).put("tool_calls", wireForContext))

                val env = DeviceEnv(appContext(), store, sessionId)
                for ((tid, name, argsStr) in calls) {
                    if (run.cancelled) break
                    runOneTool(sessionId, run, env, sse, messages, tid, name, argsStr, permissionMode)
                }
            }
            if (depth >= MAX_DEPTH) {
                sse.send(JSONObject().put("type", "error").put("message", "工具调用次数过多，已自动停止").toString())
                sse.send(JSONObject().put("type", "done").put("stop_reason", "max_depth").toString())
            }
        } catch (e: LlmClient.LlmException) {
            store.addMessage(sessionId, "assistant", "", JSONObject().put("error", e.message ?: ""))
            sse.send(JSONObject().put("type", "error").put("message", e.message ?: "LLM 请求失败").toString())
            sse.send(JSONObject().put("type", "done").put("stop_reason", "error").toString())
        } catch (e: Exception) {
            sse.send(JSONObject().put("type", "error").put("message", "内部错误：${e.message}").toString())
            sse.send(JSONObject().put("type", "done").put("stop_reason", "error").toString())
        }
    }

    private fun runOneTool(
        sessionId: String,
        run: Run,
        env: DeviceEnv,
        sse: SseWriter,
        messages: JSONArray,
        tid: String,
        name: String,
        argsStr: String,
        mode: String,
    ) {
        val args = runCatching { JSONObject(argsStr.ifEmpty { "{}" }) }.getOrDefault(JSONObject())
        val tool = ToolRegistry.byName(name)
        sse.send(JSONObject().put("type", "tool_start").put("id", tid).put("name", name).put("input", args).toString())

        // ---- 决策（与 _decide_tool 逐条对齐）----
        val decision: String = when {
            mode == "chat" -> "deny"
            approval.isAutoDenied(name) -> "deny"
            approval.isAutoAllowed(name, sessionId) -> "allow"
            tool == null && mode != "auto" -> {
                run.awaitingApproval = PendingApproval(tid, name, "未知工具，请确认是否放行（通常应拒绝）", "danger")
                val d = approval.awaitDecision(sessionId, tid, name,
                    "未知工具，请确认是否放行（通常应拒绝）", "danger", approvalTimeout())
                run.awaitingApproval = null
                d
            }
            tool == null -> "allow"
            needsAsk(tool.risk, mode) -> {
                sse.send(JSONObject()
                    .put("type", "approval").put("id", tid).put("name", name)
                    .put("summary", tool.summary).put("risk", tool.risk).toString())
                run.awaitingApproval = PendingApproval(tid, name, tool.summary, tool.risk)
                val d = approval.awaitDecision(sessionId, tid, name, tool.summary, tool.risk, approvalTimeout())
                run.awaitingApproval = null
                d
            }
            else -> "allow"
        }

        if (decision != "allow") {
            val result = JSONObject().put(
                "denied", if (decision == "timeout") "审批超时，已自动拒绝" else "用户拒绝了该操作"
            )
            sse.send(JSONObject().put("type", "tool_update").put("id", tid).put("name", name)
                .put("status", "denied").put("result", result).toString())
            val text = result.toString()
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", tid).put("content", text))
            store.addMessage(sessionId, "tool", text,
                JSONObject().put("tool_call_id", tid).put("name", name).put("status", "denied"))
            return
        }

        // ---- 执行 ----
        val result = try {
            tool!!.handler(args, env)
        } catch (e: com.pocket.agent.tools.ToolAbort) {
            e.result
        } catch (e: Exception) {
            err("工具执行异常：${e.message}")
        }
        val status = if (result.has("error") || result.has("error_code")) "failed" else "completed"
        sse.send(JSONObject().put("type", "tool_update").put("id", tid).put("name", name)
            .put("status", status).put("result", result).toString())
        val text = result.toString()
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", tid).put("content", text))
        store.addMessage(sessionId, "tool", text,
            JSONObject().put("tool_call_id", tid).put("name", name).put("status", status))
    }

    private fun needsAsk(risk: String, mode: String): Boolean =
        mode == "approve" && risk in setOf("write", "danger")

    private fun approvalTimeout(): Int = 120

    private fun titleFor(message: String): String {
        val fallback = message.trim().replace(Regex("\\s+"), " ").take(14).ifEmpty { "新会话" }
        return try {
            val client = llmFactory(settings.llmConfig())
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system")
                    .put("content", "给对话起一个不超过 14 个字的简洁标题，只输出标题本身，不要引号。"))
                .put(JSONObject().put("role", "user").put("content", message.take(2000)))
            client.complete(msgs).trim().trim('"', '「', '」').lines().firstOrNull()?.take(20)
                ?.takeIf { it.isNotEmpty() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun systemPrompt(mode: String): String = buildString {
        append("你是口袋智灵，运行在这台 Android 手机上的本地智能体。")
        append("你可以调用工具直接操作这台手机（电池/剪贴板/通知/传感器/WiFi/短信/文件/知识库等）。")
        append("回答用简体中文，简洁直接；执行敏感操作前会先征求用户同意。")
        if (mode == "approve") {
            append("当前是逐次审批模式：write/danger 工具需要用户确认，请勿尝试绕过。")
        }
    }

    companion object {
        private const val MAX_DEPTH = 8
        @Volatile lateinit var appContext: () -> android.content.Context
    }
}

private fun err(msg: String): JSONObject = JSONObject().put("error", msg)

private fun <K, V> ConcurrentHashMap<K, V>.forEach(f: (K, V) -> Unit) {
    for (e in entries) f(e.key, e.value)
}
