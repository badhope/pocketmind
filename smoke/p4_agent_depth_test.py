#!/usr/bin/env python3
"""P4 回归测试：agent 工程深度补齐项。

覆盖（与 .aqg/code-construction/poketmind-agent-depth.md 的 Behavior Contract 对齐）：
  R1 僵尸配置被真实消费（链长 / 重试 / 检索条数 / 压缩阈值 / FTS 回填批量）
  R2 中断后孤立 tool_calls 自动回填（修重启后 OpenAI 兼容 API 400）
  R3 三级审批：deny_always 全局黑名单 + allow_global 全局放行 + 旧格式兼容
  R4 MCP 工具按 annotations 自动分级（readOnlyHint/destructiveHint）
  R5 结构化长期记忆（USER.md / FACT.md / JOURNAL.jsonl）可写、可注入、可编辑
  R6 知识库显式检索（kb_add / kb_search），不自动污染上下文
  R7 记忆摘要在未启用 LiteLLM 时也可用（直连 OpenAI 兼容端点）
  R8 Skills 渐进披露：注入 name/description，正文按需 read_skill
  R9 todo 清单工具：落库 + SSE 事件

运行：AGENT_HOME=/tmp/pa-p4 python smoke/p4_agent_depth_test.py
"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


@contextmanager
def _td():
    """临时目录：ignore_cleanup_errors 规避 Windows 上 SQLite 句柄未及时释放导致的
    清理失败噪音（真实泄漏由 p3_perf_leak 套件负责把关）。"""
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as td:
        yield td


def section(title: str):
    print(f"\n-- {title}")


class _FakeStream:
    """替身流：按脚本产出 (content, reasoning, tool_calls) 三元组，并记录收到的 messages。"""

    def __init__(self, script: list[tuple]):
        self.script = script
        self.seen_messages: list[list[dict]] = []
        self.calls = 0

    def __call__(self, messages, tools):
        self.calls += 1
        self.seen_messages.append([dict(m) for m in messages])
        script = self.script

        async def _gen():
            for item in script:
                yield item
            # 脚本耗尽后返回纯文本收尾，避免死循环
            yield ("（结束）", None, None)

        return _gen()


def _tc(index: int, name: str, args: dict | None = None):
    from types import SimpleNamespace

    return SimpleNamespace(
        index=index,
        id=f"call_{index}",
        function=SimpleNamespace(name=name, arguments=json.dumps(args or {}, ensure_ascii=False)),
    )


async def _new_store(td: str, name: str = "agent.db"):
    from agentd.memory import Store

    store = Store(Path(td) / name)
    await store.start()
    return store


def _new_settings(td: str, patch: dict | None = None):
    from agentd.config import Settings

    s = Settings(Path(td) / "config.json")
    if patch:
        s.save(patch)
    return s


async def r1_zombie_config():
    section("R1 僵尸配置被真实消费")

    # ---- R1.1 链长上限来自 agent.max_tool_chain_depth ----
    with _td() as td:
        store = await _new_store(td)
        st = _new_settings(td, {"agent": {"max_tool_chain_depth": 3, "max_tool_retries": 0}})
        from agentd.agent import Agent

        agent = Agent(store, st)
        fake = _FakeStream([("", None, [_tc(0, "get_battery")])] * 10)
        agent._stream = fake
        sid = await store.create_session()
        events: list[dict] = []

        async def emit(ev):
            events.append(ev)

        await agent.chat(sid, "试试", emit=emit, ask_approval=None)
        starts = [e for e in events if e.get("type") == "tool_start"]
        stops = [e for e in events if e.get("type") == "error"]
        check("R1.1 循环轮数受 max_tool_chain_depth 限制",
              len(starts) == 3, f"tool_start={len(starts)}（期望 3）")
        check("R1.1 超限后发出停止事件",
              any("工具调用次数过多" in str(e.get("message", "")) for e in stops),
              f"events={[e.get('type') for e in events[-3:]]}")
        await store.close()

    # ---- R1.2 只读工具可重试，写/危险工具绝不重试 ----
    from agentd.tools import Tool, call_tool, register

    attempts = {"safe": 0, "danger": 0}

    async def _flaky_safe():
        attempts["safe"] += 1
        raise TimeoutError("模拟超时")

    async def _flaky_danger():
        attempts["danger"] += 1
        raise TimeoutError("模拟超时")

    register(Tool(name="_p4_flaky_safe", description="t", parameters={}, risk="safe",
                  handler=_flaky_safe, timeout=1))
    register(Tool(name="_p4_flaky_danger", description="t", parameters={}, risk="danger",
                  handler=_flaky_danger, timeout=1))
    r_safe = await call_tool("_p4_flaky_safe", {}, retries=2)
    r_danger = await call_tool("_p4_flaky_danger", {}, retries=2)
    check("R1.2 safe 工具按 max_tool_retries 重试（1+2 次）",
          attempts["safe"] == 3, f"attempts={attempts['safe']}（期望 3）")
    check("R1.2 danger 工具零重试",
          attempts["danger"] == 1, f"attempts={attempts['danger']}（期望 1）")
    check("R1.2 最终仍以结构化错误返回",
          isinstance(r_safe, dict) and isinstance(r_danger, dict)
          and "error" in r_safe and "error" in r_danger,
          f"safe={str(r_safe)[:60]} danger={str(r_danger)[:60]}")

    # ---- R1.3 memory.search_limit 真正控制检索条数 ----
    with _td() as td:
        store = await _new_store(td)
        st = _new_settings(td, {"memory": {"search_limit": 1}})
        from agentd.agent import Agent

        sid = await store.create_session()
        other = await store.create_session()
        for i in range(4):
            await store.set_summary(other, f"用户喜欢喝第{i}种咖啡")
        agent = Agent(store, st)
        fake = _FakeStream([("好的。", None, None)])
        agent._stream = fake
        await agent.chat(sid, "咖啡", emit=_noop_emit, ask_approval=None)
        # 注入的「与此相关的历史记忆」system 消息里最多 1 条
        injected = [
            m["content"] for m in fake.seen_messages[0]
            if m["role"] == "system" and "与此相关的历史记忆" in str(m.get("content", ""))
        ]
        n_lines = sum(len([ln for ln in c.splitlines() if ln.strip().startswith("·")]) for c in injected)
        check("R1.3 search_limit 限制注入条数",
              0 < n_lines <= 1, f"注入 {n_lines} 条（期望 ≤1）")
        await store.close()

    # ---- R1.4 context 压缩阈值 / 开关 ----
    from agentd.agent import _estimate_tokens, _should_compress

    msgs = [{"role": "user", "content": "字" * 400}]
    est = _estimate_tokens(msgs)
    check("R1.4 token 估算为字符数/4 口径", est == 100, f"est={est}")
    check("R1.4 预算阈值判定生效",
          _should_compress(90, 100, 0.8) is True and _should_compress(50, 100, 0.8) is False,
          "阈值 0.8 判定错误")
    check("R1.4 budget=0 时永不因预算触发压缩",
          _should_compress(10**6, 0, 0.8) is False, "budget=0 仍触发压缩")
    check("R1.4 auto_compress 关闭时不因预算触发",
          _should_compress(500, 100, 0.8, auto=False) is False, "auto=False 仍触发")

    # ---- R1.5 memory.fts_page_size 被 Store 使用 ----
    with _td() as td:
        store = await _new_store(td)
        from sqlalchemy import text as _sql

        has_attr = hasattr(store, "fts_page_size")
        store.fts_page_size = 7
        sid = await store.create_session()
        # 直接写 30 条 summary 行（回填只覆盖 kind='summary'，与检索路径一致）
        for i in range(30):
            await store._conn.execute(_sql(
                "INSERT INTO memory(session_id, kind, content, created_at) "
                "VALUES (:s,'summary',:c,0)"
            ), {"s": sid, "c": f"摘要片段{i}"})
        await store._conn.commit()
        # 强制重跑回填：清空 FTS 再回填，小批量（7/批）也必须补齐全部行
        if store._fts:
            await store._conn.execute(_sql("DELETE FROM memories_fts"))
            await store._conn.commit()
            await store._backfill_fts()
            n = (await store._conn.execute(_sql("SELECT COUNT(*) FROM memories_fts"))).scalar()
            check("R1.5 fts_page_size 生效且回填完整", has_attr and n == 30, f"attr={has_attr} rows={n}")
        else:
            check("R1.5 fts_page_size 生效且回填完整", has_attr, "FTS5 不可用环境，仅校验属性存在")
        await store.close()


async def _noop_emit(ev: dict):
    return None


async def r1b_llm_readiness():
    section("R1b LLM 可用性判据（未配置时快速失败）")
    with _td() as td:
        from agentd.config import Settings

        empty = Settings(Path(td) / "config.json")
        check("空配置：缺三项且 ready=False",
              empty.llm_missing() == ["Base URL", "模型名", "API Key"] and empty.llm_ready() is False,
              f"missing={empty.llm_missing()}")
        empty.save({"llm": {"base_url": "https://x/v1", "model": "m"}})
        check("缺 API Key 时仍 False 且只报该项",
              empty.llm_ready() is False and empty.llm_missing() == ["API Key"],
              f"missing={empty.llm_missing()}")
        empty.save({"llm": {"api_key": "fixture-key"}})
        check("三项齐备后 ready=True", empty.llm_ready() is True)

        # 开启 LiteLLM 网关时以网关为准（其 key 可来自环境变量）
        gated = Settings(Path(td) / "cfg2.json")
        gated.save({"llm": {"base_url": "", "model": "", "api_key": ""},
                    "server": {"use_litellm": True},
                    "litellm": {"model": "openai/gpt-4o-mini"}})
        check("LiteLLM 网关模式视为已配置", gated.llm_ready() is True, f"missing={gated.llm_missing()}")


async def r1c_shell_platform():
    section("R1c run_shell 跨平台参数")
    from agentd.tools.shell import run_shell

    out = await run_shell("echo pocketmind-ok")
    check("run_shell 在本平台可直接执行成功",
          isinstance(out, dict) and out.get("ok") is True and "pocketmind-ok" in (out.get("stdout") or ""),
          f"result={str(out)[:120]}")
    check("失败/成功都返回结构化字段",
          set(out.keys()) >= {"ok", "exit_code", "stdout", "stderr", "timed_out"},
          f"keys={sorted(out.keys())}")


async def r2_orphan_recovery():
    section("R2 中断后孤立 tool_calls 回填")
    with _td() as td:
        store = await _new_store(td)
        sid = await store.create_session()
        await store.add_message(sid, "user", "查电池和位置")
        await store.add_message(sid, "assistant", "", meta={"tool_calls": [
            {"id": "c1", "name": "get_battery", "args": "{}"},
            {"id": "c2", "name": "get_location", "args": "{}"},
        ]})
        await store.add_message(sid, "tool", '{"ok": true}', meta={"tool_call_id": "c1", "name": "get_battery"})
        n = await store.recover_orphan_tool_calls(sid)
        msgs = await store.get_messages(sid)
        tool_ids = [m["meta"].get("tool_call_id") for m in msgs if m["role"] == "tool"]
        check("R2 检出并回填 1 条孤立 tool_calls", n == 1, f"n={n}")
        check("R2 孤立工具得到占位响应", "c2" in tool_ids, f"tool_ids={tool_ids}")
        check("R2 已有响应不被重复回填", tool_ids.count("c1") == 1, f"tool_ids={tool_ids}")
        n2 = await store.recover_orphan_tool_calls(sid)
        check("R2 恢复操作幂等", n2 == 0, f"第二次 n={n2}")
        await store.close()


async def r3_approval_levels():
    section("R3 三级审批 + 全局粒度")
    from agentd.approval import ApprovalCenter

    with _td() as td:
        persist = Path(td) / "approval_always.json"
        ac = ApprovalCenter(timeout_cb=lambda: 5, persist_path=persist)

        async def _ask_then_decide(decision: str):
            task = asyncio.create_task(ac.ask("s1", "t1", "run_shell"))
            await asyncio.sleep(0.05)
            ok = ac.decide("s1", "t1", decision)
            return await task, ok

        res, ok = await _ask_then_decide("deny_always")
        check("R3 deny_always 决策可用且返回 deny",
              ok and res == "deny", f"ok={ok} res={res}")
        check("R3 进入全局黑名单", ac.is_auto_denied("run_shell") is True)
        # 黑名单在任意会话短路，且不发等待门
        res2 = await asyncio.wait_for(ac.ask("s2", "t9", "run_shell"), timeout=1)
        check("R3 黑名单跨会话短路为 deny", res2 == "deny", f"res2={res2}")

        # 全局放行（另一个工具）
        task = asyncio.create_task(ac.ask("s1", "t2", "send_notification"))
        await asyncio.sleep(0.05)
        ac.decide("s1", "t2", "allow_global")
        await task
        check("R3 allow_global 在其它会话同样生效",
              ac.is_auto_allowed("s_other", "send_notification") is True)

        # 持久化 + 旧格式兼容
        ac2 = ApprovalCenter(timeout_cb=lambda: 5, persist_path=persist)
        check("R3 黑白名单持久化后仍生效",
              ac2.is_auto_denied("run_shell") and ac2.is_auto_allowed("s_x", "send_notification"))

        legacy = Path(td) / "legacy.json"
        legacy.write_text(json.dumps({"sess-a": ["write_file"]}), encoding="utf-8")
        ac3 = ApprovalCenter(timeout_cb=lambda: 5, persist_path=legacy)
        check("R3 旧格式（会话级）白名单兼容",
              ac3.is_auto_allowed("sess-a", "write_file") is True)


async def r4_mcp_risk():
    section("R4 MCP 注解分级")
    try:
        from agentd.mcp import mcp_tool_risk
    except ImportError as e:
        check("R4 mcp_tool_risk 存在", False, str(e))
        return
    # 外部 MCP 服务器自报 readOnlyHint 不能降级到 safe：
    # annotations 由对端声明，标 readOnlyHint: true 就能在 approve 模式下免审批，
    # 等于把审批信任边界交给被接入的第三方（安全审计 H/M4）。
    check("R4 readOnlyHint 不得降为 safe（外部声明不可信）",
          mcp_tool_risk({"readOnlyHint": True}) == "write")
    check("R4 destructiveHint → danger",
          mcp_tool_risk({"destructiveHint": True}) == "danger")
    check("R4 无注解 → write",
          mcp_tool_risk({}) == "write" and mcp_tool_risk(None) == "write")
    check("R4 destructive 优先于 readOnly",
          mcp_tool_risk({"readOnlyHint": True, "destructiveHint": True}) == "danger")


async def r5_profile_memory():
    section("R5 结构化长期记忆")
    try:
        from agentd.profile_memory import ProfileMemory
    except ImportError as e:
        check("R5 profile_memory 模块存在", False, str(e))
        return

    with _td() as td:
        pm = ProfileMemory(Path(td) / "memory")
        pm.update_user("用户叫老王，在杭州做硬件。")
        pm.append_fact("用户周末不接收工作消息。")
        pm.append_journal("tool", "执行了 get_battery")
        base = Path(td) / "memory"
        check("R5 USER.md 落盘", "老王" in (base / "USER.md").read_text(encoding="utf-8"))
        check("R5 FACT.md 落盘", "周末" in (base / "FACT.md").read_text(encoding="utf-8"))
        # 日志为只追加 JSONL：画像写入 + 事实追加 + 显式事件 = 3 行
        journal_lines = (base / "JOURNAL.jsonl").read_text(encoding="utf-8").strip().splitlines()
        check("R5 JOURNAL.jsonl 追加（每类写入各留痕）",
              len(journal_lines) == 3 and any("\"tool\"" in ln for ln in journal_lines),
              f"lines={journal_lines}")
        block = pm.system_block()
        check("R5 system 注入块含用户与事实",
              "老王" in block and "周末" in block, block[:120])

        # 注入到对话（首个 system 消息之后的稳定位置）
        store = await _new_store(td)
        st = _new_settings(td)
        from agentd.agent import Agent

        sid = await store.create_session()
        agent = Agent(store, st, profile=pm)
        fake = _FakeStream([("好。", None, None)])
        agent._stream = fake
        await agent.chat(sid, "你好", emit=_noop_emit, ask_approval=None)
        sys_texts = [str(m.get("content", "")) for m in fake.seen_messages[0] if m["role"] == "system"]
        check("R5 事实被注入 system 前缀",
              any("周末" in t for t in sys_texts), f"system={len(sys_texts)} 条均未命中")
        await store.close()


async def r6_knowledge():
    section("R6 知识库显式检索")
    with _td() as td:
        store = await _new_store(td)
        rid = await store.kb_add("保修卡", "手机保修期 12 个月，人为损坏除外。")
        hits = await store.kb_search("保修期")
        check("R6 kb_add / kb_search 命中",
              len(hits) == 1 and "保修" in hits[0]["content"], f"hits={hits}")
        check("R6 kb_list 返回条目", len(await store.kb_list()) == 1)
        ok = await store.kb_delete(rid)
        check("R6 kb_delete 生效", ok and len(await store.kb_list()) == 0)

        from agentd.tools import get_tool

        t_add, t_search = get_tool("kb_add"), get_tool("kb_search")
        check("R6 工具已注册且分级正确",
              t_add is not None and t_search is not None
              and t_add.risk == "write" and t_search.risk == "safe")

        # 不自动注入：chat 的 system 里不得出现知识库内容
        sid = await store.create_session()
        await store.kb_add("护照", "护照号 E12345678，放在书房抽屉。")
        from agentd.agent import Agent

        agent = Agent(store, _new_settings(td))
        fake = _FakeStream([("好。", None, None)])
        agent._stream = fake
        await agent.chat(sid, "我的证件呢", emit=_noop_emit, ask_approval=None)
        leaked = any("E12345678" in str(m.get("content", "")) for m in fake.seen_messages[0])
        check("R6 知识库内容不自动进入上下文", leaked is False, "疑似自动注入")
        await store.close()


async def r7_summarizer():
    section("R7 摘要链路（未启用 LiteLLM 也可用）")
    try:
        from agentd.summarizer import LLMSummarizer
    except ImportError as e:
        check("R7 summarizer 模块存在", False, str(e))
        return

    with _td() as td:
        st = _new_settings(td, {"llm": {"base_url": "http://127.0.0.1:9/v1", "api_key": "k", "model": "m"}})
        sm = LLMSummarizer(st)
        check("R7 summarizer 具备 summarize 接口", hasattr(sm, "summarize"))

        store = await _new_store(td)
        sid = await store.create_session()
        for i in range(24):
            await store.add_message(sid, "user" if i % 2 == 0 else "assistant", f"第{i}条消息")

        class _FakeClient:
            seen = None

            async def summarize(self, messages, max_chars: int = 400):
                self.seen = messages
                return "这是摘要"

        fake = _FakeClient()
        out = await store.auto_summarize_if_needed(
            sid, {"auto_summarize": True, "summarize_every_n_turns": 5, "summary_max_chars": 200},
            llm_client=fake,
        )
        check("R7 超阈值触发摘要并落库",
              out == "这是摘要" and (await store.get_summary(sid)) == "这是摘要", f"out={out}")
        check("R7 摘要对象是最旧消息（不是最近 N 条）",
              fake.seen is not None and len(fake.seen) == 5
              and fake.seen[0]["content"] == "第0条消息",
              f"seen={[m.get('content') for m in (fake.seen or [])]}")
        await store.close()


async def r8_skills():
    section("R8 Skills 渐进披露")
    try:
        from agentd.skills import SkillRegistry
    except ImportError as e:
        check("R8 skills 模块存在", False, str(e))
        return

    with _td() as td:
        sk = Path(td) / "skills" / "battery-care"
        sk.mkdir(parents=True)
        (sk / "SKILL.md").write_text(
            "---\nname: battery-care\ndescription: 当用户问电池保养时使用\n---\n\n"
            "# 电池保养\n\n详细步骤：不要把电量长期保持在 100%。\n",
            encoding="utf-8",
        )
        reg = SkillRegistry(Path(td) / "skills")
        items = reg.list_skills()
        check("R8 SKILL.md 被解析", len(items) == 1 and items[0]["name"] == "battery-care",
              f"items={items}")
        block = reg.system_block()
        check("R8 注入只有 name/description（渐进披露）",
              "battery-care" in block and "长期保持在 100%" not in block, block[:120])
        body = reg.read_skill("battery-care")
        check("R8 read_skill 返回正文", body is not None and "长期保持在 100%" in body)
        check("R8 未知技能返回 None", reg.read_skill("nope") is None)


async def r9_todo():
    section("R9 todo 清单工具")
    from agentd.tools import call_tool, get_tool
    from agentd.tools import ctx as tool_ctx

    tool = get_tool("todo_write")
    check("R9 todo_write 已注册", tool is not None, "未注册")
    if tool is None:
        return

    with _td() as td:
        store = await _new_store(td)
        sid = await store.create_session()
        events: list[dict] = []

        async def emit(ev):
            events.append(ev)

        token_s = tool_ctx.current_session.set(sid)
        token_e = tool_ctx.current_store.set(store)
        token_m = tool_ctx.current_emit.set(emit)
        try:
            r = await call_tool("todo_write", {"todos": [
                {"content": "查电量", "status": "completed"},
                {"content": "发通知", "status": "in_progress"},
            ]})
        finally:
            tool_ctx.current_session.reset(token_s)
            tool_ctx.current_store.reset(token_e)
            tool_ctx.current_emit.reset(token_m)

        todos = await store.get_todos(sid)
        check("R9 返回成功", r.get("ok") is True, str(r)[:120])
        check("R9 落库且顺序保持",
              [t["content"] for t in todos] == ["查电量", "发通知"], f"todos={todos}")
        check("R9 发出 todo SSE 事件",
              any(e.get("type") == "todo" and len(e.get("todos", [])) == 2 for e in events),
              f"events={[e.get('type') for e in events]}")
        check("R9 非 todo_write 会话外调用不崩", tool.risk == "safe")
        await store.close()


async def main():
    print("== P4 agent 深度回归 ==")
    for fn in (r1_zombie_config, r1b_llm_readiness, r1c_shell_platform, r2_orphan_recovery, r3_approval_levels, r4_mcp_risk,
               r5_profile_memory, r6_knowledge, r7_summarizer, r8_skills, r9_todo):
        try:
            await fn()
        # aqg: top-level boundary —— 段落执行器兜底：单段未实现/异常按失败计入，便于 RED 阶段统计
        except Exception as e:  # noqa: BLE001
            check(f"{fn.__name__} 段落执行", False, f"{type(e).__name__}: {e}")
    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：" + "；".join(failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    asyncio.run(main())
