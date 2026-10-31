#!/usr/bin/env python3
"""P28 MCP 双端回环：真实 stdio 握手 + 工具 schema 全量校验。

真实缺陷（本轮审计发现）：`agentd/mcp.py` 的 `_tool_to_dict` 直接读
`t.input_schema`，而 mcp SDK 1.30 起 `Tool` 的字段是驼峰 `inputSchema`，
没有 `input_schema` 属性 —— 于是「主服务配置任意 MCP Server」在启动阶段
直接抛 AttributeError，整个服务起不来；而旧的单测只手工构造 dict，
从来没走过真正的 SDK 对象，因此门禁全绿也发现不了。

本套件不 mock SDK：真起一个 stdio 子进程（PocketMind 自己的 mcp_server），
用官方 ClientSession 握手、list_tools，再经 MCPClient 的转换层解析。
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.environ.setdefault("AGENT_HOME", "/tmp/pa-test")

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


async def run() -> int:
    print("== P28 MCP 双端回环 ==")
    from agentd.mcp import MCPClient, tool_schema

    client = MCPClient("self", sys.executable, ["-m", "agentd.mcp_server"], timeout=30)
    try:
        try:
            tools = await client.connect()
        except Exception as e:  # noqa: BLE001 —— 连接失败就是本套件的核心失败
            check("stdio 握手 + list_tools 成功", False, f"{type(e).__name__}: {e}")
            tools = []
        else:
            check("stdio 握手 + list_tools 成功", True)

        check("工具数量 ≥ 41", len(tools) >= 41, f"count={len(tools)}")
        names = {str(t.get("name", "")) for t in tools}
        check("运行时工具已注册", {"remember_fact", "read_skill"} <= names, str(sorted({"remember_fact", "read_skill"} - names)))
        check(
            "每个工具都有 object schema",
            bool(tools) and all(isinstance(t.get("inputSchema"), dict) and t["inputSchema"].get("type") == "object" for t in tools),
            str([t.get("name") for t in tools if not isinstance(t.get("inputSchema"), dict)][:5]),
        )
        check(
            "每个工具 schema 都有 properties",
            bool(tools) and all("properties" in t["inputSchema"] for t in tools),
            str([t.get("name") for t in tools if "properties" not in t.get("inputSchema", {})][:5]),
        )
        check(
            "tool_schema 兼容驼峰/下划线/dict 三种入参",
            tool_schema({"inputSchema": {"type": "object", "properties": {"a": {}}}})["properties"] == {"a": {}}
            and tool_schema({"input_schema": {"type": "object", "properties": {"b": {}}}})["properties"] == {"b": {}}
            and tool_schema(None) == {"type": "object", "properties": {}},
        )

        # 内部调用：经 MCPClient.call_tool 真正执行一个无副作用工具
        if tools:
            res = await client.call_tool("get_battery", {})
            text = str(res.get("mcp_result") or res.get("error") or "")
            check("真实调用 get_battery 有返回", bool(text), str(res)[:200])
            check(
                "无 termux-api 时返回结构化失败而非崩溃",
                "error" in res or "mcp_result" in res,
                str(res)[:200],
            )
    finally:
        await client.close()

    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：" + "；".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run()))
