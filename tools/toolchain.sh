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
#   $SDK    Android SDK 根（由 $BT 反推；ANDJ 与 NDK 都优先在它下面找）
#   $NDK    NDK 根（ANDROID_NDK_HOME → $SDK/ndk/<版本最大且含 source.properties>；
#           探测不到时为空串 —— 只有真要重编 libtermux.so 时才必需，见 build.sh）
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
# 环境变量：JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_NDK_HOME 优先于下面的
# 常见位置；ANDROID_HOME / ANDROID_SDK_ROOT 一旦给出就必须是真目录、且里面真有可用的
# build-tools 与 platforms，否则 exit 2（R-32 / R-36：静默回退到机器上的另一个 SDK、
# 或者 aapt2 与 android.jar 来自两个不同的 SDK，比直接报错难查得多）。
#
# 为什么不写死盘符：v1.96.0 之前的 run_core.sh 把 D:/ 与 E:/ 写死在脚本里，等于把测试
# 锁在开发机上；build.sh 到 v2.5.0 仍然固定 E:/repo/android/Sdk、zulu17.38.21 与
# C:/Users/<某人>/.workbuddy/.../python.exe —— 新机器按 README 直接 bash build.sh，
# 会在 aapt2 或 javac 处报 No such file or directory，看起来像源码或依赖坏了。
# v2.9.0 起 JDK 那一行也不再钉版本（原来钉死 jdk-21.0.3+9，等于另一台装了 17 的机器
# 明明能用却报「找不到 JDK」）：只认 JAVA_HOME → PATH 上的 javac。

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

# v2.9.0（R-35）：build-tools / platform 不再钉版本。
# 原来五个候选一律写成 .../build-tools/37.0.0 与 .../platforms/android-35/android.jar，
# 于是 R-32 只治了「目录不存在」这一种：目录存在、里面只有 build-tools/36.0.0 时，
# first_existing 直接跳过它、继续落到机器上另一个 SDK —— 显式给的 ANDROID_HOME 被静默
# 忽略，与 R-32 要治的是同一个病；兜底路径也没有的机器上则会报「找不到 build-tools：
# 需要 37.0.0」，把「版本不匹配」说成「找不到」。
# 现在按「版本号最大、且真含 aapt2 / android.jar 的那个」挑，仍然环境变量优先。
# 版本比较不用 sort -V（busybox 的 sort 没有）：把名字里的数字抠出来当整数比 ——
# 37.0.0 → 3700，android-35 → 35，够用且不依赖外部排序。
pick_dir() {   # pick_dir <父目录> <标记文件...> → 打印版本号最大的合格子目录
  local parent="$1"; shift
  local d v m hit best="" pick="" i
  local names=() dirs=()
  [ -d "$parent" ] || return 1
  for d in "$parent"/*; do
    [ -d "$d" ] || continue
    hit=0
    for m in "$@"; do
      if [ -f "$d/$m" ]; then hit=1; break; fi
    done
    [ "$hit" = "1" ] || continue
    names+=("$(basename "$d")")
    dirs+=("$d")
  done
  [ "${#names[@]}" -gt 0 ] || return 1
  # 版本排序优先 sort -V（GNU coreutils / macOS 都有；busybox 的 sort 不认这个选项，
  # 会报错到 /dev/null、输出为空，于是自动落到退化分支）。
  # 退化分支只比版本号主段：35.0.0-rc1 抠出所有数字是 35001，反而会压过 37.0.0。
  pick="$(printf '%s\n' "${names[@]}" | sort -rV 2>/dev/null | head -1)"
  if [ -z "$pick" ]; then
    local bestn=-1 n
    for v in "${names[@]}"; do
      n="$(printf '%s' "${v#android-}" | sed 's/\..*//; s/[^0-9]//g')"
      [ -n "$n" ] || n=0
      if [ "$n" -gt "$bestn" ] 2>/dev/null; then bestn="$n"; pick="$v"; fi
    done
  fi
  [ -n "$pick" ] || return 1
  for i in "${!names[@]}"; do
    if [ "${names[$i]}" = "$pick" ]; then printf '%s' "${dirs[$i]}"; return 0; fi
  done
  return 1
}
pick_bt()   { pick_dir "$1/build-tools" aapt2 aapt2.exe; }
pick_andj() { pick_dir "$1/platforms" android.jar; }

