"""环境自检：把「为什么它不工作」变成可执行的答案。

动机（真实痛点）：这个 Agent 要跑在极不确定的环境里——Termux 的 termux-api 没装、
存储空间满、数据目录只读、Python 版本太老、LLM 端点不可达。此前这些问题的表现
都是同一种：用户发一句话，界面停在「正在思考…」或弹一句泛泛的报错，用户不知道
下一步该做什么。

这里做的是**只读探测 + 可执行建议**：每项给出 status(ok/warn/fail)、事实描述、
以及一句用户能照做的修复动作。不做自动修改——自愈动作（删文件、改权限、装包）
必须由用户确认，静默改动比报错更危险。

另含**能力矩阵**（capability_matrix）：不只回答"哪坏了"，还回答"这台设备上
到底哪些能力存在、哪些不存在、不存在时该怎么办"。理由：有些能力不是配置问题，
而是环境本身给不了（模拟器没有真短信、PC 没有 termux-api、没外网就没有实时信息）。
用户应该在**使用之前**就知道边界，而不是发一句话才发现工具永远返回空。
"""
from __future__ import annotations

import os
import platform
import shutil
import socket
import subprocess
import sys
from pathlib import Path

MIN_PY = (3, 11)


def _probe_python() -> dict:
    v = sys.version_info
    ok = (v.major, v.minor) >= MIN_PY
    return {
        "id": "python",
        "title": "Python 版本",
        "status": "ok" if ok else "fail",
        "detail": f"{platform.python_version()}（{platform.machine()}）",
        "fix": "" if ok else f"需要 Python ≥ {MIN_PY[0]}.{MIN_PY[1]}；Termux 上执行 pkg upgrade python",
    }


def _probe_disk(path: Path) -> dict:
    try:
        path.mkdir(parents=True, exist_ok=True)
        usage = shutil.disk_usage(str(path))
        free_mb = usage.free // (1024 * 1024)
        pct = int(usage.used / usage.total * 100) if usage.total else 0
        if free_mb < 50:
            status, fix = "fail", "剩余空间不足 50MB：清理旧会话（设置→导出后删除）或卸载不用的应用"
        elif free_mb < 200:
            status, fix = "warn", "空间偏紧，建议清理；低于 50MB 时服务将无法写入"
        else:
            status, fix = "ok", ""
        return {
            "id": "disk",
            "title": "存储空间",
            "status": status,
            "detail": f"剩余 {free_mb} MB（已用 {pct}%）",
            "fix": fix,
        }
    except Exception as e:  # noqa: BLE001
        return {"id": "disk", "title": "存储空间", "status": "fail",
                "detail": f"无法读取：{type(e).__name__}", "fix": "检查数据目录是否存在且可访问"}


def _probe_writable(path: Path) -> dict:
    probe = path / ".write_probe"
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        return {"id": "writable", "title": "数据目录可写", "status": "ok", "detail": str(path), "fix": ""}
    except Exception as e:  # noqa: BLE001
        return {
            "id": "writable",
            "title": "数据目录可写",
            "status": "fail",
            "detail": f"{path} 不可写（{type(e).__name__}）",
            "fix": "在 Termux 执行 chmod 700 ~/.agent/termux-agent，或用 --home 指到可写目录",
        }


def _probe_termux() -> dict:
    """termux-api 是否可用：这决定了 25 个手机工具能不能用。"""
    if os.name != "posix" or "com.termux" not in os.environ.get("PREFIX", "") + os.environ.get("HOME", ""):
        # 非 Termux（PC / Docker）不算故障：手机工具本就用不了，属于预期降级
        has_bin = shutil.which("termux-battery-status") is not None
        return {
            "id": "termux",
            "title": "手机能力（termux-api）",
            "status": "ok" if has_bin else "warn",
            "detail": "检测到 termux-api 命令" if has_bin else "当前不是 Termux 环境，手机工具不可用（其余功能正常）",
            "fix": "" if has_bin else "如需手机能力，请在 Termux 中 pkg install termux-api 并安装 Termux:API 应用",
        }
    ok = shutil.which("termux-battery-status") is not None
    return {
        "id": "termux",
        "title": "手机能力（termux-api）",
        "status": "ok" if ok else "fail",
        "detail": "termux-api 可用" if ok else "Termux 环境但找不到 termux-api",
        "fix": "" if ok else "在 Termux 执行 pkg install termux-api，并安装 Termux:API 配套 App",
    }


