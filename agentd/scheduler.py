"""定时/条件触发：APScheduler(AsyncIOScheduler) + 官方 SQLAlchemyJobStore 持久化。

设计要点（P2 重构）：
1. 持久化改由 APScheduler 官方 SQLAlchemyJobStore 承担：job 的 trigger、func 引用、
   args/kwargs 全部 pickle 进 SQLite（apscheduler_jobs 表），进程重启后自动恢复调度，
   不再维护自写的 jobs 表。
2. 回调必须是「模块级、可导入」的函数（SQLAlchemyJobStore 会按引用 pickle/unpickle），
   因此定义顶层 ``_fire_job``；它只接收 job_id（位置参数）+ 字符串 kwargs，绝不绑定
   持有 store/runs 的闭包。真正执行 agent 的逻辑放在 main.py 的顶层函数
   ``run_agent_for_job``，触发时再延迟导入，避免循环依赖。
3. 业务字段（message/session_id/condition/name/trigger_type/expr/created_at）全部塞进
   job.kwargs（纯字符串），不新增扩展表，不改动 SQLAlchemyJobStore 的表结构。
4. 「停用」用 APScheduler 的 pause_job/resume_job 表达：停用即 next_run_time=None，
   持久化后重启仍保持停用状态。
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path

from apscheduler.jobstores.sqlalchemy import SQLAlchemyJobStore
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.date import DateTrigger
from apscheduler.triggers.interval import IntervalTrigger

# trigger_type 合法值
TRIGGER_TYPES = ("cron", "interval", "date")

# 条件操作符：支持 battery < 阈值（如 "battery < 20"）
CONDITION_OPS = ("<", ">", "<=", ">=", "==")


def parse_condition(cond: str) -> tuple[str, str, float] | None:
    """解析条件字符串 → (metric, op, value)；无法解析返回 None。"""
    if not cond or not isinstance(cond, str):
        return None
    s = cond.strip().lower()
    for op in CONDITION_OPS:
        if op in s:
            left, right = s.split(op, 1)
            metric = left.strip()
            try:
                value = float(right.strip())
            except ValueError:
                return None
            if metric not in ("battery",):
                return None
            return metric, op, value
    return None


async def _check_condition(cond: tuple[str, str, float]) -> bool:
    """条件求值：读手机电量与阈值比较。读不到电池时视为满足（避免任务静默丢失）。"""
    metric, op, value = cond
    try:
        # phone 包里 system 模块函数名是 battery()，注册的工具名才是 "get_battery"；
        # 必须按实际函数名导入，否则 ImportError 会被下面的 except 吞掉、条件恒为 True。
        from .tools.phone.system import battery as get_battery

        info = await get_battery()
        pct = float(info.get("percentage") or 0)
    except Exception as exc:  # noqa: BLE001
        # Fail-CLOSE：电池读取失败时不运行条件任务（避免低电量时错误运行耗电任务）。
        # 注意：此前 fail-open 会导致 "battery > 20" 在读取失败时恒 True，反而危险。
        import logging as _log
        _log.getLogger(__name__).warning("条件检查读取电池失败（本次不运行）: %s", exc)
        return False
    return {
        "<": pct < value,
        ">": pct > value,
        "<=": pct <= value,
        ">=": pct >= value,
        "==": abs(pct - value) < 0.5,
    }.get(op, True)


async def _fire_job(
    job_id: str,
    *,
    message: str = "",
    session_id: str = "",
    condition: str = "",
    name: str = "",
    **_: object,
) -> None:
    """APScheduler 触发入口（模块级、可被 pickle 的回调）。

    APScheduler 调用形式为 ``_fire_job(*args, **kwargs)``：args=[job_id]，其余业务字段
    从 kwargs 解包。真正执行 agent 的是 main.py 顶层函数 ``run_agent_for_job``，此处
    延迟导入以解开 main ↔ scheduler 的循环依赖。
    """
    from .notify import notify_async

    # 条件触发：不满足则跳过本轮（仍发一条通知留痕）
    parsed = parse_condition(condition)
    if parsed:
        ok = await _check_condition(parsed)
        if not ok:
            await notify_async(
                "口袋智灵 · 条件未满足", f"{name}：{condition}，本次跳过", persistent=False
            )
            return

    await notify_async("口袋智灵 · 定时任务", f"正在执行：{name}", persistent=False)
    try:
        from .main import run_agent_for_job  # 延迟导入：避免与 main.py 循环依赖
        from .telemetry import scheduler_span

        with scheduler_span(job_id):
            await run_agent_for_job(session_id, message)
    except Exception:  # noqa: BLE001 —— 定时任务异常不能影响调度器
        await notify_async("口袋智灵 · 定时任务出错", name, persistent=False)


class SchedulerService:
    """AsyncIOScheduler + SQLAlchemyJobStore 的薄封装。

    对外保持 create/update/delete/list/get/delete_by_session/start/shutdown 接口不变，
    main.py 路由层无需感知底层从「自管 sqlite」换成了「官方 JobStore」。
    """

    def __init__(self, db_path: str | Path):
        # 官方 SQLAlchemyJobStore：url 形式内部自建同步 engine，表 apscheduler_jobs 自动创建
        self._jobstore = SQLAlchemyJobStore(url=f"sqlite:///{db_path}")
        # 显式给时区，**不能让 APScheduler 去猜本地时区**：
        # Android/Termux 没有系统 tzdata（也没有 /etc/localtime），tzlocal 会抛
        # `ZoneInfoNotFoundError: No time zone found with key GMT`，直接导致服务启动失败
        # （真机/模拟器实测）。这里按「环境变量 → tzdata → UTC」三级降级，永不因时区崩溃。
        self._sched = AsyncIOScheduler(
            jobstores={"default": self._jobstore},
            timezone=self._resolve_timezone(),
        )

    @staticmethod
    def _resolve_timezone():
        """解析可用的时区，任何一步失败都退回 UTC。

        Termux 上装 `termux-tools` 会提供 TZ 环境变量；未装时退回 UTC。
        定时任务的语义是「到点执行」，用 UTC 只会让"每天 8 点"按 UTC 解释，
        对个人助手可以接受（且可在设置里通过 TZ 覆盖），远好于启动即崩。

        实现说明（2026-10-29 修复）：不能再用 `from apscheduler.util import timezone`。
        apscheduler 3.10 里该名字是 pytz.timezone（接受字符串），而 3.11 起
        `apscheduler.util.timezone` 变成了 `datetime.timezone` 类 —— 传字符串直接
        TypeError，被 except 吞掉后永远落到 UTC。"每天 8 点"于是变成"每天 16 点"
        且没有任何报错。改为显式用 zoneinfo（Python 3.9+ 标准库）。
        """
        import logging as _logging
        import os as _os

        try:
            from zoneinfo import ZoneInfo
        except ImportError:  # pragma: no cover —— Python < 3.9，本项目已要求 3.11+
            ZoneInfo = None  # type: ignore[assignment]

        candidates = []
        tz_env = _os.environ.get("TZ", "").strip()
        if tz_env:
            candidates.append(tz_env.lstrip(":"))
        candidates += ["Asia/Shanghai", "UTC"]
        if ZoneInfo is not None:
            for name in candidates:
                try:
                    return ZoneInfo(name)
                except Exception:  # noqa: BLE001 —— 取不到就试下一个
                    continue
        # 连 UTC 的 zoneinfo 都取不到（tzdata 完全缺失）→ 用固定偏移时区
        _logging.getLogger(__name__).warning(
            "系统无可用时区数据，定时任务按 UTC 运行；可设置 TZ 环境变量覆盖"
        )
        try:
            from datetime import timedelta
            from datetime import timezone as _dt_timezone

            return _dt_timezone(timedelta(0), name="UTC")
        except Exception:  # noqa: BLE001 —— 最后兜底：交给 APScheduler 默认
            return None

    # ---------- trigger 构造 ----------
    @staticmethod
    def _make_trigger(job: dict):
        tt = job["trigger_type"]
        if tt == "cron":
            return CronTrigger.from_crontab(job["expr"])
        if tt == "interval":
            return IntervalTrigger(seconds=max(int(float(job["expr"])), 5))
        # date：一次性。路由层已用 "%Y-%m-%d %H:%M" 校验过格式，这里必须把字符串
        # 解析成 datetime 对象——直接把字符串丢给 DateTrigger 会抛 "Invalid date string"
        # （APScheduler 只接受 ISO 格式或 datetime 实例）。
        from datetime import datetime

        run_date = job["expr"]
        if isinstance(run_date, str):
            run_date = datetime.strptime(run_date, "%Y-%m-%d %H:%M")
        return DateTrigger(run_date=run_date)

    # ---------- job ↔ dict 转换 ----------
    @staticmethod
    def _job_to_dict(job) -> dict:
        """把 APScheduler Job 对象转成对外 dict（字段与旧自管 JobStore 对齐）。

        APScheduler 3.11：``next_run_time`` 通过 ``job.next_run_time`` 访问；
        暂停状态 job 该属性可能不存在（用 getattr 兜底）。
        """
        kw = dict(job.kwargs or {})
        nrt = getattr(job, "next_run_time", None)
        return {
            "id": job.id,
            "name": kw.get("name", job.id),
            "trigger_type": kw.get("trigger_type", ""),
            "expr": kw.get("expr", ""),
            "message": kw.get("message", ""),
            "session_id": kw.get("session_id", ""),
            "condition": kw.get("condition", ""),
            # 停用任务被 pause 后 next_run_time 为 None
            "enabled": nrt is not None,
            "created_at": kw.get("created_at", 0),
            "next_run": nrt.isoformat() if nrt else None,
        }

    # ---------- 公共 CRUD（接口与旧实现一致） ----------
    def start(self) -> None:
        """启动调度器：SQLAlchemyJobStore 自动加载已持久化的 job，未停用者恢复触发。"""
        if not self._sched.running:
            self._sched.start()

    def get(self, jid: str) -> dict | None:
        job = self._sched.get_job(jid)
        return self._job_to_dict(job) if job else None

    def list(self) -> list[dict]:
        return [self._job_to_dict(j) for j in self._sched.get_jobs()]

    def create(self, job: dict) -> dict:
        jid = job.get("id") or uuid.uuid4().hex[:12]
        # 业务字段全部进 kwargs（纯字符串，可 pickle）；args 只放 job_id
        kwargs = {
            "message": job["message"],
            "session_id": job.get("session_id", ""),
            "condition": job.get("condition", ""),
            "name": job["name"],
            "trigger_type": job["trigger_type"],
            "expr": job["expr"],
            "created_at": job.get("created_at", time.time()),
        }
        self._sched.add_job(
            _fire_job,
            trigger=self._make_trigger(job),
            id=jid,
            args=[jid],
            kwargs=kwargs,
            replace_existing=True,
            misfire_grace_time=300,
            coalesce=True,  # 多个 misfire 触发合并为一次执行，防积压连击
        )
        # 默认停用：立即 pause（next_run_time=None，持久化后重启仍停用）
        if not job.get("enabled", True):
            self._sched.pause_job(jid)
        return self.get(jid)

    def update(self, jid: str, patch: dict) -> dict | None:
        """更新 job。

        - ``enabled`` 切换：True→resume / False→pause
        - 调度参数变更（``expr`` / ``trigger_type`` / ``message`` / ``condition``）：
          通过 APScheduler.reschedule_job 热替换 trigger + 更新 kwargs，无需删了重建。
        """
        if self._sched.get_job(jid) is None:
            return None

        existing = self.get(jid)

        # ── 调度参数变更（需重建 trigger + 更新 kwargs 缓存） ──
        if "expr" in patch or "trigger_type" in patch:
            rebuilt = {
                "trigger_type": patch.get("trigger_type", existing.get("trigger_type", "interval")),
                "expr": patch.get("expr", existing.get("expr", "3600")),
                "message": patch.get("message", existing.get("message", "")),
                "condition": patch.get("condition", existing.get("condition") or ""),
                "name": existing.get("name", ""),
            }
            trigger = self._make_trigger(rebuilt)
            self._sched.reschedule_job(jid, trigger=trigger)
            # 同步更新 kwargs 里的 trigger_type/expr（_job_to_dict 从这里读）
            job = self._sched.get_job(jid)
            if job:
                job.kwargs["trigger_type"] = rebuilt["trigger_type"]
                job.kwargs["expr"] = rebuilt["expr"]

        # ── 业务字段变更（message / condition） ──
        job = self._sched.get_job(jid)
        if job:
            for k in ("message", "condition"):
                if k in patch:
                    job.kwargs[k] = patch[k]

        # ── enabled 切换 ──
        if "enabled" in patch:
            if patch["enabled"]:
                self._sched.resume_job(jid)
            else:
                self._sched.pause_job(jid)

        return self.get(jid)

    def delete(self, jid: str) -> bool:
        try:
            self._sched.remove_job(jid)
            return True
        except Exception:  # noqa: BLE001
            return False

    def run_now(self, jid: str) -> bool:
        """立即执行一次（不等下一个触发点）：在事件循环里直接调度 _fire_job。

        为什么不是 ``modify_job(next_run_time=now)``：那会把一次性 date 任务
        变成「跑两次」；为什么不是 ``job.func()`` 同步调用：_fire_job 是协程，
        在路由里直接 await 会把整个 HTTP 响应卡到 agent 跑完（可能几分钟）。
        这里用 create_task 后台执行，HTTP 立即返回，进度照常走 SSE/通知。
        条件表达式仍然生效（_fire_job 内部会先求值）。
        """
        job = self._sched.get_job(jid)
        if job is None:
            return False
        import asyncio

        kwargs = dict(job.kwargs or {})
        asyncio.get_running_loop().create_task(_fire_job(jid, **kwargs))
        return True

    def delete_by_session(self, session_id: str) -> int:
        """删除某会话下全部任务（会话删除时级联清理）。"""
        removed = 0
        for job in self._sched.get_jobs():
            if (job.kwargs or {}).get("session_id") == session_id:
                try:
                    self._sched.remove_job(job.id)
                    removed += 1
                except Exception:  # noqa: BLE001
                    pass
        return removed

    def shutdown(self) -> None:
        try:
            if self._sched.running:
                self._sched.shutdown(wait=False)
        except Exception:  # noqa: BLE001
            pass
        try:
            self._jobstore.engine.dispose()
        except Exception:  # noqa: BLE001
            pass


# 便于类型标注：run_agent_for_job 的签名约定（main.py 提供实现）
RunAgentForJob = Callable[[str, str], Awaitable[None]]
