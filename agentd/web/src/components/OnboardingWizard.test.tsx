// 首次运行引导向导：步骤流转 + 测连门槛 + 跳过不残留。
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const putMock = vi.fn(() => Promise.resolve({}));
const postMock = vi.fn(() => Promise.resolve({ ok: true, model: "glm-4-flash" }));

vi.mock("../api", () => ({
  api: {
    get: vi.fn((path: string) => {
      if (path === "/api/providers") {
        return Promise.resolve({
          providers: [
            { id: "glm", label: "智谱 GLM", base_url: "https://x/v4", model: "glm-4-flash", note: "免费额度" },
            { id: "custom", label: "自定义", base_url: "", model: "", note: "" },
          ],
        });
      }
      return Promise.resolve({});
    }),
    put: (...a: unknown[]) => putMock(...(a as [])),
    post: (...a: unknown[]) => postMock(...(a as [])),
  },
}));

import { OnboardingWizard, onboardingDismissed, markOnboarded } from "./OnboardingWizard";

describe("OnboardingWizard", () => {
  beforeEach(() => {
    localStorage.clear();
    putMock.mockClear();
    postMock.mockClear();
  });

  it("默认选中第一个提供商，下一步进入填 Key 步骤", async () => {
    render(<OnboardingWizard onDone={() => {}} />);
    expect(await screen.findByText("智谱 GLM")).toBeInTheDocument();
    expect(screen.getByText("智谱 GLM").closest("button")).toHaveClass("on");
    fireEvent.click(screen.getByRole("button", { name: "下一步" }));
    expect(screen.getByLabelText("API Key")).toBeInTheDocument();
  });

  it("测试连接成功后才能进入下一步；跳过引导立即结束且不再弹出", async () => {
    const done = vi.fn();
    render(<OnboardingWizard onDone={done} />);
    await screen.findByText("智谱 GLM");
    fireEvent.click(screen.getByRole("button", { name: "下一步" }));
    fireEvent.change(screen.getByLabelText("API Key"), { target: { value: "sk-test" } });

    // 未测连：下一步禁用（不留"以为能用其实没通"的死角）
    const nextBtn = screen.getByRole("button", { name: "下一步" });
    expect(nextBtn).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "测试连接" }));
    await waitFor(() => expect(screen.getByText(/连接成功/)).toBeInTheDocument());
    // 测连前先落一版配置，Key 才能被服务端读到
    expect(putMock).toHaveBeenCalledWith("/api/settings", expect.objectContaining({ llm: expect.anything() }));
    expect(nextBtn).toBeEnabled();

    fireEvent.click(nextBtn);
    fireEvent.click(screen.getByRole("button", { name: /逐次审批/ }));
    fireEvent.click(screen.getByRole("button", { name: "完成" }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(onboardingDismissed()).toBe(true);
  });

  it("跳过引导：不写任何配置，只标记已完成", () => {
    const done = vi.fn();
    render(<OnboardingWizard onDone={done} />);
    fireEvent.click(screen.getByRole("button", { name: "跳过引导" }));
    expect(done).toHaveBeenCalled();
    expect(onboardingDismissed()).toBe(true);
    expect(putMock).not.toHaveBeenCalled();
  });

  it("markOnboarded 幂等", () => {
    markOnboarded();
    markOnboarded();
    expect(onboardingDismissed()).toBe(true);
  });
});
