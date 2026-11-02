# 安全策略

[English](SECURITY.md) · [简体中文](SECURITY.zh-CN.md)

## 报告漏洞

**不要开公开 Issue。** 公开披露未修复的漏洞会让这个项目的每一个用户都置于风险中。

请通过以下任一私有渠道上报：

- **GitHub Security Advisories**（镜像仓库）—— 首选，支持私密讨论与协同披露。
- 开一个只说「发现安全问题，请提供私下沟通渠道」的公开 Issue —— 不写细节、
  不写复现步骤、不写受影响版本。

私有报告里请包含：

1. 受影响版本（`GET /api/health` 会返回）。
2. 攻击前提——同设备？同局域网？是否需要恶意页面？
3. 复现步骤或 PoC。
4. 影响：攻击者能读到、写到、执行到什么。

我们会在 7 天内确认收到，核实影响并商定披露时间。除你要求匿名外会署名致谢。

## 支持的版本

只有最新发布版本（`main` HEAD 或最新 `v*` tag）接受安全修复。本项目不做回溯移植。

## 威胁模型 —— 部署前请读完

口袋智灵是**单用户、单设备**的 Agent。它的安全模型建立在这一点上，
并不打算成为一个多租户服务。

### 实际存在的边界

| 边界 | 机制 | 位置 |
|---|---|---|
| 网络 | 默认只绑 `127.0.0.1`。`--lan` / `--host 0.0.0.0` 在无 `server.token` 时**拒绝启动**。 | `agentd/main.py` |
| 鉴权 | 除 `/api/health` 与静态资源外，所有端点要求 `Authorization: Bearer`，常量时间比较。 | `agentd/main.py` |
| 跨站 | 任何带 `Origin` 的请求必须同主机，覆盖所有方法——这挡住了可能静默改写状态的无 body「简单请求」。 | `agentd/main.py` |
| 工具审批 | 服务端强制：`safe` 放行、`write`/`danger` 需确认、超时自动拒绝。全局拒绝名单在卡片渲染之前就短路。 | `agentd/permissions.py`、`agentd/approval.py` |
| 文件访问 | 读写限定在 `$HOME`、`~/storage`、`/sdcard`。`.agent`、`.ssh`、`.gnupg` 与数据目录一律拒绝，含子路径与符号链接解析。 | `agentd/tools/files.py` |
| SSRF | `read_url` 先解析 DNS，再按 IP 段判定私有/元数据地址，限制端口，并在每次重定向时重新校验。 | `agentd/tools/net.py` |
| 密钥落盘 | API Key 与令牌只存在本机 `config.json`（600）。所有 API 响应都打码；导出不含。 | `agentd/config.py`、`agentd/routes/__init__.py` |
| 数据落盘 | SQLite 目录 700、文件 600，WAL 同样权限。 | `agentd/memory.py` |

### 已知限制 —— 这些不是漏洞

- **不做提示注入过滤。** `read_url`、`read_sms` 返回的文本可能夹带指令。防线是审批
  闸门加工具收窄，不是内容扫描。用 `auto` 模式处理不可信内容是超出安全模型的用法。
- **`run_shell` 是任意代码执行**，一旦批准即生效。请看清命令。
- **局域网模式意味着任何持有令牌的设备都能调用全部端点**，包括 shell。无令牌时它拒绝
  启动，但令牌本身就是持有者凭证：一旦泄露，部署即失守。不要在不可信网络开启；
  公网暴露请在前面加 HTTPS（见 `DEPLOY.md`）。
- **APK 承载 WebView。** WebView 加载本机 Agent 界面。WebView 自身漏洞、或能读取进程
  内存的已 root 设备，不在威胁模型内。
- **Telegram 网关信任白名单。** 白名单里的任何 `chat_id` 都能驱动 Agent。
  Telegram 账号被攻破，等于 Agent 被攻破。

### 如果你要部署到个人手机之外

- 前面加 TLS 终止反代，绝不要直接暴露 `uvicorn`。
- 设置足够长的随机 `server.token`；若曾出现在日志或截图里，立即轮换。
- 用非特权用户运行，使用独立数据目录。
- 不需要 shell 就关掉它（`chat` 权限模式完全不注册工具）。

## 依赖与供应链

- 运行时依赖在 `agentd/requirements.txt` 里锁主版本；约束永远无法满足时
  `tests/check_requirements.py` 会让门禁失败。
- 漏洞扫描：`python smoke/p22_dep_audit_test.py`（OSV.dev，需外网）或
  `bash tests/run_all.sh --with-dep-audit`。
- Dependabot 已配置 npm / pip / GitHub Actions，按月分组、限制同时开启的 PR 数。
- Android 壳**零第三方运行时依赖**是设计决定：不引 Capacitor、不引 Flutter、
  不接任何分析 SDK。

## 我们不会做的事

- 不会加入任何回传数据的遥测。没有分析端点。
- 不会加入任何让模型自己批准自己工具调用的「便利」路径。这类改动在原则层面就被拒绝，
  不需要走评审。
