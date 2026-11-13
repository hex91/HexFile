#!/usr/bin/env bash
# 空 catch 不增长门（O-20）
#
#   用法：bash test/catch_check.sh          # 计数，超过基线就失败
#         bash test/catch_check.sh --list   # 额外列出全部 文件:行
#
# 为什么只做「不增长」而不是「清零」：这个项目里 830 个 catch 有 315 个是空块（38%），
# 但相当一部分是合理的 —— close()/stopForeground()/清理路径吞掉异常本来就是正确写法，
# 硬给它们加日志只是噪音。真正的问题是「网络与文件写入路径吞异常」，
# 用户只看到「失败」却没有原因。所以先设一道不增长的闸，再按网络 → UI → 文件的顺序
# 分批补，每批只治确实需要知道原因的路径。
#
# 例外（脚本不排除，人工判断时保留）：
#   catch (Throwable ignore) { }  紧跟资源关闭 / 回滚操作，属于「收尾尽力而为」；
#   但网络请求、文件写入、状态迁移里的空 catch 必须补 Log.w(TAG, 操作 + 对象, e)。
#
# 注意：日志只记「操作名 + 异常类」，不要把含 token 的 URL、密码打进 logcat
# （S3/FTP 的 URL 里带签名参数，直接 t.toString() 会泄露凭据）。
#
# 退出码：0 = 未超基线；1 = 超基线（新增代码留了空 catch）；2 = 环境缺失（找不到 Python）。
# 2 这个值是为了让「门没跑起来」和「门通过了」不混在一起：v2.4.0 之前硬编码 python3，
# 在 Windows 上命中的是 Microsoft Store 的存根，脚本静默退出 49、一行输出都没有，
# 看起来像通过。环境问题必须大声报出来。
set -u
cd "$(dirname "$0")/.."

# 基线：2026-11-13 实测 339（审计报告写的 315 是当时的时点，之后 O-16 拆包与
# F-14/F-15 又加了一些；口径为「只统计自研包」）。清理一批就把这个数下调，永不上调。
BASE=339

LIST=0
[ "${1:-}" = "--list" ] && LIST=1

# Python 解释器探测（R-13）：只 command -v 不够，WindowsApps 目录下的 python3 是
# Store 存根，--version 都返回 49。用 -c 'pass' 真跑一次才算数。
# 覆盖方式：PYTHON_EXE=python bash test/catch_check.sh
PY="${PYTHON_EXE:-${PYTHON:-}}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'pass' >/dev/null 2>&1; then
      PY="$c"; break
    fi
  done
fi
[ -n "$PY" ] || { echo "找不到可用的 Python：用 PYTHON_EXE=python 指定" >&2; exit 2; }

"$PY" - "$BASE" "$LIST" <<'PY'
import os, sys, re

base = int(sys.argv[1])
show_list = sys.argv[2] == '1'

def strip_comments(s):
    out=[]; i=0; n=len(s); st=0
    while i<n:
        c=s[i]; nxt=s[i+1] if i+1<n else ''
        if st==0:
            if c=='/' and nxt=='/': st=1; i+=2; continue
            if c=='/' and nxt=='*': st=2; i+=2; continue
            if c=='"': st=3; out.append(c); i+=1; continue
            if c=="'": st=4; out.append(c); i+=1; continue
            out.append(c); i+=1
        elif st==1:
            if c=='\n': st=0; out.append(c)
            i+=1
        elif st==2:
            if c=='*' and nxt=='/': st=0; i+=2; continue
            # 注释里的换行必须保留，否则 clean 的行号与原文错位，--list 报的就不准
            if c=='\n': out.append(c)
            i+=1
        else:
            if c=='\\':
                out.append(c); i+=1
                if i<n: out.append(s[i])
                i+=1; continue
            out.append(c)
            if (st==3 and c=='"') or (st==4 and c=="'"): st=0
            i+=1
    return ''.join(out)

hits=[]
# 只统计自研包：vendored 的 jadx / termux / nayuki 是第三方代码，改了不利于同步上游
for root, dirs, files in os.walk('src/com/hex91/file'):
    for f in files:
        if not f.endswith('.java'): continue
        p=os.path.join(root,f)
        raw=open(p,encoding='utf-8',errors='replace').read()
        clean=strip_comments(raw)
        # 行号：clean 与 raw 长度一致（只替换字符不增删），可直接用偏移算行
        for m in re.finditer(r'\bcatch\s*\(([^)]*)\)\s*\{', clean):
            i=m.end()-1; depth=0; j=i
            while j < len(clean):
                if clean[j]=='{': depth+=1
                elif clean[j]=='}':
                    depth-=1
                    if depth==0: break
                j+=1
            body=clean[i+1:j]
            if body.strip()=='':
                line=clean.count('\n',0,m.start())+1
                hits.append((p.replace('\\','/'), line))

total=len(hits)
print('空 catch 总数：%d（基线 %d）' % (total, base))
if show_list:
    for p,l in hits:
        print('  %s:%d' % (p,l))
else:
    # 不列全部，只按目录汇总，方便看清理进度
    from collections import Counter
    c=Counter()
    for p,_ in hits:
        parts=p.split('/')
        c[parts[4] if len(parts)>5 else '<root>'] += 1
    for k,v in sorted(c.items(), key=lambda kv:-kv[1]):
        print('  %-12s %d' % (k, v))

if total > base:
    print('FAIL  空 catch 比基线多了 %d 个：新增代码里不要留空 catch' % (total-base))
    sys.exit(1)
print('PASS  未超过基线')
PY
