import { useCallback, useEffect, useState } from "react";
import {
  api,
  deleteJob,
  getJobs,
  getRecipes,
  applyRecipe,
  runJobNow,
  updateJob,
  type Job,
  type Recipe,
} from "../api";
import type { DiagReport } from "./DiagnosticsPanel";
import { humanizeTrigger } from "./TimerPanel";
import { IconClock, IconShield } from "./icons";

/**
 * 任务页：把「常驻 Agent 真正在替你跑什么」集中呈现。
 *
 * 为什么合并：定时任务、审批队列、环境健康三件事在原设计里分散在顶栏时钟图标、
 * 消息流内联卡、设置面板底部 —— 用户无法回答"它现在在替我做什么/需要我做什么"。
 * 常驻型产品的核心问题恰恰是这一个，所以合成一页。
 */
export function TasksPage({ onOpenSession }: { onOpenSession: () => void }) {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const [pending, setPending] = useState<Array<{ session_id: string; tool_call_id: string; name: string; risk: string; remaining: number }>>([]);
  const [diag, setDiag] = useState<DiagReport | null>(null);
  const [busy, setBusy] = useState("");
  const [err, setErr] = useState("");

  const reload = useCallback(async () => {
    const [j, r] = await Promise.allSettled([getJobs(), getRecipes()]);
    if (j.status === "fulfilled") setJobs(j.value);
    if (r.status === "fulfilled") setRecipes(r.value);
  }, []);

  useEffect(() => {
    void reload().catch((e) => setErr((e as Error).message || "任务加载失败"));
    // 审批与环境状态轮询：不求实时（5s 粒度足够），避免与 SSE 抢带宽
    const tick = async () => {
      try {
        const p = await api.get<{ pending: typeof pending }>("/api/approvals/pending");
        setPending(p.pending ?? []);
      } catch {
        /* 未授权或旧后端：忽略 */
      }
      try {
        const d = await api.get<DiagReport>("/api/diagnostics");
        setDiag(d);
      } catch {
        /* 同上 */
      }
    };
    void tick();
    const t = window.setInterval(tick, 5000);
    return () => window.clearInterval(t);
  }, [reload]);

  const act = async (key: string, fn: () => Promise<unknown>) => {
    setBusy(key);
    setErr("");
    try {
      await fn();
      await reload();
    } catch (e) {
      setErr((e as Error).message || "操作失败");
    } finally {
      setBusy("");
    }
  };

  const problems = diag?.items?.filter((i) => i.status !== "ok") ?? [];
  const running = jobs.filter((j) => j.enabled).length;

  return (
    <div className="page">
      <header className="page-head">
        <h2>它在替你做什么</h2>
        <p className="page-sub">
          {running > 0 ? `${running} 个任务在跑` : "还没有运行中的任务"}
          {pending.length > 0 && ` · ${pending.length} 项等你确认`}
        </p>
      </header>

      {err && <p className="page-err">{err}</p>}

      {/* 待审批：常驻 Agent 最高优先级的"需要你" */}
      {pending.length > 0 && (
        <section className="task-sec urgent">
          <div className="task-sec-head">
            <IconShield size={16} />
            <span>等你确认（{pending.length}）</span>
          </div>
          <ul className="task-list">
            {pending.map((p) => (
              <li key={`${p.session_id}:${p.tool_call_id}`} className="task-row">
                <div className="task-row-main">
                  <span className="task-row-title">{p.name}</span>
                  <span className="task-row-sub">
                    {p.risk === "danger" ? "危险操作" : "需要授权"} · 剩余 {Math.max(0, p.remaining)}s
                  </span>
                </div>
                <button className="btn sm primary" onClick={onOpenSession}>
                  去处理
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* 运行中的任务 */}
      <section className="task-sec">
        <div className="task-sec-head">
          <IconClock size={16} />
          <span>定时任务（{jobs.length}）</span>
        </div>
        {jobs.length === 0 ? (
          <p className="task-empty">
            还没有定时任务。可以让它「每天早上 8 点提醒我看天气」，或从下面的常用模板一键创建。
          </p>
        ) : (
          <ul className="task-list">
            {jobs.map((j) => (
              <li key={j.id} className={`task-row ${j.enabled ? "" : "off"}`}>
                <div className="task-row-main">
                  <span className="task-row-title">{j.name}</span>
                  <span className="task-row-sub">
                    {humanizeTrigger(j)}
                    {j.next_run ? ` · 下次 ${j.next_run}` : ""}
                  </span>
                </div>
                <div className="task-row-ops">
                  <button
                    className="btn sm"
                    disabled={busy === `run:${j.id}`}
                    onClick={() => void act(`run:${j.id}`, () => runJobNow(j.id))}
                  >
                    现在跑
                  </button>
                  <button
                    className="btn sm"
                    disabled={busy === `toggle:${j.id}`}
                    onClick={() => void act(`toggle:${j.id}`, () => updateJob(j.id, !j.enabled))}
                  >
                    {j.enabled ? "停用" : "启用"}
                  </button>
                  <button
                    className="btn sm danger"
                    disabled={busy === `del:${j.id}`}
                    onClick={() => {
                      if (!window.confirm(`删除任务「${j.name}」？`)) return;
                      void act(`del:${j.id}`, () => deleteJob(j.id));
                    }}
                  >
                    删除
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 常用模板 */}
      {recipes.length > 0 && (
        <section className="task-sec">
          <div className="task-sec-head">
            <span>常用模板</span>
          </div>
          <ul className="task-list">
            {recipes.map((r) => (
              <li key={r.id} className="task-row">
                <div className="task-row-main">
                  <span className="task-row-title">{r.name}</span>
                  <span className="task-row-sub">{humanizeTrigger(r)}</span>
                </div>
                <button
                  className="btn sm"
                  disabled={r.applied || busy === `recipe:${r.id}`}
                  onClick={() => void act(`recipe:${r.id}`, () => applyRecipe(r.id))}
                >
                  {r.applied ? "已启用" : "一键创建"}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* 环境健康：常驻产品的"体检报告" */}
      <section className="task-sec">
        <div className="task-sec-head">
          <span>运行环境</span>
          <span className={`env-pill ${problems.length ? "warn" : "ok"}`}>
            {diag ? (problems.length ? `${problems.length} 项待处理` : "正常") : "检测中…"}
          </span>
        </div>
        {problems.length > 0 && (
          <ul className="task-list">
            {problems.map((i) => (
              <li key={i.id} className={`task-row env-${i.status}`}>
                <div className="task-row-main">
                  <span className="task-row-title">{i.title}</span>
                  <span className="task-row-sub">{i.detail}</span>
                  {i.fix && <span className="task-row-fix">→ {i.fix}</span>}
                </div>
              </li>
            ))}
          </ul>
        )}
        {diag?.capabilities && (
          <p className="task-empty">
            设备形态：{diag.capabilities.platform.kind} · 可用 {diag.capabilities.available.length} 类能力
          </p>
        )}
      </section>
    </div>
  );
}
