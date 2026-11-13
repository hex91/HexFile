#!/usr/bin/env bash
# 依赖方向门（O-17）
#
# 目标方向（README v2.2.0 起）：
#     MainActivity → controller → ui / fs / apk / net → utils → core
#
# 规则：
#   core            纯 JDK：不 import android.*，不 import com.hex91.file.*
#   fs/apk/net      不 import ui / controller，也不 import 根包通配
#   utils           不 import ui / controller / apk / net，也不 import 根包通配
#   ui/controller   可用下面所有层
#
# 为什么只查 import 而不查调用：Java 里没有 import 就用不了简单名，
# 唯一能绕过的是全限定名（android.system.Os.lstat 这种）。历史上真正越层的
# 7 处全都是 import 引出来的（其中 LinkUtil 的 Os 调用是 core 引了 android），
# 所以这道门对着 import 查，成本最低、误报最少。
#
# 通配 import 为什么要单独禁：`import com.hex91.file.ui.*` 里可能只用了一个类，
# 也可能一个都没用（只在 javadoc 里提了一句）。通配会让「注释里提过一个类」
# 看起来像「依赖了整个包」—— 审计时误导，门脚本也没法按行判断。
set -u
cd "$(dirname "$0")/.."

fail=0
check() { # $1=目录 $2=禁止的正则 $3=说明
  hits=$(grep -rnE "$2" "src/com/hex91/file/$1" --include='*.java' 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "FAIL  $1 —— $3"
    echo "$hits" | sed 's/^/      /'
    fail=1
  fi
}

check core  '^import (android\.|com\.hex91\.file\.)'                      'core 必须是纯 JDK'
check fs    '^import com\.hex91\.file\.(\*|ui\.|controller\.)'             'fs 不能依赖 ui/controller'
check apk   '^import com\.hex91\.file\.(\*|ui\.|controller\.)'             'apk 不能依赖 ui/controller'
check net   '^import com\.hex91\.file\.(\*|ui\.|controller\.)'             'net 不能依赖 ui/controller'
check utils '^import com\.hex91\.file\.(\*|ui\.|controller\.|apk\.|net\.)' 'utils 只能依赖 core/fs'

# O-18：自有包一律精确 import（java.* 通配可以留）
wild=$(grep -rnE '^import com\.hex91\.file\..*\.\*;' src --include='*.java' 2>/dev/null || true)
if [ -n "$wild" ]; then
  echo "FAIL 自有包禁止通配 import（O-18）"
  echo "$wild" | sed 's/^/      /'
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "依赖方向检查通过（core → utils → fs/apk/net → ui/controller → MainActivity）"
fi
exit $fail
