import { useEffect, useState } from "react";
import { api, type ToolInfo } from "../api";

/**
 * 能力页：把「这台手机能干什么」变成一级页面。
 *
 * 为什么必须是页面而不是设置里的折叠区：41 个工具（其中 25 项手机能力）是整个
 * 产品的差异点。放在设置面板第 900 行之后，等于把卖点藏起来了。用户打开应用
 * 最该看到的第二件事就是"它到底能替我做哪些事"。
 *
 * 交互设计：
 * - 按场景分组（手机能力 / 文件 / 任务编排 / 知识 / 记忆 / 系统 / 语音）
 * - 每个工具给出「人话摘要 + 原始工具名 + 风险等级」
 * - 点一下 = 把对应指令填进输入框并发起（不用背工具名）
 */
const GROUP_META: Array<{ id: string; label: string; hint: string }> = [
  { id: "phone", label: "手机能力", hint: "短信 / 电话 / 定位 / 传感器 / 通知 / 拍照" },
  { id: "files", label: "文件", hint: "读写本机文件与目录" },
  { id: "agent", label: "任务与编排", hint: "子任务、待办清单" },
  { id: "knowledge", label: "知识库", hint: "长期保存与检索资料" },
  { id: "memory", label: "长期记忆", hint: "跨会话记住你的事" },
  { id: "skills", label: "技能", hint: "可复用的既定流程" },
  { id: "system", label: "系统", hint: "执行 shell 命令" },
  { id: "voice", label: "语音", hint: "离线语音合成" },
  { id: "net", label: "联网", hint: "搜索与抓取网页" },
  { id: "mcp", label: "MCP 扩展", hint: "外部 MCP 服务器提供的工具" },
  { id: "other", label: "其他", hint: "" },
];

const RISK_META: Record<string, { label: string; cls: string }> = {
  safe: { label: "只读", cls: "safe" },
  write: { label: "会改动", cls: "write" },
  danger: { label: "危险", cls: "danger" },
};

/** 把工具名翻译成一句用户会说的话（用于点击发起）。 */
function toPrompt(t: ToolInfo): string {
  return `用 ${t.name} 工具：${t.summary}`;
}

export function CapabilityPage({ onRun }: { onRun: (prompt: string) => void }) {
  const [tools, setTools] = useState<ToolInfo[]>([]);
  const [err, setErr] = useState("");
  const [openGroup, setOpenGroup] = useState<string | null>("phone");

  useEffect(() => {
    let alive = true;
    api
      .get<{ tools: ToolInfo[] }>("/api/tools")
      .then((r) => {
        if (alive) setTools(r.tools ?? []);
      })
      .catch((e) => {
        if (alive) setErr((e as Error).message || "能力清单加载失败");
      });
    return () => {
      alive = false;
    };
  }, []);

  if (err) {
    return (
      <div className="page">
        <p className="page-err">{err}</p>
      </div>
    );
  }

  const total = tools.length;
  const danger = tools.filter((t) => t.risk === "danger").length;

  return (
    <div className="page">
      <header className="page-head">
        <h2>能做什么</h2>
        <p className="page-sub">
          {total > 0 ? `${total} 项能力` : "加载中…"}
          {danger > 0 && ` · 其中 ${danger} 项危险操作需你点头`}
        </p>
      </header>

      <div className="cap-groups">
        {GROUP_META.map((g) => {
          const items = tools.filter((t) => (t.group || "other") === g.id);
          if (items.length === 0) return null;
          const open = openGroup === g.id;
          return (
            <section key={g.id} className={`cap-group ${open ? "open" : ""}`}>
              <button
                className="cap-group-head"
                onClick={() => setOpenGroup(open ? null : g.id)}
                aria-expanded={open}
              >
                <div className="cap-group-text">
                  <span className="cap-group-name">{g.label}</span>
                  <span className="cap-group-count">{items.length}</span>
                  {g.hint && <span className="cap-group-hint">{g.hint}</span>}
                </div>
                <span className="cap-chev" aria-hidden="true">
                  ▾
                </span>
              </button>
              {open && (
                <ul className="cap-items">
                  {items.map((t) => {
                    const risk = RISK_META[t.risk] ?? { label: t.risk, cls: "" };
                    return (
                      <li key={t.name} className="cap-item">
                        <button
                          className="cap-item-btn"
                          onClick={() => onRun(toPrompt(t))}
                          title={t.description}
                        >
                          <span className="cap-item-main">
                            <span className="cap-item-summary">{t.summary}</span>
                            <code className="cap-item-name">{t.name}</code>
                          </span>
                          <span className={`cap-risk ${risk.cls}`}>{risk.label}</span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          );
        })}
      </div>
      <p className="page-foot">点任意一项 = 直接发出指令；涉及改动或外发的操作会先弹审批卡。</p>
    </div>
  );
}
