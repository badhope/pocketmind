# 贡献指南

感谢你对 **PocketMind（口袋智灵）** 的关注！欢迎以任何形式参与贡献：提 Issue、修 Bug、写文档、加功能都可以。

## 快速开始

1. Fork 本仓库到你的账号
2. 从 `main` 创建功能分支：`git checkout -b feat/your-feature`
3. 本地开发与运行方式见 [README](README.md)
4. 提交改动并发起 Pull Request，描述清楚改了什么、为什么改

## 提交规范

- 提交信息建议遵循 Conventional Commits：`feat: 新增xx`、`fix: 修复xx`、`docs: 文档`、`chore: 杂务`
- 每个提交聚焦单一改动，便于回溯与回滚
- 不要把密钥、个人数据提交进仓库

## 问题反馈

- 提 Issue 前请先搜索是否已有同类问题
- Bug 请附上复现步骤、预期/实际行为、环境信息
- 功能建议请说明使用场景

## 行为准则

参与贡献即表示同意遵守 [贡献者行为准则](CODE_OF_CONDUCT.md)。

## 发布流程（维护者）

完整规则见 [docs/VERSIONING.md](docs/VERSIONING.md)。每次对外更新按此清单执行，不跳步：

1. **定版本**：按语义化版本（SemVer）——修 Bug 升补丁位、新功能升次版本、破坏性改动升主版本
2. **同步三处版本号**：`agentd/main.py` 的 `VERSION`、`agentd/web/package.json` 的 `version`、
   `termux/apk-shell/gradle.properties` 的 `POCKET_VERSION_NAME`/`POCKET_VERSION_CODE`，
   以及根目录 `VERSION`。`python tests/check_version.py` 必须通过。
   改完版本后重新 `npm run build`，让入库的 `dist/` 跟上。
3. **同步文档**：更新 `CHANGELOG.md`（把 `[Unreleased]` 收进新版本段），
   README 的截图若界面有变也一并更新，依赖清单同步（requirements / package.json）
4. **本地验证**：`bash tests/run_all.sh` 必须全绿（全部后端套件 + compileall/ruff/部署自检 +
   前端 typecheck/单测/构建/dist 新鲜度）；需要 E2E 时加 `--with-e2e`
5. **合并**：以 PR 方式合入 `main`，门禁全绿后合并
6. **打标签**：`git tag -a vX.Y.Z -m "vX.Y.Z"` 并推送（`git push origin vX.Y.Z`）
7. **同步镜像**：主仓库在 GitCode（`gitcode.com/badhope/pocketmind`），同步到 Gitee
   （`gitee.com/badhope/pocketmind`）与三个 GitHub 仓库
   （`X33834/pocketmind`、`Morningstar202604/pocketmind`、`badhope/pocketmind`）。
   克隆默认只有 `origin`，先各加远程再推，保持五处内容一致：
   ```bash
   git remote add gitee https://gitee.com/badhope/pocketmind
   git remote add github https://github.com/X33834/pocketmind
   git remote add gh-morning https://github.com/Morningstar202604/pocketmind
   git remote add gh-badhope https://github.com/badhope/pocketmind
   bash scripts/sync-remotes.sh --tags   # 一次推 main + tags，逐个核对 commit
   ```
8. **发布**：基于该 tag 在各 GitHub 仓库创建 Release，Release Notes 写清变更要点；
   官网 `website/index.html` 为自包含单页，由 `badhope/pocketmind` 的 Pages（main 根目录）托管。
   发布脚本：`bash scripts/release.sh X.Y.Z`（不带 v；打 tag、推五处、建 Release）。
   先 `--dry-run` 看计划；GitHub 三个账号用各自 token 调 REST（gh CLI 只能用一个登录身份）。

## 分支规则

`main` 是唯一长期分支，也是所有远程的默认分支，必须始终是绿的。

- 功能开发走短命分支（`feat/…`、`fix/…`），合入 `main` 后立即删除。
- 没有 `develop`、`release/*` 或按平台分的分支。镜像只带 `main`，且与本地同一提交。
- 不要强推 `main`，唯一例外是历史分叉的镜像——`scripts/sync-remotes.sh` 对它们用
  `--force-with-lease`，对其余用快进推送。

变更记录以 [CHANGELOG.md](CHANGELOG.md)、[ROADMAP.md](ROADMAP.md)、Releases 与 git log 为准。
