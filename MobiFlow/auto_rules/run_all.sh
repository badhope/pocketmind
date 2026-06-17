#!/usr/bin/env bash
# run_all.sh  放在项目根目录里直接 ./run_all.sh 即可
# 兼容 bash/zsh(原版使用 zsh 专有语法 *(/) 和 ${var:t},已改为 POSIX 兼容写法)

set -euo pipefail

DATA_ROOT="../data"
RULE_ROOT="../task_rules"

# 可选:自动创建 task_rules 目录
# mkdir -p "$RULE_ROOT"

# 遍历 data 下的一级目录(仅目录,排除文件)
for app_dir in "$DATA_ROOT"/*/; do
    # 去除尾部斜杠后取基名(basename)
    app_dir="${app_dir%/}"
    app_name="$(basename "$app_dir")"
    mkdir -p "$RULE_ROOT/$app_name"

    # 遍历该目录下的 type* 子目录
    for type_dir in "$app_dir"/type*/; do
        [ -d "$type_dir" ] || continue
        type_dir="${type_dir%/}"
        type_name="$(basename "$type_dir")"
        out_file="$RULE_ROOT/$app_name/${app_name}-${type_name}.yaml"

        echo "→ Processing $type_dir  →  $out_file"
        python main.py "$type_dir" --output-file "$out_file"
    done
done

echo "All done!"
