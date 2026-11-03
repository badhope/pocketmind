import { useEffect, useState } from "react";
import { api, type Provider } from "../api";
import { PocketLogo } from "./PocketLogo";

/**
 * 首次运行引导向导（1.3）。
 *
 * 为什么需要：新用户打开页面只看到「未配置」状态灯，而配置入口藏在设置面板
 * 第一个折叠组里，要自己理解 provider/base_url/model 三个字段才能起步
 * （ROADMAP 1.3.x 的 planned 项）。向导把这三件事做成三步：
 *   ① 选一个提供商预设（base_url/model 自动填好）
 *   ② 填 API Key 并「测试连接」—— 点通才算配好，不留"以为能用其实没通"的死角
 *   ③ 选权限模式，讲清楚审批闸门的行为差异
 *
 * 原则：**跳过永远可用**。引导是服务新手的捷径，不能变成强制注册流程；
 * 跳过或完成后写入 localStorage，本次安装不再弹出。
 */

const ONBOARD_KEY = "pa-onboarded";

export function markOnboarded(): void {
  try {
    localStorage.setItem(ONBOARD_KEY, "1");
  } catch {
    /* 隐私模式下 localStorage 可能不可用：不弹即可，不报错 */
  }
}

export function onboardingDismissed(): boolean {
  try {
    return localStorage.getItem(ONBOARD_KEY) === "1";
  } catch {
    return true; // 读不到存储的浏览器无法记住「已完成」，默认不再打扰
  }
}

const PERMISSION_MODES: Array<{ v: "auto" | "approve" | "chat"; label: string; hint: string }> = [
  { v: "approve", label: "逐次审批（推荐）", hint: "写文件、发短信等敏感操作会先弹卡片征求你的同意" },
  { v: "auto", label: "全自动", hint: "所有工具直接执行，不再询问（不推荐在家用设备上开启）" },
  { v: "chat", label: "纯聊天", hint: "不调用任何工具，只对话" },
];