# v2.9.0（R-32）：显式 ANDROID_HOME / ANDROID_SDK_ROOT 指向不存在的目录时直接报错。
# 之前 first_existing 只是「跳过无效候选」，于是
#   ANDROID_HOME=/nonexistent/sdk bash build.sh
# 会静默落到机器上的另一个 SDK —— 构建"成功"，但用的不是你以为的那个；换台机器又变成
# 「找不到 android.jar」。与 R-23 刚建立的「显式覆盖必须校验」口径不一致。
# 只校验将被采用的那个变量（ANDROID_HOME 优先），两者互相矛盾时不啰嗦。
if [ -n "${ANDROID_HOME:-}" ]; then
  [ -d "$(norm "$ANDROID_HOME")" ] || {
    echo "ANDROID_HOME 指向的目录不存在: $ANDROID_HOME" >&2
    echo "  要指向 SDK 根目录（含 build-tools/ 与 platforms/），不是 build-tools 子目录" >&2
    exit 2
  }
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  [ -d "$(norm "$ANDROID_SDK_ROOT")" ] || {
    echo "ANDROID_SDK_ROOT 指向的目录不存在: $ANDROID_SDK_ROOT" >&2
    exit 2
  }
fi

# v2.11.0（R-36）：「目录存在」只挡住了最粗的一种错。
#   ANDROID_HOME=/tmp/empty bash build.sh      # 空目录
# 以前会一路落到机器上另一个 SDK，构建"成功"但用的不是你指的那个；缺 platforms 时则是
# 一边用 A 的 aapt2、一边用 B 的 android.jar（跨 SDK 混搭，见下面 ANDJ 那段的注释）。
# 现在显式给了 SDK 根，就要求它自己够用：缺 build-tools 或 platforms 直接 exit 2。
# 例外：BT= / ANDJ= 显式指定视为「我知道自己在干什么」（有人就拿 ANDROID_HOME 指一个
# 只放 build-tools 的目录来试某个 aapt2），这两种情况下不校验对应那一半。
SDK_VAR=""; SDK_EXPLICIT=""
if [ -n "${ANDROID_HOME:-}" ]; then
  SDK_VAR="ANDROID_HOME"; SDK_EXPLICIT="$(norm "$ANDROID_HOME")"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK_VAR="ANDROID_SDK_ROOT"; SDK_EXPLICIT="$(norm "$ANDROID_SDK_ROOT")"
fi
if [ -n "$SDK_EXPLICIT" ] && [ -z "${BT:-}" ] && ! pick_bt "$SDK_EXPLICIT" >/dev/null; then
  echo "$SDK_VAR 下没有可用的 build-tools: $SDK_EXPLICIT" >&2
  echo "  要么指向完整 SDK（含 build-tools/ 与 platforms/），要么用 BT=/path/to/build-tools/<版本> 指定" >&2
  exit 2
fi
if [ -n "$SDK_EXPLICIT" ] && [ -z "${ANDJ:-}" ] && ! pick_andj "$SDK_EXPLICIT" >/dev/null; then
  echo "$SDK_VAR 下没有 platforms/android-<版本>/android.jar" >&2
  echo "  要么指向完整 SDK，要么用 ANDJ=/path/to/android.jar 显式指定" >&2
  exit 2
fi

if [ -z "${JH:-}" ]; then
  JH=$(first_existing "${JAVA_HOME:+$(norm "$JAVA_HOME")/bin}" \
        "$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/none)" 2>/dev/null)") \
    || { echo "找不到 JDK：用 JH=/path/to/jdk/bin 指定（或设 JAVA_HOME / 把 javac 放进 PATH）" >&2; exit 2; }
fi
EXE=""
[ -f "$JH/javac.exe" ] && EXE=".exe"

if [ -z "${BT:-}" ]; then
  BT=""
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
              "E:/repo/android/Sdk" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
    [ -n "$root" ] || continue
    if BT="$(pick_bt "$(norm "$root")")"; then break; fi
    BT=""
  done
  if [ -z "$BT" ]; then
    echo "找不到 build-tools（要有 build-tools/<版本>/aapt2）：用 BT=/path/to/build-tools/<版本> 指定" >&2
    if [ -n "${ANDROID_HOME:-}" ]; then
      echo "  已设 ANDROID_HOME=$ANDROID_HOME，但其中没有可用的 build-tools" >&2
    fi
    exit 2
  fi
fi
# SDK 根由 build-tools 反推（.../build-tools/<版本> → ...）：BT 与 ANDJ 必须来自同一个
# SDK（R-36），所以先算出来，ANDJ 优先在它下面找。
SDK="$(cd "$BT/../.." 2>/dev/null && pwd)" || SDK=""

