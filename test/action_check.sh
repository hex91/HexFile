#!/usr/bin/env bash
# 文件动作码一致性门（v2.11.0，O-26 配套）
#
#   用法：bash test/action_check.sh
#
# 为什么需要它：O-25 把动作码收敛成类级 A_*（52 个），O-26 又把 runFileAction 的 52 个
# case 从数字换成了常量名。但「换个名字」这件事没有任何编译期保护 —— javac 接受
# `case 14:`，也接受 `case A_XXX:`，两者同时存在完全合法。于是下一轮改代码时很容易
# 又写回数字，这张表就慢慢退回去了。
#
# 查三件事：
#   1. runFileAction 里不许再出现 `case <数字>`；
#   2. 全仓不许出现 `acts.add(<数字>)`（菜单侧漏常量）；
#   3. runFileAction 必须保留 default 分支（否则没 case 的号又变成静默无反应）。
#
# 只扫 runFileAction 这一个方法体，不扫全仓 switch：别的 switch（菜单 id、排序模式）
# 用数字 case 是正常的，扫全仓只会制造噪音。
#
# 退出码：0 = 通过；1 = 有违规；2 = 环境缺失（找不到 Python）。
set -u
cd "$(dirname "$0")/.."

PY="${PYTHON_EXE:-${PYTHON:-}}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'pass' >/dev/null 2>&1; then
      PY="$c"; break
    fi
  done
fi
[ -n "$PY" ] || { echo "找不到可用的 Python：用 PYTHON_EXE=python 指定" >&2; exit 2; }
"$PY" -c 'pass' >/dev/null 2>&1 || { echo "PYTHON_EXE 不可用: $PY" >&2; exit 2; }

"$PY" - <<'PY'
# -*- coding: utf-8 -*-
import io, re, sys

MAIN = 'src/com/hex91/file/MainActivity.java'
bad = []

src = io.open(MAIN, encoding='utf-8').read()

# 1) runFileAction 方法体：从方法头到下一个方法（含 javadoc 的 openLanShare）
try:
    i = src.index('private void runFileAction')
    j = src.index('private void openLanShare')
except ValueError:
    print('FAIL 找不到 runFileAction / openLanShare：文件结构变了，门需要同步更新')
    sys.exit(1)
body = src[i:j]

digit = [(n + 1, l.strip()) for n, l in enumerate(body.split('\n'))
         if re.match(r'\s*case\s+\d', l)]
for n, l in digit:
    bad.append('%s: runFileAction 里还有数字 case → %s' % (MAIN, l))

# 2) default 必须还在（没 case 的号靠它变成可见失败）
if not re.search(r'^\s*default\s*:', body, re.M):
    bad.append('%s: runFileAction 没有 default 分支' % MAIN)

# 3) 菜单侧：MainActivity 的 acts.add(<数字>) 清零（O-25 的成果，别退回去）。
#    只看 MainActivity：TextEditorActivity 里也有一个局部 `acts`（编辑器自己的菜单号），
#    那是另一张表；ArchiveController 的 A_EDIT / A_HERE 同理 —— 三套编号互不相干。
for n, l in enumerate(src.split('\n'), 1):
    if re.search(r'acts\.add\(\s*\d', l):
        bad.append('%s:%d 菜单里直接写了数字动作码 → %s' % (MAIN, n, l.strip()))

names = re.findall(r'private static final int (A_\w+) = \d+;', src)
if len(names) != len(set(names)):
    bad.append('%s: 有重复的 A_* 常量名' % MAIN)

if bad:
    print('FAIL 文件动作码不一致（O-26）')
    for b in bad:
        print('      ' + b)
    sys.exit(1)

print('PASS  动作码一致：runFileAction 全是常量 case（%d 个），菜单无数字，default 在位'
      % len(re.findall(r'case\s+A_\w+', body)))
PY
