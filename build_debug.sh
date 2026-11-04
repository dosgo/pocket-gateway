#!/usr/bin/env bash
# 打 debug 版网关 APK：debuggable=true + 用 Android 标准 debug keystore 签名
# 用途：某些 ROM（如中兴）会拦自签 release 包的 adb 安装，debug 包能装上。
# 用法: bash build_debug.sh
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="/c/Users/DosGo/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-33/android.jar"
JAVA_HOME_DIR="/c/Program Files/Android/Android Studio/jbr"
JAVAC="$JAVA_HOME_DIR/bin/javac.exe"
JAVA="$JAVA_HOME_DIR/bin/java.exe"
KEYTOOL="$JAVA_HOME_DIR/bin/keytool.exe"
PY="C:/Users/DosGo/.workbuddy/binaries/python/versions/3.13.12/python.exe"

MIN_SDK=24
TARGET_SDK=33
VERSION_CODE=21
VERSION_NAME="2.9-debug"

BUILD="build_dbg"
W() { cygpath -w "$1"; }

export JAVA_HOME="$(cygpath -w "$JAVA_HOME_DIR")"
export PATH="$JAVA_HOME_DIR/bin:$PATH"

cd "$APP_DIR"
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

AAR="$APP_DIR/app/libs/fougw.aar"
if [ ! -f "$AAR" ]; then
  echo "缺少 $AAR —— 先跑 bash build_aar.sh 生成 gomobile 绑定库" >&2
  exit 1
fi

echo "[0/8] 解包 gomobile AAR"
rm -rf "$BUILD/aar" app/src/main/jniLibs
mkdir -p "$BUILD/aar"
"$PY" - "$(W "$AAR")" "$(W $BUILD/aar)" "$(W app/src/main/jniLibs)" <<'PYEOF'
import os, sys, zipfile
aar, outdir, jnidir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(aar) as z:
    z.extractall(outdir)
    n_so = 0
    for n in z.namelist():
        if n.startswith('jni/') and n.endswith('.so'):
            abi = n.split('/')[1]
            d = os.path.join(jnidir, abi)
            os.makedirs(d, exist_ok=True)
            with z.open(n) as f, open(os.path.join(d, os.path.basename(n)), 'wb') as o:
                o.write(f.read())
            n_so += 1
print("   so 共 %d 个" % n_so)
PYEOF

echo "[1/8] aapt2 compile 资源"
"$BT/aapt2.exe" compile --dir app/src/main/res -o "$BUILD/res.zip"

"$PY" - "$(W app/src/main/AndroidManifest.xml)" "$(W $BUILD/AndroidManifest.xml)" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
xml = open(src, encoding='utf-8').read()
xml = xml.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.dosgo.gateway">')
open(dst, 'w', encoding='utf-8').write(xml)
PYEOF

echo "[2/8] aapt2 link（--debug-mode：manifest 里打上 debuggable）"
"$BT/aapt2.exe" link \
  -o "$BUILD/base.apk" \
  -I "$(W "$ANDROID_JAR")" \
  --manifest "$(W $BUILD/AndroidManifest.xml)" \
  -R "$BUILD/res.zip" \
  --java "$(W $BUILD/gen)" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  --debug-mode \
  --auto-add-overlay

echo "[3/8] javac"
CP="$(W "$ANDROID_JAR");$(W $BUILD/aar/classes.jar);$(W "$APP_DIR/libs/zxing-core-3.5.3.jar")"
find app/src/main/java "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -encoding UTF-8 --release 11 -nowarn \
  -cp "$CP" -g \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "[4/8] d8（debug：保留行号，不压缩）"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8.bat" --debug --min-api $MIN_SDK --lib "$(W "$ANDROID_JAR")" \
  --output "$BUILD/dex" @"$BUILD/classes.txt" "$(W $BUILD/aar/classes.jar)" "$(W "$APP_DIR/libs/zxing-core-3.5.3.jar")"

echo "[5/8] 写入 classes.dex 与 so"
"$PY" - "$(W $BUILD/base.apk)" "$(W $BUILD/dex/classes.dex)" "$(W app/src/main/jniLibs)" <<'PYEOF'
import os, shutil, sys, zipfile
apk, dex, jni = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = apk + ".tmp"
libs = []
for abi in sorted(os.listdir(jni)):
    d = os.path.join(jni, abi)
    if not os.path.isdir(d):
        continue
    for f in sorted(os.listdir(d)):
        libs.append((os.path.join(d, f), 'lib/%s/%s' % (abi, f)))
with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == 'classes.dex':
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, 'classes.dex')
    for src, name in libs:
        zout.write(src, name)
shutil.move(tmp, apk)
print("   apk = %.1fMB" % (os.path.getsize(apk) / 1048576))
PYEOF

echo "[6/8] zipalign + debug 签名"
"$BT/zipalign.exe" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

# Android 官方 debug 证书：alias=androiddebugkey，口令都是 android
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$HOME/.android"
  "$KEYTOOL" -genkeypair -v -keystore "$(W "$KS")" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
  echo "   已生成 ~/.android/debug.keystore"
fi

OUT="$APP_DIR/Gateway-$VERSION_NAME.apk"
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" sign \
  --ks "$(W "$KS")" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version $MIN_SDK \
  --out "$(W "$OUT")" "$BUILD/aligned.apk"

echo "[7/8] 校验"
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" verify --print-certs "$(W "$OUT")" | head -6
"$BT/aapt2.exe" dump badging "$(W "$OUT")" 2>/dev/null | grep -E "^package|application-debuggable|sdkVersion|targetSdkVersion"

echo
echo "OK -> $OUT"
ls -lh "$OUT"
