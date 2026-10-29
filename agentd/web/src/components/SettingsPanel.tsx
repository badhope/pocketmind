import { useEffect, useState, type ReactNode } from "react";
import { BatteryGuide } from "./BatteryGuide";
import { DiagnosticsPanel } from "./DiagnosticsPanel";
import {
  api,
  clearApprovalRules,
  compactSession,
  deleteKb,
  getApprovalRules,
  getProfile,
  getSkills,
  getStats,
  importAll,
  isUnauthorized,
  listKb,
  putProfile,
  setToken,
  type AppSettings,
  type ApprovalRules,
  type KbItem,
  type Provider,
  type SkillInfo,
  type StatsPayload,
  type ToolInfo,
} from "../api";
import { useAgentStore } from "../store/useAgentStore";
import type { ThemePref } from "../theme";
import { CollapsibleGroup } from "./CollapsibleGroup";
import { IconClose } from "./icons";

const MODES: Array<{ v: "auto" | "approve" | "chat"; label: string; hint: string }> = [
  { v: "auto", label: "全自动", hint: "所有工具直接执行（不推荐）" },
  { v: "approve", label: "逐次审批", hint: "写文件/危险操作需你确认（推荐）" },
  { v: "chat", label: "纯聊天", hint: "不调用任何工具" },
];

// 智能体工作模式（Cline 式 Plan/Act）：
// plan = 先输出执行计划待你批准；act = 收到任务直接执行
const AGENT_MODES: Array<{ v: "plan" | "act"; label: string; hint: string }> = [
  { v: "plan", label: "先计划", hint: "先给出执行计划，你批准后再动手" },
  { v: "act", label: "直接执行", hint: "收到任务即开始调用工具" },
];

const RISK_LABEL: Record<string, string> = {
  safe: "只读",
  write: "需确认",
  danger: "危险",
};

/** memory 配置块的默认值（与后端 DEFAULTS.memory 对齐）：局部编辑时保持其余字段不丢。 */
const MEMORY_DEFAULTS = {
  auto_summarize: true,
  summarize_every_n_turns: 10,
  summary_max_chars: 400,
  search_limit: 5,
  fts_page_size: 50,
  retention_days: 90,
};

/** 工具分组展示顺序与名称 */
const GROUP_NAMES: Array<{ id: string; label: string }> = [
  { id: "phone", label: "手机能力" },
  { id: "files", label: "文件" },
  { id: "agent", label: "任务与编排" },
  { id: "knowledge", label: "知识库" },
  { id: "memory", label: "长期记忆" },
  { id: "skills", label: "技能" },
  { id: "system", label: "系统" },
  { id: "voice", label: "语音" },
  { id: "mcp", label: "MCP 扩展" },
  { id: "other", label: "其他" },
];

const THEMES: Array<{ v: ThemePref; label: string; swatch: ReactNode }> = [
  {
    v: "dark",
    label: "深色",
    swatch: (
      <svg width="46" height="30" viewBox="0 0 46 30">
        <rect width="46" height="30" rx="7" fill="#161922" />
        <rect x="6" y="7" width="20" height="4" rx="2" fill="#e4e7ec" opacity="0.9" />
        <rect x="6" y="15" width="34" height="4" rx="2" fill="#9aa3b2" opacity="0.5" />
        <rect x="6" y="22" width="14" height="3" rx="1.5" fill="#10b981" />
      </svg>
    ),
  },
  {
    v: "light",
    label: "浅色",
    swatch: (
      <svg width="46" height="30" viewBox="0 0 46 30">
        <rect width="46" height="30" rx="7" fill="#ffffff" stroke="#e0e5ec" />
        <rect x="6" y="7" width="20" height="4" rx="2" fill="#1a1f2a" opacity="0.85" />
        <rect x="6" y="15" width="34" height="4" rx="2" fill="#5a6472" opacity="0.4" />
        <rect x="6" y="22" width="14" height="3" rx="1.5" fill="#0d9d6e" />
      </svg>
    ),
  },
  {
    v: "system",
    label: "跟随系统",
    swatch: (
      <svg width="46" height="30" viewBox="0 0 46 30">
        <rect width="23" height="30" rx="7" fill="#161922" />
        <rect x="23" width="23" height="30" rx="7" fill="#ffffff" stroke="#e0e5ec" />
        <rect x="6" y="7" width="14" height="4" rx="2" fill="#e4e7ec" opacity="0.9" />
        <rect x="28" y="7" width="12" height="4" rx="2" fill="#1a1f2a" opacity="0.85" />
        <rect x="6" y="15" width="16" height="4" rx="2" fill="#9aa3b2" opacity="0.5" />
        <rect x="28" y="15" width="12" height="4" rx="2" fill="#5a6472" opacity="0.4" />
        <rect x="6" y="22" width="10" height="3" rx="1.5" fill="#10b981" />
        <rect x="28" y="22" width="10" height="3" rx="1.5" fill="#0d9d6e" />
      </svg>
    ),
  },
];

