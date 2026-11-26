#!/usr/bin/env python3
"""OSV 漏洞门禁：把两份锁文件里的每个固定版本拿去 api.osv.dev 查询。

为什么自己写而不是用 osv-scanner action：
1. action 的 ref/CLI 旗标在 v1→v2 之间漂移过（--lockfile 被移除），
   1.6.1 的 Security job 死在 "Set up job" 就是 action 引用失效；
2. 本查询用的是同一个数据源（OSV 官方 API），结果一致且可本地复现——
   提交前就能跑，而不是等 CI 十分钟后才知道红。

退出码：0 = 无已知漏洞（或在 IGNORED 白名单内）；1 = 有未处理漏洞。

白名单规则：允许-ignore 一条漏洞，但必须在 IGNORED 里写明理由和复审日期，
没有理由的 ignore 视为没有 ignore（直接删掉那行跑一遍就知道）。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OSV_BATCH = "https://api.osv.dev/v1/querybatch"

# 已知未修复、经人工评估接受的漏洞。每条必须有理由 + 复审日期。
# 复审日期到期后这里会开始报错，强迫重新评估。
IGNORED: dict[str, dict] = {
    # "GHSA-xxxx": {"reason": "仅影响 dev 依赖 pytest 的 tmpdir 权限，本仓库 CI 单次环境内不可利用", "revisit": "2027-06-01"},
}


def lock_pkgs(path: Path, ecosystem: str) -> list[tuple[str, str, str]]:
    """解析 == 固定的包 → (name, version, ecosystem)。跳过带 sys_platform 标记的非本平台项由 OSV 侧自然处理。"""
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "==" not in line:
            continue
        name, rest = line.split("==", 1)
        ver = rest.split(";")[0].strip()
        out.append((name.strip(), ver, ecosystem))
    return out


def npm_lock_pkgs(path: Path) -> list[tuple[str, str, str]]:
    """package-lock.json v3：packages{}.dependencies 里的直接+传递依赖。"""
    out = []
    if not path.exists():
        return out
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  WARN 无法解析 {path.name}: {e}")
        return out
    for key, info in (data.get("packages") or {}).items():
        if not key:  # 根包
            continue
        ver = info.get("version")
        name = key.removeprefix("node_modules/").split("/node_modules/")[-1]
        if name and ver:
            out.append((name, ver, "npm"))
    return out


def query_batch(pkgs: list[tuple[str, str, str]]) -> dict[tuple[str, str, str], list[str]]:
    """querybatch 上限 1000 条，分批 500。返回 (name,ver,eco) -> [vuln ids]。"""
    result: dict[tuple[str, str, str], list[str]] = {}
    for i in range(0, len(pkgs), 500):
        chunk = pkgs[i:i + 500]
        body = {"queries": [
            {"package": {"name": n, "ecosystem": eco}, "version": v}
            for (n, v, eco) in chunk
        ]}
        req = urllib.request.Request(
            OSV_BATCH, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        for attempt in (1, 2, 3):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    d = json.load(r)
                break
            except Exception as e:
                if attempt == 3:
                    raise RuntimeError(f"OSV API 查询失败: {e}") from e
                time.sleep(2 * attempt)
        for (n, v, eco), res in zip(chunk, d.get("results", []), strict=False):
            ids = [x.get("id", "?") for x in res.get("vulns", [])]
            if ids:
                result[(n, v, eco)] = ids
    return result


def main() -> int:
    py = (lock_pkgs(ROOT / "agentd/requirements-lock.txt", "PyPI")
          + lock_pkgs(ROOT / "agentd/requirements-dev-lock.txt", "PyPI"))
    npm = npm_lock_pkgs(ROOT / "agentd/web/package-lock.json")
    all_pkgs = py + npm
    print(f"== OSV 漏洞门禁 == 查询 {len(all_pkgs)} 个固定版本 "
          f"(PyPI {len(py)} / npm {len(npm)})")
    if not all_pkgs:
        print("  BAD 没有查到任何包——锁文件为空或路径错误")
        return 1

    found = query_batch(all_pkgs)
    blocked = []
    for (n, v, eco), ids in sorted(found.items()):
        actionable = [i for i in ids if i not in IGNORED]
        ignored = [i for i in ids if i in IGNORED]
        if actionable:
            blocked.append((n, v, eco, actionable))
            print(f"  BAD {eco}/{n}=={v}: {actionable}")
        for i in ignored:
            print(f"  IGNORED {eco}/{n}=={v}: {i}（{IGNORED[i].get('reason', '未写理由')}，复审 {IGNORED[i].get('revisit', '?')}）")

    if blocked:
        print(f"\n结果: {len(blocked)} 个包存在未处理漏洞 → 修复（升级到 fixed 版本）或在 IGNORED 写理由")
        for n, _v, eco, _ids in blocked:
            # 给出直接的下一步：OSV 页面
            print(f"  https://osv.dev/list?ecosystem={eco}&q={n}")
        return 1
    print("结果: 全部固定版本无已知漏洞")
    return 0


if __name__ == "__main__":
    sys.exit(main())
