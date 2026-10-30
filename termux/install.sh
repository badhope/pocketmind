#!/usr/bin/env bash
# 口袋 Agent · 手机端一键安装
#
# 用法（在 Termux 里执行）：
#   pkg install git
#   git clone <本仓库地址>
#   cd pocketmind && bash termux/install.sh
#
# 安装内容（首次约 10-30 分钟，主要耗在 pydantic-core 的 Rust 现场编译；
# 中低端手机可能更久。已装过的部分会自动跳过，重复执行只补缺的包）：
#   1. Python 3 + 编译工具   pkg install python git rust binutils build-essential
#      （pydantic-core 是 Rust 写的，Termux/aarch64 无预编译 wheel，必须本地编译）
#   2. termux-api            手机能力桥（通知/剪贴板/电量/短信/电话…）
#   3. pip 依赖               fastapi / uvicorn / openai / apscheduler
#   4. 数据目录               $HOME/.agent/termux-agent（权限 700，仅自己可读）
#   5. （可选）离线语音       sherpa-onnx + 中文模型，见 termux/voice.md
#   6. （可选）开机自启       termux-boot 插件，见 termux/boot.sh
set -euo pipefail
cd "$(dirname "$0")/.."

step() { echo ""; echo "== $1 =="; }

step "1/4 检查 Termux 基础组件"
command -v pkg >/dev/null 2>&1 || { echo "❌ 请先在 Termux 里执行：pkg install termux-tools"; exit 1; }
# python/git/编译工具链：pydantic-core(Rust) 与可能的 C 扩展需要 rust + binutils + build-essential。
#
# 判断"是否已安装"不能一律用 command -v：
#   - 元包（build-essential）不提供同名可执行文件 → command -v 恒失败 → 每次重装；
#   - rust 的可执行文件叫 rustc，不叫 rust → 同样恒失败。
# 在手机上重装一次要几分钟并耗流量，所以按包的真实"存在证据"分别判断。
# 返回 0 表示"已安装"，非 0 表示"缺失"（与 if 的语义对齐，别写反）
pkg_present() {
  case "$1" in
    python)   command -v python  >/dev/null 2>&1 ;;
    git)      command -v git     >/dev/null 2>&1 ;;
    rust)     command -v rustc   >/dev/null 2>&1 ;;
    binutils) command -v ld      >/dev/null 2>&1 || command -v ar >/dev/null 2>&1 ;;
    # 元包没有可执行文件：用编译器是否可用作为证据（它装的正是 gcc 工具链）
    build-essential) command -v cc >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1 ;;
    *)        command -v "$1"    >/dev/null 2>&1 ;;
  esac
}

for pkg_name in python git rust binutils build-essential; do
  if ! pkg_present "$pkg_name"; then
    echo "安装 $pkg_name..."
    if ! pkg install -y "$pkg_name"; then
      echo "⚠️  $pkg_name 安装失败（可稍后手动执行：pkg install $pkg_name）"
    fi
  fi
done
# termux-api：真正的证据是 termux-api 的可执行命令，不是包名
if ! command -v termux-notification >/dev/null 2>&1; then
  echo "安装 termux-api（手机能力桥）..."
  if ! pkg install -y termux-api; then
    echo "⚠️  termux-api 安装失败。手机能力将不可用，但不影响对话与文件操作。"
    echo "   修复：pkg install termux-api，并安装应用商店里的 Termux:API 配套 App。"
  fi
fi
# 存储权限（读 /sdcard、~/storage 需要）
if [ ! -d "$HOME/storage" ]; then
  echo "提示：首次使用请允许存储权限 → termux-setup-storage（会弹出系统授权）"
fi
python --version

step "2/4 安装 Python 依赖"
# Termux 上有一条关键捷径：部分编译型依赖在 Termux 仓库里有**预编译** deb，
# 用 pkg 装比让 pip 现场编译快一个数量级（实测 cryptography 的 Rust+OpenSSL
# 编译要 30 分钟以上且吃掉 2GB+ 临时空间，模拟器因此直接 Enospc 失败）。
# 先装这些，pip 就会复用系统已装的版本，不再自己编译。
PREBUILT="python-cryptography python-rpds-py python-numpy python-lxml"
for pkg in $PREBUILT; do
  if apt-cache policy "$pkg" 2>/dev/null | grep -q "Installed: (none)"; then
    echo "预装 $pkg（Termux 官方预编译包，避免源码编译）..."
    pkg install -y "$pkg" >/dev/null 2>&1 || echo "⚠️  $pkg 预装失败，将由 pip 尝试编译（可能很慢）"
  fi
