# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0.0](https://semver.org/).
Release mechanics are documented in [docs/VERSIONING.md](docs/VERSIONING.md).

> Entries from 1.2.1 onward are written in English. Entries for 1.2.0 and earlier
> are preserved verbatim in their original language as a historical record.

## [Unreleased]

## [1.6.2] - 2026-11-07

### Fixed

- **Android CI: the runner image no longer ships an Android SDK.** The current
  `ubuntu-24.04` runner image contains no Android tooling at all (its README
  lists nothing under "android"), so neither `sdkmanager` on PATH nor
  `android-actions/setup-android@v3` can work — the latter had failed on all 16
  runs. CI, Release and the CodeQL build now install `cmdline-tools`
  (pinned `commandlinetools-linux-11076708`) from Google's official source and
  add `platforms;android-35` + `build-tools;35.0.0` themselves, then export
  `ANDROID_HOME` via `$GITHUB_ENV`.
- **Security: the OSV job never even started** — its action ref
  (`google/osv-scanner-action/osv-scanner-action@v2`) failed at "Set up job",
  and the v1→v2 CLI had dropped the `--lockfile` flag anyway. Replaced with
  `tests/check_osv.py`, which queries the same OSV dataset directly over its
  official API for every pinned version in both lock files (PyPI + npm, 272
  entries), needs no third-party action, and is runnable locally.
- **Three real vulnerabilities found by that gate and fixed:**
  `pytest 8.4.2 → 9.0.3` (GHSA-6w46-j5rx-g56g, dev dep; the `pytest>=8,<9` cap
  was dropped), `dompurify 3.4.16 → 3.4.17` (3 GHSAs incl. SAFE_FOR_TEMPLATES
  bypass), `undici 8.11.2 → 8.12.4` (8 GHSAs incl. TLS validation bypass; via
  jsdom ^8.10.2 so a plain `npm update` sufficed). The OSV gate now reports
  **zero known vulnerabilities across 272 pinned versions**, locally verified.

## [1.6.1] - 2026-11-07

### Fixed

- **CI on Linux was red for every commit in history — now diagnosed and fixed.**
  Discovered after push while closing out: every `CI` run on GitHub `main` had
  failed, including v1.6.0's. Two independent root causes, both in environment
  setup rather than application code:
  - `requirements-lock.txt` was frozen on Windows and pinned `pywin32==312`
    (Windows-only, no Linux distribution). `pip install` on Linux failed at
    "Install dependencies (locked)". The pin now carries
    `; sys_platform == "win32"`, so Windows stays pinned and Linux skips it.
  - `android-actions/setup-android@v3` failed on every run against the current
    `ubuntu-latest` image. Both CI and Release now use the runner's preinstalled
    SDK directly (`sdkmanager --licenses` + install the three needed packages).
  - Latent third bug caught in passing: the coverage gate needs the `coverage`
    module, but `pytest-cov`/`coverage` were never in `requirements-dev.txt` or
    the dev lock — the gate would have failed right after the install step was
    fixed. Both added; the lock now installs and the full backend gate passes
    from a clean venv built **only** from the lock files (52/52).
- `security.yml`: osv-scanner v2 removed the `--lockfile` flag; scan targets are
  now positional, matching the v2 CLI.
- Local verification of this release: fresh venv from locks → install clean →
  backend gate 52/52 → `check_version` and `check_locks` pass.

## [1.6.0] - 2026-11-07

### Added

- **Context budget for the native engine** — `context.budget_tokens` now actually
  works on Android. Tool results are truncated before entering the context
  (6000 chars by default, scaled to 15% of the budget with a 60k hard cap) while
  the database keeps the full text. History is trimmed by token estimate
  (4 chars/token), dropping oldest-first but always keeping the system prompt
  and the current user message, and never splitting an `assistant(tool_calls)` /
  `tool` pair (that would 400 on OpenAI-compatible endpoints).
  Without this, one `run_shell` producing 30 KB cost ~7.5k tokens **per turn**
  for the rest of the session.
- **API key encryption via Android Keystore.** `config.json` now stores the key
  as `v1:` + AES-256-GCM ciphertext with the key material held in the TEE.
  In-memory values stay plaintext so callers are unaffected; `/api/diagnostics`
  reports `secret_storage: keystore-encrypted | plaintext-fallback` honestly
  instead of pretending encryption succeeded.
- **Approval audit log** — `files/audit/approvals.jsonl` (append-only, 1 MB
  rotation) records time / session / tool / risk / decision for every approval,
  including timeouts. Deliberately excludes tool arguments (they may contain
  clipboard contents or phone numbers). Readable at `GET /api/approvals/audit`.
  The project calls approvals its security boundary; now that claim is
  auditable instead of living in memory.
- **Scheduled jobs run headless.** When a job triggers a tool that needs
  approval there is nobody to approve it, so the loop used to block for the
  full 120 s timeout and then silently deny. It now denies immediately with an
  explicit reason the user can act on.
- **Cascade delete for sessions** — deleting a session now also removes its
  archives, its jobs, its approval allow-list entries, and the checkpoint backup
  files on disk. Previously the session vanished but the backups (and jobs that
  would recreate it) stayed.
- **Coverage gate** (`tests/check_coverage.py`): line coverage measured at
  **46.4%**, floor set to 40% and ratcheted (raise-only). Every comparable
  project surveyed has no coverage gate at all.
- **Dependency locks** (`agentd/requirements-lock.txt`,
  `requirements-dev-lock.txt`) plus `tests/check_locks.py`, which fails when a
  direct dependency is added to `requirements.txt` without re-freezing. CI now
  installs from the lock files.
- **Release workflow** (`.github/workflows/release.yml`): pushing a `v*` tag
  builds, tests, lints, verifies `versionName` matches the tag, runs
  `apksigner verify` + `zipalign -c`, and publishes the APK with release notes.
  Without keystore secrets it produces an explicitly-labelled unsigned build.
- **Security workflow** (`.github/workflows/security.yml`): OSV dependency
  scan, gitleaks full-history secret scan (runs on PRs too), and CodeQL for
  Python and Kotlin/Java.
- **Pre-commit hook** (`scripts/install-hooks.sh`): secret-shaped strings,
  ruff, `tsc`, and version consistency — the fast checks, so CI failures
  aren't discovered a round-trip later.
- **Local DB migration tests** — v1 schema → current, asserting old sessions and
  messages survive and the new tables are usable.

### Changed

- Bug and feature issue templates converted to GitHub Issue Forms (required
  fields: runtime, version, device, repro steps).
- Added `CODEOWNERS` (security-sensitive paths pinned) and a root `AGENTS.md`
  with the non-negotiable rules for both AI assistants and humans.
- `docs/COMPETITORS.md` corrected: it claimed 59% line coverage against a 90%
  red line; the measured value was never that, and no gate existed. Now states
  the measured number and points at the enforced floor.

### Fixed

- Dependabot now also covers Gradle and Docker ecosystems (previously only
  Actions/npm/pip).

## [1.5.2] - 2026-11-06

### Security

- **`read_file` / `list_dir` / `write_file` / `delete_file` could reach the
  app's own credentials.** `read_file` is a `safe`-tier tool, which by design
  never raises an approval prompt, and it was scoped to the whole `filesDir` —
  the same directory that holds `config.json` (plaintext LLM API key) and
  `rules.json` (which dangerous tools the user permanently allowed). A single
  injected web page or SMS could therefore read the key silently and ship it out
  with the next model request. The native engine now rejects `config.json`,
  `rules.json`, `pocketmind.db` and the `checkpoints/` backup directory for read,
  list, write and delete alike; the fix is locked by
  `read_file 不能读到 config_json 里的 API Key`, which fails if the deny list is
  removed (mutation-verified).

## [1.5.1] - 2026-11-06

### Fixed

- **Two endpoints the frontend called but the native engine never implemented.**
  `GET /api/sessions/search` and `POST /api/sessions/{sid}/compact` returned 404
  in the standalone APK, so the session drawer's full-text search (including
  tool output and tool-call arguments) and the "compress history" button were
  silently dead. Both are now real: LIKE-based search across sessions with the
  same `matched` labels as Python (`message` / `tool_output` / `tool_call`), and
  archive-based compaction that keeps the last N messages, emits a summary, and
  advances the `archived_upto` watermark so a second press does not re-summarise
  the same range.
- **Compaction summaries are actually injected.** A new `archives` table stores
  the rolling summaries and `AgentLoop` prepends them to the system prompt;
  without this, "compress" would have hidden messages while the model still
  forgot them.
- **`ApiContract` described a runtime that no longer existed.** It still listed
  chat/tools/sessions/jobs as unimplemented. Rewritten to match reality and
  backed by a new contract self-check test that probes every endpoint in the
  list and fails if any returns `not_implemented`.

### Changed

- README (both languages): tool counts corrected (37 native / 41 Termux =
  39 built-in + 2 runtime), the Termux-only list narrowed to MCP / Telegram /
  skills / subagent, and the scheduler section now states what makes it real
  (Doze survival, reboot re-arm, next-occurrence scheduling).

## [1.5.0] - 2026-11-06

### Added

- **Native scheduler — the APK no longer needs Termux for timed jobs.** Jobs and
  recipes are back on the native engine: `GET/POST /api/jobs`,
  `PUT/DELETE /api/jobs/{id}`, `POST /api/jobs/{id}/run`, `GET /api/recipes`,
  `POST /api/recipes/{id}/apply`. Triggers are `AlarmManager` alarms
  (`setExactAndAllowWhileIdle`, degrading to `setAndAllowWhileIdle` when
  `SCHEDULE_EXACT_ALARM` is not granted on Android 12+), so a job still fires
  after the screen locks. A 5-field cron parser (ranges, lists, steps),
  interval and one-shot triggers are supported; conditions stay
  `battery < N`. Firing re-arms the next occurrence, and `BOOT_COMPLETED`
  replays all alarms after a reboot — the case that silently killed schedules
  before.
