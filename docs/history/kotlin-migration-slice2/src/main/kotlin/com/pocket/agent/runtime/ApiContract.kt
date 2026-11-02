package com.pocket.agent.runtime

/**
 * 当前 Kotlin 原生服务真实暴露的接口面。
 *
 * [implemented] 之外的路径一律返回 503 + `{"error":"unavailable","migration":"not_implemented"}`，
 * 不允许用空实现冒充实迁移。新增真实实现时必须同时改这里和 NativeEngine 的路由，
 * 由 ContractTest 与 NativeEngineTest 双重锁定。
 */
object ApiContract {
    const val HEALTH = "/api/health"
    const val STATUS = "/api/native/status"
    const val CHAT = "/api/chat"
    const val TOOLS = "/api/tools"
    const val EXPORT = "/api/export"
    const val IMPORT = "/api/import"
    const val SETTINGS = "/api/settings"
    const val SESSIONS = "/api/sessions"
    const val APPROVAL = "/api/approval"
    const val JOBS = "/api/jobs"
    const val SSE_CONTENT_TYPE = "text/event-stream"

    /** 只读探活与本地状态：无需业务数据，已由 Kotlin 真实实现。 */
    val implemented = setOf(HEALTH, STATUS)

    /** 依赖 Python agentd 的存储/编排：Kotlin 侧尚未实现，必须 503。 */
    val unimplemented = setOf(CHAT, TOOLS, EXPORT, IMPORT, SETTINGS, SESSIONS, APPROVAL, JOBS)

    val events = setOf("session", "text", "thinking", "tool_start", "tool_update", "approval", "plan", "todo", "queued", "error", "done")
}

enum class RuntimeMode { KOTLIN, TERMUX }
enum class CapabilityState { AVAILABLE, UNAVAILABLE, PERMISSION_REQUIRED }
enum class EngineState { STOPPED, STARTING, RUNNING, FAILED }
enum class ToolRisk(val wireValue: String) { SAFE("safe"), WRITE("write"), DANGER("danger") }

data class ToolDescriptor(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val summary: String = "",
    val timeout: Int = 120,
    val group: String = "other",
)
