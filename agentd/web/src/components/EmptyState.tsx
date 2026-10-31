import { IconBattery, IconBell, IconBook, IconBookmark, IconClipboard, IconFolder } from "./icons";
import { PocketLogo } from "./PocketLogo";

// 示例任务：每条都对应一个真实工具，点一下就走完整链路（含审批）。
// 选例原则：覆盖「手机能力 / 文件 / 设备反馈 / 长期记忆 / 知识库」五类，
// 让新用户一眼看出边界——它不只是聊天，也不只是执行命令。
const TASKS = [
  { icon: <IconBattery size={18} />, c: "green", label: "查看电池电量", prompt: "查看手机电池电量" },
  { icon: <IconFolder size={18} />, c: "blue", label: "列出当前目录", prompt: "列出当前目录" },
  { icon: <IconBell size={18} />, c: "amber", label: "发一条通知", prompt: "在通知栏发一条「口袋智灵已就绪」的通知" },
  { icon: <IconClipboard size={18} />, c: "cyan", label: "读取剪贴板", prompt: "读取剪贴板内容" },
  { icon: <IconBookmark size={18} />, c: "green", label: "记住一件事", prompt: "记住：我周末不处理工作消息" },
  { icon: <IconBook size={18} />, c: "blue", label: "存进知识库", prompt: "把「家里 WiFi 密码在客厅抽屉的便签上」存进知识库" },
];

export function EmptyState({
  onPick,
  ready,
  onOpenSettings,
  onBrowseCapabilities,
}: {
  onPick: (t: string) => void;
  ready: boolean;
  onOpenSettings: () => void;
  onBrowseCapabilities: () => void;
}) {
  return (
    <div className="empty">
      <div className="empty-logo">
        <PocketLogo size={88} />
      </div>
      <h2>你好，我是口袋智灵</h2>
      <p className="empty-sub">
        {ready
          ? "我住在这台手机里——会话、记忆、备份都只存在本机，不上云。"
          : "我还不能思考：需要你在设置里填一个模型（数据依然只存在本机）。"}
      </p>
      {ready ? (
        <>
          <p className="empty-hint">先跟我说句话，或者从这些开始：</p>
          <div className="tasks">
            {TASKS.map((t) => (
              <button key={t.label} className="task" onClick={() => onPick(t.prompt)}>
                <span className={`task-icon ${t.c}`}>{t.icon}</span>
                {t.label}
              </button>
            ))}
          </div>
          <button className="empty-browse" onClick={onBrowseCapabilities}>
            查看全部 41 项能力 →
          </button>
          <p className="empty-foot">写文件、发短信、跑命令这类动作，我会先弹卡片让你点头才执行。</p>
        </>
      ) : (
        <button className="btn primary empty-setup" onClick={onOpenSettings}>
          前往设置
        </button>
      )}
    </div>
  );
}
