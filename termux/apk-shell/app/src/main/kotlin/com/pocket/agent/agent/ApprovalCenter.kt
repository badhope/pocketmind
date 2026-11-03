package com.pocket.agent.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 审批中心（服务端强制，与 Python approval.py 同语义）：
 *  - 五种决策：allow_once / allow_always（本会话）/ allow_global / deny / deny_always；
 *  - 未决审批在超时后自动拒绝（默认 120 s）；
 *  - 全局 allow / deny 名单持久化到 rules.json；deny 优先于 allow；
 *  - 「始终拒绝」与「始终允许」命中的调用不弹卡（不产生幽灵审批）。
 */
class ApprovalCenter(context: Context) {

    class Pending(
        val sessionId: String,
        val toolCallId: String,
        val name: String,
        val summary: String,
        val risk: String,
        val timeout: Int,
        val deadlineAt: Long,
    ) {
        fun json(remaining: Int): JSONObject =
            JSONObject()
                .put("session_id", sessionId).put("tool_call_id", toolCallId)
                .put("name", name).put("summary", summary).put("risk", risk)
                .put("remaining", remaining).put("timeout", timeout)
    }

    private val rulesFile = File(context.filesDir, "rules.json")
    private val globalAllow = HashSet<String>()
    private val globalDeny = HashSet<String>()
    private val sessionAllow = ConcurrentHashMap<String, MutableSet<String>>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val decisions = ConcurrentHashMap<String, Pair<CountDownLatch, AtomicReference<String>>>()

    init {
        load()
    }

    @Synchronized private fun load() {
        try {
            if (!rulesFile.exists()) return
            val r = JSONObject(rulesFile.readText())
            r.optJSONArray("global")?.forEachString { globalAllow.add(it) }
            r.optJSONArray("deny")?.forEachString { globalDeny.add(it) }
            r.optJSONObject("sessions")?.let { sessions ->
                for (sid in sessions.keys()) {
                    val set = sessionAllow.getOrPut(sid) { HashSet() }
                    sessions.optJSONArray(sid)?.forEachString { set.add(it) }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized private fun persist() {
        val r = JSONObject()
            .put("global", JSONArray(globalAllow))
            .put("deny", JSONArray(globalDeny))
            .put("sessions", JSONObject().apply {
                sessionAllow.forEach { (sid, set) -> put(sid, JSONArray(set)) }
            })
        rulesFile.writeText(r.toString(2))
    }

    fun listRules(): JSONObject {
        val sessions = JSONObject()
        sessionAllow.forEach { (sid, set) -> sessions.put(sid, JSONArray(set)) }
        return JSONObject()
            .put("global", JSONArray(globalAllow.sorted()))
            .put("deny", JSONArray(globalDeny.sorted()))
            .put("sessions", sessions)
    }

    fun isAutoAllowed(name: String, sessionId: String): Boolean =
        name in globalAllow || sessionAllow[sessionId]?.contains(name) == true

    fun isAutoDenied(name: String): Boolean = name in globalDeny

    /** 注册一个未决审批并等待决策；超时返回 "timeout"。 */
    fun awaitDecision(
        sessionId: String,
        toolCallId: String,
        name: String,
        summary: String,
        risk: String,
        timeoutSeconds: Int,
    ): String {
        val timeout = timeoutSeconds.coerceIn(10, 600)
        val p = Pending(sessionId, toolCallId, name, summary, risk, timeout,
            System.currentTimeMillis() + timeout * 1000L)
        pending[toolCallId] = p
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String>("")
        decisions[toolCallId] = latch to answer
        try {
            if (!latch.await(timeout.toLong(), TimeUnit.SECONDS)) return "timeout"
        } catch (_: InterruptedException) {
            return "timeout"
        } finally {
            pending.remove(toolCallId)
            decisions.remove(toolCallId)
        }
        return answer.get()
    }

    /** 前端 / 定时器提交决策；返回 false 表示审批不存在或已过期。 */
    fun decide(sessionId: String, toolCallId: String, decision: String): Boolean {
        val pair = decisions[toolCallId] ?: return false
        val p = pending[toolCallId] ?: return false
        // 决策必须来自同一会话（防止跨会话替批）
        if (p.sessionId != sessionId) return false
        val (latch, answer) = pair
        when (decision) {
            "allow_once" -> answer.set("allow")
            "allow_always" -> {
                sessionAllow.getOrPut(sessionId) { HashSet() }.add(p.name)
                persist()
                answer.set("allow")
            }
            "allow_global" -> {
                globalAllow.add(p.name)
                persist()
                answer.set("allow")
            }
            "deny" -> answer.set("deny")
            "deny_always" -> {
                globalDeny.add(p.name)
                persist()
                answer.set("deny")
            }
            else -> return false
        }
        latch.countDown()
        return true
    }

    /** 清理已过期的未决项（/api/approvals/pending 轮询时顺带做）。 */
    fun purgeExpired() {
        val now = System.currentTimeMillis()
        pending.values.removeIf { it.deadlineAt < now }
    }

    /** 当前未决审批快照（前端轮询 /api/approvals/pending 用）。 */
    fun pendingList(): List<Pending> {
        purgeExpired()
        return pending.values.sortedBy { it.deadlineAt }
    }

    /** 撤销名单：传 null 表示该组不变，空集合表示整组清空。 */
    @Synchronized fun replaceRules(
        globalAllow: List<String>? = null,
        deny: List<String>? = null,
        sessionAllow: Map<String, List<String>>? = null,
    ) {
        globalAllow?.let { this.globalAllow.clear(); this.globalAllow.addAll(it) }
        deny?.let { this.globalDeny.clear(); this.globalDeny.addAll(it) }
        sessionAllow?.let {
            this.sessionAllow.clear()
            it.forEach { (sid, names) -> this.sessionAllow[sid] = HashSet(names) }
        }
        persist()
    }

    private fun JSONArray.forEachString(f: (String) -> Unit) {
        for (i in 0 until length()) {
            val s = optString(i)
            if (s.isNotEmpty()) f(s)
        }
    }
}