- **Camera and speech-tool honesty.** `take_photo` now launches the system
  camera with a MediaStore output URI (works from a background service without
  pulling in AndroidX), saving into `Pictures/PocketMind`. `speech_to_text`
  still cannot run headless — it needs foreground mic focus — but now says so
  in actionable terms instead of a generic "not implemented".
- **Native `run_shell`.** `/system/bin/sh -c` with a 300 s cap, 30 KB output
  truncation, separate stdout/stderr capture and explicit `exit_code`. It runs
  as the app's own uid: no root, no `pkg`/`apt`, no busybox set. This is a real
  shell, not the previous `capability_unavailable` stub, and the diagnostics
  endpoint reports it as `restricted` rather than pretending it is Termux.
- **Checkpoints and undo.** `write_file` (overwrite) and `delete_file` back up
  the original into `files/checkpoints` before acting; the tool result carries
  `_ckpt`, the agent strips it into the `checkpoints` table and emits
  `undoable: true`, and `POST /api/undo` restores the file. Repeat undos are
  rejected. This closes the gap where a native file edit was irreversible.
- Ten new Robolectric contract tests (41 total): jobs CRUD and expression
  validation, recipe listing/apply, run-now delivery into the target session,
  undo round-trip, and real `run_shell` execution with exit-code reporting.

### Fixed

- `scan_wifi` would have thrown `SecurityException` on a device: the manifest
  was missing `ACCESS_WIFI_STATE`. Lint caught it; the permission is now
  declared.
- `NativeEngine.urlDecode` called `URLDecoder.decode(String, Charset)`, which
  requires API 33 while `minSdk` is 26 — `NoSuchMethodError` on older devices.
  Switched to the `charsetName` overload.
- Declared `uses-feature required="false"` for camera, telephony and WiFi so
  Play does not filter out tablets and ChromeOS devices that lack them.
- `Store` schema moved to v2 with an `onUpgrade` path; v1 databases upgrade in
  place instead of crashing on the missing `jobs`/`checkpoints` tables.

### Notes

- Still Python-only by design: MCP, Telegram gateway, skills directory, and
  subagent. The native engine keeps returning explicit
  `capability_unavailable` for those rather than faking success.
- `set_wifi` and `tts_offline` are **not** planned for the native engine:
  Android 10+ forbids apps from toggling WiFi, and offline TTS needs a
  multi-hundred-MB model that would triple the APK. Both stay Termux features.
- No physical-device run yet. The gate is 41 Robolectric tests + clean lint +
  a debug APK build on the Windows host; the AVD cannot start (no nested
  virtualisation).

## [1.4.0] - 2026-11-03

### Added

- **Kotlin native engine — the APK now works standalone.** The shipped APK no
  longer requires Termux, Termux:API or the in-Termux Python deployment. A
  foreground service (`NativeEngineService`) runs the whole agent in-process:
  loopback HTTP/SSE with one-time navigation grants and HttpOnly cookie
  sessions (Slice 2 contract preserved), SQLite storage for sessions/messages/
  knowledge/todos/facts, settings with the same masking/merge semantics, and
  the agent loop (streaming OpenAI-compatible client, tool-call loop, Plan/Act,
  server-enforced approvals with the five decisions and global allow/deny
  rules, per-session queueing and stop).
- **26 native tools** replacing their termux-api counterparts via Android APIs:
  battery, clipboard, notifications, toast, vibrate, torch, volume/brightness,
  sensors, WiFi info/scan, location, SMS send/read, dial (via ACTION_DIAL —
  the user keeps the final confirm), TTS, app-private file ops, download,
  knowledge base, facts, todos, web search/read with the same SSRF guard.
  `run_shell` is registered but returns a structured
  `capability_unavailable` — never a silent fake success.
- **Honest degradation surface**: `/api/diagnostics` and `/api/tools` report
  real availability; jobs/recipes/undo/skills return empty sets or explicit
  `capability_unavailable` until their slices land.
- The web UI ships inside the APK as build assets (`syncWebDist` gradle task),
  so the same React front end runs against the native engine unchanged.
- **Robolectric engine contract tests** (12 cases) lock the whole loop:
  navigation-grant single use, cookie auth, settings masking round-trip, chat
  SSE event sequence, tool auto-execution, approval flow end-to-end, chat-mode
  denial, export `format_version`, and fail-closed 503 on unimplemented paths.

### Changed

- MainActivity is native-first: it binds the engine service and loads the
  granted document; the Termux self-check flow remains as a fallback path and
  the legacy bridge stays untouched for Termux users.
- The docs-based Kotlin spike (Slice 2) is now the shipped runtime; the
  `RUN_COMMAND` deployment path is no longer the primary onboarding.

### Notes

- scheduler (jobs/recipes), MCP stdio, Telegram gateway and checkpoints/undo
  remain Python-side features; the native engine degrades them explicitly.
  See ROADMAP for the 1.5 slices.

## [1.3.0] - 2026-11-03

### Added

- **First-run onboarding wizard.** When the backend is reachable but no model is
  configured, a three-step wizard walks through provider preset → API key with a
  real connectivity test (the next step stays locked until the test passes) →
  permission-mode walkthrough. Skipping is always available and remembered;
  nothing is ever forced.
- **Session search now covers tool output.** Full-text search previously matched
  only `user`/`assistant` message bodies. Tool results (sensor readings, file
  contents, fetched web text) and tool-call arguments (`meta.tool_calls`
  arguments JSON) were invisible. Each hit now carries a `matched` field —
  `message` / `tool_output` / `tool_call` — and the session drawer shows a badge
  for tool-originated hits.
- **Structured tool result viewer.** List-shaped tool results
  (`kb_search`, `kb_list`, job lists, top-level arrays) render as a compact
  table (short flat rows) or titled cards (long content) instead of a raw JSON
  dump. Flat key/value results and error shapes keep the existing rendering.
- **Export format versioning.** `GET /api/export` now stamps
  `format_version: 1`. `POST /api/import` accepts legacy bundles without the
  field (treated as 1), rejects future versions with 400 and an upgrade hint,
  and rejects non-integer/boolean values — never guessing its way through a
  bundle it does not understand.

### Changed

- `mypy` is now clean (was a 93-error baseline) and enforced as a gate step in
  `tests/run_all.sh`. Fixes are mostly annotation-level: platform-conditional
  `resource` attribute access in `tools/shell.py`, `Settings` typed correctly as
  the `Agent` constructor argument instead of `dict`, `Store | None` narrowing
  in `main.py`, sequence types in `memory.py`, and the MCP SDK generics.

### Documentation

- ROADMAP 1.3.x items moved to shipped; verification-gap section updated (the
  mypy gap is closed). API reference documents `format_version` and the new
  search `matched` field.

## [1.2.1] - 2026-11-02

### Fixed

- **The committed Android wrapper was not executable.** `gradlew` was tracked with
  mode `100644`, so `./gradlew` failed with `Permission denied` on every Linux CI
  runner and on any fresh clone — while the docs instruct users to run exactly
  that command. It is now tracked as `100755`. The same audit corrected the mode
  of `scripts/*.sh`, `tests/run_all.sh`, `tests/setup_faketermux.sh` and
  `tools/android-vm/*.sh`.
- **A Vitest memory-tuning block was added to a config file that Vitest never
  reads.** The `test` section landed in `vite.config.ts`, but Vitest resolves
  `vitest.config.ts` first, so the block was dead. Forcing
  `--config vite.config.ts` proved it: 13 of 16 test files failed because the
  jsdom environment was missing. The worker-pool limits and timeout now live in
  `vitest.config.ts`, where they take effect (69/69 still green).
- **Version drift between the changelog and the code.** This changelog documented
  1.2.1, but `agentd/main.py`, `agentd/web/package.json` and
  `termux/apk-shell/gradle.properties` all still reported 1.2.0, and no `v1.2.1`
  tag existed. All three now report 1.2.1, `package-lock.json` matches, and
  `VERSION` at the repository root mirrors the same value.
- **`scripts/build-apk.sh` printed a stale warning.** A successful debug build
  ended with "current build is a Kotlin migration prototype, not distributable",
  which stopped being true once the Kotlin spike was archived.

### Changed

- **The Kotlin migration spike moved out of the shipped application.** The
  Slice 1–2 experiment replaced the WebView shell's Termux bridge with an
  in-process native HTTP service that answered `503 not_implemented` for every
  business endpoint — chat, tools, settings, sessions, export/import, approvals
  and jobs all stopped working inside the APK. It is preserved verbatim, together
  with its migration contract, shared test vectors and test suite, under
  `docs/history/kotlin-migration-slice2/`, and recorded in
  [ROADMAP.md](ROADMAP.md) as an exploration. The shipped APK keeps the working
  Termux-backed shell. No Kotlin source remains under `termux/apk-shell/app/src`.
- **Branch topology reduced to a single long-lived branch.** The stale
  `feature/kotlin-migration-slice1` branch pointed at the same commit as `main`;
  it was deleted so that local and every remote expose `main` only.

### Documentation

- Added [docs/VERSIONING.md](docs/VERSIONING.md): SemVer policy, the three
  single-source version locations, the release checklist, and branch/tag rules.
