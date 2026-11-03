"""MCP 客户端（P1-4：官方 mcp Python SDK 替换手写 JSON-RPC stdio）。

仍保持与旧手写客户端完全一致的对外接口：
  client = MCPClient(name, command, args)
  tools = await client.connect()          # -> [{name, description, inputSchema}]
  result = await client.call_tool(name, arguments)  # -> {"mcp_result": str} / {"error": ...}
  await client.close()

协议版本协商、stderr 日志、错误处理、子进程生命周期全部交给官方 SDK；
本类只做薄封装：把 SDK 的 Tool / CallToolResult 转成 main.py 期望的 dict 形状。
"""
from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack
from typing import Any


def tool_schema(tool) -> dict:
    """读取 MCP Tool 的 inputSchema，兼容 SDK 1.x 的两种字段形态。

    为什么需要它：mcp SDK 的 ``Tool`` 是 pydantic 模型，1.30 起字段名是
    ``inputSchema``（驼峰 alias），没有 ``input_schema`` 属性；更早的 1.x 版本
    恰好相反。直接写 ``t.input_schema`` 在 1.30 上抛 AttributeError ——
    主服务接入任意 MCP Server 时启动即崩，而单元测试因为手工构造 dict 从未覆盖到。
    这里按「属性 → alias → dict → model_dump」的顺序取值，缺省返回空 object schema。
    """
    if tool is None:
        return {"type": "object", "properties": {}}
    if isinstance(tool, dict):
        raw = tool.get("inputSchema") or tool.get("input_schema")
    else:
        raw = getattr(tool, "inputSchema", None)
        if raw is None:
            raw = getattr(tool, "input_schema", None)
        if raw is None:
            dump = getattr(tool, "model_dump", None)
            if callable(dump):
                try:
                    data = dump(by_alias=True)
                # aqg: top-level boundary —— 序列化失败按空 schema 处理，不让整个工具列表崩
                except Exception:  # noqa: BLE001
                    data = None
                if isinstance(data, dict):
                    raw = data.get("inputSchema") or data.get("input_schema")
    if not isinstance(raw, dict) or not raw:
        return {"type": "object", "properties": {}}
    return raw


def mcp_tool_risk(annotations) -> str:
    """由 MCP 工具注解推导风险等级（对标 goose 的 apply_tool_annotations 思路）。

    MCP 规范里工具可声明 annotations：
      - destructiveHint=true → 危险（可能造成不可逆破坏）→ "danger"
      - 其余（含 readOnlyHint=true、无注解）→ "write"
        readOnlyHint 故意**不**降为 safe：注解由对端服务器自己声明，把删除类工具
        标成 readOnly 就能在 approve 模式免审批执行——等于把信任边界交给被接入方。
        外部工具最低是 write（仍需确认），safe 只属于本地内建工具。

    返回本项目 tools.risk 的三档值；annotations 允许是 dict / pydantic 对象 / None。
    """
    ann = annotations
    if ann is None:
        return "write"
    if not isinstance(ann, dict):
        # pydantic 的 ToolAnnotations：转 dict；失败则按最保守处理
        dump = getattr(ann, "model_dump", None)
        if callable(dump):
            try:
                ann = dump()
            # aqg: top-level boundary —— 失败仅降级本能力
            except Exception:  # noqa: BLE001
                return "write"
        else:
            return "write"
    if bool(ann.get("destructiveHint")):
        return "danger"
    # readOnlyHint 只作为「降一级」使用，且永远不降到 safe：
    # annotations 由对端服务器自己声明，把删除类工具标成 readOnlyHint: true
    # 就能在 approve 模式下免审批执行——这是把信任边界交给了被接入方。
    # 因此外部 MCP 工具最低为 write（仍需确认），只有本地内建工具才可能是 safe。
    return "write"


