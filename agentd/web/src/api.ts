// agentd API 客户端：REST + SSE 流式。

import { fetchEventSource } from "@microsoft/fetch-event-source";

const TOKEN_KEY = "pa-token";

/** 局域网令牌：设置面板保存 server.token 时同步写入；请求统一携带。 */
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || "";
}
export function setToken(token: string) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

/**
 * 从 Android 壳读取本机令牌并落盘（仅壳内有效）。
 *
 * 为什么需要：agentd 在 Android 上必须带令牌启动（127.0.0.1 是全机共享的，
 * 否则同机任意 App 都能读会话/执行工具）。令牌由 install.sh 随机生成，
 * 壳通过 Termux 读出来 —— 用户不必手抄，也不会因为忘记令牌而锁死自己。
 *
 * 只在本地还没有令牌时写入，避免覆盖用户在设置面板里手动填的值。
 */
export function adoptNativeToken(raw: string): boolean {
  const t = (raw || "").trim();
  if (!t) return false;
  if (getToken()) return false;
  setToken(t);
  return true;
}

function headers(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { ...extra };
  const t = getToken();
  if (t) h["Authorization"] = `Bearer ${t}`;
  return h;
}

export interface Session {
  id: string;
  title: string;
  message_count: number;
  /** 后端已返回；用于会话列表显示"最后活跃时间" */
  created_at?: number;
  updated_at?: number;
  /** 1 = 已置顶（列表排序优先，与最近活跃时间无关） */
  pinned?: number;
}

export interface StoredMessage {
  /** 后端 messages 表主键；用于「编辑重发 / 重新生成」时按 id 截断历史 */
  id: number;
  role: "user" | "assistant" | "tool" | "system";
  content: string;
  meta: Record<string, unknown>;
  created_at?: number;
}

export interface MessageHit {
  id: number;
  session_id: string;
  title: string;
  role: "user" | "assistant" | "tool";
  /** 命中来源：正文 / 工具输出 / 工具调用参数（1.3 起搜索覆盖工具产物） */
  matched?: "message" | "tool_output" | "tool_call";
  snippet: string;
  created_at?: number;
}

/** 跨会话搜索消息正文（标题里没有的内容也能搜到）。 */
export async function searchMessages(q: string): Promise<MessageHit[]> {
  const r = await api.get<{ items: MessageHit[] }>(
    `/api/sessions/search?q=${encodeURIComponent(q)}`
  );
  return r.items ?? [];
}

export interface Provider {
  id: string;
  label: string;
  base_url: string;
  model: string;
  note: string;
}

export interface LlmSettings {
  provider: string;
  base_url: string;
  api_key: string;
  model: string;
  temperature: number;
  max_tokens: number;
}

export interface AppSettings {
  llm: LlmSettings;
  permission_mode: "auto" | "approve" | "chat";
  // 智能体工作模式：plan = 先出计划待批准；act = 直接执行（默认）
  agent_mode?: "plan" | "act";
  server: {
    allow_lan?: boolean;
    token?: string;
    approval_timeout?: number;
    mcp?: Array<{ name: string; command: string; args: string[] }>;
  };
  // MCP Client：PocketMind 作为客户端调用外部 MCP Server
  mcp_client?: {
    enabled?: boolean;
    timeout?: number;
    servers?: Array<{ name: string; command: string; args: string[]; auto_connect?: boolean }>;
  };
  // 运行参数（此前只能在配置文件里改，现在设置面板可直接调）
  agent?: {
    max_tool_retries: number;
    max_tool_chain_depth: number;
  };
  // 上下文预算与压缩
  context?: {
    budget_tokens: number;
    auto_compress: boolean;
    compress_threshold: number;
  };
  // 记忆与检索
  memory?: {
    auto_summarize: boolean;
    summarize_every_n_turns: number;
    summary_max_chars: number;
    search_limit: number;
    fts_page_size: number;
    retention_days: number;
  };
  user_prefs?: string;
}

/** 配方：预置的一键定时任务模板（后端 /api/recipes）。 */
export interface Recipe {
  id: string;
  name: string;
  description: string;
  trigger_type: "interval" | "cron" | "date";
  expr: string;
  applied: boolean;
}

/** 拉取全部配方及其应用状态。 */
export const getRecipes = () => api.get<{ recipes: Recipe[] }>("/api/recipes").then((d) => d.recipes);
/** 一键应用配方，后端创建对应定时任务并返回 job。 */
export const applyRecipe = (id: string) => api.post<Job>(`/api/recipes/${id}/apply`);

export interface Job {
  id: string;
  name: string;
  trigger_type: "cron" | "interval" | "date";
  expr: string;
  message: string;
  session_id: string;
  condition: string;
  enabled: boolean;
  next_run?: string | null;
}

