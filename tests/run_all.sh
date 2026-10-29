#!/usr/bin/env bash
# 全量回归门禁：一条命令跑完原本由 GitHub Actions 承担的检查。
#
#   bash tests/run_all.sh              # 后端全量 + 前端全量
#   bash tests/run_all.sh --backend    # 只跑后端
#   bash tests/run_all.sh --frontend   # 只跑前端
#   bash tests/run_all.sh --fresh      # 强制 npm ci 重装前端依赖
#   bash tests/run_all.sh --with-e2e   # 追加 Playwright E2E（需先装浏览器）
#   bash tests/run_all.sh --allow-dirty-dist  # 提交前自检：dist 允许有未提交改动
#   bash tests/run_all.sh --with-mutation     # 追加变异测试（慢，验证断言有效性）
#   bash tests/run_all.sh --with-dep-audit    # 追加依赖漏洞扫描（需外网）
#
# 退出码：0 = 请求的检查全部通过；1 = 有失败项；2 = 环境不满足（缺依赖/参数错误）。
# 数据隔离：门禁只用自己的临时 AGENT_HOME，绝不读取、更不会删除外层 shell 里的同名变量。
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

RUN_BACKEND=1
RUN_FRONTEND=1
FORCE_INSTALL=0
WITH_E2E=0
ALLOW_DIRTY_DIST=0
WITH_MUTATION=0
WITH_DEP_AUDIT=0
MODE_SEEN=""
for arg in "$@"; do
  case "$arg" in
    --backend|--frontend)
      if [ -n "$MODE_SEEN" ] && [ "$MODE_SEEN" != "$arg" ]; then
        echo "--backend 与 --frontend 互斥：不指定即两者都跑" >&2; exit 2
      fi
      MODE_SEEN="$arg" ;;
    --fresh) FORCE_INSTALL=1 ;;
    --with-e2e) WITH_E2E=1 ;;
    # 提交前自检：dist 允许有未提交改动（改完源码、build 完、还没 commit 是正常状态）。
    --allow-dirty-dist) ALLOW_DIRTY_DIST=1 ;;
    # 变异测试较慢（每个变异体跑一遍测试套件），默认不进门禁
    --with-mutation) WITH_MUTATION=1 ;;
    # 依赖漏洞扫描需要外网（OSV API），默认不做
    --with-dep-audit) WITH_DEP_AUDIT=1 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（-h 查看用法）" >&2; exit 2 ;;
  esac
done
[ "$MODE_SEEN" = "--backend" ] && RUN_FRONTEND=0
[ "$MODE_SEEN" = "--frontend" ] && RUN_BACKEND=0

GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'

PY=""
if [ "$RUN_BACKEND" = 1 ] || [ "$WITH_E2E" = 1 ]; then
  for cand in python3 python py; do
    if command -v "$cand" >/dev/null 2>&1 && \
       "$cand" -c 'import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)' 2>/dev/null; then
      PY="$cand"; break
    fi
  done
  if [ -z "$PY" ]; then
    echo "找不到 Python 3.11+，无法运行后端检查" >&2; exit 2
  fi
fi

PASSED=()
FAILED=()
SKIPPED=()

pass() { PASSED+=("$1"); printf '  %sPASS%s %s\n' "$GREEN" "$OFF" "$1"; }
fail() { FAILED+=("$1"); printf '  %sFAIL%s %s\n' "$RED" "$OFF" "$1"; }
skip() { SKIPPED+=("$1"); printf '  %sSKIP%s %s\n' "$YELLOW" "$OFF" "$1"; }

# 每步独立执行并记账：失败不中断，最后统一汇总
step() {
  local name="$1"; shift
  printf '\n== %s ==\n' "$name"
  if "$@"; then pass "$name"; return 0; else fail "$name"; return 1; fi
}

run_py() {
  local script="$1"
  [ -f "$script" ] || { echo "缺少脚本：$script" >&2; return 1; }
  "$PY" "$script"
}

