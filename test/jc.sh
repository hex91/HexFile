#!/usr/bin/env bash
# 快速编译校验（只编 com.hex91.file，vendored 依赖复用 build/obj 里的 class）
#   ./test/jc.sh        出错时打印前 40 行并返回非 0，约 30-40 秒
# 用途：改 import / 搬包这类机械改动，跑一次半分钟，不必等 3-9 分钟的完整出包。
#
# 前提：先完整跑过一次 ./build.sh。本脚本依赖两样只由 build.sh 产出的东西：
#   build/gen/com/hex91/file/R.java   （aapt2 生成，没有就是 cannot find symbol R）
#   build/obj 里的 vendored class     （android.jar 之外的依赖）
# 缺任何一样都会直接提示，而不是丢一堆看起来像源码错误的编译错误。
#
# 工具链定位复用 tools/toolchain.sh（R-12 / R-22）：不再写死 D:/ E:/ 盘符，Linux/macOS 上
# 不必有 cygpath。winpath 只在 Windows 分支生效。
set -u
cd "$(dirname "$0")/.."

. tools/toolchain.sh

if [ ! -f build/gen/com/hex91/file/R.java ]; then
  echo "缺少 build/gen/com/hex91/file/R.java：先跑 ./build.sh" >&2
  exit 2
fi
if [ -z "$(find build/obj -name '*.class' -print -quit 2>/dev/null)" ]; then
  echo "build/obj 为空：先跑 ./build.sh" >&2
  exit 2
fi

CP="$(winpath build/obj)$SEP$(winpath "$APKSIG")"
for j in libs/*.jar; do CP="$CP$SEP$(winpath "$j")"; done

# 与 build.sh 一致：编译前清掉自研包的旧 class。搬包之后 build/obj 里还留着
# 旧位置的 SyncRunner.class，javac 会从 classpath 里看到它，报「reference is ambiguous」
# —— 这种错误看起来像源码问题，其实是编译缓存没清。
find build/obj/com/hex91/file -name '*.class' -delete 2>/dev/null

SRCS=$(find src/com/hex91/file -name '*.java' | sort)
out=$("$JH/javac$EXE" -J-Dfile.encoding=UTF-8 -J-Duser.language=en -J-Duser.country=US \
    -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$(winpath "$ANDJ")" -classpath "$CP" -d build/obj \
    build/gen/com/hex91/file/R.java $SRCS 2>&1)
rc=$?
echo "$out" | grep -v '^注\|^警告\|^Note\|^warning' | head -40
exit $rc
