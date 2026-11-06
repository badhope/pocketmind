package com.pocket.agent.runtime

/**
 * 原生引擎当前真实暴露的接口面。
 *
 * 这份清单做两件事：给读者一份可核对的端点表，以及让 EngineContractTest
 * 能断言「列在这里的路径真的实现了」。v1.4.0 时这里还写着 chat/tools/
 * sessions/jobs 未实现，而它们早已迁完 —— 过期清单比没有清单更误导人。
 *
 * 维护规则：新增端点必须同时改 [implemented] 与 ApiRouter 的 when 分支。
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
    const val SEARCH = "/api/sessions/search"
    const val COMPACT = "/api/sessions/{sid}/compact"
    const val APPROVAL = "/api/approval"
    const val APPROVALS = "/api/approvals"
    const val JOBS = "/api/jobs"
    const val JOB_RUN = "/api/jobs/{id}/run"
    const val RECIPES = "/api/recipes"
    const val UNDO = "/api/undo"
    const val STOP = "/api/stop"
    const val KB = "/api/kb"
    const val SKILLS = "/api/skills"
    const val STATS = "/api/stats"
    const val METRICS = "/api/metrics"
    const val DIAGNOSTICS = "/api/diagnostics"
    const val PROVIDERS = "/api/providers"
    const val LLM_TEST = "/api/llm/test"
    const val PROFILE = "/api/memory/profile"
    const val SSE_CONTENT_TYPE = "text/event-stream"

    /** 原生引擎真实实现并返回业务数据的端点。 */
    val implemented = setOf(
        HEALTH, STATUS, CHAT, TOOLS, EXPORT, IMPORT, SETTINGS, SESSIONS, SEARCH, COMPACT,
        APPROVAL, APPROVALS, JOBS, JOB_RUN, RECIPES, UNDO, STOP, KB, SKILLS, STATS,
        METRICS, DIAGNOSTICS, PROVIDERS, LLM_TEST, PROFILE,
    )

    /**
     * 只在 Python（Termux）运行时存在的端点：原生引擎对它们返回 503。
     * 列在这里是为了区分「尚未实现」与「不打算实现」。
     */
    val pythonOnly = setOf("/api/mcp", "/api/memory", "/api/memory/search", "/api/memory/reset")

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
