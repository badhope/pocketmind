# Deployment Guide

[简体中文](DEPLOY.zh-CN.md) · [English](DEPLOY.md)

PocketMind ships as a single process: FastAPI serves both the API and the pre-built
frontend, so a deployment needs no Node toolchain.

> **Read [SECURITY.md](SECURITY.md) first.** This is a single-user agent. If you expose
> it to a network, the access token is the only thing between the internet and
> arbitrary shell execution on that host.

## Option 1 — one command on a VPS (recommended)

```bash
git clone https://gitcode.com/badhope/pocketmind && cd pocketmind
bash deploy/deploy.sh
```

The script installs dependencies, creates the data directory (mode 700), generates a
random access token, starts the service in `--lan` mode, and configures an nginx
reverse proxy on port 80. Run it as root for the nginx step; as a normal user it falls
back to `nohup` and skips the proxy.

After deploying:

- Open `http://<server-ip>/`.
- If the page asks for an access token, paste the one the script printed. It is stored
  in your browser from then on.
- **The token shown in the UI is masked** (`sk-1****ef`). There is no way to recover the
  plaintext from the page. If you lose it:
  `python3 -m agentd.main --clear-token` on the server, then set a new one.
- Settings → pick a provider → paste your API key → save.

## Option 2 — Docker

```bash
export AGENT_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker compose up -d
```

Open `http://<server-ip>:8787`.

`AGENT_TOKEN` is written into `config.json` **only on first start**, when the volume has
no token yet. After that the volume's config wins — changing the environment variable
will not overwrite a token you set in the UI.

## Option 3 — manual (for a quick check)

```bash
pip install -r agentd/requirements.txt

# --lan without a token refuses to start (that is deliberate)
AGENT_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" \
  python3 -m agentd.main --lan --port 8787
```

Then `http://127.0.0.1:8787` locally, or `http://<ip>:8787` from the network (with the
Bearer token).

## Production checklist

| Item | Guidance |
|---|---|
| **HTTPS** | Terminate TLS in front. nginx + certbot (Let's Encrypt), a cloud provider certificate, or Caddy in front of Docker. Never expose uvicorn directly. |
| **Access token** | The script generates 48 random hex characters. Rotate it if it ever appeared in a log, a screenshot or a chat. |
| **Backups** | Back up the whole `AGENT_HOME` directory (default `~/.agent/termux-agent`). It contains sessions, memory, scheduled jobs and checkpoints. `GET /api/export` gives a portable JSON bundle. |
| **Resources** | One process is enough for personal use: roughly 150–300 MB RSS with Python + SQLite. |
| **Process supervision** | `deploy/systemd/` has a unit file. Use it instead of `nohup` if the host reboots. |
| **SSE and proxies** | Any reverse proxy must disable response buffering, or streaming replies arrive all at once at the end. `deploy/nginx/agentd.conf` already sets `proxy_buffering off`. |
| **Firewall** | Only expose the proxy port. `8787` should be bound to localhost if a proxy sits in front. |

## Reverse proxy

A working nginx config is included at `deploy/nginx/agentd.conf`. The parts that matter:

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;

    # Required for SSE. Without this the stream is buffered and the UI appears frozen.
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}
```

## Docker notes

- The image runs as a single process; the frontend is baked in at build time.
- Mount a volume at `/data` and set `AGENT_HOME=/data` so sessions survive a recreate.
- `config/recipes.json` is copied into the image — it is read from the repository root.

## What a deployment is *not*

- **Not multi-tenant.** There is no user model. One token, one user, one data directory.
  Do not host it for several people.
- **Not hardened against a hostile model.** Prompt injection is out of scope; the
  approval gate is the mitigation, and it assumes a human is watching the first time a
  dangerous tool runs.
- **Not a sandbox for shell.** `run_shell` executes as the service user. Run it as an
  unprivileged user with a dedicated home directory.