free_port() {
  "$PY" - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

# 等服务就绪；进程已死就立刻返回失败，不盲等
wait_health() {
  local base="$1" pid="$2" tries="${3:-30}"
  for _ in $(seq 1 "$tries"); do
    if curl -sf --max-time 2 "$base/api/health" >/dev/null 2>&1; then return 0; fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}

# 门禁专用隔离目录：不读外层 AGENT_HOME，避免误删用户真实数据
GATE_TMP="$(mktemp -d 2>/dev/null || mktemp -d -t gate)"
GATE_HOME="$GATE_TMP/home"
export AGENT_HOME="$GATE_HOME"
unset AGENT_TOKEN 2>/dev/null
unset AGENTD_BASE 2>/dev/null

SERVER_PID=""
SERVER_LOG="$GATE_TMP/server.log"
cleanup() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
    SERVER_PID=""
  fi
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

# ---------------------------------------------------------------- 后端
if [ "$RUN_BACKEND" = 1 ]; then
  echo ""
  echo "########## 后端 ##########"

  printf '== 依赖预检 ==\n'
  if ! "$PY" -c 'import fastapi, uvicorn, pydantic, apscheduler' 2>/dev/null; then
    echo "缺少后端依赖：$PY -m pip install -r agentd/requirements.txt" >&2; exit 2
  fi
  if ! "$PY" -m ruff --version >/dev/null 2>&1; then
    echo "缺少 ruff：$PY -m pip install ruff" >&2; exit 2
  fi
  echo "  Python $("$PY" -c 'import platform;print(platform.python_version())') / ruff ok"

  step "compileall：全部模块可编译" "$PY" -m compileall -q agentd/ tests/ smoke/
  step "ruff：静态检查" "$PY" -m ruff check agentd/ tests/ smoke/
  step "p9 无障碍：主题文字对比度 ≥ 4.5:1" run_py smoke/p9_a11y_contrast_test.py
  step "p10 子代理（工具收窄 / 禁止嵌套 / 不污染主会话）" run_py smoke/p10_subagent_test.py
  step "p11 Termux 安装脚本幂等性" run_py smoke/p11_termux_install_test.py
  step "p12 能力矩阵（能做什么/不能做什么/怎么办）" run_py smoke/p12_capability_test.py
  step "p13 SSRF 防护（内网/元数据/任意端口）" run_py smoke/p13_ssrf_test.py
  step "p14 路由层（45 端点进程内：错误分支 + 契约）" run_py smoke/p14_router_test.py
  step "p15 文件工具（读写删 + 越界 + 备份）" run_py smoke/p15_file_tools_test.py
  step "p16 知识库（增删查 + 注入转义 + 边界）" run_py smoke/p16_knowledge_test.py
  step "p17 联网工具（解析器 + 引擎回退，全离线）" run_py smoke/p17_net_tools_test.py
  step "p18 Agent 主循环（Plan/审批/超时/失败/轮数上限）" run_py smoke/p18_agent_loop_test.py
  step "p20 审批规则矩阵（risk × mode 九种组合）" run_py smoke/p20_permissions_test.py
  step "p21 任务清单（上下文/截断/emit 失败降级）" run_py smoke/p21_todo_test.py
  step "p23 运行指标（计数器/Prometheus 格式/不泄露 id）" run_py smoke/p23_metrics_test.py
  step "p24 聊天编排（run_agent / 队列续跑 / 取消 / 未配置 / 异常）" run_py smoke/p24_run_agent_test.py
  step "p25 性能回归（N+1 查询数 / 工具结果硬顶）" run_py smoke/p25_perf_regression_test.py
  step "p26 工具层缺口（写操作退出码 / 指标基数 / 外发分级）" run_py smoke/p26_tool_gaps_test.py
  step "p27 导出完整性（超过一页的会话不丢失）" run_py smoke/p27_export_pagination_test.py
  step "p28 MCP 双端回环（真实 stdio 握手 + schema 转换）" run_py smoke/p28_mcp_roundtrip_test.py
  step "p29 运行统计与 Token 用量（/api/stats 真数据）" run_py smoke/p29_stats_usage_test.py
  step "p30 置顶 / 立即执行 / 导入恢复" run_py smoke/p30_features_test.py
  step "p31 真实 OpenAI 兼容链路（流式 / 工具分片 / usage / 降级）" run_py smoke/p31_openai_compat_test.py
  step "p32 Android 交付链（回环鉴权 / 时区 / 端口自检 / start.sh 参数）" run_py smoke/p32_android_delivery_test.py
  step "版本一致性（main.py / package.json / gradle.properties）" run_py tests/check_version.py
  if [ "$WITH_DEP_AUDIT" = 1 ]; then
    step "p22 依赖漏洞扫描（OSV.dev，需外网）" run_py smoke/p22_dep_audit_test.py
  fi
  if [ "$WITH_MUTATION" = 1 ]; then
    step "p19 变异测试（断言有效性，较慢）" run_py smoke/p19_mutation_test.py
  fi

  # 不依赖服务的套件先跑，失败也不会挡住后面起服务
  step "p0 回归（重启恢复 / \$HOME 展开 / FTS 排除）" run_py smoke/p0_regression_test.py
  step "p4 agent 深度（三级审批 / 记忆 / 技能 / todo）" run_py smoke/p4_agent_depth_test.py
  step "审批中心单测" run_py tests/eval_approval.py
  step "p3 健壮性单测（配置兜底 / 校验 / 打码）" run_py smoke/p3_robustness_unit.py
  step "安全专项（穿越 / 注入 / SSRF / 打码）" run_py tests/security_test.py
  step "p3 性能与泄漏" run_py smoke/p3_perf_leak_test.py
  step "MCP server 清单" run_py smoke/test_mcp_server.py
  step "Telegram 网关" run_py smoke/test_telegram_gateway.py
  step "p7 横向审计回归" run_py smoke/p7_cross_audit_test.py
  step "p6 首次使用（未配置模型，自起服务）" run_py smoke/p6_unconfigured_test.py

  # 依赖清单自检：requirements.txt 的版本约束必须真实可满足。
  # 真实事故：instrumentation 系列只发 0.x beta，写 ">=0.46" 严格高于所有 0.46b*，
  # 全新安装（干净的 Linux/Docker/Termux）直接失败，而装过旧版的环境完全无感。
  step "依赖清单自检（requirements.txt 版本约束可满足）" run_py tests/check_requirements.py

  # 部署链路自检：无令牌 --lan 必须拒启 / AGENT_TOKEN 引导 / --clear-token 恢复
  deploy_self_check() {
    local port home pid code ok=1 refuse_log boot_log
    port="$(free_port)"
    home="$GATE_TMP/lan"; rm -rf "$home"
    refuse_log="$GATE_TMP/lan-refuse.log"; boot_log="$GATE_TMP/lan-boot.log"

    if env -u AGENT_TOKEN AGENT_HOME="$home" \
         "$PY" -m agentd.main --lan --port "$port" --mock >"$refuse_log" 2>&1; then
      echo "✗ 无令牌却以 --lan 启动了（等于把服务裸奔在局域网）"
      cat "$refuse_log"; ok=0
    elif ! grep -q "必须配置访问令牌" "$refuse_log"; then
      echo "✗ 缺少令牌时未给出可执行的报错指引"; ok=0
    fi

    rm -rf "$home"
    AGENT_HOME="$home" AGENT_TOKEN=gate-smoke-token \
      "$PY" -m agentd.main --lan --port "$port" --mock >"$boot_log" 2>&1 &
    pid=$!
    if wait_health "http://127.0.0.1:$port" "$pid" 30; then
      code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/jobs" 2>/dev/null)"
      if [ "$code" != "401" ]; then
        echo "✗ 未带令牌应 401，实际 $code"; ok=0
      elif ! curl -sf --max-time 5 -H "Authorization: Bearer gate-smoke-token" \
             "http://127.0.0.1:$port/api/jobs" >/dev/null; then
        echo "✗ 带正确令牌仍不可访问"; ok=0
      fi
    else
      echo "✗ AGENT_TOKEN 引导后服务未起来"; cat "$boot_log"; ok=0
    fi
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

    "$PY" -m agentd.main --home "$home" --clear-token >/dev/null 2>&1 \
      || { echo "✗ --clear-token 执行失败"; ok=0; }
    "$PY" - "$home" <<'PY' || ok=0
import json, sys
cfg = json.load(open(sys.argv[1] + "/config.json", encoding="utf-8"))
assert cfg["server"].get("token", "x") == "", cfg["server"]
print("clear-token + --home 定位生效")
PY
    rm -rf "$home"
    [ "$ok" = 1 ]
  }
  step "部署链路自检（--lan 拒启 / AGENT_TOKEN / --clear-token）" deploy_self_check

  printf '\n== 起 mock 服务（faketermux 桩） ==\n'
  PORTS_TODO=0
  if bash tests/setup_faketermux.sh >/dev/null 2>&1; then
    export PATH="${FAKETERMUX_DIR:-/tmp/faketermux}:$PATH"
    PORT="$(free_port)"
    AGENTD_BASE="http://127.0.0.1:$PORT"
    export AGENTD_BASE
    "$PY" -m agentd.main --mock --port "$PORT" >"$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    if wait_health "$AGENTD_BASE" "$SERVER_PID"; then
      pass "mock 服务就绪（:${PORT}）"
      step "m5 回归（审批 / 命名 / 打码 / 权限 / 工具清单）" run_py smoke/m5_regression.py
      step "p0 并发排队" run_py smoke/p0_queue_test.py
      step "p1 定时任务" run_py smoke/p1_jobs_test.py
      step "p2 Plan/Act + Recipe" run_py smoke/p2_plan_recipe_test.py
      step "p3 健壮性 API（非法输入 400）" run_py smoke/p3_robustness_api.py
      step "p8 消息级操作（按 id 截断 / 编辑重发 / 重新生成）" run_py smoke/p8_message_actions_test.py
      step "p5 前端契约（导出 / 设置回环 / 工具分组）" run_py smoke/p5_frontend_contract_test.py
    else
      fail "mock 服务就绪（:${PORT}）"
      echo "--- 服务日志 ---"; tail -30 "$SERVER_LOG"
      PORTS_TODO=1
    fi
    cleanup
  else
    fail "faketermux 桩创建"
    PORTS_TODO=1
  fi

  if [ "$PORTS_TODO" = 1 ]; then
    for name in "m5 回归" "p0 并发排队" "p1 定时任务" "p2 Plan/Act + Recipe" \
                "p3 健壮性 API" "p5 前端契约" "p8 消息级操作"; do
      fail "${name}（服务/桩未就绪，未执行）"
    done
  fi
fi

# ---------------------------------------------------------------- 前端
if [ "$RUN_FRONTEND" = 1 ]; then
  echo ""
  echo "########## 前端 ##########"
  if ! command -v npm >/dev/null 2>&1; then
    fail "前端（未安装 npm；纯前端环境请只跑 --frontend 并先装 Node）"
  else
    frontend() (
      cd "$ROOT/agentd/web" || return 1
      if [ "$FORCE_INSTALL" = 1 ] || [ ! -d node_modules ] || \
         [ package-lock.json -nt node_modules/.package-lock.json ]; then
        echo "--- npm ci"; npm ci --silent || return 1
      fi
      echo "--- typecheck"; npx tsc -b --noEmit || return 1
      echo "--- 单测"; npm run test --silent || return 1
      echo "--- 构建"; npm run build --silent || return 1
      # dist 入库（手机端零 Node），源码改了必须把构建产物一起提交
      if ! git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        echo "✗ 当前目录不是 git 仓库，无法校验 dist 新鲜度" >&2
        return 1
      fi
      if [ "$ALLOW_DIRTY_DIST" = 1 ]; then
        echo "--- dist 允许未提交（--allow-dirty-dist）"
      elif [ -n "$(git -C "$ROOT" status --porcelain -- agentd/web/dist)" ]; then
        echo "✗ dist/ 与 src 构建产物不一致：请重新 build 并提交（提交前自检可加 --allow-dirty-dist）" >&2
        git -C "$ROOT" status --porcelain -- agentd/web/dist >&2
        return 1
      fi
      echo "--- dist 与源码一致"
    )
    step "前端（typecheck + 单测 + 构建 + dist 新鲜度）" frontend
  fi
fi

# ---------------------------------------------------------------- E2E
if [ "$WITH_E2E" = 1 ]; then
  echo ""
  echo "########## E2E ##########"
  e2e() (
    cd "$ROOT/agentd/web" || return 1
    if [ ! -d node_modules ]; then
      npm ci --silent || return 1
    fi
    # 桩与 E2E 数据目录：playwright.config.ts 自己用 mkdtemp 建唯一目录，
    # 并在 global-teardown 里删除（显式传 E2E_AGENT_HOME 时保留，便于排障）。
    bash "$ROOT/tests/setup_faketermux.sh" >/dev/null 2>&1 || return 1
    PATH="${FAKETERMUX_DIR:-/tmp/faketermux}:$PATH" \
      npm run test:e2e
  )
  step "E2E（Playwright）" e2e
fi

# ---------------------------------------------------------------- 汇总
echo ""
echo "================ 汇总 ================"
printf '通过 %d / 跳过 %d / 失败 %d\n' "${#PASSED[@]}" "${#SKIPPED[@]}" "${#FAILED[@]}"
if [ "${#SKIPPED[@]}" -gt 0 ]; then
  printf '跳过：%s\n' "${SKIPPED[*]}"
fi
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf '失败：%s\n' "${FAILED[*]}"
  if [ "$RUN_BACKEND" = 1 ] && [ -s "$SERVER_LOG" ]; then
    echo "--- mock 服务日志尾部 ---"; tail -20 "$SERVER_LOG"
  fi
  echo "（门禁数据目录：$GATE_TMP）"
  exit 1
fi
if [ $(( ${#PASSED[@]} + ${#SKIPPED[@]} )) -eq 0 ]; then
  echo "没有执行任何检查（参数组合有误？）" >&2
  exit 2
fi
rm -rf "$GATE_TMP"
echo "全绿"