- Added [ROADMAP.md](ROADMAP.md): what ships today, what is next, and what is
  explicitly not planned.
- Added `VERSION` at the repository root, and
  `docs/history/kotlin-migration-slice2/README.md` explaining why the spike was
  archived rather than shipped.
- `.gitignore` now excludes `.local-artifacts/`, so locally built APKs cannot be
  mistaken for published release assets.

- **`run_shell` crashed on Windows whenever a command timed out.** `_kill_group`
  called `os.getpgid` / `os.killpg` / `signal.SIGKILL` directly — all POSIX-only —
  while the `except` clause only caught `(ProcessLookupError, OSError)`. On Windows
  accessing them raises `AttributeError`, which escaped the tool instead of
  returning a structured result. The spawn path already guarded `preexec_fn` by
  platform; the kill path did not. Now capability-checked with a `proc.kill()`
  fallback. Mutation-verified: removing the guard makes the new `p4` assertion fail
  with the exact `AttributeError`.
- **Two CSS cascade bugs, found by a 3x-DPR screenshot sweep.** `.theme` (the theme
  picker) and `.checkbox-row` are both `<label>` elements, and the settings panel
  has a generic `.field label { display: block }`. Same specificity, later in the
  file, so it overrode their `display: flex`. Measured on `.theme`: the swatch sat
  at y=475..505 and the text started at y=492 — a 13px overlap, so every theme
  label had its text under the thumbnail. `.checkbox-row` was silently `block`,
  killing the 8px gap and vertical centring. Both now use `display: flex !important`
  with an explanation.
- **`pyproject.toml` pointed mypy at Python 3.11** while the installed dependency
  tree uses 3.12-only syntax, so mypy aborted before checking any project code.
  Corrected to 3.12; the real baseline is 95 errors (recorded in ROADMAP, not yet
  gated).

### Added

- `smoke/p35_css_cascade_test.py` — a CSS cascade guard (9 checks). jsdom does no
  layout, so Vitest cannot catch this class of bug. It parses `styles.css` and the
  TSX, maps each class to the real tag it renders on, and flags generic element
  rules that come later at equal-or-higher specificity. Two hard assertions pin
  `.theme` and `.checkbox-row` to `!important`. Mutation-verified in both
  directions.
- `smoke/p34_user_journey_test.py` — 44 assertions over real HTTP covering the full
  session lifecycle: create → tool call → approval → persisted result → rename/pin
  /search/delete → export → idempotent import → job create/run/disable/delete →
  approval rules → delete. Approval blocks the SSE stream, so it reads the stream on
  a worker thread and approves from the main thread — the same shape the frontend
  uses.

- **Secondary pages are code-split.** `SettingsPanel` (1086 lines),
  `CapabilityPage`, `TasksPage` and `SessionList` were all in the first-load chunk.
  They are now `React.lazy()` chunks fetched on first tab click. First-load JS
  307 KB → 264 KB (gzip 102 KB → 89 KB). Verified in a real browser: the initial
  load requests only `index.js`; clicking the capability tab pulls
  `CapabilityPage-*.js` on demand.
- **`CONTRIBUTING`, `SECURITY`, `QUICKSTART`, `DEPLOY` and `CODE_OF_CONDUCT` are now
  English-first** with full `*.zh-CN.md` counterparts and cross-links. The old
  `QUICKSTART` still described the deleted goose/Node/Flutter architecture and
  claimed 2–5 minute installs.

### Removed

- Untracked build output left in the workspace root
  (`artifacts/pocketmind-shell-*.apk`) was moved into the ignored
  `.local-artifacts/` directory. Those binaries still contained the removed
  `PocketNative` bridge and `MonitorService`, so they could not be published.

### Fixed — Android delivery (2026-10-29 audit, full ledger in `docs/audit-2026-10-29.md`)

- **The repository had never produced an APK, and a clean clone could not build
  one.** `.gitignore`'s blanket `*.jar` rule also excluded `gradle-wrapper.jar`,
  so `./gradlew` died with `ClassNotFoundException: GradleWrapperMain` — while the
  documentation told people to use exactly that command. The wrapper jar is now
  tracked, `scripts/build-apk.sh` provides one command for local and CI use, and
  the CI `android` job runs lint, assembleDebug, assembleRelease, signature /
  alignment / version verification, and artifact upload.
- **APK version disagreed with the backend.** The shell was pinned at `1.0.0`
  while the backend and frontend were `1.2.0`. The version is now single-sourced
  from `gradle.properties`, with `tests/check_version.py` as the gate.
- **The foreground service crashed on Android 13 at startup.** The manifest
  declared only `specialUse`, while `MonitorService` passed
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 33; the runtime type must be a subset
  of the manifest declaration or `startForeground` throws. The manifest now
  declares `specialUse|dataSync` and adds `FOREGROUND_SERVICE_DATA_SYNC`.
- **Keep-alive stopped working as soon as the UI closed.** `MainActivity.onDestroy`
  called `stopMonitorService()`, so the foreground service died with the Activity.
  The call was removed.
- **The WebView bridge had no trust boundary.** Without `shouldOverrideUrlLoading`
  any link opened inside the WebView that carried the `PocketNative` bridge, and
  `deployAgent(repoUrl)` interpolated a URL into a shell command. Navigation is now
  restricted to `127.0.0.1` / `localhost` / `file:///android_asset`, external links
  go to the system browser, each bridge method validates the current page URL, and
  the repository URL is charset-validated and POSIX single-quote escaped. The
  default URL is injected through `resValue` so Java and the setup page agree.
- **On Android, an unauthenticated `127.0.0.1` is readable by the whole device.**
  `check_token` allowed every request when the token was empty, and Android's
  loopback port is a device-wide namespace — any other app could read sessions and
  call shell. `install.sh` now generates a random token (0600), `start.sh` injects
  it, and the backend generates and persists one at runtime on Android. The shell
  reads the token out of Termux and injects it into the WebView; non-shell
  environments (browser / PWA) get a first-screen token entry — previously the only
  entry was inside Settings, which itself required the token, so a first `--lan`
  start was a guaranteed 401 deadlock.
- **Scheduled jobs always fell back to UTC.** Since APScheduler 3.11,
  `apscheduler.util.timezone` is `datetime.timezone` and passing a string raises
  `TypeError`, which was swallowed — so "8 AM daily" fired at 16:00 Beijing time.
  Now uses `zoneinfo.ZoneInfo` with TZ → Asia/Shanghai → UTC fallback.
- **The environment self-check always reported a port fault.** `_probe_port` tried
  to bind the port the service was already listening on, so every user saw a false
  fault on first opening Settings. It now connects first to detect a live listener.
- **Setup page typo and a dead button.** `~/poketmind` → `~/pocketmind`; "Start
  service" only brought Termux to the foreground without running `start.sh`, and
  now actually invokes `startAgent`.
- **Frontend bundle targeted ES2020, which could white-screen older WebViews.**
  The build target is now `es2017`.
- **Incomplete `native.ts` bridge declarations.** Added
  `notify/readToken/getRepoUrl/deployAgent/startAgent/checkAgain/openFdroid`, all
  of which degrade safely outside the shell.
- **APK manifest hardening.** `allowBackup=false` (tokens stay out of cloud
  backups), `usesCleartextTraffic=false` (plaintext only for `127.0.0.1`), cutout
  attributes moved to `values-v27` (so minSdk 26 no longer raises NewApi),
  `values-v35` forces edge-to-edge on exit, and `registerReceiver` passes the
  non-exported flag — `lintDebug` went from 2 errors to 0.

### Added — Android delivery

- `smoke/p32_android_delivery_test.py` (22 checks): loopback auth, timezone,
  port self-check, `start.sh` argument parsing.
- `tests/check_version.py`: three-location version consistency gate.
- `scripts/build-apk.sh`: one-command build with artifact verification.
- `agentd/web/src/components/TokenGate.tsx`: first-screen token entry.

### Documentation — Android delivery

- `docs/audit-2026-10-29.md`: the complete issue ledger (22 items, grades A/B/C).
- Install times corrected to "10–30 min on a phone / ~50 min on an emulator"
  (the previous "3–8 min" was off by an order of magnitude against the project's
  own measurements).
- Tool count corrected 31 → 41, test suites 33 → 39.

## [1.2.0] - 2026-10-24

### 修复（第二轮全量审计，每项都有可复现证据）

- **MCP 客户端接入任意外部 Server 时启动即崩**：`agentd/mcp.py` 直接读
  `Tool.input_schema`，而 mcp SDK 1.30 的字段是驼峰 `inputSchema`，没有该属性——
  `AttributeError: 'Tool' object has no attribute 'input_schema'`。主服务只要配置了
  `server.mcp`，启动阶段就会崩在工具注册上；旧单测手工构造 dict，从未走过真实 SDK 对象。
  新增 `mcp.tool_schema()` 统一兼容三种形态（属性 / alias / dict），
  回归：`smoke/p28_mcp_roundtrip_test.py`（真起 stdio 子进程握手，7 条断言）。
- **`/api/stats` 是空壳**：`metrics.snapshot()` 写好了却没有任何调用方，
  「运行统计」只回显配置。现在并入快照（请求数、工具失败率、LLM 调用与 token）。
  回归：`smoke/p29_stats_usage_test.py`。
- **Token 消耗完全不可见**：流式调用从不请求 `stream_options.include_usage`，
  也不解析 `chunk.usage`。现在请求 usage（端点不支持时自动降级重试一次），
  `done` 事件携带本轮累计用量，消息落库进 `meta.usage`，刷新后仍可回看。
  mock 模式给出 `estimated: true` 的估算值，格式与真链路一致。