export function OnboardingWizard({ onDone }: { onDone: () => void }) {
  const [step, setStep] = useState(0); // 0 选提供商 · 1 连接测试 · 2 权限模式 · 3 完成
  const [providers, setProviders] = useState<Provider[]>([]);
  const [providerId, setProviderId] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [note, setNote] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [testing, setTesting] = useState(false);
  const [testOk, setTestOk] = useState<boolean | null>(null);
  const [testMsg, setTestMsg] = useState("");
  const [mode, setMode] = useState<"auto" | "approve" | "chat">("approve");
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  const pickProvider = (p: Provider) => {
    setProviderId(p.id);
    setBaseUrl(p.base_url);
    setModel(p.model);
    setNote(p.note || "");
    setTestOk(null);
    setTestMsg("");
  };

  useEffect(() => {
    api
      .get<{ providers: Provider[] }>("/api/providers")
      .then((d) => {
        const list = Array.isArray(d.providers) ? d.providers : [];
        setProviders(list);
        const first = list.find((p) => p.id === "doubao") || list[0];
        if (first) pickProvider(first);
      })
      .catch((e) => setErr((e as Error).message || "无法读取提供商列表"));
  }, []);

  const testConnection = async () => {
    // 测连前先落一版配置（api_key 不落盘测不到；打码/剔除规则与设置面板一致）。
    setTesting(true);
    setTestOk(null);
    setTestMsg("");
    try {
      await api.put("/api/settings", {
        llm: { provider: providerId, base_url: baseUrl, model, api_key: apiKey },
      });
      const r = await api.post<{ ok: boolean; model?: string; error?: string }>("/api/llm/test");
      setTestOk(r.ok);
      setTestMsg(r.ok ? `连接成功（${r.model || model}）` : `连接失败：${r.error || "未知错误"}`);
    } catch (e) {
      setTestOk(false);
      setTestMsg(`请求失败：${(e as Error).message}`);
    } finally {
      setTesting(false);
    }
  };

  const finish = async () => {
    setSaving(true);
    setErr("");
    try {
      await api.put("/api/settings", {
        llm: { provider: providerId, base_url: baseUrl, model, api_key: apiKey },
        permission_mode: mode,
      });
      markOnboarded();
      onDone();
    } catch (e) {
      setErr((e as Error).message || "保存失败，请到设置里重试");
    } finally {
      setSaving(false);
    }
  };

  const skip = () => {
    markOnboarded();
    onDone();
  };

  const canNext = Boolean(providerId && baseUrl.trim() && model.trim());

  return (
    <div className="onboard" role="dialog" aria-modal="true" aria-label="首次使用引导">
      <div className="onboard-card">
        <div className="onboard-head">
          <h2>
            <PocketLogo size={20} /> 欢迎使用口袋智灵
          </h2>
          <p>
            {step === 0 && "三步开始使用：选一个模型提供商，填入 API Key，再选好权限模式。"}
            {step === 1 && "填入该提供商的 API Key，然后测试连接——点通了才算配好。"}
            {step === 2 && "最后选一个权限模式。无论选哪个，危险操作的审批闸门都在服务端执行。"}
            {step === 3 && "已保存。可以随时在「设置」里修改这些配置。"}
          </p>
        </div>
        <div className="onboard-steps" aria-hidden="true">
          {[0, 1, 2].map((i) => (
            <i key={i} className={step >= i ? "on" : ""} />
          ))}
        </div>

        {err && <p className="onboard-err">{err}</p>}

        {step === 0 && (
          <div className="onboard-providers">
            {providers.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`onboard-provider ${p.id === providerId ? "on" : ""}`}
                onClick={() => pickProvider(p)}
              >
                <b>{p.label}</b>
                <span>{p.note || p.base_url || "自定义端点"}</span>
              </button>
            ))}
          </div>
        )}

        {step === 1 && (
          <div className="fields">
            {note && <p className="hint">{note}</p>}
            <div className="field">
              <label htmlFor="ob-key">API Key</label>
              <input
                id="ob-key"
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="提供商控制台里创建的密钥"
                autoComplete="off"
              />
            </div>
            <div className="field">
              <label htmlFor="ob-url">Base URL</label>
              <input id="ob-url" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="ob-model">模型</label>
              <input id="ob-model" value={model} onChange={(e) => setModel(e.target.value)} />
            </div>
            <div className="onboard-foot">
              <button
                className="btn"
                type="button"
                disabled={testing || !apiKey.trim()}
                onClick={() => void testConnection()}
              >
                {testing ? "测试中…" : "测试连接"}
              </button>
              {testOk === true && <span className="onboard-ok">✓ {testMsg}</span>}
              {testOk === false && <span className="onboard-err">{testMsg}</span>}
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="onboard-modes">
            {PERMISSION_MODES.map((m) => (
              <button
                key={m.v}
                type="button"
                className={`onboard-mode ${mode === m.v ? "on" : ""}`}
                onClick={() => setMode(m.v)}
              >
                <b>{m.label}</b>
                <span>{m.hint}</span>
              </button>
            ))}
          </div>
        )}

        {step === 3 && (
          <p className="center-muted">
            已选择 <b>{PERMISSION_MODES.find((m) => m.v === mode)?.label}</b>。
            第一条消息发出去后，审批卡片、任务清单和定时任务都在对话里出现。
          </p>
        )}

        <div className="onboard-foot">
          <button className="onboard-skip" type="button" onClick={skip}>
            跳过引导
          </button>
          <span className="spacer" />
          {step > 0 && step < 3 && (
            <button className="btn" type="button" onClick={() => setStep((s) => s - 1)}>
              上一步
            </button>
          )}
          {step === 0 && (
            <button className="btn primary" type="button" disabled={!canNext} onClick={() => setStep(1)}>
              下一步
            </button>
          )}
          {step === 1 && (
            <button
              className="btn primary"
              type="button"
              disabled={testOk !== true}
              title={testOk === true ? "" : "先点「测试连接」，点通后才能继续"}
              onClick={() => setStep(2)}
            >
              下一步
            </button>
          )}
          {step === 2 && (
            <button className="btn primary" type="button" disabled={saving} onClick={() => void finish()}>
              {saving ? "保存中…" : "完成"}
            </button>
          )}
          {step === 3 && (
            <button className="btn primary" type="button" onClick={skip}>
              开始使用
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
