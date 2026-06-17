# MobiAgent Phone Runner

在 Android 设备上使用 **llama.cpp + GGUF 模型** 完全本地运行 GUI Agent。
无需云端服务器,无需 MNN,支持自由切换社区模型。

## 架构

```
┌─────────────────────────────────────┐
│           Termux (Android)          │
│                                     │
│  ┌─────────────────────────────┐    │
│  │     mobiagent.py             │    │
│  │  (Planner/Decider/Grounder)  │    │
│  └──────────┬──────────────────┘    │
│             │ OpenAI API            │
│  ┌──────────▼──────────────────┐    │
│  │    llama-server (GGUF)       │    │
│  │  MiniCPM-V / Qwen3.5 / ...   │    │
│  └──────────┬──────────────────┘    │
│             │                       │
│  ┌──────────▼──────────────────┐    │
│  │  uiautomator2 → 手机控制     │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

## 支持的模型

所有模型信息均已联网验证(2026-06),配置统一从 `config.yaml` 加载。

| 模型 ID | 参数 | 量化大小 | RAM 需求 | License | 视觉 | 说明 |
|---------|------|---------|---------|---------|------|------|
| `minicpm-v-4.6` ⭐ | 1.3B | 1.3 GB | 4 GB | Apache 2.0 | 是 | 默认,同尺寸 SOTA |
| `qwen3.5-0.8b` | 0.8B | 0.6 GB | 3 GB | Apache 2.0 | 是 | 最小,原生多模态 |
| `qwen3.5-2b` | 2B | 1.5 GB | 4 GB | Apache 2.0 | 是 | 原生多模态(图+视频+文本) |
| `qwen3.5-4b` | 4B | 2.5 GB | 6 GB | Apache 2.0 | 是 | Unsloth 动态量化 |
| `gui-owl-7b` | 7B | 5.0 GB | 10 GB | MIT | 是 | GUI 专精,需自转 GGUF |

> 默认使用 `minicpm-v-4.6`(最小)。`install.sh` 会根据 RAM 自动选择。
> `gui-owl-7b` 无官方 GGUF,需用 `convert_hf_to_gguf.py` 从 safetensors 自行转换。

## 快速开始

### 1. 安装 Termux

从 [F-Droid](https://f-droid.org/packages/com.termux/) 安装 Termux(不要用 Google Play 版,已过时)。

### 2. 一键安装

```bash
# 克隆仓库到手机
git clone https://github.com/badhope/MobiAgent.git
cd MobiAgent/phone_runner

# 一键安装(自动检测 RAM,选择模型,编译 llama.cpp,下载模型)
bash install.sh

# 或指定模型
bash install.sh minicpm-v-4.6
bash install.sh qwen3.5-4b
```

### 3. 运行

```bash
# 默认模型(本地推理,--model 非 cloud 即自动启用本地 LLM)
python mobiagent.py --model minicpm-v-4.6

# 自动选择模型(根据 RAM)
python mobiagent.py --model auto

# 使用自定义 GGUF 模型路径
python mobiagent.py --model-path /path/to/model.gguf --mmproj-path /path/to/mmproj.gguf

# 查看所有可用模型
python mobiagent.py --list-models

# 关闭 Qwen3 坐标系(使用旧坐标系)
python mobiagent.py --model minicpm-v-4.6 --no-use_qwen3

# 关闭 grounder,优先用 XML 层级匹配
python mobiagent.py --model minicpm-v-4.6 --no-use_grounder
```

## 手动安装(如果一键脚本失败)

### 步骤 1: 安装依赖

```bash
pkg update && pkg upgrade -y
pkg install rust python git cmake clang wget -y
pip install --upgrade pip
pip install -r requirements.txt
```

### 步骤 2: 编译 llama.cpp

```bash
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git /tmp/llama.cpp
cd /tmp/llama.cpp
cmake -B build -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TESTS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j$(nproc)

# 查找编译产物(路径因平台而异)
LLAMA_BIN=$(find build -name "llama-server" -type f | head -1)
cp "$LLAMA_BIN" $PREFIX/bin/
```

### 步骤 3: 下载模型

```bash
mkdir -p models/minicpm-v-4.6
cd models/minicpm-v-4.6

# 主模型
wget -c https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4.6-Q4_K_M.gguf

# 视觉投影(多模态必需)
wget -c https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-MiniCPM-V-4.6-F16.gguf