export function SettingsPanel({
  onClose,
  mock,
  theme,
  onTheme,
  onExport,
}: {
  onClose: () => void;
  mock: boolean;
  theme: ThemePref;
  onTheme: (t: ThemePref) => void;
  onExport: () => void;
}) {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [form, setForm] = useState<AppSettings | null>(null);
  const [tools, setTools] = useState<ToolInfo[]>([]);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState("");
  const [showKey, setShowKey] = useState(false);
  // 401 时不再无限停在「加载中…」：给出令牌输入口，写入本地后重新拉取
  const [needToken, setNeedToken] = useState(false);
  const [tokenInput, setTokenInput] = useState("");
  // 用户是否真的编辑过「访问令牌」输入框。
  // 必要性：GET /api/settings 回传的是打码值（如 to*********56），
  // 若保存时无条件 setToken(打码值)，本地凭据就被替换成假令牌 → 之后所有请求 401，
  // 且每次保存都会复发，用户被自己锁在门外（只能清 localStorage 才恢复）。
  const [tokenDirty, setTokenDirty] = useState(false);
  const [loadError, setLoadError] = useState("");
  // 结构化长期记忆（USER.md / FACT.md）：独立于「保存」按钮，即时写入
  const [profileForm, setProfileForm] = useState<{ user: string; facts: string } | null>(null);
  const [profileMsg, setProfileMsg] = useState("");
  const [profileSaving, setProfileSaving] = useState(false);
  // 审批权限名单（全局白名单 / 黑名单 / 会话白名单）
  const [rules, setRules] = useState<ApprovalRules | null>(null);
  // 知识库与技能（能力面）：只读浏览 + 删除；条目写入只经 kb_add 工具（走审批）
  const [kbItems, setKbItems] = useState<KbItem[]>([]);
  const [kbMsg, setKbMsg] = useState("");
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [skillsDir, setSkillsDir] = useState("");
  // 运行统计（真实计数器，不是配置回显）与备份导入状态
  const [stats, setStats] = useState<StatsPayload | null>(null);
  const [importMsg, setImportMsg] = useState("");
  const [importing, setImporting] = useState(false);

  // Plan/Act 模式直接走 store：切换即持久化（局部 PUT，不随「保存」按钮走）
  const agentMode = useAgentStore((s) => s.agentMode);
  const setAgentMode = useAgentStore((s) => s.setAgentMode);
  // 手动压缩当前会话：需要会话 id 与压缩后重载消息
  const sessionId = useAgentStore((s) => s.sessionId);
  const loadSession = useAgentStore((s) => s.loadSession);
  const [compacting, setCompacting] = useState(false);
  const [compactMsg, setCompactMsg] = useState("");

  const load = async () => {
    setLoadError("");
    const [s, p, t] = await Promise.allSettled([
      api.get<AppSettings>("/api/settings"),
      api.get<{ providers: Provider[] }>("/api/providers"),
      api.get<{ tools: ToolInfo[] }>("/api/tools"),
    ]);
    if (s.status === "fulfilled") {
      setForm(s.value);
      setNeedToken(false);
    } else if (isUnauthorized(s.reason)) {
      setNeedToken(true);
    } else {
      setLoadError((s.reason as Error)?.message || "设置加载失败");
    }
    if (p.status === "fulfilled") setProviders(p.value.providers);
    if (t.status === "fulfilled") setTools(t.value.tools);
    // 记忆档案与权限名单：失败不影响设置面板主流程
    try {
      const pf = await getProfile();
      setProfileForm({ user: pf.user, facts: pf.facts });
    } catch {
      /* 旧后端无该端点时静默 */
    }
    try {
      const r = await getApprovalRules();
      // 归一化：旧后端/异常响应可能缺字段，缺了就是空名单——不能让它炸掉整个面板
      setRules({ global: r?.global ?? [], deny: r?.deny ?? [], sessions: r?.sessions ?? {} });
    } catch {
      /* 同上 */
    }
    // 知识库与技能：能力面只读浏览
    try {
      setKbItems(await listKb());
    } catch {
      setKbItems([]);
    }
    try {
      const sk = await getSkills();
      // 归一化：响应缺字段时退化为空列表（旧后端/异常响应不能让面板崩）
      setSkills(Array.isArray(sk?.skills) ? sk.skills : []);
      setSkillsDir(typeof sk?.dir === "string" ? sk.dir : "");
    } catch {
      setSkills([]);
    }
    // 运行统计：拿不到就保持 null（面板显示「暂无数据」而不是编造 0）。
    // 归一化 metrics 结构：旧后端/异常响应缺字段时不能让它把整个设置面板炸掉
    // （nextTick 里 stats.metrics.llm.prompt_tokens 是直接解引用）。
    try {
      const raw = await getStats();
      if (raw && raw.metrics && raw.metrics.llm && raw.metrics.requests && raw.metrics.tools) {
        setStats(raw);
      } else {
        setStats(null);
      }
    } catch {
      setStats(null);
    }
  };

  /** 选择备份文件并导入（合并语义，不覆盖本机数据）。 */
  const pickImport = () => {
    setImportMsg("");
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "application/json,.json";
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file) return;
      setImporting(true);
      try {
        const text = await file.text();
        let data: Record<string, unknown>;
        try {
          data = JSON.parse(text) as Record<string, unknown>;
        } catch {
          setImportMsg("不是合法的 JSON 文件");
          return;
        }
        const r = await importAll(data);
        const im = r.imported || {};
        setImportMsg(
          `已恢复：会话 ${im.sessions ?? 0} 个（跳过 ${im.sessions_skipped ?? 0}）、` +
            `消息 ${im.messages ?? 0} 条、知识库 ${im.knowledge ?? 0} 条、定时任务 ${im.jobs ?? 0} 个`
        );
        // 数据变了，重新拉会话列表与统计
        await useAgentStore.getState().refreshSessions();
        try {
          setStats(await getStats());
        } catch {
          /* 统计刷新失败不影响导入结果 */
        }
      } catch (e) {
        setImportMsg((e as Error).message || "导入失败");
      } finally {
        setImporting(false);
      }
    };
    input.click();
  };

  /** 立即压缩当前会话：把较早历史归档成摘要，保留最近若干条原文。 */
  const compactNow = async () => {
    if (!sessionId) {
      setCompactMsg("先开始一个会话再压缩");
      return;
    }
    setCompacting(true);
    setCompactMsg("");
    try {
      const r = await compactSession(sessionId, 10);
      if (r.ok) {
        setCompactMsg(`已归档 ${r.archived_messages ?? 0} 条，保留最近 ${r.kept ?? 10} 条`);
        await loadSession(sessionId);
      } else {
        setCompactMsg(r.reason || "无需压缩");
      }
    } catch (e) {
      setCompactMsg((e as Error).message || "压缩失败");
    } finally {
      setCompacting(false);
    }
  };

  /** 删除知识库条目：不可逆（无备份），必须二次确认。 */
  const removeKb = async (item: KbItem) => {
    if (!window.confirm(`删除知识库条目「${item.title}」？此操作不可撤销。`)) return;
    setKbMsg("");
    try {
      await deleteKb(item.id);
      setKbItems((list) => list.filter((k) => k.id !== item.id));
      setKbMsg("已删除");
    } catch (e) {
      setKbMsg((e as Error).message || "删除失败");
    }
  };

  /** 立即写入记忆档案（不走「保存」按钮：人设/事实是独立文件，即时生效）。 */
  const saveProfile = async () => {
    if (!profileForm) return;
    setProfileSaving(true);
    setProfileMsg("");
    try {
      const r = await putProfile(profileForm);
      setProfileForm({ user: r.user, facts: r.facts });
      setProfileMsg("已保存（下一轮对话生效）");
    } catch (e) {
      setProfileMsg((e as Error).message || "保存失败");
    } finally {
      setProfileSaving(false);
    }
  };

  const clearRule = async (scope: "deny" | "global" | `session:${string}`, tool = "") => {
    try {
      const r = await clearApprovalRules(scope, tool);
      setRules(r.rules);
    } catch {
      /* 忽略：下次打开会重新拉取 */
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!form) {
    return (
      <div className="overlay" onClick={onClose}>
        <div className="sheet" onClick={(e) => e.stopPropagation()}>
          <div className="sheet-head">
            <h2>设置</h2>
            <button className="icon-btn" onClick={onClose} aria-label="关闭">
              <IconClose />
            </button>
          </div>
          {needToken ? (
            <CollapsibleGroup title="需要访问令牌" defaultOpen>
              <p className="hint">
                本服务已设置访问令牌（局域网保护）。在这里输入一次令牌即可打开设置；
                忘记令牌可在设备上执行 <code>python -m agentd.main --clear-token</code> 清除。
              </p>
              <div className="field">
                <label>访问令牌</label>
                <input
                  value={tokenInput}
                  onChange={(e) => setTokenInput(e.target.value)}
                  placeholder="与设置中一致的服务令牌"
                />
              </div>
              <button
                className="btn"
                onClick={async () => {
                  setToken(tokenInput.trim());
                  await load();
                }}
              >
                解锁设置
              </button>
            </CollapsibleGroup>
          ) : (
            <p className="center-muted">{loadError || "加载中…"}</p>
          )}
        </div>
      </div>
    );
  }

  const pickProvider = (id: string) => {
    const p = providers.find((x) => x.id === id);
    if (!p) return;
    setForm((f) => (f ? { ...f, llm: { ...f.llm, provider: id, base_url: p.base_url, model: p.model } } : f));
  };

  const testConnection = async () => {
    setTesting(true);
    setTestResult("");
    try {
      const r = await api.post<{ ok: boolean; mode?: string; model?: string; error?: string }>(
        "/api/llm/test"
      );
      if (r.ok) {
        setTestResult(r.model ? `连接成功（${r.model}）` : "连接成功");
      } else {
        setTestResult(`连接失败：${r.error || "未知错误"}`);
      }
    } catch (e) {
      setTestResult(`请求失败：${(e as Error).message || "未知错误"}`);
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    setMsg("");
    try {
      await api.put("/api/settings", {
        llm: form.llm,
        permission_mode: form.permission_mode,
        server: {
          token: form.server?.token ?? "",
          approval_timeout: Number(form.server?.approval_timeout ?? 120),
          // MCP 列表必须一起提交：此前只发 token/approval_timeout，
          // 在面板里改的 MCP 服务器会被静默丢弃（后端其实支持写 mcp）
          mcp: form.server?.mcp ?? [],
        },
        mcp_client: form.mcp_client ?? { enabled: false, servers: [], timeout: 15 },
        // 运行参数 / 上下文 / 记忆：面板里可调，必须一起提交（后端会做范围收敛）
        agent: form.agent,
        context: form.context,
        memory: form.memory,
        user_prefs: form.user_prefs ?? "",
      });
      // 只在用户真的改过令牌时才同步到本地：
      // 未改动说明 form 里是后端回传的打码占位符，写入会把自己锁死（见 tokenDirty 注释）
      let msg = "已保存";
      if (tokenDirty) {
        const t = (form.server?.token ?? "").trim();
        if (t) {
          setToken(t);
        } else {
          // 清空输入框：后端把空值当"不修改"（防止打码占位被写进去），服务端令牌保持不变；
          // 本地凭据跟着保持不动，否则会出现"服务端有令牌、本地没有 → 全部 401"的错位
          msg = "已保存（令牌未变更：面板清空不算清除，请在设备命令行执行 --clear-token 清除）";
        }
        setTokenDirty(false);
      }
      setMsg(msg);
      setTimeout(onClose, 800);
    } catch (e) {
      setMsg((e as Error).message || "保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="overlay" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head">
          <h2>设置</h2>
          <button className="icon-btn" onClick={onClose} aria-label="关闭">
            <IconClose />
          </button>
        </div>

        {mock && <p className="mock-banner">当前是离线演示模式（--mock），下面的配置不影响演示。</p>}

        {/* 环境自检：放在最前面——用户打开设置往往就是因为「哪里不对」 */}
        <DiagnosticsPanel api={api} />

        {/* 后台保活：仅 Android 壳且未豁免电池优化时出现 */}
        <BatteryGuide />

        {/* 外观 */}
        <CollapsibleGroup title="外观" summary={theme === "dark" ? "深色" : theme === "light" ? "浅色" : "跟随系统"} defaultOpen>
          <div className="field">
            <label>主题</label>
            <div className="theme-row">
              {THEMES.map((t) => (
                <label key={t.v} className={`theme ${theme === t.v ? "on" : ""}`}>
                  <input
                    type="radio"
                    name="theme"
                    checked={theme === t.v}
                    onChange={() => onTheme(t.v)}
                  />
                  {t.swatch}
                  {t.label}
                </label>
              ))}
            </div>
          </div>
        </CollapsibleGroup>

        {/* 智能体工作模式：Plan 先出计划待批准 / Act 直接执行 */}
        <CollapsibleGroup title="智能体模式" summary={agentMode === "plan" ? "先计划" : "直接执行"}>
          <div className="field">
            <label>工作方式</label>
            <div className="mode-row">
              {AGENT_MODES.map((m) => (
                <label key={m.v} className={`mode ${agentMode === m.v ? "on" : ""}`}>
                  <input
                    type="radio"
                    name="agent_mode"
                    checked={agentMode === m.v}
                    onChange={() => setAgentMode(m.v)}
                  />
                  <b>{m.label}</b>
                  <span>{m.hint}</span>
                </label>
              ))}
            </div>
            <p className="hint">「先计划」下，复杂任务会先给你一张计划卡片，批准后才真正执行。</p>
          </div>
        </CollapsibleGroup>

        {/* 模型 */}
        <CollapsibleGroup title="模型" summary={form.llm.model ? `${form.llm.provider} · ${form.llm.model}` : "未配置"} defaultOpen>
          <div className="field">
            <label>模型厂商</label>
            <select value={form.llm.provider} onChange={(e) => pickProvider(e.target.value)}>
              {providers.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
            {providers.find((p) => p.id === form.llm.provider)?.note && (
              <p className="hint">{providers.find((p) => p.id === form.llm.provider)?.note}</p>
            )}
          </div>
          <div className="field">
            <label>Base URL</label>
            <input
              value={form.llm.base_url}
              onChange={(e) => setForm((f) => (f ? { ...f, llm: { ...f.llm, base_url: e.target.value } } : f))}
              placeholder="https://…/v1"
            />
          </div>
          <div className="field">
            <label>模型名</label>
            <input
              value={form.llm.model}
              onChange={(e) => setForm((f) => (f ? { ...f, llm: { ...f.llm, model: e.target.value } } : f))}
              placeholder="例如 deepseek-flash"
            />
          </div>
          <div className="field">
            <label>API Key</label>
            <div className="key-row">
              <input
                type={showKey ? "text" : "password"}
                value={form.llm.api_key}
                onChange={(e) => setForm((f) => (f ? { ...f, llm: { ...f.llm, api_key: e.target.value } } : f))}
                placeholder={form.llm.api_key ? "已配置（留空则不修改）" : "sk-…"}
              />
              <button className="btn sm" onClick={() => setShowKey((v) => !v)}>
                {showKey ? "隐藏" : "显示"}
              </button>
            </div>
          </div>
          <div className="field">
            <label>Temperature · {form.llm.temperature.toFixed(1)}</label>
            <input
              type="range"
              min={0}
              max={2}
              step={0.1}
              value={form.llm.temperature}
              onChange={(e) =>
                setForm((f) => (f ? { ...f, llm: { ...f.llm, temperature: Number(e.target.value) } } : f))
              }
            />
            <p className="hint">低 = 更稳定听话，高 = 更有创意</p>
            <div className="llm-test-row">
              <button className="btn sm" onClick={() => void testConnection()} disabled={testing}>
                {testing ? "测试中…" : "测试连接"}
              </button>
              {testResult && (
                <span className={testResult.startsWith("连接成功") ? "llm-test-ok" : "llm-test-err"}>
                  {testResult}
                </span>
              )}
            </div>
          </div>
        </CollapsibleGroup>

        {/* 权限与安全 */}
        <CollapsibleGroup title="权限与安全" summary={MODES.find((m) => m.v === form.permission_mode)?.label ?? form.permission_mode}>
          <div className="field">
            <label>工具权限</label>
            <div className="mode-row">
              {MODES.map((m) => (
                <label key={m.v} className={`mode ${form.permission_mode === m.v ? "on" : ""}`}>
                  <input
                    type="radio"
                    name="permission_mode"
                    checked={form.permission_mode === m.v}
                    onChange={() => setForm((f) => (f ? { ...f, permission_mode: m.v } : f))}
                  />
                  <b>{m.label}</b>
                  <span>{m.hint}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="field">
            <label>局域网访问令牌（可选）</label>
            <input
              value={form.server?.token ?? ""}
              onChange={(e) => {
                setTokenDirty(true);
                setForm((f) => (f ? { ...f, server: { ...f.server, token: e.target.value } } : f));
              }}
              placeholder="留空 = 仅本机可访问"
            />
            <p className="hint">只要此处非空，所有 /api 请求（含本机）都必须带 Authorization: Bearer &lt;令牌&gt;；面板里清空不算清除（避免误删），清除请在设备上执行 python -m agentd.main --clear-token。</p>
          </div>
          <div className="field">
            <label>审批超时（秒）</label>
            <input
              type="number"
              min={5}
              max={600}
              value={form.server?.approval_timeout ?? 120}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, server: { ...f.server, approval_timeout: Number(e.target.value) || 120 } } : f
                )
              }
            />
            <p className="hint">等待审批超过该时长自动拒绝（安全优先）</p>
          </div>
        </CollapsibleGroup>

        {/* 运行参数（此前只能在 config.json 里改） */}
        <CollapsibleGroup title="运行参数" summary={`工具链 ${form.agent?.max_tool_chain_depth ?? 20} · 重试 ${form.agent?.max_tool_retries ?? 2}`}>
          <div className="field">
            <label htmlFor="cfg-chain-depth">工具调用链上限</label>
            <input
              id="cfg-chain-depth"
              type="number"
              min={3}
              max={50}
              value={form.agent?.max_tool_chain_depth ?? 20}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, agent: { ...(f.agent ?? { max_tool_retries: 2, max_tool_chain_depth: 20 }), max_tool_chain_depth: Number(e.target.value) || 20 } } : f
                )
              }
            />
            <p className="hint">单次请求最多连续调用多少次工具（防死循环），3–50。</p>
          </div>
          <div className="field">
            <label htmlFor="cfg-retries">只读工具失败重试次数</label>
            <input
              id="cfg-retries"
              type="number"
              min={0}
              max={5}
              value={form.agent?.max_tool_retries ?? 2}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, agent: { ...(f.agent ?? { max_tool_retries: 2, max_tool_chain_depth: 20 }), max_tool_retries: Number(e.target.value) || 0 } } : f
                )
              }
            />
            <p className="hint">
              仅对只读（safe）工具的瞬时失败生效；发短信/写文件/shell 等写操作绝不重试，避免重复副作用。
            </p>
          </div>
        </CollapsibleGroup>

        {/* 上下文与记忆 */}
        <CollapsibleGroup title="上下文与记忆" summary={form.context?.budget_tokens ? `${form.context.budget_tokens} tokens` : "不限制预算"}>
          <div className="field">
            <label htmlFor="cfg-budget">记住多长的对话（0 = 不限，推荐留空）</label>
            <input
              id="cfg-budget"
              type="number"
              min={0}
              max={200000}
              step={1000}
              value={form.context?.budget_tokens ?? 0}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, context: { ...(f.context ?? { budget_tokens: 0, auto_compress: true, compress_threshold: 0.8 }), budget_tokens: Number(e.target.value) || 0 } } : f
                )
              }
            />
            <p className="hint">单位是 token（约 1.5 个汉字算 1 个）。留 0 交给程序自动判断；填小了会更容易忘记前文，填大了更耗 token 费用。</p>
          </div>
          <div className="field">
            <label htmlFor="cfg-threshold">多满开始压缩（0.5～0.95，推荐 0.8）</label>
            <input
              id="cfg-threshold"
              type="number"
              min={0.5}
              max={0.95}
              step={0.05}
              value={form.context?.compress_threshold ?? 0.8}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, context: { ...(f.context ?? { budget_tokens: 0, auto_compress: true, compress_threshold: 0.8 }), compress_threshold: Number(e.target.value) || 0.8 } } : f
                )
              }
            />
          </div>
          <div className="field">
            <label htmlFor="cfg-autocompress">
              <input
                id="cfg-autocompress"
                type="checkbox"
                checked={form.context?.auto_compress ?? true}
                onChange={(e) =>
                  setForm((f) =>
                    f ? { ...f, context: { ...(f.context ?? { budget_tokens: 0, auto_compress: true, compress_threshold: 0.8 }), auto_compress: e.target.checked } } : f
                  )
                }
              />{' '}
              自动压缩早期历史为摘要
            </label>
            <p className="hint">关闭后不做自动归档（仍会为不超窗口而裁剪上下文，但不写入长期记忆）。</p>
          </div>
          <div className="field">
            <label htmlFor="cfg-search-limit">每轮参考几条过往记忆（推荐 5）</label>
            <input
              id="cfg-search-limit"
              type="number"
              min={1}
              max={20}
              value={form.memory?.search_limit ?? 5}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, memory: { ...(f.memory ?? MEMORY_DEFAULTS), search_limit: Number(e.target.value) || 5 } } : f
                )
              }
            />
            <p className="hint">每次回答前，从以前聊过的内容里挑几条最相关的塞给模型。条数越多越"记得住"，但更慢更贵。</p>
          </div>
          <div className="field">
            <label htmlFor="cfg-summarize-turns">每聊几轮做一次小结（推荐 10）</label>
            <input
              id="cfg-summarize-turns"
              type="number"
              min={3}
              max={50}
              value={form.memory?.summarize_every_n_turns ?? 10}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, memory: { ...(f.memory ?? MEMORY_DEFAULTS), summarize_every_n_turns: Number(e.target.value) || 10 } } : f
                )
              }
            />
          </div>
          <div className="field">
            <label htmlFor="cfg-retention">记忆保留多久（天，0 = 永久）</label>
            <input
              id="cfg-retention"
              type="number"
              min={0}
              max={3650}
              value={form.memory?.retention_days ?? 90}
              onChange={(e) =>
                setForm((f) =>
                  f ? { ...f, memory: { ...(f.memory ?? MEMORY_DEFAULTS), retention_days: Number(e.target.value) || 0 } } : f
                )
              }
            />
            <p className="hint">超过这个天数的聊天与记忆会在启动时自动清理。担心隐私就调小；想长期留底就填 0。</p>
          </div>
          <div className="field">
            <label>立即压缩当前会话</label>
            <div className="row-btns">
              <button className="btn sm" onClick={() => void compactNow()} disabled={compacting}>
                {compacting ? "压缩中…" : "把早期消息压成摘要"}
              </button>
              {compactMsg && <span className="hint">{compactMsg}</span>}
            </div>
            <p className="hint">
              保留最近 10 条原文，其余归档为摘要（与自动分页同一套机制）；会话很长时手动做一次，
              后续对话仍能记得要点。
            </p>
          </div>
        </CollapsibleGroup>

        {/* MCP 服务器（可选接入） */}
        <CollapsibleGroup title="MCP 服务器（可选）" summary={`${form.server?.mcp?.length ?? 0} 个服务器`}>
          <div className="field">
            <label>接入列表（每行一个：名称 | 启动命令 | 参数）</label>
            <textarea
              rows={3}
              value={(form.server?.mcp ?? [])
                .map((m) => [m.name, m.command, ...(m.args || [])].join(" | "))
                .join("\n")}
              onChange={(e) => {
                const rows = e.target.value
                  .split("\n")
                  .map((l) => l.trim())
                  .filter(Boolean)
                  .map((l) => {
                    const [name = "", command = "", ...rest] = l.split("|").map((x) => x.trim());
                    // 参数段再按空白切开：占位示例写的是「-y pkg /sdcard」这类多参数，
                    // 整段原样透传会让 MCP 客户端把三个参数当成一个 argv，服务器直接起不来
                    return {
                      name: name || "mcp",
                      command,
                      args: rest.flatMap((r) => r.split(/\s+/).filter(Boolean)),
                    };
                  })
                  .filter((m) => m.command);
                setForm((f) => (f ? { ...f, server: { ...f.server, mcp: rows } } : f));
              }}
              placeholder={"本地demo | python3 | tests/mcp_demo_server.py\n文件系统 | npx | -y @modelcontextprotocol/server-filesystem /sdcard"}
            />
            <p className="hint">
              MCP 是通用工具协议（Linux 基金会托管）。每行一个：名称 | 命令 | 参数。保存重启后自动连接，其工具会出现在「手机能力」里（默认需审批）。
            </p>
          </div>
        </CollapsibleGroup>

        {/* MCP Client（PocketMind 调用外部 MCP 服务器） */}
        <CollapsibleGroup title="MCP Client（调用外部 MCP）" summary={form.mcp_client?.enabled ? "已启用" : "未启用"}>
          <div className="field">
            <label className="checkbox-row">
              <input
                type="checkbox"
                checked={form.mcp_client?.enabled ?? false}
                onChange={(e) =>
                  setForm((f) =>
                    f ? { ...f, mcp_client: { ...(f.mcp_client || {}), enabled: e.target.checked } } : f
                  )
                }
              />
              启用 MCP Client（让 PocketMind 调用外部 MCP Server）
            </label>
            <p className="hint">
              服务器列表每行一个：名称 | 命令 | 参数。保存后需重启服务生效，连接成功后其工具会出现在「MCP 扩展」分组（默认需审批）。
            </p>
            <textarea
              rows={3}
              value={(form.mcp_client?.servers ?? [])
                .map((s) => [s.name, s.command, ...(s.args || [])].join(" | "))
                .join("\n")}
              onChange={(e) => {
                const rows = e.target.value
                  .split("\n")
                  .map((l) => l.trim())
                  .filter(Boolean)
                  .map((l) => {
                    const [name = "", command = "", ...rest] = l.split("|").map((x) => x.trim());
                    return {
                      name: name || "mcp",
                      command,
                      args: rest.flatMap((r) => r.split(/\s+/).filter(Boolean)),
                      auto_connect: true,
                    };
                  })
                  .filter((s) => s.command);
                setForm((f) => (f ? { ...f, mcp_client: { ...(f.mcp_client || {}), servers: rows } } : f));
              }}
              placeholder={"github | npx | -y @modelcontextprotocol/server-github"}
            />
            <p className="hint">需先安装对应 MCP Server 依赖（如 npx 包），且外部服务器需可从手机访问。</p>
          </div>
        </CollapsibleGroup>

        {/* 长期偏好（跨会话记忆） */}
        <CollapsibleGroup title="长期偏好" summary={form.user_prefs?.trim() ? "已填写" : "未填写"}>
          <div className="field">
            <label>我希望 Agent 记住的事</label>
            <textarea
              rows={4}
              value={form.user_prefs ?? ""}
              onChange={(e) => setForm((f) => (f ? { ...f, user_prefs: e.target.value } : f))}
              placeholder={"例如：\n· 回复用简体中文，简洁直接\n· 我常用支付宝，涉及支付时提醒我\n· 我是学生，白天上课，晚上有空"}
            />
            <p className="hint">这段文字会写进每次对话的系统提示（存在本机，不会上传）。</p>
          </div>
        </CollapsibleGroup>

        {/* 记忆档案（USER.md / FACT.md，结构化长期记忆；独立保存、即时生效） */}
        <CollapsibleGroup title="记忆档案" summary={(profileForm?.user?.trim() || profileForm?.facts?.trim()) ? "已有内容" : "空"}>
          <div className="field">
            <label>用户画像（USER.md）</label>
            <textarea
              rows={3}
              value={profileForm?.user ?? ""}
              onChange={(e) => setProfileForm((f) => ({ user: e.target.value, facts: f?.facts ?? "" }))}
              placeholder={"例如：\n老王，杭州，做硬件；周末不处理工作消息"}
            />
          </div>
          <div className="field">
            <label>长期事实（FACT.md，一行一条）</label>
            <textarea
              rows={4}
              value={profileForm?.facts ?? ""}
              onChange={(e) => setProfileForm((f) => ({ user: f?.user ?? "", facts: e.target.value }))}
              placeholder={"例如：\n- 家里 WiFi 密码在客厅抽屉的便签上\n- 车是 2021 款凯美瑞，保养周期 1 万公里"}
            />
            <p className="hint">
              存在本机 <code>~/.agent/termux-agent/memory/</code>，可直接编辑或备份；
              Agent 也可用 remember_fact 工具自行追加事实。与「长期偏好」不同：这里是结构化档案，
              偏好是自由文本。
            </p>
          </div>
          <div className="row-btns">
            <button className="btn sm" onClick={() => void saveProfile()} disabled={profileSaving || !profileForm}>
              {profileSaving ? "保存中…" : "保存档案"}
            </button>
            {profileMsg && <span className="hint">{profileMsg}</span>}
          </div>
        </CollapsibleGroup>

        {/* 审批权限名单（黑名单全局生效，误点后可在这里撤销） */}
        <CollapsibleGroup title="审批权限名单" summary={`始终拒绝 ${rules?.deny?.length ?? 0} · 全局允许 ${rules?.global?.length ?? 0}`}>
          <div className="field">
            <label>始终拒绝（全局黑名单，优先级最高）</label>
            {rules && (rules.deny?.length ?? 0) > 0 ? (
              <ul className="rules-list">
                {(rules.deny ?? []).map((t) => (
                  <li key={t}>
                    <code>{t}</code>
                    <button className="btn mini" onClick={() => void clearRule("deny", t)}>撤销</button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">无。在审批卡上点「始终拒绝」会把工具加入这里。</p>
            )}
            <label>全局允许（所有会话免确认）</label>
            {rules && (rules.global?.length ?? 0) > 0 ? (
              <ul className="rules-list">
                {(rules.global ?? []).map((t) => (
                  <li key={t}>
                    <code>{t}</code>
                    <button className="btn mini" onClick={() => void clearRule("global", t)}>撤销</button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">无。在审批卡上点「全局允许」会把工具加入这里。</p>
            )}
          </div>
        </CollapsibleGroup>

        {/* 知识库（只读浏览 + 删除；新增只经 kb_add 工具，走审批） */}
        <CollapsibleGroup title="知识库" summary={`${kbItems.length} 条`}>
          <div className="field">
            {kbItems.length > 0 ? (
              <ul className="kb-list">
                {kbItems.map((k) => (
                  <li key={k.id}>
                    <span className="kb-title">{k.title}</span>
                    <span className="kb-size">{k.chars} 字</span>
                    <button className="btn mini" onClick={() => void removeKb(k)}>删除</button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">还没有条目。让 Agent「把这份保修卡存进知识库」即可（写入会走审批）。</p>
            )}
            <p className="hint">
              条目只在 Agent 主动调用 kb_search 时检索，不会自动塞进每轮对话——这正是让上下文保持干净的关键。
            </p>
            {kbMsg && <p className="hint">{kbMsg}</p>}
          </div>
        </CollapsibleGroup>

        {/* 技能（只读展示：把 SKILL.md 目录放进 skills/ 即生效） */}
        <CollapsibleGroup title="技能" summary={`${skills.length} 个`}>
          <div className="field">
            {skills.length > 0 ? (
              <ul className="kb-list">
                {skills.map((s) => (
                  <li key={s.name}>
                    <span className="kb-title">{s.name}</span>
                    <span className="kb-size">{s.description}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="hint">还没有技能。把任一带 SKILL.md 的技能目录放进下面的位置即可被识别。</p>
            )}
            <p className="hint">
              目录：<code>{skillsDir || "~/.agent/termux-agent/skills/"}</code>
              ；只有技能名与描述会进系统提示，正文由 Agent 按需读取（渐进披露）。
            </p>
          </div>
        </CollapsibleGroup>

        {/* 工具能力（按分组展示） */}
        <CollapsibleGroup
          title={`工具能力（${tools.length} 项）`}
          summary={`${tools.length} 项`}
          action={<button className="btn mini" onClick={onExport} title="把所有会话与配置导出为 JSON 文件">导出备份</button>}
        >
          <div className="field">
            {GROUP_NAMES.map((g) => {
              const items = tools.filter((t) => (t.group || "other") === g.id);
              if (items.length === 0) return null;
              return (
                <div key={g.id} className="tool-group">
                  <p className="tool-group-name">{g.label}（{items.length}）</p>
                  <ul className="tools-list">
                    {items.map((t) => (
                      <li key={t.name} className={`tool-item ${t.risk}`}>
                        <code>{t.name}</code>
                        <span className="tool-risk">{RISK_LABEL[t.risk] ?? t.risk}</span>
                        <p>{t.summary}</p>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
            <p className="hint">「手机能力」依赖 termux-api：pkg install termux-api。「系统」为 shell 命令（危险）。文件操作（写/删）执行前自动备份，可在对话里一键撤销。</p>
          </div>
        </CollapsibleGroup>

        {/* 数据备份 */}
        <CollapsibleGroup title="数据备份">
          <div className="field">
            <p className="hint">
              一键把全部会话与设置导出为 JSON 文件（不含 API 密钥），用于本地备份留存；文件保存在你下载的位置。
              导入是合并语义：同 id 会话会跳过，不会覆盖本机现有数据。
            </p>
            <div className="job-actions">
              <button className="btn" onClick={onExport}>导出全部数据</button>
              <button className="btn" onClick={pickImport} disabled={importing}>
                {importing ? "导入中…" : "从备份恢复"}
              </button>
            </div>
            {importMsg && <p className="hint">{importMsg}</p>}
          </div>
        </CollapsibleGroup>

        {/* 运行统计：真实计数器（请求/工具/LLM tokens），不是配置回显 */}
        <CollapsibleGroup title="运行统计" summary={stats ? `运行 ${Math.round(stats.metrics.uptime_seconds / 60)} 分钟` : "暂无数据"}>
          <div className="field">
            {!stats ? (
              <p className="hint">拿不到统计数据（后端可能未就绪）。</p>
            ) : (
              <ul className="kb-list">
                <li>
                  <span className="kb-title">运行时长</span>
                  <span className="kb-size">{Math.round(stats.metrics.uptime_seconds / 60)} 分钟</span>
                </li>
                <li>
                  <span className="kb-title">HTTP 请求</span>
                  <span className="kb-size">{stats.metrics.requests.total} 次</span>
                </li>
                <li>
                  <span className="kb-title">LLM 调用</span>
                  <span className="kb-size">
                    {stats.metrics.llm.calls} 次 · 平均 {stats.metrics.llm.avg_ms} ms
                  </span>
                </li>
                <li>
                  <span className="kb-title">Token 用量</span>
                  <span className="kb-size">
                    输入 {stats.metrics.llm.prompt_tokens.toLocaleString()} · 输出{" "}
                    {stats.metrics.llm.completion_tokens.toLocaleString()} · 合计{" "}
                    {stats.metrics.llm.total_tokens.toLocaleString()}
                  </span>
                </li>
                <li>
                  <span className="kb-title">工具调用</span>
                  <span className="kb-size">
                    {Object.values(stats.metrics.tools).reduce((n, t) => n + t.calls, 0)} 次 · 失败{" "}
                    {Object.values(stats.metrics.tools).reduce((n, t) => n + t.failures, 0)} 次
                  </span>
                </li>
              </ul>
            )}
            <p className="hint">
              Token 只统计模型端点在流式响应里真实回报的用量；端点不回报时显示 0，不做估算。
            </p>
          </div>
        </CollapsibleGroup>

        <div className="sheet-foot">
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn primary" onClick={() => void save()} disabled={saving}>
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
        {msg && <p className="save-msg">{msg}</p>}
      </div>
    </div>
  );
}