export const getJobs = () => api.get<{ jobs: Job[] }>("/api/jobs").then((d) => d.jobs);
export const createJob = (body: Partial<Job>) => api.post<Job>("/api/jobs", body);
export const updateJob = (id: string, enabled: boolean) => api.put<Job>(`/api/jobs/${id}`, { enabled });
export const deleteJob = (id: string) => api.del<{ ok: boolean }>(`/api/jobs/${id}`);
/** 立即执行一次（不等触发点）：后端后台起跑，HTTP 立即返回。 */
export const runJobNow = (id: string) => api.post<{ ok: boolean; job_id: string }>(`/api/jobs/${id}/run`);
/** 置顶 / 取消置顶会话（与重命名共用 PUT，但只提交 pinned，避免覆盖标题）。 */
export const pinSession = (id: string, pinned: boolean) =>
  api.put<{ ok: boolean; pinned: boolean }>(`/api/sessions/${id}`, { pinned });

export interface ToolInfo {
  name: string;
  description: string;
  risk: "safe" | "write" | "danger";
  summary: string;
  timeout: number;
  group: string;
}

/** 任务清单条目（todo_write 工具维护，随 SSE todo 事件刷新）。 */
export interface TodoItem {
  content: string;
  status: "pending" | "in_progress" | "completed";
}

/** 一轮对话的 token 用量（done 事件携带；estimated=true 表示 mock/端点未回报，按字数估算）。 */
export interface TokenUsage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  estimated?: boolean;
}

export type ChatEvent =
  | { type: "session"; session_id: string }
  | { type: "text"; text: string }
  | { type: "thinking"; text: string }
  | { type: "tool_start"; id: string; name: string; input: unknown }
  | { type: "approval"; id: string; name: string; summary: string; risk: string }
  | {
      type: "tool_update";
      id: string;
      name: string;
      status: "completed" | "failed" | "denied";
      result: Record<string, unknown>;
      undoable?: boolean;
    }
  | { type: "queued"; message: string }
  // 任务清单更新（todo_write）：整表覆盖
  | { type: "todo"; todos: TodoItem[] }
  // Plan 模式：模型给出执行计划，等待用户批准（随后 done 的 stop_reason="plan" 表示本轮就此停住）
  | { type: "plan"; plan: string; tool_calls: Array<{ name: string; arguments: unknown }> }
  | { type: "done"; stop_reason: string; usage?: TokenUsage }
  | { type: "error"; message: string };

/** 审批决策：一次 / 本会话始终 / 全局始终 / 一次拒绝 / 全局始终拒绝。 */
export type ApprovalDecision =
  | "allow_once"
  | "allow_always"
  | "allow_global"
  | "deny"
  | "deny_always";

/** 当前会话的任务清单（刷新页面后恢复面板）。 */
export const getTodos = (sid: string) =>
  api.get<{ todos: TodoItem[] }>(`/api/sessions/${sid}/todos`).then((d) => d.todos);

/** 手动压缩会话历史为摘要（保留最近 keep 条原文）。 */
export const compactSession = (sid: string, keep = 10) =>
  api.post<{
    ok: boolean;
    reason?: string;
    archived_messages?: number;
    kept?: number;
    summary?: string;
  }>(`/api/sessions/${sid}/compact`, { keep });

/** 结构化长期记忆（USER.md / FACT.md）。 */
export interface ProfileData {
  enabled: boolean;
  user: string;
  facts: string;
  journal_lines?: number;
}
export const getProfile = () => api.get<ProfileData>("/api/memory/profile");
export const putProfile = (body: { user?: string; facts?: string; fact?: string }) =>
  api.put<{ ok: boolean; user: string; facts: string }>("/api/memory/profile", body);

/** 审批权限名单（全局白名单 / 全局黑名单 / 会话白名单）。 */
export interface ApprovalRules {
  global: string[];
  deny: string[];
  sessions: Record<string, string[]>;
}
/** 未决审批（含定时任务/后台触发的）：前端轮询显示，避免后台任务等超时被静默拒绝 */
export interface PendingApproval {
  session_id: string;
  tool_call_id: string;
  name: string;
  summary: string;
  risk: string;
  remaining: number;
  timeout: number;
}

export const getPendingApprovals = () =>
  api.get<{ pending: PendingApproval[] }>("/api/approvals/pending");

export const getApprovalRules = () => api.get<ApprovalRules>("/api/approvals");
export const clearApprovalRules = (scope: "deny" | "global" | `session:${string}`, tool = "") =>
  api.del<{ ok: boolean; rules: ApprovalRules }>(
    `/api/approvals?scope=${encodeURIComponent(scope)}${tool ? `&tool=${encodeURIComponent(tool)}` : ""}`
  );

