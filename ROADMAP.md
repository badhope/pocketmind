# Roadmap

Status legend: **shipped** · **in progress** · **planned** · **exploring**

This roadmap states what is real today and what is deliberately not built yet.
Items move into [CHANGELOG.md](CHANGELOG.md) when they ship.

## Now — 1.3.x (current)

| Item | Status | Notes |
|---|---|---|
| Server-enforced approval for Web and Telegram agent paths | shipped | Three-level grants, global deny list, 120 s auto-deny. |
| 41 tools (39 built-in + 2 runtime) | shipped | `safe` / `write` / `danger` risk tiers. |
| Local-first SQLite storage with 0600/0700 permissions | shipped | Sessions, messages, memory, jobs, checkpoints. |
| SSE streaming chat with per-message token usage | shipped | Falls back gracefully when the endpoint omits `usage`. |
| Plan / Act agent modes | shipped | Plan emits a `plan` event, execution requires explicit approval. |
| Scheduled jobs + recipes | shipped | Interval, cron, and battery-condition triggers. |
| Backup export / import with pagination and de-duplication | shipped | Export is never silently truncated; `format_version` since 1.3.0. |
| MCP stdio server exposing all tools | shipped | Desktop hosts must supply their own confirmation. |
| Optional Telegram gateway | shipped | Whitelisted `chat_id` only. |
| APK WebView shell with Termux deployment | shipped | Installable artifact produced by CI. |
| First-run onboarding wizard | shipped | Provider preset → connectivity-tested API key → permission walkthrough. |
| Session search across tool output | shipped | Tool results and tool-call arguments are indexed; hits carry a `matched` kind. |
| Structured tool result viewer | shipped | List-shaped results render as tables/cards instead of raw JSON. |
| Export format versioning | shipped | `format_version` stamped on export; importers reject future versions. |
| `mypy` gate | shipped | Zero errors, enforced by `tests/run_all.sh`. |
| Full local gate in one command | shipped | `bash tests/run_all.sh`. |

## Next — 1.4.x

| Item | Status | Notes |
|---|---|---|
| Kotlin native runtime (in-process HTTP service) | exploring | Slice 1–2 archived under `docs/history/kotlin-migration-slice2/`. The spike proves a trusted bootstrap path and a fail-closed API surface, but it does **not** implement any business endpoint. It is not wired into the shipped APK. |

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
| **CI has never actually run on Linux for this revision.** The gate is green on Windows; two suites (`m5` permission bits, `p3` subprocess reaping) skip themselves on non-POSIX and are asserted only on Linux. | The development host is Windows; no Linux runner or working Docker daemon was available. | One green CI run on `ubuntu-latest`, or `bash tests/run_all.sh` inside a Linux container. |
| **No real-device APK test.** The APK builds, passes lint, and its manifest/version are verified statically; Robolectric covers the Activity/Service logic. | The host is a KVM guest without nested virtualisation, so AVDs never come up. | Install on a physical Android device and exercise the WebView bootstrap, foreground service and clipboard bridge. |
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
