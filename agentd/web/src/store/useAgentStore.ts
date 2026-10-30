// 全局状态仓库（Zustand）：集中管理会话/消息/设置/审批/流式状态，消除 props 层层透传。
//
// 设计说明：
// - 响应式状态（sessions/messages/busy/error 等）放在 store 里，组件用 useXxxStore(s => s.field) 订阅。
// - 不需要触发重渲染的运行时句柄（AbortController、发送队列、运行中标记）用模块级变量持有，
//   避免放进 state 导致多余渲染。
// - 所有 action 保持改造前 App.tsx 的行为完全一致。

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";
import type { StateCreator } from "zustand/vanilla";
import {
  api,
  exportAll,
  getPendingApprovals,
  getTodos,
  isUnauthorized,
  pinSession,
  renameSession,
  undoToolCall,
  type ApiError,
  type ApprovalDecision,
  type PendingApproval,
  type Session,
  type StoredMessage,
  type TodoItem,
} from "../api";
import { isNativeShell, saveTextFile, vibrate } from "../native";
import { getThemePref, setThemePref, type ThemePref } from "../theme";
import { uid, type Msg, type Part } from "../types";
import { applyEvent, restore } from "./partReducer";
export { applyEvent, appendTextPart, restore, safeJson } from "./partReducer";
export type { Msg, Part, ToolPart } from "../types";

/** 连接状态类型 */
export type ConnectionStatus = "connected" | "disconnected" | "reconnecting";

/* ---------- 事件 → 消息状态（纯函数，可单测） ---------- */

/** 把一段流式文本追加到消息末尾的同类型 part（text/thinking），否则新开一个 part。 */
/* ---------- 非响应式运行时句柄（不触发渲染） ---------- */

let abortController: AbortController | null = null;
// 前端消息队列：同一会话连续发送时排队执行（服务端亦有队列作为 API 层保险）
let sendQueue: Array<{ text: string; aid: string }> = [];
let running = false;
// 会话消息代数：每次本地插入新消息时自增，用来识别「加载响应已过期」
let msgGen = 0;

// 后端健康检查轮询 id（用于 clearInterval）
let healthTimer: ReturnType<typeof setInterval> | null = null;
// 连续探测失败计数（用于「一次抖动不报断开」）
let pollFailures = 0;
// 后端断连后的退避秒数（指数增长，上限 30s）
let backoffSeconds = 1;
const MAX_BACKOFF = 30;
// 正常状态每 5 秒探活一次：1 秒轮询既费电又曾把服务端限流打满（应用自己判自己掉线）
const BASE_BACKOFF = 5;

// 厂商 id → 显示标签（与后端 config.PRESETS 对齐）
function providerName(id: string): string {
  const map: Record<string, string> = {
    doubao: "豆包",
    deepseek: "DeepSeek",
    qwen: "通义千问",
    kimi: "Kimi",
    glm: "智谱",
    siliconflow: "硅基流动",
    openai: "OpenAI",
    moonshot: "Moonshot",
    // custom 是「自定义 OpenAI 兼容端点」的内部 id；直接回显英文字面量对用户
    // 毫无意义（截图巡检时发现的），这里给一个说人话的标签
    custom: "自定义端点",
  };
  return map[id] || id || "";
}

/* ---------- Store 类型 ---------- */

