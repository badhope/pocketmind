#!/usr/bin/env python3
"""P36 检索与备份格式测试（1.3 新能力）：

1. 跨会话搜索覆盖**工具输出**与**工具调用参数**（此前只搜 user/assistant 正文，
   传感器读数、文件内容、联网抓回的网页文本、工具入参全部搜不到）。
2. /api/export 带 `format_version`；/api/import 对历史备份（无该字段）照常导入，
   对未来版本（> 当前）与非法值明确 400 —— 绝不把不认识的备份猜着导进去。
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

# AGENT_HOME 必须在 import agentd.main 之前设定（settings_mgr 是模块级单例）
_HOME = tempfile.mkdtemp(prefix="p36-")
os.environ["AGENT_HOME"] = _HOME
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

passed, failed = [], []


def check(name: str, cond: bool, detail: str = ""):
    (passed if cond else failed).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail and not cond else ""))


async def _seed(store) -> str:
    """造一个含三种命中来源的会话：正文 / 工具输出 / 工具调用参数。"""
    sid = await store.create_session()
    await store.touch_session(sid, "检索种子会话")
    # 正文命中（基线：旧行为已覆盖）
    await store.add_message(sid, "user", "帮我查一下电池状态")
    # 工具输出命中：role='tool' 的 content 是传感器/命令输出
    await store.add_message(
        sid, "assistant", "好的，我来读取传感器。",
        meta={"tool_calls": [{"id": "call-1", "type": "function",
                              "function": {"name": "read_sensor", "arguments": "{\"sensor\": \"温度计ZK-7749\"}"}}]},
    )
    await store.add_message(sid, "tool", "读数完成：温度计ZK-7749 当前 26.4℃",
                            meta={"tool_call_id": "call-1"})
    # 工具调用参数命中：关键词只出现在 arguments JSON 里，正文与工具输出都没有
    await store.add_message(
        sid, "assistant", "我来查一下。",
        meta={"tool_calls": [{"id": "call-2", "type": "function",
                              "function": {"name": "read_url", "arguments": "{\"url\": \"https://example.com/qinsdzk报告\"}"}}]},
    )
    await store.add_message(sid, "tool", "抓取完成，正文里没有那个词",
                            meta={"tool_call_id": "call-2"})
    return sid


def main() -> int:
    print("== P36 检索与备份格式（进程内） ==")
    import agentd.main as M

    M.MOCK = True
    from fastapi.testclient import TestClient

    app = M.create_app()
    with TestClient(app) as c:
        store = M.store
        assert store is not None

        # ---------- 搜索：工具输出与调用参数 ----------
        sid = __import__("asyncio").run(_seed(store))

        r = c.get("/api/sessions/search", params={"q": "ZK-7749"})
        check("搜索工具输出命中", r.status_code == 200 and any(
            h["session_id"] == sid and h.get("matched") == "tool_output"
            for h in r.json().get("items", [])
        ), r.text[:200])

        r = c.get("/api/sessions/search", params={"q": "qinsdzk报告"})
        check("搜索工具调用参数命中", r.status_code == 200 and any(
            h["session_id"] == sid and h.get("matched") == "tool_call"
            for h in r.json().get("items", [])
        ), r.text[:200])

        r = c.get("/api/sessions/search", params={"q": "电池状态"})
        check("正文命中 matched=message", r.status_code == 200 and any(
            h["session_id"] == sid and h.get("matched") == "message"
            for h in r.json().get("items", [])
        ), r.text[:200])

        r = c.get("/api/sessions/search", params={"q": "绝不存在的词zkq"})
        check("无命中返回空列表", r.status_code == 200 and r.json().get("items") == [])

        # ---------- 导出 format_version ----------
        r = c.get("/api/export")
        check("GET /api/export 200", r.status_code == 200, str(r.status_code))
        bundle = r.json()
        check("导出含 format_version=1", bundle.get("format_version") == 1,
              str(bundle.get("format_version")))

        # ---------- 导入：历史备份（无 format_version）照常导入 ----------
        legacy = {
            "app": "口袋智灵",
            "version": "1.2.1",
            "sessions": [{
                "id": "legacy-sid-1", "title": "历史备份会话", "message_count": 1,
                "todos": [], "summary": None, "archives": [], "archived_upto": 0,
                "messages": [{"role": "user", "content": "来自 1.2.1 的消息", "meta": {}}],
            }],
        }
        r = c.post("/api/import", json=legacy)
        check("历史备份（无 format_version）可导入", r.status_code == 200 and
              r.json().get("ok") is True, r.text[:200])
        check("历史备份会话已恢复", r.json().get("imported", {}).get("sessions", 0) >= 1,
              r.text[:200])

        # ---------- 导入：未来版本 / 非法值必须拒绝 ----------
        future = dict(legacy, format_version=99)
        r = c.post("/api/import", json=future)
        check("未来版本备份 → 400", r.status_code == 400, str(r.status_code))
        check("400 提示先升级", "升级" in (r.json().get("detail") or ""))

        bad = dict(legacy, format_version="abc")
        r = c.post("/api/import", json=bad)
        check("format_version 非整数 → 400", r.status_code == 400, str(r.status_code))

        bad = dict(legacy, format_version=True)
        r = c.post("/api/import", json=bad)
        check("format_version 布尔 → 400", r.status_code == 400, str(r.status_code))

        bad = dict(legacy, format_version=0)
        r = c.post("/api/import", json=bad)
        check("format_version 0 → 400", r.status_code == 400, str(r.status_code))

        # 当前版本备份照常导入（幂等：同 id 会话跳过）
        current = dict(bundle)
        r = c.post("/api/import", json=current)
        check("当前版本备份可再导入", r.status_code == 200, r.text[:200])

    print(f"\n通过 {len(passed)} / 失败 {len(failed)}")
    if failed:
        print("失败项：", *failed, sep="\n  - ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
