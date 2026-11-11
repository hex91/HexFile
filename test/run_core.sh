#!/usr/bin/env bash
# 核心测试（桌面 JVM，不需要设备）
#   ./test/run_core.sh                 全量（含真签名 E2E，慢）
#   ./test/run_core.sh HexLayoutTest   只跑指定用例
#   QUICK=1 ./test/run_core.sh         跳过重签 E2E 这类几十秒级的用例
set -u
cd "$(dirname "$0")/.."
JH="D:/Program Files/Java/jdk-21.0.3+9/bin"
BT="E:/repo/android/Sdk/build-tools/37.0.0"
ANDJ="E:/repo/android/Sdk/platforms/android-35/android.jar"
CP="build/obj;libs/*;$BT/lib/apksigner.jar;$ANDJ"
OUT="test/bin_core"

# 依赖 build/obj 里的编译产物：先确保 src 已构建（否则会出现「找不到符号」的假失败）
if [ ! -f build/obj/com/hex91/file/HexCore.class ]; then
  echo "build/obj 缺失，先跑 ./build.sh"
  exit 2
fi

rm -rf "$OUT" && mkdir -p "$OUT"
SRCS=$(ls test/*.java | grep -vE "RarProbe|ArscDbgB")
"$JH/javac.exe" -J-Dfile.encoding=UTF-8 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" $SRCS \
  || { echo "COMPILE FAIL"; exit 2; }

ALL="DexPropsTest KeyStoreUtilTest SignSchemeTest TreeMergeTest BakTest SessionTest SignPromptFlowTest CustomSignE2ETest ArscPreviewTest HexLayoutTest FileInspectTest KeystoreCreateTest ActivityLogTest ToolboxCoreTest TestDataCoreTest MimeUtilTest DiskScanTest UiKitTest ScanCacheTest LanProtocolTest UiDecorTest FileTaskTest FileClipboardTest FileAttrsTest ContentGrepTest LinkUtilTest TrashPolicyTest CredentialCodecTest ContrastTest WorkDirTest DocPathTest WidgetTextTest SyncPlanTest ResumePlanTest DragPlanTest S3SignerTest JsonTest OAuthTokenTest CloudPathTest Iso8601Test TokenHarvestTest M3u8PlaylistTest StreamUrlTest PlaySpeedTest"

if [ $# -gt 0 ]; then
  TESTS="$*"
elif [ "${QUICK:-0}" = "1" ]; then
  # 重签/会话类用例要真跑 apksig 打包，几十秒起，改 UI 时没必要每次都等
  TESTS="DexPropsTest KeyStoreUtilTest SignSchemeTest TreeMergeTest BakTest ArscPreviewTest HexLayoutTest FileInspectTest KeystoreCreateTest ActivityLogTest ToolboxCoreTest TestDataCoreTest MimeUtilTest DiskScanTest UiKitTest ScanCacheTest LanProtocolTest UiDecorTest FileTaskTest FileClipboardTest FileAttrsTest ContentGrepTest LinkUtilTest TrashPolicyTest CredentialCodecTest ContrastTest WorkDirTest DocPathTest WidgetTextTest SyncPlanTest ResumePlanTest DragPlanTest S3SignerTest JsonTest OAuthTokenTest CloudPathTest Iso8601Test TokenHarvestTest M3u8PlaylistTest StreamUrlTest PlaySpeedTest"
else
  TESTS="$ALL"
fi

pass=0; fail=0
for t in $TESTS; do
  case $t in
    KeyStoreUtilTest) ARG="";;
    SessionTest) ARG="test/huangguo.apk assets/hexfile.p12";;
    *) ARG="test/huangguo.apk";;
  esac
  printf '[..] %-20s ' "$t"
  start=$(date +%s)
  if timeout 420 "$JH/java.exe" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" "$t" $ARG \
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
echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ] || exit 1