export interface AgentStore {
  // 会话与消息
  sessions: Session[];
  sessionId: string;
  messages: Msg[];
  // 当前会话的任务清单（todo_write 维护；切会话/刷新后从后端恢复）
  todos: TodoItem[];
  // 未决审批（含定时任务触发的后台审批）：SSE 之外的补充通路
  pendingApprovals: PendingApproval[];
  refreshPendingApprovals: () => Promise<void>;
  /** 决定后台审批（可能属于别的会话，不能用当前 sessionId）；返回是否提交成功 */
  decideBackgroundApproval: (sessionId: string, toolCallId: string, decision: ApprovalDecision) => Promise<boolean>;
  // 清空任务清单（全部完成或任务取消）：本地立即清，同时同步后端
  clearTodos: () => Promise<void>;
  // 流式/连接状态
  busy: boolean;
  ready: boolean;
  mock: boolean;
  error: string;
  // 清除顶部错误提示（用户手动关闭）
  clearError: () => void;
  // 后端连接状态：connected 正常 / disconnected 断连 / reconnecting 重连中
  connectionStatus: ConnectionStatus;
  /**
   * 后端要求访问令牌（HTTP 401）时为 true，首屏显示令牌输入层。
   *
   * 为什么放进 store：401 可能出现在任意请求上（健康检查、会话列表、SSE）。
   * 若每个调用点各自处理，用户会看到一堆"请求失败"；统一在这里置位一次，
   * 界面只呈现一个明确的解决入口。
   */
  needToken: boolean;
  /** 令牌输入完成：清标记并重建连接。 */
  onTokenSubmitted: () => void;
  // 浮层开关
  showSessions: boolean;
  showSettings: boolean;
  showTimers: boolean;
  // 主题与撤销
  theme: ThemePref;
  undoingId: string | null;
  // 智能体工作模式：plan = 先出计划待批准；act = 直接执行
  agentMode: "plan" | "act";
  // 当前 LLM 厂商显示标签（如"豆包"、"DeepSeek"、"custom"），用于 Composer 输入框
  providerLabel: string;
  // 权限模式（与后端 permission_mode 同步）
  permissionMode: "auto" | "approve" | "chat";
  // 系统设置（脱敏后的快照）
  settings: Record<string, unknown> | null;

  // 生命周期
  init: () => Promise<void>;
  refreshSessions: () => Promise<Session[]>;
  loadSession: (sid: string) => Promise<void>;
  // 健康检查轮询（指数退避，后端恢复后自动重连）
  startHealthPolling: () => void;
  stopHealthPolling: () => void;

  // 发送/停止
  send: (text: string) => Promise<void>;
  stop: () => void;

  // 消息级操作（对标 Claude / ChatGPT：每条消息都能单独处理）
  /** 把对话回退到该消息之前（连同它一起删），用于「编辑后重发」。 */
  truncateFrom: (msg: Msg) => Promise<number>;
  /** 删除该消息及其之后的历史（对话必须保持 user/assistant 配对完整）。 */
  deleteMessage: (msg: Msg) => Promise<void>;
  /** 重新生成：截断该助手回复及其后的历史，用上一句用户消息重跑。 */
  regenerate: (msg: Msg) => Promise<void>;

  // 联网搜索开关（本轮生效，持久化到 localStorage）
  webSearch: boolean;
  setWebSearch: (v: boolean) => void;

  // Plan/Act 模式
  setAgentMode: (m: "plan" | "act") => void;
  /** 批准某条计划卡片：切到 act 模式，把触发该计划的用户原话重发一轮。 */
  approvePlan: (assistantMsgId: string) => Promise<void>;
  /** 修改计划：切回输入框聚焦，让用户补充/调整后重发。 */
  editPlan: (assistantMsgId: string) => void;
  /** 忽略计划：不执行，仅在前端收起计划卡片。 */
  ignorePlan: (assistantMsgId: string) => void;

  // 会话操作
  onNewSession: () => Promise<void>;
  onDeleteSession: (sid: string) => Promise<void>;
  onRenameSession: (sid: string, title: string) => Promise<void>;
  /** 置顶 / 取消置顶（服务端持久化，列表排序由后端保证）。 */
  onPinSession: (sid: string, pinned: boolean) => Promise<void>;

  // 审批 / 撤销 / 导出
  onApproval: (toolCallId: string, decision: ApprovalDecision) => Promise<void>;
  onUndo: (toolCallId: string) => Promise<void>;
  onExport: () => Promise<void>;

