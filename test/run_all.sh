#!/usr/bin/env bash
# HexFile 桌面 JVM 全量测试入口
# 用法：MSYS_NO_PATHCONV=1 bash test/run_all.sh [测试名过滤]
# 前提：build/obj 存在（先跑一次 build.sh）；Windows 下需 MSYS_NO_PATHCONV=1 防 classpath 被转义
set -u
cd "$(dirname "$0")/.."

# ---- 工具链定位 -------------------------------------------------------------
# R-44：原来这里写死 D:/Program Files/Java/zulu17.38.21 与 E:/repo/android/Sdk，
# 且用 ';' 拼 classpath —— 任何没有这两个盘符的机器（含 CI、Linux）必然失败，
# 而 README 的「全量 97/0/4」正是这个脚本的口径。现在与 run_core.sh 共用
# tools/toolchain.sh：环境变量优先 → 常见位置探测 → 缺东西 exit 2 + 可操作提示。
. "$(dirname "$0")/../tools/toolchain.sh"
# 缺样本的判据也要共用，否则两个入口对同一环境给出相反结论（见 tools/testlib.sh）。
. "$(dirname "$0")/../tools/testlib.sh"
JAVAC="$JH/javac$EXE"; JAVA="$JH/java$EXE"
CP="build/obj${SEP}libs/*${SEP}$APKSIG${SEP}$ANDJ"
OUT="test/bin_all"

# v1.67.2：单个测试 JVM 内存上限（防止某个测试把整机内存吃满、表现为「卡死」）
TEST_XMX="${TEST_XMX:--Xmx512m}"
# 需要命令行参数的测试统一喂样本：apk + keystore
TEST_APK="${TEST_APK:-test/huangguo.apk}"
TEST_KS="${TEST_KS:-assets/hexfile.p12}"

FILTER="${1:-}"

# 依赖 build/obj 里的编译产物：先确保 src 已构建（否则整批报「找不到符号」的假失败）
if [ -z "$(find build/obj/com/hex91/file -name '*.class' -print -quit 2>/dev/null)" ]; then
    echo "build/obj 缺失，先跑 ./build.sh"; exit 2
fi

rm -rf "$OUT" && mkdir -p "$OUT"

echo "== 编译全部测试 =="
# RarProbe / ArscDbgB 为一次性诊断工具（依赖缺失或引用过期调试类），跳过编译
TEST_SRCS=$(ls test/*.java | grep -vE "RarProbe|ArscDbgB")
if ! "$JAVAC" -J-Dfile.encoding=UTF-8 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" $TEST_SRCS; then
    echo "!! 测试编译失败"; exit 2
fi

# 收集测试类（包路径还原）
mapfile -t CLASSES < <(find "$OUT" -name "*Test.class" ! -name "*\$*" | sed "s|$OUT/||; s|\.class\$||; s|/|.|g" | sort)

pass=0; fail=0; skip=0; failed_names=()
# 需真机样本/本机不可用原生库的测试：跳过并在括号注明原因
SKIP_REASONS=(
    "AxmlRealChainTest|大样本重打包，分钟级且吃 GB 级内存（HEXFILE_SLOW=1 才跑）"
    "ArchiveWriteTest|需真机样本 test/_dbg/sample.zip|tar|tgz（/sdcard/Download/HexFile_test/）"
    "ZstdTest|zstd-jni classes 包不含 Windows 原生库，桌面无法加载"
    "ArchZstTest|同上（MakeSample 生成 zstd 样本需原生库）"
)
for cls in "${CLASSES[@]}"; do
    [[ -n "$FILTER" && "$cls" != *"$FILTER"* ]] && continue
    short="${cls##*.}"
    skip_reason=""
    for entry in "${SKIP_REASONS[@]}"; do
        name="${entry%%|*}"; reason="${entry#*|}"
        if [[ "$short" == "$name" ]]; then skip_reason="$reason"; break; fi
    done
    if [[ "$short" == "AxmlRealChainTest" && "${HEXFILE_SLOW:-0}" == "1" ]]; then skip_reason=""; fi
    # R-44：缺样本与 run_core.sh 同口径 —— SKIP 而不是 FAIL，且提示文本一致。
    if [[ -z "$skip_reason" ]]; then
        if test_needs_apk "$short" && [ ! -f "$TEST_APK" ]; then
            skip_reason="缺 $TEST_APK"
        elif test_needs_p12 "$short" && [ ! -f "$TEST_KS" ]; then
            skip_reason="缺 $TEST_KS"
        fi
    fi
    if [[ -n "$skip_reason" ]]; then
        printf '%-40sSKIP (%s)\n' "$cls" "$skip_reason"
        skip=$((skip+1)); continue
    fi
    # 需要样本参数的测试：SessionTest 要 apk+keystore，其余给 apk 即可（多余参数被忽略）
    case "$short" in
        SessionTest) TARGS="$TEST_APK $TEST_KS" ;;
        KeyStoreUtilTest|SignSchemeTest|TreeMergeTest|BakTest|SignPromptFlowTest|CustomSignE2ETest|DexPropsTest) TARGS="$TEST_APK" ;;
        *) TARGS="" ;;
    esac
    printf '%-40s' "$cls"
    t_start=$(date +%s)
    if timeout 300 "$JAVA" $TEST_XMX -Dfile.encoding=UTF-8 -cp "$OUT;$CP" "$cls" $TARGS > "$OUT/$cls.log" 2>&1; then
        echo "PASS ($(( $(date +%s) - t_start ))s)"
        pass=$((pass+1))
    else
        echo "FAIL (exit=$?, $(( $(date +%s) - t_start ))s)"
        fail=$((fail+1)); failed_names+=("$cls")
    fi
done

echo
echo "== 结果: $pass 通过, $fail 失败, $skip 跳过 =="
if (( fail )); then
    printf '失败: %s\n' "${failed_names[@]}"
    for f in "${failed_names[@]}"; do echo "---- $f 尾部输出 ----"; tail -8 "$OUT/$f.log"; done
    exit 1
fi
