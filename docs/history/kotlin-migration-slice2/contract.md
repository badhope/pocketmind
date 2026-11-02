# Kotlin 迁移契约（Slice 1）

## Slice 2：Kotlin 原生运行时的真实接口面

Slice 2 只交付「可信启动路径 + 失败回退 + 诚实的接口清单」，不迁移任何业务接口。判据是可执行断言，不是文档措辞。

### 当前真实可用（Kotlin 进程内服务）

| 端点 | 鉴权 | 行为 |
|---|---|---|
| `GET /` | 一次性 navigation nonce | 返回内置 `native_bootstrap.html` 并签发会话 Cookie；无/已用/过期 nonce 返回 403 |
| `GET /api/health` | 无 | 探活；`ok` 表示引擎响应，`ready:false` 表示核心业务尚未就绪 |
| `GET /api/native/status` | 有效会话 Cookie | 运行状态 + 已实现/未实现端点列表 |

除上述之外的所有 `/api/*`（含 `/api/chat`、`/api/tools`、`/api/settings`、`/api/sessions`、`/api/export`、`/api/import`、`/api/approval`、`/api/jobs` 及未知路径）在会话有效时返回 **503** `{"error":"unavailable","migration":"not_implemented"}`，无有效会话返回 401。健康检查里这些组件标为 `unavailable`，不得据此外推已迁移。

**明确未迁移**：聊天与 SSE、工具执行、设置读写、会话与消息、审批、定时任务、导出/导入、数据库与迁移。全部仍由 Python agentd 提供。

### 令牌交付方案

- 服务监听由系统分配的随机端口（`ServerSocket(0)`，仅 `127.0.0.1`）；不假定 8787。
- Service 仅绑定 `127.0.0.1` 的系统分配端口；`exported="false"` 限制应用外绑定。随机端口不是身份认证。
- Activity 通过 Binder 获取一次性、10 秒有效的 `navigation` URL。nonce 是短期持有者凭证，不能输出到日志；持有该 URL 的进程在它被消费前可以抢先兑换。
- 成功兑换后签发内存中的 `HttpOnly; SameSite=Strict` 会话 Cookie，服务停止时失效。`HttpOnly` 防止脚本直接读取 Cookie，但**不能阻止同源脚本代用户发起请求**；未来启用写接口前必须实施严格同源校验、CSRF 防护、审批和输入验证。
- 当前 bootstrap 是无脚本静态页，不提供聊天或其他业务；React 资源、CSP 和 API 调用链尚未接入。
- 主文档错误按当前导航 nonce 识别；旧导航错误不应覆盖新页面。真实设备的 WebView 回调时序仍待验证。

### 失败回退

服务未起、Binder 类型不符、引擎未 RUNNING、许可签发失败、主文档网络错误或 4xx/5xx、绑定中断（`onServiceDisconnected`/`onBindingDied`/`onNullBinding`）—— 一律切到状态面板并提供重试，绝不回退到「假定 127.0.0.1:8787 有合法服务」。Termux 兼容模式在普通 Android 下无法证明服务身份，显式不可用。

### 边界与残余风险

- 未在真机/模拟器验证（本机 KVM 无嵌套虚拟化）。Android 单测用 Robolectric 覆盖 Activity/Service 行为，HTTP 行为用真实 socket 测。
- WebView 自身漏洞、已 root 设备读取进程内存、以及调试版本开启的 `chrome://inspect`（`BuildConfig.DEBUG`）不在本模型防御范围内。
- 旧版遗留资产（`agentd_setup.html`、`agentd_error.html`）、`MonitorService`（固定 8787 探测）、`PocketNative` JS 桥、Termux `RUN_COMMAND` 通道与相关权限已在本切片删除。

---

# 迁移参照（Slice 1）

本文件冻结现有接口的迁移参照，不表示 Kotlin 服务已经实现。完整端点和字段以 [API.md](../../API.md)、`agentd/main.py` 和 `agentd/web/src/api.ts` 为准；`tests/test_kotlin_contract_reference.py` 与 Android 单测共用 `contract-vectors.properties`。

- REST：健康检查 `GET /api/health` 无需 token，返回 `ok`、`ready`、`components`；其他 API 的现有认证与同源策略见 API.md。`POST /api/chat` 接收 `message` 和可选 `session_id`；工具清单 `GET /api/tools` 返回 `tools` 和 `count`，工具包含 `name`、`description`、`risk`、`summary`、`timeout`、`group`。
- SSE：聊天使用 `text/event-stream`，`data: <json>\n\n` 帧；事件类型、字段和可选 `done.usage` 见 API.md 与前端 `ChatEvent`。不要把未来实现的行为当作当前服务能力。
- 安全：风险等级为 `safe`、`write`、`danger`；`approve` 模式下 write/danger 须审批，`auto` 不询问，`chat` 不注册工具且执行层拒绝工具调用。重试由 `risk == "safe"` 决定，并非运行时验证工具只读；仅异常或超时重试，工具主动返回错误不重试，write/danger 不自动重试。工具参数使用 JSON Schema。
- 审批记忆：`allow_once` 只放行当前调用；`allow_always` 按会话及工具记忆，`allow_global` 按工具跨会话记忆；`deny_always` 建立跨会话工具黑名单，拒绝优先于全局和会话白名单。名单写入 `approval_always.json`，写盘失败不会阻断当前运行，重启后可能恢复旧规则。审批默认 120 秒，可配置，超时拒绝。缺少审批回调时，未知工具以及当前模式下需要审批的工具 fail-closed；这不等同于禁止 `auto` 模式下已知工具。
- SQLite：`agentd/memory.py` 中的 `sessions`、`messages`、`memory`、`todos`、`knowledge` 及 `agentd/checkpoints.py` 的 `checkpoints` 是现有 schema 参照；时间字段为 REAL，消息 `meta` 为 JSON 文本。FTS5 trigram 不可用时回退 LIKE。
- 导出/导入：`GET /api/export` 上限 500 会话，每会话最近 1000 消息；`POST /api/import` 合并数据，跳过已存在会话、知识去重、导入任务默认停用；非法文件返回 400。详细字段与边界以 API.md 及 p27/p30 测试为准；不要直接读取 `jobs.db` 的 Python pickle。
- 共享 REST 向量覆盖 15 个代表场景：会话列表/创建/不存在，设置读取/更新，审批非法决策/不存在/待决/规则，任务列表/非法创建/不存在删除，导出、无效导入及空导入。Python 在隔离的真实应用中检查 HTTP 状态及指定顶层字段；另检查不存在任务删除为 `ok=false`、导入统计字段。字段检查是子集，非完整 JSON schema。
- Kotlin 当前仅校验向量内容稳定性；没有针对这些 REST 场景的 Kotlin 服务响应或 Python/Kotlin 差分。会话删除与更新、设置脱敏和凭据往返、审批实际决策、任务执行、非空导入、导出内容/分页及其余错误边界均未由本组共享向量覆盖。已有 SSE 测试分别覆盖路由编码和受控 LLM 驱动的 Python Agent 事件生产，不代表所有事件及异常路径完成差分。
- Slice 1 的类型与向量不改变 Java/Termux 默认运行时；服务、数据库、权限与迁移属于后续切片。
