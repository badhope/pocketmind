#!/usr/bin/env python3
"""MCP server 验证：把手机工具注册表暴露成 MCP tools。

不依赖真实 MCP 客户端 / stdio，直接在内存里 build_server 后：
  1. 工具列表至少 41 个，并包含 39 个内置工具与 2 个运行时工具；
  2. 每个工具都带合法 inputSchema（type=object，有 properties）；
  3. 每个工具的 description 带 [safe]/[write]/[danger] 风险前缀；
  4. 调用工具时确实转发到 agentd.tools.call_tool（mock 后断言入参正确）。
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("AGENT_HOME", "/tmp/pa-test")

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


async def run():
    print("== MCP server 验证 ==")
    from agentd.mcp_server import build_server

    mcp = build_server("pocket-agent")
    tools = await mcp.list_tools()

    # 1. 工具数量
    check("工具列表非空", len(tools) > 0, f"count={len(tools)}")
    check("工具数量至少 41", len(tools) >= 41, f"count={len(tools)}")
    runtime_names = {"remember_fact", "read_skill"}
    check("运行时工具已注册", runtime_names <= {t.name for t in tools}, f"missing={runtime_names - {t.name for t in tools}}")

    # 2. 每个工具有合法 inputSchema。
    # 字段名以 SDK 实际暴露的为准（1.30 起是驼峰 inputSchema，没有 input_schema 属性）；
    # 直接读错字段会让本套件在升级 SDK 后崩掉——而它本该验证的正是 schema 内容。
    from agentd.mcp import tool_schema

    bad_schema = [t.name for t in tools if (tool_schema(t) or {}).get("type") != "object"]
    check("每个工具 inputSchema 均为 object", not bad_schema, str(bad_schema))
    no_props = [t.name for t in tools if "properties" not in (tool_schema(t) or {})]
    check("每个工具 inputSchema 都有 properties 字段", not no_props, str(no_props))

    # 3. 风险前缀
    no_tag = [t.name for t in tools if not (t.description or "").startswith(("[safe]", "[write]", "[danger]"))]
    check("每个工具 description 带风险前缀", not no_tag, str(no_tag))
    danger = [t.name for t in tools if (t.description or "").startswith("[danger]")]
    check("高危工具被正确标注", "run_shell" in danger and "make_call" in danger, str(danger))

    # 4. call_tool 转发：mock 掉 agentd.mcp_server.call_tool，断言入参被正确转发
    captured = {}

    async def fake_call_tool(name, args):
        captured["name"] = name
        captured["args"] = args
        return {"ok": True, "echo": args}

    with mock.patch("agentd.mcp_server.call_tool", side_effect=fake_call_tool):
        await mcp.call_tool("get_battery", {})
    check("调用工具转发到 call_tool", captured.get("name") == "get_battery", str(captured))
    check("无参工具入参为空 dict", captured.get("args") == {}, str(captured))

    # 带参工具：验证参数被原样转发（get_battery 无参，换 send_sms）
    captured.clear()
    with mock.patch("agentd.mcp_server.call_tool", side_effect=fake_call_tool):
        await mcp.call_tool("send_sms", {"numbers": ["10086"], "text": "hi"})
    check("send_sms 参数被正确转发",
          captured.get("args") == {"numbers": ["10086"], "text": "hi"}, str(captured))

    # 5. schema 正确性抽查：send_sms 必填字段在 required 里
    sms = next(t for t in tools if t.name == "send_sms")
    sms_schema = tool_schema(sms)
    check("send_sms 把 numbers/text 标为必填",
          set(sms_schema.get("required", [])) >= {"numbers", "text"},
          str(sms_schema.get("required")))

    print(f"\n工具总数：{len(tools)}")
    print(f"通过 {len(passed)} / {len(passed) + len(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run()))