cd ../..
```

> 国内用户可使用镜像加速:把 `huggingface.co` 替换为 `hf-mirror.com`

### 步骤 4: 运行

```bash
python mobiagent.py --model minicpm-v-4.6
```

## 目录结构

```
phone_runner/
├── mobiagent.py          # 主程序(Agent 循环)
├── llm_backend.py        # LLM 后端管理(llama-server 封装)
├── config.yaml           # 模型和服务器配置(单一数据源)
├── requirements.txt      # Python 依赖
├── install.sh            # 一键安装脚本
├── models/               # 模型文件(install.sh 自动下载)
│   └── minicpm-v-4.6/
│       ├── *.gguf
│       └── mmproj-*.gguf
└── data/                 # 运行数据(截图/日志)
```

## 配置

所有配置集中在 `config.yaml`(单一数据源,代码与配置不漂移):

```yaml
default_model: "minicpm-v-4.6"

server:
  port: 8080
  ctx_size: 4096
  n_gpu_layers: 0    # Termux 用 CPU

agent:
  max_steps: 35
  step_delay: 1.0
```

## 添加自定义模型

在 `config.yaml` 的 `models` 段添加(无需改代码,`MODEL_REGISTRY` 会自动加载):

```yaml
models:
  my-custom-model:
    name: "My Custom Model"
    repo: "user/my-model-GGUF"
    files:
      model: "my-model-Q4_K_M.gguf"
      mmproj: "mmproj-f16.gguf"    # 视觉模型需要,纯文本模型可省略
    size_gb: 2.0
    ram_gb: 6
    license: "Apache-2.0"
    source: "huggingface"
    is_vision: true
    description: "我的自定义模型"
```

然后运行:
```bash
python mobiagent.py --model my-custom-model
```

## 命令行参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--model` | `auto` | 模型名称(`auto`/`minicpm-v-4.6`/`qwen3.5-*`/`gui-owl-7b`/`cloud`) |
| `--model-path` | - | 直接指定 GGUF 模型文件路径(优先于 `--model`) |
| `--mmproj-path` | - | 视觉模型 mmproj 文件路径 |
| `--port` | 8080 | llama-server 端口 |
| `--ctx-size` | 4096 | 上下文长度 |
| `--list-models` | - | 列出所有可用模型并退出 |
| `--use_qwen3` / `--no-use_qwen3` | 开启 | Qwen3 坐标系(1000 归一化) |
| `--use_grounder` / `--no-use_grounder` | 开启 | grounder 模型定位(关闭则优先 XML 匹配) |
| `--use_experience` | 关闭 | 使用 planner 重写的任务描述 |
| `--device` | `Android` | 设备类型(`Android`/`Harmony`) |
| `--device_endpoint` | - | ADB 端点 |
| `--data_dir` | `./data` | 数据目录 |
| `--task_file` | `./task.json` | 任务文件 |

> `--model` 非 `cloud` 时,自动启用本地 planner/decider/grounder,无需手动加 `--local_*`。

## 设备要求

| 模型 | 最低 RAM | 推荐 RAM | 速度 |
|------|---------|---------|------|
| qwen3.5-0.8b | 3 GB | 4 GB | 15-20 tok/s |
| minicpm-v-4.6 | 4 GB | 6 GB | 8-12 tok/s |
| qwen3.5-2b | 4 GB | 6 GB | 10-15 tok/s |
| qwen3.5-4b | 6 GB | 8 GB | 6-10 tok/s |
| gui-owl-7b | 10 GB | 12 GB | 3-6 tok/s |

> 速度基于骁龙 8 Gen 3,CPU 推理。开启 GPU 加速可提升 2-3 倍。

## 注意事项

- 首次启动需要 30-60 秒加载模型到内存
- 确保手机已开启 USB 调试并安装 ADB keyboard(`com.android.adbkeyboard`)
- 在开发者选项中关闭电池优化,避免后台被杀
- 模型文件较大(0.6-5 GB),建议在 WiFi 环境下载
- 截图和运行数据保存在 `data/` 目录

## 故障排除

### llama-server 启动失败

```bash
# 检查是否安装
which llama-server

# 手动启动测试
llama-server -m models/minicpm-v-4.6/MiniCPM-V-4.6-Q4_K_M.gguf --port 8080

# 端口被占用时清理
kill $(lsof -t -i:8080)
```

### 模型下载慢

使用国内镜像:
```bash
# HF 镜像
export HF_ENDPOINT=https://hf-mirror.com
huggingface-cli download openbmb/MiniCPM-V-4.6-gguf

# 或直接 wget
wget -c https://hf-mirror.com/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4.6-Q4_K_M.gguf
```

### uiautomator2 连接失败

```bash
# 确认 ADB 连接
adb devices

# 初始化 uiautomator2
python -m uiautomator2 init

# 重试连接
python -c "import uiautomator2 as u2; d = u2.connect(); print(d.info)"
```
