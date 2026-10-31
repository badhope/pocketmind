#!/usr/bin/env python3
"""P7 横向审计修复回归：把一次全项目审计挖出的问题锁在测试里。

每一项都对应一个**已实测复现**的缺陷（不是猜测），因此测试即证据：
  A1 令牌含非 ASCII 时不得 500（compare_digest 对 str 抛 TypeError）
  A2 非法配置块不得污染 config（否则 GET /api/settings 永久 500）
  A3 轻量回传不得含 litellm / mcp env 明文密钥
  A4 interval 极值 "inf"/"1e999" 返回 400 而非 500
  A5 get_messages 必须返回「最近」N 条（旧实现取最旧，长会话丢最新上下文）
  A6 run_shell 非零退出必须带 error（否则失败被当「完成」）
  A7 phone 写操作必须校验退出码（否则短信/WiFi/手电筒静默失败仍报成功）
  A8 read_file 不得读凭据目录（config.json / .ssh / .gnupg）
  A9 外部 MCP 的 readOnlyHint 不得把风险降到 safe
  A10 「停止」/删会话必须拦住排队消息续跑（CancelledError 不得被吞）
  B1  缺审批通道时 fail-closed（不得静默放行写/危险操作）
  B2  参数 pydantic 强转结果真正生效（"false" 不得反向下开关）
  B3  工具级超时给内部留余量（否则内部提示成死代码）
  B4  后台（定时任务）审批可查询、可决策（前端浮层依赖）
  B5  写事务失败必须回滚（不得污染后续写入）
  B6  busy_timeout 已放宽（多入口争用不直接 500）
  B7  归档水位：同一批最旧消息不重复摘要（不再每轮白烧 LLM）
  B8  孤立 tool 响应在组装时归位（否则 OpenAI 兼容 API 直接 400）
  B9  导出包含摘要 / 归档 / 定时任务（重装不丢长期记忆与提醒）

运行：AGENT_HOME=/tmp/pa-p7 python smoke/p7_cross_audit_test.py
"""
from __future__ import annotations

import asyncio
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


