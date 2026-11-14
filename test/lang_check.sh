#!/usr/bin/env bash
# 资源文案一致性门（v2.8.0，O-24 配套）
#
#   用法：bash test/lang_check.sh
#
# 为什么需要它：i18n 是「一次一页」迁的，最容易出的错不是翻错，而是**键对不上**：
#   - 代码里写了 R.string.xxx，资源里忘了加 —— aapt2 会在 link 阶段报错，这一条本来就有兜底，
#     但报错是「resource not found」，得自己回去找是哪个键；
#   - 中文加了、英文漏了 —— 编译完全正常，切英文才发现这一页还是中文，只能靠人肉发现；
#   - 英文加了、中文漏了 —— 编译正常，中文环境直接崩（找不到资源）；
#   - **格式参数对不上** —— 中文 `已保存 %1$s`、英文翻成 `Saved`（丢了参数）不崩，
#     但界面上「已保存」后面什么都没有；反过来英文多一个 %2$s 而调用方只传一个参数，
#     运行时 String.format 抛 MissingFormatArgumentException，直接闪退。
#   前三类里第三、四类是真会静默出问题的，最后一条尤其值得拦。
#
# 刻意不做的检查（避免把门做成噪音）：
#   - 不要求 values-en 覆盖全部键：v2.3.0 定的规矩是「只放已完成迁移的键」，
#     未迁移的页面回退中文是预期行为，不是漏翻。覆盖度只作为统计输出。
#   - 不检查文案语义/术语一致性：那要人看。
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
import os, re, sys
import xml.etree.ElementTree as ET

BAD = []          # 违规
def bad(msg):
    BAD.append(msg)

# ---------------------------------------------------------------- 读资源
def load(path):
    """返回 {name: (kind, [arg set])}；解析失败直接算违规（XML 坏了构建也会挂，先说清楚）"""
    d = {}
    try:
        root = ET.parse(path).getroot()
    except Exception as e:
        bad('%s 解析失败: %s' % (path, e))
        return d
    for el in root:
        name = el.get('name')
        if not name:
            continue
        kind = el.tag
        if name in d:
            bad('%s 重复的键: %s' % (path, name))
        args = set()
        for s in el.itertext():
            args |= set(re.findall(r'%(\d+)\$', s))
        d[name] = (kind, args)
    return d

ZH = load('res/values/strings.xml')
EN = load('res/values-en/strings.xml')

# ---------------------------------------------------------------- 代码里用到的键
used = {}
for root, dirs, files in os.walk('src'):
    for f in files:
        if not f.endswith('.java'):
            continue
        p = os.path.join(root, f)
        src = open(p, encoding='utf-8', errors='replace').read()
        for kind, name in re.findall(r'R\.(string|plurals)\.([A-Za-z_]\w*)', src):
            used.setdefault((kind, name), set()).add(p.replace('\\', '/'))

for (kind, name), where in sorted(used.items()):
    if name not in ZH:
        bad('代码引用了不存在的键 R.%s.%s（%s）' % (kind, name, ', '.join(sorted(where))[:120]))
    elif ZH[name][0] != kind:
        bad('R.%s.%s 的类型不符：资源里是 <%s>' % (kind, name, ZH[name][0]))

# ---------------------------------------------------------------- 英文侧
for name in sorted(EN):
    if name not in ZH:
        bad('values-en 有中文侧没有的键: %s（英文多余 = 中文环境会缺资源）' % name)

for name, (ekind, eargs) in sorted(EN.items()):
    if name not in ZH:
        continue
    zkind, zargs = ZH[name]
    if ekind != zkind:
        bad('%s 两侧资源类型不同: <%s> vs <%s>' % (name, zkind, ekind))
        continue
    # 位置参数必须一一对应：少了会输出空、多了会运行时抛 MissingFormatArgumentException
    if eargs != zargs:
        bad('%s 格式参数不一致: 中文 %s / 英文 %s'
            % (name, sorted(zargs) or '无', sorted(eargs) or '无'))

# ---------------------------------------------------------------- 统计
translated = len([n for n in ZH if n in EN])
print('资源键：中文 %d 条，英文 %d 条（已翻译 %.0f%%）' % (len(ZH), len(EN),
      100.0 * translated / max(1, len(ZH))))
print('代码引用：%d 个键（%d 处文件）' % (len(used), len({p for w in used.values() for p in w})))

if BAD:
    print()
    for m in BAD:
        print('FAIL ' + m)
    sys.exit(1)
print('PASS  文案键与格式参数一致')
PY
