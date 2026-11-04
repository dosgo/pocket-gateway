#!/usr/bin/env bash
# 用 gomobile bind 把 Go 网关编成 AAR（进程内绑定，替代子进程模式）
# 用法: bash build_aar.sh
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GOROOT="/d/toolchain/go"
GOPATH="/d/toolchain/gopath"
NDK="${ANDROID_NDK_HOME:-/d/toolchain/ndk/android-ndk-r27c}"
SDK="/c/Users/DosGo/AppData/Local/Android/Sdk"

export GOROOT
export GOPATH
export PATH="$GOROOT/bin:$GOPATH/bin:$PATH"
export GOFLAGS=-mod=mod
export ANDROID_HOME="$(cygpath -w "$SDK")"
export ANDROID_NDK_HOME="$(cygpath -w "$NDK")"
# gomobile 要用 javac 编译它生成的绑定类
JBR="/c/Program Files/Android/Android Studio/jbr"
export JAVA_HOME="$(cygpath -w "$JBR")"
export PATH="$JBR/bin:$PATH"
# 拉模块走本机代理（直连 proxy.golang.org 不通）
export HTTPS_PROXY=http://127.0.0.1:10808
export HTTP_PROXY=http://127.0.0.1:10808
# 临时目录里的 go mod tidy 会重下整棵依赖树；sumdb 校验在 Windows 临时目录下会报
# "Access is denied"，关掉校验；模块代理用国内源（proxy.golang.org 经本机代理经常 502）
export GOPROXY=https://goproxy.cn,direct
export GOSUMDB=off
export GONOSUMDB='*' 

if [ ! -d "$NDK" ]; then
  echo "找不到 NDK: $NDK" >&2
  echo "下载：curl -L -o ndk.zip https://dl.google.com/android/repository/android-ndk-r27c-windows.zip" >&2
  exit 1
fi

echo "NDK   = $ANDROID_NDK_HOME"
echo "ANDROID_HOME = $ANDROID_HOME"
command -v gomobile > /dev/null || { echo "缺 gomobile: go install golang.org/x/mobile/cmd/gomobile@latest" >&2; exit 1; }

mkdir -p "$APP_DIR/app/libs"
cd "$APP_DIR/gostack"

echo "=== gomobile bind → app/libs/fougw.aar ==="
gomobile bind \
  -target=android/arm64,android/arm,android/amd64 \
  -androidapi 21 \
  -trimpath \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$(cygpath -w "$APP_DIR/app/libs/fougw.aar")" \
  ./fougw

echo
ls -lh "$APP_DIR/app/libs/fougw.aar"
echo "=== AAR 内容 ==="
C:/Users/DosGo/.workbuddy/binaries/python/versions/3.13.12/python.exe -c "
import zipfile, sys
z = zipfile.ZipFile(r'$(cygpath -w "$APP_DIR/app/libs/fougw.aar")')
for i in z.infolist():
    print('  %-40s %9d' % (i.filename, i.file_size))
"
