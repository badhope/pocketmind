# 本地 pre-commit 钩子：把「提交前必做」的检查自动跑掉。
#
# 为什么需要：CI 会拦，但等到 CI 红已经浪费了一轮往返。这里只放**快**检查
# （几秒内），慢的（全量测试、APK 构建）仍交给 CI。
#
# 安装：bash scripts/install-hooks.sh
# 卸载：rm .git/hooks/pre-commit

set -uo pipefail

fail() {
  echo ""
  echo "提交被拒绝：$1"
  echo "   修完重新 git add 再提交；确认无误要跳过可用 git commit --no-verify"
  exit 1
}

# 1) 暂存区里不能有密钥形状的字符串
if command -v git >/dev/null 2>&1; then
  staged=$(git diff --cached --name-only --diff-filter=ACM)
  if [ -n "$staged" ]; then
    # 只扫文本文件，跳过二进制与生成物
    hits=$(echo "$staged" | while read -r f; do
      [ -f "$f" ] || continue
      case "$f" in
        *.png|*.jpg|*.gif|*.woff*|*.ttf|*.apk|*.jar|*.mp4|*.zip|*.pdf|*.wav) continue ;;
        agentd/web/dist/*|*/node_modules/*|*.lock|package-lock.json) continue ;;
      esac
      # ghp_ / gho_ / github_pat_ / sk- 长串 / 私钥头
      if grep -nE '(ghp_[A-Za-z0-9]{30,}|gho_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9]{32,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)' "$f" 2>/dev/null; then
        echo "   ^ 命中：$f"
        echo "$f" >> /tmp/.pm-secret-hits
      fi
    done)
    if [ -n "$hits" ] || [ -s /tmp/.pm-secret-hits ]; then
      rm -f /tmp/.pm-secret-hits
      fail "暂存内容里疑似有密钥/私钥"
    fi
    rm -f /tmp/.pm-secret-hits
  fi
fi

# 2) Python 侧：ruff（毫秒级）
if command -v python >/dev/null 2>&1 && python -m ruff --version >/dev/null 2>&1; then
  py_files=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.py$' || true)
  if [ -n "$py_files" ]; then
    echo "$py_files" | xargs python -m ruff check || fail "ruff 不通过"
  fi
fi

# 3) 前端：只做类型检查（不跑构建，快）
if [ -d agentd/web/node_modules ] && command -v npx >/dev/null 2>&1; then
  ts_files=$(git diff --cached --name-only --diff-filter=ACM | grep -E '^agentd/web/src/.*\.(ts|tsx)$' || true)
  if [ -n "$ts_files" ]; then
    (cd agentd/web && npx tsc -b --pretty false) || fail "TypeScript 类型检查不通过"
  fi
fi

# 4) 版本一致性：改了 VERSION 就要三处同步
if git diff --cached --name-only | grep -qE '^(VERSION|agentd/main\.py|agentd/web/package\.json|termux/apk-shell/gradle\.properties)$'; then
  if command -v python >/dev/null 2>&1; then
    python tests/check_version.py >/dev/null 2>&1 || fail "版本号三处不一致（python tests/check_version.py 查看）"
  fi
fi

exit 0
