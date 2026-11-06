# Roadmap

Status legend: **shipped** · **in progress** · **planned** · **exploring**

This roadmap states what is real today and what is deliberately not built yet.
Items move into [CHANGELOG.md](CHANGELOG.md) when they ship.

## Now — 1.5.x (current)

| Item | Status | Notes |
|---|---|---|
| **Kotlin native engine (single-APK core)** | shipped | In-process HTTP/SSE + SQLite + agent loop + approvals + 37 native tools; Termux no longer required. |
| Server-enforced approval for Web and Telegram agent paths | shipped | Three-level grants, global deny list, 120 s auto-deny (native + Python). |
| 41 tools in the Python runtime / 37 in the native runtime | shipped | `safe` / `write` / `danger` risk tiers preserved. Native-only gaps (`set_wifi`, `tts_offline`, `subagent`) are platform limits, not pending work — see below. |
| Native `run_shell` | shipped (1.5.0) | `/system/bin/sh` as the app uid: no root, no `pkg`/`apt`, toybox subset only. Reported as `restricted` in diagnostics. |
| Native scheduler: jobs + recipes | shipped (1.5.0) | AlarmManager (`setExactAndAllowWhileIdle`, inexact fallback), 5-field cron / interval / one-shot, `battery < N` conditions, re-arm on fire, `BOOT_COMPLETED` replay. |
| Checkpoint / undo for native file tools | shipped (1.5.0) | `write_file` and `delete_file` back up first; `/api/undo` restores; repeat undo rejected. |
| Boot recovery | shipped (1.5.0) | `BootReceiver` restarts the foreground engine and re-arms every alarm after a reboot or app update. |
| Local-first storage | shipped | Python: SQLite at 0600/0700. Native: app-private SQLite (schema v2 with in-place upgrade). |
| SSE streaming chat with per-message token usage | shipped | Both runtimes. |
| Plan / Act agent modes | shipped | Both runtimes (native plan-approve via resend). |
| Backup export / import | shipped | `format_version` stamped on both runtimes; bundles are cross-compatible. |
| MCP stdio server / Telegram gateway | Python only | Native engine degrades explicitly. |
| First-run onboarding wizard | shipped | Provider preset → connectivity-tested API key → permission walkthrough. |
| Session search across tool output | shipped | `matched` kinds on both runtimes (native: LIKE over tool rows). |
| Structured tool result viewer | shipped | List-shaped results render as tables/cards. |
| `mypy` gate | shipped | Zero errors, enforced by `tests/run_all.sh`. |
| Full local gate in one command | shipped | `bash tests/run_all.sh` + `gradle testDebugUnitTest lintDebug assembleDebug` (41 Robolectric tests). |

## Next — 1.6.x

| Item | Status | Notes |
|---|---|---|
| Runtime permission flow for SMS/call/location/camera tools | planned | Tools currently return `permission_required` with a hint; wire guided grant requests. |
| MCP client/server over the native engine | exploring | stdio is Termux-bound; evaluate in-app socket transport. |
| Skills directory in the native engine | exploring | Native has no skill loader; currently returns an empty list. |

## Not planned — platform limits

These are not backlog items. They cannot be done as a normal Android app, and
listing them as "pending" would be dishonest.

| Item | Why not | What to do instead |
|---|---|---|
| `set_wifi` in the native engine | Android 10 (API 29) removed the ability for third-party apps to toggle WiFi; `WifiManager.setWifiEnabled` returns false and `NETWORK_SETTINGS` is a system permission. | Use the system quick-settings tile, or the Termux build (which shells out to `termux-wifi-enable`). |
| `tts_offline` (local Chinese TTS model) | Needs a sherpa-onnx model of several hundred MB; the APK is ~2.8 MB and the promise is a single small install. | Native `tts_speak` uses the system TTS engine; the Termux build ships the offline model. |
| `subagent` | Runs a second full agent loop with a narrow toolset; it is a context-saving feature, not a capability the phone lacks. | Not ported; the native engine runs the main loop only. |
| Root-level shell | `run_shell` runs as the app uid by design; escalating would need a rooted device or a system app. | Use the `run_shell` you have, or a rooted Termux build. |

## Later — beyond 1.3

| Item | Status | Notes |
|---|---|---|
| Local model runtime (llama.cpp / MLC) | exploring | Would remove the cloud-inference dependency for offline use. Not started. |
| Multi-device sync | exploring | Conflicts with the local-first promise unless end-to-end encrypted. Design not started. |
| Desktop companion app | exploring | The MCP server already covers most remote-control needs. |
| iOS | not planned | Termux and `termux-api` have no iOS equivalent; the tool layer would need a full rewrite. |

## Known verification gaps

These are honest statements of what has *not* been proven, kept here so nobody
mistakes a green local gate for full coverage.

| Gap | Why it exists | What would close it |
|---|---|---|
| **The native scheduler has not fired a real alarm on a physical device.** The cron parser, job CRUD, run-now delivery and undo round-trip are covered by Robolectric tests, but `AlarmManager` callbacks, Doze behaviour and `BOOT_COMPLETED` replay are only exercised on real hardware. | The development host is Windows; no Linux runner or working Docker daemon was available. | One green CI run on `ubuntu-latest`, or `bash tests/run_all.sh` inside a Linux container. |
| **No real-device APK test — still the top gap.** The native engine passes 41 Robolectric tests (engine auth, routing, storage, agent loop, approvals, jobs, undo, shell) and the APK builds with lint clean, but no physical device has run the full bootstrap → LLM → tool → approval flow. | The host is a KVM guest without nested virtualisation, so AVDs never come up. | Install v1.4.0 on a physical Android device; exercise onboarding, chat with tool calls, and approval cards. |
| **Playwright E2E is opt-in.** | Browsers are a large download; the gate must stay runnable on a phone. | Run `--with-e2e` on CI as a separate job. |

## Explicitly not planned

- **Play Store distribution.** The app requires `termux-api` permissions and
  arbitrary shell execution; both conflict with store policy.
- **Multi-tenant hosting.** The security model assumes a single user on a single
  device. Running it as a shared service is unsupported.
- **Bypassing approval from the model side.** Approval is enforced server-side by
  design; any change that lets a model self-approve will be rejected.

## How to propose a roadmap change

Open an issue using the feature request template. Describe the user problem, not
the implementation. Roadmap items that do not map to a real user problem are
unlikely to be scheduled.
