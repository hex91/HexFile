#!/bin/bash
# ============================================================
# HexFile 一键构建脚本（无需 Android Studio / Gradle）
# 流程: aapt2 compile → link(R.java+base.apk) → NDK 编译 libtermux.so
#       → javac → d8 → zipalign → apksigner
#
# 路径可通过环境变量覆盖（CI 用 Linux，本地 Windows 走默认）：
#   ANDROID_HOME             Android SDK 根目录（默认 Windows 的 E:/repo/android/Sdk）
#   ANDROID_NDK_HOME         NDK 根目录（默认 $ANDROID_HOME/ndk/27.0.12077973）
#   JAVA_HOME               JDK 根目录（bin 在其下）
#   PYTHON_EXE              python 解释器（默认：Windows 用 workbuddy 环境，其它用 python3）
#   HEXFILE_KEYSTORE_BASE64 经 base64 编码的签名 keystore（CI 注入，优先于本地文件）
#   HEXFILE_KEYSTORE_PASS    keystore 密码（默认 android）
#   HEXFILE_KEYSTORE_ALIAS   签名 key 别名（默认 hex91，与本地 hexfile.keystore 内条目一致）
# ============================================================
set -e

# 判断操作系统，决定可执行文件后缀（Windows 为 .exe）
uname_s="$(uname -s)"
EXE=""
case "$uname_s" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

# ---- Android SDK ----
if [ -n "$ANDROID_HOME" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "$EXE" ]; then
  SDK="E:/repo/android/Sdk"
elif [ -d "$HOME/android-sdk" ]; then
  SDK="$HOME/android-sdk"
else
  SDK="$HOME/android-sdk"
fi
BT="$SDK/build-tools/37.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"

# ---- JDK ----
if [ -n "$JAVA_HOME" ]; then
  JDK_BIN="$JAVA_HOME/bin"
elif [ -n "$EXE" ]; then
  JDK_BIN="D:/Program Files/Java/zulu17.38.21/bin"
else
  JDK_BIN="$(dirname "$(command -v java)")"
fi
JAVAC="$JDK_BIN/javac${EXE}"
JAVA_BIN="$JDK_BIN/java${EXE}"
KEYTOOL="$JDK_BIN/keytool${EXE}"

# ---- NDK ----
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.0.12077973}"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ -n "$EXE" ]; then
  NDK_BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
fi

# ---- Python（仅用于 zip 追加 entry）----
if [ -n "$PYTHON_EXE" ]; then
  PY="$PYTHON_EXE"
elif [ -n "$EXE" ]; then
  PY="C:/Users/han/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
else
  PY="python3"
fi

# ---- 工程根目录 ----
if [ -n "$EXE" ]; then
  ROOT="$(cygpath -m "$(cd "$(dirname "$0")" && pwd)")"
else
  ROOT="$(cd "$(dirname "$0")" && pwd)"
fi
BUILD="$ROOT/build"
OUT="$ROOT/out"

