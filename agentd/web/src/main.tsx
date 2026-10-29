import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { applyTheme } from "./theme";
import "./styles.css";
import { adoptNativeToken } from "./api";
import { readNativeToken } from "./native";

// Android 壳：首帧前尝试从 Termux 取本机令牌。
//
// agentd 在手机上带令牌启动（127.0.0.1 全机共享，不能裸奔），令牌由 install.sh
// 生成。壳把它读出来后写进 localStorage，后续所有请求自动带 Authorization，
// 用户无需手动输入。非壳环境 readNativeToken 返回空串，这里静默跳过。
adoptNativeToken(readNativeToken());

// 首帧前应用主题，避免闪烁
applyTheme();

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// 生产环境注册 Service Worker（PWA 离线壳）；dev 模式不注册，避免干扰热更新
if ("serviceWorker" in navigator && import.meta.env.PROD) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  });
}