def _probe_port(host: str, port: int) -> dict:
    """端口状态：要么「正在被本服务监听」，要么「可绑定」，两者都算正常。

    修复（2026-10-29）：此前直接 bind 该端口，而本函数是在服务**已启动**后
    由 /api/diagnostics 调用的 —— 端口必然被自己占用，于是每次自检都报
    「端口被占用，请换端口」，用户一进设置就看到一条假故障。
    现在先尝试 connect：连得上说明是本服务在监听（正常）；连不上再试 bind，
    绑不上才是真被别的进程占用。
    """
    def _listening() -> bool:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return True
        except OSError:
            return False

    if _listening():
        return {
            "id": "port",
            "title": "监听端口",
            "status": "ok",
            "detail": f"{host}:{port} 正在监听（本服务运行中）",
            "fix": "",
        }

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, port))
        return {
            "id": "port",
            "title": "监听端口",
            "status": "ok",
            "detail": f"{host}:{port} 可用（当前未监听）",
            "fix": "",
        }
    except OSError as e:
        return {
            "id": "port",
            "title": "监听端口",
            "status": "fail",
            "detail": f"{host}:{port} 被其他进程占用（{e.strerror or e}）",
            "fix": f"换一个端口：python -m agentd.main --port {port + 1}",
        }
    finally:
        s.close()


def _probe_net(host: str = "www.bing.com", timeout: float = 3.0) -> dict:
    """外网可达性：联网搜索与云端 LLM 都依赖它。"""
    try:
        socket.create_connection((host, 443), timeout=timeout).close()
        return {"id": "net", "title": "外网连接", "status": "ok", "detail": f"{host}:443 可达", "fix": ""}
    except Exception as e:  # noqa: BLE001
        return {
            "id": "net",
            "title": "外网连接",
            "status": "warn",
            "detail": f"{host}:443 不可达（{type(e).__name__}）",
            "fix": "本地功能不受影响；联网搜索与云端模型需要外网，检查手机网络或代理设置",
        }


def _probe_llm(settings_mgr, mock: bool) -> dict:
    if mock:
        return {"id": "llm", "title": "模型配置", "status": "ok", "detail": "离线演示模式（mock）", "fix": ""}
    try:
        ready = bool(settings_mgr.llm_ready())
        missing = [] if ready else settings_mgr.llm_missing()
    except Exception as e:  # noqa: BLE001
        return {"id": "llm", "title": "模型配置", "status": "fail",
                "detail": f"读取配置失败：{type(e).__name__}", "fix": "检查 config.json 是否被改坏，或重置设置"}
    return {
        "id": "llm",
        "title": "模型配置",
        "status": "ok" if ready else "fail",
        "detail": "已配置" if ready else f"缺少{'、'.join(missing)}",
        "fix": "" if ready else "打开「设置 → 模型」填写 Base URL / 模型名 / API Key",
    }


def _probe_deps() -> dict:
    """可选依赖缺失不算错，但要让用户知道哪些能力被降级了。"""
    optional = {
        "mem0ai": "长期记忆（Mem0）",
        "sherpa_onnx": "离线语音",
        "telegram": "Telegram 远程控制",
    }
    missing = []
    for mod, label in optional.items():
        try:
            __import__(mod)
        except Exception:  # noqa: BLE001 —— 任何导入失败都视为未安装
            missing.append(label)
    if not missing:
        return {"id": "deps", "title": "可选能力", "status": "ok", "detail": "全部可用", "fix": ""}
    return {
        "id": "deps",
        "title": "可选能力",
        "status": "warn",
        "detail": "未启用：" + "、".join(missing),
        "fix": "按需安装：pip install mem0ai / sherpa-onnx / python-telegram-bot；不装不影响主功能",
    }


