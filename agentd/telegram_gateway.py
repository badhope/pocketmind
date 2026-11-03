"""Telegram Gateway：用 Telegram Bot 远程控制手机上的 agent。

设计要点（与 main.py 的关系）：
  - ``python-telegram-bot`` 是**可选依赖**：本模块顶层不 import telegram，
    只有真正 ``start()`` 时才惰性导入。未安装 / 未配置 token 时，
    主 FastAPI 服务照常启动，不报错。
  - bot 跑在 ptb 自己的事件循环后台 task（start_polling），与 FastAPI 并发，不阻塞。
  - 复用注入进来的 ``Agent`` 与 ``ApprovalCenter``：审批按钮回调直接调
    ``approval.decide(...)``，与网页端 ``/api/approval`` 走同一把锁，互不冲突。
  - 白名单：只有 ``chat_id`` 命中配置的人才能驱动 agent，其他人一律忽略。

callback_data 编码（Telegram 限制单条 64 字节）：
    approve:<session_id>:<tool_call_id>
    deny:<session_id>:<tool_call_id>
"""
from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # 仅供类型提示，运行期不真正导入（可选依赖）
    from .config import Settings
    from .memory import Store

# 审批按钮动作白名单
_ACTIONS = ("approve", "deny")


def encode_callback(action: str, session_id: str, tool_call_id: str) -> str:
    """把审批意图编码成 Telegram inline 按钮的 callback_data。

    Telegram 规定 callback_data ≤ 64 字节；session_id / tool_call_id 都是短 id，安全。
    """
    return f"{action}:{session_id}:{tool_call_id}"


def decode_callback(data: str | None) -> tuple[str, str, str] | None:
    """反解 callback_data；格式非法或动作不在白名单时返回 None。"""
    if not data:
        return None
    try:
        action, sid, tid = str(data).split(":", 2)
    except ValueError:
        return None
    if action not in _ACTIONS or not sid or not tid:
        return None
    return action, sid, tid


def should_start(token: str | None, chat_id: str | None) -> bool:
    """是否应该启动 Telegram gateway：token 与 chat_id 都非空才启用。"""
    return bool(token and str(token).strip() and chat_id and str(chat_id).strip())


