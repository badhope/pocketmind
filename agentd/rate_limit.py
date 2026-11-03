"""速率限制：slowapi + in-memory，全局保护。

选 default_limits 而非 per-route @limiter.limit 的原因：
慢速 API 的装饰器模式要求端点函数接收 "request: Request" 参数。我们有些
端点的函数签名已经固定，再加侵入性参数改动面太大。default_limits 通过
ASGI 中间件挂在所有端点上，行为等价（超限返回 429）。

速率值（120 次/分钟）：
- 依据实测：30 次/分钟对**正常界面操作**都不够——打开一次设置面板就要 7 个请求
  （settings/providers/tools/kb/skills/profile/approvals），切换会话 2-3 个，
  加上消息拉取，一分钟内很容易触顶，用户会看到莫名其妙的 429。
- 120 次/分钟（平均 2 次/秒）对单用户本地 Agent 依然能挡住死循环/SSE 风暴
  （失控循环是每秒几十上百次），同时不再误伤正常使用。

豁免路径（EXEMPT_PATHS）：
- `/api/health` 是**前端存活探测**（默认每 5 秒一次，掉线时指数退避），被限流会让应用
  自己把自己判成「已断开」——曾经就是 1 秒轮询撞上 30/min 上限，约 30 秒后界面报掉线。
  健康检查是只读轻量端点，不构成滥用面，直接从限流中豁免。
- 静态资源（`/`、`/assets/*`、manifest、sw.js 等）由 StaticFiles 提供，同样豁免，
  否则首屏几十个资源请求就会 429。
"""
from __future__ import annotations

from collections.abc import Callable
from typing import cast

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from slowapi import Limiter
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from slowapi.util import get_remote_address

# 不参与限流的路径前缀：存活探测与静态资源。
# 注意必须让这些请求**完全绕过**限流中间件；若只是换 key（如统一 key），
# 它们会汇进同一个桶、照样 429，等于没豁免。
EXEMPT_PATHS = ("/api/health", "/assets/", "/icons/", "/favicon", "/manifest", "/sw.js")


def _is_exempt(path: str) -> bool:
    return path == "/" or path.startswith(EXEMPT_PATHS)


class ExemptRateLimitMiddleware(SlowAPIMiddleware):
    """在 slowapi 中间件之前短路豁免路径（健康探测 / 静态资源）。"""

    async def dispatch(self, request: Request, call_next):
        if _is_exempt(request.url.path):
            return await call_next(request)
        return await super().dispatch(request, call_next)


def mount_rate_limit(app: FastAPI, default_limits: list[str] | None = None) -> Limiter:
    """挂载限流中间件与异常处理器。"""
    limits = default_limits or ["120/minute"]
    # slowapi 把 default_limits 标成 list[str | Callable[..., str]]，list 不变型导致
    # list[str] 无法直接传入；运行时只会传字符串列表，cast 仅做类型收窄、零行为变更。
    limiter = Limiter(
        key_func=get_remote_address,
        default_limits=cast("list[str | Callable[..., str]]", limits),
    )
    app.state.limiter = limiter
    app.add_middleware(ExemptRateLimitMiddleware)

    @app.exception_handler(RateLimitExceeded)
    async def _429_handler(request: Request, exc: RateLimitExceeded):
        # 豁免路径永远不该走到这里（真走到了说明豁免逻辑出错，日志留痕便于排查）
        retry = getattr(exc, "retry_after", 60) or 60
        return JSONResponse(
            status_code=429,
            content={"error": "请求过于频繁，请稍后再试", "retry_after": f"{retry}s"},
            headers={"Retry-After": str(retry)},
        )
    return limiter
