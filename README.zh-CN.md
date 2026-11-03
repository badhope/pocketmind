<div align="center">
  <img src="assets/pocketmind-logo.png" width="440" alt="PocketMind Logo" />
  <h1>PocketMind · 口袋智灵</h1>
  <p><b>真正能操作你手机的常驻 Agent</b><br/>
  运行在设备内 · 41 个工具 · 服务端强制审批 · 本地优先存储</p>
  <img src="assets/badges/gate.svg" alt="本地门禁" />
  <img src="assets/badges/platform.svg" alt="Platform" />
  <img src="assets/badges/tools.svg" alt="41 tools" />
  <img src="assets/badges/language.svg" alt="Language" />
  <img src="assets/badges/license.svg" alt="License" />
  <br />
  <sub>Web UI · PWA · APK · MCP 服务端与客户端 · Telegram 网关 · 定时配方 · Plan/Act 双模式 · 备份恢复</sub>
</div>

[English](README.md) | **简体中文**

---

## 它是什么

口袋智灵是**住进你 Android 手机里**的 Agent。它不是远程遥控器，也不是聊天套壳。
它把手机自身的能力——短信、电话、定位、传感器、通知、剪贴板、WiFi、手电筒、
相机、shell、文件——变成结构化工具来调用，并且在执行任何破坏性动作前先问你。

它记住的一切都留在设备上。离开设备的只有推理请求。

```
你 ──▶ Web UI / PWA / APK ──▶ agentd（Python 单进程）──▶ 工具 ──▶ 你的手机
                                      │
                                      └──▶ 你配置的 LLM 端点（仅推理）
```

## 看它跑起来

![口袋智灵演示](docs/demo/pocketmind-demo.gif)

<sub>真实录屏，离线 mock 模式，无需 API Key：空态 → 工具调用 → 服务端审批 → 结果回填
→ 会话管理 → 设置 → 实时统计 → 定时任务 → 深色主题 → 手机视口。
完整视频：[docs/demo/pocketmind-demo.mp4](docs/demo/pocketmind-demo.mp4)（40 秒）。</sub>

## 真正重要的部分

### 审批是安全边界，不是弹窗

每个工具都带风险等级——`safe`、`write`、`danger`。这道闸门在**服务端请求路径上**
执行，早于工具运行。模型绕不过去，被注入的页面也绕不过去。

- **五档决策**，不是两档：允许一次 · 本会话始终允许 · 全局允许 · 拒绝 · 始终拒绝。
- **拒绝优先于允许**：全局拒绝名单在卡片弹出之前就短路。
- **不响应即拒绝**：超时（默认 120 秒，可配置）自动拒绝。

<p align="center">
  <img src="assets/screenshots/02-approval.png" width="880" alt="审批卡：五档决策与倒计时" />
  <br />
  <sub>工具名用大白话，风险徽章、主决策与永久决策分区，实时倒计时。
  模型没有任何路径可以绕过这张卡。</sub>
</p>

### 覆盖手机的 41 个工具

| 分组 | 数量 | 例子 |
|---|---|---|
| 手机能力（termux-api） | 25 | `send_sms` · `read_sms` · `make_call` · `get_location` · `take_photo` · `get_wifi_info` · `scan_wifi` · `list_sensors` · `get_clipboard` · `set_torch` · `download_file` |
| 文件 | 4 | 读 / 写 / 列 / 删，限定在 `$HOME`、`~/storage`、`/sdcard`，并对 `.ssh`、`.gnupg`、`.agent` 设拒绝名单 |
| 任务与编排 | 3 | `todo_write` · `todo_clear` · `subagent` |
| 知识库 | 3 | `kb_add` · `kb_search` · `kb_list`——FTS5 trigram，只在显式调用时检索 |
| 长期记忆 | 1 | `remember_fact`——结构化 USER/FACT/JOURNAL 文件 |
| 技能 | 1 | `read_skill`——从 `SKILL.md` 渐进披露 |
| Shell | 1 | `run_shell`——`danger` 级，永远需要审批 |
| 语音 | 1 | `tts_speak` |
| 联网 | 2 | `web_search` · `read_url`——SSRF 加固，标 `write` 因为查询词会离开设备 |

<p align="center">
  <img src="assets/screenshots/05-capabilities.png" width="880" alt="能力页按分组列出 41 个工具及风险等级" />
  <br />
  <sub>每个工具都能在应用里浏览并看到风险等级——41 个里有 3 个标记为破坏性，
  永远需要确认。</sub>
