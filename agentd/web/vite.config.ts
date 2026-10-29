import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发模式：浏览器同源请求 /api/*，由 Vite 代理到 agentd（8787）。
// 生产模式：agentd 直接托管 dist/，无代理、无跨域。
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8787",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    // Android WebView 的版本跟随系统 Chrome，老机型（Android 8/9 出厂机、
    // 长期未更新 WebView 的设备）解析不了 ES2020 的 ?. 与 ??。
    // 目标降到 es2017，由 esbuild 降级语法；运行时 API 不在这里处理。
    target: "es2017",
  },
});
