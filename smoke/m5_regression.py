#!/usr/bin/env python3
"""M5 回归：审批/命名/级联/未知工具/打码/权限 —— 直连 API 验证"""
import json
import os
import pathlib
import sys
import urllib.request

BASE = os.environ.get("AGENTD_BASE", "http://127.0.0.1:8799")
passed, failed, skipped = [], [], []

def req(method, path, body=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode())

def chat(message, session_id=None, timeout=90, on_approval=None):
    """调用 /api/chat（SSE 流），边读边回调审批；返回最终聚合结果"""
    body = json.dumps({"session_id": session_id, "message": message}).encode()
    r = urllib.request.Request(BASE + "/api/chat", data=body, method="POST",
                               headers={"Content-Type": "application/json"})
    out, gate, sid, tool_ok = {"text": "", "status": "error"}, None, None, False
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        while True:
            line = resp.readline()
            if not line:
                break
            if line.startswith(b"data: "):
                ev = json.loads(line[6:].decode())
                t = ev.get("type")
                if t == "session":
                    sid = ev.get("session_id")
                elif t == "text":
                    out["text"] += ev.get("text", "")
                elif t == "tool_start":
                    if ev.get("gate_id"):
                        gate = ev.get("gate_id")
                elif t == "approval":
                    gate = ev.get("id") or gate
                    if on_approval:
                        on_approval(sid, gate)
                elif t == "tool_update":
                    if ev.get("status") == "completed" and ev.get("result"):
                        tool_ok = True
                    if ev.get("result"):
                        out["tool_result"] = ev.get("result")
                elif t == "done":
                    out["status"] = "completed" if ev.get("stop_reason") == "end_turn" else ev.get("stop_reason", "done")
    out["session_id"] = sid
    out["tool_ok"] = tool_ok
    if gate:
        out["gate_id"] = gate
    return out

def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


def skip(name, why):
    """平台/环境不适用的断言记为跳过，不算失败（避免 Windows 本地跑出一堆假红）。"""
    skipped.append(name)
    print(f"  ⏭️  {name} — 跳过：{why}")

print("== M5 安全加固回归 ==")

# 1. 打码：设置 32 位 key 后 GET 只回打码
fixture_key = "fixture-api-key-alpha-1234567890"
req("PUT", "/api/settings", {"llm": {"api_key": fixture_key, "provider": "deepseek"}})
s = req("GET", "/api/settings")
masked_key = s["llm"]["api_key"]
check("API key 打码", masked_key == fixture_key[:4] + "*" * (len(fixture_key) - 8) + fixture_key[-4:] and fixture_key not in masked_key, masked_key)

# 2. 数据文件权限（600/700）—— 仅 POSIX 有意义：Windows/NTFS 上 st_mode 不反映
# chmod（Python 只能近似），断言 0o600 必然是假失败。
if os.name != "posix":
    skip("数据文件权限 600/700", f"非 POSIX 平台（{os.name}）无 chmod 语义，交由 Linux/Termux 覆盖")
else:
    home = pathlib.Path(os.environ.get("AGENT_HOME", "/tmp/pa-test"))
    mode_db = (home / "agent.db").stat().st_mode & 0o777
    mode_dir = home.stat().st_mode & 0o777
    check("数据库 600", mode_db == 0o600, oct(mode_db))
    check("目录 700", mode_dir == 0o700, oct(mode_dir))

# 3. 核心流程：电池工具（auto 模式直接跑）
#    可用性必须看**服务端**：工具由 agentd 进程执行，客户端 PATH 上有没有桩不作数
#    （CI 里服务端 PATH 已含 faketermux 桩，测试进程没有）。termux-api 缺失时工具返回
#    结构化 error，据此记为「跳过」；其他失败照旧算失败。
req("PUT", "/api/settings", {"permission_mode": "auto"})
r = chat("查看电池电量")
sid = r.get("session_id")
_res = r.get("tool_result")
tool_err = str(_res.get("error", "")) if isinstance(_res, dict) else ""
battery_ran = "termux-api 未安装" not in tool_err
if not battery_ran:
    skip("电池工具完成", "服务端未装 termux-api（真机或 tests/setup_faketermux.sh 桩），属环境而非代码")
else:
    check("电池工具完成", r.get("status") == "completed" and r.get("tool_ok"), r.get("status", "")[:40])

# 4. 审批：切回 approve，拨号应弹审批卡，流中回调 deny，随后会话应正常收尾
req("PUT", "/api/settings", {"permission_mode": "approve"})
denied = {"ok": False}
def deny(sid, tid):
    if sid and tid:
        req("POST", "/api/approval", {"session_id": sid, "tool_call_id": tid, "decision": "deny"})
        denied["ok"] = True
r = chat("给10086打个电话", on_approval=deny)
check("danger 工具弹审批", bool(r.get("gate_id")), f"gate={r.get('gate_id')}")
check("审批回调已发拒绝", denied["ok"])
check("拒绝后会话正常收尾", r.get("status") == "completed", str(r.get("status")))

# 4b. 审批超时：设 2s 超时，不审批 → 自动拒绝（工具不得执行）
req("PUT", "/api/settings", {"server": {"approval_timeout": 2}})
r = chat("给10086打个电话")
check("审批超时自动拒绝", r.get("status") == "completed" and not r.get("tool_ok"), f"status={r.get('status')} tool_ok={r.get('tool_ok')}")
req("PUT", "/api/settings", {"server": {"approval_timeout": 120}})

# 5. 会话自动命名（依赖第 3 步真的发起过"电池"会话）
if not battery_ran:
    skip("会话自动命名", "第 3 步电池会话未执行，无命名样本可断言")
else:
    ss = req("GET", "/api/sessions")
    names = [x.get("title", "") for x in ss if isinstance(x, dict)]
    check("会话自动命名", any("电池" in n or "电量" in n for n in names), str(names)[:80])

# 6. 工具清单：断言**下限**而不是精确值。
#    精确值断言（曾经写死 38）的问题是：每加一个工具就要改测试，而这类改动
#    本身没有风险——真正要防的是「工具批量丢失」，所以用下限 + 必含清单。
tools = req("GET", "/api/tools")
names = {t.get("name") for t in tools.get("tools", [])}
required = {
    "get_battery", "read_sms", "run_shell", "write_file",      # 既有核心工具未丢失
    "todo_write", "kb_search", "read_skill", "remember_fact",  # 新增能力已注册
    "web_search", "read_url",                                  # 联网能力已注册
    "subagent",                                                # 子代理已注册
}
check("工具数量不少于 41 项", tools.get("count", 0) >= 41, str(tools.get("count")))
check("工具清单含核心与新增能力", required <= names, str(sorted(required - names)))

print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败" + (f" / {len(skipped)} 跳过" if skipped else ""))
sys.exit(1 if failed else 0)
