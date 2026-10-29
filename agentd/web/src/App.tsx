import { useCallback, useEffect, useRef, useState } from "react";
import { ChatErrorBoundary } from "./components/ChatErrorBoundary";
import { Composer } from "./components/Composer";
import { EmptyState } from "./components/EmptyState";
import { IconChevron, IconGear, IconSessions, IconTimer } from "./components/icons";
import { MessageItem } from "./components/MessageItem";
import { PendingApprovalBanner } from "./components/PendingApprovalBanner";
import { PocketLogo } from "./components/PocketLogo";
import { SessionList } from "./components/SessionList";
import { SettingsPanel } from "./components/SettingsPanel";
import { TimerPanel } from "./components/TimerPanel";
import { TodoPanel } from "./components/TodoPanel";
import { TokenGate } from "./components/TokenGate";
import { useAgentStore } from "./store/useAgentStore";
import type { Msg } from "./types";
import { applyTheme } from "./theme";
import { reloadPage } from "./native";

/* ---------- App ---------- */

export default function App() {
  // 从 Zustand store 订阅状态与 action（消除 props drilling）
  const sessions = useAgentStore((s) => s.sessions);
  const sessionId = useAgentStore((s) => s.sessionId);
  const messages = useAgentStore((s) => s.messages);
  const busy = useAgentStore((s) => s.busy);
  const ready = useAgentStore((s) => s.ready);
  const mock = useAgentStore((s) => s.mock);
  const error = useAgentStore((s) => s.error);
  const clearError = useAgentStore((s) => s.clearError);
  const showSessions = useAgentStore((s) => s.showSessions);
  const showSettings = useAgentStore((s) => s.showSettings);
  const showTimers = useAgentStore((s) => s.showTimers);
  const theme = useAgentStore((s) => s.theme);
  const undoingId = useAgentStore((s) => s.undoingId);
  const connectionStatus = useAgentStore((s) => s.connectionStatus);
  const todos = useAgentStore((s) => s.todos);
  const clearTodos = useAgentStore((s) => s.clearTodos);
  const settings = useAgentStore((s) => s.settings);
  const webSearch = useAgentStore((s) => s.webSearch);
  const setWebSearch = useAgentStore((s) => s.setWebSearch);
  const needToken = useAgentStore((s) => s.needToken);
  const onTokenSubmitted = useAgentStore((s) => s.onTokenSubmitted);

  // 审批倒计时跟随后端配置（server.approval_timeout），拿不到时兜底 120s
  // 「编辑消息」回填输入框的草稿：非空时 Composer 会消费并回调清空
  const [draft, setDraft] = useState<string | undefined>(undefined);

  const approvalTimeout =
    Number((settings?.server as { approval_timeout?: number } | undefined)?.approval_timeout) || 120;

  // 顶栏状态灯：一个颜色只回答"现在能不能用"——绿=可用、琥珀=待配置、红=断开
  const statusKind = connectionStatus !== "connected" ? "err" : ready ? "ok" : "warn";
  const statusText =
    connectionStatus === "disconnected" ? "已断开" : connectionStatus === "reconnecting" ? "连接中" : ready ? "已就绪" : "未配置";

  const init = useAgentStore((s) => s.init);
  const loadSession = useAgentStore((s) => s.loadSession);
  const send = useAgentStore((s) => s.send);
  const deleteMessage = useAgentStore((s) => s.deleteMessage);
  const regenerate = useAgentStore((s) => s.regenerate);
  const truncateFrom = useAgentStore((s) => s.truncateFrom);
  const stop = useAgentStore((s) => s.stop);
  const onNewSession = useAgentStore((s) => s.onNewSession);
  const onDeleteSession = useAgentStore((s) => s.onDeleteSession);
  const onApproval = useAgentStore((s) => s.onApproval);
  const onUndo = useAgentStore((s) => s.onUndo);
  const onExport = useAgentStore((s) => s.onExport);
  const onRenameSession = useAgentStore((s) => s.onRenameSession);
  const onPinSession = useAgentStore((s) => s.onPinSession);
  const approvePlan = useAgentStore((s) => s.approvePlan);
  const editPlan = useAgentStore((s) => s.editPlan);
  const ignorePlan = useAgentStore((s) => s.ignorePlan);

  // 消息级回调必须用 useCallback 固定引用：MessageItem 包了 memo，
  // 若每次渲染都传新的 inline 函数，memo 会全部失效（长对话流式输出时逐字重渲染）。
  const handleDelete = useCallback(
    (m: Msg) => {
      if (!window.confirm("删除这条消息及其之后的对话？此操作不可撤销。")) return;
      void deleteMessage(m);
    },
    [deleteMessage]
  );
  const handleRegenerate = useCallback((m: Msg) => void regenerate(m), [regenerate]);
  const handleEditUser = useCallback(
    (m: Msg, text: string) => {
      void truncateFrom(m).then(() => setDraft(text));
    },
    [truncateFrom]
  );
  const handleApprovePlan = useCallback((aid: string) => void approvePlan(aid), [approvePlan]);
  const handleIgnorePlan = useCallback((aid: string) => void ignorePlan(aid), [ignorePlan]);

  const agentMode = useAgentStore((s) => s.agentMode);
  const providerLabel = useAgentStore((s) => s.providerLabel);
  const setShowSessions = useAgentStore((s) => s.setShowSessions);
  const setShowSettings = useAgentStore((s) => s.setShowSettings);
  const setShowTimers = useAgentStore((s) => s.setShowTimers);
  const setTheme = useAgentStore((s) => s.setTheme);

  // 滚动容器是 <main className="chat">（overflow-y:auto）；
  // 注意：不是 .msgs —— 它没有 overflow，改它的 scrollTop 毫无效果（此前的老 bug）。
  const chatRef = useRef<HTMLElement>(null);
  // 「用户正在看历史」= 距底部还有一段距离。此时新内容不再把他拽回底部。
  const [pinnedUp, setPinnedUp] = useState(false);

  /** 距底部是否算"贴底"：留 80px 容差，手指微抖不至于判定成翻历史。 */
  const nearBottom = (el: HTMLElement) => el.scrollHeight - el.scrollTop - el.clientHeight < 80;

  const scrollToBottom = (smooth = true) => {
    const el = chatRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
    setPinnedUp(false);
  };

  // 启动：拉健康状态 + 会话列表，没有会话则新建一个
  useEffect(() => {
    void init();
  }, [init]);

  // Android 壳异步取到令牌后会派发 pa-token-ready：此时必须重新初始化，
  // 否则页面仍停留在"缺令牌"状态（首次同步读取必然是空串）。
  useEffect(() => {
    const onTokenReady = () => {
      onTokenSubmitted();
    };
    window.addEventListener("pa-token-ready", onTokenReady);
    return () => window.removeEventListener("pa-token-ready", onTokenReady);
  }, [onTokenSubmitted]);

  // system 模式下跟随系统主题切换
  useEffect(() => {
    if (theme !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: light)");
    const fn = () => applyTheme();
    mq.addEventListener("change", fn);
    return () => mq.removeEventListener("change", fn);
  }, [theme]);

  // 消息更新时跟随到底部——但只在用户本来就贴底时。
  // 旧实现无条件 scrollTop = scrollHeight：用户在流式输出期间往上翻看历史，
  // 每来一个字就被拽回底部，根本读不了（手机聊天最常见的恼人行为）。
  useEffect(() => {
    const el = chatRef.current;
    if (!el) return;
    if (!pinnedUp) el.scrollTop = el.scrollHeight;
  }, [messages, pinnedUp]);

  // 缺令牌时先解决鉴权，再渲染主界面：否则所有请求都是 401，
  // 用户只会看到"正在连接"和一堆失败提示，却没有输入入口。
  if (needToken) {
    return <TokenGate onDone={onTokenSubmitted} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <button className="icon-btn" onClick={() => setShowSessions(true)} title="会话列表" aria-label="会话列表">
          <IconSessions />
        </button>
        <div className="brand">
          <PocketLogo size={38} />
          <div className="brand-text">
            <h1>口袋智灵</h1>
            <p className="brand-sub">{mock ? "离线演示模式" : "本地智能体 · 数据只在你设备上"}</p>
          </div>
        </div>
        <div className="topbar-right">
          <span className={`pill ${statusKind}`} title={`后端状态：${statusText}`}>
            <i className={`pill-dot ${statusKind}`} aria-hidden="true" />
            {statusText}
          </span>
          <button className="icon-btn" onClick={() => setShowTimers(true)} title="定时任务" aria-label="定时任务">
            <IconTimer />
          </button>
          <button className="icon-btn" onClick={() => setShowSettings(true)} title="设置" aria-label="设置">
            <IconGear />
          </button>
        </div>
      </header>

      <div className="chat-wrap">
        <main
          className="chat"
          ref={chatRef}
          onScroll={(e) => {
            const el = e.currentTarget;
            const up = !nearBottom(el);
            if (up !== pinnedUp) setPinnedUp(up);
          }}
        >
          {messages.length === 0 ? (
            <EmptyState
              onPick={(t) => void send(t)}
              ready={ready}
              onOpenSettings={() => setShowSettings(true)}
            />
          ) : (
            <ChatErrorBoundary
              onReset={() => {
                // 错误恢复后重新加载当前会话
                if (sessionId) void loadSession(sessionId);
              }}
            >
              <div className="msgs">
                {messages.map((m) => (
                  <MessageItem
                    key={m.id}
                    msg={m}
                    parts={m.parts}
                    onApproval={onApproval}
                    onUndo={onUndo}
                    undoingId={undoingId}
                    onApprovePlan={handleApprovePlan}
                    onEditPlan={editPlan}
                    onIgnorePlan={handleIgnorePlan}
                    approvalTimeout={approvalTimeout}
                    onDelete={handleDelete}
                    onRegenerate={handleRegenerate}
                    onEditUser={handleEditUser}
                  />
                ))}
              </div>
            </ChatErrorBoundary>
          )}
        </main>
        {pinnedUp && messages.length > 0 && (
          <button className="to-bottom" onClick={() => scrollToBottom()} aria-label="回到最新消息">
            <IconChevron open={false} />
            最新
          </button>
        )}
      </div>
      {connectionStatus === "disconnected" && (
        <div className="conn-banner" role="status">
          <span>后端已断开，正在重试连接…</span>
          <button className="btn sm" onClick={reloadPage}>刷新页面</button>
        </div>
      )}
      {connectionStatus === "reconnecting" && (
        <div className="conn-banner warn" role="status">
          <span>正在连接后端…</span>
        </div>
      )}

      <PendingApprovalBanner />

      <TodoPanel todos={todos} onClear={clearTodos} />

      <Composer
        busy={busy}
        ready={ready}
        agentMode={agentMode}
        providerLabel={providerLabel}
        onSend={(t) => void send(t)}
        onStop={stop}
        error={error}
        onClearError={clearError}
        draft={draft}
        onDraftConsumed={() => setDraft(undefined)}
        webSearch={webSearch}
        onToggleWebSearch={() => setWebSearch(!webSearch)}
      />

      {showSessions && (
        <SessionList
          sessions={sessions}
          currentId={sessionId}
          onClose={() => setShowSessions(false)}
          onPick={(sid) => {
            setShowSessions(false);
            void loadSession(sid);
          }}
          onNew={() => void onNewSession()}
          onDelete={(sid) => void onDeleteSession(sid)}
          onRename={onRenameSession}
          onPin={(sid, pinned) => void onPinSession(sid, pinned)}
        />
      )}
      {showSettings && (
        <SettingsPanel
          onClose={() => setShowSettings(false)}
          mock={mock}
          theme={theme}
          onTheme={setTheme}
          onExport={() => void onExport()}
        />
      )}
      {showTimers && <TimerPanel onClose={() => setShowTimers(false)} />}
    </div>
  );
}
