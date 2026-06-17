#!/bin/bash
# ============================================================================
# MobiAgent Phone Runner - 一键安装脚本
# 在 Termux (Android) 环境中安装 llama.cpp + GGUF 模型
# ============================================================================
set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
step()  { echo -e "\n${BLUE}=== $1 ===${NC}\n"; }

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODELS_DIR="${SCRIPT_DIR}/models"

# 默认参数
MODEL="${1:-auto}"
# llama.cpp release tag (用 master 分支,避免 tag 不存在)
LLAMA_BRANCH="master"

# ============================================================================
# 1. 检测系统
# ============================================================================
detect_system() {
    step "检测系统环境"

    RAM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
    RAM_GB=$((RAM_KB / 1024 / 1024))
    ARCH=$(uname -m)
    CPU_CORES=$(nproc 2>/dev/null || echo 4)

    # 检测是否在 Termux 中
    if [ -z "$PREFIX" ] || [ ! -d "$PREFIX" ]; then
        warn "未检测到 Termux 环境 (PREFIX 未设置)"
        warn "本脚本专为 Termux 设计,在其他环境可能需要调整"
    fi

    info "RAM: ${RAM_GB} GB"
    info "CPU 架构: ${ARCH}"
    info "CPU 核心数: ${CPU_CORES}"

    # 检查架构支持
    case "$ARCH" in
        aarch64|arm64)
            info "架构支持: ARM64 ✅"
            ;;
        *)
            error "不支持的架构: ${ARCH} (仅支持 aarch64)"
            exit 1
            ;;
    esac
}

# ============================================================================
# 2. 安装 Termux 依赖
# ============================================================================
install_deps() {
    step "安装系统依赖"

    pkg update -y
    pkg install -y rust python git cmake clang wget curl

    info "安装 Python 依赖..."
    pip install --upgrade pip
    pip install -r "${SCRIPT_DIR}/requirements.txt"

    info "依赖安装完成 ✅"
}

# ============================================================================
# 3. 安装 llama.cpp
# ============================================================================
install_llamacpp() {
    step "安装 llama.cpp"

    # 检查是否已安装
    if command -v llama-server &>/dev/null; then
        info "llama-server 已安装,跳过编译"
        return 0
    fi

    # 确定安装目标目录(防止 $PREFIX 为空导致 cp 到 /bin/)
    if [ -n "$PREFIX" ] && [ -d "$PREFIX/bin" ]; then
        INSTALL_BIN="$PREFIX/bin"
    else
        warn "未检测到 Termux PREFIX,安装到 /usr/local/bin (需要 sudo)"
        INSTALL_BIN="/usr/local/bin"
        if [ "$(id -u)" != "0" ] && ! sudo -n true 2>/dev/null; then
            error "需要 root 权限安装到 ${INSTALL_BIN},请用 sudo 运行或手动指定路径"
            exit 1
        fi
    fi

    BUILD_DIR="/tmp/llama.cpp-build"

    info "从源码编译 llama.cpp (需要几分钟)..."
    rm -rf "$BUILD_DIR"
    git clone --depth 1 --branch "$LLAMA_BRANCH" \
        https://github.com/ggerganov/llama.cpp.git "$BUILD_DIR"

    cd "$BUILD_DIR"
    cmake -B build \
        -DLLAMA_BUILD_SERVER=ON \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build build --config Release -j"$CPU_CORES"

    # 查找编译产物(不同平台路径可能不同)
    LLAMA_BIN=$(find build -name "llama-server" -type f | head -1)
    if [ -z "$LLAMA_BIN" ]; then
        error "编译成功但找不到 llama-server 可执行文件"
        error "请检查 build/ 目录: ls -R build/ | grep llama-server"
        exit 1
    fi

    # 安装
    if [ "$(id -u)" = "0" ]; then
        cp "$LLAMA_BIN" "$INSTALL_BIN/"
    elif [ "$INSTALL_BIN" = "/usr/local/bin" ]; then
        sudo cp "$LLAMA_BIN" "$INSTALL_BIN/"
    else
        cp "$LLAMA_BIN" "$INSTALL_BIN/"
    fi
    chmod +x "$INSTALL_BIN/llama-server"

    cd "$SCRIPT_DIR"
    rm -rf "$BUILD_DIR"

    info "llama.cpp 安装完成 ✅"
    info "  路径: $(which llama-server)"
}

