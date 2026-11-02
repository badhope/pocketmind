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

if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ ! -f local.properties ]; then
  echo "未设置 ANDROID_HOME/ANDROID_SDK_ROOT，且没有 local.properties。" >&2
  exit 2
fi

# wrapper 优先（版本锁定），缺失时回退系统 gradle
if [ -f ./gradlew ]; then
  GRADLE=(bash ./gradlew)
else
  GRADLE=(gradle)
  echo "警告：gradlew 缺失（正常仓库里应有 wrapper jar），回退系统 gradle"
fi

[ "$#" -le 1 ] || { echo "参数过多" >&2; exit 2; }
case "${1:-}" in
  --lint)
    "${GRADLE[@]}" lintDebug --no-daemon
    exit 0
    ;;
  --release)
    task=assembleRelease
    variant=release
    ;;
  "")
    task=assembleDebug
    variant=debug
    ;;
  *) echo "未知参数：$1" >&2; exit 2 ;;
esac

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  need jshell "安装包含 jshell 的 JDK 17+"
  sdk=$(printf '%s\n' 'var p = new java.util.Properties(); try (var in = new java.io.FileInputStream("local.properties")) { p.load(in); }' 'System.out.print(p.getProperty("sdk.dir", ""));' '/exit' | jshell --feedback silent)
  [ -n "$sdk" ] || { echo "local.properties 缺少有效的 sdk.dir" >&2; exit 2; }
fi
BT="$sdk/build-tools/35.0.0"
tool_path() {
  local candidate
  for candidate in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do
    if [ -x "$candidate" ] || { [[ "$candidate" == *.bat ]] && [ -f "$candidate" ]; }; then
      printf '%s\n' "$candidate"; return 0
    fi
  done
  echo "缺少 Android 构建工具：$BT/$1" >&2
  return 1
}
apksigner=$(tool_path apksigner)
zipalign=$(tool_path zipalign)
aapt=$(tool_path aapt)

if [ "$variant" = debug ]; then
  APK=app/build/outputs/apk/debug/app-debug.apk
  rm -f "$APK"
else
  unsigned=app/build/outputs/apk/release/pocketmind-shell-release-unsigned.apk
  signed=app/build/outputs/apk/release/app-release.apk
  rm -f "$unsigned" "$signed"
fi
"${GRADLE[@]}" "$task" --no-daemon

if [ "$variant" = release ]; then
  if [ -f "$unsigned" ] && [ ! -e "$signed" ]; then
    APK="$unsigned"
  elif [ -f "$signed" ] && [ ! -e "$unsigned" ]; then
    APK="$signed"
  else
    echo "release APK 缺失或存在多个候选产物" >&2
    exit 1
  fi
fi
[ -s "$APK" ] || { echo "APK 缺失或为空：$APK" >&2; exit 1; }
version_name=$(grep '^POCKET_VERSION_NAME=' gradle.properties | cut -d= -f2-)
version_code=$(grep '^POCKET_VERSION_CODE=' gradle.properties | cut -d= -f2-)
[ -n "$version_name" ] && [ -n "$version_code" ] || { echo "缺少 APK 版本配置" >&2; exit 1; }
badging=$("$aapt" dump badging "$APK")
[[ "$badging" == *"versionName='$version_name'"* && "$badging" == *"versionCode='$version_code'"* ]] || { echo "APK 版本不匹配" >&2; exit 1; }

if [ "$variant" = release ] && [ "$APK" = "$unsigned" ]; then
  echo "未签名 release 中间制品（不可作为正式发布或直接安装）：$APK"
else
  "$apksigner" verify --print-certs "$APK"
  "$zipalign" -c 4 "$APK"
  echo "已校验 APK：$APK"
  echo "安装到手机：adb install -r $APK"
fi
