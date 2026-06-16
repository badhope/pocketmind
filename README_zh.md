# MobiAgent

> **可定制的移动端智能体框架** — 视觉-语言模型驱动的 Android / HarmonyOS 自动化,带模型族、即插即用、记录回放加速、真实设备评测基准。

[English](./README.md)

---

## 这是什么

MobiAgent 是一个**全栈移动端智能体系统**。你用自然语言描述任务("打开美团搜索附近的火锅店"),agent 通过截图 + 点击 + 输入 + 滑动驱动 Android(或 HarmonyOS)手机,直到任务完成。

- **可定制** — 自己的模型随便换
- **模块化** — agent 循环、加速框架、评测基准是独立的,可单独用
- **诚实的评测** — 跑在真机、跑在常用 App 上,不是合成截图

---

## 包含什么

### 1. 智能体模型族
一组为手机 GUI 任务微调的视觉-语言模型:
- **Planner(规划器)** — 把任务拆成高层步骤
- **Decider(决策器)** — 看当前屏幕,选下一步动作
- **Grounder(定位器)** — 把自然语言描述的元素转换成精确坐标

模型有 3B / 4B / 7B 三个尺寸。4B 混合版可同时扮演 Decider + Grounder,整套 agent 跑在一张 GPU 上。

### 2. 加速框架 — AgentRR(Record & Replay)
- 把成功的动作序列缓存成经验树
- 相似任务直接复用(重复模式 2-3× 加速)
- 一个轻量记忆模型(< 几十 MB)毫秒级匹配当前屏幕到历史经验
- 真实场景复用率:
  - 随机任务:30-60%
  - 80/20 分布(典型用户):60-85%
- 复用准确率:>99%

### 3. 评测基准 — MobiFlow
- 里程碑 DAG:每个任务有多个可接受轨迹
- 跑在真机上(不是截图,不是模拟器)
- 覆盖 20+ 主流 App(美团、淘宝、携程、小红书、...)
- 容忍环境噪音(弹窗、网络抖动、版本差异)

### 4. Android App
[`app/`](app/) 下是 APK 源码,端用户用,云端模型有免费额度,零配置。

### 5. Phone Runner(手机端推理)
用 4B 量化模型直接在手机上跑 agent — 无服务器、无云、完全本地。详见 [`phone_runner/README.md`](phone_runner/README.md)。

---

## 快速开始

### A. 直接用 App(最简单)

从 [`app/`](app/) 构建 APK,装到 Android 设备。登录,输入任务,看 agent 干活。云端模型有免费额度。

### B. 用 Python 驱动(开发者路径)

#### 1. 环境

```bash
conda create -n mobiagent python=3.10
conda activate mobiagent
pip install -r requirements.txt
```

完整流水线额外依赖(OmniParser 权重、embedding 模型、OCR):
```bash
# OmniParser 用于图标检测
for f in icon_detect/{train_args.yaml,model.pt,model.yaml} ; do
  huggingface-cli download microsoft/OmniParser-v2.0 "$f" --local-dir weights
done

# embedding 模型(用于记忆)
huggingface-cli download BAAI/bge-small-zh --local-dir ./utils/experience/BAAI/bge-small-zh

# OCR(可选,处理非英文屏幕)
sudo apt install tesseract-ocr tesseract-ocr-chi-sim
```

#### 2. 手机

- 装 [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk)(用于 `Type` 动作)
- 开开发者选项 → USB 调试
- USB 连电脑

#### 3. 用 vLLM 部署模型

```bash
# 4B 混合模型 — 单 GPU,同时跑 Decider + Grounder
vllm serve <model-checkpoint> --port 8000 --served-model-name mobi-mind

# 或者拆成 Planner / Decider / Grounder 跑在不同端口
vllm serve <decider-or-mixed> --port 8000
vllm serve Qwen/Qwen3-4B-Instruct --port 8002
```

#### 4. 跑

把任务列表写到 `runner/mobiagent/task.json`,然后:

```bash
python -m runner.mobiagent.mobiagent \
  --service_ip localhost \
  --decider_port 8000 \
  --planner_port 8002
```

常用参数:
- `--user_profile on` — 启用用户偏好记忆(需要 Milvus 或 Neo4j)
- `--use_experience` — 检索相似历史任务改写规划
- `--e2e` — 端到端模式,不单独调 Grounder
- `--device Android` 或 `--device Harmony`
- `--data_dir <path>` — 执行轨迹保存路径

多任务(跨 App):
```bash
python -m runner.mobiagent.multi_task.mobiagent_refactored \
  --service_ip localhost \
  --decider_port 8000 \
  --planner_port 8002 \
  --task "在淘宝搜 iPhone 17,把前 3 个结果发到微信"
```

---

## 记忆子系统(可选)

三层,都可独立启用:

| 层 | 作用 | 后端 |
|---|---|---|
| 用户画像 | 长期偏好、家庭地址、联系人 | Milvus(向量)或 Neo4j(图) |
| 经验记忆 | 相似历史任务的规划 | 本地文件 |
| 动作记忆 (AgentRR) | 缓存的动作序列 | 本地文件 + embedding 模型 |

通过 `.env` 配置:
```bash
MILVUS_URL=http://localhost:19530
EMBEDDING_MODEL=BAAI/bge-small-zh
EMBEDDING_MODEL_DIMS=384
OPENAI_API_KEY=sk-...   # 给记忆用的 LLM,不是给 agent 用的
OPENAI_BASE_URL=https://api.openai.com/v1
```

详见 [`runner/README.md`](runner/README.md)。

---

## 项目结构

```
agent_rr/        记录回放加速框架
collect/         数据收集、标注、处理、导出
runner/          agent 执行器(ADB 控手机)
MobiFlow/        真实设备评测基准
app/             Android App 源码(APK 构建)
phone_runner/    手机端推理 runner
deployment/      App 的服务端部署
prompts/         Planner / Decider / Grounder 的 prompt 模板
utils/           公共工具(解析、工具、权重)
```

---

## 性能

MobiFlow 实测(真机 + 主流中文 App):

| 方案 | 总成功率 | 简单任务 | 复杂任务 |
|---|---|---|---|
| 本项目(全套) | **86.4%** | 94.2% | 78.9% |
| GPT-5 | 71.2% | 88.5% | 55.3% |
| Gemini 2.5 Pro | 73.8% | 91.0% | 58.1% |
| UI-TARS-1.5-7B | 68.7% | 85.4% | 52.6% |

复杂 App(美团、淘宝、携程)差距拉到 20-30 个百分点。AgentRR 在重复任务上 2-3× 加速。

---

## 子模块 README

- [`runner/README.md`](runner/README.md) — agent 执行
- [`runner/mobiagent/multi_task/README.md`](runner/mobiagent/multi_task/README.md) — 跨 App 工作流
- [`agent_rr/README.md`](agent_rr/README.md) — 记录回放原理
- [`MobiFlow/README.md`](MobiFlow/README.md) — 评测基准
- [`phone_runner/README.md`](phone_runner/README.md) — 手机端推理
- [`app/README.md`](app/README.md) — Android App

---

## 安全

- **真机操作时人必须在场。** 任何支付 / 删除 / 发送都建议加人工确认。
- **严禁用于绕过平台安全机制**(验证码、限流、爬数据、薅羊毛等)。
- 模型可能被 prompt injection,关键操作在代码层校验,不要相信 agent 输出。

---

## License

Apache 2.0。详见 [LICENSE](LICENSE)。