- **`mcp_client.py` 的 schema 读取同样错字段**：1.30 上恒为 `None`，外部工具参数全空。
  统一走 `tool_schema()`。

### 新增

- **会话置顶**：`sessions.pinned` 列 + `PUT /api/sessions/{id} {"pinned": true}`；
  列表按 `pinned DESC, updated_at DESC` 排序。会话抽屉里一键置顶/取消。
- **定时任务「立即执行」**：`POST /api/jobs/{id}/run`，后台起跑、HTTP 立即返回，
  条件表达式仍生效。此前只能等触发点，无法验证任务是否真的能跑通。
- **备份恢复（导入）**：`POST /api/import`，与 `/api/export` 配对。
  合并语义：同 id 会话跳过、知识库按内容去重、定时任务默认停用导入（避免恢复瞬间全部触发）、
  坏文件 400 且不写半截数据。
- **运行统计面板**：设置 → 运行统计，展示运行时长 / 请求数 / LLM 调用与 token / 工具失败数。
- **消息级 token 用量**：每条助手回复下方显示本轮消耗（端点未回报时标注「估」）。
- 回归套件 p28/p29/p30/p31 进入门禁（默认路径）。
- 发布素材：`assets/screenshots/01-09`（本轮实拍，light/dark/mobile）与
  `docs/demo/pocketmind-demo.mp4` / `.gif`（真实操作录制）。
- **浅色主题下品牌色正文对比度不合格（3.06:1）**：`.s-pin`、`.s-title:hover`、
  `.composer-provider`、`.plan-title`、`.cap-tag.ok` 等 10 处用 `--brand` 当文字色；
  浅色主题的 `#0d9d6e` 在 `panel-2` 上只有 3.06:1（WCAG AA 要 4.5）。新增
  `--brand-text` token（浅色 `#0b7a58` = 4.71:1，深色 `#10b981` = 6.43:1），
  正文用 `--brand-text`、背景/描边继续用 `--brand`。`p9` 增加该断言
  （已反向验证：改回 `#0d9d6e` 时 p9 必红）。
- **UI 假绿：置顶徽章 11px 逃过字号断言**：`.s-pin` 用了 11px，而
  `mobile.spec.ts` 会对全部可见文本断言 ≥12px——只因测试数据里没有置顶会话，
  这条断言从没量到它。已改为 12px，并在手机矩阵里显式置顶一个会话、
  量完字号立刻取消（不取消会让该会话占住 `list[0]`，后续用例的消息发进旧会话）。
- **徽章在 GitCode 全 broken**：GitCode 的图片代理抓 `img.shields.io` 失败
  （实测 4 个徽章 `naturalWidth=0`）。改为仓库内自绘 SVG（`assets/badges/`），
  只在 GitCode/Gitee/GitHub 共用的 raw 通道取图；GitHub Actions 徽章保留（它必须是活的）。
- **门禁在 CI 上假绿/假红**：`smoke/p11_termux_install_test.py` 的假 Termux 环境沿用了
  宿主机 PATH。CI 的 ubuntu runner 自带 rustc 与 python，于是「真缺 rust」「没有 python」
  两个场景被宿主命令补上——本机 10/10 全绿，CI 上 8/10（2026-10-24 GitHub Actions 实测）。
  改为只暴露白名单 shim（sh/grep/tail/…），不含 python/rustc。

### 文档

- 新增 `docs/COMPETITORS.md`（对标 Mobilerun / Mobile-Agent / Cline / Open Interpreter / Letta，
  含能力矩阵与「该借什么」的具体结论，所有外部事实附来源链接）。
- README 双语重写展示区：演示视频、9 张实拍截图、能力矩阵、与竞品的边界说明。
- 新增 `scripts/release.sh`（五平台发布：GitHub ×3 + Gitee + GitCode，按仓库显式选 token；
  支持 `--dry-run`）与 `scripts/sync-remotes.sh`（推 main/tags 并逐个核对 commit）。
  用法见 CONTRIBUTING.md 第 6–7 步。

## [1.1.0] - 2026-10-23

### 修复（2026-10-23 收尾审计）

- **`set_volume` / `set_brightness` 静默失败**：两者用裸 `_run()` 且不看退出码，
  termux-api 权限被拒或参数非法时命令其实失败了，前端仍显示「已完成」。改为校验退出码
  （`set_volume` 走统一的 `_run_checked`），与 `send_sms` / `set_wifi` 同一套纪律。
  回归：`smoke/p26_tool_gaps_test.py`。
- **`/api/export` 在会话数超过 500 时静默丢数据**：`export_bundle` 只取一页就返回，
  第 501 个会话起无声消失，而导出文件的语义是「完整备份」。改为按页翻取到取尽，
  `max_sessions` 成为总量上限；同时避开 SQLite 绑定变量上限（Termux 上可能是 999），
  并在导出结果里给出 `exported_session_count` / `export_truncated`。
  回归：`smoke/p27_export_pagination_test.py`（8 条断言）。
- **指标标签泄露消息主键**：`normalize_path` 只归一化会话 id，
  `/api/sessions/{sid}/messages/123` 里的消息 id 原样进了 Prometheus 标签，
  基数随消息数线性增长。现归一化为 `{id}`。回归：p26。
- **LLM 指标恒为 0**：`metrics.record_llm` 只有定义没有调用方，
  `/api/metrics` 的 `pocketmind_llm_calls_total` 永远是 0（假指标）。
  在 `Agent._stream_round` 接线，主循环与子代理一并覆盖。
- **`read_url` 风险等级与 `web_search` 不一致**：两者都是外发行为（URL / 查询词离开设备），
  前者却标 `safe` 免审批。统一为 `write`，被注入的网页无法再驱动模型静默外发本机 URL。
- **定时任务的 run 记录缺字段**：`run_agent_for_job` 手写字面量漏掉 `cancelled`，
  取消失效依赖 `.get()` 默认值兜着。改用 `_new_run()` 工厂。
- **`/api/diagnostics` 阻塞事件循环**：探测里的同步 socket / subprocess 直接在
  协程里跑（本机实测 0.58s，网络差时更久），期间所有 SSE 与其他请求被堵住。
  改为 `asyncio.to_thread`。
- **`get_session` 返回归档水位**：补上 `archived_upto`，与 `list_sessions` 保持一致，
  避免调用方为了一个字段再查一次。

### 工程化

- 门禁补入**从未执行过**的 `p8 消息级操作` 套件（此前只在失败兜底列表里出现）；
  新增 `p26` / `p27` 两个套件。门禁覆盖全部后端套件 + compileall / ruff / 部署自检 +
  前端 typecheck / 单测 / 构建 / dist 新鲜度。
- 版本号统一到 **1.1.0**（`agentd/main.py` 与 `agentd/web/package.json` 此前一个 0.2.0、
  一个 0.1.0，且都落后于既有 tag）。
- 根目录四份历史报告移入 `docs/history/`（内容原样保留），根目录只留当前有效文档；
  开发截图同样归档到 `docs/history/`；删除误生成的 `nul` 与重复的 `ui-shot-1-main.png`。
- 新增 `docs/audit-2026-10-23.md`（本轮审计台账与证据）、`docs/API.md`（端点参考）、
  `agentd/requirements-dev.txt`（门禁所需的开发依赖）。
- E2E 数据目录改为每轮 `mkdtemp` 并在 `global-teardown.ts` 删除：此前每跑一次 E2E
  就永久留下一个 `/tmp/agentd-e2e-*`（本机实测累积了十几个）。
- GitHub 镜像恢复 CI 工作流与 Dependabot 配置（主仓库仍在 GitCode，本地门禁为权威路径）。

### 修复

- **全新安装装不上依赖**（在干净的 Ubuntu 24.04 上首次验证发现）：
  `agentd/requirements.txt` 写的 `opentelemetry-instrumentation-fastapi>=0.46` 永远匹配不到
  任何版本。instrumentation 系列走独立的 0.x beta 版本线（`0.46b0`…`0.66b1`），PEP 440 下
  `0.46b0 < 0.46`，裸下界高于所有已发布版本——干净环境 `pip install -r` 直接失败；
  已经装过旧版的环境无感（本机是从别处装的 0.66b1），所以一直没暴露。改为 `>=0.46b0`，
  并新增 `tests/check_requirements.py` 纳入门禁，防止同类「永不匹配」约束再次混入。

- **新建会话后立刻发消息会被吞**（E2E 用例 3/4 偶发失败的真实原因）：`loadSession`
  会先清空消息再异步回填，用户在这两步之间发送时，刚插入的「用户消息 + 助手占位」
  被整体覆盖，界面退回空态、只剩一条停在「正在思考…」的孤儿行。加了消息代数
  （`msgGen`）识别过期响应，且同一会话的重复加载不再清空本地已有消息；
  `sendQueue.test.ts` 新增回归用例（旧逻辑下必红）。
- **E2E 每次从干净环境开始**：`playwright.config.ts` 改用 `mkdtemp` 的唯一数据目录，
  并关闭 `reuseExistingServer`——复用时 Playwright 不执行 `env`，隔离用的
  `AGENT_HOME` 会被静默忽略，测试会跑到上一轮的权限模式与历史会话上。

### 新增

- **本地全量门禁 `tests/run_all.sh`**：GitHub Actions 工作流移除后，原来那套检查改由一条命令承担——
  后端 16 个 Python 套件（外加编译 / ruff / 部署链路自检）+ 前端
  typecheck、64 个单测、生产构建与 `dist/` 新鲜度。失败不中断、末尾汇总、有失败即非零退出；
  mock 服务跑在自动挑选的空闲端口上，不再和本机其他进程抢 8799。支持 `--backend` /
  `--frontend` / `--fresh` / `--with-e2e`。
