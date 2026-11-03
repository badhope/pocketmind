"""审批中心：管理所有待审批工具调用的生命周期。

每个待审批工具调用持有一个 asyncio.Event；超时默认拒绝（安全优先）。

权限粒度（对标 goose 的 PermissionLevel：AlwaysAllow / AskBefore / NeverAllow）：

  deny（全局黑名单）  —— 工具名粒度，跨会话生效。**优先级最高**：命中即短路拒绝，
                        既不建等待门也不推审批卡。拒绝是收敛方向，所以默认全局。
  global（全局白名单）—— 工具名粒度，跨会话生效。用户在审批卡上显式选「始终允许（全局）」
                        才写入（不隐式扩散：会话级决策不升级为全局，避免一次误点永久放行）。
  session（会话白名单）—— (会话 + 工具名) 粒度，单会话内免重复确认（原有行为）。

持久化：``$AGENT_HOME/approval_always.json``，格式
``{"sessions": {sid: [tool]}, "global": [tool], "deny": [tool]}``；
磁盘上是旧格式（顶层直接是 ``{sid: [tool]}``）时按会话白名单兼容载入。
"""
from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Callable
from pathlib import Path
from typing import cast


# PersistentStore：简单的 JSON 原子写 + 崩溃安全。
def _atomic_json_write(path: Path, data: dict) -> None:
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _load_json_safe(path: Path) -> dict:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    return {}