class MCPClient:
    """连接一个 stdio MCP 服务器（官方 SDK 管理子进程 + JSON-RPC 会话）。"""

    def __init__(self, name: str, command: str, args: list[str] | None = None, timeout: float = 20.0):
        self.name = name
        self.command = command
        self.args = args or []
        self.timeout = timeout
        self.tools: list[str] = []  # 由 main.py 连接成功后回填
        # AsyncExitStack 负责长期持有 stdio_client 与 ClientSession 两个异步上下文，
        # 使连接在整个应用生命周期内保持打开，而不是每次调用都重连。
        self._stack: AsyncExitStack | None = None
        self._session: Any = None  # ClientSession，connect() 成功后回填；SDK 存根缺陷，Any 规避

    async def connect(self) -> list[dict]:
        """启动子进程并完成 initialize 握手；返回 tools/list 结果（dict 形状）。"""
        from mcp import ClientSession
        from mcp.client.stdio import StdioServerParameters, stdio_client

        params = StdioServerParameters(command=self.command, args=list(self.args))
        self._stack = AsyncExitStack()
        read, write = await self._stack.enter_async_context(stdio_client(params))
        # mcp SDK 存根缺陷：ClientSession.__aenter__ 实际返回自身（ClientSession），
        # 存根却声明为 None，导致 AsyncExitStack.enter_async_context 推断为「不返回值」；
        # 运行时 await 结果就是可用的 session
        self._session = await self._stack.enter_async_context(
            ClientSession(read, write)  # type: ignore[arg-type, func-returns-value]  # 三方 SDK 泛型缺陷
        )
        # 官方 SDK 在此完成协议版本协商 / 能力交换
        await self._session.initialize()
        result = await self._session.list_tools()
        return [self._tool_to_dict(t) for t in (result.tools or [])]

    @staticmethod
    def _tool_to_dict(t) -> dict:
        """把 SDK 的 Tool 对象转成 main.py 期望的 dict（inputSchema 驼峰对齐）。

        同时透传 annotations：main.py 据此把只读工具放行、破坏性工具升级为 danger。
        """
        ann = getattr(t, "annotations", None)
        annotations = None
        if ann is not None:
            dump = getattr(ann, "model_dump", None)
            if callable(dump):
                try:
                    annotations = dump()
                # aqg: top-level boundary —— 失败仅降级本能力
                except Exception:  # noqa: BLE001
                    annotations = None
            elif isinstance(ann, dict):
                annotations = ann
        return {
            "name": t.name,
            "description": t.description or "",
            "inputSchema": tool_schema(t),
            "annotations": annotations,
        }

    async def call_tool(self, tool_name: str, arguments: dict) -> dict:
        """调用 MCP 工具，返回结构化结果（兼容内建工具的输出形状）。"""
        if self._session is None:
            return {"error": "MCP 客户端未连接"}
        try:
            result = await asyncio.wait_for(
                self._session.call_tool(tool_name, arguments or {}),
                timeout=self.timeout,
            )
        except TimeoutError:
            return {"error": f"MCP 工具 {tool_name} 超时"}
        except Exception as e:  # noqa: BLE001 —— 调用异常转成结构化错误，不冒泡崩服务
            return {"error": f"MCP 调用失败：{e}"}
        # SDK 的 CallToolResult：is_error 标记业务错误，content 为内容块列表
        if getattr(result, "is_error", False):
            texts = [
                c.text for c in (result.content or [])
                if getattr(c, "type", "") == "text"
            ]
            return {"error": "\n".join(t for t in texts if t) or "MCP 调用失败"}
        texts = [
            c.text for c in (result.content or [])
            if getattr(c, "type", "") == "text"
        ]
        return {"mcp_result": "\n".join(texts)}

    async def close(self) -> None:
        """关闭 stdio 子进程（由 SDK 负责优雅退出 + 清理）。"""
        if self._stack is not None:
            try:
                await self._stack.aclose()
            except Exception:  # noqa: BLE001
                pass
        self._stack = None
        self._session = None
