#!/usr/bin/env python3
"""锁文件一致性门禁：requirements.txt 里的每个直接依赖都必须在锁文件里出现。

为什么需要：锁文件是「可复现」的承诺，但它会过期。有人往 requirements.txt
加了一个包、忘了重新冻结，CI 用锁文件装出来的环境就缺这个包——表现为
「本地能跑、CI 报 ModuleNotFoundError」，而且报错位置离根因很远。

这里只检查**直接依赖是否被覆盖**，不比对版本号：版本号由 pip 解析决定，
真正要防的是「加了依赖但锁没跟上」这种人为疏漏。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 允许用 PEP 503 的常见写法差异（- vs _），并剥掉 extras 与版本约束
NAME = re.compile(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)")


def norm(name: str) -> str:
    return name.lower().replace("_", "-")


def direct_deps(path: Path) -> set[str]:
    out: set[str] = set()
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = NAME.match(line)
        if m:
            out.add(norm(m.group(1)))
    return out


def locked(path: Path) -> set[str]:
    out: set[str] = set()
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "==" not in line:
            continue
        out.add(norm(line.split("==")[0].strip()))
    return out


def main() -> int:
    pairs = [
        ("agentd/requirements.txt", "agentd/requirements-lock.txt"),
        ("agentd/requirements-dev.txt", "agentd/requirements-dev-lock.txt"),
    ]
    failed = False
    print("== 依赖锁一致性 ==")
    for req_rel, lock_rel in pairs:
        req = direct_deps(ROOT / req_rel)
        lock = locked(ROOT / lock_rel)
        missing = sorted(req - lock)
        if not (ROOT / lock_rel).exists():
            print(f"  BAD {lock_rel} 不存在")
            failed = True
            continue
        if missing:
            print(f"  BAD {lock_rel} 缺少直接依赖：{', '.join(missing)}")
            print(f"      修法：uv pip freeze 后按分组重排 {lock_rel}")
            failed = True
        else:
            print(f"  OK  {lock_rel} 覆盖 {len(req)} 个直接依赖（共 {len(lock)} 条）")
    if failed:
        return 1
    print("结果: 锁文件与直接依赖一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
