#!/usr/bin/env python3
"""P33 Mock 会话多轮工具调用：同一会话的第二轮必须仍然能拿到工具调用。

修的是真实缺陷：`MockLLM.chat()` 原来用 `any(m["role"] == "tool" for m in messages)`
判断"本轮是否已有工具结果"，扫描的是**整段历史**。于是同一会话里只要第一轮调用过
任何工具，之后所有提问都会跳过工具分支、直接回"工具已执行完毕"——mock 演示只能
走通一轮，E2E 与手工验收都会被误导。

同时守住两条相邻约束：

- 工具调用 id 必须逐轮唯一（首轮 `call_mock_1` 是回归套件依赖的固定值），否则
  前端按 `session_id:tool_call_id` 去重时会吞掉后一轮的审批卡。
- 没有 user 消息时不能崩（原实现会对 `None` 做切片/加法）。

不依赖服务、不依赖真机，直接驱动 MockLLM。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

# 中文 Windows 控制台默认 GBK，直接打印 ✅/❌ 会 UnicodeEncodeError 崩溃。
# 统一把 stdout 切到 UTF-8 并用 replace 兜底，保证脚本在任何终端都能跑完。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from agentd.mock_llm import MockLLM  # noqa: E402

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


async def collect(messages, tools):
    """把一次 mock 流收集成 (文本, 推理, 工具调用列表)。"""
    text, reasoning, calls = "", "", []
    async for chunk in MockLLM({}).chat(messages, tools):
        delta = chunk.choices[0].delta
        text += delta.content or ""
        reasoning += delta.reasoning_content or ""
        if delta.tool_calls:
            calls.extend(delta.tool_calls)
    return text, reasoning, calls


TOOLS = [{"name": "get_battery"}, {"name": "send_notification"}]


def main() -> int:
    print("== P33 Mock 多轮工具调用 ==")

    # 1. 首轮：必须发出 get_battery，且 id 是回归套件依赖的 call_mock_1
    text1, reasoning1, calls1 = asyncio.run(
        collect([{"role": "user", "content": "查看电池电量"}], TOOLS)
    )
    check("首轮触发工具调用", len(calls1) == 1, f"calls={len(calls1)}")
    check(
        "首轮 id 为 call_mock_1",
        bool(calls1) and calls1[0].id == "call_mock_1",
        f"id={calls1[0].id if calls1 else None}",
    )
    check("首轮工具名正确", bool(calls1) and calls1[0].function.name == "get_battery")
    check("首轮先输出推理", reasoning1.strip() != "")

    # 2. 同一会话第二轮（历史里已有第一轮的工具结果）：仍必须触发新工具
    history = [
        {"role": "user", "content": "查看电池电量"},
        {"role": "assistant", "content": ""},
        {"role": "tool", "content": '{"percentage": 80}'},
        {"role": "user", "content": "发一条通知，内容是测试"},
    ]
    text2, _, calls2 = asyncio.run(collect(history, TOOLS))
    check("同一会话第二轮仍触发工具调用", len(calls2) == 1, f"calls={len(calls2)}")
    check(
        "第二轮工具名正确",
        bool(calls2) and calls2[0].function.name == "send_notification",
        f"name={calls2[0].function.name if calls2 else None}",
    )
    check(
        "第二轮 id 与首轮不同",
        bool(calls2) and calls2[0].id != "call_mock_1",
        f"id={calls2[0].id if calls2 else None}",
    )
    check("第二轮不再直接回『工具已执行完毕』", "工具已执行完毕" not in text2)

    # 3. 本轮已有工具结果：应收尾，不再发起新调用
    history_done = history + [
        {"role": "assistant", "content": ""},
        {"role": "tool", "content": '{"ok": true}'},
        {"role": "user", "content": "谢谢"},
    ]
    text3, _, calls3 = asyncio.run(collect(history_done, TOOLS))
    check("本轮已有工具结果时收尾不再调用", len(calls3) == 0, f"calls={len(calls3)}")

    # 4. 没有 user 消息：不能崩
    try:
        text4, _, _ = asyncio.run(collect([{"role": "system", "content": "x"}], TOOLS))
        ok4 = isinstance(text4, str)
    except Exception as exc:  # noqa: BLE001 - 这里就是要断言"不抛"
        ok4 = False
        text4 = f"{type(exc).__name__}: {exc}"
    check("无 user 消息不崩溃", ok4, text4)

    # 5. 未命中关键词的普通提问：回显，不调用工具
    text5, _, calls5 = asyncio.run(collect([{"role": "user", "content": "你好呀"}], TOOLS))
    check("普通提问不触发工具", len(calls5) == 0, f"calls={len(calls5)}")
    check("普通提问有回显", "收到" in text5)

    # 6. 工具失败时必须承认失败，不能说"执行完毕/链路是通的"（虚假成功）
    import json as _json

    fail_hist = [
        {"role": "user", "content": "发一条通知"},
        {"role": "assistant", "content": ""},
        {"role": "tool", "content": _json.dumps({"ok": False, "error": "权限被拒"})},
    ]
    text6, _, _ = asyncio.run(collect(fail_hist, TOOLS))
    check("工具失败时不谎报成功", "工具已执行完毕" not in text6, text6[:80])
    check("工具失败时说明未成功", "没有执行成功" in text6, text6[:80])

    # 7. 执行层判定失败（meta.status=failed，例如审批被拒/超时）同样要承认失败
    meta_fail = [
        {"role": "user", "content": "发一条通知"},
        {"role": "assistant", "content": ""},
        {"role": "tool", "content": "{}", "meta": {"status": "failed"}},
    ]
    text7, _, _ = asyncio.run(collect(meta_fail, TOOLS))
    check("meta 标记失败时也承认失败", "没有执行成功" in text7, text7[:80])

    # 8. 工具成功时仍走原来的成功文案
    ok_hist = [
        {"role": "user", "content": "发一条通知"},
        {"role": "assistant", "content": ""},
        {"role": "tool", "content": _json.dumps({"ok": True})},
    ]
    text8, _, _ = asyncio.run(collect(ok_hist, TOOLS))
    check("工具成功时保留成功文案", "工具已执行完毕" in text8, text8[:80])

    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：" + "；".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
