# Versioning & Release Policy

PocketMind follows [Semantic Versioning 2.0.0](https://semver.org/).

```
MAJOR.MINOR.PATCH
```

| Component | Increment when | Examples |
|---|---|---|
| **MAJOR** | A change breaks the documented HTTP API, the on-disk data format, or the installation flow in a way that requires user action. | Removing an endpoint, renaming a config key, changing the SQLite schema without migration. |
| **MINOR** | New capability that is backward compatible. | A new tool, a new endpoint, a new UI page, a new integration. |
| **PATCH** | Backward-compatible bug fix, security fix, documentation-only correction. | Fixing an approval bypass, correcting a timezone bug, fixing a broken link. |

## Version is single-sourced

The authoritative version lives in **three** files that must always agree:

| File | Field | Consumed by |
|---|---|---|
| `agentd/main.py` | `VERSION` | The backend process; exposed at `/api/health` and `/api/export`. |
| `agentd/web/package.json` | `version` | The frontend bundle metadata. |
| `termux/apk-shell/gradle.properties` | `POCKET_VERSION_NAME` / `POCKET_VERSION_CODE` | The Android APK `versionName` / `versionCode`. |

`tests/check_version.py` is the gate that enforces this. It runs as part of
`bash tests/run_all.sh` and fails the build when any of the three drift apart or
stop being valid SemVer.

`VERSION` at the repository root mirrors the same value for tooling that does not
want to parse Python or JSON. It is **not** a fourth source of truth — the gate
checks the three above and the release script compares `VERSION` against them.

## Release cadence

There is no fixed calendar. A release happens when `main` has accumulated
user-visible changes and the full gate is green. Security fixes ship immediately
as a PATCH release.

## Release checklist

Run these in order. Do not skip steps.

1. **Confirm the gate is green on a clean tree**
   ```bash
   bash tests/run_all.sh
   ```
   Exit code must be `0`. This covers every backend suite, `compileall`, `ruff`,
   the deploy self-check, frontend typecheck, 69 Vitest unit tests, the production
   build, and the committed-`dist/` freshness check.

2. **Bump the version in all three files** (plus `VERSION` at the root)
   ```bash
   # agentd/main.py            VERSION = "X.Y.Z"
   # agentd/web/package.json   "version": "X.Y.Z"
   # termux/apk-shell/gradle.properties
   #     POCKET_VERSION_NAME=X.Y.Z
   #     POCKET_VERSION_CODE=<major*10000 + minor*100 + patch>
   ```
   Then regenerate the frontend bundle so `dist/` matches:
   ```bash
   cd agentd/web && npm run build
   ```

3. **Write the CHANGELOG entry**
   Move everything under `## [Unreleased]` into a new
   `## [X.Y.Z] - YYYY-MM-DD` section. Group under `### Added`, `### Changed`,
   `### Fixed`, `### Security`, `### Documentation`, `### Removed`.

4. **Verify the version gate**
   ```bash
   python tests/check_version.py
   ```

5. **Commit and tag**
   ```bash
   git add -A && git commit -m "release: vX.Y.Z"
   git tag -a vX.Y.Z -m "vX.Y.Z"
   ```

6. **Push to the primary remote and mirrors**
   ```bash
   bash scripts/sync-remotes.sh --tags
   ```
   This pushes `main` and all tags to every configured remote and verifies that
   each remote's `main` resolves to the same commit as the local `HEAD`.

7. **Publish the release notes**
   ```bash
   bash scripts/release.sh X.Y.Z --dry-run   # inspect the plan first
   bash scripts/release.sh X.Y.Z
   ```

## Branches

`main` is the only long-lived branch. It is the default branch on every remote
and must always be green.

- Feature work happens on short-lived branches (`feat/…`, `fix/…`) that are
  merged into `main` and deleted immediately after.
- There are no `develop`, `release/*`, or per-platform branches. Mirrors carry
  `main` only, at the same commit.
- Force-pushing `main` is only acceptable on mirrors whose history diverged for
  historical reasons; `scripts/sync-remotes.sh` uses `--force-with-lease` for
  exactly those and plain fast-forward for the rest.

## Tags

Tags follow `vX.Y.Z`. `vX.Y.Z` is immutable: never move or delete a published
tag. The repository also carries a legacy `latest` tag from an early release and
should not gain more aliases — tooling reads `v*` only.

## What the version means in practice

When a user reports a problem, the first question is *which version*. The version
returned by `GET /api/health`, the version printed in the UI footer, and the
`versionName` reported by `adb shell dumpsys package com.pocket.agent` must all
agree. That is why the gate exists and why there is exactly one place per
artifact that defines it.