if [ -z "${ANDJ:-}" ]; then
  ANDJ=""
  # 候选顺序：与 BT 同一个 SDK → 其余候选。root_norm 用 cd+pwd 归一（MSYS 下
  # C:/Users/x 与 /c/Users/x 是同一个目录，不归一后面比较会误判成跨 SDK）。
  _roots=(); _found=""
  _root_norm() {
    local r; r="$(cd "$(norm "$1")" 2>/dev/null && pwd)" || return 1
    printf '%s' "$r"
  }
  if [ -n "$SDK" ]; then _r="$(_root_norm "$SDK" 2>/dev/null)" && _roots+=("$_r"); fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
              "E:/repo/android/Sdk" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
    [ -n "$root" ] || continue
    _r="$(_root_norm "$root" 2>/dev/null)" || continue
    _dup=0
    for _e in ${_roots[@]+"${_roots[@]}"}; do [ "$_e" = "$_r" ] && _dup=1; done
    [ "$_dup" = "1" ] || _roots+=("$_r")
  done
  for root in ${_roots[@]+"${_roots[@]}"}; do
    if _pj="$(pick_andj "$root")"; then ANDJ="$_pj/android.jar"; _found="$root"; break; fi
  done
  if [ -z "$ANDJ" ]; then
    echo "找不到 android.jar（要有 platforms/android-<版本>/android.jar）：用 ANDJ=/path/to/android.jar 指定" >&2
    if [ -n "$SDK" ]; then
      echo "  已按 build-tools 定位到 SDK=$SDK，但其中没有可用的 platforms" >&2
    fi
    exit 2
  fi
  # 跨 SDK 混搭（aapt2 来自 A、android.jar 来自 B）不拦：旧机器上确实有人这么装。但不能
  # 静默——aapt2 37 配 platform 34 打包出来的东西，出问题时没人会想到是这里。
  if [ -n "$SDK" ] && [ "$_found" != "$SDK" ]; then
    echo "[warn] build-tools 与 android.jar 来自不同 SDK" >&2
    echo "       BT   = $BT" >&2
    echo "       ANDJ = $ANDJ" >&2
    echo "       建议把两者放到同一个 SDK 下，或用 BT= / ANDJ= 显式指定" >&2
  fi
fi

APKSIG="$BT/lib/apksigner.jar"

# v2.11.0（R-36）：NDK 不再钉 27.0.12077973。
# 原来 build.sh 写死 $SDK/ndk/27.0.12077973，CI 又单独钉一遍；换台只装了 26.x 的机器，
# 报出来的是 clang 的 "No such file or directory"（rc=127），完全看不出是 NDK 版本对不上。
# 标记用 source.properties：ndk/ 下可能有 ndk-bundle 这类别名目录，按「版本号最大」排会
# 被别名干扰，按「有没有 source.properties」认更稳。
pick_ndk() { pick_dir "$1/ndk" source.properties; }

NDK=""
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  [ -d "$(norm "$ANDROID_NDK_HOME")" ] || {
    echo "ANDROID_NDK_HOME 指向的目录不存在: $ANDROID_NDK_HOME" >&2
    exit 2
  }
  NDK="$(norm "$ANDROID_NDK_HOME")"
elif [ -n "$SDK" ]; then
  NDK="$(pick_ndk "$SDK")" || NDK=""
fi
# NDK 只在「.so 比 termux.c 旧、真要重编」时才必需，所以这里允许为空，由 build.sh 判定。

# v2.6.0（R-23）：显式覆盖也必须「真能跑」才放行。
# 原来 JH=/nonexistent/jdk bash test/jc.sh 会一路走到 javac 那一行才炸，输出是
#   /nonexistent/jdk/javac: No such file or directory   （rc=127）
# 看起来像脚本写错了；而 README 承诺的是「环境缺失 → rc=2 + 一行可操作提示」。
# 校验放在探测之后、任何使用之前，失败一律 exit 2。
[ -x "$JH/javac$EXE" ] || { echo "JH 下没有 javac: $JH" >&2; exit 2; }
[ -f "$BT/aapt2$EXE" ] || { echo "BT 下没有 aapt2: $BT" >&2; exit 2; }
[ -f "$ANDJ" ]         || { echo "找不到 android.jar: $ANDJ" >&2; exit 2; }

# SDK 根已在上面算过（R-36 起提前到 ANDJ 探测之前，因为两者要对齐到同一个 SDK）

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

# v2.10.0（R-35）：在 Windows 上把手里的路径统一成「混合形式」（C:/Users/...）。
# 起因：ANDROID_HOME 探测出来的路径是 MSYS 形式（/c/Users/...），bash 自己能用，但
#   · javac -classpath 用 ';' 拼多段时 MSYS 不做参数转换 —— apksig 那段直接找不到，
#     报出来是一屏 "cannot find symbol: ApkSigner"，看着像源码坏了；
#   · d8 走的是 @args 文件，里面的路径根本不经过 shell，Java 把 /c/... 当成「当前盘根
#     下的相对路径」，同样一批找不到类。
# cygpath -m 同时满足 bash（无空格问题）与 Windows 可执行文件（含 Java），所以在这一处
# 统一转，而不是要求每个调用方各自 winpath（jc.sh 转、build.sh 忘了转，就是这个 bug）。
if [ "$WIN" = 1 ]; then
  BT="$(toolpath "$BT")"
  ANDJ="$(toolpath "$ANDJ")"
  if [ -n "$SDK" ]; then SDK="$(toolpath "$SDK")"; fi
  if [ -n "$NDK" ]; then NDK="$(toolpath "$NDK")"; fi
  APKSIG="$(toolpath "$BT/lib/apksigner.jar")"
fi