done
# Termux 里 PyPI 官方源常常连不通（实测：pypi.org 连接被拒，清华/阿里云镜像正常）。
# 先试官方源，失败自动换镜像重试——不能让用户对着 "Connection refused" 自己猜。
PIP_MIRROR="${AGENT_PIP_MIRROR:-https://mirrors.aliyun.com/pypi/simple}"
python -m pip install --upgrade pip -q
# Termux 新版 Python 3.12 可能标记为 externally-managed（PEP 668）：
# 先按普通方式装，失败再回退 --break-system-packages（Termux 的 Python 即用户自己的环境，
# 没有 root 系统 Python，加该参数是安全且常规的做法）。
if ! python -m pip install -r agentd/requirements.txt -q 2>"$HOME/.agent-pip-err.log"; then
  echo "官方 PyPI 失败（Termux 上常见），改用镜像 $PIP_MIRROR 重试..."
  if ! python -m pip install -i "$PIP_MIRROR" -r agentd/requirements.txt -q 2>"$HOME/.agent-pip-err.log"; then
    echo "镜像安装也失败，改用 --break-system-packages 再试..."
  fi
fi
if ! python -c "import fastapi, pydantic" >/dev/null 2>&1; then
  if ! python -m pip install --break-system-packages -i "$PIP_MIRROR" -r agentd/requirements.txt -q 2>"$HOME/.agent-pip-err.log"; then
    echo "❌ Python 依赖安装失败。最后 20 行错误："
    tail -20 "$HOME/.agent-pip-err.log" 2>/dev/null || true
    echo ""
    echo "常见原因与处理："
    echo "  1. 网络不通/镜像慢：换源后重试"
    echo "     pip install -i https://mirrors.aliyun.com/pypi/simple --break-system-packages -r agentd/requirements.txt"
    echo "  2. pydantic-core 需要编译：确认已装 rust（pkg install rust）"
    echo "  3. 磁盘不足：df -h 查看剩余空间，至少需要约 500MB"
    exit 1
  fi
fi
rm -f "$HOME/.agent-pip-err.log"

step "3/4 初始化数据目录（权限 700，仅自己可读）"
DATA_DIR="$HOME/.agent/termux-agent"
mkdir -p "$DATA_DIR/logs"
chmod 700 "$DATA_DIR"

# ── 访问令牌（Android 上 127.0.0.1 是全机共享的，必须鉴权）──────────────────
# 背景：安卓没有"只有本机进程能连的端口"，任何 App 都能连 127.0.0.1:8787。
# 若 agentd 无令牌，同机任意应用都可以读你的全部会话、调用 shell 工具。
# 因此安装时生成随机令牌：
#   1. 写 $DATA_DIR/token（600），start.sh 会作为 AGENT_TOKEN 注入；
#   2. 同一份值由 APK 壳通过 Termux 读出并注入 WebView，用户无需手抄。
# 已有令牌时不覆盖（重装不应让已登录的客户端失效）。
TOKEN_FILE="$DATA_DIR/token"
if [ ! -s "$TOKEN_FILE" ]; then
  # 生成 32 字节随机 → 64 hex。多级回退，任一步成功即可：
  #   1. python（下一步装依赖本来就用它）
  #   2. od /dev/urandom（coreutils 齐全时）
  #   3. 都不可用 → 不阻断安装：agentd 在 Android 上首次启动会自己生成并落盘
  #      （见 agentd/main.py 的 _is_android_runtime 分支），安全性不打折。
  TOKEN="$(python -c 'import secrets;print(secrets.token_hex(32))' 2>/dev/null || true)"
  if [ -z "$TOKEN" ] && [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
    TOKEN="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
  fi
  if [ -n "$TOKEN" ]; then
    # 子 shell 局部 umask：全局 umask 077 会污染同一 shell 的后续步骤，
    # 让装完后创建的其它文件全部变成 600。
    (
      umask 077
      printf '%s' "$TOKEN" > "$TOKEN_FILE"
    )
    chmod 600 "$TOKEN_FILE" 2>/dev/null || true
    echo "已生成访问令牌（保存在 $TOKEN_FILE，权限 600）"
  else
    echo "⚠️  暂时无法生成访问令牌；agentd 首次启动时会自动生成（无需手工处理）"
  fi
else
  chmod 600 "$TOKEN_FILE" 2>/dev/null || true
  echo "访问令牌已存在，保留原值"
fi

step "4/4 可选能力（按需，跳过不影响主功能）"
echo ""
echo "a) 离线语音（完全本地 TTS，约 135MB）：见 termux/voice.md"
echo "   pip install sherpa-onnx，再下载中文模型到 \$HOME/.agent/termux-agent/tts-model/"
echo "b) 开机自启（重启后自动拉起服务）：见 termux/boot.sh"
echo "   pkg install termux-boot && mkdir -p ~/.termux/boot && cp termux/boot.sh ~/.termux/boot/agentd.sh && chmod +x ~/.termux/boot/agentd.sh"

step "完成 ✅"
echo ""
echo "启动：            bash termux/start.sh"
echo "浏览器打开：      http://127.0.0.1:8787"
echo ""
echo "首次使用：浏览器打开后先粘贴访问令牌（cat ~/.agent/termux-agent/token），"
echo "          再到右上角 ⚙ 设置里选择模型厂商（豆包/DeepSeek/千问/Kimi/智谱/硅基流动）并填入 API Key。"
echo "数据安全：会话/记忆/备份全部存本机（$DATA_DIR），权限 700。"
echo "撤销保护：写文件/删文件等危险操作执行前会自动备份，对话里可一键撤销。"
