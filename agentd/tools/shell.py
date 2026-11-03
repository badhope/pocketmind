"""Shell 工具：在 Termux 里执行命令。

任意 shell 属于最高危险级（danger）：
- approve 模式下必须用户在界面确认后才能执行；
- 输出做长度截断，防止超长输出撑爆上下文。
"""
from __future__ import annotations

import asyncio
import os
import shutil
import signal

from . import Tool, register

MAX_OUTPUT = 30000

SCHEMA = {
    "type": "object",
    "properties": {
        "command": {
            "type": "string",
            "description": "要执行的完整 shell 命令，例如 ls -la ~/storage",
        },
        "timeout": {
            "type": "integer",
            "description": "超时秒数，默认 60，最大 300",
            "minimum": 1,
            "maximum": 300,
        },
    },
    "required": ["command"],
}


def _kill_group(proc: asyncio.subprocess.Process) -> None:
    """杀掉整个进程组（bash -lc 及其孙进程），避免后台命令残留。

    子进程以 start_new_session=True 启动后会拿到独立 pgid；这里按 pgid 发 SIGKILL，
    比只 kill bash 本身更彻底（`sleep 100 &` 这类孙进程也会被回收）。
    """
    if proc.returncode is not None:
        return
    # os.getpgid / os.killpg / signal.SIGKILL 都是 POSIX 专有。Windows 上它们
    # 根本不存在，访问会抛 AttributeError —— 而旧实现只捕 (ProcessLookupError,
    # OSError)，AttributeError 会直接冒出去把 run_shell 打断。
    # 先在能力层面判断，再按平台选回收方式。
    if not (hasattr(os, "getpgid") and hasattr(os, "killpg") and hasattr(signal, "SIGKILL")):
        try:
            proc.kill()
        except ProcessLookupError:
            pass
        return
    try:
        pgid = os.getpgid(proc.pid)
        # os.killpg 是 POSIX 专有 API，Windows 类型存根上不存在；
        # 运行时已被上方 hasattr(os, "killpg") 能力判断保护，不会在 Windows 走到这里。
        os.killpg(pgid, signal.SIGKILL)  # type: ignore[attr-defined]  # Unix-only 平台属性
    except (ProcessLookupError, OSError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


def _rlimit_preexec(max_as_mb: int = 512, max_cpu_s: int = 60, max_nproc: int = 64) -> None:
    """子进程 exec 前设置 resource limit（仅 Unix/Linux/Termux，Windows 无效）。

    - RLIMIT_AS：地址空间（内存）上限，超量 OOM 杀而非整体 Termux 崩溃
    - RLIMIT_CPU：CPU 秒数上限（已含子进程）
    - RLIMIT_NPROC：子进程数上限，防 fork bomb
    """
    try:
        # resource 模块的 getrlimit/setrlimit/RLIMIT_* 均为 Unix-only API，
        # Windows 类型存根上不存在；运行时已在 try 内，ImportError/OSError 时静默跳过。
        import resource
        soft_as, hard_as = resource.getrlimit(resource.RLIMIT_AS)  # type: ignore[attr-defined]  # Unix-only 平台属性
        target_as = min(hard_as, max_as_mb * 1024 * 1024) if hard_as != resource.RLIM_INFINITY else max_as_mb * 1024 * 1024  # type: ignore[attr-defined]  # Unix-only 平台属性
        resource.setrlimit(resource.RLIMIT_AS, (target_as, hard_as))  # type: ignore[attr-defined]  # Unix-only 平台属性
        soft_cpu, hard_cpu = resource.getrlimit(resource.RLIMIT_CPU)  # type: ignore[attr-defined]  # Unix-only 平台属性
        target_cpu = min(hard_cpu, max_cpu_s) if hard_cpu != resource.RLIM_INFINITY else max_cpu_s  # type: ignore[attr-defined]  # Unix-only 平台属性
        resource.setrlimit(resource.RLIMIT_CPU, (target_cpu, hard_cpu))  # type: ignore[attr-defined]  # Unix-only 平台属性
        soft_nproc, hard_nproc = resource.getrlimit(resource.RLIMIT_NPROC)  # type: ignore[attr-defined]  # Unix-only 平台属性
        target_nproc = min(hard_nproc, max_nproc) if hard_nproc != resource.RLIM_INFINITY else max_nproc  # type: ignore[attr-defined]  # Unix-only 平台属性
        resource.setrlimit(resource.RLIMIT_NPROC, (target_nproc, hard_nproc))  # type: ignore[attr-defined]  # Unix-only 平台属性
    except (ImportError, ValueError, OSError):
        pass  # Windows / 权限不足时静默跳过


async def run_shell(command: str, timeout: int = 60) -> dict:
    if not isinstance(command, str) or not command.strip():
        return {"error": "命令不能为空"}
    shell = shutil.which("bash") or "/bin/sh"
    timeout = max(1, min(int(timeout or 60), 300))
    # start_new_session：独立进程组，便于超时/取消时整组回收（POSIX/Windows 都接受）
    # preexec_fn：在子进程 exec 前设置 resource limit（内存/CPU/进程数），防越权。
    #   它是 POSIX 专属参数——Windows 上传入会直接 ValueError（本工具在 Windows 上必然失败，
    #   实测报 "preexec_fn is not supported on Windows platforms"），因此按平台条件传入。
    # bash -c 而非 -lc：避免 login shell 加载 profile/rc，减少注入面
    spawn_kwargs: dict = {
        "stdout": asyncio.subprocess.PIPE,
        "stderr": asyncio.subprocess.PIPE,
        "start_new_session": True,
    }
    if os.name == "posix":
        spawn_kwargs["preexec_fn"] = _rlimit_preexec
    proc = await asyncio.create_subprocess_exec(shell, "-c", command, **spawn_kwargs)
    timed_out = False
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except TimeoutError:
        timed_out = True
        _kill_group(proc)
        out, err = await proc.communicate()
    except asyncio.CancelledError:
        # 外部取消（用户停止 / SSE 断开 / 删会话）：整组回收，不留后台进程
        _kill_group(proc)
        raise
    stdout = out.decode("utf-8", errors="replace").strip()
    stderr = err.decode("utf-8", errors="replace").strip()
    result = {
        "ok": proc.returncode == 0,
        "exit_code": proc.returncode,
        "stdout": stdout[:MAX_OUTPUT],
        "stderr": stderr[:MAX_OUTPUT],
        "timed_out": timed_out,
    }
    if len(stdout) > MAX_OUTPUT:
        result["truncated"] = True
    # 非零退出 / 超时必须带 error 字段：agent 的状态判定只看 result["error"] 或
    # "error_code"，旧实现只给 exit_code，于是失败命令在前端与落库状态里都是「完成」，
    # 定时任务与条件判断也会把失败当成功往下走。
    if timed_out:
        result["error"] = f"命令超时（{timeout}s）：{stderr[:200] or command[:120]}"
    elif proc.returncode != 0:
        result["error"] = f"命令以非零状态退出（exit {proc.returncode}）：{stderr[:200] or stdout[:120]}"
    return result


register(
    Tool(
        name="run_shell",
        description="在 Termux 终端执行一条 shell 命令并返回输出。用于查系统信息、管理文件、运行脚本等。",
        parameters=SCHEMA,
        risk="danger",
        handler=run_shell,
        summary="执行 shell 命令（可运行任意命令，请确认是你想要的）",
        group="system",
        timeout=300,
    )
)
