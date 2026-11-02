#!/usr/bin/env bash
# 在 Windows 上用 Android 模拟器验证 Termux 环境（一键复现）。
#
# 用途：真机不可得时，用官方 AVD 验证「安装脚本能不能装、Python 能不能起来」。
# 不能验证：真实硬件能力（短信/电话/定位/传感器）、aarch64 编译行为、电源管理。
#
# 前提：Windows 10/11 + 已开启 Hyper-V 平台（WHPX）；约 8GB 磁盘；管理员权限（仅首次装 SDK）。
# 用法：
#   bash tools/android-vm/provision.sh          # 安装 SDK + AVD + Termux
#   bash tools/android-vm/provision.sh --check  # 只检查环境
set -uo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
AVD_NAME="${AVD_NAME:-termux_test}"
API_LEVEL="${API_LEVEL:-35}"
TERMUX_APK="${TERMUX_APK:-$HOME/Android/termux-full.apk}"
TERMUX_APK_URL="https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_x86_64.apk"
TERMUX_APK_SIZE=35189933  # 来自 GitHub Release API，用于校验完整性

ADB="$SDK_ROOT/platform-tools/adb.exe"
EMU="$SDK_ROOT/emulator/emulator.exe"
SDKMGR="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMGR="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager.bat"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; }

check_env() {
  say "环境检查"
  local fail=0
  if [ -x "$ADB" ]; then ok "adb: $ADB"; else bad "缺 adb（先运行不带 --check 的本脚本）"; fail=1; fi
  if [ -x "$EMU" ]; then ok "emulator: $EMU"; else bad "缺 emulator"; fail=1; fi
  local hv
  hv=$(powershell -NoProfile -Command "(Get-CimInstance Win32_ComputerSystem).HypervisorPresent" 2>/dev/null | tr -d '\r')
  if [ "$hv" = "True" ]; then ok "Hyper-V 平台已启用（WHPX 可用）"; else bad "Hyper-V 未启用：模拟器将极慢或无法启动"; fail=1; fi
  if [ -x "$ADB" ]; then
    local devs
    devs=$("$ADB" devices 2>/dev/null | grep -c "emulator-.*device")
    if [ "$devs" -gt 0 ]; then ok "已连接的模拟器: $devs"; else printf '  · 暂无运行中的模拟器\n'; fi
  fi
  return "$fail"
}

install_sdk() {
  say "安装 Android SDK 组件（约 5GB）"
  mkdir -p "$SDK_ROOT"
  if [ ! -x "$SDKMGR" ]; then
    echo "下载 commandline-tools..."
    curl -sL -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools
    mkdir -p "$SDK_ROOT/cmdline-tools"
    rm -rf "$SDK_ROOT/cmdline-tools/latest"
    mv /tmp/cmdtools/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
  fi
  yes | "$SDKMGR" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1
  "$SDKMGR" --sdk_root="$SDK_ROOT" "platform-tools" "emulator" \
    "system-images;android-${API_LEVEL};google_apis;x86_64" 2>&1 | tail -3
}

create_avd() {
  say "创建 AVD: $AVD_NAME"
  if "$EMU" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
    ok "已存在，跳过"
    return
  fi
  echo no | "$AVDMGR" create avd -n "$AVD_NAME" \
    -k "system-images;android-${API_LEVEL};google_apis;x86_64" -d pixel_6 --force 2>&1 | tail -2
}

start_emu() {
  say "启动模拟器（无头）"
  if "$ADB" devices 2>/dev/null | grep -q "emulator-.*device"; then
    ok "已有模拟器在运行"
    return
  fi
  ( cd "$SDK_ROOT" && nohup "$EMU" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot > /tmp/emulator.log 2>&1 & )
  "$ADB" wait-for-device
  for i in $(seq 1 60); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { ok "已启动（约 $((i*3))s）"; return; }
    sleep 3
  done
  bad "启动超时，见 /tmp/emulator.log"
  return 1
}

install_termux() {
  say "安装 Termux"
  if "$ADB" shell pm list packages 2>/dev/null | grep -q "package:com.termux"; then
    ok "已安装，跳过"
    return
  fi
  # 完整性校验：GitHub Release 直连常被截断，用断点续传补齐到官方 size
  if [ ! -f "$TERMUX_APK" ] || [ "$(stat -c%s "$TERMUX_APK" 2>/dev/null || echo 0)" -lt "$TERMUX_APK_SIZE" ]; then
    echo "下载 Termux APK（约 34MB，可能需要多次续传）..."
    for i in 1 2 3 4 5 6; do
      curl -sL -C - -o "$TERMUX_APK.part" "$TERMUX_APK_URL" 2>/dev/null
      local sz; sz=$(stat -c%s "$TERMUX_APK.part" 2>/dev/null || echo 0)
      [ "$sz" -ge "$TERMUX_APK_SIZE" ] && break
    done
    mv "$TERMUX_APK.part" "$TERMUX_APK"
  fi
  local got; got=$(stat -c%s "$TERMUX_APK" 2>/dev/null || echo 0)
  if [ "$got" -lt "$TERMUX_APK_SIZE" ]; then
    bad "APK 不完整（$got / $TERMUX_APK_SIZE），请重试或手动下载"
    return 1
  fi
  ok "APK 完整（$got 字节）"
  "$ADB" install -r "$TERMUX_APK" 2>&1 | tail -2
}

wait_bootstrap() {
  say "等待 Termux bootstrap（首启自动解压）"
  "$ADB" shell am start -n com.termux/.app.TermuxActivity >/dev/null 2>&1
  for i in $(seq 1 60); do
    local n
    n=$("$ADB" shell "run-as com.termux ls /data/data/com.termux/files/usr/bin 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r')
    [ "${n:-0}" -gt 300 ] 2>/dev/null && { ok "bootstrap 完成（$n 个命令）"; return 0; }
    sleep 5
  done
  bad "bootstrap 未完成（可能需要联网或手动打开 Termux）"
  return 1
}

main() {
  if [ "${1:-}" = "--check" ]; then check_env; exit $?; fi
  install_sdk || exit 1
  create_avd
  start_emu || exit 1
  install_termux || exit 1
  wait_bootstrap || exit 1
  say "完成"
  echo "  后续验证："
  echo "    bash tools/android-vm/termux-run.sh 'python --version'"
  echo "    bash tools/android-vm/termux-run.sh 'pip install -r ~/requirements.txt -i https://mirrors.aliyun.com/pypi/simple'"
}

main "$@"