  // UI 开关
  setShowSessions: (v: boolean) => void;
  setShowSettings: (v: boolean) => void;
  setShowTimers: (v: boolean) => void;
  setTheme: (t: ThemePref) => void;
}

const _storeCreator: StateCreator<AgentStore, [], []> = (set, get) => {
  /** 跑单条 SSE 流式对话：事件折叠进 id=aid 的 assistant 占位消息。 */
  const doRun = async (text: string, aid: string) => {
    const ac = new AbortController();
    abortController = ac;
    const sessionId = get().sessionId;
    // 联网开关：在用户原话前加一条系统级指令，让模型知道「这次可以查外网」。
    // 不改后端契约（避免为一颗按钮新增字段），也不污染 UI 上显示的用户消息。
    const outgoing = get().webSearch
      ? "[联网搜索已开启：遇到需要实时信息或你不确定的事实，请先调用 web_search 查证；" +
        "引用结果时给出链接。若搜索失败，如实说明失败原因，不要编造。]\n\n" + text
      : text;
    try {
      await api.sseChat(sessionId, outgoing, (ev) => {
        // 后端可能在首帧才返回真实 session_id（新建会话时），同步更新
        if (ev.type === "session" && ev.session_id !== get().sessionId) {
          set({ sessionId: ev.session_id });
        }
        // 开始产出时清除「排队中」标记（plan 事件也算正式开始产出）
        if (ev.type === "text" || ev.type === "tool_start" || ev.type === "thinking" || ev.type === "plan") {
          set((s) => ({ messages: s.messages.map((m) => (m.id === aid ? { ...m, queued: false } : m)) }));
        }
        // 任务清单：整表覆盖（后端已落库，前端只是渲染面）
        if (ev.type === "todo") {
          set({ todos: ev.todos });
        }
        set((s) => ({ messages: s.messages.map((m) => (m.id === aid ? applyEvent(m, ev) : m)) }));
      }, ac.signal);
    } catch (e) {
      const err = e as Error;
      if (err.name === "AbortError") {
        set((s) => ({ messages: s.messages.map((m) => (m.id === aid ? { ...m, done: true } : m)) }));
      } else {
        set({ error: err.message || "请求失败" });
        set((s) => ({
          messages: s.messages.map((m) =>
            m.id === aid ? { ...m, done: true, error: err.message || "请求失败" } : m
          ),
        }));
      }
    } finally {
      // 兜底：SSE 正常结束但未收到 done 事件时（连接中途断开/后端重启），
      // 标记消息完成，避免永久停在"正在思考…"
      set((s) => ({ messages: s.messages.map((m) => (m.id === aid && !m.done ? { ...m, done: true } : m)) }));
      abortController = null;
    }
  };

  /**
   * 队列驱动器：串行执行 sendQueue 里的每一条消息。
   *
   * 为什么单独抽出来：旧实现是「push 后如果没在跑就自己起循环」，循环结束把 running 置 false。
   * 但在这两步之间（`while` 判空退出 → `running = false`）入队的消息会看到 running 仍为 true
   * 而直接 return，队列再也没有驱动者——界面上停在「已排队」，实际**永不执行**（消息被吞）。
   * 现在统一入口 + 收尾接力，任何时刻入队的消息最终都会被处理。
   */
  const driveQueue = async () => {
    if (running) return;
    running = true;
    set({ busy: true, error: "" });
    try {
      while (sendQueue.length > 0) {
        const item = sendQueue.shift()!;
        await doRun(item.text, item.aid);
        void get().refreshSessions();
      }
    } finally {
      running = false;
      set({ busy: false });
      abortController = null;
      // 接力：覆盖「刚好在退出瞬间入队」的窗口
      if (sendQueue.length > 0) void driveQueue();
    }
  };

  return {
    // ---------- 初始状态 ----------
    sessions: [],
    sessionId: "",
    messages: [],
    todos: [] as TodoItem[],
    pendingApprovals: [] as PendingApproval[],
    busy: false,
    ready: false,
    mock: false,
    error: "",
    providerLabel: "",
    showSessions: false,
    showSettings: false,
    showTimers: false,
    theme: getThemePref(),
    undoingId: null,
    // 默认 act（直接执行）；init 时若后端保存过 plan 模式会再纠正
    agentMode: "act",
    webSearch: false,
    // 默认 approve mode；init 时若后端保存过其他值会再纠正
    permissionMode: "auto" as "auto" | "approve" | "chat",
    // 后端 settings 快照（脱敏后）
    settings: null as Record<string, unknown> | null,
    // 连接状态：初始为 reconnecting，首次健康检查后确定
    connectionStatus: "reconnecting" as ConnectionStatus,
    needToken: false,

    onTokenSubmitted() {
      set({ needToken: false, connectionStatus: "reconnecting" });
      void get().init();
    },

    // ---------- 健康检查轮询（指数退避） ----------
    startHealthPolling() {
      if (healthTimer) return;
      const poll = async () => {
        try {
          const h = await api.get<{ ok: boolean; ready: boolean; mock: boolean }>("/api/health");
          pollFailures = 0; // 成功即复位；瞬时抖动不会留下「已断开」
          // 顺带拉一次未决审批：定时任务触发的审批卡没有 SSE 通路，
          // 只能靠轮询补显示，否则用户永远看不到、任务必然等超时被拒
          void useAgentStore.getState().refreshPendingApprovals();
          const prev = useAgentStore.getState().connectionStatus;
          if (prev === "disconnected" || prev === "reconnecting") {
            set({ ready: h.ready, mock: h.mock, connectionStatus: "connected" });
          }
          // 连接正常时重置退避，并恢复快速轮询
          if (backoffSeconds !== BASE_BACKOFF) {
            backoffSeconds = BASE_BACKOFF;
            if (healthTimer) {
              clearInterval(healthTimer);
              healthTimer = setInterval(poll, BASE_BACKOFF * 1000);
            }
          }
        } catch {
          // 丢包/请求被中断（例如正在刷新页面）会让单次探测失败——手机上网络本来就常抖，
          // 因此要求**连续两次**失败才报「已断开」：一次抖动不该吓用户一跳，
          // 真正的宕机仍会在约 2 秒内提示。
          pollFailures += 1;
          if (pollFailures < 2) {
            const cur0 = useAgentStore.getState().connectionStatus;
            if (cur0 === "connected") set({ connectionStatus: "reconnecting" });
            return;
          }
          const cur = useAgentStore.getState().connectionStatus;
          if (cur !== "disconnected") {
            set({ ready: false, connectionStatus: "disconnected" });
          }
          // 指数退避：翻倍至上限，并更新轮询间隔
          const nextBackoff = Math.min(
            backoffSeconds === 0 ? BASE_BACKOFF : backoffSeconds * 2,
            MAX_BACKOFF
          );
          if (nextBackoff !== backoffSeconds) {
            backoffSeconds = nextBackoff;
            if (healthTimer) {
              clearInterval(healthTimer);
              healthTimer = setInterval(poll, backoffSeconds * 1000);
            }
          }
        }
      };
      // 首次立即执行，之后按当前退避间隔轮询
      void poll();
      healthTimer = setInterval(poll, backoffSeconds * 1000);
    },

    stopHealthPolling() {
      if (healthTimer) {
        clearInterval(healthTimer);
        healthTimer = null;
      }
    },

    // ---------- 生命周期 ----------
    async init() {
      set({ connectionStatus: "reconnecting" });
      try {
        const h = await api.get<{ ok: boolean; ready: boolean; mock: boolean }>("/api/health");
        set({ ready: h.ready, mock: h.mock, connectionStatus: "connected" });
      } catch (e) {
        // 401 不是"后端挂了"，而是"缺令牌"：必须给出输入入口，否则用户只能看到
        // 一直转圈的"正在连接"，永远不知道该做什么。
        if (isUnauthorized(e)) {
          set({ ready: false, needToken: true, connectionStatus: "connected" });
          return;
        }
        set({ ready: false, connectionStatus: "disconnected" });
      }
      // 启动持续健康检查轮询（在后台持续检测后端是否恢复）
      get().startHealthPolling();
      // 读取后端持久化的智能体模式（plan/act）+ 当前 LLM 厂商标签 + 设置快照
      // providerLabel 用于 Composer 输入框左上角显示（如"豆包""DeepSeek"）
      // settings 快照供只读消费者使用（审批倒计时读 server.approval_timeout 等）
      try {
        const s = await api.get<{ agent_mode?: "plan" | "act"; llm?: { provider?: string } }>("/api/settings");
        set({ settings: s as Record<string, unknown> });
        if (s.agent_mode === "plan" || s.agent_mode === "act") set({ agentMode: s.agent_mode });
        if (s.llm?.provider) set({ providerLabel: providerName(s.llm.provider) });
      } catch {
        /* 后端尚未支持该字段时静默忽略 */
      }
      const list = await get().refreshSessions();
      if (list.length === 0) {
        let created: { session_id: string } | null = null;
        try {
          created = await api.post<{ session_id: string }>("/api/sessions", {});
        } catch (e) {
          // 401 时创建会话同样会失败：不要留一个空白的"已连接"界面，
          // 直接给令牌入口（否则用户看到的是一句话都发不出去的聊天框）。
          if (isUnauthorized(e)) {
            set({ needToken: true });
            return;
          }
          throw e;
        }
        if (created?.session_id) await get().loadSession(created.session_id);
      } else {
        await get().loadSession(list[0].id);
      }
    },

    async refreshPendingApprovals() {
      try {
        const r = await getPendingApprovals();
        set({ pendingApprovals: r.pending ?? [] });
      } catch {
        // 轮询失败不打扰用户（网络抖动/服务重启），保留上一次结果
      }
    },

    async refreshSessions() {
      try {
        const list = await api.get<Session[]>("/api/sessions");
        set({ sessions: list });
        return list;
      } catch {
        return [];
      }
    },

    async loadSession(sid: string) {
      // 请求发出前先记代数：等待期间只要 send 插入过新消息（msgGen 自增），
      // 这次响应就是过期的，不能再清空或回填——否则会把刚插入的
      // 「用户消息 + 助手占位」整体抹掉，界面退回空态、只剩一条停在
      // 「正在思考…」的孤儿行（E2E 用例 3/4 偶发失败的真实原因）。
      const gen = msgGen;
      // 切会话必然换一份消息；但如果本次是同一会话的重复加载（新建后立刻发消息
      // 就属于这种），本地已经有的消息必须留着，不能用空数组盖掉。
      const sameSession = get().sessionId === sid;
      set((st) => ({
        sessionId: sid,
        messages: sameSession ? st.messages : [],
        todos: [],
        error: "",
      }));
      try {
        const msgs = await api.get<StoredMessage[]>(`/api/sessions/${sid}/messages`);
        const cur = get().sessionId;
        if (cur === sid && gen === msgGen) set({ messages: restore(msgs) });
      } catch {
        /* 会话可能已删 */
      }
      // 任务清单同样按会话恢复（后端落库；失败不影响聊天）
      try {
        const todos = await getTodos(sid);
        if (get().sessionId === sid) set({ todos });
      } catch {
        /* 旧后端没有该端点时静默忽略 */
      }
    },

    async clearTodos() {
      // 乐观清空 + 服务端同步：只清本地，刷新/切会话后会重新拉回来（"复活"）
      const sid = get().sessionId;
      if (!sid) {
        set({ todos: [] });
        return;
      }
      set({ todos: [] });
      try {
        await api.del(`/api/sessions/${sid}/todos`);
      } catch {
        // 旧后端没有该端点 / 请求失败：本地保持已清空，下次 loadSession 会拉回，不阻断用户
      }
    },

    // ---------- 发送 / 停止 ----------
    async send(text: string) {
      const t = text.trim();
      if (!t || !get().sessionId) return;
      const aid = uid("a");
      // 队列里已有在等/正在执行的请求时，新消息立即显示并排队
      const queued = running || sendQueue.length > 0;
      msgGen += 1;
      set((s) => ({
        messages: [
          ...s.messages,
          { id: uid("u"), role: "user", parts: [{ type: "text", text: t }], done: true, createdAt: Date.now() / 1000 },
          { id: aid, role: "assistant", parts: [], done: false, queued, createdAt: Date.now() / 1000 },
        ],
      }));
      sendQueue.push({ text: t, aid });
      // 统一交给驱动器：在跑就并入当前循环，没跑就起一轮（含退出瞬间入队的接力）
      await driveQueue();
    },

    async truncateFrom(msg: Msg) {
      const sid = get().sessionId;
      if (!sid) return 0;
      // 没有 serverId = 还没落库（本地刚插入），只需从本地移除，不必请求后端
      if (msg.serverId === undefined) {
        set((s) => ({ messages: s.messages.slice(0, s.messages.findIndex((m) => m.id === msg.id)) }));
        return 0;
      }
      if (running) get().stop();
      const r = await api.del<{ removed: number }>(`/api/sessions/${sid}/messages/${msg.serverId}`);
      set((s) => ({ messages: s.messages.slice(0, s.messages.findIndex((m) => m.id === msg.id)) }));
      return r?.removed ?? 0;
    },

    async deleteMessage(msg: Msg) {
      await get().truncateFrom(msg);
      void get().refreshSessions();
    },

    async regenerate(msg: Msg) {
      const sid = get().sessionId;
      if (!sid) return;
      // 找到该助手消息之前最近的一条用户消息，截断到它之前后原样重发
      const msgs = get().messages;
      const idx = msgs.findIndex((m) => m.id === msg.id);
      if (idx < 0) return;
      let userIdx = -1;
      for (let i = idx - 1; i >= 0; i--) {
        if (msgs[i].role === "user") { userIdx = i; break; }
      }
      if (userIdx < 0) return;
      const text = msgs[userIdx].parts
        .filter((p): p is Extract<Part, { type: "text" }> => p.type === "text")
        .map((p) => p.text)
        .join("\n");
      await get().truncateFrom(msgs[userIdx]);
      await get().send(text);
    },

    stop() {
      abortController?.abort();
      // 停止：同时清空排队中的消息，并移除对应占位
      sendQueue = [];
      set((s) => ({
        messages: s.messages.map((m) => (m.queued ? { ...m, done: true, error: "已停止" } : m)),
      }));
      void api.post("/api/stop", { session_id: get().sessionId }).catch(() => {});
    },

    // ---------- 会话操作 ----------
    async onNewSession() {
      const s = await api.post<{ session_id: string }>("/api/sessions", {});
      set({ showSessions: false });
      await get().loadSession(s.session_id);
      void get().refreshSessions();
    },

    async onDeleteSession(sid: string) {
      await api.del(`/api/sessions/${sid}`).catch(() => {});
      const list = await get().refreshSessions();
      if (sid === get().sessionId) {
        if (list.length === 0) {
          const s = await api.post<{ session_id: string }>("/api/sessions", {});
          await get().loadSession(s.session_id);
        } else {
          await get().loadSession(list[0].id);
        }
      }
    },

    async onRenameSession(sid: string, title: string) {
      if (!title.trim()) return;
      await renameSession(sid, title.trim()).catch(() => {});
      void get().refreshSessions();
    },

    async onPinSession(sid: string, pinned: boolean) {
      // 先本地乐观更新（点一下立刻有反馈），失败后 refreshSessions 拉回真值
      set((s) => ({
        sessions: s.sessions.map((x) => (x.id === sid ? { ...x, pinned: pinned ? 1 : 0 } : x)),
      }));
      try {
        await pinSession(sid, pinned);
      } catch {
        /* 失败静默：refreshSessions 会把状态纠正回来 */
      }
      void get().refreshSessions();
    },

    // ---------- 审批 / 撤销 / 导出 ----------
    async decideBackgroundApproval(sessionId, toolCallId, decision) {
      // 必须显式传该审批所属的会话：后台审批属于触发它的那个会话，
      // 用「当前打开的会话」提交会让后端找不到 gate（静默无效，用户点了没反应）。
      // 返回是否真正提交成功：失败时卡片必须保留（服务端的 gate 还开着），
      // 藏掉卡片就退回到「静默失败」——审批只能等超时被自动拒绝。
      let sent = false;
      try {
        await api.post("/api/approval", {
          session_id: sessionId,
          tool_call_id: toolCallId,
          decision,
        });
        sent = true;
        vibrate(20);
      } catch (e) {
        // 404 = 已超时/已被处理：卡片消失是正确行为，不算提交失败
        const status = (e as ApiError).status;
        if (status !== 404) set({ error: (e as Error).message || "审批提交失败，请重试" });
      } finally {
        await get().refreshPendingApprovals();
      }
      return sent;
    },

    async onApproval(toolCallId, decision) {
      try {
        await api.post("/api/approval", {
          session_id: get().sessionId,
          tool_call_id: toolCallId,
          decision,
        });
        vibrate(20); // 手机上给一次轻振动：确认"我的决定已生效"（浏览器环境无操作）
        // 拒绝类决策（deny / deny_always）不把卡片置为运行中
        if (!decision.startsWith("deny")) {
          set((s) => ({
            messages: s.messages.map((m) => ({
              ...m,
              parts: m.parts.map((p) =>
                p.type === "tool" && p.tool.id === toolCallId
                  ? {
                      ...p,
                      tool: {
                        ...p.tool,
                        status: "running" as const,
                        // 审批等待已作废起表（partReducer 的 approval 分支），
                        // 批准后从决策时刻重新计时：耗时只含执行段，不含用户思考段。
                        // 未走审批的工具 startedAt 仍在，保持原值不动。
                        startedAt: p.tool.startedAt ?? Date.now(),
                        startedFor: "running" as const,
                      },
                    }
                  : p
              ),
            })),
          }));
        }
      } catch (e) {
        set({ error: (e as Error).message || "审批提交失败" });
      }
    },

    async onUndo(toolCallId: string) {
      if (!get().sessionId) return;
      set({ undoingId: toolCallId });
      try {
        const r = await undoToolCall(get().sessionId, toolCallId);
        if (r.ok && r.restored) {
          set((s) => ({
            messages: s.messages.map((m) => ({
              ...m,
              parts: m.parts.map((p) =>
                p.type === "tool" && p.tool.id === toolCallId
                  ? { ...p, tool: { ...p.tool, undoable: false, result: { ...p.tool.result, "已撤销": r.restored } } }
                  : p
              ),
            })),
          }));
          set({ error: "" });
          window.alert("已还原：" + r.restored);
        } else {
          set({ error: r.error || "撤销失败" });
        }
      } catch (e) {
        set({ error: (e as Error).message || "撤销失败" });
      } finally {
        set({ undoingId: null });
      }
    },

    async onExport() {
      try {
        const data = await exportAll();
        const json = JSON.stringify(data, null, 2);
        const fileName = `pocket-agent-backup-${new Date().toISOString().slice(0, 10)}.json`;

        // Android 壳：blob + <a download> 在 WebView 里不会触发下载
        // （DownloadListener 收不到 blob: URL），必须走原生保存。
        const saved = saveTextFile(fileName, json);
        if (saved) {
          set({ error: "" });
          return;
        }

        // 非壳环境（真浏览器）：照常走下载。
        // 注意：壳内 saveTextFile 返回 null（桥失败/权限被拒）时走不到这里的
        // "浏览器下载也行"——壳内 blob 同样不生效，必须显式报错而不是静默无反应。
        if (isNativeShell()) {
          set({ error: "导出失败：原生保存不可用，请重启应用后重试" });
          return;
        }

        const blob = new Blob([json], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = fileName;
        a.click();
        URL.revokeObjectURL(url);
      } catch (e) {
        set({ error: (e as Error).message || "导出失败" });
      }
    },

    // ---------- 错误清除 ----------
    clearError: () => set({ error: "" }),

    // ---------- UI 开关 ----------
    setShowSessions: (v) => set({ showSessions: v }),
    setShowSettings: (v) => set({ showSettings: v }),
    setShowTimers: (v) => set({ showTimers: v }),
    setTheme: (t) => {
      set({ theme: t });
      setThemePref(t);
    },

    // ---------- Plan/Act 模式 ----------
    setWebSearch: (v) => {
      // 只存本地偏好：这是「这一次要不要外发查询」的选择，不该悄悄写进服务端配置
      set({ webSearch: v });
    },

    setAgentMode: (m) => {
      // 乐观更新本地状态；后端 settings.save 是合并写入，局部 PUT 不会冲掉其他配置
      set({ agentMode: m });
      void api.put("/api/settings", { agent_mode: m }).catch(() => {});
    },

    async approvePlan(assistantMsgId: string) {
      const list = get().messages;
      const idx = list.findIndex((m) => m.id === assistantMsgId);
      // 向上找到触发该计划的最近一条用户消息，批准后原样重发一轮
      let userText = "";
      for (let i = idx - 1; i >= 0; i--) {
        const m = list[i];
        if (m.role === "user") {
          userText = m.parts
            .filter((p): p is Extract<Part, { type: "text" }> => p.type === "text")
            .map((p) => p.text)
            .join("\n");
          break;
        }
      }
      if (!userText) return;
      // 卡片置为「已批准」态：按钮变展示态，防止重复点击
      set((st) => ({
        messages: st.messages.map((m) =>
          m.id === assistantMsgId
            ? {
                ...m,
                parts: m.parts.map((p) => (p.type === "plan" ? { ...p, approved: true } : p)),
              }
            : m
        ),
      }));
      // 切到 act 模式（同步持久化），再把用户原话发出去真正执行
      get().setAgentMode("act");
      await get().send(userText);
    },

    editPlan: (_assistantMsgId: string) => {
      // 仅把输入框聚焦回来，让用户补充/修改后重发；卡片保留在历史里
      // _assistantMsgId 预留：未来可针对该计划做上下文回填
      window.dispatchEvent(new CustomEvent("agent:focus-composer"));
    },
    ignorePlan: (assistantMsgId: string) => {
      set((st) => ({
        messages: st.messages.map((m) =>
          m.id === assistantMsgId
            ? { ...m, parts: m.parts.map((p) => (p.type === "plan" ? { ...p, ignored: true } : p)) }
            : m
        ),
      }));
    },
  };
};

export const useAgentStore = create<AgentStore>()(
  persist(_storeCreator, {
    name: "pocketmind-ui",
    storage: createJSONStorage(() => localStorage),
    // 只持久化界面偏好（主题/模式/设置/Provider标签），消息与会话始终从服务端恢复
    partialize: (s) => ({
      theme: s.theme,
      agentMode: s.agentMode,
      providerLabel: s.providerLabel,
      webSearch: s.webSearch,
      permissionMode: s.permissionMode,
      settings: s.settings,
    }),
    version: 1,
    migrate: (): Partial<AgentStore> => ({ theme: "system", agentMode: "act", providerLabel: "", permissionMode: "approve", settings: null }),
  })
);