</p>

### 结果是结构化的，不是一段话

每个工具都返回退出码、stdout、stderr、超时标记和耗时。失败就是失败：工具卡变红，
助手也会被告知这次调用没有成功。

<p align="center">
  <img src="assets/screenshots/03-tool-result.png" width="880" alt="工具结果卡：结构化输出与完成状态" />
  <br />
  <sub>上面是工具入参，下面是结构化结果，回复附带耗时与 token 消耗。一键复制。</sub>
</p>

### 不需要电脑也能跑

构建好的 `dist/` 随仓库分发，手机端不需要 Node 工具链。`termux/install.sh` 一次性
装好 Python 与依赖，`termux/start.sh` 启动服务，`boot.sh` 开机自启。可以用 PWA，
也可以装 APK 获得独立图标、前台服务与原生剪贴板桥。

### 重启也不会丢的定时任务

任务落在 SQLite，启动时恢复。触发器支持 `interval`、`cron`，以及**电量条件**
（`battery < 20`）。内置四个配方——低电量提醒、每日总结、每日天气、午餐提醒——
一键实例化。任务可以「立即执行」，不必等触发点，这也是确认任务真的能跑通的唯一办法。

<p align="center">
  <img src="assets/screenshots/07-timers.png" width="880" alt="定时任务与配方模板" />
  <br />
  <sub>上方是配方，下方是运行中的任务，各自带下次运行时间、条件与「立即执行」。</sub>
</p>

### 真的能恢复的备份

`GET /api/export` 导出全部会话（按页取尽，绝不静默截断）、结构化档案记忆、
每会话任务清单与知识库。`POST /api/import` 合并回去：已存在的会话跳过、知识去重、
导入的任务默认停用（避免恢复瞬间全部触发）、坏文件返回 400 且不写入半截数据。

### 两种 Agent 模式

- **Act（默认）**——直接调用工具执行。
- **Plan**——先输出执行计划（含打算调用的工具），什么都不跑，等你点
  **批准并执行** 或 **修改计划**。

### 双向远程控制

- **MCP 服务端**——把全部 41 个工具通过 stdio 暴露给 Claude Desktop、Cline 或 Codex。
  这是与 Web 审批中心分离的接口，桌面宿主必须自己做确认；每个工具描述都带风险前缀。
- **Telegram 网关**——在聊天里驱动手机。写操作与危险操作以「允许/拒绝」按钮下发；
  只有白名单里的 `chat_id` 会被响应。

### 花多少 token 一目了然

每条回复都显示端点回报的 token 用量，端点不回报时标注「估」。`/api/stats` 汇总请求数、
工具失败、LLM 调用与 token；`/api/metrics` 用 Prometheus 格式暴露同样的计数器。

## 诚实的边界

这是单用户、单设备的 Agent。这决定了安全模型，也决定了限制：

- **不做提示注入过滤。** `read_url`、`read_sms` 返回的文本可能夹带指令。防线是审批闸门
  与工具收窄，不是内容扫描。不要在 `auto` 模式下处理不可信来源。
- **`run_shell` 是任意代码执行**，一旦批准就生效。看清命令再点。
- **局域网模式**（`--lan`）意味着任何拿到令牌的设备都能调用全部端点，包括 shell。
  无令牌时它拒绝启动。不要在不可信网络开启；公网暴露请在前面加 HTTPS（见 `DEPLOY.md`）。
- **设备能力有差异。** 短信、电话、定位、传感器、电量、相机需要真机 + `termux-api`。
  聊天、文件、记忆、清单与调度在任何环境都能用。查
  [docs/capability-matrix.md](docs/capability-matrix.md) 或在设备上跑 `GET /api/diagnostics`。
- **APK 自带原生 Kotlin 引擎（1.4.0 起）。** 核心功能不装 Termux 即可用；
  原生调度器、MCP 与 Termux 可选桥是 1.5 的切片——见 [ROADMAP.md](ROADMAP.md)。

## 安装

### 方案 A —— 只装 APK，不需要 Termux（推荐，1.4.0 起）

APK 原生运行整个 Agent：安装 → 打开 → 选提供商 → 填 API Key → 开聊。
手机上不需要 Termux、Python 或任何工具链。

```bash
bash scripts/build-apk.sh            # debug APK，已校验、可直接安装
bash scripts/build-apk.sh --release  # 没有 keystore.properties 时是未签名件
```