- **smoke 套件支持 `AGENTD_BASE`**：六个直连 API 的套件（m5 / p0_queue / p1_jobs / p2 / p3_api / p5）
  的服务地址改为环境变量可覆盖，默认仍是 `http://127.0.0.1:8799`，单独跑时行为不变。

- **后台审批浮层**（定时任务/后台触发的审批此前无人可见，只能等超时被静默拒绝）：
  新增 `GET /api/approvals/pending`（含剩余秒数倒计时）与输入框上方的审批浮层；
  与消息流内联审批卡按「会话 + 等待中工具」去重不重复显示，卡片用
  `session_id:tool_call_id` 复合键避免跨会话同 id 互相吞掉；决策提交失败时卡片保留并
  提示可重试（服务端 gate 仍在等待，藏掉卡片就退回到静默失败）。
- **清空任务清单服务端端点** `DELETE /api/sessions/{sid}/todos`：前端「清空」此前只清本地，
  刷新页面会把已清空的清单拉回来「复活」；现乐观清空 + 服务端同步（复用 `todo_clear` 的
  `store.clear_todos` 落库路径）。
- **工具耗时只计执行段**：审批等待期间作废计时起点，用户思考的 60 秒不再显示成工具耗时；
  批准后从决策时刻重新计时，被拒绝的工具不显示耗时（附 3 条单测锁住）。
- **P7 横审计回归扩到 48 项**（B10：清空任务清单端点真实注册且走落库路径；
  B5b：`delete_session` 中途失败必回滚，悬挂语句不污染后续写入）。
- **设置面板补齐能力面**（此前这些旋钮只能在 config.json 里改）：
  「运行参数」（工具调用链上限 / 只读工具重试次数）、「上下文与记忆」（上下文预算、
  压缩阈值、自动压缩开关、检索注入条数、摘要触发轮数、记忆保留天数）两组可直接调，
  保存时与 `agent`/`context`/`memory` 三块一起提交。
- **知识库与技能面板**：设置面板可浏览/删除知识库条目（删除走二次确认，条目写入仍只经
  `kb_add` 工具、走审批）与已安装技能列表（只读展示目录位置，不提供在线安装）。
- **导出补全本机数据**：`GET /api/export` 现在包含结构化档案（USER/FACT 原文）、
  每会话任务清单、知识库条目——此前这三类新增数据不在备份里，换机/重装会无声丢失。
- **任务清单面板打磨**：加进度条（`role="progressbar"` 带 aria 值）、可收起/展开、
  完成态降饱和并出现「清空」；移动端补 `env(safe-area-inset-bottom)` 安全区。
- **工具结果可复制**：工具卡片结果右上角新增复制按钮（读到的电量/短信/命令输出常要拿出去用）。
- **PWA/品牌细节**：manifest 补 `id`/`scope`/`display_override`/`categories`；
  index.html 补 `description`、`color-scheme`、apple 系列元信息、亮/暗两套 `theme-color`、
  apple-touch-icon；标题改为「口袋智灵 · 你手机里的本地智能体」。
- **空态文案重写**（品牌语，非通用口号）：说明"住在手机里、不上云"，并给出 6 条可点示例
  （含「记住一件事」「存进知识库」两条新能力），底部说明危险动作会先弹卡片确认。

- **任务清单工具（todo_write / todo_clear）**：对标 Claude Code 的 TodoWrite，act 过程中持续维护
  跨 step 的任务清单，按会话落库（`todos` 表）、随 SSE `todo` 事件推给前端、刷新/重启后恢复；
  前端新增输入框上方的任务清单面板。
- **三级审批 + 全局粒度**：在「允许一次 / 本会话始终允许 / 拒绝」之外新增
  **全局允许（allow_global）** 与 **始终拒绝（deny_always）**。黑名单全局生效且优先级最高
  （命中即短路拒绝，不建等待门、不发审批卡）；新增 `GET/DELETE /api/approvals` 查看与撤销名单，
  设置面板可见可清（避免一次误点永久不可用）；磁盘上的旧格式白名单文件自动兼容载入。
- **结构化长期记忆（USER.md / FACT.md / JOURNAL.jsonl）**：对标 Cherry Studio 的 agent 文件记忆，
  落在 `$AGENT_HOME/memory/`（700/600 权限），内容注入 system 前缀且**文件不变则字节不变**（保提示缓存）；
  新增 `remember_fact` 工具（risk=write，经审批）与 `GET/PUT /api/memory/profile`，设置面板可直接编辑。
- **知识库（kb_add / kb_search / kb_list）**：本机 SQLite + FTS5（trigram）实现，**只经显式工具调用检索**，
  不自动注入上下文（避免顶掉会话记忆）；`GET /api/kb`、`DELETE /api/kb/{id}` 管理条目。
- **Skills 机制（SKILL.md 渐进披露）**：扫描 `$AGENT_HOME/skills/<name>/SKILL.md`，
  仅把 name/description 注入 system，正文由 `read_skill` 工具按需读取；格式对齐生态 SKILL.md，纯 stdlib 解析。
- **手动压缩会话**：`POST /api/sessions/{sid}/compact` 把较早历史归档为摘要（保留最近 N 条原文），
  与自动分页同一套机制。
- **MCP 工具按注解自动分级**：`destructiveHint` → `danger`、`readOnlyHint` 不降级
  （注解由对端服务器自报，外部工具最低 `write`，免审批仅限本地内建工具），
  替代此前"所有 MCP 工具一律要审批"的硬编码。

- **移动端 e2e 规格** `agentd/web/e2e/mobile.spec.ts`：8 个视口（320×568 / 360×640 / 390×844 /
  412×915 / 键盘弹起矮视口 360×360 / 横屏 844×390 / 平板 768×1024 / 桌面 1280×860）逐项断言
  布局与触摸不变量：无横向溢出、顶栏不压消息区、输入框与发送键在视口内、触摸目标 ≥36px、
  触屏上不依赖 hover 揭示可点元素、审批 5 键完整可见且高度达标、覆层按钮不被裁出容器、
  设置面板不超宽可滚动。跑法：`cd agentd/web && npx playwright test e2e/mobile.spec.ts`。
- **mock 支持演示任务清单**：mock LLM 新增 `任务清单/待办/todo` 触发词 → 调 `todo_write`，
  离线演示与 e2e 都能直接看到任务清单面板。

### 变更

- **状态色改为语义分工**（一色一义，不再一处绿打天下）：青=正在执行（`--brand-2`）、
  琥珀=待你处理（审批）、绿=完成/就绪、红=失败/拒绝/断开；顶栏新增状态灯圆点。
- **审批倒计时不再硬编码 120s**：改为读取后端 `server.approval_timeout`
  （经 store 的设置快照下发，取不到时兜底 120），前端显示的等待时长与真实超时一致。
- **配置块在保存时就收敛**：`agent`/`context`/`memory`/`mcp_client` 四块此前只在访问器里
  做范围收敛，config.json 会存下 999 而运行时按 50 生效——设置面板显示值与生效值不一致。
  现在 `_coerce` 对扩展块同样过 pydantic 模型（越界即收敛，面板所见即生效）。
- **去掉诱导性动效**：删除"每个卡片 hover 上浮 + 投影"的默认动画（移动端反馈交给 `:active`）；
  新增全局 `prefers-reduced-motion` 降级。
- **工具分组展示补全**：设置面板工具清单新增「任务与编排 / 知识库 / 长期记忆 / 技能」四组，
  新工具不再落到「其他」。

- **CI 门禁覆盖**：backend job 从 4 个套件扩到全部 11 个（安全专项、配方、健壮性/性能泄漏、MCP、Telegram），
  新增「部署链路自检」步骤（无令牌 `--lan` 必须拒启 / `AGENT_TOKEN` 引导生效 / `--clear-token` 可恢复）。
- **文档纠偏**：`QUICKSTART.md`、`termux/install.sh`、`CONTRIBUTING.md` 里残留的旧仓库名 `termux-agent`
  改为 `pocketmind`；`DEPLOY.md` 说明令牌为打码显示、`--clear-token` 恢复方式与 `AGENT_TOKEN` 的首次语义；
  `web/start.sh` 的 Python 版本口径统一为 3.11+，`--lan` 提示改为"无令牌会拒绝启动"；
  `.monkeycode/MEMORY.md` 整体重写（原文描述的是已废弃的 goose/Rust/ACP 架构，对现在代码是纯误导）。
- 新增本 `CHANGELOG.md`，并在 `CONTRIBUTING.md` 发布流程中明确：主仓库在 GitCode，
  CI 跑在 GitHub 镜像，合并后必须 `git push github main --tags` 同步，否则门禁检查的是旧代码。

