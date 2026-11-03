"""MCP Client：让 PocketMind 作为 MCP Client 接入外部 MCP Server（双向 MCP 桥接）。

与 mcp_server.py（暴露 PocketMind 工具给外部 Client）对应：
- Server 角色：外部 Claude / Cline → PocketMind
- Client 角色（本文件）：PocketMind → 外部 MCP Server（GitHub / Notion / Slack 等）

配置方式（在 settings.mcp_client）：
{
  "mcp_client": {
    "enabled": true,
    "servers": [
      {
        "name": "github",
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-github"],
        "env": {"GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx"},
        "auto_connect": true
      }
    ],
    "timeout": 15
  }
}

工具发现：load_external_tools() 返回 Tool 对象列表，注册进 PocketMind 的工具注册中心。
"""
from __future__ import annotations

import asyncio
import logging
import warnings
from typing import Any

from .mcp import tool_schema

logger = logging.getLogger(__name__)


class MCPClientWrapper:
    """单个 MCP Server 连接的封装（stdio 模式）。"""

    def __init__(self, name: str, command: str, args: list[str], env: dict | None = None, timeout: int = 15):
        warnings.warn(
            "MCPClientWrapper is deprecated, use mcp.MCPClient instead",
            DeprecationWarning,
            stacklevel=2,
        )
        self.name = name
        self.command = command
        self.args = args
        self.env = env or {}
        self.timeout = timeout
        # 已弃用的旧封装：session 类型由 connect() 运行期回填；
        # mcp SDK 存根缺陷（__aenter__ 返回值声明错误），用 Any 规避联合/None 误报
        self._session: Any = None
        self._tools_cache: list[dict] = []

    async def connect(self) -> bool:
        """尝试连接 MCP Server。失败返回 False（连接失败不阻塞启动）。"""
        try:
            from mcp import ClientSession
            from mcp.client.stdio import StdioServerParameters, stdio_client

            params = StdioServerParameters(command=self.command, args=self.args, env=self.env or None)
            self._stdio_ctx = stdio_client(params)
            read, write = await self._stdio_ctx.__aenter__()
            self._session = ClientSession(read, write)
            await self._session.__aenter__()
            await self._session.initialize()
            logger.info("MCP 客户端已连接：%s", self.name)
            return True
        except Exception as exc:
            logger.warning("MCP 客户端连接失败 [%s]（%s）—— 未安装或配置错误", self.name, exc)
            self._session = None
            return False

    async def list_tools(self) -> list[dict]:
        """拉取 Server 暴露的工具列表。"""
        if self._session is None:
            return self._tools_cache
        try:
            tools = await self._session.list_tools()
            self._tools_cache = [
                {
                    "name": f"{self.name}.{t.name}",
                    "description": t.description or "",
                    "input_schema": tool_schema(t),
                    # 透传注解：main.py 用 mcp_tool_risk 决定只读工具能否免审批
                    "annotations": self._annotation_dict(getattr(t, "annotations", None)),
                }
                for t in tools.tools
            ]
        except Exception as exc:
            logger.warning("MCP 工具列表拉取失败 [%s]：%s", self.name, exc)
        return self._tools_cache

    @staticmethod
    def _annotation_dict(ann) -> dict | None:
        """把 SDK 的 ToolAnnotations（pydantic）转成 dict，供风险分级使用。"""
        if ann is None:
            return None
        if isinstance(ann, dict):
            return ann
        dump = getattr(ann, "model_dump", None)
        if callable(dump):
            try:
                return dump()
            # aqg: top-level boundary —— 失败仅降级本能力
            except Exception:  # noqa: BLE001
                return None
        return None

    async def call_tool(self, tool_name: str, arguments: dict) -> dict:
        """调用 Server 的工具（去掉名称前缀 '<server>.' 部分）。"""
        if self._session is None:
            return {"error": f"MCP 客户端 {self.name} 未连接"}
        real_name = tool_name.split(".", 1)[1] if "." in tool_name else tool_name
        try:
            result = await asyncio.wait_for(self._session.call_tool(real_name, arguments), timeout=self.timeout)
            content = []
            for item in result.content:
                if hasattr(item, "text"):
                    content.append(item.text)
            return {"ok": True, "content": content}
        except Exception as exc:
            return {"error": str(exc)[:200]}

    async def close(self) -> None:
        """关闭连接。"""
        if self._session is not None:
            try:
                await self._session.__aexit__(None, None, None)
            except Exception:
                pass
            self._session = None


class MCPClientHub:
    """管理多个 MCP Client 连接。"""

    def __init__(self, servers_cfg: list[dict], timeout: int = 15):
        self._clients: dict[str, MCPClientWrapper] = {}
        for s in servers_cfg:
            if isinstance(s, dict) and s.get("command"):
                name = s.get("name", s["command"])
                self._clients[name] = MCPClientWrapper(
                    name=name, command=s["command"], args=s.get("args", []), env=s.get("env"), timeout=timeout,
                )

    async def connect_all(self) -> int:
        """连接所有 auto_connect=True 的 server；返回连接成功数."""
        ok = 0
        for c in self._clients.values():
            if await c.connect():
                ok += 1
        return ok

    async def collect_tools(self) -> list[dict]:
        """收集所有已连接 Server 的工具。"""
        tools = []
        for c in self._clients.values():
            tools.extend(await c.list_tools())
        return tools

    async def call(self, tool_name: str, arguments: dict) -> dict:
        """路由调用：根据 <server>.<tool> 格式找到对应 Server。"""
        server_name = tool_name.split(".")[0]
        client = self._clients.get(server_name)
        if not client:
            return {"error": f"未找到 MCP Server {server_name}"}
        return await client.call_tool(tool_name, arguments)

    async def close_all(self) -> None:
        """关闭所有连接。"""
        for c in self._clients.values():
            await c.close()

    def status(self) -> dict:
        """返回各连接状态（用于 /api/mcp 路由）。"""
        return {name: "connected" if c._session is not None else "disconnected" for name, c in self._clients.items()}


# ---------- 高级访问函数 ----------

_hub: MCPClientHub | None = None


def init_mcp_client(cfg: dict) -> MCPClientHub | None:
    """根据 settings.mcp_client 配置初始化 Hub。返回 None 表示未启用或空配置。"""
    global _hub
    if not cfg.get("enabled", False):
        return None
    servers = cfg.get("servers", [])
    if not servers:
        return None
    _hub = MCPClientHub(servers, timeout=cfg.get("timeout", 15))
    return _hub


async def shutdown_mcp_client() -> None:
    """清理函数（在 FastAPI lifespan 关闭段调用）。"""
    global _hub
    if _hub is not None:
        await _hub.close_all()
        _hub = None
