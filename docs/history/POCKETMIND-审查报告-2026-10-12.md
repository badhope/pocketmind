# PocketMind（口袋智灵）严格检验报告

> **历史快照（2026-10-14 之前）**：本文记录的是当时代码的状态与结论，部分内容已被后续修复/新增取代；
> **现状请以 [CHANGELOG.md](CHANGELOG.md) 与代码为准**。保留本文仅供追溯当时的判断依据。

> 审查日期：2026-10-12
> 审查对象：`poketmind` 工作区（Android Termux 常驻 AI Agent，Python FastAPI 后端 + React/Vite/TS 前端）
> 审查方式：源码走读 + 实际运行基线（后端 lint/编译/测试、前端 typecheck/test/build、mock 服务端到端冒烟）

---

## 一、结论摘要

**当前工作区状态：功能架构完整、安全意识良好，但不能按文档直接部署，且存在 1 个真实鉴权漏洞与多项「报告全绿但实际失败」的不一致。**

- 按 `requirements.txt` 安装依赖后，服务**无法启动**（缺 `slowapi`、`opentelemetry-sdk`、`opentelemetry-instrumentation-fastapi`，实测两次 `ModuleNotFoundError`）。
- `/api/memory/search` 端点**缺少鉴权依赖**：设置访问令牌后，无令牌仍可访问（实测 200，而其他端点 401）。`--lan` 暴露到局域网时，任意设备可搜索本机长期记忆。
- 11 个后端 smoke 套件中，**4 个当前失败**（`m5_regression`、`p0_queue_test`、`p2_plan_recipe_test`、`p3_robustness_unit`）。
- `FINAL_REPORT.md` 声称「全绿 ✅」，与实际运行结果不符；README 徽章「141 backend tests」与实际测试规模不符。

---

## 二、验证执行情况（实际跑了什么）

| 验证项 | 命令 | 结果 |
|---|---|---|
| Python 编译检查 | `python -m compileall agentd/` | ✅ 通过 |
| 静态检查 | `ruff check agentd/ tests/ smoke/` | ✅ All checks passed |
| 前端类型检查 | `npx tsc -b --noEmit` | ✅ 0 错误 |
| 前端单测 | `npm run test`（vitest） | ✅ 7 文件 31/31 通过 |
| 前端生产构建 | `npm run build` | ✅ 253KB JS(gzip 85.8KB) + 22KB CSS |
| 后端 smoke：p0_regression | `python smoke/p0_regression_test.py` | ✅ 7/7 |
| 后端 smoke：security | `python tests/security_test.py` | ✅ 37 断言全过 |
| 后端 smoke：p3_perf_leak | `python smoke/p3_perf_leak_test.py` | ✅ 5 过 / 1 跳过 |
| 后端 smoke：test_mcp_server | `python smoke/test_mcp_server.py` | ✅ 10/10 |
| 后端 smoke：test_telegram_gateway | `python smoke/test_telegram_gateway.py` | ✅ 18/18 |
| 后端 smoke：p1_jobs | `python smoke/p1_jobs_test.py` | ✅ 8/8 |
| 后端 smoke：p3_robustness_api | `python smoke/p3_robustness_api.py` | ✅ 6/6 |
| 后端 smoke：p3_robustness_unit | `python smoke/p3_robustness_unit.py` | ❌ **ImportError：`agentd.main._masked_settings` 已不存在** |
| 后端 smoke：m5_regression | `python smoke/m5_regression.py` | ❌ **4 过 / 2 失败**（审批流未触发） |
| 后端 smoke：p0_queue_test | `python smoke/p0_queue_test.py` | ❌ **失败**（审批流未触发 + 404） |
| 后端 smoke：p2_plan_recipe | `python smoke/p2_plan_recipe_test.py` | ❌ **12 过 / 1 失败**（GET settings 缺 agent_mode） |
| pytest 收集 `tests/` | `python -m pytest tests/` | ⚠️ 本机嵌入式 Python 环境收集失败（`No module named 'agentd'`；CI 实际不走 pytest 收集） |
| mock 服务冒烟 | `python -m agentd.main --mock --port 8799` | ✅ 服务可启动（补装缺失依赖后），`/api/health` 全组件 ok |