- **技术债清算**（死代码 / 从不运行的测试 / 过期文档）：
  - 删除无调用方的 `Store.clear_all`、`Store.count_memory`、`mcp_client.get_hub`；
    `telemetry.traced_tool` 装饰器一并移除（其能力改由 `call_tool` 内的 span 承担）。
  - **OTEL 手动 span 接线**：此前 `agent_span`/`scheduler_span`/工具 span 定义了却从未使用，
    可观测性实际只有 HTTP 层。现在工具调用（含 risk/attempts/error 属性）、一轮对话、
    定时任务触发各自成 span；`telemetry.py` 不再模块级导入 fastapi，工具层可轻量引用。
  - **`native.ts` 桥接接线**：`copyText`/`vibrate` 此前无人使用——而不用 `copyText` 是真 bug：
    WebView 里 `navigator.clipboard` 需要 HTTPS，APK 壳的原生剪贴板桥正是为此存在。
    现两处复制按钮统一走 `native.copyText`（浏览器自动降级），审批决定后给一次轻振动。
    未使用的 `isNativeAvailable`/`notify` 已删。
  - **`tests/eval_approval.py` 从不运行**：它用 pytest 写，而 CI 既不装 pytest 也无 pytest 步骤，
    且持久化断言还停在旧文件格式（即便跑也必红）。已改写为仓库统一的可直接运行脚本（15 项）、
    断言更新为新格式并补旧格式兼容，纳入 CI 单元步骤。
  - **前端开启 `noUnusedLocals`/`noUnusedParameters`**：清掉 3 处未使用导入，防止死代码再生。
  - 死辅助 `compactSession` 接线：设置面板「上下文与记忆」新增「立即压缩当前会话」按钮，
    端点本身也补进 p5 断言（3 项）。
  - 文档时效纠偏：`README`/`README.zh-CN` 仓库布局表由 `tests/` 改为 `smoke/`（14 套件）；
    4 份根报告加「历史快照」声明；`POCKETMIND_主流方案对标.md` 第四节按当前代码复核
    （原列的 P0/P1 缺口已全部完成，并写明仍开放项及不做的理由）；官网 `website/index.html`
    工具数与特性网格同步到现状。

- **触摸设备适配**（按 `(hover: none) / (pointer: coarse)` 判定，而非只看宽度）：
  主按钮 44px、次要按钮 36px 起；消息「复制」「工具结果复制」「任务清单收起/清空」「思考过程」
  等一律给足触摸高度；`touch-action: manipulation` 去掉 300ms 双击缩放延迟与点击蓝闪。
- **触屏不再依赖 hover 揭示操作**：消息「复制」按钮原先 `opacity: 0` 只在 hover 时出现——
  手机上没有 hover，等于该功能不存在；现触摸设备常显。
- **输入与滚动细节**：输入框在窄屏用 16px 字号（避免聚焦自动放大页面）、软键盘回车键显示
  「发送」（`enterkeyhint`）、关闭自动大写/纠错；`overscroll-behavior-y: none` 防止下拉刷新
  打断对话；窄屏收紧边距、设置面板与任务清单补安全区内边距；矮视口下面板不抢占对话区。

### 修复

- **自动摘要压错对象**：`get_messages` 语义反转为「最近 N 条」后，
  `auto_summarize_if_needed` 仍直接复用它取摘要素材，会把**正在进行的对话**摘要掉、
  早期历史永远不被摘要。现按归档水位（`archived_upto`）显式取最旧的 N 条，
  与手动压缩走同一水位，不重复摘要同一段。
- **CSRF 防线画错边 + IPv6 误拒**：`check_same_origin` 原来只校验写方法，
  未来新增带副作用的 GET 端点会被整体绕过——现对所有方法生效（无 Origin 的
  curl/安卓 App 照常放行）；Host 头按中括号切分（`[::1]:port` 原写法会被
  `split(":")` 错切成 `[`，IPv6 loopback 启动时合法浏览器请求被误 403）。
- **删会话窗口内排队续跑误复活**：旧任务刚收尾、续跑任务尚未重建时删会话，
  `cancel_run` 拿不到旧 run，续跑会往已删会话写消息——现续跑前复查会话仍存在。
- **老库补列失败静默**：`_ensure_column`（归档水位列）失败原来 `pass` 不留痕，
  缺列会让导出 500/归档悄悄失效；现打 warning，且 `get_archived_upto` 读不到
  列时降级返回 0。
- **配置回退绕过范围收敛**：`_coerce` 校验失败分支里 int 字段用 `int(float(raw))`
  回退，`"-100"`/`"999999"` 这类合法字符串数字会成功转换、绕过 clamp 写进配置；
  现直接回退默认值。
- **「回到最新」按钮定位错**：`.to-bottom` 绝对定位但聊天容器没有定位上下文，按钮漂到
  body 坐标系压住输入框；现包一层 `.chat-wrap`（relative + 同样 flex 布局）锚定。
- **设置分组头嵌套 button**：`CollapsibleGroup` 组头是 `<button>` 又内嵌动作按钮（HTML 非法，
  React 告警、键盘焦点行为各浏览器不一致）；改为 `div[role=button]`（可 Tab、Enter/Space 触发展开，
  动作按钮保留 stopPropagation）。
- **清空令牌本地凭据错位**：面板里清空令牌输入框并保存时，本地 localStorage 凭据被删而服务端
  令牌仍生效 → 之后所有请求 401。现在清空不算修改：本地凭据不动，并明确提示清除需命令行
  `--clear-token`（文案与后端「空值=不修改」语义对齐）。
- **`/api/health` 不返回 `ready` 字段**：前端据它决定顶栏「已就绪」与空态是否展示示例任务，
  读到 `undefined` 便会**永远显示「未配置」**（即使模型已配好、对话正常）。现按
  `mock || (api_key && model)` 计算并返回顶层 `ready`。
- **设置面板可能整屏崩溃**：`rules.deny.length` / `sk.skills` 直接取字段，旧后端或异常响应
  缺字段时抛 `TypeError: Cannot read properties of undefined` 导致面板白屏；现统一在
  载入时归一化（缺字段退化为空列表/空名单），渲染侧加可选链兜底。
- **进行中状态配色不统一**：任务清单的 in_progress 标记与工具卡状态徽标仍用主色绿/琥珀，
  与「青=进行中」的语义分工冲突，已统一为 `--brand-2`（青）。

- **记忆摘要全链路静默失效**（三重问题叠加，默认配置下摘要从未生效）：
  ① `auto_summarize_if_needed` 里 `await conn.execute(...).scalar()` 优先级写错，每次必抛
  `AttributeError: 'coroutine' object has no attribute 'scalar'`；② 摘要器传的是 `AsyncOpenAI`
  客户端，它没有 `summarize` 方法（未开 LiteLLM 时必然失败）；③ 归档因此退化成一句占位文本。
  现改为独立 `agentd/summarizer.py`（直连 OpenAI 兼容端点），并修正 await 优先级。
- **中断后会话卡死（重启/停止留下的孤立 tool_calls）**：assistant(tool_calls) 在工具执行前落库，
  若进程在结果落库前被杀，历史里会留下没有响应的工具调用，OpenAI 兼容 API 对这种历史直接 400，
  该会话此后无法继续。现在每次组装上下文前自动补「未执行完成」占位响应（幂等），历史自洽。
- **已声明但从未被消费的配置**（设置面板旋钮是装饰品）：
  `agent.max_tool_chain_depth`（链长此前硬编码 20）、`agent.max_tool_retries`（此前无重试）、
  `memory.search_limit`（此前写死 5）、`memory.fts_page_size`（此前写死 50）、
  `context.auto_compress` / `context.compress_threshold`（此前完全未实现——现在按估算 token
  占预算比例触发滚动归档）。`agent.tool_timeout` 与 `eval.*` 因语义与既有实现冲突/无消费方，
  直接从 DEFAULTS 删除（不留假旋钮）。
- **只读工具瞬时失败无重试**：新增重试仅对 `risk="safe"` 工具生效（超时/异常类失败，
  最多 `max_tool_retries` 次）；write/danger 工具（发短信、写文件、shell）**绝不重试**，避免重复副作用。
- **CI 门禁实际跑不起来**：`.github/workflows/ci.yml` 引用的 10 个套件路径写成 `tests/*.py`，
  而文件实际位于 `smoke/`（`tests/` 只有 `security_test.py`），按当前 workflow 会在第一步就报
  "can't open file"。已全部改指 `smoke/`，并把新的 P4 套件纳入门禁。
- **MCP 独立模式缺 2 个工具**：`remember_fact` / `read_skill` 只在 FastAPI lifespan 注册，
  `python -m agentd.mcp_server`（stdio 独立模式）不跑 lifespan，桌面端会少看到这两个工具；
  现在 `build_server()` 前补注册（幂等）。

- **一键部署链路走不通**：`docker-compose.yml` / `Dockerfile` 一直传 `AGENT_TOKEN`，但没有任何代码读取它；
  容器以 `--lan` 启动时因"未配置访问令牌"直接 `SystemExit(1)`，配合 `restart: unless-stopped` 就是无限重启。
  现在启动时会用 `AGENT_TOKEN` **首次**初始化 `server.token`（已有配置则以 `config.json` 为准）。
- **镜像缺配方数据**：`Dockerfile` 只 COPY `agentd/`，而预设配方按 `仓库根/config/recipes.json` 读取，
  导致容器里 Jobs 面板配方静默为空、`/api/recipes/{id}/apply` 404。已补 `COPY config/`。
- **保存设置时明文回传令牌**：`GET /api/settings` 刻意把 `server.token` / `tg_token` 打码，
  但 `PUT` 的响应把未打码的原始配置整包返回——保存一次即泄漏。改为 GET/PUT 共用 `_masked_settings()`。
- **配方实例化绕过校验**：`/api/recipes/{id}/apply` 直接调 `scheduler.create()`，
  坏 cron/interval 表达式会在 APScheduler 内抛 `ValueError` 变成 500。现与手工建任务共用
  `_validate_job_payload()`，非法输入统一 400。
- **`--home` 静默失效**：`Settings` 是模块级单例，`path` 在 import 时就按当时的 `AGENT_HOME` 定死了，
  `main()` 里只改环境变量不会搬走配置与 `*.db`。现在给定 `--home` 会显式重定位并重新加载。
