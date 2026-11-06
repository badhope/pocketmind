<div align="center">
  <img src="assets/pocketmind-logo.png" width="440" alt="PocketMind Logo" />
  <h1>PocketMind</h1>
  <p><b>The always-on agent that actually operates your phone.</b><br/>
  One APK, no Termux · Server-enforced approval · Local-first storage</p>
  <img src="assets/badges/gate.svg" alt="Local gate" />
  <img src="assets/badges/platform.svg" alt="Platform" />
  <img src="assets/badges/tools.svg" alt="Tools" />
  <img src="assets/badges/language.svg" alt="Language" />
  <img src="assets/badges/license.svg" alt="License" />
  <br />
  <sub>Standalone APK · Native engine · Streaming chat · Approval center · Sessions &amp; backup · Plan/Act modes</sub>
</div>

**English** | [简体中文](README.zh-CN.md)

---

## What it is

PocketMind is an agent that lives **inside your Android phone**. It is not a remote
controller and it is not a chatbot wrapper. It calls the phone's own capabilities —
SMS, calls, GPS, sensors, notifications, clipboard, Wi-Fi, torch, camera, shell,
files — as structured tools, and it asks you before it does anything destructive.

Everything it remembers stays on the device. Only inference leaves it.

```
You ──▶ APK ──▶ Kotlin native engine ──▶ 37 native tools ──▶ your phone
                        │
                        └──▶ your LLM endpoint (inference only)

Full runtime (optional): Web UI / PWA ──▶ agentd (Python, Termux) ──▶ 41 tools
```

## See it work

![PocketMind demo](docs/demo/pocketmind-demo.gif)

<sub>Recorded from the running app in offline mock mode — no API key required.
Empty state → tool call → server-side approval → result → sessions → settings →
runtime statistics → scheduled jobs → dark theme → mobile viewport.
Full video: [docs/demo/pocketmind-demo.mp4](docs/demo/pocketmind-demo.mp4) (40 s).</sub>

## The parts that matter

> Scope: approvals, structured results, backups, Plan/Act, usage stats, the
> scheduler, shell and undo exist in both runtimes. The standalone APK ships 37
> native tools. Only **MCP, the Telegram gateway, the skills directory and the
> subagent** are Termux-only; unsupported capabilities are reported explicitly
> instead of faking success.

### Approval is a security boundary, not a dialog

Every tool carries a risk tier — `safe`, `write`, or `danger`. The gate is enforced
on the server, in the request path, before the tool runs. A model cannot talk its
way past it, and a compromised page cannot either.

- **Five decisions**, not two: allow once · always this session · global allow ·
  deny · always deny.
- **Deny beats allow.** A global deny list short-circuits before a card is even shown.
- **Silence means no.** Unanswered approvals are auto-denied after a configurable
  timeout (120 s by default).

<p align="center">
  <img src="assets/screenshots/02-approval.png" width="880" alt="Approval card with five decisions and a countdown" />
  <br />
  <sub>Plain-language tool name, risk badge, primary decisions separated from the
  permanent ones, and a live countdown. The model has no path around this card.</sub>
</p>

### 41 tools across the phone

> The table below describes the Termux runtime (41 = 39 built-in plus the
> `remember_fact` / `read_skill` tools registered at startup). The standalone APK
> covers most of them with 37 native tools; the difference is the "three things it
> cannot do" listed under honest boundaries.

| Group | Count | Examples |
|---|---|---|
| Phone (termux-api) | 25 | `send_sms` · `read_sms` · `make_call` · `get_location` · `take_photo` · `get_wifi_info` · `scan_wifi` · `list_sensors` · `get_clipboard` · `set_torch` · `download_file` |
| Files | 4 | read · write · list · delete, confined to `$HOME`, `~/storage`, `/sdcard` with a denylist for `.ssh`, `.gnupg`, `.agent` |
| Task orchestration | 3 | `todo_write` · `todo_clear` · `subagent` |
| Knowledge base | 3 | `kb_add` · `kb_search` · `kb_list` — FTS5 trigram, retrieved only on explicit call |
| Long-term memory | 1 | `remember_fact` — structured USER/FACT/JOURNAL files |
| Skills | 1 | `read_skill` — progressive disclosure from `SKILL.md` |
| Shell | 1 | `run_shell` — `danger` tier, always approved |
| Voice | 1 | `tts_speak` |
| Network | 2 | `web_search` · `read_url` — SSRF-hardened, `write` tier because the query leaves the device |