# 版本号从 AndroidManifest 读取，避免硬编码
VER="$(grep -o 'android:versionName="[^"]*"' "$ROOT/AndroidManifest.xml" | head -1 | sed 's/.*"\(.*\)"/\1/')"
echo "Building HexFile $VER"
SECONDS=0

# 增量清理：只删本次构建会被覆盖的产物
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$OUT"
rm -f "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/java.args"
rm -f "$BUILD"/dex/classes*.dex
rm -f "$OUT/HexFile-unsigned.apk"

echo "[1/7] aapt2 compile resources..."
"$BT/aapt2${EXE}" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link (manifest + R.java)..."
"$BT/aapt2${EXE}" link -o "$BUILD/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version 21 --target-sdk-version 30 \
    "$BUILD/res.zip"

echo "[3/7] NDK 编译 libtermux.so（Termux PTY 原生桥，Apache-2.0）..."
mkdir -p "$ROOT/native/lib/arm64-v8a" "$ROOT/native/lib/armeabi-v7a"
# 原生桥极少改动：两个 ABI 的 .so 都比 termux.c 新时跳过编译
if [ "$ROOT/native/lib/arm64-v8a/libtermux.so" -nt "$ROOT/native/jni/termux.c" ] \
   && [ "$ROOT/native/lib/armeabi-v7a/libtermux.so" -nt "$ROOT/native/jni/termux.c" ]; then
    echo "      libtermux.so 已是最新，跳过"
else
"$NDK_BIN/aarch64-linux-android21-clang" -shared -fPIC -O2 \
    -o "$ROOT/native/lib/arm64-v8a/libtermux.so" "$ROOT/native/jni/termux.c"
"$NDK_BIN/armv7a-linux-androideabi21-clang" -shared -fPIC -O2 \
    -o "$ROOT/native/lib/armeabi-v7a/libtermux.so" "$ROOT/native/jni/termux.c"
fi

echo "[4/7] javac..."
# libs = apksigner.jar + baksmali/dexlib2 等（smali 反编译引擎），供编译期引用
# classpath 分隔符：Windows 为 ;，其它(Unix/Linux)为 :
SEP=":"
if [ -n "$EXE" ]; then SEP=";"; fi
LIBS_CP="$BT/lib/apksigner.jar"
for j in "$ROOT"/libs/*.jar; do LIBS_CP="$LIBS_CP$SEP$j"; done
# 源码集：
#   src/com/hex91/file/*            HexFile 自有
#   src/jadx/...                   vendored jadx ARSC 解析器（Apache-2.0）
#   src/com/termux/terminal|view   vendored Termux 终端引擎（Apache-2.0）
#   src/androidx/annotation/*      本地桩（编译期注解）
#   src/io/nayuki/qrcodegen/*      vendored Nayuki QR Code 生成器（MIT）
"$JAVAC" -J-Dfile.encoding=UTF-8 -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$PLATFORM" \
    -classpath "$LIBS_CP" \
    -d "$BUILD/obj" \
    "$BUILD/gen/com/hex91/file/R.java" \
    "$ROOT"/src/com/hex91/file/*.java \
    "$ROOT"/src/jadx/jadx/core/xmlgen/*.java \
    "$ROOT"/src/jadx/jadx/core/xmlgen/entry/*.java \
    "$ROOT"/src/com/termux/terminal/*.java \
    "$ROOT"/src/com/termux/view/*.java \
    "$ROOT"/src/com/termux/view/support/*.java \
    "$ROOT"/src/com/termux/view/textselection/*.java \
    "$ROOT"/src/androidx/annotation/*.java \
    "$ROOT"/src/io/nayuki/qrcodegen/*.java

echo "[5/7] d8 dex（分层：依赖 dex 指纹缓存 + 应用 dex 增量重建）..."
# 为什么要分层：全量合并时 d8 每次都要重新解析 apksigner.jar + 20 个 libs（约 20MB），
# 单次常达数分钟；而这些依赖几乎不变。改为：
#   ① 依赖 dex（build/deps）：仅在 libs/apksigner/platform/build-tools 指纹变化时重建；
#   ② 应用 dex（build/dex）：每次只编译工程自身的 class，libs 以 --lib 形式仅参与类型解析。
DEPS_DIR="$BUILD/deps"
DEPS_SIG="$BUILD/deps.sig"
deps_inputs() {
    ls -l "$BT/lib/apksigner.jar" "$PLATFORM" 2>/dev/null
    for j in "$ROOT"/libs/*.jar; do ls -l "$j"; done
    echo "$BT"
}
SIG_NOW="$(deps_inputs | md5sum 2>/dev/null | awk '{print $1}')"
if [ -z "$SIG_NOW" ]; then SIG_NOW="$(deps_inputs | cksum | awk '{print $1}')"; fi

if [ -f "$DEPS_SIG" ] && [ "$(cat "$DEPS_SIG")" = "$SIG_NOW" ] \
   && [ -n "$(ls -A "$DEPS_DIR" 2>/dev/null)" ]; then
    echo "      依赖 dex 未变化，复用缓存（跳过 d8 依赖阶段）"
else
    echo "      重建依赖 dex（apksig + smali 引擎 + jadx + Termux…），仅依赖变化时执行"
    rm -rf "$DEPS_DIR"
    mkdir -p "$DEPS_DIR"
    {
        echo "-cp \"$BT/lib/d8.jar\""
        echo "com.android.tools.r8.D8"
        echo "--release --min-api 21"
        echo "--lib \"$PLATFORM\""
        echo "--output \"$DEPS_DIR\""
        echo "\"$BT/lib/apksigner.jar\""
        for j in "$ROOT"/libs/*.jar; do echo "\"$j\""; done
    } > "$BUILD/deps.args"
    "$JAVA_BIN" @"$BUILD/deps.args"
    echo "$SIG_NOW" > "$DEPS_SIG"
fi

# 应用 dex：只编译工程自身 class（libs 作为 --lib 不打包进来）
ARGS="$BUILD/java.args"
{
    echo "-cp \"$BT/lib/d8.jar\""
    echo "com.android.tools.r8.D8"
    echo "--release --min-api 21"
    echo "--lib \"$PLATFORM\""
    for j in "$ROOT"/libs/*.jar; do echo "--lib \"$j\""; done
    echo "--output \"$BUILD/dex\""
    find "$BUILD/obj/com/hex91/file" "$BUILD/obj/jadx" "$BUILD/obj/com/termux" "$BUILD/obj/androidx" "$BUILD/obj/io" -name "*.class" -printf '"%p"\n'
} > "$ARGS"
"$JAVA_BIN" @"$ARGS"

echo "[6/7] zipalign + 打包原生库..."
cd "$BUILD"
# dex 段（DEFLATED）：应用 dex 在前、依赖 dex 在后，统一连续编号 classes*.dex
"$PY" -c "
import zipfile, glob, re
def key(p):
    m = re.search(r'classes(\d*)\.dex$', p)
    return int(m.group(1) or 1)
app = sorted(glob.glob('dex/classes*.dex'), key=key)
dep = sorted(glob.glob('deps/classes*.dex'), key=key)
z = zipfile.ZipFile('base.apk', 'a', zipfile.ZIP_DEFLATED)
i = 1
for f in app + dep:
    z.write(f, 'classes.dex' if i == 1 else 'classes%d.dex' % i)
    i += 1
z.close()
print('      dex: app=%d deps=%d total=%d' % (len(app), len(dep), len(app) + len(dep)))
"
# assets/（含重签名用 keystore hexfile.p12）
if [ -d "$ROOT/assets" ]; then
    "$PY" -c "import zipfile,glob,os; z=zipfile.ZipFile('base.apk','a',zipfile.ZIP_DEFLATED); [z.write(f, 'assets/'+os.path.basename(f)) for f in glob.glob('$ROOT/assets/*')]; z.close()"
fi
# native 库（7-Zip 引擎，RAR4/RAR5；Zstd 引擎）：STORED 写入 lib/arm64-v8a/
if [ -f "$ROOT/native/lib7-Zip-JBinding.so" ]; then
    "$PY" -c "import zipfile; z=zipfile.ZipFile('base.apk','a',zipfile.ZIP_STORED); z.write(r'$ROOT/native/lib7-Zip-JBinding.so','lib/arm64-v8a/lib7-Zip-JBinding.so'); z.close()"
fi
if [ -f "$ROOT/native/libzstd-jni-1.5.5-11.so" ]; then
    "$PY" -c "import zipfile; z=zipfile.ZipFile('base.apk','a',zipfile.ZIP_STORED); z.write(r'$ROOT/native/libzstd-jni-1.5.5-11.so','lib/arm64-v8a/libzstd-jni-1.5.5-11.so'); z.close()"
fi
# 终端 NDK 原生库（libtermux.so，Termux PTY 桥，Apache-2.0）：按 ABI 打包进 lib/<abi>/
for abi in arm64-v8a armeabi-v7a; do
    if [ -f "$ROOT/native/lib/$abi/libtermux.so" ]; then
        "$PY" -c "import zipfile; z=zipfile.ZipFile('base.apk','a',zipfile.ZIP_STORED); z.write(r'$ROOT/native/lib/$abi/libtermux.so','lib/$abi/libtermux.so'); z.close()"
    fi
done
"$BT/zipalign${EXE}" -f -p 4 base.apk "$OUT/HexFile-unsigned.apk"

echo "[7/7] apksigner sign..."
KS="$ROOT/hexfile.keystore"
KS_PASS="${HEXFILE_KEYSTORE_PASS:-android}"
KS_ALIAS="${HEXFILE_KEYSTORE_ALIAS:-hex91}"
if [ -n "$HEXFILE_KEYSTORE_BASE64" ]; then
    # CI 注入：base64 解码为 keystore
    echo "$HEXFILE_KEYSTORE_BASE64" | base64 -d > "$KS"
elif [ ! -f "$KS" ]; then
    # 本地无密钥时自动生成调试密钥（storepass/keypass 均为 android）
    "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=HexFile, O=hex91, C=CN" 2>/dev/null
fi
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out "$OUT/HexFile-$VER.apk" "$OUT/HexFile-unsigned.apk"

echo ""
echo "BUILD OK -> $OUT/HexFile-$VER.apk"
ls -lh "$OUT/HexFile-$VER.apk" | awk '{print "size:", $5}'
echo "耗时: $((SECONDS / 60)) 分 $((SECONDS % 60)) 秒"
