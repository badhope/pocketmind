import { useCallback, useEffect, useRef, useState } from "react";
import { BottomTabs, type TabKey } from "./components/BottomTabs";
import { CapabilityPage } from "./components/CapabilityPage";
import { ChatErrorBoundary } from "./components/ChatErrorBoundary";
import { Composer } from "./components/Composer";
import { EmptyState } from "./components/EmptyState";
import { IconChevron, IconGear, IconSessions } from "./components/icons";
import { MessageItem } from "./components/MessageItem";
import { PendingApprovalBanner } from "./components/PendingApprovalBanner";
import { PocketLogo } from "./components/PocketLogo";
import { SessionList } from "./components/SessionList";
import { SettingsPanel } from "./components/SettingsPanel";
import { TasksPage } from "./components/TasksPage";
import { TokenGate } from "./components/TokenGate";
import { TodoPanel } from "./components/TodoPanel";
import { useAgentStore } from "./store/useAgentStore";
import type { Msg } from "./types";
import { applyTheme } from "./theme";
import { reloadPage } from "./native";

/**
 * 应用外壳：三个一级页签 + 可下钻的运行状态。
 *
 * 设计依据（按产品定位而非通用聊天 App 模板）：
 *   PocketMind 是「常驻在手机里的 Agent」，差异点是 41 个工具、25 项手机能力、
 *   服务端强制审批、定时任务、数据全本地。因此一级导航必须是
 *   对话 / 能力 / 任务 —— 让「它能做什么」和「它正在做什么」成为主路径，
 *   而不是像通用聊天客户端那样把它们折叠进设置面板。
 *
 * 与旧结构的差别：
 *   - 顶栏从「品牌 + 5 个图标」简化为「会话 + 状态 + 设置」，其余下移到底部页签
 *   - 定时任务从顶栏独立浮层变成「任务」页的一部分，与环境健康、待审批合并
 *   - 41 个工具从设置第 900 行提到「能力」页，可直接点击发起
 */
export default function App() {
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
  const pendingApprovals = useAgentStore((s) => s.pendingApprovals);

  const [tab, setTab] = useState<TabKey>("chat");
  const [draft, setDraft] = useState<string | undefined>(undefined);

  const approvalTimeout =
    Number((settings?.server as { approval_timeout?: number } | undefined)?.approval_timeout) || 120;

  const statusKind = connectionStatus !== "connected" ? "err" : ready ? "ok" : "warn";
  const statusText =
    connectionStatus === "disconnected"
      ? "已断开"
      : connectionStatus === "reconnecting"
        ? "连接中"
        : ready
          ? "已就绪"
          : "未配置";

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
  const agentMode = useAgentStore((s) => s.agentMode);
  const providerLabel = useAgentStore((s) => s.providerLabel);
  const setShowSessions = useAgentStore((s) => s.setShowSessions);
  const setShowSettings = useAgentStore((s) => s.setShowSettings);
  const setTheme = useAgentStore((s) => s.setTheme);

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

  /** 从能力页发起：切到对话页并发送（不直接弹输入框，减少一次操作）。 */
  const runFromCapability = useCallback(
    (prompt: string) => {
      setTab("chat");
      void send(prompt);
    },
    [send]
  );

  const chatRef = useRef<HTMLElement>(null);
  const [pinnedUp, setPinnedUp] = useState(false);
  const nearBottom = (el: HTMLElement) => el.scrollHeight - el.scrollTop - el.clientHeight < 80;

  const scrollToBottom = (smooth = true) => {
    const el = chatRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
    setPinnedUp(false);
  };

  useEffect(() => {
    void init();
  }, [init]);

  useEffect(() => {
    const onTokenReady = () => onTokenSubmitted();
    window.addEventListener("pa-token-ready", onTokenReady);
    return () => window.removeEventListener("pa-token-ready", onTokenReady);
  }, [onTokenSubmitted]);

  useEffect(() => {
    if (theme !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: light)");
    const fn = () => applyTheme();
    mq.addEventListener("change", fn);
    return () => mq.removeEventListener("change", fn);
  }, [theme]);

  useEffect(() => {
    const el = chatRef.current;
    if (!el || tab !== "chat") return;
    // 空态不滚到底：空态内容在窄高容器里会高于可视区，无条件 scrollTop=scrollHeight
    // 会把 logo 与标题推出顶部（实测 1280×800 下 logo 顶边 y=-5，被状态栏裁掉）。
    // 只有真的有消息时才需要"跟到最新"。
    if (messages.length === 0) {
      el.scrollTop = 0;
      return;
    }
    if (!pinnedUp) el.scrollTop = el.scrollHeight;
  }, [messages, pinnedUp, tab]);

  if (needToken) return <TokenGate onDone={onTokenSubmitted} />;

  return (
    <div className="app">
      <header className="topbar">
        <button
          className="icon-btn"
          onClick={() => setShowSessions(true)}
          aria-label="会话列表"
          title="会话列表"
        >
          <IconSessions />
        </button>
        <div className="brand">
          <PocketLogo size={32} />
          <div className="brand-text">
            <h1>口袋智灵</h1>
            <p className="brand-sub">{mock ? "离线演示模式" : tabLabel(tab)}</p>
          </div>
        </div>
        <div className="topbar-right">
          <button
            className={`pill ${statusKind} pill-btn`}
            title={`后端状态：${statusText}（点击查看运行环境）`}
            onClick={() => setTab("tasks")}
          >
            <i className={`pill-dot ${statusKind}`} aria-hidden="true" />
            {statusText}
          </button>
          <button
            className="icon-btn"
            onClick={() => setShowSettings(true)}
            title="设置"
            aria-label="设置"
          >
            <IconGear />
          </button>
        </div>
      </header>

      {tab === "chat" && (
        <>
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
                  onBrowseCapabilities={() => setTab("capability")}
                />
              ) : (
                <ChatErrorBoundary
                  onReset={() => {
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
              <button className="btn sm" onClick={reloadPage}>
                刷新页面
              </button>
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
        </>
      )}

      {tab === "capability" && <CapabilityPage onRun={runFromCapability} />}

      {tab === "tasks" && (
        <TasksPage
          onOpenSession={() => {
            setTab("chat");
          }}
        />
      )}

      <BottomTabs
        active={tab}
        onChange={setTab}
        badge={{ tasks: pendingApprovals.length }}
      />

      {showSessions && (
        <SessionList
          sessions={sessions}
          currentId={sessionId}
          onClose={() => setShowSessions(false)}
          onPick={(sid) => {
            setShowSessions(false);
            setTab("chat");
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
    </div>
  );
}

function tabLabel(t: TabKey): string {
  return t === "chat" ? "本地智能体 · 数据只在你设备上" : t === "capability" ? "它能替你做哪些事" : "常驻任务与运行状态";
}
