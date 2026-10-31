// Playwright E2E 配置：
// - 浏览器直接打 agentd 托管的页面（baseURL=127.0.0.1:8793）
// - webServer 每次自起 mock 后端（python -m agentd.main --mock），跑在全新数据目录上
// - 沙箱/本地若未装浏览器：先 `npx playwright install chromium`（CI 用 --with-deps）
import { defineConfig, devices } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// webServer 需要从仓库根目录跑 `python -m agentd.main`（相对 web/ 向上两级）
// 注意：本工程 package.json 为 "type": "module"，不能用 __dirname
const here = fileURLToPath(new URL(".", import.meta.url));
const repoRoot = path.resolve(here, "../..");
const python = process.env.PYTHON || (process.platform === "win32" ? "python" : "python3");
const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH;

// 每次运行都从空数据目录开始：复用过期的 /tmp/agentd-e2e 会让上一轮的
// permission_mode=auto（审批卡不再弹出）和历史会话（".row.agent" 取最后一条时错位）
// 串进本轮，表现为随机的 "waitForSelector .approval" 超时与「收到：你好」找不到。
// 用 mkdtemp 而不是"删掉固定目录"：Windows 上上一轮刚退出的 agentd 仍持有
// agent.db 句柄，rmSync 固定路径会 EPERM；唯一目录既保证干净，也不会互相删。
const e2eHome =
  process.env.E2E_AGENT_HOME || fs.mkdtempSync(path.join(os.tmpdir(), "agentd-e2e-"));

export default defineConfig({
  testDir: path.resolve(here, "e2e"),
  // 每轮结束删掉 mkdtemp 出来的数据目录（E2E_AGENT_HOME 显式指定时不动）
  globalTeardown: path.resolve(here, "e2e/global-teardown.ts"),
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: "http://127.0.0.1:8793",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        ...(executablePath ? { launchOptions: { executablePath } } : {}),
      },
    },
  ],
  webServer: {
    // mock 模式后端：不消耗 LLM 额度，发"你好"会回固定文案，适合确定性 E2E
    // 默认按平台选择解释器；门禁通过 PYTHON 显式传入项目环境，避免 Windows 上
    // 误用没有 FastAPI 依赖的系统 Python。
    command: `${python} -m agentd.main --mock --port 8793`,
    cwd: repoRoot,
    url: "http://127.0.0.1:8793/api/health",
    timeout: 60_000,
    // 必须自起：复用已有进程时 Playwright **不执行**下面的 env，
    // 隔离用的 AGENT_HOME 会被静默忽略，测试就跑到别人的数据/权限模式上
    // （实测表现为审批卡不弹、SSE 回复收不到，且只在第二次及以后运行出现）。
    // 代价是 8793 被占用时会直接报错——这是正确的失败方式，而不是悄悄测错对象。
    reuseExistingServer: false,
    env: {
      // PATH 前置 faketermux 桩（由 tests/setup_faketermux.sh 生成到 /tmp/faketermux）；
      // 显式用 e2eHome 而不是外层 AGENT_HOME，避免被 m5/p2 改过的实例串扰
      AGENT_HOME: e2eHome,
      PATH: `/tmp/faketermux:${process.env.PATH || ""}`,
    },
  },
});