def run_diagnostics(settings_mgr, mock: bool, host: str, port: int, data_dir: Path) -> dict:
    items = [
        _probe_python(),
        _probe_writable(data_dir),
        _probe_disk(data_dir),
        _probe_port(host, port),
        _probe_net(),
        _probe_termux(),
        _probe_llm(settings_mgr, mock),
        _probe_deps(),
    ]
    fails = [i for i in items if i["status"] == "fail"]
    warns = [i for i in items if i["status"] == "warn"]
    return {
        "ok": not fails,
        "summary": "一切正常" if not fails and not warns
        else (f"{len(fails)} 项需要处理" if fails else f"{len(warns)} 项提示"),
        "items": items,
        # 能力矩阵：不只报"哪坏了"，还说明"这台设备能做什么、不能做什么、怎么办"
        "capabilities": capability_matrix(),
    }


# ---------------------------------------------------------------------------
# 能力矩阵：这台设备到底能做什么
# ---------------------------------------------------------------------------

def _detect_platform() -> dict:
    """识别运行形态。不同形态的"能力边界"完全不同，必须先认清自己在哪种。"""
    is_termux = "com.termux" in os.environ.get("PREFIX", "") or "com.termux" in os.environ.get("HOME", "")
    abi = platform.machine().lower()
    in_container = os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv")
    if is_termux:
        kind = "termux"
    elif in_container:
        kind = "container"
    elif os.name == "nt":
        kind = "windows"
    else:
        kind = "linux"
    return {
        "kind": kind,
        "abi": abi,
        "is_termux": is_termux,
        "is_android": is_termux or "android" in platform.platform().lower(),
        "is_emulator": _looks_like_emulator() if is_termux else False,
    }


def _looks_like_emulator() -> bool:
    """判断是不是 Android 模拟器。

    实测（Termux / Android 15 x86_64 模拟器）：
    - `platform.uname().release` 返回的是 **Android 版本号 "15"**，不是内核串 ——
      所以按内核名判断会漏判（第一版就这么错了，真机上跑出来 is_emulator=false）。
    - 可靠信号是 Android 属性：`ro.kernel.qemu=1`、`ro.hardware=ranchu|goldfish`。

    只读属性，不调外部命令的话拿不到，所以用 subprocess 读 getprop；
    任何失败都当作"非模拟器"，避免把真机误判成模拟器（那会让手机能力被错误隐藏）。
    """
    # 先看属性（最可靠）
    for prop, expect in (("ro.kernel.qemu", "1"), ("ro.hardware", ("ranchu", "goldfish"))):
        try:
            out = subprocess.run(
                ["getprop", prop], capture_output=True, text=True, timeout=3,
            ).stdout.strip().lower()
            if isinstance(expect, tuple):
                if any(e in out for e in expect):
                    return True
            elif out == expect:
                return True
        except Exception:  # noqa: BLE001 —— 没有 getprop（非 Android）就继续
            pass
    # 再退回内核串（某些环境 getprop 不可用但 uname 有线索）
    try:
        uname = " ".join(platform.uname()).lower()
        if "goldfish" in uname or "ranchu" in uname:
            return True
    except Exception:  # noqa: BLE001
        pass
    return False


