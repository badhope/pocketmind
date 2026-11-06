# 对标报告：PocketMind 与主流手机/桌面 Agent

> 更新：2026-10-24 · 版本 1.2.0
> 方法：读各自官方 README / 官网（外部事实均附链接，抓取日期 2026-10-24）+
> 对 PocketMind 侧只写**能在本仓库代码/文档里验证的事实**（引用 `文件:行`）。
> 本文件替代 `docs/history/POCKETMIND-主流方案对标-2026-10-12.md`（历史快照）。

## 一、结论先行

PocketMind 的定位不是「又一个手机 GUI Agent」，而是：

**一台手机上的常驻 Agent 服务：系统能力走 API（termux-api），数据留在本机，审批在服务端强制执行。**

三条边界决定了它不该照着谁抄：

1. **不碰屏幕。** Mobilerun（原 DroidRun）和 Mobile-Agent 用无障碍树/截图去「点屏幕」；
   PocketMind 用 `termux-api` 直接调系统能力（短信、电话、定位、传感器、通知、剪贴板、WiFi）。
   前者能操作任何 App，但没有结构化结果；后者拿到的是 JSON，可被程序继续处理。
   两者互补，不是替代关系。
2. **不在电脑上跑。** Mobilerun / Mobile-Agent / Cline / Open Interpreter 的宿主都是 PC（或云手机），
   手机只是被控端。PocketMind 的宿主就是手机本身（Termux + PWA/APK 壳），
   断网、锁屏、随身场景才是它的战场。
3. **不是聊天玩具。** 审批、撤销、定时任务、MCP 双向、备份恢复是它的主干功能，
   与「对话」同等重要——这些在手机端 Agent 里几乎没有同类实现。

因此本报告的重点不是「谁功能多」，而是**哪些能力该借、哪些不该借**。

## 二、对标对象与事实

