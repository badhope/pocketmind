#!/usr/bin/env python3
"""版本一致性门禁：三处版本号必须相同，否则用户无法判断手机上装的是哪一版。

真实问题（2026-10-29 审计）：APK 壳 versionName 停在 1.0.0，而后端与前端都是
1.2.0 —— 装到手机上的包和服务的版本对不上，出问题无法定位，发布页也无法说明
"这个 APK 对应哪个后端"。

检查三处：
  - agentd/main.py 的 VERSION
  - agentd/web/package.json 的 version
  - termux/apk-shell/gradle.properties 的 POCKET_VERSION_NAME

用法：python tests/check_version.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")


def main() -> int:
    versions: dict[str, str] = {}

    main_py = ROOT / "agentd" / "main.py"
    m = re.search(r'^VERSION\s*=\s*"([^"]+)"', main_py.read_text(encoding="utf-8"), re.M)
    versions["agentd/main.py"] = m.group(1) if m else "<missing>"

    pkg = ROOT / "agentd" / "web" / "package.json"
    versions["agentd/web/package.json"] = json.loads(pkg.read_text(encoding="utf-8")).get(
        "version", "<missing>"
    )

    props = ROOT / "termux" / "apk-shell" / "gradle.properties"
    m2 = re.search(r"^POCKET_VERSION_NAME=(.+)$", props.read_text(encoding="utf-8"), re.M)
    versions["termux/apk-shell/gradle.properties"] = m2.group(1).strip() if m2 else "<missing>"

    print("== 版本一致性 ==")
    for k, v in versions.items():
        ok = bool(SEMVER.match(v))
        print(f"  {'OK ' if ok else 'BAD'} {k}: {v}")

    failed = []
    for k, v in versions.items():
        if not SEMVER.match(v):
            failed.append(f"{k} 不是合法 semver: {v}")

    unique = set(versions.values())
    if len(unique) != 1:
        detail = "；".join(f"{k}={v}" for k, v in versions.items())
        failed.append(f"三处版本号不一致（{detail}）")

    if failed:
        print("\n失败：")
        for f in failed:
            print(f"  - {f}")
        return 1
    print(f"\n结果: 三处版本一致（{versions['agentd/main.py']}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