def capability_matrix() -> dict:
    """返回 {"platform":..., "available":[...], "unavailable":[...], "degraded":[...]}。

    每一项：{id, name, status, reason, workaround}
      status: available / unavailable / degraded / conditional
      workaround 是"没有它时怎么办"，不能只写"不支持"。
    """
    env = _detect_platform()
    termux_api = shutil.which("termux-battery-status") is not None
    has_net = _probe_net()["status"] == "ok"

    caps: list[dict] = []

    def add(cid, name, status, reason="", workaround=""):
        caps.append({"id": cid, "name": name, "status": status,
                     "reason": reason, "workaround": workaround})

    # --- 总是可用的核心能力 ---
    add("chat", "对话与工具编排", "available")
    add("files", "读写本机文件", "available")
    add("memory", "会话记忆 / 长短期记忆", "available")
    add("todo", "任务清单", "available")
    add("scheduler", "定时任务", "available",
        reason="若设备无 tzdata，按 UTC 解释（已在启动时降级）"
        if env["is_android"] else "")

    # --- 依赖 termux-api 的手机能力 ---
    phone_tools = [
        ("sms", "收发短信"), ("phone", "拨打电话"), ("location", "定位"),
        ("sensors", "传感器读数"), ("battery", "电池状态"), ("clipboard", "剪贴板"),
        ("notification", "系统通知"), ("camera", "拍照"),
    ]
    # 判定优先级：模拟器 > 非 Termux > 未装 termux-api > 可用。
    # 必须先判模拟器：模拟器上即使装了 termux-api，硬件能力也不存在，
    # 报"未安装 termux-api"会误导用户去装一个装了也没用的包。
    if env["is_emulator"]:
        for cid, name in phone_tools:
            add(cid, name, "unavailable",
                reason="当前是 Android 模拟器：没有真实 SIM / GPS / 传感器硬件，"
                       "即使装了 termux-api 也只会拿到空数据或错误",
                workaround="手机硬件能力只能在真实手机上验证；模拟器仅用于验证程序逻辑与安装链路")
    elif not env["is_termux"]:
        for cid, name in phone_tools:
            add(cid, name, "unavailable",
                reason="当前不是 Termux 环境，没有 termux-api（PC / Docker 上属预期）",
                workaround="这些能力只在安卓手机的 Termux 里存在；PC 上请用文件与 shell 工具")
    elif not termux_api:
        for cid, name in phone_tools:
            add(cid, name, "unavailable",
                reason="Termux 环境但未安装 termux-api",
                workaround="pkg install termux-api，并安装应用商店里的 Termux:API 配套 App")
    else:
        for cid, name in phone_tools:
            add(cid, name, "available")

    # --- 依赖外网 ---
    add("web_search", "联网搜索", "available" if has_net else "unavailable",
        # 隐私边界必须写明：这是「数据不出本机」的产品，联网搜索会把查询词发给
        # 第三方搜索引擎。可用不等于默认开启——输入框的开关默认关闭。
        reason=("可用，但**查询词会发送到外部搜索引擎**；默认关闭，需在输入框手动开启"
                if has_net else "外网不可达"),
        workaround=("" if has_net else
                    "可离线使用本机知识库（kb_add / kb_search）与文件工具"))
    add("llm_cloud", "云端模型（OpenAI 兼容端点）",
        "conditional" if has_net else "unavailable",
        reason="需要外网 + 已配置 Base URL/模型/Key" if has_net else "外网不可达，无法调用云端模型",
        workaround="配置局域网内自建模型端点（如 Ollama / vLLM）；mock 模式可离线演示")

    # --- 可选能力 ---
    for mod, cid, name in [
        ("mem0ai", "mem0", "Mem0 长期记忆"),
        ("sherpa_onnx", "voice", "离线语音合成"),
        ("telegram", "telegram", "Telegram 远程控制"),
    ]:
        try:
            __import__(mod)
            add(cid, name, "available")
        except Exception:  # noqa: BLE001
            add(cid, name, "unavailable", reason="未安装可选依赖",
                workaround=f"需要时安装：pip install {mod}；不装不影响主功能")

    # --- 平台特有的限制 ---
    if env["is_android"]:
        add("aarch64_native", "aarch64 原生编译产物", "conditional",
            reason="当前进程架构是 " + env["abi"] + "；真机多为 arm64，二进制不通用",
            workaround="依赖是纯 Python 或在本机现场编译，不跨架构复用")
        add("power_mgmt", "锁屏后台常驻", "conditional",
            reason="Android 会因电池优化杀进程；需唤醒锁 + 关闭电池优化",
            workaround="termux/start.sh 已申请 wake-lock；仍建议在系统设置里给 Termux 关闭电池优化")

    available = [c for c in caps if c["status"] == "available"]
    unavailable = [c for c in caps if c["status"] == "unavailable"]
    degraded = [c for c in caps if c["status"] in ("degraded", "conditional")]

    return {
        "platform": env,
        "available": available,
        "unavailable": unavailable,
        "degraded": degraded,
        "summary": (
            f"{len(available)} 项可用"
            + (f"，{len(degraded)} 项有条件可用" if degraded else "")
            + (f"，{len(unavailable)} 项不可用" if unavailable else "")
        ),
    }