原生引擎当前覆盖：对话（SSE）、三种权限模式、服务端强制审批、26 个
手机/文件/知识库工具（Android API 直调）、会话、导出导入、搜索。
暂未覆盖且会**明确告知**（不伪成功）：shell 命令、定时任务、MCP、Telegram。
需要这些请用方案 B。

### 方案 B —— Termux 完整 41 工具运行时

```bash
# 在 Termux（F-Droid 版，另装 Termux:API）里一次性准备
pkg install git python termux-api
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
bash termux/install.sh      # 装依赖 + 建数据目录；pydantic-core 会编译一次（真机 10-30 分钟）

# 启动
bash termux/start.sh        # http://127.0.0.1:8787
```

打开该地址，然后 **⚙ 设置 → 选一个提供商 → 填 API Key → 保存**。

> 想从同一 Wi-Fi 的其他设备访问：`bash termux/start.sh --lan`。
> 首次运行会生成令牌，页面会向你要。

### 方案 C —— 本机试跑，不需要手机

```bash
cd pocketmind
AGENT_HOME=/tmp/pa python3 -m agentd.main --mock --port 8787
# 打开 http://127.0.0.1:8787，发「查看电池电量」看整条链路
```

`--mock` 不需要 API Key，会走完流式输出、工具调用、审批与结果回填。
上面的演示 GIF 就是用它录的。

## 配置

| 项目 | 位置 |
|---|---|
| LLM 提供商、模型、API Key | 设置面板，或 `$AGENT_HOME/config.json` 的 `llm` 块 |
| 权限模式（`auto` / `approve` / `chat`） | 设置 → Agent 模式 |
| 审批超时 | `server.approval_timeout`（默认 120 秒） |
| 访问令牌 | 首次 `--lan` 启动时生成；`python -m agentd.main --clear-token` 重置 |
| 数据目录 | `AGENT_HOME`（默认 `~/.agent/termux-agent`） |

凭据除随请求发往你配置的端点外不会离开设备。所有 API 响应都会打码
`api_key`、`server.token`、`server.tg_token`；导出文件不含它们。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `agentd/` | 后端：FastAPI 应用、Agent 循环、工具、存储、调度 |
| `agentd/web/` | React + TypeScript 前端（Vite）。构建好的 `dist/` 入库，因为手机端不构建 |
| `termux/` | 手机侧脚本：`install.sh`、`start.sh`、`boot.sh`、`uninstall.sh` |
| `termux/apk-shell/` | Android WebView 壳（零第三方依赖） |
| `smoke/` | 回归套件，全部由 `tests/run_all.sh` 驱动 |
| `config/recipes.json` | 预设任务模板 |
| `docs/` | API 参考、架构、能力矩阵、审计记录、版本策略 |
| `website/` | 自包含落地页 |

## 自己验证

一条命令跑完整个门禁。它是权威实现，CI 只负责装依赖并调用它。

```bash
bash tests/run_all.sh              # 后端 + 前端
bash tests/run_all.sh --backend    # 只跑后端
bash tests/run_all.sh --with-e2e   # 追加 Playwright（浏览器装一次）
```

只有全部通过时退出码才是 `0`，失败项会在末尾汇总列出。门禁覆盖全部 Python 套件
外加 `compileall`、`ruff` 与部署链路自检；前端 typecheck、单测、生产构建，以及
**`dist/` 新鲜度校验**（入库的产物必须与当前源码一致，因为手机端从不构建它）。

逐套件说明：[smoke/README.md](smoke/README.md)。
API 参考：[docs/API.md](docs/API.md)。
架构：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 项目治理

- [CHANGELOG.md](CHANGELOG.md) —— 每一项用户可见的改动，Keep a Changelog 格式
- [ROADMAP.md](ROADMAP.md) —— 现在交付什么、下一步是什么、明确不做什么
- [docs/VERSIONING.md](docs/VERSIONING.md) —— SemVer 策略、三处版本单源位置、
  发布清单、分支与标签规则
- [CONTRIBUTING.md](CONTRIBUTING.md) —— 如何提改动，以及一次发布怎么走
- [SECURITY.md](SECURITY.md) —— 安全模型与漏洞上报方式
- [docs/COMPETITORS.md](docs/COMPETITORS.md) —— 带来源的对比，包括本项目更弱的地方

## 许可

Apache-2.0 —— 见 [LICENSE](LICENSE)。
