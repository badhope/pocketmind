#!/usr/bin/env bash
# 口袋 Agent · 启动 agentd（本地智能体服务）
#
# 用法：
#   bash termux/start.sh                 # 本机访问 http://127.0.0.1:8787
#   bash termux/start.sh 8788            # 自定义端口
#   bash termux/start.sh --lan           # 局域网访问（端口默认 8787）
#   bash termux/start.sh 8787 --lan      # 局域网 + 指定端口（顺序任意）
set -euo pipefail
cd "$(dirname "$0")/.."

# 参数解析：端口与 --lan 顺序任意。
# 修复（2026-10-29）：旧写法 `PORT="${1:-8787}"` 会把 `--lan` 当成端口传给 uvicorn，
# 而 README 的「日常使用」正是 `bash termux/start.sh --lan` —— 必失败。
PORT="8787"
EXTRA=""
for arg in "$@"; do
  case "$arg" in
    --lan) EXTRA="--lan" ;;
    -h|--help)
      sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    [0-9]*) PORT="$arg" ;;
    *)
      echo "未知参数：$arg（用法：bash termux/start.sh [端口] [--lan]）" >&2
      exit 2
      ;;
  esac
done

if [ -n "$EXTRA" ]; then
  echo "⚠ 局域网模式：请确认已在页面设置里配置访问令牌，否则任何设备都能调用本服务。"
fi

echo "启动口袋 Agent（端口 $PORT）..."
# 访问令牌：install.sh 生成的 $DATA_DIR/token 优先注入。
# 只在本进程环境里生效，不写入 config.json —— 令牌文件是唯一事实源，
# 用户在设置面板改了令牌也不会被这里的旧值覆盖（main.py 只在该值为空时初始化）。
TOKEN_FILE="${AGENT_HOME:-$HOME/.agent/termux-agent}/token"
if [ -z "${AGENT_TOKEN:-}" ] && [ -s "$TOKEN_FILE" ]; then
  AGENT_TOKEN="$(cat "$TOKEN_FILE")"
  export AGENT_TOKEN
  echo "已加载访问令牌（$TOKEN_FILE）"
  echo "  浏览器首次打开需要它：cat $TOKEN_FILE"
  echo "  若忘记：python -m agentd.main --clear-token 后重启（会失去鉴权，仅限本机调试）"
fi
# 唤醒锁：阻止 CPU 在锁屏后休眠（否则定时任务/工具回调会被系统冻结）。
# 退出时务必释放，避免空耗电池；termux-wake-lock 不存在（非 Termux）时静默跳过。
WAKE=0
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock && WAKE=1
fi
cleanup() { [ "$WAKE" = 1 ] && command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock; }
trap cleanup EXIT INT TERM

python -m agentd.main --port "$PORT" $EXTRA