- **前端 MCP 配置保存被丢弃**：设置面板的 MCP 文本框写入 `form.server.mcp`，
  但 `save()` 只提交 `token` 与 `approval_timeout`，后端其实支持 `mcp` 字段——用户改完保存等于没改。
- **前端 MCP 参数切分错误**：`名称 | 命令 | 参数` 的参数段整段当成一个 argv，
  多参数服务器（如 `npx -y pkg /sdcard`）根本起不来。现按空白切分为多个 argv。
- **设置面板 401 死锁**：配置了令牌后本机也被拦（`check_token` 只看令牌是否非空，不区分 `--lan`），
  而前端 `.catch(() => {})` 把 401 吞掉，面板永远停在"加载中…"，既看不到原因也没有输入入口。
  现在 401 会显示令牌输入框（解锁后自动重载），其他错误显示真实原因；
  并新增 `python -m agentd.main --clear-token` 作为忘记令牌时的本机恢复入口。

- **未配置模型时发送消息会长时间挂起**：AsyncOpenAI 会拿默认 base_url 去打 api.openai.com
  并重试（最长十几分钟），用户对着「正在思考…」干等才看到报错。现在入口处快速失败
  （0.3 秒内返回 `error` 事件），并按缺失项给出指引：「尚未配置模型（缺少Base URL、模型名）」；
  `/api/health` 同步返回 `llm_missing` 明细，判据由 `Settings.llm_missing()/llm_ready()` 统一提供
  （p4 新增 R1b 断言：空配置/缺单项/LiteLLM 网关三种情形）。
- **首次使用路径把会话永久锁成「排队」**（上一条修复引入的回归，由真实模式浏览器检查抓到）：
  快速失败用了提前 `return`，跳过了 `finally` 里的 `runs.pop`，该会话此后每条消息都被判为
  「上一轮还在进行中」而永不执行。现在守卫放在 `try` 内（`return` 仍走 `finally` 清理与续跑），
  并把「用户消息 + 失败说明」落库，重开会话能看到完整一问一答。新增
  `smoke/p6_unconfigured_test.py`（8 项，自带服务生命周期、不需要任何 LLM 配置）把它锁死。

- **应用把自己限流打死**：前端每 1 秒轮询 `/api/health`，而服务端限流默认 30 次/分钟——
  约 30 秒后轮询收到 429，界面误报「后端已断开」。现在健康检查与静态资源**完全绕过**限流中间件
  （统一 key 之类的"换桶"写法无效：仍会汇进同一个桶），前端探活降为 5 秒一次（省电），
  并要求连续两次失败才报断开（一次抖动不再吓用户）。
- **限流配额对正常界面操作也不够**：打开一次设置面板就要 7 个请求、切会话 2-3 个，30 次/分钟
  很容易触顶 → 用户看到莫名 429。配额调整为 120 次/分钟（失控循环仍是每秒几十上百次，照样拦住）。
  p3 套件新增「健康检查连打 40 次 / 首页连打 10 次 / 常规 API 连打 45 次不触发 429」回归。
- **审批卡可能不展开**：工具卡从「运行中」变「需要确认」时不自动展开，审批按钮与倒计时被折叠
  藏起来（是否展开还取决于 SSE 两个事件是否落在同一渲染批次，时好时坏）；而等待会超时自动拒绝。
  现在状态转为 waiting 即自动展开，并新增 `MessageItem.test.tsx`（5 项）锁定。
- **消息会被静默吞掉**：发送队列在「循环判空退出」与 `running=false` 之间的窗口入队的消息
  永不执行（界面停在「已排队」，实测连发 3 条只有 2 条落库）。队列改为统一驱动器 + 收尾接力，
  新增 `src/store/sendQueue.test.ts`（4 项）锁定不变量。
- **p6 套件会在端口被占时静默测到别人的服务**：改为自动申请空闲端口，并检测自身进程是否提前退出。
- **e2e 配置只认 python3**：Windows 本地跑 `npx playwright test` 会卡在 webServer 启动超时，
  现按平台选择解释器（Windows 用 `python`）。

- **run_shell 在 Windows 上必然失败**：`preexec_fn` 是 POSIX 专属参数，Windows 上传入直接
  `ValueError: preexec_fn is not supported on Windows platforms`——走一遍真实用户旅程（批准一次
  shell 命令）才发现，此前只跑 mock 分支没暴露。现在按平台条件传参（Windows 仍保留
  `start_new_session`），p4 新增「run_shell 在本平台可直接执行成功」断言（含结构化字段）。
- **e2e 手机矩阵会被其他套件的脏状态误杀**：`m5` / `p2` 会把 `permission_mode` 改成 `auto`，
  而 Playwright `reuseExistingServer` 复用了它们在 8799 起的实例 → 危险操作不再弹审批，
  8 个视口全线误报失败（现场表现为「审批卡没出现」而页面显示「run_shell 完成」）。
  现在 e2e 用独立端口 8793 + 独立数据目录，并在 `beforeAll` 显式把权限模式设回 approve，
  套件对运行环境不再敏感；复跑 8/8（42 秒）。

- **工具结果在手机上是一团 JSON**：`{ "ok": true, "exit_code": 0, ... }` 在 390px 宽的卡片里
  只能横着读。现在扁平结果逐行展示为 `键：值`（布尔中文化、null 显示 `—`、超长值截断），
  嵌套/数组仍退回 JSON 保证信息不丢；新增 `agentd/web/src/lib/format.ts` 与 10 项单测锁定行为。
- **会话列表看不到时间**：后端 `/api/sessions` 一直在返回 `updated_at`，但前端 `Session` 类型里
  没有这个字段，信息白丢。现在每行显示相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 HH:MM / M月D日）。
- **输入框计数是个裸数字**：用户看不出那是字数还是上限。现在平时不显示，接近上限才提示
  「还可输入 N 字」，超出则「已超出 N 字」并变红。

- **设置面板是一条七屏长的卷轴**：15 个分组全部平铺，实测内容高 5222px（视口 726px，7.2 屏），
  用户找不到想要的项。改成分组可折叠（新增 `CollapsibleGroup`），收起时在标题右侧直接显示
  当前值概览（「逐次审批」「工具链 20 · 重试 2」「38 项」），内容收起时不渲染；
  「外观」「模型」默认展开以免常用项多点一次，「需要访问令牌」保持常开（它是拦截提示）。
  折叠后面板 5222px → 1685px（2.3 屏），分组头触摸高度 48px，键盘可达且带 aria-expanded。
- **辅助文字小于 12px**：全站 14 处 10px/11px 声明（时间、风险标签、说明文字、输入计数等）
  在手机上读不清、且会被系统文字放大机制拉伸导致同一段文字字号不一致。统一提到 12px 下限
  （全宽度，不再只对窄屏生效），并补 `-webkit-text-size-adjust: 100%` 关闭系统自动放大。
- **中文字体回退顺序不明确**：字体栈里中日字形优先级不清晰，部分 Android 机型会用日文字形渲染
  汉字。改为显式声明 PingFang SC → Hiragino Sans GB → Noto Sans CJK SC → Source Han Sans SC →
  Microsoft YaHei 的中文优先顺序。
- **工具描述被硬切**：`.tool-item p` 用 `nowrap + ellipsis` 强制单行，实测
  「执行 shell 命令（可运行任意命令，请确认是…」需要 297px 但只有 193px，句子被切断。
  改为完整换行（该分组默认收起，展开时多两行无代价），38 条描述 0 截断。

- **长对话里新消息不再自动滚动到底（真 bug）**：滚动容器其实是 `<main class="chat">`
  （`overflow-y: auto`），而代码一直对 `.msgs` 设 `scrollTop` —— 该元素没有 overflow，
  赋值完全无效。内容装得下一屏时看不出来，对话一长就暴露：实测发消息后新消息停在
  视口下方 447px 处。现在改为跟随真实滚动容器。
- **上翻看历史被强制拽回底部**：原实现每次 `messages` 变化都无条件滚到底，用户流式输出
  期间往上翻会被每来一个字拉回一次。改为“贴底才跟随”（留 80px 容差），上翻时出现
  「最新」浮动按钮，点击回到当前消息。
- **辅助文字对比度不足 WCAG AA**：`--text-3` 在深色下仅 3.37–3.91:1（低于 4.5:1），
  影响时间戳、消息「复制」、agent 名、输入提示等高频文字。深色改 `#8a94a4`（最差背景
  5.32:1）、浅色改 `#6b7280`（4.55:1），保持原有灰调只提亮度。
- **触摸目标不足 44px**：实测发送键 38×38、顶栏图标键 42px、小按钮/工具复制键 36px、
  会话删除键 40px。手机上统一提到 44px（桌面保持紧凑），
  「思考过程」折叠头同样抬到 44px。
- **工具卡没有耗时**：用户看着转圈无法判断“等 30 秒”是正常还是卡住。前端在
  `tool_start → tool_update` 之间计时（≥300ms 才显示）；等待用户审批的时间不计入。
  配套单测发现并修掉一处真 bug：`startedAt ? ... : undefined` 用真值判断，
  时间戳为 0 时会被误判成“没有开始时间”。

- **令牌含非 ASCII 时所有 API 变 500**：`hmac.compare_digest` 对含中文的 str 抛
  `TypeError`，于是每个带 Authorization 的请求都 500，用户填过中文令牌后连设置页都打不开
  且无法自行改回。改为比较前先 encode("utf-8")（保持常量时间语义）。
