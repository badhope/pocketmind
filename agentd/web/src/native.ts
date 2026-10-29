// PocketNative JS Bridge 封装：统一 Android WebView 注入对象 PocketNative 的调用。
// 在浏览器 / 非 WebView 环境下优雅降级（no-op 或 fallback），不抛错。

/**
 * PocketNative 桥接对象声明（Android WebView 注入的全局变量）。
 * 这里只声明我们用到的方法；运行时 typeof 检查决定是否走原生通道。
 */
interface PocketNativeBridge {
  /** 触发设备振动（毫秒） */
  vibrate?: (ms: number) => void;
  /** 写入系统剪贴板（Android 原生实现，不依赖 HTTPS） */
  copyText?: (text: string) => void;
  /** 触发页面重载 */
  reload?: () => void;
  /** 发一条系统通知（Android 13+ 首次调用会申请权限） */
  notify?: (title: string, body: string) => void;
  /** 从 Termux 读取 agentd 访问令牌；读不到返回空串 */
  readToken?: () => string;
  /** 默认部署仓库地址（由原生 resValue 注入，与 Java 同源） */
  getRepoUrl?: () => string;
  /** 一键部署：在 Termux 中装依赖、克隆仓库、安装并启动 agentd */
  deployAgent?: (repoUrl: string) => void;
  /** 仅启动已部署的 agentd */
  startAgent?: () => void;
  /** 重新执行健康检查 */
  checkAgain?: () => void;
  /** 打开 Termux 的 F-Droid 下载页 */
  openFdroid?: () => void;
  /** 把文本保存为文件（WebView 里 <a download> 不生效，走原生保存）；返回保存路径 */
  saveTextFile?: (fileName: string, content: string) => string;
  /** 系统是否已豁免本应用的电池优化 */
  isBatteryOptimizationIgnored?: () => boolean;
  /** 打开系统的「忽略电池优化」申请页 */
  requestIgnoreBatteryOptimization?: () => void;
}

declare global {
  interface Window {
    PocketNative?: PocketNativeBridge;
  }
}

function getBridge(): PocketNativeBridge | null {
  return typeof window !== "undefined" ? window.PocketNative ?? null : null;
}

/** 是否运行在 Android WebView 壳内（决定是否显示"部署/启动服务"类按钮）。 */
export function isNativeShell(): boolean {
  return getBridge() !== null;
}

/**
 * 振动反馈（Android 原生）。
 * @param ms 振动时长，默认 30
 */
export function vibrate(ms = 30): void {
  try {
    getBridge()?.vibrate?.(ms);
  } catch {
    /* 浏览器环境或桥调用失败：忽略 */
  }
}

/**
 * 复制文本到系统剪贴板。
 * WebView 优先走原生（无 HTTPS 限制）；浏览器降级到 Clipboard API。
 */
export async function copyText(text: string): Promise<boolean> {
  const bridge = getBridge();
  if (bridge?.copyText) {
    try {
      bridge.copyText(text);
      return true;
    } catch {
      /* 降级到浏览器 API */
    }
  }
  if (typeof navigator !== "undefined" && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      return false;
    }
  }
  return false;
}

/** 触发页面重载（WebView 走原生 reload 以保留 WebView 状态；浏览器走 location.reload）。 */
export function reloadPage(): void {
  const bridge = getBridge();
  if (bridge?.reload) {
    try {
      bridge.reload();
      return;
    } catch {
      /* 降级 */
    }
  }
  if (typeof window !== "undefined") {
    window.location.reload();
  }
}

/** 系统通知（仅 WebView 壳内可用；浏览器内 no-op）。 */
export function notify(title: string, body: string): void {
  try {
    getBridge()?.notify?.(title, body);
  } catch {
    /* 忽略 */
  }
}

/**
 * 从 Termux 读取访问令牌。
 *
 * 为什么需要：Android 上 127.0.0.1 是全机共享的，agentd 必须带令牌启动；
 * 令牌由 install.sh 随机生成并写进手机本地文件，壳通过 Termux 读出来交给 WebView，
 * 用户不必手抄一串 64 位十六进制。
 *
 * @returns 令牌字符串；非壳环境或读取失败时返回 ""
 */
export function readNativeToken(): string {
  try {
    return getBridge()?.readToken?.() ?? "";
  } catch {
    return "";
  }
}

/** 默认部署仓库地址（壳内可用；浏览器返回 ""）。 */
export function getNativeRepoUrl(): string {
  try {
    return getBridge()?.getRepoUrl?.() ?? "";
  } catch {
    return "";
  }
}

/** 一键部署（仅壳内）。返回是否已交给原生层。 */
export function deployAgent(repoUrl: string): boolean {
  const bridge = getBridge();
  if (!bridge?.deployAgent) return false;
  try {
    bridge.deployAgent(repoUrl);
    return true;
  } catch {
    return false;
  }
}

/** 仅启动已部署的 agentd（仅壳内）。 */
export function startAgent(): boolean {
  const bridge = getBridge();
  if (!bridge?.startAgent) return false;
  try {
    bridge.startAgent();
    return true;
  } catch {
    return false;
  }
}

/** 重新执行健康检查（仅壳内）。 */
export function checkAgain(): boolean {
  const bridge = getBridge();
  if (!bridge?.checkAgain) return false;
  try {
    bridge.checkAgain();
    return true;
  } catch {
    return false;
  }
}

/**
 * 保存文本为文件。
 *
 * WebView 里 `<a download>` + blob: 不会触发任何下载（DownloadListener 收不到 blob），
 * 所以导出备份必须走原生保存。非壳环境返回 null，调用方应回退到浏览器下载。
 *
 * @returns 保存后的可读路径；非壳环境或失败返回 null
 */
export function saveTextFile(fileName: string, content: string): string | null {
  const bridge = getBridge();
  if (!bridge?.saveTextFile) return null;
  try {
    const path = bridge.saveTextFile(fileName, content);
    return path && path.length > 0 ? path : null;
  } catch {
    return null;
  }
}

/**
 * 电池优化是否已豁免。
 *
 * Android 的 Doze 在锁屏后限制后台网络与 CPU —— agentd 跑在 Termux 里，
 * 一旦被冻结，SSE 断开、定时任务不触发、审批提醒收不到。
 * 非壳环境返回 true（浏览器没有这个概念，不显示引导）。
 */
export function isBatteryOptimizationIgnored(): boolean {
  const bridge = getBridge();
  if (!bridge?.isBatteryOptimizationIgnored) return true;
  try {
    return bridge.isBatteryOptimizationIgnored();
  } catch {
    return true;
  }
}

/** 打开系统的「忽略电池优化」申请页（仅壳内）。 */
export function requestIgnoreBatteryOptimization(): boolean {
  const bridge = getBridge();
  if (!bridge?.requestIgnoreBatteryOptimization) return false;
  try {
    bridge.requestIgnoreBatteryOptimization();
    return true;
  } catch {
    return false;
  }
}
