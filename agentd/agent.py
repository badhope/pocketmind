"""Agent 循环：流式输出 → 工具调用 → 审批门 → 结果回填 → 继续。

与旧实现（goose 黑盒 + 服务端自动批准一切）的本质区别：
1. 循环是我们自己的，每一步都可见、可中断、可审批；
2. 审批门由服务端强制，危险工具必须用户确认，不再被自动放行；
3. 消息全部落 SQLite，服务重启后上下文从库里恢复，不会"看起来有历史、实际上失忆"。
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable

from . import metrics as _m
from . import permissions
from .config import Settings
from .mem0_memory import Mem0Memory
from .memory import Store
from .mock_llm import MockLLM
from .tools import call_tool, get_tool, schemas
from .tools import ctx as tool_ctx

logger = logging.getLogger(__name__)

# 以下常量是「配置缺失时的兜底默认」：正常路径上取 settings.agent / settings.memory /
# settings.context 的值（见 Agent._cfg），保证设置面板里的旋钮真实生效。
MAX_ITERATIONS = 20      # 兜底：一轮最多工具调用次数（配置项 agent.max_tool_chain_depth）
MAX_CONTEXT = 80         # 上下文消息上限，超出做安全裁剪（消息条数兜底，非 token 预算）
MAX_HISTORY_WINDOW = 60  # 长程记忆分页：近期原始消息窗口，更早的历史滚动归档为摘要
MAX_TOOL_TEXT = 6000     # 回传给模型的工具结果默认上限
MAX_TOOL_TEXT_HARD_CAP = 60_000  # 按预算放大时的硬顶（约 1.5 万 token）
CHARS_PER_TOKEN = 4      # 粗略 token 估算口径（与 _tool_result_limited 保持一致）

Emit = Callable[[dict], Awaitable[None]]
AskApproval = Callable[[str, str, str, str], Awaitable[str]]  # (tool_call_id, name, summary, risk) -> allow|deny

# 固定 system 前缀：内容恒定不变，位于请求最前，配合厂商「提示缓存」机制
# （相同前缀命中后 token 费约 1 折，DeepSeek/豆包/百炼/Kimi 均支持）。
# 注意：勿随意改动这里，改动会降低缓存命中率。
SYSTEM_FIXED = (
    "你是「口袋智灵」（PocketMind），运行在用户 Android 手机的 Termux 环境里，可以调用手机能力"
    "（短信、电话、定位、传感器、WiFi、通知、剪贴板、文件、shell 等）。\n"
    "原则：\n"
    "1. 用用户的语言回答（默认简体中文），简洁直接，不写废话。\n"
    "2. 需要用户信息时先调用工具获取，不臆测；拿不到就如实说明。\n"
    "3. 工具返回后，用自然语言把结果讲清楚。\n"
    "4. 危险操作（删除、拨号、写文件、shell）会由用户审批，你只负责发起，不要绕过审批。"
)


def _fallback_title(message: str) -> str:
    """回退标题：取首条消息前 14 字（与 mock 规则一致）。"""
    text = " ".join(str(message).split())
    return text[:14] + ("…" if len(text) > 14 else "")


def _estimate_tokens(messages: list[dict]) -> int:
    """粗估一批消息的 token 数（字符数 / 4，与工具结果截断同一口径）。

    只用内容长度，不计 role / tool_calls 包装开销——预算判断只需要量级正确；
    精确分词在 Termux 上要么引原生依赖（tiktoken），要么误差不比这个更小。
    """
    total = 0
    for m in messages:
        total += len(str(m.get("content") or ""))
    return total // CHARS_PER_TOKEN


def _should_compress(est_tokens: int, budget_tokens: int, threshold: float,
                     auto: bool = True) -> bool:
    """是否因接近 token 预算而需要压缩早期历史。

    budget_tokens <= 0 表示未配置预算（用模型默认窗口），此时永不因预算触发；
    auto=False（settings.context.auto_compress 关闭）同理。
    """
    if not auto or budget_tokens <= 0:
        return False
    return est_tokens >= int(budget_tokens * float(threshold))


def _trim(messages: list[dict], maxn: int = MAX_CONTEXT) -> list[dict]:
    """裁剪早期消息，保证 tool 消息永远跟着它的 assistant tool_call 一起被裁掉；
    第一条 system（记忆摘要/角色设定）永远保留。

    按「块」丢弃：一个 assistant(tool_calls) 块 = assistant 本体 + 紧随其后的所有
    tool 响应，整块一起删，绝不拆散。否则当 drop 额度在 tool 消息中间耗尽时，
    会留下一条没有前置 assistant tool_call 的孤立 tool 消息，OpenAI 兼容 API 直接 400。
    """
    if len(messages) <= maxn:
        return messages
    drop = len(messages) - maxn
    i = 1 if messages and messages[0]["role"] == "system" else 0  # 保护 system
    while i < len(messages) and drop > 0:
        m = messages[i]
        if m["role"] == "assistant" and m.get("tool_calls"):
            # 整块丢弃：assistant + 紧随的所有 tool 响应（原子，不可拆）。
            # drop 按整块实际消息数扣，避免每块只扣 1 导致过度裁剪。
            start = i
            i += 1
            while i < len(messages) and messages[i]["role"] == "tool":
                i += 1
            drop -= i - start
        else:
            i += 1
            drop -= 1
    return messages[:1] + messages[i:] if messages and messages[0]["role"] == "system" and i > 0 else messages[i:]


def _tool_result_limited(text: str, settings) -> str:
    """按上下文预算裁剪工具结果（P1 Token 级预算）。

    - settings.context.budget_tokens > 0 时：按预算 15% 估算单条工具结果上限
      （×4 字符/token 粗略换算），至少 400 字符；
    - 否则回退 MAX_TOOL_TEXT 硬截断。
    超限时保留首尾摘要（中间省略），避免丢失关键头部/尾部信息。
    """
    limit = MAX_TOOL_TEXT
    try:
        if hasattr(settings, "context_cfg"):
            ctx = settings.context_cfg()
        elif isinstance(settings, dict):
            ctx = settings.get("context", {})
        else:
            ctx = {}
        budget = int((ctx or {}).get("budget_tokens", 0) or 0)
        if budget > 0:
            # 单条工具结果的预算上限。
            # 必须有**硬顶**：按比例算时 budget=200000 会给出 12 万字符（≈3 万 token），
            # 一条 cat 大文件的结果就能把整个上下文挤爆（手机更明显）。
            # 60000 字符 ≈ 1.5 万 token，已是单条工具结果的实际上限。
            limit = max(400, min(int(budget * 0.15 * 4), MAX_TOOL_TEXT_HARD_CAP))
    except Exception:  # noqa: BLE001
        limit = MAX_TOOL_TEXT
    if len(text) <= limit:
        return text
    half = max(40, limit // 2 - 30)
    return text[:half] + "\n…（中间省略，如需完整结果可用 shell/文件工具查看）…\n" + text[-half:]


def _rrf_fuse(mem0_results: list[dict], fts5_results: list[dict], k: int = 60, limit: int = 6) -> list[dict]:
    """Reciprocal Rank Fusion：合并 Mem0 向量检索和 FTS5 关键词检索。

    RRF 分数 = Σ 1/(k + rank_i)，k 是排名偏移常数（默认 60）。
    两路结果归一化字段后按 RRF 分数降序排列取 top-limit。
    """
    scores: dict[str, float] = {}
    items: dict[str, dict] = {}

    for rank, m in enumerate(mem0_results):
        text = m.get("memory") or m.get("content", "")
        if not text:
            continue
        key = text[:120]
        scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank)
        items[key] = {"text": text, "score": m.get("score", 0.0), "src": "vec"}

    for rank, m in enumerate(fts5_results):
        text = m.get("content") or m.get("memory", "")
        if not text:
            continue
        key = text[:120]
        scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank)
        if key not in items:
            items[key] = {"text": text, "score": 0.0, "src": "kw"}

    ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)
    return [items[key] for key, _ in ranked[:limit] if key in items]


class Agent:
    def __init__(
        self,
        store: Store,
        # 所有调用点都传 Settings 对象（main/telegram/smoke）；裸 dict 是历史遗留
        # 的兜底路径（见 _cfg 的 isinstance 分支），运行时不再有构造方传入
        settings: Settings,
        mock: bool = False,
        mem0: Mem0Memory | None = None,
        profile=None,      # profile_memory.ProfileMemory：USER/FACT 文件记忆（可选）
        skills=None,       # skills.SkillRegistry：SKILL.md 渐进披露（可选）
    ):
        self.store = store
        self.settings = settings
        self.mock = mock
        self.mem0 = mem0  # 本地 Mem0 记忆（可选）
        self.profile = profile  # 结构化长期记忆（可选）
        self.skills = skills    # 技能索引（可选）
        self._client = None  # AsyncOpenAI 懒加载缓存（httpx 连接池复用，避免每次新建泄漏）
        self._litellm = None  # LiteLLM 懒加载缓存（use_litellm=true 时生效）
        self._summarizer_obj = None  # 摘要器懒加载（直连端点，见 summarizer.py）
        self._pending_mem0_tasks: set = set()  # 保留引用，防 GC 回收进行中任务
        # 最近一次流式调用的 token 用量（由 _stream 填写；端点不回报时保持 0）
        self._last_usage: dict = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
        # 本轮（一次 chat 调用）的累计用量：多轮 tool loop 逐轮累加，结束时随 done 下发
        self._turn_usage: dict = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

    async def _memory_context_block(self, message: str, session_id: str, search_limit: int) -> list[dict]:
        """检索并组装「相关历史记忆 + 分页摘要」两个 system 块。

        Mem0 语义召回与 FTS5 关键词检索互补，用 RRF（k=60）融合；
        任一来源失败只降级该来源，不影响对话。
        """
        mem0_results: list[dict] = []
        fts5_results: list[dict] = []
        if self.mem0 is not None and self.mem0.enabled():
            try:
                mem0_results = await self.mem0.asearch(
                    message, limit=search_limit, session_id=session_id
                )
            except Exception as e:  # noqa: BLE001
                logger.warning("Mem0 搜索失败，降级 FTS5: %s", e)
        try:
            fts5_results = await self.store.search_memories(
                message, limit=search_limit, exclude_session=session_id
            )
        except Exception as e:  # noqa: BLE001
            logger.warning("FTS5 搜索失败: %s", e)

        out: list[dict] = []
        merged = _rrf_fuse(mem0_results, fts5_results, k=60, limit=search_limit)
        if merged:
            rel_text = "\n".join(f"· {m['text'][:160]}" for m in merged[:search_limit])
            out.append({"role": "system", "content": f"与此相关的历史记忆：\n{rel_text}"})
        summary = await self.store.get_summary(session_id)
        archives = await self.store.get_archives(session_id, limit=5)
        mem_parts = [a["content"][:300] for a in reversed(archives)]
        if summary:
            mem_parts.append(summary)
        if mem_parts:
            mem_text = "\n".join(f"· {p}" for p in mem_parts)
            out.append({"role": "system", "content": f"此前对话摘要（分页归档）：\n{mem_text}"})
        return out

    async def _load_history(
        self,
        *,
        session_id: str,
        mem_cfg: dict,
        budget_tokens: int,
        auto_compress: bool,
        compress_threshold: float,
    ) -> list[dict]:
        """恢复历史消息，并在需要时先滚动归档，再转成 LLM 消息格式。

        两类归档触发：① 估算 token 接近预算 × 阈值（预算优先）；
        ② 条数超过 MAX_HISTORY_WINDOW（兜底）。归档失败只降级。
        """
        # 中断恢复：上一轮在「工具结果落库前」被杀会留下没有响应的 tool_calls，
        # 补占位响应后历史才自洽（否则本次请求直接 400，会话卡死）。
        try:
            recovered = await self.store.recover_orphan_tool_calls(session_id)
            if recovered:
                logger.info("会话 %s 检测到 %d 个未完成工具调用，已补占位响应", session_id, recovered)
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception as e:  # noqa: BLE001
            logger.warning("孤立工具调用恢复失败: %s", e)

        history = await self.store.get_messages(session_id)
        if auto_compress and history:
            llm_for_archive = self._summarizer()
            if _should_compress(_estimate_tokens(history), budget_tokens, compress_threshold,
                                auto=auto_compress):
                keep = max(10, len(history) // 3)
                if len(history) > keep:
                    old_msgs, history = history[:-keep], history[-keep:]
                    try:
                        await self.store.archive_messages(
                            session_id, old_msgs, mem_cfg, llm_client=llm_for_archive
                        )
                    except Exception as e:  # noqa: BLE001
                        logger.warning("预算触发归档失败: %s", e)
            if len(history) > MAX_HISTORY_WINDOW:
                old_msgs, history = history[:-MAX_HISTORY_WINDOW], history[-MAX_HISTORY_WINDOW:]
                try:
                    await self.store.archive_messages(
                        session_id, old_msgs, mem_cfg, llm_client=llm_for_archive
                    )
                except Exception:  # noqa: BLE001
                    pass

        out: list[dict] = []
        for m in history:
            msg: dict = {"role": m["role"], "content": m["content"] or ""}
            meta = m.get("meta") or {}
            if m["role"] == "assistant" and meta.get("tool_calls"):
                msg["tool_calls"] = [
                    {
                        "id": c["id"],
                        "type": "function",
                        "function": {"name": c["name"], "arguments": c.get("args", "{}")},
                    }
                    for c in meta["tool_calls"]
                ]
            elif m["role"] == "tool" and meta.get("tool_call_id"):
                msg["tool_call_id"] = meta["tool_call_id"]
            out.append(msg)
        return out

    # ---------- 配置读取：把散落的 try/except 收敛为一处 ----------

    def _runtime_limits(self) -> dict:
        """读取运行参数并做范围收敛。

        原来 chat() 里连写三段几乎相同的 try/except + int()/float()，
        每段都要单独处理 TypeError / ValueError / 越界，属于同一规则写三遍。
        收敛后新增配置项只改这里。
        """
        agent_cfg = self._cfg("agent_cfg", "agent")
        mem_cfg = self._cfg("memory_cfg", "memory")
        ctx_cfg = self._cfg("context_cfg", "context")

        def _int(cfg: dict, key: str, default: int, lo: int, hi: int) -> int:
            try:
                return max(lo, min(hi, int(cfg.get(key) or default)))
            except (TypeError, ValueError):
                return default

        def _float(cfg: dict, key: str, default: float, lo: float, hi: float) -> float:
            try:
                return max(lo, min(hi, float(cfg.get(key) or default)))
            except (TypeError, ValueError):
                return default

        return {
            "mem_cfg": mem_cfg,
            "max_iters": _int(agent_cfg, "max_tool_chain_depth", MAX_ITERATIONS, 1, 100),
            "retries": _int(agent_cfg, "max_tool_retries", 0, 0, 10),
            "search_limit": _int(mem_cfg, "search_limit", 5, 1, 20),
            "budget_tokens": _int(ctx_cfg, "budget_tokens", 0, 0, 200_000),
            "auto_compress": bool(ctx_cfg.get("auto_compress", True)),
            "compress_threshold": _float(ctx_cfg, "compress_threshold", 0.8, 0.5, 0.95),
        }

    def _build_system_prefix(self, tools: list[dict]) -> list[dict]:
        """组装 system 前缀：固定角色 → 用户偏好 → 档案 → 技能 → 模型提示。

        顺序固定、内容稳定，目的是让提示缓存命中前缀。每一项失败只降级该项。
        """
        out: list[dict] = [{"role": "system", "content": SYSTEM_FIXED}]
        prefs = str(self.settings.get().get("user_prefs", "")).strip()
        if prefs:
            out.append({"role": "system", "content": f"用户长期偏好：{prefs}"})
        for label, provider in (("档案记忆", self.profile), ("技能索引", self.skills)):
            if provider is None:
                continue
            try:
                block = provider.system_block()
            # aqg: top-level boundary —— 失败仅降级本能力
            except Exception as e:  # noqa: BLE001
                logger.warning("%s读取失败: %s", label, e)
                block = ""
            if block:
                out.append({"role": "system", "content": block})
        if tools and self._is_reasoning_model():
            out.append({
                "role": "system",
                "content": (
                    "注意：你当前是思考模型。如果本环境无法通过 function calling 调用工具，"
                    "请直接说明「当前模型不支持工具调用，建议换用支持工具的非思考模型」，不要假装执行。"
                ),
            })
        return out

    # ---------- 配置读取（统一兜底：Settings 对象 / 裸 dict 都支持） ----------

    def _cfg(self, accessor: str, key: str) -> dict:
        """读取配置块：优先 Settings 的专用访问器（带 pydantic 收敛），退化到 dict。

        早期实现散落 `hasattr(settings, "xx_cfg")` 判断且部分配置块无人读取
        （agent_cfg/auto_compress/search_limit 等），导致设置面板旋钮是装饰品；
        这里统一入口，保证每个旋钮都有真实消费方。
        """
        try:
            fn = getattr(self.settings, accessor, None)
            if callable(fn):
                return fn() or {}
            if isinstance(self.settings, dict):
                v = self.settings.get(key)
                return v if isinstance(v, dict) else {}
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception:  # noqa: BLE001 —— 配置读取失败退回兜底常量
            pass
        return {}

    def _summarizer(self):
        """摘要器：LiteLLM（带成本追踪）优先，否则直连 OpenAI 兼容端点。

        修复要点：此前把 `self._litellm or self._client` 直接当 llm_client 传给
        Store，而 AsyncOpenAI 没有 summarize 方法——未开 LiteLLM（默认）时
        自动摘要每次都 AttributeError 被吞，归档退化为占位句。mock 模式返回
        None（沿用原有行为：mock 不写摘要）。
        """
        if self.mock:
            return None
        litellm_client = self._get_litellm()
        if litellm_client is not None:
            return litellm_client
        if self._summarizer_obj is None:
            from .summarizer import LLMSummarizer

            self._summarizer_obj = LLMSummarizer(self.settings)
        return self._summarizer_obj

    def _llm(self):
        if self.mock:
            return MockLLM(self.settings)
        if self._client is not None:
            return self._client
        from openai import AsyncOpenAI

        llm = self.settings.get().get("llm", {})
        self._client = AsyncOpenAI(
            api_key=llm.get("api_key") or "empty",
            base_url=llm.get("base_url") or None,
            timeout=180,
            max_retries=2,
        )
        return self._client

    def _get_litellm(self):
        """懒加载 LiteLLM 客户端。返回 None 表示未启用。"""
        if self.mock:
            return None  # mock 模式不走 LiteLLM
        if self._litellm is not None:
            return self._litellm
        if not self.settings.use_litellm():
            return None
        from .litellm_client import LiteLLMClient

        self._litellm = LiteLLMClient(self.settings.get())
        return self._litellm

    async def aclose(self) -> None:
        """关闭底层 httpx 连接池（每个 chat 结束后调用，防 FD / 连接泄漏）。"""
        if self._client is not None:
            try:
                await self._client.close()
            except Exception:  # noqa: BLE001
                pass
            self._client = None
        if self._summarizer_obj is not None:
            try:
                await self._summarizer_obj.aclose()
            # aqg: top-level boundary —— 失败仅降级本能力
            except Exception:  # noqa: BLE001
                pass
            self._summarizer_obj = None

    async def _stream(self, messages: list[dict], tools: list[dict] | None):
        """统一流式接口：产出 (content, reasoning, tool_calls_delta)。

        优先级：mock > litellm（settings 开关）> OpenAI SDK 直连。

        附带 side-channel：每次调用结束后 ``self._last_usage`` 被填成
        {"prompt_tokens", "completion_tokens", "total_tokens"}（仅当端点在流里回报了
        usage；拿不到时保持 0）。主循环据此把 token 用量透给前端与 /api/metrics。
        """
        self._last_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
        # ── Mock 模式（离线自测） ──
        if self.mock:
            # mock 不消耗真实 token，但格式必须与真链路一致：前端与测试都靠它判断
            # 「usage 字段是否存在」，用 0 而不是缺失能避免消费方到处写 None 判断。
            prompt_est = _estimate_tokens(messages)
            completion_est = 0
            async for chunk in self._llm().chat(messages, tools):
                d = chunk.choices[0].delta
                if d.content:
                    completion_est += max(1, len(d.content) // CHARS_PER_TOKEN)
                yield d.content, d.reasoning_content, d.tool_calls
            self._last_usage = {
                "prompt_tokens": prompt_est,
                "completion_tokens": completion_est,
                "total_tokens": prompt_est + completion_est,
                "estimated": True,
            }
            return

        # ── LiteLLM 统一网关（可选） ──
        litellm_client = self._get_litellm()
        if litellm_client is not None:
            async for chunk in litellm_client.chat(messages, tools):
                usage = getattr(chunk, "usage", None)
                if usage:
                    self._last_usage = {
                        "prompt_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
                        "completion_tokens": int(getattr(usage, "completion_tokens", 0) or 0),
                        "total_tokens": int(getattr(usage, "total_tokens", 0) or 0),
                    }
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                if delta is None:
                    continue
                reasoning = getattr(delta, "reasoning_content", None) or getattr(delta, "reasoning", None)
                yield delta.content, reasoning, delta.tool_calls
            return

        # ── OpenAI SDK 直连（fast path） ──
        llm = self.settings.get().get("llm", {})
        client = self._llm()
        kwargs = {
            "model": llm.get("model") or "unknown",
            "messages": messages,
            "stream": True,
        }
        if tools:
            kwargs["tools"] = tools
        temp = llm.get("temperature")
        if isinstance(temp, (int, float)) and 0 <= temp <= 2:
            kwargs["temperature"] = temp
        mt = llm.get("max_tokens")
        if isinstance(mt, int) and mt > 0:
            kwargs["max_tokens"] = min(mt, 65536)
        # 请求端点回报 usage：OpenAI / DeepSeek / 智谱 / Kimi 的 OpenAI 兼容端点都支持
        # （本机对 api.qs.al 的 glm-5.3-flash 实测支持）。不支持的端点会 400，
        # 因此失败时自动去掉该参数重试一次——不能为了统计把主链路搞挂。
        try:
            stream = await client.chat.completions.create(
                **kwargs, stream_options={"include_usage": True}
            )
        except Exception as e:  # noqa: BLE001 —— 端点不支持 stream_options 时降级
            logger.debug("端点不支持 stream_options，降级为不统计 usage: %s", e)
            stream = await client.chat.completions.create(**kwargs)
        async for chunk in stream:
            usage = getattr(chunk, "usage", None)
            if usage:
                self._last_usage = {
                    "prompt_tokens": int(getattr(usage, "prompt_tokens", 0) or 0),
                    "completion_tokens": int(getattr(usage, "completion_tokens", 0) or 0),
                    "total_tokens": int(getattr(usage, "total_tokens", 0) or 0),
                }
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if delta is None:
                continue
            reasoning = getattr(delta, "reasoning_content", None) or getattr(delta, "reasoning", None)
            yield delta.content, reasoning, delta.tool_calls

    def _is_reasoning_model(self) -> bool:
        """思考模型启发式识别——精确匹配，避免误报。

        旧实现使用 substring "r1" "o1" "o3"，会误报如 claritan_r1_7b 这类普通模型。
        注意："r1" 已从 token 集合移除，仅通过特定后缀（"_r1"）精确匹配——
        这样 deepseek_r1 能命中，但 claritan_r1_7b 不会误触。
        """
        llm = self.settings.get().get("llm", {})
        m = str(llm.get("model", "")).lower().replace("-", "_").replace(".", "_")
        # "r1" 不放在集合里做 token 交集：model 名里含 "r1"  substr 的普通模型太多
        # （claritan_r1_7b、mistral_r1 等），必须用后缀 "_r1" 精确匹配
        reasoning_words = {"reasoner", "thinking", "reasoning", "think"}
        if set(m.split("_")) & reasoning_words:
            return True
        # 后缀精确匹配（避免子串误报）+ 特定前缀
        if m.endswith(("_r1", "_thinking", "_reasoner")):
            return True
        return m.startswith(("deepseek_r1", "deepseek_think"))

    async def chat(
        self,
        session_id: str,
        message: str,
        emit: Emit,
        ask_approval: AskApproval | None = None,
    ) -> None:
        mode = self.settings.permission_mode()
        tools = [] if mode == "chat" else schemas()
        # 每次 chat 重置本轮累计用量（同一个 Agent 实例可能被队列续跑复用）
        self._turn_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
        limits = self._runtime_limits()
        mem_cfg = limits["mem_cfg"]
        max_iters = limits["max_iters"]
        retries = limits["retries"]
        search_limit = limits["search_limit"]
        budget_tokens = limits["budget_tokens"]
        auto_compress = limits["auto_compress"]
        compress_threshold = limits["compress_threshold"]

        # 组装上下文（顺序固定，前缀稳定以利提示缓存）
        messages: list[dict] = self._build_system_prefix(tools)
        # 跨会话相关记忆 + 分页摘要（两个 system 块）
        messages.extend(await self._memory_context_block(message, session_id, search_limit))
        # 历史消息（含中断恢复与滚动归档后的近期窗口）
        messages.extend(await self._load_history(
            session_id=session_id,
            mem_cfg=mem_cfg,
            budget_tokens=budget_tokens,
            auto_compress=auto_compress,
            compress_threshold=compress_threshold,
        ))
        messages.append({"role": "user", "content": message})
        await self.store.add_message(session_id, "user", message)

        # 工具执行上下文：todo / 知识库这类工具需要知道当前会话、存储与推流回调。
        # set 后整个 await 链（含工具 handler）都能读到；finally 里 reset，绝不跨请求泄漏。
        _tok_s = tool_ctx.current_session.set(session_id)
        _tok_store = tool_ctx.current_store.set(self.store)
        _tok_emit = tool_ctx.current_emit.set(emit)
        # 子代理需要 agent 本体（复用同一套 LLM 配置）与审批回调（审批不豁免）
        _tok_agent = tool_ctx.current_agent.set(self)
        _tok_ask = tool_ctx.current_ask.set(ask_approval)
        try:
            from .telemetry import agent_span

            with agent_span(session_id):
                await self._loop(
                    session_id, messages, tools, emit, ask_approval, mode,
                    max_iters, retries, mem_cfg,
                )
        finally:
            tool_ctx.current_ask.reset(_tok_ask)
            tool_ctx.current_agent.reset(_tok_agent)
            tool_ctx.current_emit.reset(_tok_emit)
            tool_ctx.current_store.reset(_tok_store)
            tool_ctx.current_session.reset(_tok_s)

    async def _loop(
        self,
        session_id: str,
        messages: list[dict],
        tools: list[dict],
        emit: Emit,
        ask_approval: AskApproval | None,
        mode: str,
        max_iters: int,
        retries: int,
        mem_cfg: dict,
    ) -> None:
        """ReAct 主循环：流式 → 决定收尾/落计划/调工具 → 下一轮。

        复杂度从 53 降到 ~10：每一段都抽成了独立方法（_stream_round /
        _finish_plan / _finish_turn / _run_one_tool / _decide_tool），
        这里只保留"这一轮该走哪条路"的判断。
        """
        for _ in range(max_iters):
            messages = _trim(messages)
            try:
                content_acc, tool_calls = await self._stream_round(messages, tools, emit)
            except asyncio.CancelledError:
                raise

            # Plan 模式：首轮输出后即停止，不执行任何工具
            if self.settings.agent_mode() == "plan":
                await self._finish_plan(
                    session_id=session_id, content=content_acc, tool_calls=tool_calls, emit=emit
                )
                return

            # 没有工具调用 = 本轮结束
            if not tool_calls:
                await self._finish_turn(
                    session_id=session_id, content=content_acc,
                    messages=messages, emit=emit, mem_cfg=mem_cfg,
                )
                return

            # 模型要求调用工具：先落一条带 tool_calls 的 assistant 消息
            # 显式 list[dict]：内层 function 值含嵌套 dict，不标注会被 mypy
            # 收窄成 dict[str, str]，导致 c["function"]["name"] 报「对 str 做下标」
            calls_list: list[dict] = [
                {
                    "id": tc["id"] or f"call_{i}",
                    "type": "function",
                    "function": {"name": tc["name"], "arguments": tc["args"] or "{}"},
                }
                for i, tc in sorted(tool_calls.items())
            ]
            messages.append({"role": "assistant", "content": content_acc or None,
                             "tool_calls": calls_list})
            await self.store.add_message(
                session_id, "assistant", content_acc,
                {"tool_calls": [{"id": c["id"], "name": c["function"]["name"],
                                 "args": c["function"]["arguments"][:500]} for c in calls_list]},
            )

            for call in calls_list:
                await self._run_one_tool(
                    session_id=session_id, call=call, messages=messages, emit=emit,
                    ask_approval=ask_approval, mode=mode, retries=retries,
                )

        await emit({"type": "error", "message": "工具调用次数过多，已自动停止"})

    async def _stream_round(
        self,
        messages: list[dict],
        tools: list[dict],
        emit: Emit,
    ) -> tuple[str, dict[int, dict]]:
        """跑一轮流式输出，返回 (累积文本, 按 index 聚合的 tool_calls)。

        聚合逻辑单独成方法：OpenAI 兼容 API 的 tool_calls 是**分片**到达的
        （id 只在第一片、arguments 逐片拼接），这种"边收边拼"的代码塞在主循环里
        会让人误以为是一次性返回的。
        """
        content_acc = ""
        tool_calls: dict[int, dict] = {}
        # LLM 调用次数/耗时此前只有 metrics.record_llm 的定义，全仓没有调用方 ——
        # /api/metrics 里的 pocketmind_llm_calls_total 永远是 0，等于假指标。
        # 在这里计时：主循环和子代理都走 _stream_round，一处接线全覆盖。
        _t0 = time.perf_counter()
        try:
            async for content, reasoning, tc_delta in self._stream(messages, tools):
                if reasoning:
                    await emit({"type": "thinking", "text": reasoning})
                if content:
                    content_acc += content
                    await emit({"type": "text", "text": content})
                if tc_delta:
                    for tc in tc_delta:
                        acc = tool_calls.setdefault(tc.index, {"id": "", "name": "", "args": ""})
                        if tc.id:
                            acc["id"] = tc.id
                        if tc.function:
                            if tc.function.name:
                                acc["name"] = tc.function.name
                            if tc.function.arguments:
                                acc["args"] += tc.function.arguments
        finally:
            usage = self._last_usage or {}
            _m.record_llm(
                time.perf_counter() - _t0,
                prompt_tokens=int(usage.get("prompt_tokens", 0) or 0),
                completion_tokens=int(usage.get("completion_tokens", 0) or 0),
            )
            # 累计到本轮（一次用户消息可能有多轮 tool loop，token 要累加）
            self._turn_usage["prompt_tokens"] += int(usage.get("prompt_tokens", 0) or 0)
            self._turn_usage["completion_tokens"] += int(usage.get("completion_tokens", 0) or 0)
            self._turn_usage["total_tokens"] += int(usage.get("total_tokens", 0) or 0)
        return content_acc, tool_calls

    async def _finish_plan(
        self,
        *,
        session_id: str,
        content: str,
        tool_calls: dict[int, dict],
        emit: Emit,
    ) -> None:
        """Plan 模式收尾：把「计划文本 + 打算调用的工具」交给用户，不执行任何工具。

        参考 Cline Plan/Act：tool_calls 一律不执行，只随计划透出，
        用户切到 act 模式（或下一轮）才真正动手。
        """
        planned = [
            {"name": tc["name"], "arguments": tc["args"] or "{}"}
            for _, tc in sorted(tool_calls.items())
        ]
        plan_text = content.strip()
        if planned:
            calls_desc = "\n".join(f"· 调用 {c['name']}({c['arguments']})" for c in planned)
            plan_text = (f"{plan_text}\n\n计划调用工具：\n{calls_desc}" if plan_text
                         else f"计划调用工具：\n{calls_desc}")
        # 计划本身作为一条 assistant 消息落库（不含 tool_calls，因为并未执行）
        await self.store.add_message(session_id, "assistant", plan_text or "（无具体计划内容）")
        await emit({"type": "plan", "plan": plan_text, "tool_calls": planned})
        await emit({"type": "done", "stop_reason": "plan", "usage": self.usage_payload()})

    async def _finish_turn(self, *, session_id: str, content: str, messages: list[dict],
                           emit: Emit, mem_cfg: dict) -> None:
        """一轮结束（模型没有再要工具）：落库、更新摘要、异步抽 Mem0、发 done。"""
        text = content.strip()
        # usage 一并落进 meta：刷新页面后历史消息仍能显示这次花了多少 token，
        # 否则 replay 只有正文，用量信息只活在这一屏。
        usage = self.usage_payload()
        await self.store.add_message(
            session_id, "assistant", text,
            {"usage": usage} if usage.get("total_tokens") else None,
        )
        # 滚动更新记忆摘要（后续对话开头注入）。失败只降级，不影响主流程。
        try:
            if mem_cfg:
                llm_for_summary = self._summarizer()
                if llm_for_summary:
                    await self.store.auto_summarize_if_needed(
                        session_id, mem_cfg, llm_client=llm_for_summary
                    )
                else:
                    logger.debug("记忆摘要跳过：无可用摘要器 session=%s", session_id)
        except Exception as e:  # noqa: BLE001
            logger.warning("记忆摘要更新失败: %s", e)
        # Mem0 异步抽取长期事实（不阻塞响应；失败仅日志）
        if self.mem0 is not None and self.mem0.enabled() and not self.mock:
            self._fire_and_forget_mem0_add(
                session_id, messages + [{"role": "assistant", "content": text}]
            )
        await emit({"type": "done", "stop_reason": "end_turn", "usage": self.usage_payload()})

    def usage_payload(self) -> dict:
        """本轮 token 用量（done 事件与前端展示共用；无数据时全 0，不返回 None）。"""
        usage = dict(self._turn_usage or {})
        usage.setdefault("prompt_tokens", 0)
        usage.setdefault("completion_tokens", 0)
        usage.setdefault("total_tokens", 0)
        if self.mock:
            usage["estimated"] = True
        return usage

    # ---------- 工具执行（从 _loop 抽出，降低主循环复杂度） ----------

    async def _decide_tool(
        self,
        *,
        tid: str,
        name: str,
        tool,
        mode: str,
        ask_approval: AskApproval | None,
    ) -> str:
        """决定这次工具调用是放行还是拒绝。

        返回 "allow" / "deny" / "timeout"。

        为什么单独一个方法：这里有四条互斥的判定，每条都对应一个真实的安全语义，
        混在主循环里既难读也难测：
        1. chat 模式 → 一律拒绝（模型被注入后仍可能吐 tool_call，必须执行前兜底）
        2. 没有审批通道 → 写/危险操作 fail-closed（否则 approve 模式会静默退化成 auto）
        3. 未知工具 → approve 模式下问用户（模型幻觉出来的名字不能直接执行）
        4. 已知工具 → 按 risk × mode 决定要不要问
        """
        if mode == "chat":
            return "deny"
        if ask_approval is None:
            # fail-closed：没有审批通道时，写/危险操作必须拒绝而不是静默放行。
            # 旧实现默认 "allow"，只要调用方漏传 ask_approval（新入口、测试、
            # MCP 子进程模式），approve 模式就退化成 auto —— 审批机制形同虚设。
            if tool is None or permissions.should_ask(tool.risk, mode):
                return "deny"
            return "allow"
        if tool is None:
            # 模型幻觉出的未知工具：approve 模式下同样要求用户确认，不静默执行
            if mode != "auto":
                return await ask_approval(tid, name, "未知工具，请确认是否放行（通常应拒绝）", "danger")
            return "allow"
        if permissions.should_ask(tool.risk, mode):
            return await ask_approval(tid, name, tool.summary or name, tool.risk)
        return "allow"

    async def _record_checkpoint(self, session_id: str, tid: str, name: str, ck: dict) -> bool:
        """把工具产生的备份登记到 checkpoint 存储，返回是否可撤销。

        失败只降级（不可撤销），不能让一次备份失败毁掉整个工具结果。
        """
        if not (ck and ck.get("backup")):
            return False
        try:
            from .checkpoints import CheckpointStore, get_shared

            # 优先复用进程级共享连接；未注入时（MCP server / 单测）退回一次性新建
            cs = get_shared()
            owns = False
            if cs is None:
                cs = CheckpointStore(self.store.path.parent / "checkpoints.db")
                await cs.start()
                owns = True
            try:
                await cs.record(session_id, tid, name, ck["path"], ck["backup"], ck.get("kind", "restore"))
            finally:
                if owns:
                    await cs.close()
            return True
        except Exception:  # noqa: BLE001 —— 备份登记失败仅失去撤销能力
            return False

    async def _run_one_tool(
        self,
        *,
        session_id: str,
        call: dict,
        messages: list[dict],
        emit: Emit,
        ask_approval: AskApproval | None,
        mode: str,
        retries: int,
    ) -> None:
        """执行单个工具调用：审批 → 执行 → 备份 → 状态 → 落库。

        每个 tool_call 都要走完整条链路，抽出后 _loop 不必再嵌套五层。
        """
        tid = call["id"]
        name = call["function"]["name"]
        try:
            args = json.loads(call["function"]["arguments"] or "{}")
        except json.JSONDecodeError:
            args = {}
        if not isinstance(args, dict):
            args = {}

        tool = get_tool(name)
        await emit({"type": "tool_start", "id": tid, "name": name, "input": args})

        decision = await self._decide_tool(
            tid=tid, name=name, tool=tool, mode=mode, ask_approval=ask_approval
        )

        if decision != "allow":
            if decision == "timeout":
                # 显式 dict 标注：与 else 分支 call_tool 的返回值统一为 dict[Any, Any]，
                # 避免 mypy 把 result 推断成 dict[str, str] 导致 pop 默认值/联合类型误报
                result: dict = {"denied": "审批超时，已自动拒绝"}
            else:
                result = {"denied": "用户拒绝了该操作"}
            status = "denied"
            await emit({"type": "tool_update", "id": tid, "name": name, "status": status, "result": result})
        else:
            _t0 = time.perf_counter()
            result = await call_tool(name, args, retries=retries)
            _m.record_tool(name, time.perf_counter() - _t0, not (result.get("error") or result.get("error_code")))
            # 危险文件操作的备份凭证：剥离出来登记，不进工具结果
            undoable = await self._record_checkpoint(session_id, tid, name, result.pop("_ckpt", None))
            # 状态判定：不能用 `"error" in result`（file 名可含 error 字样），
            # 也不能用 `denied and X or Y`（空字符串 denied 导致逻辑错乱）
            if result.get("error") or result.get("error_code"):
                status = "failed"
            else:
                status = "completed"
            await emit({
                "type": "tool_update", "id": tid, "name": name,
                "status": status, "result": result, "undoable": undoable,
            })

        text = json.dumps(result, ensure_ascii=False)
        limited = _tool_result_limited(text, self.settings)
        messages.append({"role": "tool", "tool_call_id": tid, "content": limited})
        await self.store.add_message(
            session_id, "tool", limited,
            {"tool_call_id": tid, "name": name, "status": status},
        )

    # ---------- 会话自动命名 ----------
    async def title_for(self, message: str) -> str:
        """给新会话生成标题：真实 LLM 一次小请求（≤14 字），失败回退截断首条消息。"""
        if self.mock:
            return self._llm().title(message)
        llm = self.settings.get().get("llm", {})
        if not (llm.get("api_key") and llm.get("model") and llm.get("base_url")):
            return _fallback_title(message)
        try:
            client = self._llm()
            resp = await client.chat.completions.create(
                model=llm.get("model") or "unknown",
                messages=[
                    {"role": "system", "content": "给对话起一个不超过 14 个字的简洁标题，只输出标题本身，不要引号。"},
                    {"role": "user", "content": message[:2000]},
                ],
                max_tokens=20,
                temperature=0.2,
            )
            title = (resp.choices[0].message.content or "").strip().strip('"「」').splitlines()[0][:20]
            if title:
                return title
        except Exception:  # noqa: BLE001 —— 标题失败不阻塞聊天
            pass
        return _fallback_title(message)

    # ---------- 记忆摘要 ----------
    def _fire_and_forget_mem0_add(self, session_id: str, messages: list[dict]) -> None:
        """异步抽取对话事实写入 Mem0（fire-and-forget，非阻塞）。"""

        async def _do():
            try:
                ok = await self.mem0.aadd(messages, session_id=session_id)
                if not ok:
                    logger.debug("Mem0 add 未写入（可能无新事实）session=%s", session_id)
            except Exception as e:  # noqa: BLE001
                logger.warning("Mem0 异步抽取失败（不影响对话）: %s", e)

        try:
            loop = asyncio.get_running_loop()
            task = loop.create_task(_do())
            self._pending_mem0_tasks.add(task)
            task.add_done_callback(self._pending_mem0_tasks.discard)
        except RuntimeError:
            # 无运行事件循环（如同步上下文 from scheduler）：跳过，不阻塞
            logger.debug("Mem0 fire-and-forget 跳过（无运行中事件循环）session=%s", session_id)