class TelegramGateway:
    """Telegram 远程控制网关。通过构造函数注入 store / settings / approval，
    避免反向 import main.py（main.py 在 startup 里 new 本类）。"""

    def __init__(
        self,
        token: str,
        chat_id: str,
        store: Store,
        settings: Settings,
        approval,
        mock: bool = False,
    ):
        self.token = str(token).strip()
        self.chat_id = str(chat_id).strip()
        self.store = store
        self.settings = settings
        # 注入 main.py 的 ApprovalCenter（鸭子类型：需要 ask / decide 两个方法）
        self.approval = approval
        self.mock = mock
        # ptb 的 Application 在 start() 里构建；初始为 None。
        # 标 Any 而非 None：mypy 会把 __init__ 的 None 赋值推断为 None 类型，
        # 导致 start() 里 add_handler/initialize 等全部报「None 无此属性」
        self.app: Any = None  # ptb Application，start() 后才有
        # 每个 telegram chat 对应一个 agent 会话 id（重启后重建，不持久化）
        self._chat_sessions: dict[int, str] = {}

    # ---------------- 生命周期 ----------------
    async def start(self) -> None:
        """构建 Application 并开始长轮询。未安装 ptb 时抛 ImportError，由 main.py 兜底。"""
        from telegram.ext import (
            ApplicationBuilder,
            CallbackQueryHandler,
            CommandHandler,
            MessageHandler,
            filters,
        )

        self.app = ApplicationBuilder().token(self.token).build()
        self.app.add_handler(CommandHandler("start", self._cmd_start))
        self.app.add_handler(CommandHandler("help", self._cmd_help))
        self.app.add_handler(CallbackQueryHandler(self._on_callback))
        # 普通文本（排除命令）交给 agent；其他类型（贴纸/语音等）直接忽略
        self.app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, self._on_message))

        await self.app.initialize()
        await self.app.start()
        # start_polling 非阻塞：后台 task 拉取更新，本函数立即返回，FastAPI 继续跑
        await self.app.updater.start_polling()

    async def stop(self) -> None:
        """优雅停掉轮询与应用（FastAPI shutdown 时调用）。"""
        if self.app is None:
            return
        try:
            if self.app.updater is not None:
                await self.app.updater.stop()
            await self.app.stop()
            await self.app.shutdown()
        except Exception:  # noqa: BLE001 —— 关闭阶段的异常不阻塞退出
            pass

    # ---------------- 白名单 ----------------
    def _allowed(self, chat_id: int) -> bool:
        """只放行配置的 chat_id；其它人发到 bot 的消息一律不理。"""
        try:
            return str(chat_id) == self.chat_id
        except (TypeError, ValueError):
            return False

    # ---------------- 命令 ----------------
    async def _cmd_start(self, update, context) -> None:
        if not self._allowed(update.effective_chat.id):
            return
        await context.bot.send_message(
            update.effective_chat.id,
            "你好，我是口袋智灵。直接发消息就能让我操作这台手机。\n"
            "需要审批的写 / 危险操作，我会在这里弹出允许 / 拒绝按钮。\n"
            "发送 /help 查看可用命令。",
        )

    async def _cmd_help(self, update, context) -> None:
        if not self._allowed(update.effective_chat.id):
            return
        await context.bot.send_message(
            update.effective_chat.id,
            "用法：\n"
            "· 直接发文字 → 我调用工具帮你完成\n"
            "· 审批卡片点 ✅允许 / ❌拒绝\n"
            "· /new → 开一个新会话（清空上文记忆）",
        )

    # ---------------- 会话管理 ----------------
    async def _ensure_session(self, chat_id: int) -> str:
        """为该 chat 复用已存在会话；不存在或已被删则新建。"""
        sid = self._chat_sessions.get(chat_id)
        if sid and await self.store.get_session(sid) is not None:
            return sid
        sid = await self.store.create_session()
        self._chat_sessions[chat_id] = sid
        return sid

    # ---------------- 消息主流程 ----------------
    async def _on_message(self, update, context) -> None:
        chat_id = update.effective_chat.id
        if not self._allowed(chat_id):
            # 未授权用户：不回内容，避免暴露 bot 存在
            return
        text = (update.message.text or "").strip()
        if not text:
            return
        # /new：开新会话（命令已被 filters 排除，这里兼容纯文本触发）
        if text == "/new":
            self._chat_sessions.pop(chat_id, None)
            await context.bot.send_message(chat_id, "已开启新会话。")
            return

        session_id = await self._ensure_session(chat_id)
        try:
            await context.bot.send_chat_action(chat_id, "typing")
        except Exception:  # noqa: BLE001 —— typing 状态失败不影响主流程
            pass
        await self._run_agent(chat_id, session_id, text, context)

    async def _run_agent(self, chat_id: int, session_id: str, text: str, context) -> None:
        """跑一轮 agent：把流式文本攒起来，结束后一次性发回；审批走 inline 按钮。"""
        from .agent import Agent

        agent = Agent(self.store, self.settings, mock=self.mock)
        buf: dict[str, str] = {"text": ""}

        async def send_split(msg: str) -> None:
            """Telegram 单条上限 4096 字，超长按 3500 切分。"""
            msg = msg.strip() or "（空回复）"
            while msg:
                chunk, msg = msg[:3500], msg[3500:]
                try:
                    await context.bot.send_message(chat_id, chunk)
                except Exception:  # noqa: BLE001 —— 发送失败不炸掉任务
                    return

        async def emit(ev: dict) -> None:
            etype = ev.get("type")
            if etype == "text":
                buf["text"] += ev.get("text", "")
            elif etype == "done":
                if buf["text"]:
                    await send_split(buf["text"])
                    buf["text"] = ""
            elif etype == "error":
                await send_split("⚠️ " + str(ev.get("message", "未知错误")))
            # thinking / tool_start / tool_update 等过程事件不逐条回 Telegram，避免刷屏

        async def ask(tid: str, name: str, summary: str, risk: str) -> str:
            """审批门：发带按钮的卡片，然后阻塞等 ApprovalCenter 被按钮回调唤醒。"""
            from telegram import InlineKeyboardButton, InlineKeyboardMarkup

            label = {"danger": "高危", "write": "需写入", "safe": "只读"}.get(risk, risk)
            kb = InlineKeyboardMarkup(
                [
                    [
                        InlineKeyboardButton(
                            "✅ 允许", callback_data=encode_callback("approve", session_id, tid)
                        ),
                        InlineKeyboardButton(
                            "❌ 拒绝", callback_data=encode_callback("deny", session_id, tid)
                        ),
                    ]
                ]
            )
            try:
                await context.bot.send_message(
                    chat_id,
                    f"🔔 需要审批（{label}）\n{summary}\n工具：{name}",
                    reply_markup=kb,
                )
            except Exception:  # noqa: BLE001 —— 卡片发不出就直接走超时拒绝
                pass
            # 复用 main.py 的 ApprovalCenter.ask（含超时 / 始终允许记忆）
            return await self.approval.ask(session_id, tid, name)

        try:
            await agent.chat(session_id, text, emit=emit, ask_approval=ask)
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 —— 兜底，不能让一条消息拖垮 bot
            await send_split(f"⚠️ 处理失败：{e}")
        finally:
            # 确保每个消息的 Agent 连接池都被关闭（防 FD / 连接泄漏）
            await agent.aclose()

    # ---------------- 审批按钮回调 ----------------
    async def _on_callback(self, update, context) -> None:
        q = update.callback_query
        parsed = decode_callback(getattr(q, "data", None))
        if not parsed:
            await q.answer("无法识别的按钮", show_alert=False)
            return
        action, sid, tid = parsed
        # 安全：只允许该 chat 内正在跑的会话来审批，避免跨会话伪造
        chat_id = update.effective_chat.id
        if not self._allowed(chat_id) or self._chat_sessions.get(chat_id) != sid:
            await q.answer("审批已过期或不属于本会话", show_alert=True)
            return

        decision = "allow_once" if action == "approve" else "deny"
        ok = self.approval.decide(sid, tid, decision)
        if not ok:
            await q.answer("审批已过期", show_alert=True)
            return
        await q.answer("已允许" if action == "approve" else "已拒绝")
        # 把原按钮卡片置灰，避免重复点
        try:
            verdict = "→ ✅ 已允许" if action == "approve" else "→ ❌ 已拒绝"
            await q.edit_message_reply_markup(reply_markup=None)
            await q.edit_message_text((q.message.text or "") + "\n\n" + verdict)
        except Exception:  # noqa: BLE001 —— 消息已被编辑等错误可忽略
            pass