> 说明：本机 Python 是 AutoClaw 嵌入式环境，`sys.path` 不含 cwd，`pytest tests/` 收集失败属环境特性；但 CI 中也没有任何一步运行 `pytest tests/`，因此 `tests/eval_approval.py` 的「pytest 6 个单测」在 CI 门禁中实际未被覆盖（CI 只直接执行 `tests/security_test.py` 与 `smoke/*.py`）。

---

## 三、关键发现

### P0-1（严重）依赖清单不完整，按文档部署无法启动服务

**证据链：**

1. `agentd/requirements.txt` **没有** `slowapi`，但 `agentd/main.py` 的 `create_app()` 无条件执行 `from .rate_limit import mount_rate_limit` → `import slowapi`。
2. `requirements.txt` **没有** `opentelemetry-sdk` 与 `opentelemetry-instrumentation-fastapi`，但 `agentd/main.py` 的 `create_app()` 无条件执行 `setup_telemetry(app, ...)`，`telemetry.py` 顶层导入 `opentelemetry.sdk.*` 与 `opentelemetry.instrumentation.fastapi`。
3. 实测（按 `pip install -r agentd/requirements.txt` 后）：
   - `from agentd.main import create_app` → `ModuleNotFoundError: No module named 'slowapi'`
   - 补装 slowapi 后再次启动 → `ModuleNotFoundError: No module named 'opentelemetry.sdk'`
4. 影响面：README「Quick Start」、`termux/install.sh`、`DEPLOY.md`、`Dockerfile`、CI 的安装命令全部使用同一份 `requirements.txt`，因此**手机 Termux、Docker、VPS 三种部署路径都会在启动阶段崩溃**。

**修复建议：** 在 `requirements.txt` 增加 `slowapi`、`opentelemetry-sdk`、`opentelemetry-instrumentation-fastapi`（如需 OTEL OTLP 导出再加 `opentelemetry-exporter-otlp-proto-grpc`）；同时 `litellm` 若作为可选能力，也应在文档/注释中明确安装命令（`litellm_client.py` 是懒加载，缺它只在开启 LiteLLM 时崩溃）。

---

### P0-2（严重）`/api/memory/search` 缺少鉴权，局域网可搜隐私记忆

**证据链：**

1. `agentd/main.py` 中：
   - `GET /api/memory`、`DELETE /api/memory/{id}`、`POST /api/memory/reset` 均带 `dependencies=[Depends(check_token)]`；
   - **`GET /api/memory/search` 没有该依赖**（唯一漏网的 memory 端点）。
2. 实测：设置 `server.token="<synthetic-token>"` 后：
   - 无令牌 `GET /api/memory/search?q=test` → **200**
   - 无令牌 `GET /api/memory`、`/api/sessions`、`/api/settings`、`/api/jobs`、`/api/tools`、`/api/export` → 全部 401
3. 影响：`--lan`/Docker 公网部署时，任意设备无需令牌即可搜索本机长期记忆（Mem0 语义记忆可能含用户隐私）。
4. 另外两个无鉴权端点（低危信息泄露）：`/api/health`（泄露 DB/LLM/Mem0/Scheduler 组件状态与 `active_runs`）、`/api/providers`（provider 预设，非敏感）。

**修复建议：** 给 `/api/memory/search` 补上 `dependencies=[Depends(check_token)]`；健康检查若需对外探活，可保留但收敛返回字段。

---

### P1-1（功能）`GET /api/settings` 不返回 `agent_mode`，Plan/Act 状态无法从服务端恢复

**证据链：**

