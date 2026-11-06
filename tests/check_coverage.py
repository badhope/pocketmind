#!/usr/bin/env python3
"""覆盖率门禁：跑核心模块的覆盖率并按实测值卡红线。

为什么单独写一个脚本，而不是塞进 run_all.sh 里一行 pytest --cov：
本仓库的测试不是 pytest 风格（smoke/p*.py 是自带 check() 的脚本），
pytest 直接收集不到。用 `coverage run` 包住这些脚本才是真实覆盖率。

**红线不是拍脑袋定的**：先用当前实测值减去一点余量作为地板，
每次只能往上抬（ratchet）。改低红线必须在 PR 里写明理由——
否则"覆盖率门禁"会退化成"覆盖率装饰"。

用法：
    python tests/check_coverage.py            # 跑并检查
    python tests/check_coverage.py --report   # 只看上次结果
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 参与统计的包。用**包名**而不是文件路径：`--source` 接受的是可导入的模块/包，
# 传 "agentd/main.py" 会被当成模块路径解析，结果只统计到少数几个文件
# （实测 17/43），覆盖率数字虚高且不可信。
TARGET = "agentd"

# 红线（百分比）。**只能上调**。
# 基线来源：2026-11-07 在 Windows + Python 3.12 上实测 46.4%（见 CHANGELOG 1.6.0）。
# 地板取 40%：留出小重构的余量，但不允许明显倒退。
LINE_FLOOR = 40.0
BRANCH_FLOOR = 0.0  # 分支覆盖暂不卡：coverage 的分支统计对 async 代码噪声大

# 覆盖率要跑哪些脚本：选覆盖面最广的几个，不是全跑（全跑一遍要 20+ 分钟）
# 套件在子进程里逐个跑（见 run()），所以不会互相污染模块级状态。
SUITES = [
    "smoke/p14_router_test.py",
    "smoke/p18_agent_loop_test.py",
    "smoke/p15_file_tools_test.py",
    "smoke/p16_knowledge_test.py",
    "smoke/p20_permissions_test.py",
    "smoke/p24_run_agent_test.py",
]


def run() -> int:
    env = dict(os.environ)
    env.setdefault("MOCK", "1")
    env["AGENT_HOME"] = tempfile.mkdtemp(prefix="cov-home-")

    # 必须先删旧数据文件：`coverage run --append` 会把上一次的结果一并算进去，
    # 导致「跑了 3 个套件但统计出 40% 覆盖、17 个文件」这种自欺结果。
    # 门禁数字必须只反映本次运行。
    for stale in (ROOT / ".coverage", ROOT / ".coverage.json"):
        stale.unlink(missing_ok=True)

    failed_suites = []
    for suite in SUITES:
        path = ROOT / suite
        if not path.exists():
            print(f"  跳过不存在的套件：{suite}")
            continue
        # append 模式：多个套件累加到同一份 .coverage
        # 每个套件独立数据文件：`--append` 到同一个 .coverage 时，若上一轮遗留了
        # 数据会把不同套件的测量混在一起（实测导致 17/43 文件、数字虚高），
        # 而且套件之间还会互相污染模块级状态。
        data_file = ROOT / f".coverage.{suite.replace('/', '_')}"
        data_file.unlink(missing_ok=True)
        cmd = [
            sys.executable, "-m", "coverage", "run",
            f"--data-file={data_file}",
            f"--source={TARGET}",
            str(path),
        ]
        def once(cmd=cmd):
            return subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True)
        r = once()
        if r.returncode != 0:
            out = r.stdout or r.stderr or ""
            # 区分两类失败：
            #  - 断言失败：输出含「N 失败」且 N>0 → 真红，不重试
            #  - 进程崩溃：断言全过（0 失败）但退出码非零。实测为 Windows
            #    coverage tracer 在解释器收尾阶段偶发 0xC0000409（约 1/10），
            #    断言本身全绿。对这类崩溃重试一次；仍崩才算失败。
            m = re.search(r"(\d+)\s*失败", out)
            asserted_failed = bool(m) and int(m.group(1)) > 0
            if not asserted_failed:
                r2 = once()  # 同参数重试
                if r2.returncode == 0:
                    print(f"  采集 {suite} ... FLAKY-CRASH，重试通过"
                          f"（首次 exit {r.returncode}，断言全过；tracer 收尾阶段偶发崩溃）")
                    r = r2
                else:
                    out2 = r2.stdout or r2.stderr or ""
                    m2 = re.search(r"(\d+)\s*失败", out2)
                    if not (m2 and int(m2.group(1)) > 0):
                        # 重试仍是崩溃且断言全过 → 再给最后一次机会前先记录
                        print(f"  采集 {suite} ... 崩溃复现（exit {r.returncode}/{r2.returncode}，断言全过）")
            if r.returncode != 0:
                failed_suites.append(suite)
                out = r.stdout or r.stderr or ""
                tail = out.strip().splitlines()[-6:]
                print(f"  采集 {suite} ... 失败（exit {r.returncode}）")
                for line in tail:
                    print(f"      {line}")
        else:
            print(f"  采集 {suite} ... ok")

    if failed_suites:
        print(f"覆盖率采集期间有套件失败：{failed_suites}")
        return 1

    # 合并各套件的独立数据文件
    data_files = sorted(ROOT.glob(".coverage.smoke_*"))
    if data_files:
        combine = subprocess.run(
            [sys.executable, "-m", "coverage", "combine", "--keep",
             "--data-file=.coverage", *[str(f) for f in data_files]],
            cwd=ROOT, capture_output=True, text=True,
        )
        if combine.returncode != 0:
            print(f"  合并覆盖率数据失败：{(combine.stderr or '')[-300:]}")
            return 1

    # 用 json 报告拿总数：`report --format=total` 的输出格式在不同 coverage 版本
    # 间改过（7.x 加了表格样式），解析它比解析 json 脆。
    subprocess.run([sys.executable, "-m", "coverage", "json", "-o", ".coverage.json",
                    "--data-file=.coverage"],
                   cwd=ROOT, capture_output=True, text=True)
    import json

    data = json.loads((ROOT / ".coverage.json").read_text(encoding="utf-8"))
    totals = data["totals"]
    line_pct = float(totals["percent_covered"])
    branch_pct = float(totals.get("percent_covered_display", line_pct).rstrip("%")) \
        if isinstance(totals.get("percent_covered_display"), str) else line_pct
    n_files = len(data["files"])

    print(f"  {'OK ' if line_pct >= LINE_FLOOR else 'BAD'} 行覆盖 {line_pct:.1f}%"
          f"（红线 {LINE_FLOOR:.0f}%，{n_files} 个文件，{totals['num_statements']} 条语句）")
    (ROOT / ".coverage.json").unlink(missing_ok=True)
    for f in ROOT.glob(".coverage.smoke_*"):
        f.unlink(missing_ok=True)

    if line_pct < LINE_FLOOR:
        print(f"覆盖率低于红线：{line_pct:.1f}% < {LINE_FLOOR:.0f}%")
        print("  补测试，或在 PR 里写明为什么必须下调红线（不允许静默改低）")
        return 1
    if BRANCH_FLOOR and branch_pct < BRANCH_FLOOR:
        print(f"分支覆盖低于红线：{branch_pct:.1f}% < {BRANCH_FLOOR:.0f}%")
        return 1
    return 0


if __name__ == "__main__":
    if "--report" in sys.argv:
        sys.exit(subprocess.run([sys.executable, "-m", "coverage", "report"],
                                cwd=ROOT).returncode)
    sys.exit(run())
