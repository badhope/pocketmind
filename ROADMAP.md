# Roadmap

Status legend: **shipped** · **in progress** · **planned** · **exploring**

This roadmap states what is real today and what is deliberately not built yet.
Items move into [CHANGELOG.md](CHANGELOG.md) when they ship.

## Now — 1.4.x (current)

| Item | Status | Notes |
|---|---|---|
| **Kotlin native engine (single-APK core)** | shipped | In-process HTTP/SSE + SQLite + agent loop + approvals + 26 native tools; Termux no longer required. |
| Server-enforced approval for Web and Telegram agent paths | shipped | Three-level grants, global deny list, 120 s auto-deny (native + Python). |
| 41 tools in the Python runtime / 26 tools in the native runtime | shipped | `safe` / `write` / `danger` risk tiers preserved; native `run_shell` degrades honestly. |
| Local-first storage | shipped | Python: SQLite at 0600/0700. Native: app-private SQLite. |
| SSE streaming chat with per-message token usage | shipped | Both runtimes. |
| Plan / Act agent modes | shipped | Both runtimes (native plan-approve via resend). |
| Scheduled jobs + recipes | shipped (Python) / **not native** | Native engine returns empty sets; Android scheduler is a 1.5 slice. |
| Backup export / import | shipped | `format_version` stamped on both runtimes; bundles are cross-compatible. |
| MCP stdio server / Telegram gateway | Python only | Native engine degrades explicitly. |
| First-run onboarding wizard | shipped | Provider preset → connectivity-tested API key → permission walkthrough. |
| Session search across tool output | shipped | `matched` kinds on both runtimes (native: LIKE over tool rows). |
| Structured tool result viewer | shipped | List-shaped results render as tables/cards. |
| `mypy` gate | shipped | Zero errors, enforced by `tests/run_all.sh`. |
| Full local gate in one command | shipped | `bash tests/run_all.sh` + `gradle testDebugUnitTest` (34 Robolectric tests). |

## Next — 1.5.x

| Item | Status | Notes |
|---|---|---|
| Android job scheduler (WorkManager/AlarmManager) in the native engine | planned | Restore jobs/recipes parity; battery-condition triggers map to WorkManager constraints. |
| Runtime permission flow for SMS/call/location/camera tools | planned | Tools currently return `permission_required` with a hint; wire guided grant requests. |
| Checkpoint/undo for native file tools | planned | Mirror the Python checkpoint store. |
| MCP client/server over the native engine | exploring | stdio is Termux-bound; evaluate in-app socket transport. |

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
| **No real-device APK test — now the top gap.** The native engine passes 34 Robolectric tests (engine auth, routing, storage, agent loop, approvals) and the APK builds with lint clean, but no physical device has run the full bootstrap → LLM → tool → approval flow. | The host is a KVM guest without nested virtualisation, so AVDs never come up. | Install v1.4.0 on a physical Android device; exercise onboarding, chat with tool calls, and approval cards. |
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
