# 口袋智灵 · APK 打包方案

本产品是「Termux 本地服务 + 浏览器 Web UI」。**PWA（添加到主屏幕）就是它的
"安装"形态，零构建、零体积开销**；如果希望有独立 App 图标、通知权限、前后台保活，
使用下面的 **增强版 WebView 壳 APK**（首次使用带一键部署引导）。

---

## 路径 A：PWA 安装（推荐，零构建，开箱即用）

前置：`bash termux/start.sh` 启动 agentd，手机浏览器打开 `http://127.0.0.1:8787`。

| 浏览器 | 操作 |
|---|---|
| Chrome | 菜单 →「添加到主屏幕」（或「安装应用」） |
| Edge | 菜单 →「添加到手机」→「安装」 |
| 其他 Chromium 内核 | 同样有「添加到主屏幕」 |

安装后：主屏出现「口袋智灵」图标，独立全屏窗口运行。Service Worker 缓存了页面壳，
断网重开也能秒开界面（工具调用时再连回本机服务）。

---

## 路径 B：增强版 WebView 壳 APK（独立图标 + 引导部署 + 保活）

`termux/apk-shell/` 是一个**零第三方依赖的原生 WebView 壳工程**（不引 Capacitor/Flutter），
在 WebView 客户端之上提供：

- **首次启动引导页**：检测到 agentd 未就绪时，展示「安装 Termux → 一键部署 → 检查连接」
  三步引导，用户无需手动敲命令；
- **一键部署**：通过 Termux 的 `RunCommandService` 自动执行
  `pkg install git/python/termux-api → git clone 仓库 → termux/install.sh → termux/start.sh`；
- **一键启动**：已部署过时，直接在 Termux 中执行 `termux/start.sh`；
- **前台服务保活 + 掉线检测**：`MonitorService` 持续探测 `127.0.0.1:8787/api/health`，
  agentd 掉线时自动切到错误/引导页，恢复后自动重连；
- **JS Bridge**：复制文本、系统通知、振动反馈、唤醒 Termux、打开 F-Droid 下载页；
- 本地自签名证书放行、非本机 SSL 拒绝、返回键先走页面历史。

### 构建步骤

**推荐：一条命令（本地与 CI 同一份逻辑）**

```bash
# 前置：JDK 17+、Android SDK（platforms;android-35 + build-tools;35.0.0）
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-apk.sh              # debug APK，可直接 adb install
bash scripts/build-apk.sh --release    # release；未配签名时产物明确命名为 *-unsigned.apk
bash scripts/build-apk.sh --lint       # 只跑 lint（0 error 才放行）
```

**或手动 / Android Studio**

1. 安装 **Android Studio**（自带 SDK + Gradle），或已有 Android SDK + JDK 17；
2. 用 Android Studio 打开 `termux/apk-shell/` 目录；
3. 首次构建前确认 `local.properties` 中的 `sdk.dir` 指向你的 Android SDK 路径；
4. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`；
5. 把 APK 装到手机（`adb install -r` 或直接传输安装）。

**发布签名（可选）**：在 `termux/apk-shell/keystore.properties` 写

```properties
storeFile=release.keystore
storePassword=***
keyAlias=pocketmind
keyPassword=***
```

放在同目录的 keystore 文件不入库（`.gitignore` 已排除 `*.keystore` / `*.jks`）。
配置后 `--release` 产出的就是已签名、可直接分发的 APK；版本号由 `gradle.properties`
的 `POCKET_VERSION_NAME/CODE` 提供，并由门禁 `tests/check_version.py` 保证与后端一致。

**版本一致性**：`agentd/main.py`、`agentd/web/package.json`、`termux/apk-shell/gradle.properties`
三处必须相同，`bash tests/run_all.sh` 会校验。

### 首次使用流程（真机）

1. 手机安装 **Termux（F-Droid 版）** 与 **Termux:API**：
   `pkg install termux-api`（或在引导页点击「一键部署」，脚本会自动安装）；
2. 打开口袋智灵 App，进入引导页：
   - 若未安装 Termux → 点击「打开 Termux 下载页」；
   - 点击「一键部署」，**第一次时 Termux 会弹出「允许运行命令」授权，请允许**；
   - 部署过程在 Termux 中自动进行（真机约 10-30 分钟，首次需编译 pydantic-core；模拟器实测约 50 分钟）；
3. 部署完成后回到 App，点击「检查连接」，就绪后自动进入聊天界面；
4. 在设置里选择 LLM 厂商并填入 API Key，开始使用。

### 注意事项

- **访问令牌是强制的**：Android 上 `127.0.0.1` 是全机共享命名空间，任何 App 都能连。
  `install.sh` 首次安装时生成随机令牌（`~/.agent/termux-agent/token`，权限 600），
  `start.sh` 自动注入；APK 壳通过 Termux 读出并交给 WebView，用户无需手抄。
  用浏览器/PWA 访问时需要手动粘贴一次（页面会直接给出输入框与 `cat` 命令）。
- 一键部署依赖 Termux 的 `RUN_COMMAND` 授权（Termux 设置 → 允许运行命令）；
- 若一键部署失败，可改用「复制启动命令」在 Termux 中手动执行排查；
- 数据全部保存在手机本地（`~/.agent/termux-agent`），卸载 App 不影响已部署的后端；
- 若已通过 PWA 使用过，直接安装 APK 也可连接同一个本地服务。

---

## 不推荐：PWA Builder / Bubblewrap 在线打包

这类工具生成的是 **Trusted Web Activity（TWA）**，要求站点有公网 HTTPS 域名。
我们的服务跑在手机本地 `127.0.0.1`，没有公网域名，TWA 不适用——这是架构决定的，
不是能力缺失。PWA 安装（路径 A）已覆盖同场景。

---

## 技术说明：为什么不把 Python 后端直接内嵌进 APK？

当前 FastAPI 依赖 `pydantic-core`（Rust/PyO3 编译产物），Chaquopy 等 Android Python
运行时暂无法可靠编译该扩展（见 pydantic-core#1607 / chaquopy#1017），因此本阶段
采用「APK 引导 + Termux 承载 Python 运行时」的形态。APK 负责安装引导、客户端与
保活，用户不需要接触命令行即可完成部署。