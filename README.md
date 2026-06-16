# MobiAgent

> **Customizable mobile agent framework for Android / HarmonyOS** — plug-in vision-language models, record-and-replay acceleration, and a real-device evaluation benchmark.

[中文](./README_zh.md)

---

## What is MobiAgent

MobiAgent is a full-stack mobile agent system. You describe a task in natural language ("open Meituan and search for nearby hot pot"), and the agent drives an Android (or HarmonyOS) device through screenshots, taps, types, and swipes — looping until the task is done.

It is **customizable**: bring your own model. It is **modular**: the agent loop, the acceleration framework, and the benchmark are independent pieces you can use separately. It is **honest about its limits**: real-world mobile automation is hard, and MobiAgent's evaluation numbers are on real devices, not synthetic screenshots.

---

## What's in the box

### 1. Agent Model Family
A small set of vision-language models fine-tuned for mobile GUI tasks:
- **Planner** — turns a task into a high-level plan
- **Decider** — looks at the current screen, picks the next action
- **Grounder** — turns a natural-language element description into exact coordinates

Models come in 3B / 4B / 7B sizes. The 4B mixed version can play Decider + Grounder roles, so the whole agent can run on a single GPU.

### 2. Acceleration Framework — AgentRR (Record & Replay)
- Caches successful action sequences as experience trees
- Reuses them for similar tasks (2-3× faster on repeated patterns)
- A small latent memory model matches the current screen to past experiences in milliseconds
- Real-world reuse rates: 30-60% on random tasks, 60-85% on power-law (typical user) distributions
- Replay accuracy: >99%

### 3. Evaluation Benchmark — MobiFlow
- Milestone-DAG-based: each task has multiple acceptable trajectories
- Runs on real devices (not screenshots or simulators)
- Covers 20+ popular apps (Meituan, Taobao, Ctrip, Xiaohongshu, ...)
- Tolerates environment noise (pop-ups, network lag, version differences)

### 4. Android App
A standalone APK in [`app/`](app/) that bundles everything for end users. Cloud-hosted models with free tier, no local setup required.

### 5. Phone Runner (on-device inference)
Run the agent on the phone itself with a quantized 4B model — no server, no cloud, fully private. See [`phone_runner/README.md`](phone_runner/README.md).

---

## Quick Start

### A. Just use the App (easiest)

Build the APK from [`app/`](app/) and install on your Android device. Sign in, type a task, watch it work. Cloud models with a free tier.

### B. Drive it from Python (developer path)

#### 1. Environment

```bash
conda create -n mobiagent python=3.10
conda activate mobiagent
pip install -r requirements.txt
```

Full-pipeline extras (OmniParser weights, embedding model, OCR):
```bash
# OmniParser for icon detection
for f in icon_detect/{train_args.yaml,model.pt,model.yaml} ; do
  huggingface-cli download microsoft/OmniParser-v2.0 "$f" --local-dir weights
done

# Embedding model for memory
huggingface-cli download BAAI/bge-small-zh --local-dir ./utils/experience/BAAI/bge-small-zh

# OCR (optional, for non-English screens)
sudo apt install tesseract-ocr tesseract-ocr-chi-sim
```

#### 2. Phone

- Install [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk) on the device (for the `Type` action)
- Enable Developer Options → USB debugging
- Connect via USB

#### 3. Deploy the models with vLLM

```bash
# 4B mixed model — single GPU, plays Decider + Grounder
vllm serve <model-checkpoint> --port 8000 --served-model-name mobi-mind

# Or split into Planner / Decider / Grounder on separate ports
vllm serve <decider-or-mixed> --port 8000
vllm serve Qwen/Qwen3-4B-Instruct --port 8002
```

#### 4. Run

Write your task list to `runner/mobiagent/task.json`, then:

```bash
python -m runner.mobiagent.mobiagent \
  --service_ip localhost \
  --decider_port 8000 \
  --planner_port 8002
```

Common flags:
- `--user_profile on` — enable user preference memory (needs Milvus or Neo4j)
- `--use_experience` — retrieve similar past tasks to rewrite the plan
- `--e2e` — end-to-end mode, no separate Grounder call
- `--device Android` or `--device Harmony`
- `--data_dir <path>` — where to save execution traces

Multi-task (cross-app):
```bash
python -m runner.mobiagent.multi_task.mobiagent_refactored \
  --service_ip localhost \
  --decider_port 8000 \
  --planner_port 8002 \
  --task "在淘宝搜 iPhone 17,把前 3 个结果发到微信"
```

---

## Memory Subsystems (Optional)

Three layers, all optional, all independent:

| Layer | What it does | Backend |
|---|---|---|
| User profile | Long-term preferences, address, contacts | Milvus (vector) or Neo4j (graph) |
| Experience | Similar past task plans | Local files |
| Action (AgentRR) | Cached action sequences | Local files + embedding model |

Set them up via `.env`:
```bash
MILVUS_URL=http://localhost:19530
EMBEDDING_MODEL=BAAI/bge-small-zh
EMBEDDING_MODEL_DIMS=384
OPENAI_API_KEY=sk-...   # for the memory LLM, not the agent
OPENAI_BASE_URL=https://api.openai.com/v1
```

See [`runner/README.md`](runner/README.md) for the full config.

---

## Project Layout

```
agent_rr/        Record & Replay acceleration framework
collect/         Data collection, annotation, processing & export
runner/          Agent executor (connects to phone via ADB)
MobiFlow/        Real-device evaluation benchmark
app/             Android App source (APK build)
phone_runner/    On-device inference runner
deployment/      Server deployment for the App
prompts/         Prompt templates for Planner / Decider / Grounder
utils/           Shared utilities (parsing, tools, weights)
```

---

## Performance

Measured on MobiFlow (real devices, popular Chinese apps):

| Setup | Overall | Easy | Hard |
|---|---|---|---|
| This project (full stack) | **86.4%** | 94.2% | 78.9% |
| GPT-5 | 71.2% | 88.5% | 55.3% |
| Gemini 2.5 Pro | 73.8% | 91.0% | 58.1% |
| UI-TARS-1.5-7B | 68.7% | 85.4% | 52.6% |

On complex apps (Meituan, Taobao, Ctrip) the gap widens to 20-30 percentage points. AgentRR cuts end-to-end time by 2-3× on repeated tasks.

---

## Sub-module READMEs

- [`runner/README.md`](runner/README.md) — agent execution
- [`runner/mobiagent/multi_task/README.md`](runner/mobiagent/multi_task/README.md) — multi-app workflows
- [`agent_rr/README.md`](agent_rr/README.md) — Record & Replay internals
- [`MobiFlow/README.md`](MobiFlow/README.md) — benchmark
- [`phone_runner/README.md`](phone_runner/README.md) — on-device inference
- [`app/README.md`](app/README.md) — Android App

---

## Safety

- **Human must be present** during real device operation. Any payment / deletion / sending should require explicit confirmation.
- **Don't use for bypassing platform security** (captcha, rate limits, scraping, etc.).
- Models can be prompt-injected. Validate critical actions in code, not in agent output.

---

## License

Apache 2.0. See [LICENSE](LICENSE).