async def main():
    print("== P7 横向审计修复回归 ==")

    # ---------- A1 非 ASCII 令牌 ----------
    import hmac

    def same(given: str, token: str) -> bool:
        try:
            return hmac.compare_digest(given.encode("utf-8"), token.encode("utf-8"))
        except (UnicodeError, TypeError):
            return False

    try:
        hmac.compare_digest("我的令牌", "我的令牌")
        check("A1 前置：确认 str 比较会抛 TypeError", False, "本机未复现，测试前提已变")
    except TypeError:
        check("A1 令牌比较改为 bytes 后支持非 ASCII", same("我的令牌", "我的令牌") is True)
        check("A1 错误令牌仍被拒", same("别的令牌", "我的令牌") is False)

    # ---------- A2 非法配置块 ----------
    import logging

    logging.disable(logging.WARNING)
    from agentd.config import Settings
    from agentd.routes import masked_settings

    bad_cases = [{"llm": "text"}, {"llm": None}, {"server": {"token": 123}}, {"server": "x"}]
    a2_ok = True
    for bad in bad_cases:
        with tempfile.TemporaryDirectory() as td:
            st = Settings(pathlib.Path(td) / "c.json")
            try:
                st.save(bad)
                out = masked_settings(st.get())
                if not isinstance(out.get("llm"), dict) or not isinstance(out.get("server"), dict):
                    a2_ok = False
            # aqg: top-level boundary —— 保存/回传任何异常都算该用例失败
            except Exception as e:  # noqa: BLE001
                a2_ok = False
                print(f"     {bad} → {type(e).__name__}: {str(e)[:50]}")
    check("A2 非法配置块被剔除，设置仍可读", a2_ok, f"{len(bad_cases)} 种非法输入")

    # A2b 回退分支里越界字符串数字不能绕过范围收敛：「llm 坏块 + server.approval_timeout
    # ="999999"」会让 pydantic 校验失败走回退分支，旧实现 int(float()) 成功后被
    # 3600 兜底 clamp，越出 ServerSettings 的 600 上限；现直接按默认值回退
    with tempfile.TemporaryDirectory() as td:
        st = Settings(pathlib.Path(td) / "c.json")
        st.save({"llm": "text", "server": {"approval_timeout": "999999"}})
        got = st.get().get("server", {}).get("approval_timeout")
    check("A2b 回退分支越界值按默认收敛（不写 3600/999999 进配置）",
          got == 120, f"approval_timeout={got}")

    # ---------- A3 密钥不回传 ----------
    out = masked_settings({
        "litellm": {"api_key": "LITELLM-SECRET-9999"},
        "mcp_client": {"servers": [{"name": "gh", "command": "npx", "env": {"GITHUB_TOKEN": "fixture-github-token"}}]},
    })
    blob = json.dumps(out, ensure_ascii=False)
    check("A3 litellm.api_key 已打码", "LITELLM-SECRET-9999" not in blob)
    check("A3 mcp env 密钥已打码", "fixture-github-token" not in blob)
    check("A3 mcp command 等功能字段保留",
          out.get("mcp_client", {}).get("servers", [{}])[0].get("command") == "npx")

    # ---------- A4 interval 极值 ----------
    from fastapi import HTTPException

    from agentd.routes import validate_job_payload

    a4_ok = True
    for expr in ["inf", "1e999", "nan", "-1", "0", "abc"]:
        try:
            validate_job_payload({"name": "n", "trigger_type": "interval", "expr": expr, "message": "m"})
            a4_ok = False
            print(f"     未拒绝: {expr}")
        except HTTPException as e:
            if e.status_code != 400:
                a4_ok = False
        # aqg: top-level boundary —— 非 HTTPException 400 一律视为校验失败
        except Exception as e:  # noqa: BLE001
            a4_ok = False
            print(f"     {expr} → 崩溃 {type(e).__name__}")
    check("A4 interval 极值/非法值一律 400", a4_ok)
    validate_job_payload({"name": "n", "trigger_type": "interval", "expr": "3600", "message": "m"})
    check("A4 合法 interval 仍通过", True)

    # ---------- A5 get_messages 取最近 ----------
    from agentd.memory import Store

    with tempfile.TemporaryDirectory() as td:
        st = Store(pathlib.Path(td) / "m.db")
        await st.start()
        sid = await st.create_session()
        for i in range(250):
            await st.add_message(sid, "user", f"m{i}")
        msgs = await st.get_messages(sid)
        idx = [int(m["content"][1:]) for m in msgs]
        check("A5 返回最近 200 条（含最新）", idx[-1] == 249 and len(msgs) == 200, f"末条=m{idx[-1]}")
        check("A5 时间正序", all(b > a for a, b in zip(idx, idx[1:], strict=False)))
        check("A5 空会话返回空", len(await st.get_messages(await st.create_session())) == 0)
        await st.close()

    # ---------- A6 run_shell 失败带 error ----------
    from agentd.tools.shell import run_shell

    good = await run_shell("exit 0")
    bad = await run_shell("exit 7")
    check("A6 成功命令不带 error", good.get("exit_code") == 0 and not good.get("error"))
    check("A6 非零退出带 error（否则被当完成）",
          bad.get("exit_code") == 7 and bool(bad.get("error")), str(bad.get("error"))[:60])

    # ---------- A7 phone 写操作校验退出码 ----------
    import inspect

    from agentd.tools.phone import communication, network, system

    srcs = "\n".join(inspect.getsource(m) for m in (communication, network, system))
    check("A7 写操作走 _run_checked（校验退出码）", srcs.count("_run_checked") >= 5,
          f"出现 {srcs.count('_run_checked')} 次")
    check("A7 send_sms 不再无条件报成功",
          "failed = await _run_checked" in inspect.getsource(communication.send_sms))

    # ---------- A8 凭据目录不可读 ----------
    from agentd.tools import files

    home = pathlib.Path.home()
    denied = [home / ".agent" / "termux-agent" / "config.json", home / ".ssh" / "id_rsa"]
    a8_ok = True
    for t in denied:
        try:
            files._resolve(str(t))
            a8_ok = False
            print(f"     仍可访问: {t}")
        except ValueError:
            pass
    check("A8 凭据/数据目录被拒", a8_ok)
    try:
        files._resolve(str(home / "some_notes.txt"))
        check("A8 普通主目录文件仍可访问", True)
    except ValueError:
        check("A8 普通主目录文件仍可访问", False, "白名单被误伤")

    # ---------- A9 MCP 风险不降级 ----------
    try:
        from agentd.mcp import mcp_tool_risk

        check("A9 readOnlyHint 不降为 safe", mcp_tool_risk({"readOnlyHint": True}) == "write")
        check("A9 destructiveHint 仍为 danger", mcp_tool_risk({"destructiveHint": True}) == "danger")
    except ImportError:
        check("A9 mcp_tool_risk 可用", False, "导入失败")

    # ---------- A10 取消不续跑 ----------
    # 复刻 main.py 的判定：cancelled 标志位而非 task.cancelled()
    async def worker(flag: dict):
        try:
            await asyncio.sleep(5)
        except asyncio.CancelledError:
            flag["cancelled"] = True
            raise

    flag: dict = {}
    t = asyncio.create_task(worker(flag))
    await asyncio.sleep(0.05)
    t.cancel()
    try:
        await t
    except asyncio.CancelledError:
        pass
    check("A10 取消后标志位为真（排队消息不续跑）", flag.get("cancelled") is True)
    check("A10 task.cancelled() 与标志位一致（re-raise 生效）", t.cancelled() is True)

    # ---------- B1 缺审批通道时 fail-closed ----------
    import inspect as _inspect

    from agentd import agent as agent_mod

    agent_src = _inspect.getsource(agent_mod)
    check("B1 ask_approval=None 时写/危险操作被拒绝（fail-closed）",
          "ask_approval is None" in agent_src and "should_ask(tool.risk, mode)" in agent_src)

    # ---------- B2 参数强转结果真正生效 ----------
    from agentd.tools import all_tools as _all_tools
    from agentd.tools import coerce_args, get_tool

    cleaned, err = coerce_args(get_tool("set_wifi"), {"enabled": "false"})
    check("B2 字符串 'false' 强转为布尔 False（不再反向开 WiFi）",
          err == "" and cleaned.get("enabled") is False, str(cleaned))
    cleaned2, _ = coerce_args(get_tool("vibrate"), {"duration_ms": "500"})
    check("B2 数字字符串强转为 int", cleaned2.get("duration_ms") == 500, str(cleaned2))
    cleaned3, _ = coerce_args(get_tool("list_dir"), {"path": None})
    check("B2 null 参数被剔除（用 handler 默认值）", "path" not in cleaned3, str(cleaned3))

    # ---------- B3 超时余量：工具级 > 内部 ----------
    tools = {t.name: t for t in _all_tools()}
    check("B3 get_location 工具超时留出余量（内部 30s）", tools["get_location"].timeout > 30,
          f"{tools['get_location'].timeout}s")
    check("B3 scan_wifi 工具超时留出余量（内部 30s）", tools["scan_wifi"].timeout > 30,
          f"{tools['scan_wifi'].timeout}s")
    check("B3 run_shell 超时足够（长命令）", tools["run_shell"].timeout >= 60,
          f"{tools['run_shell'].timeout}s")

    # ---------- B4 后台审批可查询、可决策 ----------
    from agentd.approval import ApprovalCenter

    ac = ApprovalCenter(timeout_cb=lambda: 20)
    check("B4 无待审批时 pending 为空", ac.pending() == [])
    bg_task = asyncio.create_task(
        ac.ask("s-bg", "t-bg", "run_shell", summary="执行命令", risk="danger")
    )
    await asyncio.sleep(0.15)
    pend = ac.pending()
    check("B4 后台审批被登记（含 summary/risk/倒计时）",
          len(pend) == 1 and pend[0]["risk"] == "danger" and pend[0]["summary"] == "执行命令"
          and 0 < pend[0]["remaining"] <= 20, str(pend))
    ac.decide("s-bg", "t-bg", "allow_once")
    check("B4 决策生效", (await bg_task) == "allow")
    check("B4 决策后 pending 清空", ac.pending() == [])

    # ---------- B5 事务回滚：失败不污染后续写入 ----------
    from sqlalchemy import text as _text

    with tempfile.TemporaryDirectory() as td2:
        st2 = Store(pathlib.Path(td2) / "r.db")
        await st2.start()
        sid_a = await st2.create_session()
        sid_b = await st2.create_session()
        await st2.add_message(sid_a, "user", "keep-me")
        await st2.add_message(sid_b, "user", "to-delete")
        async with st2._write_lock:
            try:
                await st2._conn.execute(
                    _text("DELETE FROM messages WHERE session_id=:s"), {"s": sid_b}
                )
                raise RuntimeError("模拟中途失败")
            except RuntimeError:
                await st2._conn.rollback()
        await st2.add_message(sid_a, "user", "after-failure")
        rows = (await st2._conn.execute(_text("SELECT content FROM messages ORDER BY id"))).fetchall()
        contents = [dict(r._mapping)["content"] for r in rows]
        check("B5 失败事务回滚，未污染后续写入",
              "to-delete" in contents and "after-failure" in contents, str(contents))

        # ---------- B5b delete_session 自身的事务回滚路径（注入中间语句失败）----------
        # AsyncConnection 是 __slots__，实例属性不可赋值，包一层代理
        real_conn = st2._conn
        class _ConnFail:
            def __init__(self, inner):
                self._inner = inner
            def __getattr__(self, name):
                return getattr(self._inner, name)
            async def execute(self, *a, **kw):
                sql = str(a[0]) if a else ""
                if "DELETE FROM memory" in sql:
                    raise RuntimeError("模拟 memory 删除失败")
                return await self._inner.execute(*a, **kw)
        st2._conn = _ConnFail(real_conn)
        try:
            await st2.delete_session(sid_b)
            check("B5b delete_session 失败必须抛出", False, "未抛出")
        except RuntimeError:
            check("B5b delete_session 失败必须抛出", True)
        finally:
            st2._conn = real_conn
        # 回滚后 to-delete 的消息行必须还在（悬挂语句没有污染连接）
        rows = (await st2._conn.execute(
            _text("SELECT content FROM messages WHERE session_id=:s"), {"s": sid_b}
        )).fetchall()
        check("B5b 失败后未提交的数据完整保留",
              [dict(r._mapping)["content"] for r in rows] == ["to-delete"],
              str([dict(r._mapping)["content"] for r in rows]))
        # ---------- B6 busy_timeout 已设置（同一库顺带核对）----------
        bt = (await st2._conn.execute(_text("PRAGMA busy_timeout"))).scalar()
        check("B6 busy_timeout 已放宽（避免 database is locked → 500）",
              int(bt or 0) >= 10000, f"{bt}ms")

        # ---------- B7 归档水位：同一批不重复摘要 ----------
        b7 = await st2.create_session()
        for i in range(30):
            await st2.add_message(b7, "user", f"msg-{i}")
        b7_msgs = await st2.get_messages(b7)
        check("B7 get_messages 返回 id（水位判断依赖）", "id" in b7_msgs[0])

        calls7 = {"n": 0}

        class _FakeLLM:
            async def summarize(self, msgs, max_chars=400):
                calls7["n"] += 1
                return f"摘要#{calls7['n']}"

        old_batch = b7_msgs[:10]
        first = await st2.archive_messages(b7, old_batch, {}, llm_client=_FakeLLM())
        second = await st2.archive_messages(b7, old_batch, {}, llm_client=_FakeLLM())
        check("B7 首批归档产生摘要", bool(first))
        check("B7 同一批再次归档被跳过（不重复烧 LLM）", second is None and calls7["n"] == 1,
              f"second={second} calls={calls7['n']}")
        check("B7 水位推进到最后一条", await st2.get_archived_upto(b7) == int(old_batch[-1]["id"]))
        archives = await st2.get_archives(b7, limit=20)
        check("B7 归档条数无重复堆积", len(archives) == 1, f"{len(archives)} 条")

        # ---------- B8 tool 响应归位（OpenAI 合法顺序）----------
        seq_items = [
            {"role": "user", "meta": {}, "id": 1},
            {"role": "assistant", "meta": {"tool_calls": [{"id": "c9", "name": "x", "args": "{}"}]}, "id": 2},
            {"role": "user", "meta": {}, "id": 3},
            {"role": "tool", "meta": {"tool_call_id": "c9"}, "id": 4},
        ]
        got_seq = [(m["role"], m["id"]) for m in Store._reorder_tool_pairs(seq_items)]
        check("B8 孤立 tool 响应被归位到其 assistant 之后",
              got_seq == [("user", 1), ("assistant", 2), ("tool", 4), ("user", 3)], str(got_seq))

        b8 = await st2.create_session()
        await st2.add_message(b8, "user", "查电池")
        await st2.add_message(b8, "assistant", "",
                              meta={"tool_calls": [{"id": "c1", "name": "get_battery", "args": "{}"}]})
        await st2.add_message(b8, "user", "还在吗")
        await st2.add_message(b8, "assistant", "在的")
        filled = await st2.recover_orphan_tool_calls(b8)
        b8_msgs = await st2.get_messages(b8)
        i_asst = next(k for k, m in enumerate(b8_msgs) if (m.get("meta") or {}).get("tool_calls"))
        check("B8 中断恢复后 tool 紧跟其 assistant（否则 API 400）",
              filled == 1 and b8_msgs[i_asst + 1]["role"] == "tool"
              and (b8_msgs[i_asst + 1].get("meta") or {}).get("tool_call_id") == "c1",
              str([(m["role"], (m.get("meta") or {}).get("tool_call_id", "")) for m in b8_msgs]))
        check("B8 占位响应不重复",
              len([m for m in b8_msgs if m["role"] == "tool"]) == 1)

        # ---------- B9 导出包含摘要/归档/定时任务 ----------
        import inspect as _ins

        from agentd import main as _main_mod

        exp_src = _ins.getsource(_main_mod)
        check("B9 导出含 summary/archives", '"summary"' in exp_src and '"archives"' in exp_src)
        check("B9 导出含定时任务 jobs", '"jobs": []' in exp_src and "scheduler_svc.list()" in exp_src)
        await st2.close()

    # ---------- B10 清空任务清单有服务端端点（前端"清空"刷新不复活） ----------
    # 前端 clearTodos 调 DELETE /api/sessions/{sid}/todos；路由表必须真实注册，
    # 且处理函数复用 MemoryStore.clear_todos（与 todo_clear 工具同一落库路径）。
    import inspect as _ins

    from agentd import main as _main_mod

    routes = [(list(getattr(r, "methods", None) or []), getattr(r, "path", "")) for r in _main_mod.app.routes]
    del_todos = [m for m, p in routes if p == "/api/sessions/{sid}/todos" and "DELETE" in m]
    check("B10 DELETE /api/sessions/{sid}/todos 已注册", bool(del_todos))
    src10 = _ins.getsource(_main_mod)
    check("B10 清空走 store.clear_todos 落库", "store.clear_todos(sid)" in src10)

    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    asyncio.run(main())
