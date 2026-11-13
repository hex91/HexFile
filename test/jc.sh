#!/usr/bin/env bash
# 快速编译校验（只编 com.hex91.file，vendored 依赖复用 build/obj 里的 class）
#   ./test/jc.sh        出错时打印前 40 行并返回非 0
# 用途：改 import / 搬包这类机械改动，跑一次十几秒，不必等 3-9 分钟的完整出包。
#
# 注意：classpath 里的路径必须过 cygpath -w。MSYS 会把含 ';' 的参数当成路径列表
# 再转换一遍，'E:/x' 变成 '/e/x' 之后 javac 就找不到 jar 了 —— 表现为
# 「package com.android.apksig does not exist」，很容易误判成依赖缺失。
set -u
cd "$(dirname "$0")/.."
JH="D:/Program Files/Java/jdk-21.0.3+9/bin"
BT="/e/repo/android/Sdk/build-tools/37.0.0"
PLATFORM=$(cygpath -w /e/repo/android/Sdk/platforms/android-35/android.jar)
CP="$(cygpath -w build/obj);$(cygpath -w "$BT/lib/apksigner.jar")"
for j in libs/*.jar; do CP="$CP;$(cygpath -w "$j")"; done
SRCS=$(find src/com/hex91/file -name '*.java' | sort)
# 与 build.sh 一致：编译前清掉自研包的旧 class。搬包之后 build/obj 里还留着
# 旧位置的 SyncRunner.class，javac 会从 classpath 里看到它，报「reference is ambiguous」
# —— 这种错误看起来像源码问题，其实是编译缓存没清。
find build/obj/com/hex91/file -name '*.class' -delete 2>/dev/null
out=$("$JH/javac" -J-Dfile.encoding=UTF-8 -J-Duser.language=en -J-Duser.country=US \
    -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$PLATFORM" -classpath "$CP" -d build/obj \
    build/gen/com/hex91/file/R.java $SRCS 2>&1)
rc=$?
echo "$out" | grep -v '^注\|^警告\|^Note\|^warning' | head -40
exit $rc
