import { useEffect, useState } from "react";
import { isBatteryOptimizationIgnored, isNativeShell, requestIgnoreBatteryOptimization } from "../native";

/**
 * 后台保活引导。
 *
 * 为什么需要：Android 的 Doze / App Standby 会在锁屏后限制后台网络与 CPU。
 * 无论后端是 APK 内的原生引擎还是 Termux 里的 agentd，系统一旦冻结进程，
 * SSE 会断、定时任务不触发、审批提醒收不到 —— 用户看到的表象是
 * "放一会儿就不提醒了"，而根因在系统省电策略。
 *
 * 常驻类应用的官方指引都是「设置 → 电池 → 不受限制」。这里只在
 * 「确实是壳内 + 尚未豁免」时提示一次，不打断聊天（做成设置面板里的一行）。
 */
export function BatteryGuide() {
  const [needsGuide, setNeedsGuide] = useState(false);
  const [requested, setRequested] = useState(false);

  useEffect(() => {
    // 只有 Android 壳里才有这个概念；浏览器（含 PWA）一律不显示
    if (!isNativeShell()) return;
    setNeedsGuide(!isBatteryOptimizationIgnored());
  }, []);

  if (!needsGuide) return null;

  return (
    <div className="battery-guide" role="note">
      <div className="battery-guide-text">
        <strong>建议关闭电池优化</strong>
        <p>
          系统休眠时会限制后台网络，导致消息推送与定时任务延迟。
          将本应用设为「不受限制」可避免掉线。
        </p>
      </div>
      <button
        type="button"
        className="btn sm"
        onClick={() => {
          if (requestIgnoreBatteryOptimization()) {
            setRequested(true);
            // 用户从系统页面返回后重新检测（onResume 会重跑 effect 之外的状态）
            window.setTimeout(() => setNeedsGuide(!isBatteryOptimizationIgnored()), 1200);
          }
        }}
      >
        {requested ? "已打开设置" : "去设置"}
      </button>
    </div>
  );
}
