#!/usr/bin/env bash
# 空 catch 不增长门（O-20）
#
#   用法：bash test/catch_check.sh          # 计数，超过基线就失败
#         bash test/catch_check.sh --list   # 额外列出全部 文件:行
#         bash test/catch_check.sh --others # 只列「变量名不带 ignore」的那批（欠理由）
#
# 为什么只做「不增长」而不是「清零」：这个项目里 830 个 catch 有 330 多个是空块，
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

# 基线：2026-11-13 实测。审计报告写的 315 是当时的时点，之后 O-16 拆包与 F-14/F-15
# 又加了一些；口径为「只统计自研包」。
#   v2.4.0 首测 345 → 治 LanNode 6 处 → 339
#   v2.5.0 治 FtpFs 状态码解析 1 处 → 338（O-23 批次 1 实测：FtpFs/S3Fs/HlsDownloader
#   的其余 31 处全是 close/disconnect/QUIT 这类收尾，或 URLDecoder 失败保留原值这类
#   降级容错，硬加日志只是噪音，故本批只补 1 处）
#   v2.6.0（R-21）口径修正后 → 336：原来的 338 里有 2 处是 WebView 注入的 JS 源码
#   （CloudLoginActivity 里 "...catch(e){}..."），根本不是 Java 语法。
# 清理一批就把这个数下调，永不上调。
BASE=336

MODE=""
case "${1:-}" in
  --list)   MODE="all" ;;
  --others) MODE="others" ;;
esac

