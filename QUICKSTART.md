# Quick Start

[简体中文](QUICKSTART.zh-CN.md) · [English](QUICKSTART.md)

Get PocketMind running in five minutes on a desktop, or in about twenty on a phone.

## Try it on a desktop (no phone, no API key)

```bash
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
python3 -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r agentd/requirements.txt

AGENT_HOME=/tmp/pa python3 -m agentd.main --mock --port 8787
```

Open <http://127.0.0.1:8787> and send **"check my battery"**. You will see the full
pipeline: streaming reasoning → tool call → approval card → structured result.

`--mock` needs no API key. It is what the demo recording was made with.

## Install it on your phone

Requires **Termux from F-Droid** (not the Play Store build) and **Termux:API**.

```bash
pkg install git python termux-api
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
bash termux/install.sh      # 10–30 min on a phone: pydantic-core compiles once
bash termux/start.sh        # http://127.0.0.1:8787
```

Then open that address in the phone's browser:

1. **⚙ Settings** → choose a provider (DeepSeek / Doubao / Qwen / Kimi / Zhipu /
   SiliconFlow, or "custom" for any OpenAI-compatible endpoint).
2. Paste your API key → **Save**.
3. Send a message.

Optional: `bash termux/boot.sh --install` makes it start on boot.

### Reaching it from your laptop

```bash
bash termux/start.sh --lan
```

The first `--lan` start generates an access token and prints it. The web page asks for
it on first load. **A token is a bearer credential** — do not enable LAN mode on a
network you do not trust. See [SECURITY.md](SECURITY.md).

## Install the APK

```bash
bash scripts/build-apk.sh            # debug APK, verified, adb-installable
bash scripts/build-apk.sh --release  # unsigned unless keystore.properties exists
```

The APK is a WebView shell: an app icon, a foreground service and a native clipboard
bridge. The agent itself still runs in Termux — the shell does not bundle Python.

## Configure

| Setting | Where |
|---|---|
| Provider / model / API key | Settings panel, or the `llm` block in `$AGENT_HOME/config.json` |
| Permission mode | Settings → Agent mode: `approve` (default), `auto`, or `chat` |
| Approval timeout | `server.approval_timeout`, default 120 s |
| Access token | Generated on first `--lan`; reset with `python -m agentd.main --clear-token` |
| Data directory | `AGENT_HOME`, default `~/.agent/termux-agent` |

## What to try first

| Say this | What happens |
|---|---|
| `check my battery` | `safe` tool, runs immediately |
| `read my last 3 SMS` | `write` tool — you get an approval card first |
| `send a notification saying hello` | `write` tool, approval, then the notification appears |
| `what is 2+2` | no tool, just a reply |
| `run ls -la` | `danger` tool, approval, shell output as a structured card |

If a tool needs a capability your device lacks (e.g. SMS on a desktop), you get a
structured error explaining what is missing, not a silent failure.

## Verify your install

```bash
bash tests/run_all.sh --backend   # backend suites
bash tests/run_all.sh             # + frontend typecheck, tests, build, dist freshness
```

Exit code `0` means everything passed. Details in [smoke/README.md](smoke/README.md).

## Where to go next

- [README.md](README.md) — what it is and what it can do
- [docs/API.md](docs/API.md) — every endpoint
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how it is built
- [docs/capability-matrix.md](docs/capability-matrix.md) — which tools work on which device
- [ROADMAP.md](ROADMAP.md) — what is next, and what is deliberately not planned
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to change it
