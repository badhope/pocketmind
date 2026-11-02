# 快速开始

[English](QUICKSTART.md) · [简体中文](QUICKSTART.zh-CN.md)

桌面五分钟跑起来，手机约二十分钟。

## 在桌面试跑（不需要手机，不需要 API Key）

```bash
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
python3 -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r agentd/requirements.txt

AGENT_HOME=/tmp/pa python3 -m agentd.main --mock --port 8787
```

打开 <http://127.0.0.1:8787>，发一句「查看电池电量」。你会看到完整链路：
流式推理 → 工具调用 → 审批卡 → 结构化结果。

`--mock` 不需要 API Key。上面的演示录像就是用它录的。

## 装到手机上

需要 **F-Droid 版 Termux**（不是 Play 商店那个）以及 **Termux:API**。

```bash
pkg install git python termux-api
git clone https://gitcode.com/badhope/pocketmind.git && cd pocketmind
bash termux/install.sh      # 真机 10-30 分钟：pydantic-core 要编译一次
bash termux/start.sh        # http://127.0.0.1:8787
```

然后在手机浏览器打开该地址：

1. **⚙ 设置** → 选一个提供商（DeepSeek / 豆包 / 通义千问 / Kimi / 智谱 / 硅基流动，
   或「自定义」接任意 OpenAI 兼容端点）。
2. 填 API Key → **保存**。
3. 发一条消息。

可选：`bash termux/boot.sh --install` 让它在开机时自启。

### 从电脑访问

```bash
bash termux/start.sh --lan
```

首次 `--lan` 启动会生成访问令牌并打印出来，网页首次加载会向你要。**令牌就是持有者凭证**
——不要在不可信网络开启局域网模式。见 [SECURITY.md](SECURITY.md)。

## 装 APK

```bash
bash scripts/build-apk.sh            # debug APK，已校验，可直接 adb install
bash scripts/build-apk.sh --release  # 没有 keystore.properties 时是未签名件
```

APK 是 WebView 壳：提供应用图标、前台服务与原生剪贴板桥。Agent 本体仍跑在 Termux 里，
壳不打包 Python。

## 配置

| 项目 | 位置 |
|---|---|
| 提供商 / 模型 / API Key | 设置面板，或 `$AGENT_HOME/config.json` 的 `llm` 块 |
| 权限模式 | 设置 → Agent 模式：`approve`（默认）、`auto`、`chat` |
| 审批超时 | `server.approval_timeout`，默认 120 秒 |
| 访问令牌 | 首次 `--lan` 生成；`python -m agentd.main --clear-token` 重置 |
| 数据目录 | `AGENT_HOME`，默认 `~/.agent/termux-agent` |

## 先试这些

| 说这句 | 会发生什么 |
|---|---|
| `查看电池电量` | `safe` 工具，立即执行 |
| `读一下最近 3 条短信` | `write` 工具 —— 先给你审批卡 |
| `发一条通知，内容是你好` | `write` 工具，审批后通知出现 |
| `2+2 等于几` | 不调工具，直接回答 |
| `执行 ls -la` | `danger` 工具，审批后 shell 输出以结构化卡片返回 |

如果某个工具需要设备不具备的能力（例如桌面上发短信），你会得到一条说明缺什么的
结构化错误，而不是静默失败。

## 验证安装

```bash
bash tests/run_all.sh --backend   # 后端套件
bash tests/run_all.sh             # 再加前端 typecheck / 单测 / 构建 / dist 新鲜度
```

退出码 `0` 表示全过。详见 [smoke/README.md](smoke/README.md)。

## 接下来看什么

- [README.zh-CN.md](README.zh-CN.md) —— 它是什么、能做什么
- [docs/API.md](docs/API.md) —— 每个端点
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) —— 怎么搭起来的
- [docs/capability-matrix.md](docs/capability-matrix.md) —— 哪些工具在哪种设备上可用
- [ROADMAP.md](ROADMAP.md) —— 下一步做什么、明确不做什么
- [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md) —— 怎么改
