#!/usr/bin/env bash
# 重启桌面测试服务器（编译纯 Java 部分）
set -e
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
J="/c/Program Files/Android/Android Studio/jbr"
S=app/src/main/java/com/dosgo/gateway
"$J/bin/javac.exe" -encoding UTF-8 --release 11 -nowarn -d build/test-classes \
  "$(cygpath -w $S/net/Logs.java)" \
  "$(cygpath -w $S/net/NetUtils.java)" \
  "$(cygpath -w $S/net/Relay.java)" \
  "$(cygpath -w $S/net/Socks5Server.java)" \
  "$(cygpath -w $S/net/HttpProxyServer.java)" \
  "$(cygpath -w $S/fou/Ip4.java)" \
  "$(cygpath -w $S/fou/TcpFlow.java)" \
  "$(cygpath -w $S/fou/FouServer.java)" \
  "$(cygpath -w test/DesktopMain.java)"
echo "compiled"
"/c/Program Files/Android/Android Studio/jbr/bin/java.exe" -cp build/test-classes DesktopMain
