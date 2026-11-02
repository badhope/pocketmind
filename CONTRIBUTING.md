# Contributing to PocketMind

Thanks for taking the time to contribute. This project is a single-user, on-device
agent; the bar for changes is **"does this make the phone work better for the person
holding it"**, not "does this add a feature".

[简体中文](CONTRIBUTING.zh-CN.md) · [English](CONTRIBUTING.md)

## Before you start

- **Open an issue first for anything larger than a typo.** A short description of the
  problem you hit is worth more than a 500-line PR that solves a problem nobody had.
- **Search existing issues and the ROADMAP** before proposing a feature. Items already
  listed as "not planned" need a strong argument to revisit.
- **Never commit secrets.** API keys live in `$AGENT_HOME/config.json`, which is
  gitignored. `keystore.properties` is gitignored for the same reason.

## Development setup

```bash
# Backend (Python 3.11+; 3.12 is what CI uses)
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r agentd/requirements.txt -r agentd/requirements-dev.txt

# termux-api stubs so phone tools resolve on a desktop
bash tests/setup_faketermux.sh
export PATH=/tmp/faketermux:$PATH

# Offline run — no API key, exercises the full pipeline
AGENT_HOME=/tmp/pa python -m agentd.main --mock --port 8787

# Frontend
cd agentd/web && npm ci
npm run dev          # http://localhost:5173, proxies /api to :8787
```

If you change anything under `agentd/web/src`, you must rebuild the committed bundle:

```bash
cd agentd/web && npm run build
```

Phones never run a build step, so `dist/` is part of the source of truth and the gate
fails if it is stale.

## The gate

One command is the authority. CI only installs dependencies and calls it.

```bash
bash tests/run_all.sh              # backend + frontend
bash tests/run_all.sh --backend    # backend only
bash tests/run_all.sh --with-e2e   # add Playwright (npx playwright install chromium once)
```

Exit code `0` means everything passed. The summary at the end lists every failure by
name. Do not open a PR with a red gate and a note saying "the failing suite is
unrelated" — either fix it or explain in the issue why it should be skipped.

### What the gate covers

| Layer | Checks |
|---|---|
| Backend | Every `smoke/` suite, `compileall`, `ruff`, a deploy-path self-check, and the version-consistency gate |
| Frontend | `tsc -b --noEmit`, Vitest unit tests, production build, and a committed-`dist/` freshness check |
| Android | Robolectric unit tests, `lintDebug` (0 errors), debug + release APK build and verification |
| Optional | Playwright E2E (`--with-e2e`), dependency audit (`--with-dep-audit`), mutation testing (`--with-mutation`) |

## Code conventions

- **Python**: `ruff` is the linter, and the gate runs it. Type hints on public
  functions. No new runtime dependency without a reason in the PR description —
  every dependency is something a phone has to install.
- **TypeScript**: `noUnusedLocals` / `noUnusedParameters` are on. Components stay
  functional; state lives in the Zustand store, not in ad-hoc module globals.
- **Comments explain *why*, not *what*.** The codebase has a lot of "this used to be
  X and it broke because Y" comments. Keep that style: the next person needs the
  history, not a restatement of the code.
- **Error handling is layered.** A failing sub-feature must degrade, not take down the
  request. Look at how `agentd/main.py` wraps optional integrations before adding one.
- **Chinese UI strings are fine** (the product ships in Chinese); code identifiers,
  commit messages and docs are English.

## Commit messages

Conventional Commits, imperative mood:

```
fix(approval): deny before showing the card, not after
feat(tools): add set_alarm
docs(readme): correct the install time
```

A commit should be reviewable on its own. If you touched a behaviour, say what broke
and how you verified the fix — "tested" is not verification, a named suite output is.

## Pull requests

- One logical change per PR.
- Describe the problem, not just the solution.
- Include the gate output (the summary line is enough).
- If you changed the API, update `docs/API.md`.
- If you changed user-visible behaviour, update `CHANGELOG.md` under `[Unreleased]`.
- If you changed a screenshot-visible screen, regenerate the screenshots.

## Reporting bugs

Include:

1. What you expected and what happened.
2. The version — `GET /api/health` returns it, and the APK version is in
   `adb shell dumpsys package com.pocket.agent | grep versionName`.
3. Device, Android version, and whether `termux-api` is installed.
4. Whether it reproduces in `--mock` mode. This separates "the agent logic is wrong"
   from "the device integration is wrong" and saves a lot of back-and-forth.

## Security issues

Do **not** open a public issue. See [SECURITY.md](SECURITY.md).

## Release process (maintainers)

Full rules are in [docs/VERSIONING.md](docs/VERSIONING.md). The short version:

1. Bump the version in `agentd/main.py`, `agentd/web/package.json`,
   `termux/apk-shell/gradle.properties` and the root `VERSION`; rebuild `dist/`.
2. Move `[Unreleased]` in `CHANGELOG.md` into a dated section.
3. `bash tests/run_all.sh` must be green.
4. `git tag -a vX.Y.Z -m "vX.Y.Z"` and push.
5. `bash scripts/sync-remotes.sh --tags` to push `main` and tags everywhere and verify
   each remote resolves to the same commit.
6. `bash scripts/release.sh X.Y.Z` (use `--dry-run` first) to publish release notes.

## Branch rules

`main` is the only long-lived branch and the default everywhere. Feature work happens
on short-lived `feat/…` / `fix/…` branches, merged and deleted. No `develop`, no
per-platform branches, no force-push to `main` except on mirrors whose history
diverged (handled by `--force-with-lease` in the sync script).

## Code of conduct

Participation means agreeing to the [Code of Conduct](CODE_OF_CONDUCT.md).
