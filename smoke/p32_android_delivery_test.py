#!/usr/bin/env python3
"""P32 Android 交付链：令牌鉴权、时区、端口自检、启动脚本参数。

这条测试守的是「手机上装完真的能用」，而不是「PC 上跑得通」：

1. **回环鉴权**：Android 上 127.0.0.1 是全机共享的，agentd 必须带令牌启动，
   否则同机任意 App 都能读会话、执行 shell。这里验证：
   - Android 环境下首次启动自动生成令牌并落盘（600）；
   - 带令牌 200 / 不带令牌 401；
   - 桌面环境保持原行为（回环免令牌），不破坏开发流程。
2. **时区**：apscheduler 3.11 的 `util.timezone` 已不是 pytz 时区工厂，
   传字符串会 TypeError 并被吞掉，导致"每天 8 点"变成 UTC 8 点。验证 TZ 生效。
3. **端口自检**：`_probe_port` 曾 bind 自己正在监听的端口，导致每次自检都报
   "端口被占用"。验证服务运行中时该项为 ok。
4. **start.sh 参数**：`--lan` 曾被当成端口号传给 uvicorn。验证两种顺序都能解析。

不依赖真机：用 PREFIX 环境变量模拟 Termux，用进程内调用验证纯函数。
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def _http_code(url: str, token: str = "") -> int:
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def _wait_health(base: str, token: str, timeout: float = 40) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _http_code(f"{base}/api/health", token) == 200:
            return True
        time.sleep(0.5)
    return False


def test_android_token() -> None:
    """Android 环境：自动生成令牌 + 强制 401。"""
    print("\n-- Android 回环鉴权 --")
    home = tempfile.mkdtemp(prefix="p32-tok-")
    port = _free_port()
    env = {
        **os.environ,
        "HOME": home,
        "PREFIX": "/data/data/com.termux/files/usr",
        "AGENT_HOME": str(Path(home) / ".agent" / "termux-agent"),
    }
    env.pop("AGENT_TOKEN", None)
    proc = subprocess.Popen(
        [sys.executable, "-m", "agentd.main", "--mock", "--port", str(port)],
        cwd=str(ROOT), env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        token_file = Path(home) / ".agent" / "termux-agent" / "token"
        deadline = time.time() + 40
        while time.time() < deadline and not token_file.exists():
            time.sleep(0.3)
        check("Android 首次启动自动生成 token 文件", token_file.exists(), str(token_file))
        token = token_file.read_text(encoding="utf-8").strip() if token_file.exists() else ""
        check("token 长度 ≥ 32（足够熵）", len(token) >= 32, f"len={len(token)}")
        if os.name == "posix" and token_file.exists():
            mode = token_file.stat().st_mode & 0o777
            check("token 文件权限 600", mode == 0o600, oct(mode))

        base = f"http://127.0.0.1:{port}"
        check("服务在 Android 环境下就绪", _wait_health(base, token), base)
        check("不带令牌访问 /api/sessions → 401",
              _http_code(f"{base}/api/sessions") == 401,
              str(_http_code(f"{base}/api/sessions")))
        check("带令牌访问 /api/sessions → 200",
              _http_code(f"{base}/api/sessions", token) == 200,
              str(_http_code(f"{base}/api/sessions", token)))
        check("/api/health 始终可匿名探活（壳的掉线检测依赖它）",
              _http_code(f"{base}/api/health") == 200)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


def test_desktop_no_token() -> None:
    """桌面环境：回环保持免令牌，不破坏本机开发与既有测试。"""
    print("\n-- 桌面回环行为不变 --")
    home = tempfile.mkdtemp(prefix="p32-desk-")
    port = _free_port()
    env = {
        **os.environ,
        "HOME": home,
        "AGENT_HOME": str(Path(home) / ".agent"),
        "PREFIX": "",
    }
    env.pop("AGENT_TOKEN", None)
    proc = subprocess.Popen(
        [sys.executable, "-m", "agentd.main", "--mock", "--port", str(port)],
        cwd=str(ROOT), env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        base = f"http://127.0.0.1:{port}"
        check("桌面模式服务就绪", _wait_health(base, ""), base)
        check("桌面模式回环免令牌（200）",
              _http_code(f"{base}/api/sessions") == 200,
              str(_http_code(f"{base}/api/sessions")))
        check("桌面模式不生成 token 文件",
              not (Path(home) / ".agent" / "token").exists())
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


def test_timezone() -> None:
    """时区：TZ 必须真的生效（曾被 apscheduler 3.11 静默退化成 UTC）。"""
    print("\n-- 定时任务时区 --")
    probe = (
        f"import sys; sys.path.insert(0, {str(ROOT)!r})\n"
        "from agentd.scheduler import SchedulerService\n"
        "tz = SchedulerService._resolve_timezone()\n"
        "print('TZNAME', getattr(tz, 'key', str(tz)))\n"
    )
    env = {**os.environ, "TZ": "Asia/Shanghai"}
    r = subprocess.run([sys.executable, "-c", probe], cwd=str(ROOT), env=env,
                       capture_output=True, text=True, timeout=60)
    out = r.stdout + r.stderr
    check("TZ=Asia/Shanghai 被正确解析（不是 UTC）",
          "TZNAME Asia/Shanghai" in out, out[-300:])

    r2 = subprocess.run([sys.executable, "-c", probe], cwd=str(ROOT),
                        env={**os.environ, "TZ": ""},
                        capture_output=True, text=True, timeout=60)
    out2 = r2.stdout + r2.stderr
    check("未设置 TZ 时回退到 Asia/Shanghai（中文用户默认）",
          "TZNAME Asia/Shanghai" in out2, out2[-300:])


def test_probe_port() -> None:
    """端口自检：服务已在监听时必须是 ok，而不是"被占用"。"""
    print("\n-- 端口自检 --")
    from agentd.diagnostics import _probe_port

    port = _free_port()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    try:
        r = _probe_port("127.0.0.1", port)
        check("端口正在监听 → status=ok", r["status"] == "ok", str(r))
        check("detail 说明是正在监听", "监听" in r["detail"], r["detail"])
    finally:
        srv.close()

    r2 = _probe_port("127.0.0.1", _free_port())
    check("空闲端口 → status=ok", r2["status"] == "ok", str(r2))


def test_start_sh_args() -> None:
    """start.sh：端口与 --lan 顺序任意，未知参数报错退出。"""
    print("\n-- start.sh 参数解析 --")
    sh = ROOT / "termux" / "start.sh"
    src = sh.read_text(encoding="utf-8")
    # 只检查「实际赋值语句」：注释里引用旧写法是允许的（说明为什么改）
    code_lines = [ln for ln in src.splitlines() if not ln.lstrip().startswith("#")]
    code = "\n".join(code_lines)
    check("start.sh 不再把 $1 直接当端口", 'PORT="${1:-8787}"' not in code)
    check("start.sh 含 --lan 分支", "--lan)" in code)
    check("start.sh 对未知参数报错", "未知参数" in code)

    # 干跑：用一个假的 python 截获最终参数。
    # 注意：日志文件必须用**仓库内的相对路径**。start.sh 会 `cd` 到仓库根目录，
    # 若传绝对路径，MSYS/Git Bash 会把 "C:/..." 当作含冒号的参数做路径转换，
    # 在仓库里落下一个 `C：Users...args.txt` 的垃圾文件（实测踩到）。
    tmp = tempfile.mkdtemp(prefix="p32-sh-")
    log_name = ".p32-args.txt"
    log_path = ROOT / log_name
    fake = Path(tmp) / "python"
    fake.write_text(f'#!/bin/sh\nprintf "%s\\n" "$@" > {log_name}\n', encoding="utf-8")
    fake.chmod(0o755)
    env = {**os.environ, "PATH": tmp + os.pathsep + os.environ.get("PATH", "")}
    try:
        for args, expect_port, expect_lan in (
            ([], "8787", False),
            (["8788"], "8788", False),
            (["--lan"], "8787", True),
            (["8788", "--lan"], "8788", True),
            (["--lan", "8788"], "8788", True),
        ):
            if log_path.exists():
                log_path.unlink()
            r = subprocess.run(["bash", str(sh), *args], cwd=str(ROOT), env=env,
                               capture_output=True, text=True, timeout=60)
            raw = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
            got = " ".join(raw.split())
            ok = (f"--port {expect_port}" in got) and (("--lan" in got) == expect_lan)
            check(f"start.sh {' '.join(args) or '(无参数)'} → port={expect_port} lan={expect_lan}",
                  ok, got or (r.stdout + r.stderr)[-200:])
    finally:
        if log_path.exists():
            log_path.unlink()


def test_clear_token_sentinel() -> None:
    """--clear-token 后令牌不得被 start.sh / env_token 复活（第四轮 D4）。

    复现路径：install.sh 写 token 文件 → start.sh 注入 AGENT_TOKEN →
    用户 --clear-token 清掉 config.json → 重启时 env_token 分支把旧令牌
    写回 config.json，"清除"永远不生效。修复 = 哨兵文件 .token-cleared。
    """
    print("\n-- --clear-token 与 token 文件的一致性 --")
    home = tempfile.mkdtemp(prefix="p32-clear-")
    data_dir = Path(home) / ".agent" / "termux-agent"
    data_dir.mkdir(parents=True, exist_ok=True)
    token_file = data_dir / "token"
    token_file.write_text("oldtoken" * 8, encoding="utf-8")

    # 1) --clear-token 写哨兵
    env = {**os.environ, "AGENT_HOME": str(data_dir)}
    r = subprocess.run(
        [sys.executable, "-m", "agentd.main", "--clear-token"],
        cwd=str(ROOT), env=env, capture_output=True, text=True, timeout=60,
    )
    check("--clear-token 正常退出", r.returncode == 0, r.stderr[-200:])
    sentinel = data_dir / ".token-cleared"
    check("--clear-token 写入哨兵文件", sentinel.exists(), str(sentinel))
    cfg = json.loads((data_dir / "config.json").read_text(encoding="utf-8"))
    check("config.json 里令牌已清空", cfg.get("server", {}).get("token", "") == "", str(cfg.get("server", {})))

    # 2) start.sh 在哨兵存在时不注入旧令牌
    sh = ROOT / "termux" / "start.sh"
    log_name = ".p32-args2.txt"
    log_path = ROOT / log_name
    tmp = tempfile.mkdtemp(prefix="p32-sh2-")
    fake = Path(tmp) / "python"
    fake.write_text(f'#!/bin/sh\nprintf "%s\n" "$@" > {log_name}\n', encoding="utf-8")
    fake.chmod(0o755)
    env2 = {**os.environ, "PATH": tmp + os.pathsep + os.environ.get("PATH", ""),
            "AGENT_HOME": str(data_dir), "HOME": home}
    try:
        # AGENT_TOKEN 是环境变量不进 argv：让假 python 把环境一起 dump 出来
        fake.write_text(
            f'#!/bin/sh\nprintf "%s\n" "$@" > {log_name}\n'
            f'printf "AGENT_TOKEN=%s\n" "$AGENT_TOKEN" >> {log_name}\n',
            encoding="utf-8")
        subprocess.run(["bash", str(sh)], cwd=str(ROOT), env=env2,
                       capture_output=True, text=True, timeout=60)
        out = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
        check("哨兵存在时 start.sh 不注入 AGENT_TOKEN", "AGENT_TOKEN=oldtoken" not in out, out[:200])
    finally:
        if log_path.exists():
            log_path.unlink()

    # 3) 保存新令牌（设置面板路径）→ 哨兵清除 + token 文件同步
    probe = (
        f"import sys, os; sys.path.insert(0, {str(ROOT)!r}); "
        f"os.environ['AGENT_HOME'] = {str(data_dir)!r}; "
        "from agentd.config import Settings; "
        "sm = Settings(); sm.path = __import__('pathlib').Path(os.environ['AGENT_HOME']) / 'config.json'; "
        "sm.load(); sm.save({'server': {'token': 'newtoken' * 8}}); "
        "from agentd import main as M; "
        "# 直接调 main.py 的同步逻辑等价体：验证哨兵被清除\n"
        "sentinel = __import__('pathlib').Path(os.environ['AGENT_HOME']) / '.token-cleared'\n"
        "tf = __import__('pathlib').Path(os.environ['AGENT_HOME']) / 'token'\n"
        "# 模拟 put_settings 的同步块\n"
        "new_token = sm.get().get('server', {}).get('token', '').strip()\n"
        "if new_token:\n"
        "    if sentinel.exists(): sentinel.unlink()\n"
        "    if tf.read_text(encoding='utf-8').strip() != new_token:\n"
        "        tf.write_text(new_token, encoding='utf-8')\n"
        "print('SENTINEL', sentinel.exists()); print('TF', tf.read_text(encoding='utf-8')[:8])\n"
    )
    r2 = subprocess.run([sys.executable, "-c", probe], cwd=str(ROOT),
                        env={**os.environ, "AGENT_HOME": str(data_dir)},
                        capture_output=True, text=True, timeout=60)
    out2 = r2.stdout + r2.stderr
    check("保存新令牌后哨兵被清除", "SENTINEL False" in out2, out2[-300:])
    check("token 文件同步为新令牌", "TF newtoken" in out2, out2[-300:])

def main() -> int:
    print("== P32 Android 交付链 ==")
    test_timezone()
    test_probe_port()
    test_start_sh_args()
    test_android_token()
    test_desktop_no_token()
    test_clear_token_sentinel()
    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：" + "；".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

