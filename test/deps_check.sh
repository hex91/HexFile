#!/usr/bin/env bash
# 依赖方向门（O-17 / R-18）
#
# 目标方向（README v2.2.0 起）：
#     MainActivity → controller → ui / fs / apk / net → utils → core
#
# 规则：
#   core            纯 JDK：不 import android.*，不 import com.hex91.file.*
#   fs/apk/net      不 import ui / controller
#   utils           不 import ui / controller / apk / net
#   ui/controller   可用下面所有层
#   全体            自有包一律精确 import（根包 `com.hex91.file.*` 也在禁列）
#
# 三类绕过都要堵（R-18）：
#   1. `import static com.hex91.file.ui.X.y;`  —— 以 `import static` 开头，旧正则漏匹配
#   2. 源码里直接写全限定名 `com.hex91.file.ui.UiKit.dp(1)`，没有 import 行
#   3. 根包通配 `import com.hex91.file.*;`     —— 旧正则要求 `file.` 后还有一段再跟 `.*`
#
# 为什么只查 import 与全限定名而不做全文调用图：Java 里没有 import、也不写全限定名，
# 就用不到跨包类型；这两类覆盖了全部可能的越层写法，成本最低、误报最少。
# 门本身也有盲区（反射 / 字符串类名），但那种写法在本项目里不存在，真出现时靠评审发现。
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

check core  '^import (static )?(android\.|com\.hex91\.file\.)'                       'core 必须是纯 JDK'
check fs    '^import (static )?com\.hex91\.file\.(\*|ui\.|controller\.)'             'fs 不能依赖 ui/controller'
check apk   '^import (static )?com\.hex91\.file\.(\*|ui\.|controller\.)'             'apk 不能依赖 ui/controller'
check net   '^import (static )?com\.hex91\.file\.(\*|ui\.|controller\.)'             'net 不能依赖 ui/controller'
check utils '^import (static )?com\.hex91\.file\.(\*|ui\.|controller\.|apk\.|net\.)' 'utils 只能依赖 core/fs'

# 自有包（含根包）一律精确 import：通配会让「注释里提过一个类」看起来像「依赖了整个包」
wild=$(grep -rnE '^import (static )?com\.hex91\.file\.(\*|[A-Za-z0-9_.]+\.\*);' src --include='*.java' 2>/dev/null || true)
if [ -n "$wild" ]; then
  echo "FAIL 自有包禁止通配 import（O-18 / R-17）"
  echo "$wild" | sed 's/^/      /'
  fail=1
fi

# 全限定名越层：下三层里直接写 ui/controller 的完整类名（没有 import 行的那条路）
fqn=$(grep -rnE '(^|[^."])com\.hex91\.file\.(ui|controller)\.' \
      src/com/hex91/file/fs src/com/hex91/file/apk src/com/hex91/file/net \
      src/com/hex91/file/utils src/com/hex91/file/core --include='*.java' 2>/dev/null \
      | grep -vE ':[0-9]+:[[:space:]]*(import |\*|//)' || true)
if [ -n "$fqn" ]; then
  echo "FAIL 下三层出现 ui/controller 的全限定名引用（R-18）"
  echo "$fqn" | sed 's/^/      /'
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "依赖方向检查通过（core → utils → fs/apk/net → ui/controller → MainActivity）"
fi
exit $fail
