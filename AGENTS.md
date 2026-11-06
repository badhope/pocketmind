# AGENTS.md — 给 AI 编码助手与人类贡献者的共同约定

这份文件是**可执行的事实**，不是欢迎致辞。改代码前先读完它。

## 这个项目是什么

PocketMind 是一个**跑在 Android 手机里的常驻 AI Agent**。两个运行时：

| 运行时 | 入口 | 说明 |
|---|---|---|
| 原生（推荐） | `termux/apk-shell/` (Kotlin) | 单 APK，无需 Termux。37 个原生工具、AlarmManager 调度、real `/system/bin/sh` |
| Termux（完整） | `agentd/` (Python) | 41 个工具、MCP、Telegram、技能目录、子代理 |

前端 `agentd/web/`（React 18 + Vite）被两个运行时共用，编译产物打进 APK 的 assets。

## 不可协商的规则

1. **审批必须在服务端路径上强制。** 任何让模型能自批准的改动一律拒绝，即使它能"提升体验"。
2. **不伪成功。** 未实现的能力返回 `capability_unavailable` 或 503，不许返回空集冒充。降级必须写进 `/api/diagnostics`。
3. **数据不出设备。** 不加任何遥测/分析端点。用户主动触发的导出除外。
4. **凭据不进日志、不进上下文、不进导出。** `config.json` / `rules.json` / `checkpoints/` / `audit/` 已在文件工具的拒绝名单里。
5. **改动要能被测试证伪。** 说"修好了"之前，先证明测试在改坏时会红。

## 构建与验证

```bash
# 后端门禁（ruff + mypy + 50 个 smoke 套件 + 锁一致性 + 覆盖率）
PYTHON="$PWD/.venv/Scripts/python.exe" bash tests/run_all.sh --backend

# 前端门禁（tsc + vitest + build + dist 新鲜度）
bash tests/run_all.sh --frontend

# Android：单测 + lint + APK
cd termux/apk-shell
GRADLE_USER_HOME="$PWD/.gradle-home" gradle testDebugUnitTest lintDebug assembleDebug
```

**环境坑（Windows）**：系统 Gradle 会加载带 BOM 的 `init.d` 脚本导致启动失败，必须设 `GRADLE_USER_HOME` 到项目内目录。

## 测试不是走过场

- 每个新功能至少一个**行为断言**（不是"函数被调用过"）。
- 修 bug 先写红测试，再修。
- 关键路径用**变异反证**：注释掉实现，测试必须红（见 `CHANGELOG` 里 "mutation-verified" 的条目）。

## 目录速查

```
agentd/                  Python 后端（FastAPI）
  tools/                 工具注册表（41 个）
  approval.py            审批中心（服务端强制）
  scheduler.py           APScheduler 定时任务
agentd/web/              React 前端（两运行时共用）
termux/apk-shell/        Android 原生引擎（Kotlin）
  .../runtime/           HTTP 引擎、路由、调度、启动恢复
  .../tools/             37 个原生工具
  .../agent/             Agent 循环与审批
config/recipes.json      内置配方（与 Recipes.kt 同步）
tests/run_all.sh         唯一门禁入口
```

## 提交前

```bash
bash scripts/install-hooks.sh   # 装 pre-commit：密钥扫描 / ruff / tsc / 版本一致
```

版本号三处必须同步：`agentd/main.py`、`agentd/web/package.json`、`termux/apk-shell/gradle.properties`。`tests/check_version.py` 会拦。