| 项目 | 定位 | 运行位置 | 手机控制方式 | LLM | 许可 | 规模（GitHub stars，2026-10-24） |
|---|---|---|---|---|---|---|
| **PocketMind** | 手机常驻 Agent 服务（本地优先） | **手机内**（Termux） | termux-api 系统调用（41 个工具） | 任意 OpenAI 兼容端点 | Apache-2.0 | 新项目 |
| [Mobilerun](https://github.com/droidrun/mobilerun)（原 DroidRun） | 手机 GUI 自动化框架 | PC / Docker / 云手机 | ADB 或 Portal App（无障碍 + 截图） | OpenAI / Anthropic / Gemini / Ollama / DeepSeek 等 | MIT | 9.7k |
| [Mobile-Agent](https://github.com/X-PLUG/MobileAgent)（通义实验室） | GUI Agent 模型 + 框架 | PC / 云手机 | 视觉 + ADB | GUI-Owl 系列（自研 VLM） | MIT | 9.3k |
| [Cline](https://github.com/cline/cline) | 编码 Agent（IDE/CLI/桌面） | PC | 无（不控手机） | 多厂商 | Apache-2.0 | 70.8k |
| [Open Interpreter](https://github.com/openinterpreter/openinterpreter) | 终端编码 Agent | PC | 无 | 低成本模型优先（Kimi/GLM 等） | Apache-2.0 | 68.7k |
| [Letta](https://github.com/letta-ai/letta)（原 MemGPT） | 有状态 Agent 平台 | 服务端 / 本地 | 无 | 多厂商 | Apache-2.0 | 25.2k |

外部事实来源：
- Mobilerun README（平台支持表、ADB/Portal、LLM 列表、Python 3.11–3.13 限制）：
  https://github.com/droidrun/mobilerun
- Mobile-Agent README（GUI-Owl-1.5 系列模型、AndroidWorld/OSWorld 评测、云手机）：
  https://github.com/X-PLUG/MobileAgent
- Cline README（IDE 插件 / CLI / 桌面三形态）：https://github.com/cline/cline
- Open Interpreter README（Codex fork、harness 切换）：https://github.com/openinterpreter/openinterpreter
- Letta README（letta-code、App Server、多通道）：https://github.com/letta-ai/letta
- stars 数据经 GitHub REST API `/repos/{owner}/{repo}` 读取（2026-10-24）。

## 三、能力矩阵（只写可验证项）

图例：● 原生支持　◐ 部分/可选　○ 无

| 能力 | PocketMind | Mobilerun | Mobile-Agent | Cline | Open Interpreter | Letta |
|---|---|---|---|---|---|---|
| 宿主在手机内运行 | ● Termux | ○（手机是被控端） | ○ | ○ | ○ | ○ |
| 系统能力（短信/电话/定位/传感器） | ● 25 个 termux 工具 | ○（只能点 UI） | ○（只能点 UI） | ○ | ○ | ○ |
| 操作任意 App 的 UI | ○ | ● 无障碍+截图 | ● 视觉 | ○ | ○ | ○ |
| 服务端强制审批（模型无法绕过） | ● `agentd/permissions.py` + `approval.py` | ◐ 人工确认 | ○ | ● | ◐ | ◐ |
| 文件操作可撤销 | ● checkpoint + 备份 | ○ | ○ | ◐（git） | ○ | ○ |
| Plan/Act 双模式 | ● | ◐ reasoning 模式 | ◐ 规划模块 | ● | ○ | ○ |
| 定时任务 / 条件触发 | ● APScheduler + battery 条件 | ◐ 云端定时 | ○ | ◐ routines（桌面） | ○ | ○ |
| MCP 双端（Server + Client） | ● | ◐ 云端 MCP | ○ | ● | ○ | ◐ |
| 长期记忆 | ◐ FTS5 + 摘要 + 可选 Mem0 | ○ | ◐ 任务记忆 | ◐ | ○ | ● 分层记忆 |
| 多 Agent 编排 | ◐ 子代理（工具收窄、禁止嵌套） | ○ | ● 多 Agent | ○ | ○ | ◐ |
| Token/成本可见 | ● 本轮 token + /api/stats | ◐ 云端面板 | ○ | ● | ● | ● |
| 数据全本地 | ● SQLite 0600/0700 | ◐ 本地执行，云端可选 | ◐ | ● | ● | ◐ |
| 手机端零 Node 运行时 | ● 构建产物入库，纯 pip | ○（需 PC Python） | ○ | ○ | ○ | ○ |

PocketMind 侧证据：
- 工具清单：`curl /api/tools` 实测 41 项，其中 `phone` 组 25 项（`agentd/tools/phone/*`）。
- 审批：`agentd/permissions.py:1`、`agentd/approval.py`，以及 `agentd/agent.py:_decide_tool` 的
  fail-closed 分支（`ask_approval is None` 时写/危险操作直接拒绝）。
- 撤销：`agentd/checkpoints.py` + `/api/undo`（`agentd/main.py:758`）。
- 定时：`agentd/scheduler.py`（APScheduler + SQLAlchemyJobStore，条件表达式 `battery < N`）。
- MCP：`agentd/mcp_server.py`（暴露 41 工具）+ `agentd/mcp_client.py`（接入外部 Server）。
- 本地数据：`agentd/memory.py:_secure_files`（目录 700 / 库文件 600）。

## 四、该借什么（已落地 / 待落地）

### 已落地（本轮 1.2.0）

1. **MCP 互操作的正确姿势**（借自 Cline/Claude 生态的共识）：工具描述带风险前缀
   `[safe]/[write]/[danger]`，外部工具的 `readOnlyHint` **不降级**为免审批——
   注解由对端声明，信任边界不能交给被接入方（`agentd/mcp.py:mcp_tool_risk`）。
2. **Plan/Act 交互**（借自 Cline）：计划先落库再等批准，批准后重发原话，
   而不是让模型「假装执行」（`agentd/agent.py:_finish_plan`）。
3. **Token 用量可见**（借自 Cline / Open Interpreter 的成本面板）：
   `stream_options.include_usage` + `done.usage` + 消息级展示 + `/api/stats` 聚合。

### 待落地（有明确收益，但不在本轮范围）

| 项 | 来源 | 为什么值得做 | 代价 |
|---|---|---|---|
| 无障碍/截图工具（可选 MCP） | Mobilerun / Mobile-Agent | 覆盖 termux-api 触达不到的 App 内操作 | 需要 Android 无障碍权限 + 图像模型；与「不碰屏幕」定位冲突，应做成**可选外部 MCP**，不进核心 |
| 分层记忆分页（类似 MemGPT） | Letta | 当前是「摘要 + 归档」，缺少可寻址的记忆块 | 需要重写上下文组装；现有 `archived_upto` 水位是基础 |
| 多设备编排 | Mobilerun Cloud | 一台手机跑一个 Agent，多机协同是空白 | 需要中心节点与鉴权模型，属于另一个产品 |
| 语音全双工 | Mobile-Agent v3.5 | 现有 TTS/STT 是单轮的 | 需要流式音频通道；Termux 上可行性未验证 |

**明确不借**：云端手机托管（与本地优先冲突）、自研 GUI VLM（成本与团队规模不匹配）、
把主循环换成 LangGraph/ADK（其原生依赖在 Termux 无 wheel，见
`docs/history/POCKETMIND-主流方案对标-2026-10-12.md` 的调研结论）。

## 五、PocketMind 能站住的三条差异

1. **「真·手机内运行」**：不是把手机当外设，而是手机自己就是服务器。
   断网可用（本地模型）、锁屏可跑（wake-lock + 前台服务）、换机可迁移（导出/导入）。
2. **「系统 API 而非屏幕像素」**：短信、定位、传感器拿到的是结构化数据，
   可被定时任务、条件触发、外部 MCP 客户端复用；GUI Agent 的点按结果无法这样组合。
3. **「审批是一等公民」**：三级授权 + 全局拒绝名单 + 超时自动拒绝 + 外部工具不降级，
   这套语义在开源手机 Agent 里没有等价实现。

## 六、已知短板（诚实清单）

- **覆盖率**：行覆盖 **46.4%**（实测，2026-11-07），门禁红线 40% 且只升不降
  （`tests/check_coverage.py`）。此前本文件写的「59% / 红线 90%」是未落地的目标值，
  没有可执行的门禁支撑，属于自欺——已改为实测数字。主要缺口集中在
  `agentd/tools/phone/*`（需要真机 termux-api）与 `agentd/main.py` 的路由错误分支。
- 真机未验证：所有 E2E 在 Chromium + Windows 完成；aarch64 真机的 termux-api 行为只有模拟器证据
  （`docs/android-emulator-testing.md`）。
- 无 UI 自动化能力：这是定位选择，不是缺陷，但用户需要知道边界（`docs/capability-matrix.md`）。
- 单用户单机：没有多租户、没有权限分级账号体系。
