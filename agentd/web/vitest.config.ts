/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Vitest 配置：复用 Vite 的 react 插件，使用 jsdom 模拟浏览器环境。
// 测试入口在 src/**/*.test.ts(x)，setup 文件引入 @testing-library/jest-dom 匹配器。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    // e2e/ 是 Playwright 用例（另跑 npm run test:e2e），不要被 Vitest 收进来
    exclude: ["e2e/**", "node_modules/**", "dist/**", "playwright.config.ts"],
    // 默认按 CPU 核数 fork worker，每个 jsdom worker 约占 100-150MB。低内存机器上
    // 会直接 OOM（worker 退出码 134），门禁表现为随机变红。线程池 + 限制并发把峰值压下来。
    pool: "threads",
    maxWorkers: 2,
    minWorkers: 1,
    // 不要设 isolate:false。实测它会跨文件泄漏 jsdom 全局与 DOM，settingsPanel 的用例
    // 报 "Found multiple elements with the role button and name 保存"（5 个用例必红）。
    // 省下的启动开销远不值得引入这种间歇性假红。
    testTimeout: 15000,
  },
});