/** 已安装技能（$AGENT_HOME/skills/<name>/SKILL.md）。 */
export interface SkillInfo {
  name: string;
  description: string;
}
export const getSkills = () => api.get<{ skills: SkillInfo[]; dir: string }>("/api/skills");

/** 知识库条目（管理用；正文写入只经 kb_add 工具，避免绕过审批）。 */
export interface KbItem {
  id: number;
  title: string;
  chars: number;
  created_at: number;
}
export const listKb = () => api.get<{ items: KbItem[] }>("/api/kb").then((d) => d.items);
export const deleteKb = (id: number) => api.del<{ ok: boolean; id: number }>(`/api/kb/${id}`);

/** 撤销一次文件类危险操作（写文件/删文件前已自动备份）。 */
export const undoToolCall = (sessionId: string, toolCallId: string) =>
  api.post<{ ok?: boolean; restored?: string; error?: string }>("/api/undo", {
    session_id: sessionId,
    tool_call_id: toolCallId,
  });

/** 会话重命名。 */
export const renameSession = (sessionId: string, title: string) =>
  api.put<{ ok: boolean; title: string }>(`/api/sessions/${sessionId}`, { title });

/** 导出全部数据（会话/消息/配置）为 JSON 下载。 */
export const exportAll = () => api.get<Record<string, unknown>>("/api/export");

/** 从备份文件恢复（合并语义：同 id 会话跳过、知识库按内容去重）。 */
export const importAll = (payload: Record<string, unknown>) =>
  api.post<{ ok: boolean; imported: Record<string, number> }>("/api/import", payload);

/** 运行统计（真实计数器：请求 / 工具 / LLM tokens）。 */
export interface StatsPayload {
  use_litellm: boolean;
  use_mem0: boolean;
  metrics: {
    uptime_seconds: number;
    requests: { total: number; by_status: Record<string, number> };
    tools: Record<string, { calls: number; avg_ms: number; failures: number }>;
    llm: { calls: number; avg_ms: number; prompt_tokens: number; completion_tokens: number; total_tokens: number };
    errors: Record<string, number>;
  };
}
export const getStats = () => api.get<StatsPayload>("/api/stats");

/** 带 HTTP 状态码的错误：调用方要能区分「401 需要令牌」与一般失败。 */
export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export const isUnauthorized = (e: unknown): boolean => e instanceof ApiError && e.status === 401;

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = await res.json();
      if (j?.detail) msg = j.detail;
      else if (j?.error) msg = j.error;
    } catch {
      /* keep http status */
    }
    throw new ApiError(msg, res.status);
  }
  return (await res.json()) as T;
}

export const api = {
  async get<T>(path: string): Promise<T> {
    return parse<T>(await fetch(path, { cache: "no-store", headers: headers() }));
  },
  async post<T>(path: string, body?: unknown): Promise<T> {
    return parse<T>(
      await fetch(path, {
        method: "POST",
        headers: headers({ "Content-Type": "application/json" }),
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    );
  },
  async put<T>(path: string, body: unknown): Promise<T> {
    return parse<T>(
      await fetch(path, {
        method: "PUT",
        headers: headers({ "Content-Type": "application/json" }),
        body: JSON.stringify(body),
      })
    );
  },
  async del<T>(path: string): Promise<T> {
    return parse<T>(await fetch(path, { method: "DELETE", headers: headers() }));
  },

  /** POST /api/chat 并消费 SSE（@microsoft/fetch-event-source），事件交给 onEvent。 */
  async sseChat(
    sessionId: string,
    message: string,
    onEvent: (ev: ChatEvent) => void,
    signal?: AbortSignal
  ): Promise<void> {
    // 注意：聊天是一次性 POST，网络错误时不能自动重连（否则会重复发送用户消息）。
    // 因此 onerror 一律抛错终止重连；正常流结束由 onclose 收尾。
    await fetchEventSource("/api/chat", {
      method: "POST",
      headers: headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ session_id: sessionId, message }),
      signal,
      async onopen(res) {
        if (!res.ok) {
          let msg = `HTTP ${res.status}`;
          try {
            const j = await res.json();
            msg = j?.detail || j?.error || msg;
          } catch {
            /* keep */
          }
          throw new Error(msg);
        }
      },
      onmessage(ev) {
        // ev.data 即 SSE data: 后的原文，逐帧 JSON 解析后分发
        try {
          onEvent(JSON.parse(ev.data) as ChatEvent);
        } catch {
          /* 忽略坏帧 */
        }
      },
      onerror(err) {
        // 抛错 = 终止 fetch-event-source 的默认重连，让外层 catch 统一处理
        throw err instanceof Error ? err : new Error("SSE 连接异常");
      },
      onclose() {
        // 服务端正常关闭流：无需额外动作，外层 finally 兜底标记消息完成
      },
    });
  },
};
