# PocketMind（口袋智灵）— 项目知识备忘

> 本文档面向接手本仓库的开发者与 AI 助手。历史版本曾以 `aaif-goose/goose`（Rust）
> 为底座、走 ACP 协议，**该架构已整体废弃**，现在仓库里没有任何 Rust/goose/ACP 代码；
> 若在别处看到相关描述，一律以本文件与实际代码为准。

## 架构（一句话）

单个 Python 进程 `agentd`（FastAPI + uvicorn，纯 h11）跑在 Android Termux 上：LLM 只负责推理，
执行全部落在手机端（termux-api 工具 + shell + 文件），前端是已构建好的 React 静态页，
数据（会话/记忆/定时任务/checkpoint）全存本机 SQLite。

- 后端：`agentd/main.py`（HTTP/SSE/审批/静态托管）+ `agent.py`（Agent 循环）+
  `tools/`（phone 25 / files 4 / shell 1 / voice 1 / 知识库 3 / 任务清单 2 / 联网 2 / 子代理 1 / 档案记忆 1 / 技能 1，共 41）+ `memory.py` + `scheduler.py` +
  `checkpoints.py` + `config.py`（pydantic 校验 + JSON 落盘）+ `mcp.py` / `telegram_gateway.py`
- 前端：`agentd/web/`（Vite + React 18 + TS + Zustand），**`dist/` 入库**，手机端不需要 Node
- 官网：`website/index.html` 自包含单页（非应用，无发布流水线）
- 预设配方：`config/recipes.json`（`main.py` 按 仓库根/config 读取，Docker 里必须一起 COPY）

## 端口与目录

- agentd 默认 `8787`，只绑 `127.0.0.1`；`--lan` 才绑 `0.0.0.0`
- Vite 开发 `5173`（代理 `/api` → 8787）；`tests/run_all.sh` 起 mock 服务时自动挑空闲端口，
  单独跑某个 smoke 套件时默认打 `8799`，可用 `AGENTD_BASE` 覆盖
- 数据目录 `AGENT_HOME`，默认 `~/.agent/termux-agent`（config.json / agent.db / jobs.db / checkpoints.db）
- `AGENT_HOME` 与 `--home`：`settings_mgr` 是模块级单例，路径在 import 时解析，
  所以 **必须通过环境变量在启动前给定**；CLI `--home` 由 `main()` 显式重定位 path（否则会被静默忽略）

## 安全语义（改动前务必读）

- `server.token` 一旦非空，**所有** `/api/*`（含 127.0.0.1）都必须带 `Authorization: Bearer <token>`；
  这不是 `--lan` 才生效。前端 401 时会显示令牌输入口，忘记令牌用 `python -m agentd.main --clear-token` 恢复
- `--lan` 且无令牌 → 直接拒绝启动（`SystemExit(1)`）；容器/服务化用环境变量 `AGENT_TOKEN` 首次注入
- 响应体外发前统一走 `_masked_settings()`：`api_key`、`server.token`、`server.tg_token` 一律打码；
  GET 与 PUT 共用同一个函数，避免"GET 打码 / PUT 回明文"的自相矛盾
- 危险工具走审批门（`permission_mode`: auto/approve/chat）；文件类危险操作有 checkpoint 可 undo

## 本地跑起来

```bash
python3 -m pip install -r agentd/requirements.txt   # Python 3.11（门禁口径）
bash tests/setup_faketermux.sh                      # 造 termux-* 桩，否则手机工具全部报"未安装"
export PATH=/tmp/faketermux:$PATH
AGENT_HOME=/tmp/pa python3 -m agentd.main --mock --port 8787   # 离线全链路（不耗 API）
cd agentd/web && npm ci && npm run test && npm run build       # 改前端必须重建 dist 并入库
```

Windows 上可以跑后端与测试（`web/start.sh`、`tests/*.sh` 需要 Git Bash），但有平台边界：
`tests/m5_regression.py` 的 chmod 断言、`tests/p3_perf_leak_test.py` 的进程残留探测是 POSIX 专用，
非 POSIX 会自动记为「跳过」而不是失败。

## 测试

脚本式回归（无 pytest，自己统计 passed/failed，非零退出即失败）：

| 脚本 | 需要服务 | 覆盖 |
| --- | --- | --- |
| `p0_regression_test.py` | 否 | 重启恢复 tool_calls、$HOME 展开、FTS5 排除 |
| `security_test.py` | 否 | 路径穿越 / FTS 注入 / SSRF / chat 模式禁工具 / 打码 |
| `p3_robustness_unit.py` | 否 | 配置兜底、任务与配方共用校验、审批超时 clamp、undo |
| `m5_regression.py`、`p0_queue_test.py`、`p1_jobs_test.py`、`p2_plan_recipe_test.py`、`p3_robustness_api.py` | 是（8799） | 端到端 |
| `test_mcp_server.py`、`test_telegram_gateway.py` | 否 | MCP stdio 清单、TG 网关 |
| `p3_perf_leak_test.py` | 否 | N+1、索引、子进程回收（POSIX） |

上表**全部**由 `bash tests/run_all.sh` 统一调度（一条命令跑完，失败不中断、末尾汇总、有失败即非零退出），
另含"部署链路自检"（无令牌 `--lan` 拒启 / `AGENT_TOKEN` 引导 / `--clear-token`）与前端
typecheck + vitest + build + `dist/` 新鲜度校验。GitHub Actions 工作流已于 2026-10-18 移除，
门禁改为本地/CI 自建流水线都可直接调用这个脚本。

## 仓库与镜像（2026-11-03 起共 5 处）

主仓库 `gitcode.com/badhope/pocketmind`（remote `origin`），镜像 4 处：
Gitee `gitee.com/badhope/pocketmind`（remote `gitee`）、GitHub
`X33834/pocketmind`（`gh-x33834`）、`Morningstar202604/pocketmind`（`gh-morningstar`）、
`badhope/pocketmind`（`gh-badhope`）。五处内容应保持一致（同一 tree），
**push 到 gitcode 后同步其余镜像**：`git push <remote> main --tags`（`latest` 标签需 `--force`）。
GitHub 各账号的 PAT 由用户在会话中提供，未存入仓库配置——推送用一次性 URL 或凭据管理器；
本机代理的 schannel TLS 对 github.com 偶发握手失败，遇错可改用 GitHub REST API
（POST /git/tags + /git/refs）补推附注标签。仓库内已无 `.github/workflows`。

## 版本

- v1.3.0（2026-11-03）：首次运行引导向导、跨工具输出搜索（命中带 matched 类型）、
  结构化工具结果查看器、导出 format_version、mypy 清零并入门禁。
- 门禁注意事项：本机代理 Fake-IP DNS 会把 example.com 解析到 198.18.0.32（保留段），
  p13/p17 的 example.com 用例因此失败——属环境问题，非代码回归；其余套件全绿。
