"""口袋智灵 · agentd —— 手机上的本地智能体服务（单进程）。

启动：
  python -m agentd.main                     # 本机访问，端口 8787
  python -m agentd.main --lan               # 局域网访问（会要求设置访问令牌）
  python -m agentd.main --mock              # 离线 mock 模式（不调用 LLM，用于自测）

安全要点（对比旧实现的修复）：
  - 默认只绑 127.0.0.1；--lan 时强制要求 Bearer token；
  - 工具审批由服务端强制，approve 模式下写/危险操作必须用户确认；
  - GET /api/settings 只返回打码后的 API Key。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import re
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, cast

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sse_starlette.sse import EventSourceResponse, ServerSentEvent

from .agent import Agent
from .approval import ApprovalCenter
from .config import PRESETS, Settings, mask_key
from .mcp import MCPClient, mcp_tool_risk
from .memory import Store
from .notify import notify
from .profile_memory import ProfileMemory
from .skills import SkillRegistry
from .tools import Tool, register


def _sse_event(ev: dict) -> ServerSentEvent:
    """把事件 dict 编码成与原手写 SSE 逐字节一致的帧：``data: <json>\\n\\n``。

    sse-starlette 若直接 yield dict 会把键当作 SSE 字段名（event/id/retry…），
    无法把整条事件 dict 放进 data 字段，因此这里统一显式构造 ServerSentEvent。
    sep 固定为 "\\n"，与原手写 ``f"data: {...}\\n\\n"`` 完全一致。
    """
    return ServerSentEvent(data=json.dumps(ev, ensure_ascii=False), sep="\n")


VERSION = "1.3.0"
APP_NAME = "口袋智灵"

# 导出格式版本（/api/export 的 format_version 字段）。
# 只在「读取语义发生变化、旧版导入器会误解数据」时递增；纯增量字段（新增顶层键）
# 不需要递增 —— 导入端对未知键一律忽略，旧备份也能导入新版本。
# 导入规则：缺失视为 1（历史备份没有这个字段）；大于当前值 → 400，
# 提示先升级本机，绝不把更高版本的备份「猜」着导进去。
EXPORT_FORMAT_VERSION = 1

DIST = Path(__file__).parent / "web" / "dist"


# ---------- 应用全局状态 ----------
# 这些是模块级单例：run_agent / run_agent_for_job 必须是模块级函数（scheduler 触发时
# 经延迟导入调用），因此它们依赖的 store / runs / approval 也必须是模块级全局。
settings_mgr = Settings()
store: Store | None = None
mem0: Any = None  # Mem0Memory 实例（Optional，不启用时为 None）
profile: ProfileMemory | None = None  # 结构化长期记忆（USER/FACT 文件层）
skills: SkillRegistry | None = None   # SKILL.md 技能注册表
_scheduler: Any = None  # SchedulerService 实例


def _approval_timeout() -> int:
    """从 settings_mgr 读取审批超时（秒）。"""
    try:
        return int(settings_mgr.get().get("server", {}).get("approval_timeout", 120))
    except (TypeError, ValueError):
        return 120


def _approval_always_path() -> Path | None:
    """返回审批白名单持久化路径（agent 数据目录下）。"""
    try:
        return settings_mgr.path.parent / "approval_always.json"
    except Exception:
        return None


approval = ApprovalCenter(timeout_cb=_approval_timeout, persist_path=_approval_always_path())
runs: dict[str, dict] = {}  # session_id -> {task, queue, pending}
MOCK = False
# Telegram 远程控制：CLI 覆盖值（为空则回落到 settings.server.tg_*）
TG_TOKEN: str = ""
TG_CHAT_ID: str = ""
tg_gateway = None  # TelegramGateway 实例（未启用时为 None）


def _new_run(queue: asyncio.Queue, pending: list[str] | None = None) -> dict:
    """统一的 run 记录结构。

    所有字段一次给全：此前两处字面量一个有 cancelled 一个没有，
    读的地方靠 .get("cancelled") 默认值兜着——能跑，但"取消语义"依赖默认值
    是很容易被后来者改错的写法（新增入口漏字段 → 取消失效）。
    """
    return {"task": None, "queue": queue, "pending": pending or [], "cancelled": False}


def emit_now(session_id: str):
    """取该会话的 SSE 事件队列，返回一个异步 emit 回调；无进行中任务时返回 None。"""
    q = runs.get(session_id, {}).get("queue")
    if q is None:
        return None

    async def _emit(ev: dict):
        q.put_nowait(ev)

    return _emit


def _notify_bg(title: str, content: str, persistent: bool = False) -> None:
    """后台发通知：notify() 内部是阻塞的 subprocess.run，绝不能在事件循环里直接调。

    丢线程池跑 + fire-and-forget，调用方不 await——这样在 CancelledError / 异常收尾
    路径里也不会因为再 await 而被二次取消打断。
    """
    try:
        asyncio.get_running_loop().create_task(
            asyncio.to_thread(notify, title, content, persistent)
        )
    except RuntimeError:
        notify(title, content, persistent)


async def run_agent(session_id: str, message: str) -> None:
    """处理一条用户消息：新会话自动命名 → Agent 循环 → 结束后续跑排队消息。"""
    _notify_bg("口袋智灵", f"正在处理：{message[:40]}", persistent=True)
    agent = None
    try:
        # store 在 lifespan 启动时创建，本函数只会于服务就绪后被调用；
        # assert 仅做 None 收窄（mypy），运行时恒为真
        assert store is not None
        # 未配置模型时快速失败并给出明确指引：否则 AsyncOpenAI 会拿默认 base_url
        # 打 api.openai.com 并重试，用户要对着「正在思考…」干等十几分钟才看到报错。
        # 注意必须放在 try 内：提前 return 也要走 finally（runs.pop + 续跑排队消息），
        # 否则该会话会一直留在 runs 里，后续每条消息都被判成「排队」而永不执行。
        if not MOCK and not settings_mgr.llm_ready():
            missing = "、".join(settings_mgr.llm_missing()) or "模型配置"
            note = f"（未能执行：尚未配置模型，缺少{missing}。请打开「设置 → 模型」填写后重试。）"
            # 用户消息先落库（正常路径由 Agent.chat 落库，这里提前返回，得自己记），
            # 再补一条说明；否则重开会话只剩「未能执行」而看不到用户说了什么。
            await store.add_message(session_id, "user", message)
            await store.add_message(session_id, "assistant", note)
            emit = emit_now(session_id)
            if emit:
                await emit(
                    {"type": "error", "message": f"尚未配置模型（缺少{missing}）：请打开「设置 → 模型」填写后重试。"}
                )
                await emit({"type": "done", "stop_reason": "unconfigured"})
            _notify_bg("口袋智灵 · 未配置模型", f"缺少{missing}", persistent=False)
            return
        # 新会话（尚无 user 消息）自动命名
        if await store.first_user_message(session_id) is None:
            try:
                title_agent = Agent(store, settings_mgr, mock=MOCK, mem0=mem0, profile=profile, skills=skills)
                try:
                    title = await title_agent.title_for(message)
                finally:
                    await title_agent.aclose()
                await store.touch_session(session_id, title or "新会话")
            except Exception:  # noqa: BLE001 —— 命名失败不阻塞
                pass
        agent = Agent(store, settings_mgr, mock=MOCK, mem0=mem0, profile=profile, skills=skills)

        async def ask(tid: str, name: str, summary: str, risk: str) -> str:
            # 「始终拒绝」（全局黑名单）优先于一切：直接拒绝，不推审批卡、不注册 gate。
            # 与「始终允许」同理——否则用户会收到一张自己早已拒绝过的幽灵审批卡。
            if approval.is_auto_denied(name):
                return "deny"
            # 已「始终允许」的工具：不再弹审批卡、不注册 gate，直接放行。
            # 必须在 emit 之前判断——否则前端会收到一张早已自动批准的幽灵审批卡，
            # 用户点同意时 gate 已不存在（404），体验上是「卡了一下又消失」。
            if approval.is_auto_allowed(session_id, name):
                return "allow"
            emit = emit_now(session_id)
            if emit:
                await emit(
                    {"type": "approval", "id": tid, "name": name, "summary": summary, "risk": risk}
                )
                _notify_bg(
                    "口袋智灵 · 需要确认",
                    f"{summary}（{'危险' if risk == 'danger' else '需要' if risk == 'write' else '只读'}操作），去应用里处理",
                    persistent=True,
                )
            return await approval.ask(session_id, tid, name, summary=summary, risk=risk)

        await agent.chat(session_id, message, emit=emit_now(session_id), ask_approval=ask)
        _notify_bg("口袋智灵 · 完成", f"已处理：{message[:30]}", persistent=False)
    except asyncio.CancelledError:
        # 必须显式标记「本轮被取消」并 re-raise：
        # 旧实现吞掉 CancelledError 后任务正常结束，于是 finally 里
        # `run["task"].cancelled()` 恒为 False → pending 队列被当成「正常结束」继续续跑，
        # 用户的「停止」和「删除会话」都拦不住后续消息（删除后还会往已删会话里写数据）。
        run = runs.get(session_id)
        if run:
            run["cancelled"] = True
        q = runs.get(session_id, {}).get("queue")
        if q:
            q.put_nowait({"type": "error", "message": "已停止"})
        _notify_bg("口袋智灵", "已停止")
        raise
    except Exception as e:  # noqa: BLE001 —— 服务端兜底，不能静默
        q = runs.get(session_id, {}).get("queue")
        if q:
            q.put_nowait({"type": "error", "message": str(e)})
        # 异常信息可能含 API Key/内部路径，须脱敏后再通知
        err_raw = str(e)[:120]
        err_safe = re.sub(r'(api_key|token|secret|password)=[^\s&]+', r'\1=***', err_raw, flags=re.IGNORECASE)
        err_safe = re.sub(r'/data/[^\s]+', '/data/***', err_safe)
        err_safe = err_safe[:80]
        _notify_bg("口袋智灵 · 出错", err_safe)
    finally:
        # 关闭本 chat 的 LLM 连接池（AsyncOpenAI 内部 httpx client，防 FD 泄漏）
        if agent is not None:
            try:
                await agent.aclose()
            except Exception:  # noqa: BLE001
                pass
        run = runs.get(session_id)
        pending = (run or {}).get("pending", [])
        cancelled = bool(run and run.get("cancelled"))
        runs.pop(session_id, None)
        # 排队续跑：同一会话期间发来的消息在此自动执行。
        # 但若本轮是被外部取消（用户停止 / SSE 断开 / 删会话），则丢弃 pending，
        # 避免会话已删/已停还在后台偷偷续跑。
        # 还有一个更窄的窗口：本次 run 刚 pop、续跑尚未重建时 DELETE /sessions 到达，
        # cancel_run 拿不到旧 run 也就没置 cancelled —— 此时必须复查会话还在，
        # 否则续跑任务会往已删会话写消息。
        if pending and not cancelled:
            # finally 块不继承 try 内 assert 的收窄（异常可能在 assert 前抛出），需独立收窄
            assert store is not None
            still_exists = await store.get_session(session_id) is not None
        else:
            still_exists = False
        if pending and still_exists:
            nxt = pending.pop(0)
            queue2: asyncio.Queue = asyncio.Queue()
            runs[session_id] = _new_run(queue2, pending)
            task2 = asyncio.create_task(run_agent(session_id, nxt))
            runs[session_id]["task"] = task2


def cancel_run(session_id: str) -> bool:
    run = runs.get(session_id)
    if run:
        # 无论任务是否已在收尾，都要置位：决定「排队消息是否续跑」的是这个标志，
        # 不是 task 状态。删会话路径（main.py 的 DELETE /api/sessions）也依赖它。
        run["cancelled"] = True
        if run.get("task") and not run["task"].done():
            run["task"].cancel()
            return True
    return False


async def run_agent_for_job(session_id: str, message: str) -> None:
    """定时任务触发入口（scheduler._fire_job 经延迟导入调用本模块级函数）。

    会话不存在时自动新建；执行结果写入该会话，可在前端回看。之所以做成模块级顶层
    函数而非闭包，是因为它会被 APScheduler 的可 pickle 回调链路触达。
    """
    # store 在 lifespan 启动时创建；assert 仅做 None 收窄（mypy），运行时恒为真
    assert store is not None
    if not session_id or await store.get_session(session_id) is None:
        session_id = await store.create_session()
    # 必须用 _new_run()：这里此前是手写字面量且少了 "cancelled" 字段，靠
    # run_agent 里的 .get("cancelled") 默认值兜着——一旦默认值改掉，定时任务
    # 的「停止」就会失效（任务还在跑、取消标志读不到）。
    runs.setdefault(session_id, _new_run(asyncio.Queue()))
    await run_agent(session_id, message)


# ---------- Recipe 预设配方（config/recipes.json） ----------
# 在 lifespan 启动时一次性读入内存，避免在 async 路由里做阻塞的文件读取。
RECIPES: list[dict] = []


def _load_recipes() -> None:
    """从仓库根 config/recipes.json 读入预设配方；缺失/损坏时置空列表。"""
    global RECIPES
    p = Path(__file__).resolve().parent.parent / "config" / "recipes.json"
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        RECIPES = [r for r in data if isinstance(r, dict)] if isinstance(data, list) else []
    except Exception:  # noqa: BLE001 —— 配方缺失不阻塞服务
        RECIPES = []


# 从 settings 导入的 validate_job_payload / masked_settings 不再在本文件定义，
# 而是退居 routes 子包（可被独立单测直接 import）。
def create_app() -> FastAPI:
    global store
    store = Store(settings_mgr.path.parent / "agent.db")

    # ---------- 定时/条件触发（SQLAlchemyJobStore 持久化） ----------
    from .scheduler import SchedulerService

    scheduler_svc = SchedulerService(settings_mgr.path.parent / "jobs.db")
    # 同时注册到模块全局（供 /api/health 探活）
    global _scheduler
    _scheduler = scheduler_svc
    mcp_clients: list[MCPClient] = []

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        """FastAPI 生命周期：替代已弃用的 @app.on_event。

        启动顺序：异步存储建连 → 恢复定时任务调度 → 接入 MCP 服务器；
        关闭顺序：停调度 → 断 MCP → 关异步存储。
        """
        await store.start()  # P1-5：异步引擎建连 + WAL + 建表/回填
        # 按 memory.retention_days 清理过期记忆/消息（0 = 永久保留）
        try:
            mem_cfg = settings_mgr.memory_cfg()
            # FTS 回填批量跟随配置（小屏/慢存储可调小，减少启动卡顿）
            store.fts_page_size = int(mem_cfg.get("fts_page_size", 50) or 50)
            await store.cleanup_expired(int(mem_cfg.get("retention_days", 0) or 0))
        except Exception:  # noqa: BLE001 —— 清理失败不影响启动
            pass
        # ---- 结构化长期记忆（USER.md / FACT.md / JOURNAL.jsonl）----
        global profile, skills
        profile = ProfileMemory(settings_mgr.path.parent / "memory")
        try:
            profile.register_tools()  # remember_fact（risk=write，经审批）
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception as e:  # noqa: BLE001
            logging.getLogger(__name__).warning("remember_fact 工具注册失败（%s）", e)
        # ---- Skills：SKILL.md 渐进披露（目录不存在 = 空注册表，不报错）----
        skills = SkillRegistry(settings_mgr.path.parent / "skills")
        try:
            skills.register_tool()  # read_skill 工具（skill 数为 0 时也注册，便于用户放目录后立即生效）
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception as e:  # noqa: BLE001
            logging.getLogger(__name__).warning("read_skill 工具注册失败（%s）", e)
        # ---- Mem0 本地记忆（可选：未配置/依赖缺失时静默跳过）----
        global mem0
        try:
            from .mem0_memory import create_mem0
            mem0 = create_mem0(settings_mgr, settings_mgr.path.parent / "mem0")
            if mem0 is not None and mem0.enabled():
                print(f"[agentd] Mem0 本地记忆已启用（embed={mem0._embedding_model}）")
            else:
                mem0 = None
                print("[agentd] Mem0 未启用（pip install mem0ai chromadb fastembed；或在 settings.mem0.enabled=true）")
        except Exception as e:  # noqa: BLE001 —— 不影响主服务
            mem0 = None
            print(f"[agentd] Mem0 初始化失败（不影响主服务）: {e}")
        # 共享 CheckpointStore：整个进程复用一条连接，避免每次文件工具/undo/删会话
        # 都新建 aiosqlite 连接（CREATE TABLE + commit + dispose 的开销）
        from .checkpoints import CheckpointStore, set_shared

        cs = CheckpointStore(settings_mgr.path.parent / "checkpoints.db")
        await cs.start()
        set_shared(cs)
        _load_recipes()  # 读入预设配方（内存缓存，避免路由内阻塞读文件）
        scheduler_svc.start()
        # P1：MCP 服务器可选接入（mock 模式不连，保持演示环境纯净）
        if not MOCK:
            mcp_configs = settings_mgr.get().get("server", {}).get("mcp", []) or []
            for cfg in mcp_configs:
                if not isinstance(cfg, dict) or not cfg.get("command"):
                    continue
                client = MCPClient(
                    str(cfg.get("name", "mcp")).strip() or "mcp",
                    str(cfg["command"]),
                    [str(a) for a in cfg.get("args", []) or []],
                )
                try:
                    tools = await client.connect()
                except Exception:  # noqa: BLE001 —— 连接失败不阻塞启动
                    await client.close()
                    continue
                for t in tools:
                    tname = str(t.get("name", ""))
                    if not tname:
                        continue
                    full = f"mcp__{client.name}__{tname}"

                    async def _handler(c=client, tn=tname, **args):
                        return await c.call_tool(tn, args)

                    register(
                        Tool(
                            name=full,
                            description=f"[MCP:{client.name}] {(t.get('description') or tname)[:160]}",
                            parameters=t.get("inputSchema") or {"type": "object", "properties": {}},
                            # 风险按 MCP 注解分级：destructiveHint→danger；
                            # readOnlyHint 不降级（外部声明不可信），最低 write
                            risk=mcp_tool_risk(t.get("annotations")),
                            handler=_handler,
                            summary=f"MCP {client.name} · {tname}",
                            timeout=60,
                            group="mcp",
                        )
                    )
                client.tools = [str(t.get("name", "")) for t in tools if t.get("name")]
                mcp_clients.append(client)

        # ---- MCP Client Hub（让 PocketMind 能调用外部 MCP Server）----
        try:
            from .mcp_client import init_mcp_client  # noqa: E402 — 失败时不阻塞启动
            hub = init_mcp_client(settings_mgr.mcp_client_cfg())
            if hub is not None:
                connected = await hub.connect_all()
                logging.getLogger(__name__).info("MCP 客户端已连接 %d/%d 个外部服务", connected, len(hub._clients))
                # 把外部 MCP Server 的工具注册进工具注册表（统一纳入审批）
                for tool in await hub.collect_tools():
                    tname = str(tool.get("name", ""))
                    if "." not in tname:
                        continue
                    server, tool_real = tname.split(".", 1)
                    full = f"mcp__{server}__{tool_real}"

                    async def _mcp_client_handler(server=server, tool_real=tool_real, **kwargs):
                        return await hub.call(f"{server}.{tool_real}", kwargs)

                    register(Tool(
                        name=full,
                        description=f"[MCP Client:{server}] {(tool.get('description') or tool_real)[:160]}",
                        parameters=tool.get("input_schema") or {"type": "object", "properties": {}},
                        # 风险按 MCP 注解分级（详见 mcp_tool_risk）：
                        # destructiveHint→danger；readOnlyHint 不降级，外部工具最低 write
                        risk=mcp_tool_risk(tool.get("annotations")),
                        handler=_mcp_client_handler,
                        summary=f"MCP {server} · {tool_real}",
                        timeout=60,
                        group="mcp",
                    ))
        except Exception as exc:  # noqa: BLE001
            logging.getLogger(__name__).warning("MCP 客户端初始化失败（%s）", exc)

        # ---- Telegraph ----
        global tg_gateway
        try:
            from .telegram_gateway import TelegramGateway, should_start

            srv = settings_mgr.get().get("server", {})
            token = TG_TOKEN or str(srv.get("tg_token", ""))
            chat_id = TG_CHAT_ID or str(srv.get("tg_chat_id", ""))
            if should_start(token, chat_id):
                tg_gateway = TelegramGateway(
                    token=token,
                    chat_id=chat_id,
                    store=store,
                    settings=settings_mgr,
                    approval=approval,
                    mock=MOCK,
                )
                await tg_gateway.start()
                print(f"[agentd] Telegram gateway 已启动（chat_id={chat_id}）")
            else:
                print("[agentd] 未配置 Telegram token/chat_id，跳过 Telegram gateway")
        except ImportError:
            print(
                "[agentd] 未安装 python-telegram-bot，跳过 Telegram gateway（pip install python-telegram-bot）"
            )
        except Exception as e:  # noqa: BLE001 —— gateway 起不来绝不拖垮主服务
            print(f"[agentd] Telegram gateway 启动失败（不影响主服务）: {e}")
        yield
        # ---- 关闭 ----
        if tg_gateway is not None:
            try:
                await tg_gateway.stop()
            except Exception:  # noqa: BLE001
                pass
        scheduler_svc.shutdown()
        # MCP Client 关闭
        try:
            from .mcp_client import shutdown_mcp_client
            await shutdown_mcp_client()
        except Exception:  # noqa: BLE001
            pass
        for c in mcp_clients:
            try:
                await c.close()
            except Exception:  # noqa: BLE001
                pass
        try:
            set_shared(None)
            await cs.close()
        except Exception:  # noqa: BLE001
            pass
        if store is not None:
            await store.close()  # P1-5：关闭异步引擎连接

    app = FastAPI(title=APP_NAME, version=VERSION, lifespan=lifespan)

    # ---------- 请求体大小上限 ----------
    # /api/import 会接收整份备份 JSON，/api/chat 会接收最长 100KB 的消息。
    # 没有上限时，一个畸形请求（或恶意页面发起的同源请求）可以把内存打满。
    # 20MB 足够容纳导出全部会话的备份，同时给内存一个明确边界。
    MAX_BODY_BYTES = 20 * 1024 * 1024

    @app.middleware("http")
    async def _body_limit_mw(request, call_next):
        cl = request.headers.get("content-length")
        if cl is not None:
            try:
                if int(cl) > MAX_BODY_BYTES:
                    from fastapi.responses import JSONResponse

                    return JSONResponse({"detail": "请求体过大（上限 20MB）"}, status_code=413)
            except ValueError:
                pass
        return await call_next(request)

    # ---------- 运行指标（请求耗时/状态码/工具失败率） ----------
    # 用中间件而非每个端点装饰器：端点有 43 个，逐个加会漏；
    # 且中间件能统计到 404/500 这类"没有走到端点"的响应。
    @app.middleware("http")
    async def _metrics_mw(request, call_next):
        import time as _time

        from . import metrics as _m

        t0 = _time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            _m.record_request(request.method, request.url.path, 500, _time.perf_counter() - t0)
            _m.record_error("unhandled")
            raise
        _m.record_request(
            request.method, request.url.path, response.status_code, _time.perf_counter() - t0
        )
        return response

    # ---------- 速率限制（保护高频写端点） ----------
    from .rate_limit import mount_rate_limit
    mount_rate_limit(app)  # default 30 次/分钟，超 429

    # ---------- OpenTelemetry 可观测性（插桩 + tracer） ----------
    from .telemetry import setup_telemetry
    setup_telemetry(app, service_name=APP_NAME, version=VERSION)

    # ---------- CSRF / 跨站防护 ----------
    def check_same_origin(request: Request) -> None:
        """拦掉浏览器发起的跨站请求（**对所有方法**生效，不只是写方法）。

        为什么需要：本机模式下（无令牌）没有任何鉴权，而一部分写端点没有请求体 ——
        无 body 的 POST 属于 CORS simple request，**不触发预检**，
        于是手机浏览器打开任意恶意网页就能静默 POST /api/memory/reset
        （清空全部记忆）或 /api/recipes/<id>/apply（创建并启用定时任务）。
        非浏览器客户端（curl / 安卓 App / 测试脚本）不带 Origin，照常放行。

        为什么 GET 也校验：当前 GET 端点都是只读，跨站 GET 读不到响应内容
        （无 CORS 头）暂无实害；但同源判定对所有方法统一，未来新增带副作用的
        GET 端点时防线不会漏边。GET 只读端点被误拒的概率极低（同源的 GET
        浏览器会带 Origin，主机匹配即放行）。
        """
        origin = request.headers.get("origin")
        if not origin:
            return  # 非浏览器来源（curl / 安卓 App / 测试脚本都不带 Origin）
        # 有 Origin = 浏览器发起：只接受「同主机」的来源。
        # 判定只比主机名，不比端口：本机可能通过 127.0.0.1 / localhost 的
        # 任意端口（如旧版文档页）访问，这些都是同一台设备，安全上等价。
        # Host 头解析用中括号切分而非 split(":")：IPv6 写作 [::1]:port，
        # split 会把 [::1] 错切成 "["（::1 启动时合法浏览器请求被误拒）
        host = request.headers.get("host", "").lower()
        if host.startswith("["):
            host = host[1 : host.find("]")] if "]" in host else ""
        else:
            host = host.split(":")[0]
        if not host:
            raise HTTPException(status_code=403, detail="跨站请求被拒绝")
        try:
            from urllib.parse import urlparse

            origin_host = (urlparse(origin).hostname or "").lower()
        except ValueError:
            origin_host = ""
        # localhost / 127.0.0.1 / ::1 视为同一台设备
        # 注意：urlparse("http://[::1]").hostname 返回的是裸 "::1"（不带中括号），
        # 所以集合里放 "::1" 而不是 "[::1]"（带括号的写法永远匹配不上）
        loopback = {"localhost", "127.0.0.1", "::1"}
        same = origin_host == host or (origin_host in loopback and host in loopback)
        if not same:
            raise HTTPException(status_code=403, detail="跨站请求被拒绝")

    # ---------- 鉴权（局域网开启后强制） ----------
    def check_token(request: Request) -> None:
        check_same_origin(request)
        import hmac

        s = settings_mgr.get()
        token = str(s.get("server", {}).get("token", ""))
        if not token:
            return
        auth = request.headers.get("authorization", "")
        if not auth.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="需要访问令牌")
        given = auth[len("Bearer ") :].strip()
        # 必须先编码成 bytes 再比较：hmac.compare_digest 对含非 ASCII 的 str 会抛
        # TypeError（"comparing strings with non-ASCII characters is not supported"），
        # 那会让每个带 Authorization 的请求变成 500 —— 用户填过中文令牌后连设置页都打不开，
        # 且无法自行改回。字节比较对任意 UTF-8 输入都安全，且保持常量时间语义。
        try:
            same = hmac.compare_digest(given.encode("utf-8"), token.encode("utf-8"))
        except (UnicodeError, TypeError):
            same = False
        if not same:
            raise HTTPException(status_code=401, detail="需要访问令牌")

    # ---------- API ----------
    @app.get("/api/health")
    async def health():
        """Deep health：探活 DB 连通性 + LLM 配置 + Mem0 状态。

        - severity:"ok": 全部可用
        - severity:"degraded": DB 可读但 Mem0 未启用/LLM 未配置（仍可聊天降级）
        - severity:"error": DB 不可读或组件异常
        """
        health_info: dict = {
            "ok": True, "app": APP_NAME, "version": VERSION,
            "mock": MOCK, "active_runs": len(runs), "components": {},
        }
        # 1. DB 探活
        try:
            if store is not None:
                async with store.engine.connect() as conn:
                    await conn.execute(text("SELECT 1"))  # deep health: 实际 DB 连通性
                health_info["components"]["db"] = "ok"
            else:
                health_info["components"]["db"] = "not_initialized"
        except Exception as e:  # noqa: BLE001
            health_info["components"]["db"] = f"error: {type(e).__name__}"
            health_info["ok"] = False

        # 2. LLM 连接性（仅检查配置有效性，不实际调 LLM）
        llm_ready = bool(MOCK or settings_mgr.llm_ready())
        missing = [] if llm_ready else settings_mgr.llm_missing()
        health_info["components"]["llm"] = (
            "ready" if llm_ready else f"not_configured: 缺少{'、'.join(missing)}"
        )
        # 顶层 ready：前端据此决定是否展示「已就绪」与示例任务。
        # 此前 health 不返回该字段，前端读到 undefined 便永远显示「未配置」——
        # 即使模型已配好、可以正常对话（顶栏状态灯与空态文案都被它带偏）。
        health_info["ready"] = llm_ready
        if not llm_ready:
            health_info["llm_missing"] = missing

        # 3. Mem0 状态
        if mem0 is not None:
            ms = mem0.status()
            health_info["components"]["mem0"] = "ok" if ms.get("initialized") else f"degraded: {ms.get('error', 'not_initialized')}"
        else:
            health_info["components"]["mem0"] = "disabled"

        # 4. Scheduler（探活 via main.py 保存的服务实例）
        try:
            health_info["components"]["scheduler"] = "ok" if _scheduler is not None else "not_initialized"
        except Exception:  # noqa: BLE001
            health_info["components"]["scheduler"] = "not_initialized"

        return health_info

    @app.post("/api/chat", dependencies=[Depends(check_token)])
    async def chat(payload: dict, request: Request):
        if store is None:
            raise HTTPException(503, "服务未就绪")
        message = payload.get("message")
        if not isinstance(message, str) or not message.strip():
            raise HTTPException(400, "消息不能为空")
        if len(message) > 100000:
            raise HTTPException(400, "消息过长（最多 10 万字符）")

        session_id = payload.get("session_id")
        if not isinstance(session_id, str) or not session_id.strip():
            session_id = await store.create_session()
        elif await store.get_session(session_id) is None:
            raise HTTPException(404, "会话不存在")

        # 同一会话进行中：消息入队，当前轮结束后由 run_agent → agent.chat 统一落库执行
        # （不在此重复 add_message，否则 agent.chat:166 会再写一次导致重复）
        if session_id in runs:
            runs[session_id].setdefault("pending", []).append(message)
            sid = session_id

            async def queued_sse():
                # 事件字段与原手写 SSE 完全一致，仅改用 sse-starlette 编码
                yield _sse_event({"type": "session", "session_id": sid})
                yield _sse_event(
                    {
                        "type": "queued",
                        "message": "上一轮回复还在进行中，这条已排队，完成后自动执行",
                    }
                )
                yield _sse_event({"type": "done", "stop_reason": "queued"})

            return EventSourceResponse(
                queued_sse(),
                sep="\n",
                headers={"Cache-Control": "no-cache"},
            )

        queue: asyncio.Queue = asyncio.Queue()
        # 先注册再启动任务，避免 run_agent 首帧 emit 时 runs 尚未就绪的竞态
        runs[session_id] = _new_run(queue)
        task = asyncio.create_task(run_agent(session_id, message))
        runs[session_id]["task"] = task

        async def sse():
            # 首帧告知会话 ID（新建会话时前端需要）
            yield _sse_event({"type": "session", "session_id": session_id})
            try:
                while True:
                    ev = await queue.get()
                    # 心跳保活改由 EventSourceResponse(ping=15) 内置发送注释帧，
                    # 这里只转发业务事件；审批等待长达 120s 也不会断连。
                    yield _sse_event(ev)
                    if ev.get("type") in ("done", "error"):
                        break
            except asyncio.CancelledError:
                # 客户端断开（SSE 连接关闭）→ 取消正在跑的 agent 任务
                cancel_run(session_id)
                raise

        return EventSourceResponse(
            sse(),
            sep="\n",
            ping=15,  # 每 15s 自动发注释帧保活（替代原手写 _heartbeat）
            headers={
                "Cache-Control": "no-cache",
            },
        )

    @app.post("/api/stop", dependencies=[Depends(check_token)])
    async def stop(payload: dict):
        session_id = payload.get("session_id", "")
        return {"ok": cancel_run(session_id)}

    @app.post("/api/approval", dependencies=[Depends(check_token)])
    async def approval_endpoint(payload: dict):
        session_id = payload.get("session_id", "")
        tool_call_id = payload.get("tool_call_id", "")
        decision = payload.get("decision", "")
        # 决策白名单：一次放行 / 本会话始终允许 / 全局始终允许 / 一次拒绝 / 全局始终拒绝
        if decision not in ("allow_once", "allow_always", "allow_global", "deny", "deny_always"):
            raise HTTPException(
                400, "decision 必须是 allow_once / allow_always / allow_global / deny / deny_always"
            )
        ok = approval.decide(session_id, tool_call_id, decision)
        if not ok:
            raise HTTPException(404, "该审批已过期或不存在")
        return {"ok": True, "rules": approval.list_rules()}

    @app.get("/api/approvals/pending", dependencies=[Depends(check_token)])
    async def approvals_pending():
        """当前未决审批（含定时任务/后台触发的）。

        为什么需要：后台任务没有 SSE 消费者，它的审批卡 emit 进的是无人读取的队列，
        用户在界面里看不到 → 任务必然等超时被拒（或已授权时静默执行），
        整个过程对用户不可见。前端轮询本端点把卡片补显示出来。
        """
        return {"pending": approval.pending()}

    @app.get("/api/approvals", dependencies=[Depends(check_token)])
    async def approvals_list():
        """当前审批权限名单（全局白名单 / 全局黑名单 / 会话白名单）。

        黑名单是全局的：一次误点「始终拒绝」会让工具永久不可用，用户需要一个
        可见、可撤销的入口，否则只能手动去删 approval_always.json。
        """
        return approval.list_rules()

    @app.delete("/api/approvals", dependencies=[Depends(check_token)])
    async def approvals_clear(scope: str = "deny", tool: str = ""):
        """清除名单：scope=deny（黑名单）/ global（全局白名单）/ session:<sid>。"""
        if scope == "deny":
            approval.clear_never(tool or None)
        elif scope == "global":
            approval.clear_global(tool or None)
        elif scope.startswith("session:"):
            approval.forget(scope.split(":", 1)[1])
        else:
            raise HTTPException(400, "scope 必须是 deny / global / session:<sid>")
        return {"ok": True, "rules": approval.list_rules()}

    @app.post("/api/undo", dependencies=[Depends(check_token)])
    async def undo_endpoint(payload: dict):
        """撤销一次已完成的文件类操作（写文件/删文件前已自动备份）。"""
        session_id = payload.get("session_id", "")
        tool_call_id = payload.get("tool_call_id", "")
        if not (session_id and tool_call_id):
            raise HTTPException(400, "需要 session_id 与 tool_call_id")
        from .checkpoints import CheckpointStore, get_shared, undo

        cs = get_shared()
        if cs is not None:
            return await undo(cs, session_id, tool_call_id)
        # 共享连接未注入（极少见）：退回一次性连接
        cs = CheckpointStore(settings_mgr.path.parent / "checkpoints.db")
        await cs.start()
        try:
            return await undo(cs, session_id, tool_call_id)
        finally:
            await cs.close()

    @app.put("/api/sessions/{sid}", dependencies=[Depends(check_token)])
    async def rename_session(sid: str, payload: dict):
        """重命名 / 置顶：两个字段都可单独提交（前端各用一个按钮，不必互相覆盖）。"""
        has_title = "title" in payload
        has_pinned = "pinned" in payload
        if not has_title and not has_pinned:
            raise HTTPException(400, "需要 title 或 pinned 字段")
        if store is None or await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        title = None
        if has_title:
            title = str(payload.get("title", "")).strip()[:60]
            if not title:
                raise HTTPException(400, "标题不能为空")
            await store.touch_session(sid, title)
        if has_pinned:
            await store.set_pinned(sid, bool(payload.get("pinned")))
        return {"ok": True, "title": title, "pinned": bool(payload.get("pinned", False))}

    @app.get("/api/sessions/search", dependencies=[Depends(check_token)])
    async def search_messages(q: str = "", limit: int = 20):
        """跨会话搜索消息正文（会话抽屉搜索框升级：标题 + 正文都能搜）。"""
        if store is None:
            raise HTTPException(503)
        query = (q or "").strip()
        if not query:
            return {"query": "", "items": []}
        items = await store.search_messages(query, limit=limit)
        return {"query": query, "count": len(items), "items": items}

    @app.get("/api/sessions", dependencies=[Depends(check_token)])
    async def list_sessions():
        if store is None:
            return []
        return await store.list_sessions()

    @app.post("/api/sessions", dependencies=[Depends(check_token)])
    async def create_session():
        if store is None:
            raise HTTPException(503)
        sid = await store.create_session()
        return {"session_id": sid, "title": "新会话"}

    @app.delete("/api/sessions/{sid}", dependencies=[Depends(check_token)])
    async def delete_session(sid: str):
        if store is None:
            raise HTTPException(503)
        cancel_run(sid)
        ok = await store.delete_session(sid)
        if ok:
            approval.forget(sid)  # 清理该会话的"始终允许"记忆与未决审批
            from .checkpoints import CheckpointStore, get_shared

            cs = get_shared()
            owns = False
            if cs is None:
                cs = CheckpointStore(settings_mgr.path.parent / "checkpoints.db")
                await cs.start()
                owns = True
            try:
                await cs.delete_session(sid)
            finally:
                if owns:
                    await cs.close()
            # 级联清理该会话下的定时任务（内存调度 + 持久化库）
            scheduler_svc.delete_by_session(sid)
        return {"ok": ok}

    @app.get("/api/sessions/{sid}/messages", dependencies=[Depends(check_token)])
    async def session_messages(sid: str):
        if store is None:
            raise HTTPException(503)
        if await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        return await store.get_messages(sid)

    @app.delete("/api/sessions/{sid}/messages/{mid}", dependencies=[Depends(check_token)])
    async def truncate_messages(sid: str, mid: int):
        """从 mid 起截断该会话历史（编辑重发 / 重新生成共用）。

        语义上等价于「把对话回退到这条消息之前」：该条及其后续全部删除，
        保证 user/assistant/tool 配对完整（删单条会留下孤立 tool 响应）。
        """
        if store is None:
            raise HTTPException(503)
        if await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        # 正在跑的时候截断会把在途轮次写回已删区间，先停掉
        cancel_run(sid)
        removed = await store.delete_messages_from(sid, mid)
        return {"ok": True, "removed": removed}

    @app.get("/api/sessions/{sid}/todos", dependencies=[Depends(check_token)])
    async def session_todos(sid: str):
        """当前会话的任务清单（todo_write 维护；刷新页面后前端据此恢复面板）。"""
        if store is None:
            raise HTTPException(503)
        if await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        return {"todos": await store.get_todos(sid)}

    @app.delete("/api/sessions/{sid}/todos", dependencies=[Depends(check_token)])
    async def session_todos_clear(sid: str):
        """清空当前会话的任务清单（前端"清空"按钮；与 todo_clear 工具同一后端方法）。

        为什么必须有这个端点：前端清掉面板后刷新页面会重新拉一次清单，
        没有服务端同步，已清空的清单会"复活"。
        """
        if store is None:
            raise HTTPException(503)
        if await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        await store.clear_todos(sid)
        return {"ok": True}

    @app.post("/api/sessions/{sid}/compact", dependencies=[Depends(check_token)])
    async def session_compact(sid: str, payload: dict | None = None):
        """手动压缩：把较早的历史归档成摘要，保留最近若干条原文。

        与自动分页同一套机制（archive_messages → memory(kind=archive)），
        区别只是由用户显式触发；LLM 不可用时退化为占位摘要（不报错）。
        """
        if store is None:
            raise HTTPException(503)
        if await store.get_session(sid) is None:
            raise HTTPException(404, "会话不存在")
        keep = 10
        if isinstance(payload, dict):
            try:
                keep = max(2, min(200, int(payload.get("keep", 10))))
            except (TypeError, ValueError):
                keep = 10
        history = await store.get_messages(sid, limit=1000)
        if len(history) <= keep:
            return {"ok": False, "reason": f"消息只有 {len(history)} 条，无需压缩"}
        old_msgs, kept = history[:-keep], history[-keep:]
        # 摘要器：mock 下为 None（降级为占位摘要），真实模式走直连端点
        helper = Agent(store, settings_mgr, mock=MOCK, mem0=None)
        try:
            summary = await store.archive_messages(
                sid, old_msgs, settings_mgr.memory_cfg(), llm_client=helper._summarizer()
            )
        finally:
            await helper.aclose()
        return {"ok": True, "archived_messages": len(old_msgs), "kept": len(kept),
                "summary": summary}

    # ---------- P1：定时任务 API ----------
    @app.get("/api/jobs", dependencies=[Depends(check_token)])
    async def jobs_list():
        return {"jobs": scheduler_svc.list()}

    @app.post("/api/jobs", dependencies=[Depends(check_token)])
    async def jobs_create(payload: dict):
        from .routes import validate_job_payload
        norm = validate_job_payload(payload)
        try:
            job = scheduler_svc.create(norm)
        except ValueError as e:
            # cron 表达式非法 / date 字符串无法解析等：构造 trigger 时抛 ValueError，
            # 统一转成 400 而非 500（健壮性边界：非法输入不应让服务崩）
            raise HTTPException(400, f"表达式无法解析：{e}") from None
        return job

    @app.put("/api/jobs/{jid}", dependencies=[Depends(check_token)])
    async def jobs_update(jid: str, payload: dict):
        """更新定时任务。

        支持 ``enabled`` 切换 + 调度参数热更新（expr / trigger_type / message / condition），
        服务内部通过 APScheduler.reschedule_job 热替换，无需删了重建。
        校验复用 _validate_job_payload，确保格式合法再写入。
        """
        if not payload:
            raise HTTPException(400, "请求体不能为空")
        # 构建 patch：只取 scheduler.update 认的字段
        patch = {}
        for k in ("enabled", "expr", "trigger_type", "message", "condition"):
            if k in payload:
                patch[k] = payload[k]
        # 如果有调度参数变更，复用统一校验（trigger_type + expr 格式）
        if "expr" in patch or "trigger_type" in patch:
            from .routes import validate_job_payload
            validate_job_payload({
                "name": "reschedule",
                "trigger_type": patch.get("trigger_type", "interval"),
                "expr": str(patch.get("expr", "")),
                "message": str(patch.get("message", "")),
                "condition": patch.get("condition") or "",
                "enabled": True,
            })
        job = scheduler_svc.update(jid, patch)
        if job is None:
            raise HTTPException(404, "任务不存在")
        return job

    @app.delete("/api/jobs/{jid}", dependencies=[Depends(check_token)])
    async def jobs_delete(jid: str):
        return {"ok": scheduler_svc.delete(jid)}

    @app.post("/api/jobs/{jid}/run", dependencies=[Depends(check_token)])
    async def jobs_run_now(jid: str):
        """立即执行一次（调试定时/条件任务时不必等到下一个触发点）。

        放在独立端点而不是复用 PUT：PUT 的语义是「改配置」，
        把「现在跑一次」混进去会让幂等语义失真（每次重试都多跑一轮）。
        """
        if scheduler_svc.get(jid) is None:
            raise HTTPException(404, "任务不存在")
        if not scheduler_svc.run_now(jid):
            raise HTTPException(409, "任务无法立即执行（调度器未就绪）")
        return {"ok": True, "job_id": jid}

    # ---------- Recipe：预设配方一键实例化为定时任务 ----------
    def _recipe_matched_job(rec_message: str) -> dict | None:
        """按配方 message 精确匹配已创建的 job（单层 recipe→job，不做多级依赖）。"""
        for j in scheduler_svc.list():
            if j.get("message") == rec_message:
                return j
        return None

    @app.get("/api/recipes", dependencies=[Depends(check_token)])
    async def recipes_list():
        out = []
        for r in RECIPES:
            matched = _recipe_matched_job(r.get("message", ""))
            out.append(
                {**r, "applied": matched is not None, "job_id": matched["id"] if matched else None}
            )
        return {"recipes": out}

    @app.post("/api/recipes/{recipe_id}/apply", dependencies=[Depends(check_token)])
    async def recipes_apply(recipe_id: str):
        rec = next((r for r in RECIPES if r.get("id") == recipe_id), None)
        if rec is None:
            raise HTTPException(404, "配方不存在")
        # 幂等：同一配方已实例化过则直接返回已有 job
        matched = _recipe_matched_job(rec.get("message", ""))
        if matched is not None:
            return matched
        # 配方与手工建任务走同一份校验：此前这里绕过校验，坏 expr/trigger_type 会在
        # scheduler 内部抛 ValueError → 500（配方来自 config/recipes.json，属于可编辑数据）
        #
        # enabled 必须透传：此前未传，scheduler.create 默认 pause（next_run_time=None），
        # 导致 config/recipes.json 里 enabled=true 的配方被创建后实际是停用状态。
        from .routes import validate_job_payload as _vjp
        norm = _vjp(
            {
                "name": str(rec.get("name", recipe_id)),
                "trigger_type": rec.get("trigger_type", "interval"),
                "expr": str(rec.get("expr", "")),
                "message": str(rec.get("message", "")),
                "condition": rec.get("condition") or "",
                "enabled": bool(rec.get("enabled", True)),
            }
        )
        try:
            job = scheduler_svc.create(norm)
        except ValueError as e:
            raise HTTPException(400, f"配方表达式无法解析：{e}") from None
        if job is None:
            raise HTTPException(500, "配方实例化失败")
        return job

    @app.get("/api/providers", dependencies=[Depends(check_token)])
    async def providers():
        # 如果开了 LiteLLM：动态补充「liteLLM 统一网关」选项
        if settings_mgr.use_litellm():
            return {
                "providers": PRESETS,
                "litellm": {
                    "enabled": True,
                    "primary_model": settings_mgr.litellm_cfg().get("model", ""),
                    "note": "已启用 LiteLLM 统一网关，可在设置中配置任意模型（provider/model 格式）",
                },
            }
        return {"providers": PRESETS}

    @app.post("/api/llm/test", dependencies=[Depends(check_token)])
    async def llm_test():
        """测试 LLM 连接：用当前配置发一个最小请求，返回 ok 或错误信息（用于设置面板「测试连接」）。"""
        llm = settings_mgr.llm()
        if MOCK:
            return {"ok": True, "mode": "mock", "model": llm.get("model", "")}
        if not (llm.get("api_key") and llm.get("model") and llm.get("base_url")):
            raise HTTPException(400, "请先填写 Base URL、模型名与 API Key")
        client = None
        try:
            from openai import AsyncOpenAI

            client = AsyncOpenAI(
                api_key=llm.get("api_key") or "empty",
                base_url=llm.get("base_url") or None,
                timeout=15,
                max_retries=1,
            )
            resp = await client.chat.completions.create(
                model=llm.get("model") or "unknown",
                messages=[{"role": "user", "content": "ping"}],
                max_tokens=1,
            )
            return {"ok": True, "model": llm.get("model"), "reply": (resp.choices[0].message.content or "")[:50]}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": f"{type(e).__name__}: {str(e)[:200]}"}
        finally:
            if client is not None:
                try:
                    await client.close()
                except Exception:
                    pass

    @app.get("/api/diagnostics", dependencies=[Depends(check_token)])
    async def diagnostics_endpoint():
        """环境自检：Python / 数据目录 / 磁盘 / 端口 / 外网 / termux-api / 模型 / 可选依赖。

        只读探测 + 可执行建议，不自动修改任何东西——自愈动作（删文件、改权限、装包）
        必须由用户确认，静默改动比报错更危险。
        """
        from .diagnostics import run_diagnostics

        # 探测里有同步 socket / subprocess（DNS、端口、getprop），直接 await 会把
        # 整个事件循环卡住：本机实测 0.58s，网络更差时更久，期间所有 SSE 与
        # 其它请求都被堵住。丢线程池执行，保持"只读探测不阻塞服务"的承诺。
        return await asyncio.to_thread(
            run_diagnostics,
            settings_mgr,
            MOCK,
            settings_mgr.get().get("server", {}).get("host", "127.0.0.1"),
            int(settings_mgr.get().get("server", {}).get("port", 8787) or 8787),
            settings_mgr.path.parent,
        )

    @app.get("/api/metrics", dependencies=[Depends(check_token)])
    async def metrics_endpoint():
        """Prometheus 文本格式指标。供 scrape，也方便 curl 直接看。

        为什么需要 token：指标里含端点路径与工具名，虽然不是用户数据，
        但暴露了内部能力面（装了哪些工具、哪些在失败），不该对未授权者开放。
        """
        from fastapi.responses import PlainTextResponse

        from . import metrics as _m

        return PlainTextResponse(_m.render_prometheus(), media_type="text/plain; version=0.0.4")

    @app.get("/api/stats", dependencies=[Depends(check_token)])
    async def stats():
        """运行统计：请求 / 工具 / LLM 调用的真实计数器 + 可选能力状态。

        此前这里只回显配置项（use_litellm / mem0_status），
        `metrics.snapshot()` 写好了却没有任何调用方——「运行统计」等于空壳。
        现在把快照直接并入响应；前端设置面板的「运行统计」分组据此展示
        请求量、工具失败率、LLM 调用次数与真实 token 消耗。
        """
        from . import metrics as _m

        return {
            "use_litellm": settings_mgr.use_litellm(),
            "litellm_cfg": {
                k: v for k, v in settings_mgr.litellm_cfg().items()
                if k != "api_key"
            },
            "use_mem0": settings_mgr.use_mem0(),
            "mem0_status": mem0.status() if mem0 is not None else None,
            "metrics": _m.snapshot(),
        }

    # ---------- Mem0 记忆管理 ----------
    @app.get("/api/memory/profile", dependencies=[Depends(check_token)])
    async def profile_get():
        """读取结构化长期记忆（USER.md / FACT.md），并可查看最近日志行数。"""
        if profile is None:
            return {"enabled": False, "user": "", "facts": "", "journal_lines": 0}
        data = profile.load()
        try:
            journal_lines = len(profile.journal.read_text(encoding="utf-8").splitlines())
        except OSError:
            journal_lines = 0
        return {"enabled": True, "user": data["user"], "facts": data["facts"],
                "journal_lines": journal_lines}

    @app.put("/api/memory/profile", dependencies=[Depends(check_token)])
    async def profile_put(payload: dict):
        """编辑结构化长期记忆。user=覆盖画像；facts=整体替换事实清单；fact=追加一条事实。"""
        if profile is None:
            raise HTTPException(503, "档案记忆未启用")
        if not isinstance(payload, dict):
            raise HTTPException(400, "请求体必须是 JSON 对象")
        if "user" in payload:
            profile.update_user(str(payload.get("user") or ""))
        if "facts" in payload:
            # 整体替换：先清空再写入（按行拆分，逐条 append 以沿用去重与上限逻辑）
            try:
                profile.fact_md.write_text("", encoding="utf-8")
            except OSError as e:
                raise HTTPException(500, f"写入失败：{e}") from None
            for line in str(payload.get("facts") or "").splitlines():
                line = line.strip().lstrip("-").strip()
                if line:
                    profile.append_fact(line)
        elif payload.get("fact"):
            profile.append_fact(str(payload["fact"]))
        data = profile.load()
        return {"ok": True, "user": data["user"], "facts": data["facts"]}

    @app.get("/api/kb", dependencies=[Depends(check_token)])
    async def kb_list_endpoint():
        """知识库条目列表（标题与长度；正文用 kb_search 工具检索）。"""
        if store is None:
            raise HTTPException(503)
        items = await store.kb_list()
        return {"items": [{"id": i["id"], "title": i["title"], "chars": len(i["content"]),
                           "created_at": i["created_at"]} for i in items]}

    @app.delete("/api/kb/{kid}", dependencies=[Depends(check_token)])
    async def kb_delete_endpoint(kid: int):
        if store is None:
            raise HTTPException(503)
        ok = await store.kb_delete(int(kid))
        if not ok:
            raise HTTPException(404, "条目不存在")
        return {"ok": True, "id": kid}

    @app.get("/api/skills", dependencies=[Depends(check_token)])
    async def skills_list():
        """已安装的技能（$AGENT_HOME/skills/<name>/SKILL.md）。"""
        if skills is None:
            return {"skills": []}
        return {"skills": skills.list_skills(), "dir": str(skills.dir)}

    @app.get("/api/memory", dependencies=[Depends(check_token)])
    async def memory_list():
        """列出全部长期记忆（管理页面用）。"""
        if mem0 is None or not mem0.enabled():
            return {"enabled": False, "memories": []}
        items = await mem0.aget_all()
        return {"enabled": True, "memories": items, "status": mem0.status()}

    @app.get("/api/memory/search", dependencies=[Depends(check_token)])
    async def memory_search(q: str = "", limit: int = 10):
        """语义搜索记忆（前端调试用）。"""
        if mem0 is None or not mem0.enabled():
            return {"enabled": False, "results": []}
        items = await mem0.asearch(q, limit=limit)
        return {"enabled": True, "results": items}

    @app.delete("/api/memory/{memory_id}", dependencies=[Depends(check_token)])
    async def memory_delete(memory_id: str):
        """删除指定记忆。"""
        if mem0 is None or not mem0.enabled():
            raise HTTPException(503, "Mem0 未启用")
        ok = await mem0.adelete(memory_id)
        return {"ok": ok, "id": memory_id}

    @app.post("/api/memory/reset", dependencies=[Depends(check_token)])
    async def memory_reset():
        """清除所有记忆（危险操作）。"""
        if mem0 is None or not mem0.enabled():
            raise HTTPException(503, "Mem0 未启用")
        ok = await mem0.areset()
        return {"ok": ok}

    @app.get("/api/tools", dependencies=[Depends(check_token)])
    async def tools():
        from .tools import all_tools

        return {
            "tools": [
                {
                    "name": t.name,
                    "description": t.description,
                    "risk": t.risk,
                    "summary": t.summary,
                    "timeout": t.timeout,
                    "group": t.group,
                }
                for t in all_tools()
            ],
            "count": len(all_tools()),
        }

    @app.get("/api/mcp", dependencies=[Depends(check_token)])
    async def mcp_status():
        cfg = settings_mgr.get().get("server", {}).get("mcp", []) or []
        return {
            "configured": cfg,
            "connected": [
                {
                    "name": c.name,
                    "tools": c.tools if hasattr(c, "tools") and isinstance(c.tools, list) else [],
                }
                for c in mcp_clients
            ],
        }

    @app.get("/api/export", dependencies=[Depends(check_token)])
    async def export_all():
        """导出全部数据（会话 + 消息 + 摘要归档 + 档案记忆 + 任务清单 + 知识库 + 定时任务）。

        承诺是「本机数据的完整备份」，因此本机数据必须齐全，否则换机/重装后无声丢失：
        结构化档案 USER/FACT、每会话任务清单、知识库条目，以及**摘要/归档记忆
        （memory 表）与定时任务** —— 后两者此前完全没导出，
        用户重装后会发现定时提醒全没了、长期记忆只剩最近一条，而导出文件看着「完整」。
        """
        import datetime

        prof = profile.load() if profile is not None else {}
        out: dict = {
            "app": "口袋智灵",
            "version": VERSION,
            # 备份格式版本：导入端据此判断「这份备份我认识吗」。
            # 缺失 = 历史备份，按 1 处理；大于当前值 = 新版本备份，导入端必须拒绝。
            "format_version": EXPORT_FORMAT_VERSION,
            "exported_at": datetime.datetime.now().isoformat(timespec="seconds"),
            "settings": {
                "permission_mode": settings_mgr.get().get("permission_mode", "approve"),
                "llm": {
                    k: v for k, v in settings_mgr.get().get("llm", {}).items() if k != "api_key"
                },
                "user_prefs": settings_mgr.get().get("user_prefs", ""),
            },
            # 结构化长期记忆原文（USER.md / FACT.md；不含 JOURNAL 事件日志）
            "memory_profile": {"user": prof.get("user") or "", "facts": prof.get("facts") or ""},
            "knowledge": [],
            "sessions": [],
            # 定时任务（会话级 + 全局）：重装后要能照原样恢复
            "jobs": [],
        }
        if store is not None:
            out["knowledge"] = [
                {"id": k["id"], "title": k["title"], "content": k["content"],
                 "created_at": k["created_at"]}
                for k in await store.kb_list()
            ]
            # 批量取回并**翻页取尽**：原来是「每个会话查 5 次」（500 会话 = 2500 次
            # 往返），后来改成 5 条聚合查询但仍然只取一页 —— 第 501 个会话静默丢失。
            # 现在 max_sessions 是总量上限、page_size 控制每批 SQL 的绑定参数个数：
            # Termux 自带 SQLite 的变量上限可能是 999，500 个会话拼进 IN 子句
            # 会直接报 "too many SQL variables"（这也是分页而非一次全量的硬理由）。
            bundle = await store.export_bundle(
                max_sessions=500, max_messages=1000, page_size=100
            )
            out["exported_session_count"] = len(bundle["sessions"])
            out["export_truncated"] = len(bundle["sessions"]) >= 500
            for s in bundle["sessions"]:
                payload_s = bundle["by_session"].get(s["id"], {})
                msgs = [
                    {"role": m["role"], "content": m["content"], "meta": m.get("meta")}
                    for m in payload_s.get("messages", [])
                ]
                out["sessions"].append(
                    {
                        "id": s["id"],
                        "title": s["title"],
                        "created_at": s.get("created_at"),
                        "pinned": s.get("pinned", 0),
                        "message_count": len(msgs),
                        "todos": payload_s.get("todos", []),
                        "messages": msgs,
                        "summary": payload_s.get("summary"),
                        "archives": payload_s.get("archives", []),
                        "archived_upto": s.get("archived_upto", 0),
                    }
                )
            try:
                out["jobs"] = scheduler_svc.list()
            # aqg: top-level boundary —— 定时任务读不到不影响其余数据导出
            except Exception as e:  # noqa: BLE001
                logging.getLogger(__name__).warning("导出定时任务失败: %s", e)
        return out

    @app.post("/api/import", dependencies=[Depends(check_token)])
    async def import_all(payload: dict):
        """从 /api/export 的备份文件恢复数据（换机 / 重装 / 误删）。

        语义是**合并**：同 id 会话跳过、知识库按内容去重，绝不覆盖本机现有数据。
        导入前先校验形状：一个随便的 JSON 打进来不能把库写坏；校验失败 400，
        并且什么都不写（不是「导入一半」）。
        """
        if store is None:
            raise HTTPException(503, "服务未就绪")
        if not isinstance(payload, dict):
            raise HTTPException(400, "备份文件必须是 JSON 对象")
        # 格式版本校验：在读任何数据之前先确认「这份备份我认识」。
        # 缺失视为 1（历史备份没有 format_version 字段）；
        # 未来版本（format_version > 当前）的备份可能带本版不理解的结构，
        # 猜着导入会写坏数据，必须明确拒绝并指路升级。
        fmt = payload.get("format_version", 1)
        if isinstance(fmt, bool) or not isinstance(fmt, int) or fmt < 1:
            raise HTTPException(400, f"format_version 必须是正整数，实际是 {fmt!r}")
        if fmt > EXPORT_FORMAT_VERSION:
            raise HTTPException(
                400,
                f"备份格式版本过新（{fmt} > 当前支持 {EXPORT_FORMAT_VERSION}）："
                "请先把本机 PocketMind 升级到与备份同版本再导入",
            )
        sessions = payload.get("sessions")
        if not isinstance(sessions, list):
            raise HTTPException(400, "备份文件缺少 sessions 数组（不是 PocketMind 的导出文件？）")
        if len(sessions) > 500:
            raise HTTPException(400, "单次最多导入 500 个会话，请拆分备份文件")
        try:
            stats = await store.import_bundle(payload)
        except Exception as e:  # noqa: BLE001 —— 解析/写入失败统一 400，不把半截状态留给用户
            raise HTTPException(400, f"导入失败：{type(e).__name__}: {e}") from None

        # 档案记忆（USER.md / FACT.md）：备份里的内容按行合并到本机，不覆盖已有画像
        prof = payload.get("memory_profile")
        if isinstance(prof, dict) and profile is not None:
            try:
                user_md = str(prof.get("user") or "").strip()
                if user_md:
                    existing = profile.load().get("user") or ""
                    # 用户画像用覆盖写：备份里的画像更完整（同一个人换机恢复），
                    # 但本机已有内容且不同则保留本机——避免导入旧备份冲掉新写的画像
                    if not existing.strip():
                        profile.update_user(user_md)
                for line in str(prof.get("facts") or "").splitlines():
                    fact = line.strip().lstrip("-").strip()
                    if fact:
                        profile.append_fact(fact)
            except Exception as e:  # noqa: BLE001 —— 档案合并失败不影响会话导入结果
                logging.getLogger(__name__).warning("导入档案记忆失败: %s", e)

        # 定时任务：备份里带 jobs 时按原样重建（已存在同名任务则跳过）
        jobs = payload.get("jobs")
        restored_jobs = 0
        if isinstance(jobs, list):
            existing_names = {j.get("name") for j in scheduler_svc.list()}
            for j in jobs:
                if not isinstance(j, dict) or j.get("name") in existing_names:
                    continue
                try:
                    from .routes import validate_job_payload

                    norm = validate_job_payload({
                        "name": j.get("name", ""),
                        "trigger_type": j.get("trigger_type", "interval"),
                        "expr": str(j.get("expr", "")),
                        "message": j.get("message", ""),
                        "condition": j.get("condition") or "",
                        "enabled": False,  # 导入的任务默认停用：避免恢复瞬间把历史任务全部触发
                    })
                    scheduler_svc.create(norm)
                    restored_jobs += 1
                except Exception as e:  # noqa: BLE001 —— 单条任务坏了不阻断其余数据恢复
                    logging.getLogger(__name__).warning("导入定时任务失败 [%s]: %s", j.get("name"), e)
        stats["jobs"] = restored_jobs
        return {"ok": True, "imported": stats}

    @app.get("/api/settings", dependencies=[Depends(check_token)])
    async def get_settings():
        # api_key / server.token / tg_token 统一打码（见 _masked_settings）
        from .routes import masked_settings
        return masked_settings(settings_mgr.get())

    @app.put("/api/settings", dependencies=[Depends(check_token)])
    async def put_settings(payload: dict):
        llm = payload.get("llm")
        if isinstance(llm, dict):
            # api_key 打码占位符：前端回传的是 GET 时打码后的值（或空串），
            # 原样写回会污染真实 key，因此剔除——仅当用户输入了「新的、非占位」的 key 才写入。
            masked = mask_key(settings_mgr.llm().get("api_key", ""))
            key = llm.get("api_key")
            if isinstance(key, str) and (not key.strip() or key == masked):
                llm.pop("api_key", None)
        server = payload.get("server")
        if isinstance(server, dict) and isinstance(server.get("mcp"), list):
            # mcp 条目清洗：只保留带 command 的服务器（与原逻辑一致），结构校验交给 pydantic
            cleaned_mcp = []
            for c in server["mcp"]:
                if isinstance(c, dict) and str(c.get("command", "")).strip():
                    cleaned_mcp.append(
                        {
                            "name": c.get("name", "mcp"),
                            "command": c["command"],
                            "args": c.get("args", []),
                        }
                    )
            server["mcp"] = cleaned_mcp
        if isinstance(server, dict):
            # tg_token 与 api_key 同理：GET 返回的是打码占位，原样回传会污染真实 token，
            # 因此回传值等于打码占位（或空）时剔除，仅当用户填了新值才写入。
            masked_tg = mask_key(settings_mgr.get().get("server", {}).get("tg_token", ""))
            tg = server.get("tg_token")
            if isinstance(tg, str) and (not tg.strip() or tg == masked_tg):
                server.pop("tg_token", None)
            # server.token（局域网令牌）同理：GET 已打码，前端原样回传打码占位时剔除，
            # 避免把真实令牌覆盖成一串星号。
            masked_token = mask_key(settings_mgr.get().get("server", {}).get("token", ""))
            tok = server.get("token")
            if isinstance(tok, str) and (not tok.strip() or tok == masked_token):
                server.pop("token", None)
        # permission_mode 非法值不写入（pydantic Literal 也会拒绝，这里显式兜底）
        mode = payload.get("permission_mode")
        if mode is not None and mode not in ("auto", "approve", "chat"):
            payload.pop("permission_mode", None)
        # agent_mode（plan/act）非法值同样不写入
        amode = payload.get("agent_mode")
        if amode is not None and amode not in ("plan", "act"):
            payload.pop("agent_mode", None)
        # 类型强制 / 范围收敛（temperature、max_tokens、approval_timeout、字符串截断等）
        # 全部由 pydantic 模型在 Settings.save() 内完成，不再手写 if/else clamp。
        settings_mgr.save(payload)
        # 令牌状态机同步：
        #   - 保存了新令牌 → 清除 --clear-token 哨兵、同步 token 文件
        #     （start.sh / APK 壳都从文件读取，不同步会让壳拿到旧令牌 401）
        #   - 令牌为空（面板清空）→ 写哨兵，防止 start.sh 复活旧令牌
        try:
            new_token = str(settings_mgr.get().get("server", {}).get("token", "")).strip()
            tf = settings_mgr.path.parent / "token"
            sentinel = settings_mgr.path.parent / ".token-cleared"
            if new_token:
                if sentinel.exists():
                    sentinel.unlink()
                if tf.read_text(encoding="utf-8").strip() != new_token:
                    tf.write_text(new_token, encoding="utf-8")
                    os.chmod(tf, 0o600)
            else:
                sentinel.write_text("cleared", encoding="utf-8")
        except OSError:
            pass
        # 与 GET 同一份打码逻辑：保存响应不得回传明文 server.token / tg_token
        from .routes import masked_settings
        return {"ok": True, "settings": masked_settings(settings_mgr.get())}

    # ---------- 静态前端 ----------
    if DIST.exists():
        app.mount("/", StaticFiles(directory=str(DIST), html=True), name="static")
    else:

        @app.get("/", include_in_schema=False)
        async def root_placeholder():
            return HTMLResponse(
                "<html><body style='font-family:sans-serif;background:#0f1115;color:#e4e4e7;padding:40px'>"
                "<h2>口袋智灵 服务已启动</h2>"
                "<p>前端尚未构建。在仓库根目录执行：</p>"
                "<pre>cd agentd/web && npm install && npm run build</pre>"
                "</body></html>"
            )

    return app


def _is_android_runtime() -> bool:
    """是否运行在 Android（Termux 或 APK 内嵌）上。

    判定依据：
      - 环境变量 PREFIX/HOME 指向 com.termux（Termux 标准形态）
      - 或 sys.platform == "android"（Chaquopy 等内嵌 Python 的形态）
      - 或 /system/build.prop 存在且含 ro.build.version.sdk（Android 内核）

    用途：决定是否强制回环鉴权。Android 的 127.0.0.1 是全机共享命名空间，
    与桌面「回环只有本用户可连」的安全假设完全不同，不能沿用同一默认值。
    """
    import sys as _sys

    prefix = os.environ.get("PREFIX", "") + os.environ.get("HOME", "")
    if "com.termux" in prefix:
        return True
    if _sys.platform == "android":
        return True
    try:
        with open("/system/build.prop", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if line.startswith("ro.build.version.sdk="):
                    return True
    except OSError:
        pass
    return False


def _rebuild_app_for_home() -> None:
    """--home 重定位：重建 approval 与 app，使 store/scheduler/checkpoints 跟随新数据目录。"""
    global approval, app, _scheduler
    if store is not None:
        try:
            asyncio.run(store.close())
        except Exception:
            pass
    if _scheduler is not None:
        try:
            _scheduler.shutdown()
        except Exception:
            pass
    approval = ApprovalCenter(timeout_cb=_approval_timeout, persist_path=_approval_always_path())
    app = create_app()
    import sys
    # 把重建后的 app 暴露为模块属性（供 uvicorn 动态引用）；ModuleType 未声明
    # 该属性，cast 为 Any 仅做类型收窄，运行时 cast 是恒等调用、零行为变更
    cast(Any, sys.modules[__name__]).app = app


def main():
    global MOCK, TG_TOKEN, TG_CHAT_ID
    parser = argparse.ArgumentParser(description="口袋智灵 agentd")
    parser.add_argument("--host", default=None, help="监听地址（默认 127.0.0.1，--lan 时 0.0.0.0）")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--lan", action="store_true", help="允许局域网访问（会校验访问令牌）")
    parser.add_argument("--mock", action="store_true", help="离线 mock 模式")
    parser.add_argument("--home", default=None, help="数据目录（默认 ~/.agent/termux-agent）")
    # Telegram 远程控制（可选）：命令行覆盖 settings 里的 server.tg_token / tg_chat_id
    parser.add_argument(
        "--tg-token", default=None, help="Telegram bot token（不填则读 settings.server.tg_token）"
    )
    parser.add_argument("--tg-chat-id", default=None, help="允许访问的 Telegram chat_id 白名单")
    parser.add_argument(
        "--mcp-server",
        action="store_true",
        help="以 stdio MCP server 模式启动（不启动 FastAPI，供 Claude Desktop/Cline 连接）",
    )
    parser.add_argument(
        "--clear-token",
        action="store_true",
        help="清除 config.json 里的访问令牌后退出（忘记令牌导致本机被锁死时的恢复入口）",
    )
    args = parser.parse_args()

    MOCK = args.mock
    if args.home:
        os.environ["AGENT_HOME"] = args.home
        # settings_mgr 是模块级单例，self.path 在 import 时就按当时的 AGENT_HOME 解析完了；
        # 只改环境变量不重定位，--home 会静默失效（配置与 *.db 仍写到默认目录）。
        settings_mgr.path = Path(args.home).expanduser() / "config.json"
    settings_mgr.load()
    # --home 重定位：模块底部 app = create_app() 已在 import 时用默认目录创建，
    # 这里重建 approval 与 app，使 store / scheduler / checkpoints 全部跟随新数据目录。
    if args.home:
        _rebuild_app_for_home()

    # 忘记令牌的恢复入口：本机直接 `python -m agentd.main --clear-token` 清掉后重启，
    # 不必手工编辑 config.json（此前唯一的出路是手改文件，普通手机用户做不到）。
    if args.clear_token:
        settings_mgr.save({"server": {"token": ""}})
        # 写哨兵文件：start.sh 看到它就不再注入 $DATA_DIR/token 的旧令牌。
        # 没有这一步，清除会被 token 文件"复活"——env_token 分支把旧令牌
        # 写回 config.json，用户以为清掉了，实际还在（第四轮 D4）。
        try:
            (settings_mgr.path.parent / ".token-cleared").write_text(
                "cleared", encoding="utf-8"
            )
        except OSError:
            pass
        print("[agentd] 已清除访问令牌（server.token）。重新启动即可无令牌访问；")
        print("[agentd] 若要对局域网暴露，请重新在「设置」里配置令牌，或用 AGENT_TOKEN 环境变量注入。")
        return

    # 部署引导：AGENT_TOKEN 仅用于「首次」写入访问令牌（docker/systemd 一键部署场景），
    # 已有令牌时以 config.json 为准，不覆盖用户改过的值。没有这一步，--lan 会在下面
    # 的安全校验里直接 SystemExit(1)，容器就会无限重启（DEPLOY.md 承诺的路径走不通）。
    env_token = os.environ.get("AGENT_TOKEN", "").strip()
    if env_token and not str(settings_mgr.get().get("server", {}).get("token", "")).strip():
        settings_mgr.save({"server": {"token": env_token}})
        print("[agentd] 已从环境变量 AGENT_TOKEN 初始化访问令牌（后续以 config.json 为准）")

    # Android/Termux：127.0.0.1 是**全机共享**的，不是"只有本进程能连"。
    # 若不带令牌启动，同机任意 App（含恶意应用）都能读写会话、调用 shell 工具。
    # 因此在这个平台上即使只监听回环也强制鉴权：
    #   - 已有令牌：照常
    #   - 没有令牌：自动生成一个随机令牌并落盘（600），用户无需输入；
    #     壳从同一个文件读取并注入 WebView。
    # 桌面/容器保持原行为（回环无需令牌），避免破坏本机开发与测试流程。
    if _is_android_runtime() and not str(settings_mgr.get().get("server", {}).get("token", "")).strip():
        import secrets as _secrets

        _tok = _secrets.token_hex(32)
        settings_mgr.save({"server": {"token": _tok}})
        try:
            _tf = settings_mgr.path.parent / "token"
            _tf.write_text(_tok, encoding="utf-8")
            os.chmod(_tf, 0o600)
        except OSError:
            pass
        print("[agentd] 已在 Android 上自动生成访问令牌（127.0.0.1 全机共享，必须鉴权）")

    # MCP server 独立模式：直接走 stdio，不启动 FastAPI
    if args.mcp_server:
        from .mcp_server import main as mcp_main

        mcp_main()
        return

    # 把 CLI 传入的 TG 配置存到模块全局，lifespan 启动时读取
    if args.tg_token:
        TG_TOKEN = args.tg_token
    if args.tg_chat_id:
        TG_CHAT_ID = args.tg_chat_id

    host = args.host or ("0.0.0.0" if args.lan else "127.0.0.1")
    # 判定依据是「最终是否对非本机地址开放」，而不是用户是否敲了 --lan：
    # 旧写法下 `--host 0.0.0.0` 能完全跳过下面的令牌强校验，而 check_token 在
    # token 为空时直接放行 —— 等于把整个 API（含 chat/export/settings）暴露给局域网。
    _local_only = host in ("127.0.0.1", "localhost", "::1")
    if not _local_only:
        s = settings_mgr.get()
        token = str(s.get("server", {}).get("token", "")).strip()
        if not token:
            print(
                f"✗ 安全策略：监听 {host}（非本机）必须配置访问令牌才能启动，"
                "否则局域网内任何设备都能调用本服务（含读取文件与执行命令）。"
            )
            print(
                "  请先设置 server.token（启动本机模式后，在页面「设置 → 局域网访问令牌」填入），"
            )
            print("  或为容器/服务注入环境变量 AGENT_TOKEN=<令牌>（首次启动会自动写入 config.json），再重试。")
            raise SystemExit(1)

    import uvicorn

    print(f"[agentd] 口袋智灵 v{VERSION} | mock={MOCK} | 监听 {host}:{args.port}")
    if MOCK:
        print(
            "[agentd] mock 模式：消息含 电池/短信/定位/剪贴板/传感器/通知/工具 等词会走工具链路，便于自测"
        )
    # 复用模块级 app（已由 uvicorn 导入时创建）；避免在 main() 里再次 create_app()
    # 导致 store / runs 等全局状态重复初始化（Python 会把 agentd.main 当新模块再跑一遍）。
    import __main__
    if hasattr(__main__, "app"):
        runner_app = __main__.app
    else:
        runner_app = create_app()
    uvicorn.run(runner_app, host=host, port=args.port, log_level="warning")


if __name__ == "__main__":
    # `python -m agentd.main` 时本模块以 __main__ 运行，全局 store/runs 都挂在它上面；
    # 而 scheduler._fire_job 触发时会 `from .main import run_agent_for_job`，若不做这步别名，
    # Python 会把 agentd.main 当一个全新模块再执行一遍，那个副本里 store=None，定时任务
    # 一触发就 AttributeError。这里强制让具名模块指向 __main__，两份代码共享同一份全局状态。
    import sys

    sys.modules["agentd.main"] = sys.modules[__name__]
    main()


# uvicorn agentd.main:app 入口（生产/开发服务器）
app = create_app()