<p align="center">
  <img src="assets/screenshots/05-capabilities.png" width="880" alt="Capability page listing all 41 tools by group with risk badges" />
  <br />
  <sub>Every tool is browsable in the app with its risk tier — 3 of the 41 are
  marked as destructive and always require confirmation.</sub>
</p>

### Results are structured, not prose

Every tool returns exit code, stdout, stderr, a timeout flag and a duration. A
failure is a failure: the tool card turns red, and the assistant is told the call
did not succeed.

<p align="center">
  <img src="assets/screenshots/03-tool-result.png" width="880" alt="Tool result card showing structured output and completion state" />
  <br />
  <sub>Tool input on top, structured result below, duration and token cost attached
  to the reply. Copy is one tap away.</sub>
</p>

### Install it and go

Since 1.4.0 the APK ships the native engine and a prebuilt frontend: install it,
open it, configure a provider, and start chatting. No Termux, Python or Node on the
phone. Termux is only needed for the full 41-tool runtime: `termux/install.sh`
installs dependencies, `termux/start.sh` starts the service, `boot.sh` restarts it
on boot.

### Scheduled work that survives a reboot

`AlarmManager` in the APK, APScheduler under Termux; both persist jobs in SQLite.
Triggers are `interval`, `cron` (5-field, with ranges/lists/steps) and one-shot
`date`, plus a **battery condition** (`battery < 20`). Four recipes ship built in —
low-battery alert, daily summary, daily weather, lunch reminder — and any of them
instantiates with one tap.

Three things that are easy to fake and are done for real here: **it still fires
when the screen is locked** (`setExactAndAllowWhileIdle` across Doze), **alarms are
re-armed after a reboot** (`BOOT_COMPLETED`), and **the next occurrence is
scheduled on fire**. Jobs can also run immediately without waiting for the
trigger, which is the only way to know a job actually works.

<p align="center">
  <img src="assets/screenshots/07-timers.png" width="880" alt="Scheduled jobs and recipe templates" />
  <br />
  <sub>Recipes at the top, live jobs below, each with next-run time, conditions and
  a run-now button.</sub>
</p>

### Backups you can actually restore

`GET /api/export` writes every session (paged to exhaustion, never silently
truncated), structured profile memory, per-session task lists and the knowledge
base. `POST /api/import` merges it back: existing sessions are skipped, knowledge is
de-duplicated, imported jobs arrive disabled so a restore does not fire everything
at once, and a malformed file returns 400 without writing partial data.

### Two agent modes

- **Act** (default) — calls tools and acts immediately.
- **Plan** — emits an execution plan including the tools it intends to call, runs
  nothing, and waits for **Approve &amp; Execute** or **Edit Plan**.

### Remote control, both directions

- **MCP server** — exposes all 41 tools over stdio to Claude Desktop, Cline or Codex.
  This is a separate interface from the Web approval center, so the desktop host
  must supply its own confirmation; every tool description carries its risk prefix.
- **Telegram gateway** — drive the phone from a chat. Write and danger actions
  arrive as Allow/Deny buttons; only your whitelisted `chat_id` is obeyed.

### Know what it costs

Every reply shows the token usage reported by the endpoint, marked *estimated* when
the endpoint omits it. `/api/stats` aggregates requests, tool failures, LLM calls
and tokens; `/api/metrics` exposes the same counters in Prometheus format.

## Honest boundaries

This is a single-user, single-device agent. That shapes the security model and the
limits:

- **Prompt injection is not filtered.** `read_url` and `read_sms` can return text
  containing instructions. The defence is the approval gate and tool narrowing, not
  content scanning. Do not run untrusted sources in `auto` mode.
- **`run_shell` is arbitrary code execution** once approved. Read the command.
- **LAN mode** (`--lan`) means any device holding the token can call every endpoint,
  including shell. It refuses to start without a token. Do not enable it on an
  untrusted network; put HTTPS in front for public exposure (see `DEPLOY.md`).
- **Device capability varies.** SMS, calls, GPS, sensors, battery and camera need a
  real Android phone with `termux-api`. Chat, files, memory, todos and the scheduler
  work anywhere. Check [docs/capability-matrix.md](docs/capability-matrix.md) or
  `GET /api/diagnostics` on your device.
- **The APK ships a native Kotlin engine (since 1.4.0; scheduler and shell since
  1.5.0).** Core features run without Termux. MCP, the Telegram gateway and the
  skills directory stay Termux-only — see [ROADMAP.md](ROADMAP.md).
- **The native shell is not Termux.** `run_shell` runs `/system/bin/sh` as the
  app's own uid: no root, no `pkg`/`apt`, only the toybox command subset, no
  Python or Node. Diagnostics reports it as `restricted`, not `ok`.

