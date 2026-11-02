#!/usr/bin/env bash
# 建立 /tmp/faketermux 测试桩：termux-api 命令的最小可运行替身。
#
# 注意：桩的二进制名 / 参数必须与真机 termux-api 完全一致，否则 phone.py 会
# 因 `shutil.which` 找不到命令而走「未安装」分支，测试永远假绿、掩盖真机 bug。
# 这里的命名以 wiki.termux.com 各命令页为准。
# 用法：bash tests/setup_faketermux.sh
set -euo pipefail

DIR="${FAKETERMUX_DIR:-/tmp/faketermux}"
rm -rf "$DIR"
mkdir -p "$DIR"

# Windows 的 shutil.which 按 PATHEXT 找可执行文件，裸文件名的 bash 脚本它认不出来
# （服务端在那条路径上判定 "termux-api 未安装"，m5 的电池用例就被静默跳过）。
# 所以非 POSIX 平台再补一个 .cmd 垫片，内容同样是打印固定的 JSON。
IS_WINDOWS=0
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT) IS_WINDOWS=1 ;;
esac

make_stub() {
  local name="$1" output="$2"
  cat > "$DIR/$name" <<EOF
#!/usr/bin/env bash
# auto-generated stub for $name
cat <<'STUB_EOF'
$output
STUB_EOF
EOF
  chmod +x "$DIR/$name"
  if [ "$IS_WINDOWS" = 1 ]; then
    # .cmd 垫片必须用 echo 输出固定 JSON。原实现把 JSON 裸写在 `@echo off` 之后，
    # cmd.exe 会把它当成命令去执行（`{"status":` 不是合法命令），于是 stderr 是
    # GBK 编码的中文报错 —— 按 UTF-8 解码就变成 `����`，工具被判失败、界面报乱码。
    # 用 @echo 前缀逐行输出，并声明 chcp 65001 让输出走 UTF-8。
    {
      printf '@echo off\r\n'
      printf 'chcp 65001 >nul\r\n'
      while IFS= read -r line; do
        # 转义 cmd 的特殊字符（% 需写成 %%，^ & | < > 需加 ^）
        escaped=${line//%/%%}
        escaped=${escaped//^/^^}
        escaped=${escaped//&/^&}
        escaped=${escaped//|/^|}
        escaped=${escaped//</^<}
        escaped=${escaped//>/^>}
        printf 'echo %s\r\n' "$escaped"
      done <<< "$output"
    } > "$DIR/$name.cmd"
  fi
}

# 系统
# termux-battery-status 真实输出是单个 JSON 对象
make_stub termux-battery-status '{"health": "GOOD", "percentage": 85, "plugged": "UNPLUGGED", "status": "DISCHARGING", "temperature": 32.5, "voltage": 4099}'
make_stub termux-notification '{"status": "ok"}'
make_stub termux-notification-remove '{"status": "ok"}'
make_stub termux-vibrate '{"status": "ok"}'
make_stub termux-torch '{"status": "ok"}'
# termux-volume 无参返回所有流的 JSON 数组
make_stub termux-volume '[{"stream": "call", "volume": 3, "max_volume": 8}, {"stream": "music", "volume": 7, "max_volume": 15}]'
make_stub termux-brightness '{"status": "ok"}'
make_stub termux-toast '{"status": "ok"}'

# 通信
make_stub termux-sms-send '{"status": "ok"}'
# termux-sms-list 输出数组；发件人字段为 number，时间字段为 date（epoch ms）
make_stub termux-sms-list '[{"_id": 1, "type": 1, "number": "10086", "body": "【测试】您的验证码是 123456", "date": 1700000000000, "read": 0}]'
make_stub termux-telephony-call '{"status": "ok"}'

# 隐私
make_stub termux-location '{"provider": "network", "latitude": 23.0, "longitude": 113.0, "altitude": 10.0, "accuracy": 20.0}'
make_stub termux-clipboard-get '测试剪贴板内容'
make_stub termux-clipboard-set '{"status": "ok"}'

# 传感：termux-sensor -l 输出纯文本列表；-s <name> -n 1 输出 JSON 数组
cat > "$DIR/termux-sensor" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  -l)
    # 列出传感器：每行一个 "name: vendor"
    echo "accelerometer: Invensense"
    echo "gyroscope: Invensense"
    echo "magnetometer: AKM"
    echo "proximity: Sharp"
    echo "light: Liteon"
    ;;
  -s)
    # 读数：返回 JSON 数组，首元素含 name/values
    echo '[{"name": "accelerometer", "values": [0.01, 0.02, 9.81]}]'
    ;;
  *)
    echo "unknown sensor args: $*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$DIR/termux-sensor"

# 语音
make_stub termux-tts-speak '{"status": "ok"}'
make_stub termux-speech-to-text '这是一段离线语音识别结果'

# 网络（真实二进制名：connectioninfo / scaninfo，不是 wifi-info / wifi-scan）
make_stub termux-wifi-connectioninfo '{"bssid": "00:11:22:33:44:55", "frequency": 2412, "ip": "192.168.1.100", "link_speed": 72, "network_id": 0, "rssi": -55, "ssid": "TestWiFi", "supplicant_state": "COMPLETED"}'
make_stub termux-wifi-scaninfo '[{"bssid": "00:11:22:33:44:55", "frequency": 2412, "level": -55, "ssid": "TestWiFi", "timestamp": 1700000000}]'
make_stub termux-wifi-enable '{"status": "ok"}'

# 媒体 / 文件
make_stub termux-camera-photo '{"status": "ok"}'
make_stub termux-share '{"status": "ok"}'
make_stub termux-download '{"status": "ok"}'
make_stub termux-open '{"status": "ok"}'

COUNT=$(find "$DIR" -type f ! -name "*.cmd" | wc -l | tr -d " ")
echo "faketermux stubs created at $DIR ($COUNT commands)"
echo "Add to PATH: export PATH=\"$DIR:\$PATH\""
