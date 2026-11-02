#!/usr/bin/env bash
# 在已启动的 Android 模拟器的 Termux 里执行一条命令。
#
# 为什么不直接 `adb shell`：Android 10+ 的 SELinux 禁止普通 shell 访问别的应用
# 私有目录（/data/data/com.termux 会 Permission denied）。必须走 `run-as`，
# 而它只对 debuggable 构建有效 —— 所以本脚本配套的是 Termux 官方 debug APK。
#
# 用法：
#   bash tools/android-vm/termux-run.sh 'python --version'
#   bash tools/android-vm/termux-run.sh --push agentd/requirements.txt
set -uo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
ADB="$SDK_ROOT/platform-tools/adb.exe"
PREFIX_IN_APP=/data/data/com.termux/files/usr
HOME_IN_APP=/data/data/com.termux/files/home

if [ ! -x "$ADB" ]; then
  echo "找不到 adb：$ADB（先运行 provision.sh）" >&2
  exit 2
fi
if ! "$ADB" devices 2>/dev/null | grep -q "emulator-.*device"; then
  echo "没有已连接的模拟器。先启动：emulator -avd termux_test -no-window" >&2
  exit 2
fi

# adb shell 传参会被宿主 sh 再解析一次：含空格/引号的命令必须整体转义。
# 这里把用户命令 base64 编码后传输，彻底绕开多层引号地狱（实测三层引号必炸）。
run_in_termux() {
  local cmd="$1"
  local b64
  b64=$(printf '%s' "$cmd" | base64 | tr -d '\n')
  MSYS_NO_PATHCONV=1 "$ADB" shell "run-as com.termux $PREFIX_IN_APP/bin/bash -c 'export PATH=$PREFIX_IN_APP/bin:\$PATH; export PREFIX=$PREFIX_IN_APP; export HOME=$HOME_IN_APP; echo $b64 | base64 -d | bash'"
}

case "${1:-}" in
  --push)
    src="${2:?用法: --push <本地文件>}"
    name=$(basename "$src")
    # Git Bash 会把以 / 开头的参数改写成 Windows 路径：必须关掉转换，
    # 否则 adb 收到的是 "C:/Program Files/Git/data/local/tmp/..."（实测踩到）。
    MSYS_NO_PATHCONV=1 "$ADB" push "$src" "/data/local/tmp/$name" >/dev/null || exit 1
    MSYS_NO_PATHCONV=1 "$ADB" shell "run-as com.termux cp /data/local/tmp/$name $HOME_IN_APP/$name" || exit 1
    echo "已推送: $HOME_IN_APP/$name"
    ;;
  ""|-h|--help)
    sed -n '2,14p' "$0"
    ;;
  *)
    run_in_termux "$*"
    ;;
esac