# ============================================================================
# 4. 模型选择
# ============================================================================
select_model() {
    step "选择模型"

    if [ "$MODEL" == "auto" ]; then
        info "自动选择模型 (RAM=${RAM_GB}GB)..."

        if [ $RAM_GB -lt 4 ]; then
            MODEL="gemma4-e2b"
        elif [ $RAM_GB -lt 6 ]; then
            MODEL="minicpm-v-4.6"
        elif [ $RAM_GB -lt 10 ]; then
            MODEL="qwen3.5-4b"
        else
            MODEL="gui-owl-1.5-8b"
        fi
    fi

    info "选定模型: ${MODEL}"

    # 模型信息
    case "$MODEL" in
        minicpm-v-4.6)
            MODEL_REPO="openbmb/MiniCPM-V-4.6-GGUF"
            MODEL_FILE="MiniCPM-V-4.6-Q4_K_M.gguf"
            MMPROJ_FILE="mmproj-model-f16.gguf"
            MODEL_SIZE_GB="1.3"
            ;;
        qwen3.5-2b)
            MODEL_REPO="unsloth/Qwen3.5-2B-Instruct-GGUF"
            MODEL_FILE="Qwen3.5-2B-Instruct-UD-Q4_K_XL.gguf"
            MMPROJ_FILE=""
            MODEL_SIZE_GB="1.5"
            ;;
        qwen3.5-4b)
            MODEL_REPO="unsloth/Qwen3.5-4B-Instruct-GGUF"
            MODEL_FILE="Qwen3.5-4B-Instruct-UD-Q4_K_XL.gguf"
            MMPROJ_FILE=""
            MODEL_SIZE_GB="2.5"
            ;;
        gemma4-e2b)
            MODEL_REPO="google/gemma-4-e2b-it-GGUF"
            MODEL_FILE="gemma-4-e2b-it-Q4_K_M.gguf"
            MMPROJ_FILE="mmproj-model-f16.gguf"
            MODEL_SIZE_GB="1.2"
            ;;
        gui-owl-1.5-8b)
            MODEL_REPO="mPLUG/GUI-Owl-1.5-8B-Instruct-GGUF"
            MODEL_FILE="GUI-Owl-1.5-8B-Instruct-Q4_K_M.gguf"
            MMPROJ_FILE="mmproj-model-f16.gguf"
            MODEL_SIZE_GB="5.0"
            ;;
        *)
            error "未知模型: $MODEL"
            error "可用: minicpm-v-4.6, qwen3.5-2b, qwen3.5-4b, gemma4-e2b, gui-owl-1.5-8b"
            exit 1
            ;;
    esac

    info "  仓库: ${MODEL_REPO}"
    info "  文件: ${MODEL_FILE}"
    info "  大小: ${MODEL_SIZE_GB} GB"
    if [ -n "$MMPROJ_FILE" ]; then
        info "  视觉: ${MMPROJ_FILE}"
    fi
}

