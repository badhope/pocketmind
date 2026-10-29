#!/usr/bin/env bash
# 一键构建 Android APK（本地与 CI 共用同一份逻辑）。
#
# 用法：
#   bash scripts/build-apk.sh              # debug APK（可直接 adb install）
#   bash scripts/build-apk.sh --release    # release APK（未配签名时为 unsigned）
#   bash scripts/build-apk.sh --lint       # 只跑 lint
#
# 前置：
#   - JDK 17+
#   - Android SDK（设置 ANDROID_HOME，或写 termux/apk-shell/local.properties 的 sdk.dir）
#
# 为什么需要这个脚本：仓库文档原来只说「用 Android Studio 打开」，
# 而干净克隆的 ./gradlew 因 wrapper jar 被 .gitignore 排除而不可用（审计 A2）。
# 这里把「装 SDK → 构建 → 校验产物」固化成一条命令，并给出可读的失败提示。
set -euo pipefail
cd "$(dirname "$0")/.."
SHELL_DIR="termux/apk-shell"
cd "$SHELL_DIR"

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少 $1：$2" >&2; exit 2; }; }
need java "安装 JDK 17+（Termux 外构建不需要 Android Studio）"

if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  if [ -f local.properties ] && grep -q "sdk.dir=" local.properties; then
    echo "使用 local.properties 里的 sdk.dir"
  else
    echo "未设置 ANDROID_HOME/ANDROID_SDK_ROOT，且没有 local.properties。" >&2
    echo "先安装 Android SDK（commandline-tools + platforms;android-35 + build-tools;35.0.0），" >&2
    echo "然后：echo 'sdk.dir=/path/to/android-sdk' > termux/apk-shell/local.properties" >&2
    exit 2
  fi
fi

# wrapper 优先（版本锁定），缺失时回退系统 gradle
if [ -x ./gradlew ]; then
  GRADLE="./gradlew"
else
  GRADLE="gradle"
  echo "警告：gradlew 缺失（正常仓库里应有 wrapper jar），回退系统 gradle"
fi

case "${1:-}" in
  --lint)
    "$GRADLE" lintDebug --no-daemon
    ;;
  --release)
    "$GRADLE" assembleRelease --no-daemon
    ;;
  *)
    "$GRADLE" assembleDebug --no-daemon
    ;;
esac

echo ""
echo "== 产物 =="
find app/build/outputs/apk -name "*.apk" -exec ls -lh {} \;

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ] && [ -n "${ANDROID_HOME:-}" ]; then
  BT="$ANDROID_HOME/build-tools/35.0.0"
  if [ -x "$BT/apksigner" ]; then
    echo ""
    echo "== 签名校验 =="
    "$BT/apksigner" verify --print-certs "$APK" | head -3
  fi
fi

echo ""
echo "安装到手机：adb install -r $APK"
echo "（APK 只是壳，手机端仍需先部署后端：Termux 里 bash termux/install.sh && bash termux/start.sh）"
