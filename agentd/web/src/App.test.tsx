// App 组件冒烟测试：渲染不崩溃即可。
// 通过 mock api 模块避免真实网络请求。
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

// mock 掉整个 api 模块：init 流程会依次打 /api/health、/api/sessions、POST /api/sessions、GET messages
vi.mock("./api", () => {
  return {
    api: {
      get: vi.fn((path: string) => {
        if (path === "/api/health") {
          return Promise.resolve({ ok: true, ready: true, mock: false });
        }
        if (path === "/api/sessions") {
          return Promise.resolve([]); // 无会话 → 触发新建
        }
        if (path.endsWith("/messages")) {
          return Promise.resolve([]);
        }
        return Promise.resolve({});
      }),
      post: vi.fn((path: string) => {
        if (path === "/api/sessions") return Promise.resolve({ session_id: "new-sid" });
        return Promise.resolve({});
      }),
      put: vi.fn(() => Promise.resolve({})),
      del: vi.fn(() => Promise.resolve({})),
      sseChat: vi.fn(() => Promise.resolve()),
    },
    exportAll: vi.fn(() => Promise.resolve({})),
    renameSession: vi.fn(() => Promise.resolve({})),
    undoToolCall: vi.fn(() => Promise.resolve({})),
  };
});

import App from "./App";
import { useAgentStore } from "./store/useAgentStore";

describe("App 冒烟测试", () => {
  beforeEach(() => {
    // 重置 store，避免上一个用例残留状态
    useAgentStore.setState({
      sessions: [],
      sessionId: "",
      messages: [],
      busy: false,
      ready: false,
      mock: false,
      error: "",
      showSessions: false,
      showSettings: false,
      showTimers: false,
    });
  });

  it("渲染不崩溃，并显示品牌名", async () => {
    render(<App />);
    // 顶栏品牌名一定在
    expect(await screen.findByText("口袋智灵")).toBeInTheDocument();
  });

  it("ready 状态下顶栏显示「已就绪」", async () => {
    render(<App />);
    expect(await screen.findByText("已就绪")).toBeInTheDocument();
  });

  // 回归：审批卡是独立于 messages 的 state。自动滚动原先只依赖 messages，
  // 卡片渲染后把内容推高却不重新滚动，导致「允许一次」等决策键落到输入框下面
  // （实测 1280×900 完全不可见，用户以为 Agent 卡住）。锁住「审批卡出现也会滚到底」。
  it("审批卡出现时会把消息区滚到底", async () => {
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, "scrollHeight", "get");
    scrollHeightSpy.mockReturnValue(1200);
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, "clientHeight", "get");
    clientHeightSpy.mockReturnValue(400);
    try {
      const { rerender } = render(<App />);
      await screen.findByText("口袋智灵");

      // 先有消息：滚动逻辑进入「有消息」分支
      useAgentStore.setState({
        messages: [{ id: "1", role: "user", parts: [{ type: "text", text: "hi" }], done: true }],
      });
      rerender(<App />);
      await Promise.resolve();

      const chat = document.querySelector(".chat") as HTMLElement;
      expect(chat).toBeTruthy();
      chat.scrollTop = 0;

      // 再让审批卡出现：长度变化必须触发重新滚动
      useAgentStore.setState({
        pendingApprovals: [
          {
            session_id: "s",
            tool_call_id: "c",
            name: "send_notification",
            summary: "x",
            risk: "write",
            remaining: 100,
            timeout: 120,
          },
        ],
      });
      rerender(<App />);
      await Promise.resolve();

      expect(chat.scrollTop).toBe(1200);
    } finally {
      scrollHeightSpy.mockRestore();
      clientHeightSpy.mockRestore();
    }
  });
});
