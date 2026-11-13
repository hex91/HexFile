#!/usr/bin/env bash
# 只重新生成 build/gen/com/hex91/file/R.java（约 10-20 秒）
#
# 用途：改了 res/values/strings.xml 之后，jc.sh 会因为 R.string.xxx 不存在而失败 ——
# 完整 build.sh 要 5 分钟，只为补一个资源 id 不值当。这个脚本跑 aapt2 的
# compile + link 两步（与 build.sh 的 [1/7] [2/7] 完全一致），产出 R.java 与 base.apk。
#
#   ./test/jres.sh && ./test/jc.sh
set -u
cd "$(dirname "$0")/.."
. test/toolchain.sh

[ -d res ] || { echo "没有 res 目录" >&2; exit 2; }
mkdir -p build/gen

"$BT/aapt2$EXE" compile --dir res -o build/res.zip || { echo "aapt2 compile 失败" >&2; exit 2; }
"$BT/aapt2$EXE" link -o build/base.apk \
    -I "$(winpath "$ANDJ")" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 21 --target-sdk-version 30 \
    build/res.zip || { echo "aapt2 link 失败" >&2; exit 2; }
echo "R.java 已更新：build/gen/com/hex91/file/R.java"
