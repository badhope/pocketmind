# 部署指南

[English](DEPLOY.md) · [简体中文](DEPLOY.zh-CN.md)

口袋智灵是单进程部署：FastAPI 同时提供 API 与构建好的前端，因此部署不需要 Node 工具链。

> **先读 [SECURITY.md](SECURITY.md)。** 这是单用户 Agent。一旦暴露到网络，
> 访问令牌就是「互联网」与「该主机上的任意 shell 执行」之间唯一的东西。

## 方式一 —— VPS 一条命令（推荐）

```bash
git clone https://gitcode.com/badhope/pocketmind && cd pocketmind
bash deploy/deploy.sh
```

脚本会装依赖、建数据目录（700）、生成随机访问令牌、以 `--lan` 模式启动服务，
并在 80 端口配置 nginx 反代。nginx 这一步需要 root；普通用户运行会退化为 `nohup`
并跳过反代。

部署后：

- 打开 `http://<服务器IP>/`。
- 若页面要求访问令牌，填入脚本打印的那一串，之后浏览器会记住。
- **界面上显示的令牌是打码的**（`sk-1****ef`），无法从页面取回明文。忘了就在服务器上执行
  `python3 -m agentd.main --clear-token`，然后重设。
- 设置 → 选提供商 → 填 API Key → 保存。

## 方式二 —— Docker

```bash
export AGENT_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker compose up -d
```

打开 `http://<服务器IP>:8787`。

`AGENT_TOKEN` **只在首次启动**（卷里还没有令牌时）写入 `config.json`。之后以卷里的配置为准
——改环境变量不会覆盖你在界面上设过的令牌。

## 方式三 —— 手动（快速验证）

```bash
pip install -r agentd/requirements.txt

# --lan 无令牌会拒绝启动（这是故意的）
AGENT_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" \
  python3 -m agentd.main --lan --port 8787
```

然后本机访问 `http://127.0.0.1:8787`，或从网络访问 `http://<IP>:8787`（带 Bearer 令牌）。

## 生产检查清单

| 项 | 建议 |
|---|---|
| **HTTPS** | 在前面终止 TLS：nginx + certbot（Let's Encrypt）、云厂商证书，或 Docker 前置 Caddy。绝不直接暴露 uvicorn。 |
| **访问令牌** | 脚本生成 48 位随机十六进制。若曾出现在日志、截图或聊天里，立即轮换。 |
| **备份** | 备份整个 `AGENT_HOME` 目录（默认 `~/.agent/termux-agent`），含会话、记忆、定时任务与 checkpoints。`GET /api/export` 可导出可移植的 JSON 包。 |
| **资源** | 个人使用单进程足够：Python + SQLite 大约 150–300 MB 常驻内存。 |
| **进程守护** | `deploy/systemd/` 提供 unit 文件。主机要重启就用它，不要用 `nohup`。 |
| **SSE 与反代** | 任何反代都必须关闭响应缓冲，否则流式回复会在最后一次性到达。`deploy/nginx/agentd.conf` 已设 `proxy_buffering off`。 |
| **防火墙** | 只暴露反代端口。若前面有反代，`8787` 应只绑本机。 |

## 反向代理

`deploy/nginx/agentd.conf` 是一份可用配置。关键部分：

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;

    # SSE 必需。不关掉缓冲，流会被攒住，界面看起来像卡死。
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}
```

## Docker 说明

- 镜像跑单进程，前端在构建时打进镜像。
- 在 `/data` 挂卷并设 `AGENT_HOME=/data`，容器重建后会话才不会丢。
- `config/recipes.json` 会被复制进镜像——它从仓库根目录读取。

## 部署**不是**什么

- **不是多租户。** 没有用户模型。一个令牌、一个用户、一个数据目录。不要拿来给多人用。
- **不是对恶意模型的加固方案。** 提示注入不在防护范围内；缓解手段是审批闸门，
  而它假设第一次危险工具运行时有人在场。
- **不是 shell 沙箱。** `run_shell` 以服务用户身份执行。请用非特权用户 + 独立 home 目录运行。