1. `agentd/routes/__init__.py` 的 `masked_settings()` 按 `DEFAULTS` 顶层 key 遍历输出；`agentd/config.py` 的 `DEFAULTS` **没有顶层 `agent_mode`**（它只在 pydantic 模型 `AppSettings` 里，默认 `"act"`）。
2. 实测：`PUT /api/settings {"agent_mode":"plan"}` 后 `GET /api/settings` 返回 keys 为 `['agent','context','eval','litellm','llm','mcp_client','mem0','memory','permission_mode','server','user_prefs']`，**无 `agent_mode`**。
3. 影响：前端 `useAgentStore.init()` 读取 `s.agent_mode` 永远为 `undefined`，Plan/Act 模式只能靠本地 localStorage 恢复，换设备/清缓存后丢失；`p2_plan_recipe_test` 对应断言失败。

**修复建议：** 在 `DEFAULTS` 增加顶层 `"agent_mode": "act"`（与 pydantic 默认一致），或在 `masked_settings()` 显式补 `agent_mode`。

---

### P1-2（功能/测试）`mock_llm` 正则 `打电话|呼叫` 无法匹配「打个电话」

**证据链：**

1. `agentd/mock_llm.py` 拨号规则为 `re.compile(r"打电话|呼叫")`；消息「给10086打个电话」中间有「个」字，正则不命中。
2. 实测 `MockLLM._match_tool('给10086打个电话')` → `None`；`_match_tool('呼叫一下')` → `make_call`。
3. 影响：mock 演示模式与多套 smoke 测试依赖此匹配触发拨号审批流程：
   - `m5_regression.py`：`❌ danger 工具弹审批`、`❌ 审批回调已发拒绝`（第 4b「审批超时自动拒绝」是**假阳性**——实际根本没触发工具，`tool_ok` 恒为 False）；
   - `p0_queue_test.py`：第一轮没有挂起审批，第二轮未排队，随后 `POST /api/approval` 返回 404。
4. 该问题在 CI（Linux + faketermux 桩）同样会失败，说明 CI 门禁实际并未全绿，或从未以当前代码重新跑过。

**修复建议：** 将正则改为 `re.compile(r"打电话|打个电话|呼叫")`（或 `"电话"`），并让 mock 演示更接近真实表达。

---

### P1-3（功能）`--home` 参数无法真正重定位数据目录

**证据链：**

1. `agentd/main.py` 模块底部 `app = create_app()` 在 import 时执行，此时 `settings_mgr.path` 还是默认路径（`~/.agent/termux-agent`），`store = Store(settings_mgr.path.parent / "agent.db")` 已用默认路径创建。
2. `main()` 中 `--home` 只修改 `settings_mgr.path`，但 `store`/`scheduler_svc`/`checkpoints` 等已在 `create_app()` 里绑定旧路径。
3. 影响：`--home /xxx` 启动后，`config.json` 写到新目录，而 `agent.db`/`jobs.db`/`checkpoints.db` 仍在旧目录，造成**数据分裂**。`--clear-token --home` 因只读 config 不受影响（CI 的部署自检因此仍通过）。

**修复建议：** 在 `main()` 解析完 `--home` 之后再执行 `create_app()`，或让 `create_app()` 接受数据目录参数；需要调整模块级 `store` 的初始化时机。

---

### P2-1（测试）`smoke/p3_robustness_unit.py` 引用已删除符号

- `smoke/p3_robustness_unit.py:133` `from agentd.main import _masked_settings, _validate_job_payload`
- 当前 `agentd/main.py` 已把这两个函数移到 `agentd/routes/__init__.py`（`masked_settings` / `validate_job_payload`），导致 ImportError。
- HEAD 版本 main.py 中函数仍在，说明**重构后测试未同步**。CI 引用该脚本，当前提交会直接红。

---

### P2-2（文档）`FINAL_REPORT.md` 与实际不符

| FINAL_REPORT 声称 | 实际情况 |
|---|---|
| `tests/eval_agent_utils.py`（110 行、7 测试）存在 | **文件不存在**（`find` 无结果） |
| `pytest 19/19 ✅ PASS` | 本机 pytest 收集 `tests/` 直接失败；CI 也无 pytest 收集步骤；实际后端测试规模与声明不符 |
| 「限流 30/min → 429」已上线 | `slowapi` 未写入 `requirements.txt`，部署即缺依赖 |
| 「OTEL setup_telemetry」已上线 | 依赖缺失，同样未声明 |
| 「全量验证 + 全绿」 | 当前工作区 4/11 个 smoke 套件失败 |

