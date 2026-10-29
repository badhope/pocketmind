import { useState } from "react";
import { setToken } from "../api";

/**
 * 访问令牌输入层。
 *
 * 为什么必须有：agentd 在 Android/Termux 上默认带令牌启动（127.0.0.1 是全机共享的，
 * 不鉴权等于把整台手机的会话、文件与 shell 交给同机任意 App）。令牌由安装脚本
 * 随机生成并存在手机上；APK 壳会自动读取并注入，但有两种情况仍需手动输入：
 *   1. 用浏览器 / PWA 访问（没有壳的桥）；
 *   2. 用户改过令牌，或壳读取失败（Termux 未授权）。
 *
 * 早期版本只在「设置面板」里提供令牌输入，而设置面板本身需要令牌才能加载 ——
 * 首次用 --lan 启动的用户会卡在 401 死锁里，无路可走。这一层把入口提前到首屏。
 */
export function TokenGate({ onDone }: { onDone: () => void }) {
  const [value, setValue] = useState("");
  const [reveal, setReveal] = useState(false);

  const submit = () => {
    const t = value.trim();
    if (!t) return;
    setToken(t);
    onDone();
  };

  return (
    <div className="token-gate" role="dialog" aria-modal="true" aria-labelledby="token-gate-title">
      <div className="token-gate-card">
        <h2 id="token-gate-title">需要访问令牌</h2>
        <p className="token-gate-hint">
          本机服务已开启鉴权（Android 上 127.0.0.1 全机共享，必须鉴权）。
          令牌在手机的 Termux 里：
        </p>
        <code className="token-gate-cmd">cat ~/.agent/termux-agent/token</code>
        <label className="token-gate-label" htmlFor="token-input">
          访问令牌
        </label>
        <div className="token-gate-row">
          <input
            id="token-input"
            className="token-gate-input"
            type={reveal ? "text" : "password"}
            autoComplete="off"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
            }}
            placeholder="粘贴令牌后回车"
          />
          <button
            type="button"
            className="btn sm"
            onClick={() => setReveal((v) => !v)}
            aria-label={reveal ? "隐藏令牌" : "显示令牌"}
          >
            {reveal ? "隐藏" : "显示"}
          </button>
        </div>
        <button type="button" className="btn primary token-gate-submit" onClick={submit} disabled={!value.trim()}>
          连接
        </button>
        <p className="token-gate-note">
          忘记令牌：在 Termux 执行 <code>python -m agentd.main --clear-token</code> 后重启服务。
        </p>
      </div>
    </div>
  );
}
