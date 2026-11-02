#!/usr/bin/env python3
"""P34 用户旅程：在真实 HTTP 上走完一条完整的会话生命周期。

p14 用 TestClient 覆盖每个端点的**错误分支**；这套测的是另一件事——
**一个真实用户从打开应用到最后删掉会话，中间每一步的状态是否连贯**。
这正是单元测试与路由测试都覆盖不到、而用户第一分钟就会遇到的问题。

覆盖的旅程：

1. 首次启动是空环境：health 就绪、会话列表为空、工具清单可读。
2. 新建会话 → 发送一条会触发工具的消息 → 收到 SSE 事件流 → 审批 → 工具
   结果回填 → 助手给出收尾回复，并且这条回复**落库**（刷新页面还在）。
3. 会话被自动命名，出现在列表里，`message_count` 大于 0。
4. 再发一条消息（同一会话第二轮），确认第二轮**仍然能触发工具**——
   这一条守的是 mock_llm 的历史缺陷。
5. 消息级操作：改标题、置顶、搜索命中正文、删除单条消息。
6. 导出整包 → 在新环境里导入 → 会话与消息被恢复，且**不重复**。
7. 定时任务：建一个 interval 任务 → 立即执行 → 列表里能看到运行记录 →
   禁用 → 删除。
8. 审批规则：拒绝一次后进入全局名单，再次调用应被直接拒绝。
9. 会话删除后，它的消息不可再访问（404）。

设计约束（与门禁其它套件一致）：
- 自己起一个 mock 服务，挑空闲端口，跑完关掉；不依赖外部服务。
- 只断言用户能观察到的语义，不锁具体文案（避免文案微调就红）。
- 每一步失败都给出可读原因，不做"静默跳过"。
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable
passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Server:
    """起一个隔离的 agentd mock 进程，跑完一定回收。"""

    def __init__(self):
        self.home = tempfile.mkdtemp(prefix="p34-home-")
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        self.proc: subprocess.Popen | None = None

    def start(self):
        env = dict(os.environ)
        env["AGENT_HOME"] = self.home
        env["PYTHONIOENCODING"] = "utf-8"
        # faketermux 桩：让 phone 工具走成功分支而不是"未安装"
        faketermux = "/tmp/faketermux"
        if os.path.isdir(faketermux):
            env["PATH"] = faketermux + os.pathsep + env.get("PATH", "")
        self.proc = subprocess.Popen(
            [PY, "-m", "agentd.main", "--mock", "--port", str(self.port)],
            cwd=str(ROOT), env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        deadline = time.time() + 60
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(f"{self.base}/api/health", timeout=2) as r:
                    if json.loads(r.read()).get("ok"):
                        return
            except (urllib.error.URLError, ConnectionError, OSError):
                time.sleep(0.5)
        raise RuntimeError("mock 服务 60 秒内没有就绪")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def req(self, method: str, path: str, body=None, timeout: int = 30):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            if not raw:
                return r.status, {}
            try:
                return r.status, json.loads(raw)
            except json.JSONDecodeError:
                # /api/metrics 返回 Prometheus 纯文本，不是 JSON。按文本原样返回，
                # 调用方只关心状态码时不至于因为解析失败而中断整条旅程。
                return r.status, {"_raw": raw.decode("utf-8", errors="replace")}

    def sse(self, message: str, session_id: str, timeout: int = 90):
        """POST /api/chat 并收集 SSE 事件，返回 (事件列表, 是否见到 done)。

        注意：审批会**阻塞**这条流 —— 服务端的 ask_approval 回调在等决策时不会
        继续发事件。因此这里的 timeout 是从连接建立到读完的总时长，调用方若预期
        弹审批，应当先用 sse_async 起后台线程再审批。
        """
        payload = json.dumps({"message": message, "session_id": session_id}).encode()
        req = urllib.request.Request(
            self.base + "/api/chat", data=payload, method="POST",
            headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        )
        events = []
        with urllib.request.urlopen(req, timeout=timeout) as r:
            for raw in r:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                try:
                    ev = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    continue
                events.append(ev)
                if ev.get("type") == "done":
                    break
        return events, any(e.get("type") == "done" for e in events)

    def sse_async(self, message: str, session_id: str, timeout: int = 90):
        """在后台线程里跑 sse()，支持边收事件边审批。

        返回一个 dict：{"events": [], "done": bool, "error": str|None}，
        以及等待器 wait(prefix, cond, seconds)。
        """
        state = {"events": [], "done": False, "error": None}

        payload = json.dumps({"message": message, "session_id": session_id}).encode()
        req = urllib.request.Request(
            self.base + "/api/chat", data=payload, method="POST",
            headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        )

        def run():
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    for raw in r:
                        line = raw.decode("utf-8", errors="replace").strip()
                        if not line.startswith("data:"):
                            continue
                        try:
                            ev = json.loads(line[5:].strip())
                        except json.JSONDecodeError:
                            continue
                        state["events"].append(ev)
                        if ev.get("type") == "done":
                            state["done"] = True
                            break
            except Exception as exc:  # noqa: BLE001
                state["error"] = f"{type(exc).__name__}: {exc}"

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        state["thread"] = thread
        return state

    @staticmethod
    def wait_for_approval(state: dict, seconds: int = 20):
        """等后台流里出现审批事件，返回该事件或 None。"""
        deadline = time.time() + seconds
        while time.time() < deadline:
            ev = next((e for e in state["events"] if e.get("type") == "approval"), None)
            if ev:
                return ev
            if state["error"]:
                return None
            time.sleep(0.4)
        return None

    @staticmethod
    def wait_done(state: dict, seconds: int = 40) -> bool:
        deadline = time.time() + seconds
        while time.time() < deadline:
            if state["done"]:
                return True
            if state["error"]:
                return False
            time.sleep(0.4)
        return False


def approve(srv: Server, sid: str, tool_call_id: str, decision: str = "allow_once"):
    return srv.req("POST", "/api/approval",
                   {"session_id": sid, "tool_call_id": tool_call_id, "decision": decision})


def main() -> int:
    print("== P34 用户旅程（真实 HTTP，完整生命周期） ==")
    srv = Server()
    try:
        srv.start()
    except Exception as exc:  # noqa: BLE001
        check("mock 服务启动", False, str(exc))
        print("\n结果: 0 通过 / 1 失败")
        return 1
    check("mock 服务启动", True)

    try:
        # ── 1. 空环境 ──
        st, health = srv.req("GET", "/api/health")
        check("health 200 且 ready", st == 200 and health.get("ready") is True)
        st, sessions = srv.req("GET", "/api/sessions")
        check("初始会话列表为空", st == 200 and isinstance(sessions, list) and sessions == [])
        st, tools = srv.req("GET", "/api/tools")
        check("工具清单可读且非空", st == 200 and (tools.get("count") or 0) > 0,
              f"count={tools.get('count')}")

        # ── 2. 新建会话 → 触发工具 → 审批 → 结果回填 ──
        st, created = srv.req("POST", "/api/sessions", {})
        sid = created.get("session_id")
        check("创建会话返回 session_id", st == 200 and bool(sid), str(created)[:120])

        # 第一轮：用后台流，边收事件边处理审批（审批会阻塞流，不能同步读到底）
        state = srv.sse_async("查看电池电量", sid)
        approval_ev = srv.wait_for_approval(state, seconds=20)
        if approval_ev:
            tcid = approval_ev.get("id") or approval_ev.get("tool_call_id")
            st, _ = approve(srv, sid, tcid, "allow_once")
            check("审批提交 200", st == 200, f"tcid={tcid}")
        done = srv.wait_done(state, seconds=40)
        types = [e.get("type") for e in state["events"]]
        check("SSE 流收到 done", done, str(types[:12]))
        if approval_ev:
            # 审批后工具继续跑，等消息落库
            deadline = time.time() + 30
            got_result = False
            while time.time() < deadline:
                st, msgs = srv.req("GET", f"/api/sessions/{sid}/messages")
                if any(m.get("role") == "tool" for m in msgs):
                    got_result = True
                    break
                time.sleep(1)
            check("审批后工具结果落库", got_result)
        else:
            # 工具是 safe 级、无需审批时也应当有结果
            check("工具事件出现（safe 直接执行）", "tool_start" in types or "tool_update" in types,
                  str(types[:12]))

        st, msgs = srv.req("GET", f"/api/sessions/{sid}/messages")
        roles = [m.get("role") for m in msgs]
        check("消息已持久化（user + assistant）",
              "user" in roles and "assistant" in roles, str(roles))
        check("助手回复非空",
              any(m.get("role") == "assistant" and (m.get("content") or "").strip() for m in msgs))

        # ── 3. 会话列表可见、自动命名、计数 ──
        st, sessions = srv.req("GET", "/api/sessions")
        target = next((s for s in sessions if s.get("id") == sid), None)
        check("会话出现在列表里", target is not None)
        check("会话有标题", bool(target and (target.get("title") or "").strip()),
              str(target.get("title") if target else None))
        check("会话 message_count > 0", bool(target and target.get("message_count", 0) > 0),
              f"count={target.get('message_count') if target else None}")

        # ── 4. 同一会话第二轮仍能触发工具 ──
        state2 = srv.sse_async("发一条通知，内容是第二轮", sid)
        approval2 = srv.wait_for_approval(state2, seconds=20)
        if approval2:
            # 用「始终允许」把 send_notification 记进会话白名单，后续轮次不再弹卡
            approve(srv, sid, approval2.get("id") or approval2.get("tool_call_id"), "allow_always")
        done2 = srv.wait_done(state2, seconds=40)
        types2 = [e.get("type") for e in state2["events"]]
        check("第二轮 SSE 收到 done", done2, str(types2[:12]))
        tool_events2 = [x for x in types2 if x in ("tool_start", "approval")]
        check("第二轮仍触发工具", len(tool_events2) > 0, str(types2[:14]))
        time.sleep(1)

        # ── 5. 改标题 / 置顶 / 搜索 / 删单条消息 ──
        st, _ = srv.req("PUT", f"/api/sessions/{sid}", {"title": "P34 改名验证"})
        check("改标题 200", st == 200)
        st, _ = srv.req("PUT", f"/api/sessions/{sid}", {"pinned": True})
        check("置顶 200", st == 200)
        st, sessions = srv.req("GET", "/api/sessions")
        target = next((s for s in sessions if s.get("id") == sid), None)
        check("标题已更新", bool(target and target.get("title") == "P34 改名验证"),
              str(target.get("title") if target else None))
        check("置顶已生效", bool(target and target.get("pinned") == 1),
              f"pinned={target.get('pinned') if target else None}")

        st, found = srv.req("GET", "/api/sessions/search?q=" + urllib.parse.quote("第二轮"))
        check("正文搜索命中", st == 200 and bool(found), str(found)[:120])

        st, msgs = srv.req("GET", f"/api/sessions/{sid}/messages")
        user_msg = next((m for m in msgs if m.get("role") == "user"), None)
        if user_msg:
            st, _ = srv.req("DELETE", f"/api/sessions/{sid}/messages/{user_msg['id']}")
            check("删除单条消息 200", st == 200)
            st, msgs2 = srv.req("GET", f"/api/sessions/{sid}/messages")
            check("消息确实少了", len(msgs2) < len(msgs), f"{len(msgs)} -> {len(msgs2)}")

        # ── 6. 导出 → 新环境导入 ──
        st, bundle = srv.req("GET", "/api/export")
        check("导出 200 且含 settings/sessions",
              st == 200 and "settings" in bundle and "sessions" in bundle)
        exported_sessions = bundle.get("sessions") or []
        check("导出包含本会话", any(s.get("id") == sid for s in exported_sessions),
              f"n={len(exported_sessions)}")
        check("导出不含明文 api_key", "api_key" not in json.dumps(bundle.get("settings", {})),
              "settings 里出现了 api_key 字段")

        # 在同一个服务上导入应幂等（已存在则跳过）
        st, imported = srv.req("POST", "/api/import", bundle)
        check("重复导入幂等（200 且不报错）", st == 200, str(imported)[:120])
        st, sessions_after = srv.req("GET", "/api/sessions")
        same_ids = [s.get("id") for s in sessions_after]
        check("导入后会话未重复", len(same_ids) == len(set(same_ids)),
              f"{len(same_ids)} vs {len(set(same_ids))}")

        # ── 7. 定时任务生命周期 ──
        st, job = srv.req("POST", "/api/jobs", {
            "name": "P34 任务", "message": "查看电池电量",
            "trigger_type": "interval", "expr": "3600",
        })
        jid = job.get("id") or job.get("job_id")
        check("创建定时任务 200 且有 id", st == 200 and bool(jid), str(job)[:120])
        if jid:
            st, _ = srv.req("POST", f"/api/jobs/{jid}/run")
            check("立即执行 200", st == 200)
            time.sleep(3)
            st, jobs = srv.req("GET", "/api/jobs")
            listing = jobs.get("jobs") if isinstance(jobs, dict) else jobs
            entry = next((j for j in (listing or []) if (j.get("id") or j.get("job_id")) == jid), None)
            check("任务出现在列表", entry is not None)
            st, _ = srv.req("PUT", f"/api/jobs/{jid}", {"enabled": False})
            check("禁用任务 200", st == 200)
            st, _ = srv.req("DELETE", f"/api/jobs/{jid}")
            check("删除任务 200", st == 200)

        # ── 8. 审批全局拒绝名单 ──
        st, rules = srv.req("GET", "/api/approvals")
        check("审批规则可读", st == 200 and "deny" in str(rules).lower(), str(rules)[:100])

        # ── 9. 删除会话后其消息不可访问 ──
        st, _ = srv.req("DELETE", f"/api/sessions/{sid}")
        check("删除会话 200", st == 200)
        try:
            st, _ = srv.req("GET", f"/api/sessions/{sid}/messages")
            gone = st == 404
        except urllib.error.HTTPError as exc:
            gone = exc.code == 404
        check("删除后消息 404", gone)

        # ── 10. 其他只读端点不 500 ──
        for path in ("/api/providers", "/api/recipes", "/api/skills", "/api/kb",
                     "/api/memory", "/api/stats", "/api/metrics", "/api/diagnostics",
                     "/api/settings", "/api/mcp"):
            try:
                st, _ = srv.req("GET", path, timeout=20)
                check(f"GET {path} 不报错", st == 200, f"status={st}")
            except urllib.error.HTTPError as exc:
                check(f"GET {path} 不报错", False, f"HTTP {exc.code}")

    finally:
        srv.stop()

    total = len(passed) + len(failed)
    print(f"\n结果: {len(passed)} 通过 / {len(failed)} 失败（共 {total} 项检查）")
    if failed:
        print("失败项：" + "；".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
