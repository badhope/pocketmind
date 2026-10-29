import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { applyTheme } from "./theme";
import "./styles.css";
import { adoptNativeToken } from "./api";
import { readNativeToken } from "./native";

// Android 壳：从 Termux 取本机令牌。
//
// agentd 在手机上带令牌启动（127.0.0.1 全机共享，不能裸奔），令牌由 install.sh
// 生成。壳通过 RUN_COMMAND 异步读回 —— 首次同步调用必然是空串，
// 因此这里做三件事：
//   1. 先试同步读（缓存命中时立即成功）；
//   2. 监听原生层的 pocket-token 事件（TokenGate 等输入框打开时到达）；
//   3. 短轮询几次兜底（事件在页面初始化前抛出时会丢）。
// 拿到令牌后写 localStorage；已有令牌时不覆盖。
function bootstrapNativeToken() {
  const apply = (raw: string) => {
    if (adoptNativeToken(raw)) {
      // 令牌刚到位：让 store 重新走一次 init（此前是 401 状态）
      window.dispatchEvent(new CustomEvent("pa-token-ready"));
    }
  };

  apply(readNativeToken());

  window.addEventListener("pocket-token", (e) => {
    const detail = (e as CustomEvent<string>).detail;
    if (typeof detail === "string") apply(detail);
  });

  let tries = 0;
  const timer = window.setInterval(() => {
    tries += 1;
    const t = readNativeToken();
    if (t) {
      apply(t);
      window.clearInterval(timer);
    } else if (tries >= 10) {
      window.clearInterval(timer);
    }
  }, 500);
}

bootstrapNativeToken();

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
