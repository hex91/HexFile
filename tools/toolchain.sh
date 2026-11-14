#!/usr/bin/env bash
# 桌面工具链定位（R-12 / R-22）：build.sh 与 test/*.sh 共用这一份。
#
#   用法： . "$ROOT/tools/toolchain.sh"                 # build.sh
#          . "$(dirname "$0")/../tools/toolchain.sh"    # test/*.sh
#
# 只定义变量与函数，不执行编译、不改变 cwd。也**不设** set -e / set -u：build.sh 要 -e、
# 测试脚本要 -u，语义由调用方决定；本文件所有外部变量一律用 ${X:-} 读，缺了不会炸。
#
# source 之后可用：
#   $JH     JDK 的 bin 目录        $BT     build-tools 目录（含 apksigner.jar）
#   $ANDJ   android.jar            $APKSIG $BT/lib/apksigner.jar
#   $SDK    Android SDK 根（由 $BT 反推，给 NDK 用）
#   $EXE    javac/java 的后缀（Windows 为 ".exe"）  $SEP  classpath 分隔符
#   $WIN    1 = Windows/MSYS
#   $PY     python 解释器（探测不到时为空串，由调用方决定要不要报错）
#   winpath <path>   仅在 Windows 分支调用 cygpath -w（给 javac 的 classpath 用）
#   toolpath <path>  仅在 Windows 分支调用 cygpath -m（给原生 exe 的普通参数用）
#
# 覆盖方式（都可单独指定，指定值同样要通过校验，见 R-23）：
#   JH=/path/to/jdk/bin  BT=/path/to/build-tools/37.0.0  ANDJ=/path/to/android.jar
#   PYTHON_EXE=python
#
# 为什么不写死盘符：v1.96.0 之前的 run_core.sh 把 D:/ 与 E:/ 写死在脚本里，等于把测试
# 锁在开发机上；build.sh 到 v2.5.0 仍然固定 E:/repo/android/Sdk、zulu17.38.21 与
# C:/Users/<某人>/.workbuddy/.../python.exe —— 新机器按 README 直接 bash build.sh，
# 会在 aapt2 或 javac 处报 No such file or directory，看起来像源码或依赖坏了。

first_existing() {
  for c in "$@"; do
    [ -n "$c" ] && [ -e "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

# python 解释器探测：只 command -v 不够，WindowsApps 目录下的 python3 是 Store 存根，
# --version 都返回 49、一行输出都没有。用 -c 'pass' 真跑一次才算数。
first_python() {
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'pass' >/dev/null 2>&1; then
      printf '%s' "$c"; return 0
    fi
  done
  return 1
}

WIN=0
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) WIN=1; SEP=';';;
  *) SEP=':';;
esac

# 环境变量优先（R-22 第 4 点）：CI 注入的 JAVA_HOME / ANDROID_HOME 必须压倒常见位置，
# 否则 runner 上恰好存在同名目录时会被开发机习惯抢走。
# Windows 的 JAVA_HOME 可能带反斜杠（D:\Program Files\...），先归一成 / 再拼 /bin。
norm() { printf '%s' "$1" | tr '\\' '/'; }

if [ -z "${JH:-}" ]; then
  JH=$(first_existing "${JAVA_HOME:+$(norm "$JAVA_HOME")/bin}" \
        "D:/Program Files/Java/jdk-21.0.3+9/bin" \
        "$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/none)" 2>/dev/null)") \
    || { echo "找不到 JDK：用 JH=/path/to/jdk/bin 指定" >&2; exit 2; }
fi
EXE=""
[ -f "$JH/javac.exe" ] && EXE=".exe"

if [ -z "${BT:-}" ]; then
  BT=$(first_existing "${ANDROID_HOME:+$(norm "$ANDROID_HOME")/build-tools/37.0.0}" \
        "${ANDROID_SDK_ROOT:+$(norm "$ANDROID_SDK_ROOT")/build-tools/37.0.0}" \
        "E:/repo/android/Sdk/build-tools/37.0.0" \
        "$HOME/android-sdk/build-tools/37.0.0" \
        "$HOME/Android/Sdk/build-tools/37.0.0") \
    || { echo "找不到 build-tools（需要 37.0.0）：用 BT=/path/to/build-tools/37.0.0 指定" >&2; exit 2; }
fi
if [ -z "${ANDJ:-}" ]; then
  ANDJ=$(first_existing "${ANDROID_HOME:+$(norm "$ANDROID_HOME")/platforms/android-35/android.jar}" \
        "${ANDROID_SDK_ROOT:+$(norm "$ANDROID_SDK_ROOT")/platforms/android-35/android.jar}" \
        "E:/repo/android/Sdk/platforms/android-35/android.jar" \
        "$HOME/android-sdk/platforms/android-35/android.jar" \
        "$HOME/Android/Sdk/platforms/android-35/android.jar") \
    || { echo "找不到 android.jar：用 ANDJ=/path/to/android.jar 指定" >&2; exit 2; }
fi

APKSIG="$BT/lib/apksigner.jar"

# v2.6.0（R-23）：显式覆盖也必须「真能跑」才放行。
# 原来 JH=/nonexistent/jdk bash test/jc.sh 会一路走到 javac 那一行才炸，输出是
#   /nonexistent/jdk/javac: No such file or directory   （rc=127）
# 看起来像脚本写错了；而 README 承诺的是「环境缺失 → rc=2 + 一行可操作提示」。
# 校验放在探测之后、任何使用之前，失败一律 exit 2。
[ -x "$JH/javac$EXE" ] || { echo "JH 下没有 javac: $JH" >&2; exit 2; }
[ -f "$BT/aapt2$EXE" ] || { echo "BT 下没有 aapt2: $BT" >&2; exit 2; }
[ -f "$ANDJ" ]         || { echo "找不到 android.jar: $ANDJ" >&2; exit 2; }

# SDK 根由 build-tools 反推（.../build-tools/37.0.0 → ...），仅 build.sh 取 NDK 用
SDK="$(cd "$BT/../.." 2>/dev/null && pwd)" || SDK=""

# ---- Python（可选）----
# build.sh 用它往 zip 里追加 entry；纯文本门（catch_check.sh）必须能在没有 JDK/SDK 的
# 机器上跑，所以它自带一份探测，不 source 本文件。
PY=""
if [ -n "${PYTHON_EXE:-}" ]; then
  "$PYTHON_EXE" -c 'pass' >/dev/null 2>&1 \
    || { echo "PYTHON_EXE 不可用: $PYTHON_EXE" >&2; exit 2; }
  PY="$PYTHON_EXE"
elif [ -n "${PYTHON:-}" ] && "$PYTHON" -c 'pass' >/dev/null 2>&1; then
  PY="$PYTHON"
else
  PY="$(first_python)" || PY=""
fi

# Windows 上必须把路径转成原生形式再拼进 classpath：MSYS 会重写含分隔符的参数，
# 'E:/x' 变成 '/e/x' 之后 javac 报的是「package com.android.apksig does not exist」，
# 看起来像依赖缺失，其实是路径没转。没有 cygpath（如 WSL）就原样返回，让报错更直白。
winpath() {
  if [ "$WIN" = 1 ] && command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

# 同上，但产出正斜杠的盘符形式（E:/a/b）：给原生 exe 的普通参数用
# （NDK 的 clang、python 脚本的路径参数），避免 MSYS 二次转换。
toolpath() {
  if [ "$WIN" = 1 ] && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}
