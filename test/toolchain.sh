#!/usr/bin/env bash
# 桌面工具链定位（R-12）：run_core.sh 与 jc.sh 共用这一份，不再各写一套盘符路径。
#
#   用法： . "$(dirname "$0")/toolchain.sh"
#
# 只定义变量与函数，不执行编译、不改变 cwd。source 之后可用：
#   $JH     JDK 的 bin 目录        $BT     build-tools 目录（含 apksigner.jar）
#   $ANDJ   android.jar            $APKSIG $BT/lib/apksigner.jar
#   $EXE    javac/java 的后缀（Windows 为 ".exe"）  $SEP  classpath 分隔符
#   $WIN    1 = Windows/MSYS
#   winpath <path>   仅在 Windows 分支调用 cygpath -w
#
# 覆盖方式（三者都可单独指定，不指定则探测）：
#   JH=/path/to/jdk/bin  BT=/path/to/build-tools/37.0.0  ANDJ=/path/to/android.jar
#
# 为什么不写死盘符：v1.96.0 之前的 run_core.sh 把 D:/ 与 E:/ 写死在脚本里，等于把测试
# 锁在开发机上；jc.sh 到 v2.4.0 还是这样，在 Linux/CI 上 PLATFORM 直接为空。
set -u

first_existing() {
  for c in "$@"; do
    [ -n "$c" ] && [ -e "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

WIN=0
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*) WIN=1; SEP=';';;
  *) SEP=':';;
esac

if [ -z "${JH:-}" ]; then
  JH=$(first_existing "D:/Program Files/Java/jdk-21.0.3+9/bin" \
        "${JAVA_HOME:+$JAVA_HOME/bin}" \
        "$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/none)" 2>/dev/null)") \
    || { echo "找不到 JDK：用 JH=/path/to/jdk/bin 指定" >&2; exit 2; }
fi
EXE=""
[ -f "$JH/javac.exe" ] && EXE=".exe"

if [ -z "${BT:-}" ]; then
  BT=$(first_existing "E:/repo/android/Sdk/build-tools/37.0.0" \
        "${ANDROID_HOME:+$ANDROID_HOME/build-tools/37.0.0}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/build-tools/37.0.0}" \
        "$HOME/android-sdk/build-tools/37.0.0" \
        "$HOME/Android/Sdk/build-tools/37.0.0") \
    || { echo "找不到 build-tools（需要 37.0.0）：用 BT=/path/to/build-tools/37.0.0 指定" >&2; exit 2; }
fi
if [ -z "${ANDJ:-}" ]; then
  ANDJ=$(first_existing "${ANDROID_HOME:+$ANDROID_HOME/platforms/android-35/android.jar}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platforms/android-35/android.jar}" \
        "E:/repo/android/Sdk/platforms/android-35/android.jar" \
        "$HOME/android-sdk/platforms/android-35/android.jar" \
        "$HOME/Android/Sdk/platforms/android-35/android.jar") \
    || { echo "找不到 android.jar：用 ANDJ=/path/to/android.jar 指定" >&2; exit 2; }
fi

APKSIG="$BT/lib/apksigner.jar"

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
