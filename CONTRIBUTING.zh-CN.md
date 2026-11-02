# 贡献指南

感谢你愿意为 **PocketMind（口袋智灵）** 出力。这个项目是单用户、运行在设备上的
Agent，判断一次改动值不值得的标准是**「拿着手机的那个人会不会因此更好用」**，
而不是「有没有多加一个功能」。

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

## 动手之前

- **比错别字更大的改动，请先开 Issue。** 一段说清你遇到什么问题的话，比一个解决了
  没人遇到的问题的 500 行 PR 有价值得多。
- **先搜已有 Issue 和 [ROADMAP](ROADMAP.md)**。已经标为「不做」的条目，需要很强的
  理由才会重新考虑。
- **绝不提交密钥。** API Key 存在 `$AGENT_HOME/config.json`（已 gitignore）。
  `keystore.properties` 同理。

## 开发环境

```bash
# 后端（Python 3.11+；CI 用 3.12）
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r agentd/requirements.txt -r agentd/requirements-dev.txt

# 造 termux-api 桩，让手机工具在桌面上也能解析到
bash tests/setup_faketermux.sh
export PATH=/tmp/faketermux:$PATH

# 离线运行——不需要 API Key，走完整链路
AGENT_HOME=/tmp/pa python -m agentd.main --mock --port 8787

# 前端
cd agentd/web && npm ci
npm run dev          # http://localhost:5173，/api 代理到 :8787
```

改了 `agentd/web/src` 下的任何东西，都必须重建入库的 bundle：

```bash
cd agentd/web && npm run build
```

手机端从不执行构建，所以 `dist/` 是源码的一部分；产物过期门禁会直接失败。

## 门禁

一条命令就是权威判据。CI 只负责装依赖并调用它。

```bash
bash tests/run_all.sh              # 后端 + 前端
bash tests/run_all.sh --backend    # 只跑后端
bash tests/run_all.sh --with-e2e   # 追加 Playwright（先 npx playwright install chromium）
```

退出码 `0` 表示全过。末尾汇总会逐条列出失败项。不要带着红门禁提 PR 再附一句
「失败的套件与本次无关」——要么修掉，要么在 Issue 里说清楚为什么该跳过。

### 门禁覆盖什么

| 层 | 检查项 |
|---|---|
| 后端 | 全部 `smoke/` 套件、`compileall`、`ruff`、部署链路自检、版本一致性 |
| 前端 | `tsc -b --noEmit`、Vitest 单测、生产构建、入库 `dist/` 新鲜度 |
| Android | Robolectric 单测、`lintDebug`（0 error）、debug + release APK 构建与校验 |
| 可选 | Playwright E2E（`--with-e2e`）、依赖漏洞扫描（`--with-dep-audit`）、变异测试（`--with-mutation`） |

## 代码约定

- **Python**：用 `ruff`，门禁会跑。公开函数写类型标注。新增运行时依赖必须在 PR 里
  说明理由——每一个依赖都是手机要装的东西。
- **TypeScript**：开了 `noUnusedLocals` / `noUnusedParameters`。组件保持函数式；
  状态放 Zustand store，不要塞模块级全局变量。
- **注释解释「为什么」，不是「做了什么」。** 代码里有大量「这里以前是 X，因为 Y
  坏了」式的注释，请保持这个风格：下一个人需要的是这段历史，不是代码复述。
- **错误处理要分层。** 子功能失败必须降级，不能把整个请求带崩。加可选集成前先看
  `agentd/main.py` 是怎么包它们的。
- **中文界面文案没问题**（产品以中文交付）；但代码标识符、提交信息、文档一律英文。

## 提交信息

Conventional Commits，祈使句：

```
fix(approval): deny before showing the card, not after
feat(tools): add set_alarm
docs(readme): correct the install time
```

一个提交应当能被单独评审。改了行为就说明**原来坏在哪、怎么验证的**——
「测过了」不算验证，贴出门禁汇总或具体套件的输出才算。

## Pull Request

- 一个 PR 只做一件事。
- 描述问题，不要只描述方案。
- 附上门禁输出（末尾那行汇总就够）。
- 改了 API 就更新 `docs/API.md`。
- 改了用户可见行为就在 `CHANGELOG.md` 的 `[Unreleased]` 下补一条。
- 改了会出现在截图里的界面，就重新出图。

## 报 Bug

请附上：

1. 期望行为与实际行为。
2. 版本号——`GET /api/health` 会返回；APK 版本用
   `adb shell dumpsys package com.pocket.agent | grep versionName`。
3. 设备、Android 版本、是否装了 `termux-api`。
4. 在 `--mock` 模式下能否复现。这一条能区分「Agent 逻辑错了」还是「设备集成错了」，
   能省掉大量来回。

## 安全问题

**不要**开公开 Issue。见 [SECURITY.md](SECURITY.md)。

## 发布流程（维护者）

完整规则见 [docs/VERSIONING.md](docs/VERSIONING.md)。简述：

1. 在 `agentd/main.py`、`agentd/web/package.json`、
   `termux/apk-shell/gradle.properties` 与根目录 `VERSION` 里升版本号；重建 `dist/`。
2. 把 `CHANGELOG.md` 的 `[Unreleased]` 收进带日期的版本段。
3. `bash tests/run_all.sh` 必须全绿。
4. `git tag -a vX.Y.Z -m "vX.Y.Z"` 并推送。
5. `bash scripts/sync-remotes.sh --tags` 把所有远程推到同一 commit 并逐个核对。
6. `bash scripts/release.sh X.Y.Z`（先 `--dry-run`）发布 Release 说明。

## 分支规则

`main` 是唯一长期分支，也是所有远程的默认分支。功能开发走短命的 `feat/…` /
`fix/…` 分支，合完即删。没有 `develop`，没有按平台分的分支，不强推 `main`
（历史分叉的镜像由同步脚本用 `--force-with-lease` 处理）。

## 行为准则

参与贡献即表示同意遵守 [贡献者行为准则](CODE_OF_CONDUCT.md)。
