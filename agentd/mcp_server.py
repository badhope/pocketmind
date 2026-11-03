"""MCP Server：把手机工具注册表暴露为 MCP tools，供桌面端「远程控制手机」。

桌面端（Claude Desktop / Cline / Codex）通过 stdio 连接本模块后，即可直接调用
手机上的 电量 / 短信 / 定位 / 文件 / shell 等工具。

启动方式：
    python -m agentd.mcp_server                # 默认 stdio 传输
    python -m agentd.main --mcp-server         # 等价入口（不启动 FastAPI）

设计要点：
  - 用官方 mcp SDK 的 FastMCP（mcp 1.x）；mcp 2.x 已改名为 MCPServer，这里做兼容导入。
  - **动态遍历** ``agentd.tools.all_tools()`` 注册，不手写每个工具。
  - inputSchema 由工具自带的 JSON Schema 推导：给包装函数动态挂一个
    ``__signature__``（FastMCP 会据此生成 pydantic 模型 / JSON Schema）。
  - description 前加风险等级前缀：``[safe]`` / ``[write]`` / ``[danger]``，
    让桌面端模型一眼看出哪些是高危操作。
  - 工具执行统一走 ``agentd.tools.call_tool``（含参数校验 / 超时 / 异常兜底）。
"""
from __future__ import annotations

import inspect

# mcp 1.x：from mcp.server.fastmcp import FastMCP
# mcp 2.x：FastMCP 改名 MCPServer（from mcp.server.mcpserver import MCPServer）
# 统一兼容导入，别名为 FastMCP，调用处无感。
try:  # pragma: no cover - 取决于环境装的大版本
    # mcp SDK 1.x/2.x 的导出路径不同（fastmcp / mcpserver），mypy 存根只认其一；
    # 运行时由 try/except 兜底兼容两种大版本
    from mcp.server.fastmcp import FastMCP  # type: ignore[attr-defined]  # 三方 SDK 泛型/导出缺陷
except ModuleNotFoundError:  # pragma: no cover
    from mcp.server.mcpserver import MCPServer as FastMCP  # type: ignore[attr-defined]  # 三方 SDK 泛型/导出缺陷

from .tools import all_tools, call_tool

# JSON Schema 基本类型 → Python 注解（FastMCP 据此推导 inputSchema）
_TYPE_MAP = {
    "string": str,
    "integer": int,
    "number": float,
    "boolean": bool,
    "array": list,
    "object": dict,
}

# 风险等级中文前缀，直接拼进 description
_RISK_TAG = {"safe": "safe", "write": "write", "danger": "danger"}


def _build_signature(parameters: dict) -> inspect.Signature:
    """由工具的 JSON Schema 构造一个仅含 keyword-only 参数的签名。

    必填项无默认值（FastMCP 会放进 required）；可选项默认 None。
    全部 keyword-only，避免包装函数被位置参数搞乱。
    """
    props = (parameters or {}).get("properties", {}) or {}
    required = set((parameters or {}).get("required", []) or [])
    params: list[inspect.Parameter] = []
    for name, spec in props.items():
        ann = _TYPE_MAP.get(str(spec.get("type", "string")), str)
        if name in required:
            params.append(
                inspect.Parameter(name, inspect.Parameter.KEYWORD_ONLY, annotation=ann)
            )
        else:
            # 可选项：注解标成 Optional，默认 None
            params.append(
                inspect.Parameter(
                    name, inspect.Parameter.KEYWORD_ONLY, annotation=ann | None, default=None
                )
            )
    return inspect.Signature(parameters=params, return_annotation=dict)


def _register_runtime_tools() -> None:
    """注册依赖运行期数据目录的工具（档案记忆 / 技能），使 stdio MCP 模式与外层一致。

    这些工具由 main.py 的 lifespan 在服务模式注册；独立 stdio 模式（不跑 FastAPI
    lifespan）若不补注册，桌面端会少看到 remember_fact / read_skill 两个工具。
    注册幂等（registry 按名字覆盖），重复调用无害；失败只影响对应工具。
    """
    try:
        from .paths import home_dir

        data = home_dir()
        try:
            from .profile_memory import ProfileMemory

            ProfileMemory(data / "memory").register_tools()
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception:  # noqa: BLE001
            pass
        try:
            from .skills import SkillRegistry

            SkillRegistry(data / "skills").register_tool()
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception:  # noqa: BLE001
            pass
    # aqg: top-level boundary —— 失败仅降级本能力
    except Exception:  # noqa: BLE001
        pass


def build_server(name: str = "pocket-agent"):
    """构建并注册完所有工具的 FastMCP server（不启动传输，便于测试 list_tools）。"""
    _register_runtime_tools()
    mcp = FastMCP(name)

    for tool in all_tools():
        risk = _RISK_TAG.get(tool.risk, "safe")
        # description 带风险前缀，桌面端模型据此谨慎对待危险工具
        desc = f"[{risk}] {tool.description or tool.name}"

        # 用默认参数 _tool=tool 捕获当前循环变量，避免闭包 Late Binding 坑
        async def _handler(_tool=tool, **kwargs):
            # 剔掉 None 的可选参数，保持与 Web 端 call_tool 入参一致
            args = {k: v for k, v in kwargs.items() if v is not None}
            return await call_tool(_tool.name, args)

        # 动态签名 + 注解：FastMCP 读 __signature__ 生成 inputSchema
        # 给函数对象动态挂 __signature__ 是 FastMCP 官方用法，mypy 不认函数属性赋值
        _handler.__signature__ = _build_signature(tool.parameters)  # type: ignore[attr-defined]  # 动态函数属性，运行时合法
        _handler.__annotations__ = {"return": dict}
        mcp.add_tool(_handler, name=tool.name, description=desc)

    return mcp


def main() -> None:
    """stdio 入口：桌面端 MCP 客户端通过子进程 stdin/stdout 与本服务通信。"""
    mcp = build_server()
    # run() 默认 transport='stdio'，阻塞运行直到 stdin 关闭
    mcp.run()


if __name__ == "__main__":
    main()