# Python 解释器探测（R-13）：只 command -v 不够，WindowsApps 目录下的 python3 是
# Store 存根，--version 都返回 49。用 -c 'pass' 真跑一次才算数。
# 覆盖方式：PYTHON_EXE=python bash test/catch_check.sh
#
# 这里刻意不 source tools/toolchain.sh：那道定位器要求机器上有 JDK + Android SDK，
# 而本门是纯文本检查，必须在什么都没装的环境里也能跑（CI 上的 docs-only 任务就是）。
PY="${PYTHON_EXE:-${PYTHON:-}}"
if [ -z "$PY" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'pass' >/dev/null 2>&1; then
      PY="$c"; break
    fi
  done
fi
# R-23：显式覆盖也要「真能跑」。原来 PYTHON_EXE=/nonexistent/python 会一路走到
# line 59 才炸，输出是 "/nonexistent/python: No such file or directory"（rc=127），
# 与 README 承诺的 rc=2 不一致，CI 里看着像脚本自身出错。
[ -n "$PY" ] || { echo "找不到可用的 Python：用 PYTHON_EXE=python 指定" >&2; exit 2; }
"$PY" -c 'pass' >/dev/null 2>&1 || { echo "PYTHON_EXE 不可用: $PY" >&2; exit 2; }

"$PY" - "$BASE" "$MODE" <<'PY'
import os, sys, re

base = int(sys.argv[1])
mode = sys.argv[2]          # '' = 汇总；'all' = --list；'others' = --others

def strip_comments(s):
    """把注释整段换成等长空格（保留换行）。

    v2.6.0（R-21）顺带修的一处：原来的实现是**删掉**注释字符，于是 clean 比原文短，
    「clean 与原文等长、偏移可回查」这个前提从一开始就不成立 —— 行号是靠数 \\n 撑住的，
    所以 --list 才没露馅，但任何按偏移回查原文的地方都是错的。改成抹白之后，
    clean[i] 与 raw[i] 一一对应，下方判「空块里是否写了 ignore 注释」才能拿到对的那段。
    """
    out=list(s); i=0; n=len(s); st=0
    while i<n:
        c=s[i]; nxt=s[i+1] if i+1<n else ''
        if st==0:
            if c=='/' and nxt=='/': st=1; out[i]=' '; i+=1; continue
            if c=='/' and nxt=='*': st=2; out[i]=' '; i+=1; continue
            if c=='"': st=3
            elif c=="'": st=4
            i+=1
        elif st==1:
            if c=='\n': st=0
            else: out[i]=' '
            i+=1
        elif st==2:
            if c=='*' and nxt=='/':
                out[i]=' '; out[i+1]=' '; st=0; i+=2; continue
            if c!='\n': out[i]=' '
            i+=1
        else:
            if c=='\\':
                i+=2; continue
            if (st==3 and c=='"') or (st==4 and c=="'"): st=0
            i+=1
    return ''.join(out)

def blank_strings(s):
    """v2.6.0（R-21）：把字符串/字符字面量内容换成等长空格。

    两个真实误报，都来自只去注释、不去字符串：
      1) 注入给 WebView 的 JS 源码里写着 "...catch(e){}..."，
         CloudLoginActivity 有两处，被当成 Java 空 catch 计进基线；
      2) 字符串里的 '{' '}' 会参与空块的花括号配对（"if (c == '{') ..."）。
    替换而非删除：不增删字符、保留换行，行号与偏移全都不变，--list 依旧准。
    转义对（\\" 与 \\\\）必须整体抹掉，否则 \\" 会被当成字符串结束，后续代码全被吞。
    """
    out=list(s); i=0; n=len(s); st=0
    while i<n:
        c=s[i]
        if st==0:
            if c=='"': st=3
            elif c=="'": st=4
            i+=1
        else:
            if c=='\\':
                out[i]=' '
                if i+1<n and s[i+1]!='\n': out[i+1]=' '
                i+=2; continue
            if (st==3 and c=='"') or (st==4 and c=="'"):
                st=0
            elif c!='\n':
                out[i]=' '
            i+=1
    return ''.join(out)

def deliberate(var):
    """作者是否明确表态「我知道会抛，就是不处理」。

    判据是命名，不是注释：v2.6.0 之前只认 ignore/ignored，实测剩下 17 个里
    9 个叫 ig（ArchiveUtil 7 处、MainActivity、ZstdUtil），意思完全一样，
    却被算进「欠理由」，后续按这个数排期就是白干。
    """
    v=var.lower()
    return v.startswith('ignore') or v in ('ig','ign')

hits=[]
# 只统计自研包：vendored 的 jadx / termux / nayuki 是第三方代码，改了不利于同步上游
for root, dirs, files in os.walk('src/com/hex91/file'):
    for f in files:
        if not f.endswith('.java'): continue
        p=os.path.join(root,f)
        raw=open(p,encoding='utf-8',errors='replace').read()
        # 去注释 → 抹字符串 → 再做 catch 匹配与花括号配对
        clean=blank_strings(strip_comments(raw))
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
                # 变量名叫 ignore/ig = 作者明确决定吞掉；叫 t/e/ex 却留空块，
                # 通常是「本来想记但忘了」，这一类才是要治的
                decl=m.group(1).strip()
                var=decl.split()[-1] if decl.split() else ''
                # 空块里只写着注释的（catch (Throwable t2) { /* ignore */ }）同样算
                # 明确表态 —— clean 里注释已被抹掉，得回头看原文同一段偏移
                note=raw[i+1:j].lower()
                said=deliberate(var) or 'ignore' in note or '忽略' in note
                hits.append((p.replace('\\','/'), line, said))

total=len(hits)
print('空 catch 总数：%d（基线 %d）' % (total, base))
others=[h for h in hits if not h[2]]
if mode=='all':
    for p,l,_d in hits:
        print('  %s:%d' % (p,l))
elif mode=='others':
    for p,l,_d in others:
        print('  %s:%d' % (p,l))
else:
    # 不列全部，只按目录汇总，方便看清理进度
    from collections import Counter
    c=Counter()
    for p,_,_d in hits:
        parts=p.split('/')
        c[parts[4] if len(parts)>5 else '<root>'] += 1
    for k,v in sorted(c.items(), key=lambda kv:-kv[1]):
        print('  %-12s %d' % (k, v))
    # 「明确忽略」与「留空但变量名不是 ignore」分开报：后者才是真正欠理由的地方
    print('  其中「命名表态忽略」= %d，其余 = %d' % (total-len(others), len(others)))

if total > base:
    print('FAIL  空 catch 比基线多了 %d 个：新增代码里不要留空 catch' % (total-base))
    sys.exit(1)
print('PASS  未超过基线')
PY