class ApprovalCenter:
    """每个待审批工具调用一个 asyncio.Event；超时默认拒绝（安全优先）。"""

    def __init__(self, timeout_cb: Callable[[], int] | None = None, persist_path: Path | None = None):
        self._gates: dict[tuple[str, str], dict] = {}
        self._always: dict[str, set] = {}     # 会话级白名单：sid -> {tool}
        self._global: set[str] = set()        # 全局白名单：{tool}（跨会话）
        self._never: set[str] = set()         # 全局黑名单：{tool}（跨会话，优先级最高）
        self._timeout_cb = timeout_cb or (lambda: 120)
        self._persist_path = persist_path
        if persist_path:
            self._load()

    # ---------- 持久化 ----------

    def _load(self) -> None:
        loaded = _load_json_safe(self._persist_path) if self._persist_path else {}
        if not isinstance(loaded, dict):
            return
        try:
            if "sessions" in loaded or "global" in loaded or "deny" in loaded:
                # 新格式
                sessions = loaded.get("sessions") or {}
                self._always = {k: set(v) for k, v in sessions.items() if v}
                self._global = {str(t) for t in (loaded.get("global") or [])}
                self._never = {str(t) for t in (loaded.get("deny") or [])}
            else:
                # 旧格式：顶层直接是 {sid: [tool]}，按会话白名单兼容
                self._always = {k: set(v) for k, v in loaded.items() if v}
        # aqg: top-level boundary —— 失败仅降级本能力
        except Exception:  # noqa: BLE001 —— 坏文件不阻塞启动
            self._always, self._global, self._never = {}, set(), set()

    def _persist(self) -> None:
        """名单变更后写盘（原子替换）。"""
        if not self._persist_path:
            return
        try:
            _atomic_json_write(
                self._persist_path,
                {
                    "sessions": {k: sorted(v) for k, v in self._always.items() if v},
                    "global": sorted(self._global),
                    "deny": sorted(self._never),
                },
            )
        except Exception:
            pass  # 写盘失败不影响运行时；下次重启会读旧版本

    # ---------- 查询 ----------

    def is_auto_allowed(self, session_id: str, tool_name: str) -> bool:
        """会话级或全局白名单命中即自动放行。"""
        return tool_name in self._global or tool_name in self._always.get(session_id, set())

    def is_auto_denied(self, tool_name: str) -> bool:
        """全局黑名单命中（跨会话）。"""
        return tool_name in self._never

    def is_always_allowed_in_any_session(self, tool_name: str) -> bool:
        """该工具是否在任何会话中被『始终允许』（前端用：判断默认勾选）。"""
        return tool_name in self._global or any(tool_name in v for v in self._always.values())

    def list_rules(self) -> dict:
        """当前权限名单（前端展示 / 用户清理误点用）。"""
        return {
            "global": sorted(self._global),
            "deny": sorted(self._never),
            "sessions": {k: sorted(v) for k, v in self._always.items() if v},
        }

    def clear_never(self, tool_name: str | None = None) -> None:
        """清除黑名单（全部，或指定工具）。"""
        if tool_name is None:
            self._never.clear()
        else:
            self._never.discard(tool_name)
        self._persist()

    def clear_global(self, tool_name: str | None = None) -> None:
        """清除全局白名单（全部，或指定工具）。"""
        if tool_name is None:
            self._global.clear()
        else:
            self._global.discard(tool_name)
        self._persist()

    # ---------- 主流程 ----------

    async def ask(
        self,
        session_id: str,
        tool_call_id: str,
        tool_name: str,
        summary: str = "",
        risk: str = "",
    ) -> str:
        # 黑名单最优先：直接拒绝，不建门、不等待（调用方也不会推审批卡）
        if tool_name in self._never:
            return "deny"
        if self.is_auto_allowed(session_id, tool_name):
            return "allow"
        timeout = self._timeout_cb()
        gate = {
            "event": asyncio.Event(),
            "decision": None,
            # 展示信息 + 创建时刻：让「待审批」可被查询出来。
            # 后台定时任务触发的审批卡只能 emit 到无人消费的队列，用户在界面里
            # 永远看不到它（只能等超时被拒），必须有一条可轮询的查询通路。
            "name": tool_name,
            "summary": summary,
            "risk": risk,
            "created_at": time.time(),
            "timeout": timeout,
        }
        self._gates[(session_id, tool_call_id)] = gate
        # gate 是异构 dict（值含 Event/str/float/None），mypy 无法从下标访问推断
        # event 类型；cast 仅做类型收窄，运行时恒等调用
        event = cast(asyncio.Event, gate["event"])
        try:
            await asyncio.wait_for(event.wait(), timeout=timeout)
        except TimeoutError:
            return "timeout"  # 超时默认拒绝（安全优先）
        finally:
            self._gates.pop((session_id, tool_call_id), None)
        d = gate["decision"]
        if d == "allow_always":
            self._always.setdefault(session_id, set()).add(tool_name)
            self._persist()
            return "allow"
        if d == "allow_global":
            self._global.add(tool_name)
            self._persist()
            return "allow"
        if d == "allow_once":
            return "allow"
        if d == "deny_always":
            # 黑名单是全局的（拒绝是收敛方向）；同时清掉可能存在的放行记录
            self._never.add(tool_name)
            self._global.discard(tool_name)
            self._always.get(session_id, set()).discard(tool_name)
            self._persist()
            return "deny"
        return "deny"

    def pending(self) -> list[dict]:
        """当前未决审批（供前端轮询显示，覆盖后台任务/定时任务触发的场景）。"""
        now = time.time()
        out: list[dict] = []
        for (sid, tid), gate in list(self._gates.items()):
            if gate.get("event") and gate["event"].is_set():
                continue
            created = float(gate.get("created_at") or now)
            timeout = int(gate.get("timeout") or self._timeout_cb())
            out.append(
                {
                    "session_id": sid,
                    "tool_call_id": tid,
                    "name": gate.get("name", ""),
                    "summary": gate.get("summary", ""),
                    "risk": gate.get("risk", ""),
                    "remaining": max(0, int(timeout - (now - created))),
                    "timeout": timeout,
                }
            )
        return out

    def decide(self, session_id: str, tool_call_id: str, decision: str) -> bool:
        gate = self._gates.get((session_id, tool_call_id))
        if not gate:
            return False
        gate["decision"] = decision
        gate["event"].set()
        return True

    def forget(self, session_id: str) -> None:
        """会话删除时清理：未决审批 + 该会话的『始终允许』记忆（全局名单不动）。"""
        for k, gate in self._gates.items():
            if k[0] == session_id and not gate["event"].is_set():
                gate["decision"] = "deny"
                gate["event"].set()
        self._gates = {k: v for k, v in self._gates.items() if k[0] != session_id}
        if session_id in self._always:
            del self._always[session_id]
            self._persist()