# ============================================================================
# 5. 下载模型
# ============================================================================
download_model() {
    step "下载模型"

    MODEL_DIR="${MODELS_DIR}/${MODEL}"
    mkdir -p "$MODEL_DIR"

    # 尝试 HF 镜像(国内加速)
    HF_BASE="https://huggingface.co"
    HF_MIRROR="https://hf-mirror.com"

    MODEL_URL="${HF_BASE}/${MODEL_REPO}/resolve/main/${MODEL_FILE}"
    MODEL_URL_MIRROR="${HF_MIRROR}/${MODEL_REPO}/resolve/main/${MODEL_FILE}"

    MODEL_PATH="${MODEL_DIR}/${MODEL_FILE}"

    if [ -f "$MODEL_PATH" ]; then
        info "模型文件已存在,跳过下载"
    else
        info "下载模型文件 (${MODEL_SIZE_GB} GB)..."
        info "  如果下载慢,可手动用 hf-mirror.com"

        # 先尝试官方源,失败则用镜像
        if wget -c -q --show-progress "$MODEL_URL" -O "$MODEL_PATH" 2>/dev/null; then
            info "从 HuggingFace 下载完成 ✅"
        else
            warn "官方源下载失败,尝试镜像源..."
            wget -c --show-progress "$MODEL_URL_MIRROR" -O "$MODEL_PATH"
            info "从镜像源下载完成 ✅"
        fi
    fi

    # 下载 mmproj(视觉模型需要)
    if [ -n "$MMPROJ_FILE" ]; then
        MMPROJ_PATH="${MODEL_DIR}/${MMPROJ_FILE}"
        MMPROJ_URL="${HF_BASE}/${MODEL_REPO}/resolve/main/${MMPROJ_FILE}"
        MMPROJ_URL_MIRROR="${HF_MIRROR}/${MODEL_REPO}/resolve/main/${MMPROJ_FILE}"

        if [ -f "$MMPROJ_PATH" ]; then
            info "mmproj 文件已存在,跳过下载"
        else
            info "下载 mmproj 文件..."
            if wget -c -q --show-progress "$MMPROJ_URL" -O "$MMPROJ_PATH" 2>/dev/null; then
                info "mmproj 下载完成 ✅"
            else
                wget -c --show-progress "$MMPROJ_URL_MIRROR" -O "$MMPROJ_PATH"
                info "mmproj 下载完成 ✅"
            fi
        fi
    fi
}

# ============================================================================
# 6. 验证安装
# ============================================================================
verify_install() {
    step "验证安装"

    # 检查 llama-server
    if ! command -v llama-server &>/dev/null; then
        error "llama-server 未安装"
        exit 1
    fi
    info "llama-server: $(which llama-server) ✅"

    # 检查 Python 依赖
    python -c "import requests, PIL, uiautomator2, pydantic, yaml, openai" 2>/dev/null || {
        error "Python 依赖缺失,请运行: pip install -r requirements.txt"
        exit 1
    }
    info "Python 依赖 ✅"

    # 检查模型文件
    MODEL_DIR="${MODELS_DIR}/${MODEL}"
    if [ ! -f "${MODEL_DIR}/${MODEL_FILE}" ]; then
        error "模型文件缺失: ${MODEL_DIR}/${MODEL_FILE}"
        exit 1
    fi
    info "模型文件 ✅"

    info "\n安装验证通过! 🎉"
}

# ============================================================================
# 7. 输出使用说明
# ============================================================================
print_usage() {
    step "安装完成"

    echo "MobiAgent Phone Runner 已安装成功!"
    echo ""
    echo "选定模型: ${MODEL} (${MODEL_SIZE_GB} GB)"
    echo ""
    echo "快速启动:"
    echo "  python mobiagent.py --model ${MODEL}"
    echo ""
    echo "手动指定模型:"
    echo "  python mobiagent.py --model minicpm-v-4.6"
    echo "  python mobiagent.py --model qwen3.5-4b"
    echo "  python mobiagent.py --model gui-owl-1.5-8b"
    echo ""
    echo "使用自定义模型路径:"
    echo "  python mobiagent.py --model-path /path/to/model.gguf"
    echo ""
    echo "查看所有可用模型:"
    echo "  python mobiagent.py --list-models"
    echo ""
    echo "确保手机已开启 USB 调试并连接(或本机运行)。"
}

# ============================================================================
# 主流程
# ============================================================================
main() {
    echo ""
    echo "============================================"
    echo "  MobiAgent Phone Runner Installer"
    echo "  llama.cpp + GGUF 本地推理方案"
    echo "============================================"
    echo ""

    detect_system
    install_deps
    install_llamacpp
    select_model
    download_model
    verify_install
    print_usage
}

main "$@"
