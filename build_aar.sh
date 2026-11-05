#!/usr/bin/env bash
# 用 gomobile bind 把 Go 网关编成 AAR（进程内绑定，替代子进程模式）
# 用法: bash build_aar.sh
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 本机工具链固定位置。不要从 ANDROID_HOME / ANDROID_NDK_HOME 读：那些用户环境变量
# 是 Windows 格式（D:\...），Bash 的 [ -d ] 判断会失败。系统级已把这些路径写进
# HKCU 环境变量，脚本里保留显式兜底，保证任何终端里都能直接跑。
GOROOT="/d/toolchain/go"
GOPATH="/d/toolchain/gopath"
NDK="/d/toolchain/ndk/android-ndk-r27c"
SDK="/c/Users/DosGo/AppData/Local/Android/Sdk"
JBR="/c/Program Files/Android/Android Studio/jbr"

export GOROOT GOPATH
export PATH="$GOROOT/bin:$GOPATH/bin:$JBR/bin:$PATH"
export GOFLAGS=-mod=mod
export ANDROID_HOME="$(cygpath -w "$SDK")"
export ANDROID_NDK_HOME="$(cygpath -w "$NDK")"
# gomobile 要用 javac 编译它生成的绑定类
export JAVA_HOME="$(cygpath -w "$JBR")"

# 宿主进程（WorkBuddy 更新器等）会把 TMP/TEMP/TMPDIR 指到自己的更新临时目录，
# gomobile 就把整个构建工作区放进去，go build 写 libgojni.so 时会挂死。
# 统一改到项目内。三个变量都给 Windows 原生程序（gomobile/go/javac）用
# Windows 格式；shell 侧需要临时目录时一律显式 -p "$TMP_POSIX"，不依赖 TMPDIR。
TMP_POSIX="$APP_DIR/.tmp"
mkdir -p "$TMP_POSIX"
export TMP="$(cygpath -w "$TMP_POSIX")"
export TEMP="$TMP"
export TMPDIR="$TMP"
export GOTMPDIR="$TMP"

# 绝不设 HTTPS_PROXY / HTTP_PROXY：本机 10808 端口即使在监听，代理本身也可能不通，
# gomobile 内部的 go mod tidy 一旦走它就无限等（实测 5m45s 零输出）。
# Go 有自己的模块代理 GOPROXY；依赖已全部缓存在 $GOPATH/pkg/mod，正常构建不联网。
unset HTTPS_PROXY HTTP_PROXY https_proxy http_proxy ALL_PROXY all_proxy
# 临时目录里的 go mod tidy 会重下整棵依赖树；sumdb 校验在 Windows 临时目录下会报
# "Access is denied"，关掉校验。默认离线：只用本地缓存，缺依赖时立刻报错而不是干等；
# 确实要补依赖时手动改成 GOPROXY=https://goproxy.cn,direct 再跑一次，仍不需要本机代理。
export GOPROXY=off
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
