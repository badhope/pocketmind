# Archived: Kotlin native runtime (Slice 1–2)

**Status:** archived experiment, not shipped. See [ROADMAP.md](../../ROADMAP.md).

This directory preserves, verbatim and unmodified, the Kotlin migration work that
was developed on the branch `feature/kotlin-migration-slice1` and never committed
to `main`. It is kept because the design and the test suite are reusable; it is
**not** part of the shipped product, and no file here is compiled.

## Why it was not shipped

The spike replaced the WebView shell's Termux bridge with an in-process Kotlin
HTTP service. That service implemented exactly two endpoints:

- `GET /api/health`
- `GET /api/native/status`

Every other `/api/*` path — chat and SSE, tools, settings, sessions, export,
import, approvals, jobs — returned `503 {"error":"unavailable","migration":"not_implemented"}`.

The migration contract in `contract.md` states this plainly and the test suite
asserts it. The consequence, however, is that installing that APK would have left
the user with a shell that could display a status page and nothing else: no chat,
no tools, no history. That is a regression against the shipping product, which
already works end to end through Termux.

Shipping it would also have required deleting `MonitorService`, `SecurityPolicy`,
the `PocketNative` JS bridge and the Termux `RUN_COMMAND` path — all of which the
current, working application depends on.

The honest options were therefore:

1. Finish the whole backend in Kotlin before touching the shipped APK. That is a
   multi-month effort and was out of scope for this round.
2. Ship a working product now and keep the spike as a documented design asset.

Option 2 was chosen.

## What is genuinely valuable here

- **`contract.md`** — a frozen, precise description of the existing Python API
  surface (REST shapes, SSE event types, risk tiers, approval memory semantics,
  SQLite schema, export/import limits). This is useful documentation regardless of
  whether the Kotlin port ever resumes.
- **`NativeEngine.kt`** — a dependency-free local HTTP server with a defensible
  identity model: `ServerSocket(0)` on `127.0.0.1` only, exact `Host` matching,
  single-use navigation nonces with a 10 s TTL, and an `HttpOnly; SameSite=Strict`
  session cookie. The token never appears in a URL, a log line, or a retained
  field.
- **`SessionRepository.kt`** — a `SQLiteOpenHelper` schema for sessions and
  messages, including a `reorderToolPairs` pass that restores assistant
  `tool_calls` adjacency after a paged read. Note that this deliberately differs
  from the Python implementation's duplicate/lost-row behaviour.
- **`src/test/`** — 42 Robolectric/JVM tests, including mutation-verified
  assertions: removing the grant check turns 4 tests red, and making `/api/*`
  return 200 turns 2 tests red.

## What is not verified

- Nothing here ever ran on a real device or an emulator. The host used for this
  work is a KVM guest without nested virtualisation, so AVDs stayed offline and
  ARM64 emulation was unavailable.
- The `WebView.loadUrl(url, headers)` header-delivery path is asserted only under
  Robolectric. A WebView build that drops additional headers would produce a 401
  and the status panel — fail-closed, but unverified on hardware.
- `SessionApi.kt` was written but **never wired into `NativeEngine`'s routing
  table**; `/api/sessions` still answered 503 in the spike. It is included here as
  an unused building block, not as a working endpoint.

## If this work resumes

Read `contract.md` first, then `src/main/kotlin/.../NativeEngine.kt`. The routing
table in `NativeEngine` is the single place where the implemented surface is
declared, and `ApiContract.implemented` / `ApiContract.unimplemented` must be kept
in sync with it — `ContractTest` enforces the two sets are disjoint and that
`/api/health` is never listed as unimplemented.

A resumption should also settle two open questions the spike did not answer:

1. Whether `WebView.loadUrl` additional headers survive on target WebView
   versions, or whether the bootstrap must use a different channel.
2. How device capabilities (SMS, location, sensors) are reached from a Kotlin
   process, given that `termux-api` is a Termux-side interface.

## File map

| Path | Contents |
|---|---|
| `contract.md` | The migration contract: real API surface, token delivery design, failure fallbacks, residual risks. |
| `native_bootstrap.html` | The script-free bootstrap document served at `/`. |
| `src/main/kotlin/…/runtime/ApiContract.kt` | Endpoint constants, implemented/unimplemented sets, enums, `ToolDescriptor`. |
| `src/main/kotlin/…/runtime/NativeEngine.kt` | The HTTP server, nonce grants, session cookies, routing. |
| `src/main/kotlin/…/runtime/NativeEngineService.kt` | The bound Android `Service` exposing `newDocumentUrl()` over Binder. |
| `src/main/kotlin/…/runtime/SessionApi.kt` | Session endpoints — written, **not wired**. |
| `src/main/kotlin/…/storage/SessionRepository.kt` | SQLite schema and queries for sessions and messages. |
| `src/test/kotlin/` | 42 tests across `ContractTest`, `NativeEngineTest`, `SessionApiTest`, `SessionRepositoryTest`. |
| `src/test/resources/contract-vectors.properties` | Shared vectors consumed by both the Kotlin tests and `test_kotlin_contract_reference.py`. |
| `test_kotlin_contract_reference.py` | Python-side check that the reference API still matches the frozen vectors (7 tests). |

The `test_kotlin_contract_reference.py` file was removed from `tests/` when the
spike was archived, so it is not part of the gate. To run it against the current
backend, copy it back into `tests/` and run it with the project virtualenv.
