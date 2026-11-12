#!/usr/bin/env bash
# 核心测试（桌面 JVM，不需要设备）
#   ./test/run_core.sh                 全量（含真签名 E2E，慢）
#   ./test/run_core.sh HexLayoutTest   只跑指定用例
#   QUICK=1 ./test/run_core.sh         跳过重签 E2E 这类几十秒级的用例
set -u
cd "$(dirname "$0")/.."

# ---- 工具链定位（v1.96.0）--------------------------------------------------
# 原来这里写死了 D:/ 与 E:/ 盘符，等于把测试锁在开发机上（CI 跑不起来，换台机器也跑不起来）。
# 现在三件事：环境变量优先 → 其次按常见位置探测 → 都没有就明确报错。
#   JH=/path/to/jdk/bin   BT=/path/to/build-tools/37.0.0   ANDJ=/path/to/android.jar
first_existing() {
  for c in "$@"; do
    [ -n "$c" ] && [ -e "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

if [ -z "${JH:-}" ]; then
  JH=$(first_existing "D:/Program Files/Java/jdk-21.0.3+9/bin" \
        "${JAVA_HOME:+$JAVA_HOME/bin}" \
        "$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/none)" 2>/dev/null)") \
    || { echo "找不到 JDK：用 JH=/path/to/jdk/bin 指定"; exit 2; }
fi
EXE=""
[ -f "$JH/javac.exe" ] && EXE=".exe"

if [ -z "${BT:-}" ]; then
  BT=$(first_existing "E:/repo/android/Sdk/build-tools/37.0.0" \
        "${ANDROID_HOME:+$ANDROID_HOME/build-tools/37.0.0}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/build-tools/37.0.0}" \
        "$HOME/android-sdk/build-tools/37.0.0" \
        "$HOME/Android/Sdk/build-tools/37.0.0") \
    || { echo "找不到 build-tools（需要 37.0.0）：用 BT=/path/to/build-tools/37.0.0 指定"; exit 2; }
fi
if [ -z "${ANDJ:-}" ]; then
  ANDJ=$(first_existing "${ANDROID_HOME:+$ANDROID_HOME/platforms/android-35/android.jar}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platforms/android-35/android.jar}" \
        "E:/repo/android/Sdk/platforms/android-35/android.jar" \
        "$HOME/android-sdk/platforms/android-35/android.jar" \
        "$HOME/Android/Sdk/platforms/android-35/android.jar") \
    || { echo "找不到 android.jar：用 ANDJ=/path/to/android.jar 指定"; exit 2; }
fi

# 类路径分隔符：Windows（Git Bash / MSYS）是 ';'，其余平台 ':'
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';';;
  *) SEP=':';;
esac
echo "JDK=$JH  BT=$BT"
CP="build/obj${SEP}libs/*${SEP}$BT/lib/apksigner.jar${SEP}$ANDJ"
OUT="test/bin_core"

# 依赖 build/obj 里的编译产物：先确保 src 已构建（否则会出现「找不到符号」的假失败）
if [ ! -f build/obj/com/hex91/file/HexCore.class ]; then
  echo "build/obj 缺失，先跑 ./build.sh"
  exit 2
fi

rm -rf "$OUT" && mkdir -p "$OUT"
SRCS=$(ls test/*.java | grep -vE "RarProbe|ArscDbgB")
"$JH/javac$EXE" -J-Dfile.encoding=UTF-8 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" $SRCS \
  || { echo "COMPILE FAIL"; exit 2; }

ALL="DexPropsTest KeyStoreUtilTest SignSchemeTest TreeMergeTest BakTest SessionTest SignPromptFlowTest CustomSignE2ETest ArscPreviewTest HexLayoutTest FileInspectTest KeystoreCreateTest ActivityLogTest ToolboxCoreTest TestDataCoreTest MimeUtilTest DiskScanTest UiKitTest ScanCacheTest LanProtocolTest UiDecorTest FileTaskTest FileClipboardTest FileAttrsTest ContentGrepTest LinkUtilTest TrashPolicyTest CredentialCodecTest ContrastTest WorkDirTest DocPathTest WidgetTextTest SyncPlanTest ResumePlanTest DragPlanTest S3SignerTest JsonTest OAuthTokenTest CloudPathTest Iso8601Test TokenHarvestTest M3u8PlaylistTest StreamUrlTest PlaySpeedTest HlsPlaylistTest ScreenPolicyTest AuditFixTest HlsDownloaderTest DirReaderTest TreeOpsTest TaskListenerTest AtomicReplaceTest ZipUtilTest TaskJournalTest LeftoverScanTest FileItemCacheTest"

if [ $# -gt 0 ]; then
  TESTS="$*"
elif [ "${QUICK:-0}" = "1" ]; then
  # 重签/会话类用例要真跑 apksig 打包，几十秒起，改 UI 时没必要每次都等
  TESTS="DexPropsTest KeyStoreUtilTest SignSchemeTest TreeMergeTest BakTest ArscPreviewTest HexLayoutTest FileInspectTest KeystoreCreateTest ActivityLogTest ToolboxCoreTest TestDataCoreTest MimeUtilTest DiskScanTest UiKitTest ScanCacheTest LanProtocolTest UiDecorTest FileTaskTest FileClipboardTest FileAttrsTest ContentGrepTest LinkUtilTest TrashPolicyTest CredentialCodecTest ContrastTest WorkDirTest DocPathTest WidgetTextTest SyncPlanTest ResumePlanTest DragPlanTest S3SignerTest JsonTest OAuthTokenTest CloudPathTest Iso8601Test TokenHarvestTest M3u8PlaylistTest StreamUrlTest PlaySpeedTest HlsPlaylistTest ScreenPolicyTest AuditFixTest HlsDownloaderTest DirReaderTest TreeOpsTest TaskListenerTest AtomicReplaceTest ZipUtilTest TaskJournalTest LeftoverScanTest FileItemCacheTest"
else
  TESTS="$ALL"
fi

pass=0; fail=0; skipped=0
for t in $TESTS; do
  case $t in
    KeyStoreUtilTest) ARG="";;
    SessionTest) ARG="test/huangguo.apk assets/hexfile.p12";;
    *) ARG="test/huangguo.apk";;
  esac
  # v1.96.0：少数用例依赖本机样本 APK（*.apk 被 .gitignore，CI 上必然没有）——
  # 缺样本就 SKIP，不然整条流水线因为「样本没上传」变红，测试就白接了。
  # 注意：这里必须按用例逐个点名，不能拿"ARG 文件是否存在"当判据：
  # 绝大多数用例的 ARG 只是占位，会对不存在的路径照样自给自足。
  case "$t" in
    KeyStoreUtilTest|SignSchemeTest|ArscPreviewTest|FileInspectTest) needs_apk=1;;
    *) needs_apk=0;;
  esac
  if [ "$needs_apk" = "1" ] && [ ! -f test/huangguo.apk ]; then
    printf '[..] %-20s SKIP (缺 test/huangguo.apk)\n' "$t"
    skipped=$((skipped + 1))
    continue
  fi
  printf '[..] %-20s ' "$t"
  start=$(date +%s)
  if timeout 420 "$JH/java$EXE" -Dfile.encoding=UTF-8 -cp "$OUT${SEP}$CP" "$t" $ARG \
      > "test/${t}_v2.log" 2>&1 < /dev/null; then
    printf 'PASS %ss\n' "$(( $(date +%s) - start ))"
    pass=$((pass + 1))
  else
    printf 'FAIL %ss\n' "$(( $(date +%s) - start ))"
    fail=$((fail + 1))
    tail -3 "test/${t}_v2.log"
  fi
done
echo "-----------------------------------------"
echo "pass=$pass fail=$fail skip=$skipped"
[ "$fail" -eq 0 ] || exit 1
