#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."
JH="D:/Program Files/Java/jdk-21.0.3+9/bin"
BT="E:/repo/android/Sdk/build-tools/37.0.0"
ANDJ="E:/repo/android/Sdk/platforms/android-35/android.jar"
CP="build/obj;libs/*;$BT/lib/apksigner.jar;$ANDJ"
OUT="test/bin_core"
rm -rf "$OUT" && mkdir -p "$OUT"
SRCS=$(ls test/*.java | grep -vE "RarProbe|ArscDbgB")
"$JH/javac.exe" -J-Dfile.encoding=UTF-8 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" $SRCS || { echo "COMPILE FAIL"; exit 2; }
for t in DexPropsTest KeyStoreUtilTest SignSchemeTest TreeMergeTest BakTest SessionTest SignPromptFlowTest CustomSignE2ETest ArscPreviewTest; do
  case $t in
    KeyStoreUtilTest) ARG="";;
    SessionTest) ARG="test/huangguo.apk assets/hexfile.p12";;
    *) ARG="test/huangguo.apk";;
  esac
  printf '%-22s ' "$t"
  if timeout 420 "$JH/java.exe" -Dfile.encoding=UTF-8 -cp "$OUT;$CP" "$t" $ARG > "test/${t}_v2.log" 2>&1; then echo PASS; else echo "FAIL"; tail -3 "test/${t}_v2.log"; fi
done
