# Security Policy

[简体中文](SECURITY.zh-CN.md) · [English](SECURITY.md)

## Reporting a vulnerability

**Do not open a public issue.** Public disclosure of an unfixed vulnerability puts
every user of this project at risk.

Report privately through either channel:

- **GitHub Security Advisories** on the mirror repository — preferred, because it
  supports private discussion and coordinated disclosure.
- Open a public issue that says only *"I found a security issue, please provide a
  private channel"* — no details, no reproduction steps, no affected versions.

Include in the private report:

1. Affected version (`GET /api/health` returns it).
2. Attack preconditions — same device? same LAN? does it require a malicious page?
3. Reproduction steps or a proof of concept.
4. Impact: what can an attacker read, write, or execute?

We acknowledge within 7 days, confirm the impact, and agree on a disclosure date.
Credit is given unless you ask otherwise.

## Supported versions

Only the latest release (`main` HEAD or the newest `v*` tag) receives security fixes.
This project does not backport.

## Threat model — read this before deploying

PocketMind is a **single-user, single-device** agent. Its security model is built
around that assumption and does not attempt to be a multi-tenant service.

### The boundaries that exist

| Boundary | Mechanism | Where |
|---|---|---|
| Network | Binds `127.0.0.1` by default. `--lan` / `--host 0.0.0.0` **refuses to start** without a `server.token`. | `agentd/main.py` |
| Authentication | Every endpoint except `/api/health` and static assets requires `Authorization: Bearer`, compared in constant time. | `agentd/main.py` |
| CSRF | Any request carrying an `Origin` header must be same-host, on every method — this covers bodyless "simple requests" that could otherwise silently mutate state. | `agentd/main.py` |
| Tool approval | Enforced server-side: `safe` passes, `write` and `danger` require confirmation, timeout auto-denies. A global deny list short-circuits before the card is rendered. | `agentd/permissions.py`, `agentd/approval.py` |
| File access | Reads and writes are confined to `$HOME`, `~/storage`, `/sdcard`. `.agent`, `.ssh`, `.gnupg` and the data directory are denied, including via subpaths and symlink resolution. | `agentd/tools/files.py` |
| SSRF | `read_url` resolves DNS first, checks the resulting IP against private/metadata ranges, restricts ports, and re-validates on every redirect hop. | `agentd/tools/net.py` |
| Secrets at rest | API keys and tokens live only in the local `config.json` (mode 600). Every API response masks them; exports never contain them. | `agentd/config.py`, `agentd/routes/__init__.py` |
| Data at rest | SQLite directory 700, files 600, WAL files likewise. | `agentd/memory.py` |

### Known limitations — these are not vulnerabilities

- **Prompt injection is not filtered.** `read_url` and `read_sms` return text that may
  contain instructions. The defence is the approval gate plus tool narrowing, not
  content scanning. Running `auto` mode against untrusted content is outside the
  security model.
- **`run_shell` is arbitrary code execution** after approval. Review the command.
- **LAN mode means any device with the token can call every endpoint**, including
  shell. It refuses to start without a token, but a token is a bearer credential: if
  it leaks, the deployment is compromised. Do not enable LAN mode on an untrusted
  network. For public exposure, put HTTPS in front (see `DEPLOY.md`).
- **The APK hosts a WebView.** The WebView loads the local agent UI. A WebView
  vulnerability, or a rooted device that can read process memory, is outside the
  threat model.
- **The Telegram gateway trusts a whitelist.** Any `chat_id` in the allowlist can drive
  the agent. A compromised Telegram account is a compromised agent.

### If you deploy beyond a personal phone

- Put a TLS-terminating reverse proxy in front; never expose `uvicorn` directly.
- Set a long random `server.token`; rotate it if it was ever in a log or a screenshot.
- Run as an unprivileged user with a dedicated data directory.
- Disable `run_shell` if the deployment does not need it (permission mode `chat`
  registers no tools at all).

## Dependencies and supply chain

- Runtime dependencies are pinned by major version in `agentd/requirements.txt`;
  `tests/check_requirements.py` fails the gate if a constraint can never be satisfied.
- Vulnerability scanning: `python smoke/p22_dep_audit_test.py` (OSV.dev, needs network)
  or `bash tests/run_all.sh --with-dep-audit`.
- Dependabot is configured for npm, pip and GitHub Actions on a monthly grouped
  schedule with a small open-PR limit.
- The Android shell has **zero third-party runtime dependencies** by design: no
  Capacitor, no Flutter, no analytics SDK.

## What we will not do

- We will not add telemetry that phones home. There is no analytics endpoint.
- We will not add a "convenience" path that lets the model approve its own tool calls.
  Any such change is rejected on principle, not on review.