## Install

### Option A — the APK, no Termux needed (recommended, since 1.4.0)

The APK runs the agent natively: install it, open it, pick a provider, enter
your API key, chat. No Termux, no Python, no toolchain on the phone.

```bash
bash scripts/build-apk.sh            # debug APK, verified and installable
bash scripts/build-apk.sh --release  # unsigned unless keystore.properties is present
```

What the native engine covers today: chat (SSE), all three permission modes,
server-enforced approvals, 37 phone/file/knowledge/system tools via Android
APIs, sessions, export/import, search, a real `/system/bin/sh`, and scheduled
jobs (AlarmManager with cron/interval/one-shot triggers that survive screen
lock and reboot), plus undoable file writes and deletes. What stays Termux-only
(the app says so explicitly instead of faking it): MCP, the Telegram gateway,
the skills directory and the subagent. For those, use Option B.

### Option B — on your phone with Termux (full 41-tool runtime)

```bash
# One-time setup in Termux (F-Droid build, plus Termux:API)
pkg install git python termux-api
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
bash termux/install.sh      # deps + data dir; pydantic-core compiles once (10–30 min on a phone)

# Start it
bash termux/start.sh        # http://127.0.0.1:8787
```

Open the URL, then **⚙ Settings → pick a provider → enter your API key → save**.

> Reaching it from another device on the same Wi-Fi: `bash termux/start.sh --lan`.
> A token is generated on first run and the page asks for it.

### Option C — try it locally, no phone

```bash
cd pocketmind
AGENT_HOME=/tmp/pa python3 -m agentd.main --mock --port 8787
# Open http://127.0.0.1:8787 and send "check my battery" to watch the whole pipeline
```

`--mock` needs no API key and exercises streaming, tool calls, approval and result
folding. It is what the demo GIF above was recorded with.

## Configuration

| What | Where |
|---|---|
| LLM provider, model, API key | Settings panel, or the `llm` block in `$AGENT_HOME/config.json` |
| Permission mode (`auto` / `approve` / `chat`) | Settings → Agent mode |
| Approval timeout | `server.approval_timeout` (default 120 s) |
| Access token | Generated on first `--lan` start; `python -m agentd.main --clear-token` resets it |
| Data directory | `AGENT_HOME` (default `~/.agent/termux-agent`) |

Credentials never leave the device except as part of a request to the endpoint you
configured. Every API response masks `api_key`, `server.token` and `server.tg_token`;
exports never contain them.

## Repository layout

| Path | Contents |
|---|---|
| `agentd/` | The backend: FastAPI app, agent loop, tools, storage, scheduler |
| `agentd/web/` | React + TypeScript frontend (Vite). The built `dist/` is committed because phones build nothing |
| `termux/` | Phone-side scripts: `install.sh`, `start.sh`, `boot.sh`, `uninstall.sh` |
| `termux/apk-shell/` | Android WebView shell (zero third-party dependencies) |
| `smoke/` | Regression suites, all driven by `tests/run_all.sh` |
| `config/recipes.json` | Preset job templates |
| `docs/` | API reference, architecture, capability matrix, audits, versioning policy |
| `website/` | Self-contained landing page |

## Verify it yourself

One command runs the whole gate. It is the source of truth; CI only installs
dependencies and calls it.

```bash
bash tests/run_all.sh              # backend + frontend
bash tests/run_all.sh --backend    # backend only
bash tests/run_all.sh --with-e2e   # add Playwright (install browsers once)
```

Exit code is `0` only when everything passes, and failures are listed in the
summary. The gate covers every Python suite plus `compileall`, `ruff` and a deploy
self-check; frontend typecheck, unit tests, the production build and a
**committed-`dist/` freshness check** (the committed bundle must match the current
source, since phones never build it).

Per-suite detail: [smoke/README.md](smoke/README.md).
API reference: [docs/API.md](docs/API.md).
Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Project governance

- [CHANGELOG.md](CHANGELOG.md) — every user-visible change, Keep a Changelog format
- [ROADMAP.md](ROADMAP.md) — what ships now, what is next, what is explicitly not planned
- [docs/VERSIONING.md](docs/VERSIONING.md) — SemVer policy, the three single-source
  version locations, the release checklist, branch and tag rules
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to propose a change and how a release happens
- [SECURITY.md](SECURITY.md) — the security model and how to report a vulnerability
- [docs/COMPETITORS.md](docs/COMPETITORS.md) — comparison with sources, including
  where this project is weaker

## License

Apache-2.0 — see [LICENSE](LICENSE).
