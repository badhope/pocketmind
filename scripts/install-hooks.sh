#!/usr/bin/env bash
# 安装（或重新安装）本地 git 钩子。
#
# 为什么用脚本而不是 .pre-commit-config.yaml：本项目的门禁已经集中在
# tests/run_all.sh，再引入 pre-commit 框架等于维护两套配置。这里直接
# 把 scripts/pre-commit.sh 链到 .git/hooks/，零额外依赖。
set -euo pipefail
cd "$(dirname "$0")/.."

[ -d .git ] || { echo "不在 git 仓库根目录" >&2; exit 1; }

hook=.git/hooks/pre-commit
cat > "$hook" <<'EOF'
#!/usr/bin/env bash
exec bash "$(git rev-parse --show-toplevel)/scripts/pre-commit.sh" "$@"
EOF
chmod +x "$hook"
echo "已安装 pre-commit 钩子：$hook"
echo "  检查：密钥形状 / ruff / tsc / 版本一致"
echo "  跳过：git commit --no-verify"