- **`--host 0.0.0.0` 可绕过「非本机必须有令牌」的启动强校验**：旧判定只看是否传了 `--lan`，
  而 `check_token` 在 token 为空时直接放行 —— 相当于把 chat / export / settings
  整个 API 暴露给局域网。改为按「最终监听地址是否非本机」判定。
- **GET /api/settings 明文回传第三方密钥**：`litellm.api_key` 与 `mcp_client.servers[].env`
  （常见放 GitHub/Notion 令牌）此前整体透传，/api/stats 专门剔除 litellm key 而这里漏了。
  现递归打码，功能字段（command/args）保留。
- **`read_file` 可无审批读走自己的密钥**：它是 safe 级（永不弹审批），白名单是 `$HOME`，
  而 config.json（含 API Key 与局域网令牌）、`.ssh/id_rsa`、`.gnupg` 都在 `$HOME` 里 ——
  一条被注入的短信即可让模型静默读走凭据并随下次请求发给模型厂商。
  现将 `$AGENT_HOME`、`.ssh`、`.gnupg` 加入拒绝名单。
- **非法配置块会永久污染配置**：`PUT /api/settings {"llm": "text"}` 校验失败后原样落盘，
  之后 `GET /api/settings` 永久 500（`merged.update` 对字符串抛错），设置面板打不开、
  导出失败，只能手改 config.json。现在按块/按字段回退默认值而非保留坏值。
- **`get_messages` 返回的是「最旧」200 条**：会话超过 200 条后，最新消息进不了上下文，
  模型表现为「突然失忆、答非所问」。改为倒序取再翻转（时间正序返回）。
- **「停止」与「删除会话」拦不住排队消息**：`CancelledError` 被吞导致
  `task.cancelled()` 恒为 False，pending 队列被当作正常结束而继续执行 ——
  用户点停止后下一条继续跑，删掉的会话还会被继续写入。改为显式标志位 + re-raise。
- **run_shell 非零退出被当「完成」**：只返回 `exit_code` 而判定只看 `error` 字段，
  失败命令在前端与落库状态都是完成，定时任务/条件判断也把失败当成功。
- **十余个手机工具忽略退出码、伪造成功**：发短信、拨号、切 WiFi、手电筒、通知、震动、
  TTS、分享、下载、打开均「调用完就报成功」，termux-api 缺失或权限被拒时静默失败。
  统一走 `_run_checked` 校验退出码。
- **外部 MCP 可自报 readOnlyHint 免除审批**：把删除类工具标成只读即可在 approve 模式免确认
  —— 等于把审批信任边界交给被接入方。现在外部工具最低为 write。
- **interval 极值 `inf`/`1e999` 返回 500**：`int(float(...))` 抛 OverflowError 而只捕了
  ValueError；同时补上范围校验（1 秒 ~ 365 天）。
- **删会话残留备份文件**：checkpoint 只删表里的行，`.bak` 永久留在磁盘（含用户文件副本）。
- **E2E 用例间状态泄漏**：新建会话不中断正在跑的流，上一条用例仍在流式输出时下一条的
  发送会被丢弃（表现为「没弹审批」）。前置等输入框回到空闲占位。

- **定时任务触发的审批卡用户看不到**：后台任务没有 SSE 消费者，审批请求 emit 进的是
  无人读取的队列 —— 用户在界面上完全看不到，任务只能等超时被自动拒绝（安全但静默失败），
  也无法审计定时任务将要做什么。现在审批登记在 ApprovalCenter 并可经
  `GET /api/approvals/pending` 查询，前端新增浮层审批卡（贴在输入区上方），
  支持允许一次 / 始终允许 / 拒绝；倒计时按本地秒表平滑递减。
  修复过程中又发现一处真 bug：浮层最初用「当前打开的会话」提交决策，
  而后台审批属于触发它的另一个会话 → 后端找不到 gate，用户点了没反应。
- **保存设置会把「打码后的令牌」写进本地凭据，导致自我锁死**：GET /api/settings 回传
  `to*********56` 这类打码值，而保存时无条件 `setToken(...)`，于是本地凭据被替换成假令牌，
  之后所有请求 401、设置页也打不开，且每次保存都复发（只能清 localStorage 才恢复）。
  现在只在用户真的编辑过令牌输入框时才同步。
- **`ask_approval` 漏传时审批整体失效（fail-open）**：旧实现默认 `decision="allow"`，
  只要调用方没传审批回调（未来的新入口 / MCP 子进程模式），approve 模式就退化成 auto。
  现改为 fail-closed：无审批通道时写与危险操作一律拒绝。
- **浏览器可跨站驱动本机服务（CSRF）**：无 body 的 POST 属 CORS simple request 不触发预检，
  手机浏览器打开任意恶意网页即可静默 `POST /api/memory/reset`（清空全部记忆）或
  `/api/recipes/<id>/apply`（创建并启用定时任务）。现在校验 Origin 同主机；
  curl / 安卓 App / 测试脚本（无 Origin 头）不受影响。
- **pydantic 强转结果被丢弃**：只用于校验、真正传给 handler 的仍是模型原始值 ——
  `set_wifi(enabled="false")` 里非空字符串为真值，**反而把 WiFi 打开**；`vibrate("500")`
  直接 TypeError。现在使用强转结果，并剔除 null（模型用 null 表示"用默认"，透传会覆盖
  handler 默认参数导致 `Path(None)` 报错）。
- **超时倒挂**：`get_location` / `scan_wifi` 内部要 30 秒而工具级超时只有 20 秒 →
  外层先触发，内部那句「GPS 冷启动可能较慢」的提示永远到不了用户；
  `send_sms` 内部 20s 与工具级同刻竞争。现分别调整为 40s / 15s 留出余量。
- **写事务失败会污染后续写入**：一批 DELETE 共用一条连接，中途抛错而直接退出时，
  前几条未提交的语句会悬在连接上被**下一次任意 commit** 连带提交 ——
  用户看到删除失败，数据却已被悄悄清空。现已显式 rollback；
  同时补 `PRAGMA busy_timeout=30000`（此前 `database is locked` 直接 500）。

- **同一批最旧消息被每轮重复摘要**：会话超过窗口后，每轮都把同一段最旧历史交给 LLM
  重新摘要一次（白烧 token），而归档越堆越多、5 份近似重复的内容顶掉真正有用的记忆。
  现在 sessions 表记录 `archived_upto` 水位（幂等补列，老库自动升级），只归档水位之后
  的消息；摘要落库成功才推进水位，失败下轮重试不丢历史。
- **中断恢复的 tool 占位响应顺序非法**：占位消息被追加到会话末尾，而 OpenAI 兼容 API
  要求 tool 消息紧跟发起它的 assistant —— 极端情况下仍会 400，会话继续卡死。
  现在在组装上下文时按 tool_call_id 归位（`_reorder_tool_pairs`），任何写入顺序都得到合法历史。
- **导出「完整备份」缺失长期记忆与定时任务**：摘要、归档记忆、定时任务此前完全没有导出，
  换机/重装后长期记忆只剩最近一条、定时提醒全没了，而导出文件看着是完整的。
  现在导出含每会话 `summary` / `archives` / `archived_upto` 与顶层 `jobs`。

### 测试

- 新增 `smoke/p6_unconfigured_test.py`（8 项）：首次使用路径回归（健康检查缺失项、快速失败、
  连续两条消息不被锁成排队、历史留完整一问一答）；另有真实模式首次使用的浏览器断言 7 项。
- `tests/eval_approval.py` 由 pytest 改写为可直接运行的脚本（15 项）并纳入 CI 单元步骤。
- 新增 `smoke/p4_agent_depth_test.py`（50 项）：配置接线 / 中断恢复 / 三级审批 / MCP 注解分级 /
  档案记忆注入 / 知识库显式检索 / 摘要链路 / Skills 渐进披露 / todo 工具，含反例（danger 工具零重试、
  黑名单跨会话短路、旧审批文件格式兼容、知识库不自动注入、skills 正文不进 system、恢复幂等）。
- 新增 `smoke/p5_frontend_contract_test.py`（18 项）：导出覆盖档案/清单/知识库、
  agent/context/memory 三块设置回环与越界收敛、新工具分组齐备。
- 新增 `agentd/web/src/components/TodoPanel.test.tsx`（5 项）与 SettingsPanel「能力面」4 项；
  settingsPanel 的 api mock 补上 helper 风格导出（listKb/getSkills/…），否则组件会真的发请求并静默失败。
- 新增 `smoke/p6_unconfigured_test.py`（8 项）：首次使用路径回归（健康检查缺失项、快速失败、
  连续两条消息不被锁成排队、历史留完整一问一答）；另有真实模式首次使用的浏览器断言 7 项。
- 浏览器可见行为断言（Playwright，17 项）：状态灯取当前主题 `--ok`、进度条 aria 与填充比例、
  进行中=青（`--brand-2`）、被拒工具卡与结果复制、六组设置分组与知识库条目。
- 新增 `agentd/web/src/components/settingsPanel.test.tsx`：MCP 保存契约（含"未编辑不能丢数据"）与 401 门禁。
- `tests/p3_robustness_unit.py` 增加任务/配方校验与响应打码断言（13 → 23 项）。
- `tests/m5_regression.py`、`tests/p3_perf_leak_test.py`：chmod 语义与进程残留探测等 POSIX 专用断言
  在非 POSIX 平台记为「跳过」，不再产生假红；同时不再要求 Windows 本机必须有 termux 桩。
- `agentd/web/src/test/setup.ts`：Node ≥ 25 的实验性原生 `localStorage` 会盖掉 jsdom 实现并抛
  `localStorage.getItem is not a function`，整个前端套件在 Node 25 上全红。补一个内存版 Storage 兜底。