### P2-3（文档）README/报告与实现不一致的其他点

- README 徽章「141 backend + 31 frontend tests」：前端 31 属实；后端 141 与当前可运行的直接脚本断言规模（约 60-70 个断言点）不符，且 4 个套件当前失败。
- `termux/make-apk.md` 称 WebView 壳是「30 行 Java」，实际 `MainActivity.java` 约 478 行（功能更全，但文档过时）。
- README 声称「MCP Server … 31 tools」，实测 `GET /api/tools` count=31，一致。

### P2-4（工程状态）工作区是未提交的大变更集

- `git status`：37 个文件修改（+1793/−1431），`tests/` 重命名为 `smoke/`，新增 `rate_limit.py`、`telemetry.py`、`mcp_client.py` 等**未跟踪**文件。
- `agentd/web/dist` 与仓库版本不一致（重新 build 后产生新 hash 文件），CI 的「Verify dist freshness」步骤在提交前需要重新构建并提交。
- 结论：当前目录是一个未完成/未验证的变更集；若直接把工作区内容当作「已发布版本」，会踩到上述 P0 问题。

---

## 四、做得好的地方（客观肯定）

- **安全设计意识强**：服务端强制审批（`approval.py` + `permissions.py`，chat 模式连工具都不注册）、超时自动拒绝、`allow_always` 白名单持久化。
- **文件工具**有路径白名单（`$HOME`/`~/storage`/`/sdcard`，resolve 后校验）、危险操作自动备份 + 一键 undo（checkpoint/undo）。
- **shell 工具**：`bash -c`（不加载 profile）、rlimit 沙箱（内存/CPU/进程数）、进程组回收防后台残留。
- **SSRF 防护**：`_safe_http_url` 只放行 http/https。
- **前端 XSS 防护**：`DOMPurify` + `marked`，自动加 `rel="noopener noreferrer"` 防 tabnabbing。
- **API Key 打码**：GET 回传打码、PUT 时剔除占位值；数据目录 0700、DB 0600。
- **测试覆盖思路好**：安全专项、健壮性、性能/泄漏、Plan/Recipe、Telegram、MCP 都有脚本，pytest 单测覆盖审批中心。
- **APK WebView 壳**：零 AndroidX、错误页/前台服务监控/JS Bridge/状态恢复，质量不错。

---

## 五、总体评价与建议

1. **定性**：这是一个设计完整、代码可读性好、安全意识强的个人/团队项目；但**当前工作区不等于可交付状态**。若按 README 在 Termux 或 Docker 部署，会因依赖缺失直接启动失败；若启用局域网令牌，`/api/memory/search` 会让隐私记忆裸奔。
2. **优先修复顺序**：
   1. 补全 `requirements.txt`（slowapi + opentelemetry 系）并实测 `python -m agentd.main --mock` 可启动；
   2. 给 `/api/memory/search` 加 `check_token` 依赖，并补一条安全回归用例；
   3. `masked_settings` 补 `agent_mode`；
   4. 修 `mock_llm` 拨号正则；
   5. 同步 `smoke/p3_robustness_unit.py` 到新路由；
   6. 更新 `FINAL_REPORT.md`/README 徽章与实际测试结果一致；
   7. 修复 `--home` 数据目录分裂问题；
   8. 提交前重建 dist，让 CI「dist freshness」门禁通过。
3. **CI 信任度**：（当时）CI 门禁（`ci.yml`，已于 2026-10-18 移除，现由 `tests/run_all.sh` 承担）引用 11 个后端套件，但其中 4 个在当前代码上失败——建议先把 CI 跑绿作为提交硬门槛，并补上对 `tests/eval_approval.py` 的 pytest 收集步骤（在 CI 中显式 `python -m pytest tests/`）。