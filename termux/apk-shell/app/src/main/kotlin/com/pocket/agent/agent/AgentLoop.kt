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
     *
     * [headless]：无人在看界面（定时任务触发）。此时需要审批的工具**直接拒绝**，
     * 不再等 120 秒——后台没有任何人能点批准，等满只会让任务白转两分钟，
     * 用户还以为任务卡住了。拒绝理由写清楚，任务结果里能看到。
     */
    fun chat(sessionId: String, message: String, sse: SseWriter, headless: Boolean = false) {
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
                runOne(sessionId, current, run, sse, headless)
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

    private fun runOne(sessionId: String, message: String, run: Run, sse: SseWriter, headless: Boolean = false) {
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

        // 组装上下文：system + 历史归档 + 最近历史
        val history = store.getMessages(sessionId, 40)
        val messages = JSONArray()
        val system = StringBuilder(systemPrompt(permissionMode))
        // 归档摘要必须注入，否则「压缩」只是把消息藏起来、模型照样失忆。
        // 按旧→新拼接，最多 5 段（与 Python get_archives(limit=5) 一致）。
        val archives = store.getArchives(sessionId, 5)
        if (archives.length() > 0) {
            system.append("\n\n以下是更早对话的摘要（按时间从旧到新）：")
            for (i in 0 until archives.length()) {
                system.append("\n- ").append(archives.optString(i))
            }
        }
        messages.put(JSONObject().put("role", "system").put("content", system.toString()))
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
        // 按 token 预算裁剪历史：固定 40 条在长会话里照样能撑爆窗口
        // （40 条 × 单条几千字）。这里从最旧开始丢，system 与最后一条用户
        // 消息永远保留——丢到最后一问一答仍然可回答。
        trimToBudget(messages)

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
                    runOneTool(sessionId, run, env, sse, messages, tid, name, argsStr, permissionMode, headless)
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
        headless: Boolean = false,
    ) {
        val args = runCatching { JSONObject(argsStr.ifEmpty { "{}" }) }.getOrDefault(JSONObject())
        val tool = ToolRegistry.byName(name)
        sse.send(JSONObject().put("type", "tool_start").put("id", tid).put("name", name).put("input", args).toString())

        // ---- 决策（与 _decide_tool 逐条对齐）----
        // headless（定时任务）分支必须排在审批之前：后台没人能点批准，
        // 走到 awaitDecision 就是白等 120 秒再被超时拒绝。
        val decision: String = when {
            mode == "chat" -> "deny"
            approval.isAutoDenied(name) -> "deny"
            approval.isAutoAllowed(name, sessionId) -> "allow"
            headless && (tool == null || needsAsk(tool.risk, mode)) -> "headless_denied"
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
                "denied",
                when (decision) {
                    "timeout" -> "审批超时，已自动拒绝"
                    "headless_denied" ->
                        "定时任务在后台运行，无人可以确认这次操作，已跳过。" +
                            "若要允许，请先在对话里手动执行一次并选择「始终允许」，或改用只读工具。"
                    else -> "用户拒绝了该操作"
                }
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
        // 撤销凭证从工具结果里剥离单独登记（与 Python `_ckpt` 约定一致）：
        // 留在 result 里会被模型当成"备份路径"复述给用户，也不该进上下文。
        val undoable = registerCheckpoint(sessionId, tid, name, result)
        val status = if (result.has("error") || result.has("error_code")) "failed" else "completed"
        val update = JSONObject().put("type", "tool_update").put("id", tid).put("name", name)
            .put("status", status).put("result", result)
        if (undoable) update.put("undoable", true)
        sse.send(update.toString())
        // 落库存全文（用户回看时不该丢内容），但送进上下文必须裁剪：
        // 一条 run_shell 的 30KB 输出 ≈ 7500 token，且之后每轮都会重发。
        val fullText = result.toString()
        val contextText = limitToolResult(fullText)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", tid).put("content", contextText))
        store.addMessage(sessionId, "tool", fullText,
            JSONObject().put("tool_call_id", tid).put("name", name).put("status", status))
    }

    /**
     * 工具结果入上下文的截断（对齐 Python `_tool_result_limited`）。
     *
     * - 配了 `context.budget_tokens`：单条上限 = 预算 15%（×4 字符/token），
     *   下限 400、硬顶 60000 字符（≈1.5 万 token）。硬顶不能省——budget=200000
     *   按比例会给出 12 万字符，一条 cat 大文件就足以挤爆整个上下文。
     * - 未配置：固定 6000 字符（与 Python MAX_TOOL_TEXT 同值）。
     * 超限保留首尾、中间标注省略，避免丢掉开头的结构字段与结尾的统计/结论。
     */
    private fun limitToolResult(text: String): String {
        val ctx = settings.contextCfg()
        val limit = if (ctx.budgetTokens > 0) {
            maxOf(400, minOf((ctx.budgetTokens * 0.15 * 4).toInt(), MAX_TOOL_TEXT_HARD_CAP))
        } else {
            MAX_TOOL_TEXT
        }
        if (text.length <= limit) return text
        val head = limit * 2 / 3
        val tail = limit - head - 60
        return text.take(head) +
            "\n…（工具输出已截断，原文 ${text.length} 字符，仅保留首尾）…\n" +
            text.takeLast(maxOf(0, tail))
    }

    /**
     * 按 token 预算裁剪消息列表（原地修改）。
     *
     * 估算口径 4 字符/token，与 Python `CHARS_PER_TOKEN` 一致。未配置预算时
     * 不裁剪（沿用固定 40 条窗口）；配置了就从最旧开始丢，但：
     *  - 第 0 条（system，含归档摘要）永不丢；
     *  - 最后一条（当前这轮 user 消息）永不丢；
     *  - tool 消息必须跟着它前面的 assistant(tool_calls) 一起丢，否则会出现
     *    孤立的 tool 响应，OpenAI 兼容端点直接 400。
     */
    private fun trimToBudget(messages: JSONArray) {
        val cfg = settings.contextCfg()
        val budget = cfg.budgetTokens
        if (budget <= 0) return
        fun est(msg: JSONObject): Int = msg.optString("content").length / CHARS_PER_TOKEN + 8
        var total = 0
        for (i in 0 until messages.length()) total += est(messages.optJSONObject(i) ?: JSONObject())
        if (total <= budget) return

        // 从 index 1 往后找第一个可丢的位置，保持「assistant 带 tool_calls」
        // 与它后面的 tool 响应成对出现。
        var dropFrom = 1
        while (dropFrom < messages.length() - 1 && total > budget) {
            val msg = messages.optJSONObject(dropFrom) ?: break
            // 若当前要丢的是 tool，往前退到它的 assistant 起点
            var end = dropFrom + 1
            if (msg.optString("role") == "tool") {
                // 不该发生（tool 前面一定是 assistant），保守跳过
                dropFrom++; continue
            }
            // 把紧跟其后的 tool 消息一起带走
            while (end < messages.length() - 1 &&
                messages.optJSONObject(end)?.optString("role") == "tool") {
                end++
            }
            for (i in dropFrom until end) total -= est(messages.optJSONObject(i) ?: JSONObject())
            dropFrom = end
        }
        if (dropFrom <= 1) return
        // 重建：保留 system + 剩余尾部
        val kept = JSONArray()
        kept.put(messages.optJSONObject(0) ?: JSONObject())
        for (i in dropFrom until messages.length()) kept.put(messages.optJSONObject(i) ?: continue)
        // 插一条提示，让模型知道前面有内容被裁掉了，不要假装自己看过
        if (kept.length() > 1) {
            kept.put(1, JSONObject().put("role", "system").put("content",
                "（较早的对话因超出上下文预算已省略；如需回顾请让用户重新说明。）"))
        }
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        for (i in 0 until kept.length()) messages.put(kept.optJSONObject(i))
    }

    /** 从工具结果里取出 `_ckpt` 并落库；返回该调用是否可撤销。 */
    private fun registerCheckpoint(sessionId: String, toolCallId: String, toolName: String, result: JSONObject): Boolean {
        val ck = result.optJSONObject("_ckpt") ?: return false
        result.remove("_ckpt")
        return try {
            store.recordCheckpoint(
                sessionId, toolCallId, toolName,
                ck.optString("path"), ck.optString("backup"), ck.optString("kind", "overwrite")
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 压缩会话历史：把 keep 之前的消息归档成一段摘要，保留最近 keep 条。
     *
     * 放在 AgentLoop 而不是路由层，是因为摘要要走同一个 llmFactory ——
     * 路由层自己 new LlmClient 会让单测无法注入假客户端（会真连网络）。
     *
     * 水位（archived_upto）只在摘要落库后推进：失败不丢历史，下次还能重试。
     */
    fun compact(sessionId: String, keep: Int): JSONObject {
        val k = keep.coerceIn(2, 200)
        val history = store.getMessages(sessionId, 1000)
        if (history.length() <= k) {
            return JSONObject().put("ok", false).put("reason", "消息只有 ${history.length()} 条，无需压缩")
        }
        val upto = store.archivedUpto(sessionId)
        val pending = ArrayList<JSONObject>()
        for (i in 0 until history.length() - k) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optLong("id") > upto) pending.add(m)
        }
        if (pending.isEmpty()) {
            return JSONObject().put("ok", false).put("reason", "这批历史已归档过，无需重复压缩")
        }
        val summary = summarize(pending) ?: run {
            // 兜底：取最后一条用户消息。压缩的价值是不丢上下文，
            // 因为模型不可用就让整个操作失败没有意义。
            pending.asReversed().firstOrNull { it.optString("role") == "user" }
                ?.let { "（早期对话摘要）" + it.optString("content").take(200) }
        }
        if (summary.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("reason", "没有可压缩的正文内容")
        }
        store.appendArchive(sessionId, summary)
        store.setArchivedUpto(sessionId, pending.maxOf { it.optLong("id") })
        return JSONObject().put("ok", true)
            .put("archived_messages", pending.size)
            .put("kept", k)
            .put("summary", summary)
    }

    /** 用模型压缩；未配置或失败返回 null，由调用方降级。 */
    private fun summarize(messages: List<JSONObject>): String? {
        if (!settings.llmReady()) return null
        return try {
            val sb = StringBuilder()
            for (m in messages) {
                val role = m.optString("role")
                if (role != "user" && role != "assistant") continue
                val text = m.optString("content").trim()
                if (text.isEmpty()) continue
                sb.append(if (role == "user") "用户：" else "助手：").append(text.take(600)).append('\n')
            }
            if (sb.isEmpty()) return null
            val req = JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "把下面的对话压缩成一段不超过 400 字的中文摘要，保留结论、数字、待办与用户偏好；" +
                        "不要复述过程，不要加评论，直接输出摘要正文。"))
                .put(JSONObject().put("role", "user").put("content", sb.toString().take(12000)))
            llmFactory(settings.llmConfig()).complete(req, maxTokens = 500).trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 撤销一次文件操作：把备份拷回原路径。
     *
     * kind（overwrite / restore）都归结为「写回备份」，差别只在语义来源：
     * 前者是被覆盖前备份，后者是被删除前备份。目录不清空——原生版本只备份单文件。
     */
    fun undo(sessionId: String, toolCallId: String): JSONObject {
        val ck = store.findCheckpoint(sessionId, toolCallId)
            ?: return JSONObject().put("error", "没有可撤销的操作（可能未备份或记录已删除）")
        if (ck.optBoolean("undone")) return JSONObject().put("error", "该操作已撤销过")
        val backup = java.io.File(ck.optString("backup"))
        if (!backup.isFile) return JSONObject().put("error", "备份文件已丢失，无法撤销")
        return try {
            val target = java.io.File(ck.optString("path"))
            target.parentFile?.mkdirs()
            if (target.exists() && !target.delete()) {
                return JSONObject().put("error", "目标文件被占用，无法还原")
            }
            backup.copyTo(target, overwrite = true)
            store.markCheckpointUndone(ck.optLong("id"))
            JSONObject().put("ok", true).put("restored", target.absolutePath)
                .put("tool", ck.optString("tool"))
        } catch (e: Exception) {
            JSONObject().put("error", "撤销失败：${e.message}")
        }
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
        /** 单条工具结果入上下文的固定上限（未配置预算时），与 Python MAX_TOOL_TEXT 同值。 */
        private const val MAX_TOOL_TEXT = 6000
        /** 按预算放大时的硬顶：约 1.5 万 token，再大就不是"上下文"而是"文件传输"了。 */
        private const val MAX_TOOL_TEXT_HARD_CAP = 60_000
        private const val CHARS_PER_TOKEN = 4
        @Volatile lateinit var appContext: () -> android.content.Context
    }
}

private fun err(msg: String): JSONObject = JSONObject().put("error", msg)

private fun <K, V> ConcurrentHashMap<K, V>.forEach(f: (K, V) -> Unit) {
    for (e in entries) f(e.key, e.value)
}
