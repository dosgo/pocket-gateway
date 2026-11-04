#!/usr/bin/env bash
# 手工打包网关 APK（不依赖 Gradle / 网络下载）
# 用法: bash build_apk.sh
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
VERSION_CODE=24
VERSION_NAME="3.2"

# Windows 原生工具需要 Windows 风格路径
W() { cygpath -w "$1"; }

export JAVA_HOME="$(cygpath -w "$JAVA_HOME_DIR")"
export PATH="$JAVA_HOME_DIR/bin:$PATH"

cd "$APP_DIR"
rm -rf build
mkdir -p build/res build/gen build/classes build/dex

AAR="$APP_DIR/app/libs/fougw.aar"
if [ ! -f "$AAR" ]; then
  echo "缺少 $AAR —— 先跑 bash build_aar.sh 生成 gomobile 绑定库" >&2
  exit 1
fi

echo "[0/8] 解包 gomobile AAR（classes.jar + libgojni.so）"
rm -rf build/aar app/src/main/jniLibs
mkdir -p build/aar
"$PY" - "$(W "$AAR")" "$(W build/aar)" "$(W app/src/main/jniLibs)" <<'PYEOF'
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
            print("   %s -> %s (%.1fMB)" % (n, abi, os.path.getsize(os.path.join(d, os.path.basename(n))) / 1048576))
print("   so 共 %d 个, classes.jar = %s" % (n_so, 'classes.jar' in z.namelist()))
PYEOF

echo "[1/8] aapt2 compile 资源"
"$BT/aapt2.exe" compile --dir app/src/main/res -o build/res.zip

# aapt2 需要 manifest 里带 package（Gradle 8+ 又要求不带，所以这里生成临时副本）
"$PY" - "$(W app/src/main/AndroidManifest.xml)" "$(W build/AndroidManifest.xml)" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
xml = open(src, encoding='utf-8').read()
xml = xml.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.dosgo.gateway">')
open(dst, 'w', encoding='utf-8').write(xml)
PYEOF

echo "[2/8] aapt2 link 生成资源 APK + R.java"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$(W "$ANDROID_JAR")" \
  --manifest "$(W build/AndroidManifest.xml)" \
  -R build/res.zip \
  --java "$(W build/gen)" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "[3/8] javac 编译 Java 源码"
# gomobile 生成的 Java 类在 aar 的 classes.jar 里，编译期要能解析
CP="$(W "$ANDROID_JAR");$(W build/aar/classes.jar);$(W "$APP_DIR/libs/zxing-core-3.5.3.jar")"
find app/src/main/java build/gen -name '*.java' > build/sources.txt
"$JAVAC" -encoding UTF-8 --release 11 -nowarn \
  -cp "$CP" \
  -d build/classes \
  @build/sources.txt

echo "[4/8] d8 生成 dex（含 gomobile 绑定类）"
find build/classes -name '*.class' > build/classes.txt
"$BT/d8.bat" --release --min-api $MIN_SDK --lib "$(W "$ANDROID_JAR")" \
  --output build/dex @build/classes.txt "$(W build/aar/classes.jar)" "$(W "$APP_DIR/libs/zxing-core-3.5.3.jar")"

echo "[5/8] 塞入 classes.dex 与 FOU 引擎（jniLibs）"
"$PY" - "$(W build/base.apk)" "$(W build/dex/classes.dex)" "$(W app/src/main/jniLibs)" <<'PYEOF'
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
print("   dex + %d 个 so 已写入, apk = %.1fMB" % (len(libs), os.path.getsize(apk) / 1048576))
PYEOF

echo "[6/8] zipalign + 签名"
"$BT/zipalign.exe" -f -p 4 build/base.apk build/aligned.apk

KS="$APP_DIR/keystore/gateway.jks"
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  "$KEYTOOL" -genkeypair -v -keystore "$(W "$KS")" -storepass gateway123 -keypass gateway123 \
    -alias gateway -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Gateway, OU=Dev, O=DosGo, L=CN, ST=CN, C=CN" > /dev/null 2>&1
  echo "   已生成签名密钥 keystore/gateway.jks"
fi

OUT="$APP_DIR/Gateway-$VERSION_NAME.apk"
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" sign \
  --ks "$(W "$KS")" --ks-pass pass:gateway123 --key-pass pass:gateway123 \
  --min-sdk-version $MIN_SDK \
  --out "$(W "$OUT")" build/aligned.apk

echo "[7/8] 校验签名"
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" verify --print-certs "$(W "$OUT")" | head -5

echo
echo "OK -> $OUT"
ls -lh "$OUT"
