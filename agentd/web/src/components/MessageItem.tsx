import { useEffect, useMemo, useRef, useState, memo } from "react";
import { marked } from "marked";
import DOMPurify from "dompurify";
import type { Msg, Part, ToolPart } from "../types";
import type { ApprovalDecision } from "../api";
import { IconChevron } from "./icons";
import { formatToolResult } from "../lib/format";
import { structuredResult } from "../lib/structuredResult";
import { StructuredResult } from "./StructuredResult";
import { copyText } from "../native";
import { PlanCard } from "./PlanCard";
import { PocketLogo } from "./PocketLogo";

/* ---------- Markdown（含代码块/表格，DOMPurify 消毒） ---------- */

// 一次性注册 DOMPurify 钩子：消毒后强制给所有 <a> 加 rel="noopener noreferrer"，
// 防止 LLM 输出里的外链 target=_blank 带来的反向 tabnabbing（window.opener 泄漏）。
DOMPurify.addHook("afterSanitizeAttributes", (node: Element) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

export function Markdown({ text }: { text: string }) {
  const html = useMemo(
    // marked v12 的 parse 在 async 模式下返回 Promise；显式 { async: false } 确保同步返回 string，
    // 不再用 `as string` 类型撒谎
    () =>
      DOMPurify.sanitize(marked.parse(text, { async: false }) as string, {
        // 允许 target 属性（钩子会统一加），rel 由钩子写入
        ADD_ATTR: ["target", "rel"],
      }),
    [text]
  );
  return <div className="md" dangerouslySetInnerHTML={{ __html: html }} />;
}

/* ---------- 思考块 ---------- */

function ThinkingBlock({ text, streaming, forceOpen }: { text: string; streaming: boolean; forceOpen?: boolean }) {
  const [open, setOpen] = useState<boolean>(streaming || forceOpen || false);
  const bodyId = useMemo(() => `think-${Math.random().toString(36).slice(2, 8)}`, []);
  if (!text.trim()) return null;
  return (
    <div className={`thinking ${open ? "open" : ""}`}>
      <button
        className="thinking-head"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={bodyId}
      >
        {streaming && !open ? <span className="dot-blink" /> : <span className="dot" />}
        <span>{open ? "思考过程" : "思考过程 · 已收起"}</span>
        <IconChevron open={open} />
      </button>
      {open && (
        <div className="thinking-body" id={bodyId}>
          {text}
        </div>
      )}
    </div>
  );
}

/* ---------- 工具卡片（含审批） ---------- */

// 审批默认超时（秒）兜底：真实值来自后端 settings.server.approval_timeout
// （经 App → MessageItem → ToolCard 传入），前端不再硬编码唯一真相。
const APPROVAL_TIMEOUT_FALLBACK = 120;

// 把秒级时间戳格式化为 HH:MM（历史消息缺时间时返回空串）
function formatTime(ts?: number): string {
  if (!ts) return "";
  const d = new Date(ts * 1000);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

// 少数工具的内部名对用户毫无意义，给中文标签；其余保持原名（技术用户认这个）
// 工具内部名 → 中文标签。覆盖全部 39 个工具：审批卡与工具卡都会显示它，
// 留英文名会让非技术用户看不懂自己正在批准什么（截图巡检发现 run_shell 裸露）。
const TOOL_LABEL: Record<string, string> = {
  // agent / 编排
  subagent: "子代理",
  todo_write: "任务清单",
  todo_clear: "清空任务清单",
  // 文件
  list_dir: "列出目录",
  read_file: "读取文件",
  write_file: "写入文件",
  delete_file: "删除文件",
  // 知识库 / 记忆
  kb_add: "存入知识库",
  kb_search: "检索知识库",
  kb_list: "知识库列表",
  remember_fact: "记住一件事",
  read_skill: "读取技能",
  // 联网
  web_search: "联网搜索",
  read_url: "读取网页",
  // 系统
  run_shell: "执行命令",
  // 手机：通信
  send_sms: "发送短信",
  read_sms: "读取短信",
  make_call: "拨打电话",
  // 手机：媒体
  take_photo: "拍照",
  share_text: "分享文本",
  download_file: "下载文件",
  open_target: "打开链接",
  // 手机：网络
  get_wifi_info: "WiFi 信息",
  scan_wifi: "扫描 WiFi",
  set_wifi: "开关 WiFi",
  // 手机：位置 / 传感器
  get_location: "获取定位",
  list_sensors: "列出传感器",
  read_sensor: "读取传感器",
  // 手机：系统
  get_clipboard: "读取剪贴板",
  set_clipboard: "写入剪贴板",
  get_battery: "查看电量",
  send_notification: "发送通知",
  show_toast: "显示提示",
  vibrate: "震动",
  set_torch: "手电筒",
  get_volume: "查看音量",
  set_volume: "调节音量",
  set_brightness: "调节亮度",
  // 语音
  tts_speak: "语音朗读",
  tts_offline: "离线朗读",
  speech_to_text: "语音转文字",
};

const STATUS_LABEL: Record<ToolPart["status"], string> = {
  running: "运行中",
  waiting: "需要确认",
  completed: "完成",
  failed: "失败",
  denied: "已拒绝",
};

function ToolCard({
  tool,
  onApproval,
  onUndo,
  undoing,
  approvalTimeout = APPROVAL_TIMEOUT_FALLBACK,
}: {
  tool: ToolPart;
  onApproval: (id: string, d: ApprovalDecision) => void;
  onUndo?: (id: string) => void;
  undoing?: boolean;
  approvalTimeout?: number;
}) {
  const [open, setOpen] = useState(tool.status === "waiting");
  const [copied, setCopied] = useState(false);
  const bodyId = useMemo(() => `tool-${tool.id}-${Math.random().toString(36).slice(2, 6)}`, [tool.id]);
  const inputText =
    typeof tool.input === "string"
      ? tool.input
      : JSON.stringify(tool.input ?? {}, null, 2);
  // 结果优先按「键：值」逐行展示（手机窄屏比 raw JSON 好读得多），无法结构化时退回 JSON
  const resultText = tool.result ? formatToolResult(tool.result) : "";
  // 列表型结果（kb_search / kb_list / 任务列表…）映射成表格/卡片视图；
  // 映射不了（扁平 kv / 错误）保持原有文本渲染，行为不变。
  const structured = tool.result ? structuredResult(tool.name, tool.result) : null;
  const waiting = tool.status === "waiting";
  const [countdown, setCountdown] = useState(approvalTimeout);

  // 状态变为「需要确认」时自动展开：
  // 审批按钮与倒计时必须出现在用户眼前——不能指望用户注意到小徽标再手动点开。
  // 早先只在挂载时判一次 tool.status === "waiting"，而工具卡通常先以 running 挂载
  // （tool_start → approval 两个事件分属不同渲染批次），于是卡片停在折叠态、
  // 审批按钮被藏在里面；更糟的是等待会超时自动拒绝。是否展开因此变得「看时序」。
  useEffect(() => {
    if (tool.status === "waiting") setOpen(true);
  }, [tool.status]);

  // 审批等待倒计时：status 变为 waiting 时从后端配置的超时值递减，超时后服务端自动拒绝
  useEffect(() => {
    if (tool.status !== "waiting") return;
    setCountdown(approvalTimeout);
    const timer = window.setInterval(() => {
      setCountdown((c) => (c > 0 ? c - 1 : 0));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [tool.status, tool.id, approvalTimeout]);

  const copyResult = async () => {
    // 统一走 native.copyText：WebView 里 navigator.clipboard 需要 HTTPS，
    // APK 壳提供了原生剪贴板桥；浏览器环境自动降级到 Clipboard API。
    const okCopy = await copyText(resultText);
    if (okCopy) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    }
  };

  return (
    <div className={`tool ${tool.status}`}>
      <button
        className="tool-head"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={bodyId}
      >
        <span className={`tool-dot ${tool.status}`} />
        <span className="tool-name">{TOOL_LABEL[tool.name] || tool.name}</span>
        <span className={`tool-status ${tool.status}`}>{STATUS_LABEL[tool.status]}</span>
        {typeof tool.ms === "number" && tool.ms >= 300 && (
          <span className="tool-ms" title="执行耗时">
            {tool.ms >= 1000 ? `${(tool.ms / 1000).toFixed(1)}s` : `${Math.round(tool.ms)}ms`}
          </span>
        )}
        <IconChevron open={open} />
      </button>
      {open && (
        <div className="tool-body" id={bodyId}>
          <pre className="tool-input">$ {inputText}</pre>
          {waiting && (
            <div className="approval">
              <p className="approval-summary">
                <b>{TOOL_LABEL[tool.name] || tool.name}</b>
                {tool.risk === "danger" ? <span className="risk-tag">危险操作</span> : null}
                <span className="approval-what">{tool.summary || "需要你确认"}</span>
              </p>
              <div className="approval-btns">
                <button className="btn ok" onClick={() => onApproval(tool.id, "allow_once")}>允许一次</button>
                <button className="btn" onClick={() => onApproval(tool.id, "allow_always")}>始终允许</button>
                <button className="btn danger" onClick={() => onApproval(tool.id, "deny")}>拒绝</button>
              </div>
              <div className="approval-btns secondary">
                <button
                  className="btn sm"
                  onClick={() => onApproval(tool.id, "allow_global")}
                  title="所有会话都不再询问该工具"
                >
                  全局允许
                </button>
                <button
                  className="btn sm danger"
                  onClick={() => onApproval(tool.id, "deny_always")}
                  title="永久拒绝该工具（可在设置→权限名单里撤销）"
                >
                  始终拒绝
                </button>
              </div>
              <p className="approval-note">
                {countdown > 0 ? (
                  <span className="approval-countdown">不点击将在 {countdown} 秒后自动拒绝。</span>
                ) : (
                  "已超时，正在自动拒绝。"
                )}{' '}
                「始终允许」只对当前会话生效；「全局允许 / 始终拒绝」对所有会话生效。
              </p>
            </div>
          )}
          {!waiting && tool.result && (
            <div className="tool-result-wrap">
              {structured ? (
                <StructuredResult view={structured} />
              ) : (
                <pre className="tool-result">{resultText}</pre>
              )}
              <button className="tool-copy" onClick={() => void copyResult()} title="复制结果">
                {copied ? "已复制" : "复制"}
              </button>
            </div>
          )}
          {!waiting && tool.undoable && onUndo && (
            <div className="undo-bar">
              <button className="btn undo" disabled={undoing} onClick={() => onUndo(tool.id)}>
                {undoing ? "正在撤销…" : "撤销此操作"}
              </button>
              <span className="undo-note">执行前已自动备份，可安全还原</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ---------- 消息条目 ---------- */

function MessageItemImpl({
  msg,
  parts,
  onApproval,
  onUndo,
  undoingId,
  onApprovePlan,
  onEditPlan,
  onIgnorePlan,
  approvalTimeout,
  onDelete,
  onRegenerate,
  onEditUser,
}: {
  msg: Msg;
  parts: Part[];
  onApproval: (id: string, d: ApprovalDecision) => void;
  onUndo?: (id: string) => void;
  undoingId?: string | null;
  onApprovePlan: (assistantMsgId: string) => void;
  onEditPlan: (assistantMsgId: string) => void;
  onIgnorePlan: (assistantMsgId: string) => void;
  approvalTimeout?: number;
  /** 删除这条消息及其之后的全部历史（对话必须保持配对完整） */
  onDelete?: (msg: Msg) => void;
  /** 用上一条用户消息重新生成（仅 assistant 消息） */
  onRegenerate?: (msg: Msg) => void;
  /** 把该用户消息放回输入框编辑（随之截断后续历史） */
  onEditUser?: (msg: Msg, text: string) => void;
}) {
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 卸载时清理「已复制」延迟，避免 setState on unmounted component
  useEffect(() => {
    return () => {
      if (copyTimer.current) clearTimeout(copyTimer.current);
    };
  }, []);

  const handleCopy = async () => {
    const raw = parts
      .filter((p): p is Extract<Part, { type: "text" }> => p.type === "text")
      .map((p) => p.text)
      .join("\n");
    const okCopy = await copyText(raw);
    if (okCopy) {
      setCopied(true);
      if (copyTimer.current) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 1200);
    }
  };

  if (msg.role === "user") {
    const text = parts
      .filter((p): p is Extract<Part, { type: "text" }> => p.type === "text")
      .map((p) => p.text)
      .join("\n");
    return (
      <div className="row user">
        <div className="bubble user">{text}</div>
        <div className="msg-actions">
          {formatTime(msg.createdAt) && (
            <span className="msg-time">{formatTime(msg.createdAt)}</span>
          )}
          <button className="msg-action" onClick={handleCopy} title="复制" aria-label="复制用户消息">
            {copied ? "已复制" : "复制"}
          </button>
          <button
            className="msg-action"
            onClick={() => onEditUser?.(msg, text)}
            title="编辑并重新发送"
            aria-label="编辑这条消息"
          >
            编辑
          </button>
          <button
            className="msg-action danger"
            onClick={() => onDelete?.(msg)}
            title="删除这条消息及其之后的对话"
            aria-label="删除这条消息"
          >
            删除
          </button>
        </div>
      </div>
    );
  }
  const hasContent = parts.length > 0;
  // 有工具在等审批时，自动展开同消息的思考块，让用户看到模型为什么发起该操作
  const waitingApproval = parts.some((p) => p.type === "tool" && p.tool.status === "waiting");
  return (
    <div className="row agent">
      <div className="agent-head">
        <span className="avatar">
          <PocketLogo size={26} />
        </span>
        <span className="agent-name">{msg.done ? "口袋智灵" : "正在思考…"}</span>
        {formatTime(msg.createdAt) && <span className="msg-time">{formatTime(msg.createdAt)}</span>}
      </div>
      {msg.error && <div className="err-banner">{msg.error}</div>}
      {msg.queued && <div className="err-banner queued-banner">已排队：上一轮回复还在进行中，这条将在完成后自动执行。</div>}
      {parts.map((p, i) =>
        p.type === "thinking" ? (
          <ThinkingBlock key={i} text={p.text} streaming={!msg.done} forceOpen={waitingApproval} />
        ) : p.type === "tool" ? (
          <ToolCard
            key={p.tool.id}
            tool={p.tool}
            onApproval={onApproval}
            onUndo={onUndo}
            undoing={undoingId === p.tool.id}
            approvalTimeout={approvalTimeout}
          />
        ) : p.type === "plan" ? (
          <PlanCard
            key={i}
            plan={p.plan}
            toolCalls={p.tool_calls}
            approved={p.approved}
            ignored={p.ignored}
            onApprove={() => onApprovePlan(msg.id)}
            onEdit={() => onEditPlan(msg.id)}
            onIgnore={() => onIgnorePlan(msg.id)}
          />
        ) : p.text.trim() ? (
          <div className="bubble agent" key={i}>
            <Markdown text={p.text} />
          </div>
        ) : null
      )}
      {hasContent && msg.done && (
        <div className="msg-actions">
          <button className="msg-action" onClick={handleCopy} title="复制" aria-label="复制助手消息">
            {copied ? "已复制" : "复制"}
          </button>
          <button
            className="msg-action"
            onClick={() => onRegenerate?.(msg)}
            title="用同一条提问重新生成"
            aria-label="重新生成这条回复"
          >
            重新生成
          </button>
          <button
            className="msg-action danger"
            onClick={() => onDelete?.(msg)}
            title="删除这条回复及其之后的对话"
            aria-label="删除这条回复"
          >
            删除
          </button>
          {msg.usage && msg.usage.total_tokens > 0 && (
            <span
              className="msg-usage"
              title={`输入 ${msg.usage.prompt_tokens} · 输出 ${msg.usage.completion_tokens}${msg.usage.estimated ? "（估算）" : ""}`}
            >
              {msg.usage.total_tokens.toLocaleString()} tokens{msg.usage.estimated ? " · 估" : ""}
            </span>
          )}
        </div>
      )}
      {!hasContent && !msg.done && (
        <div className="dots">
          <i />
          <i />
          <i />
        </div>
      )}
    </div>
  );
}

/**
 * 用 memo 包住：流式输出时每一帧都会触发 App 重渲染，若不 memo，
 * 200 条历史消息会跟着逐字重渲染（实测长对话明显掉帧）。
 * 只有当该条消息的 msg/parts 或交互回调变化时才重渲染。
 */
export const MessageItem = memo(MessageItemImpl);
