# 技术笔记

实现细节与实测记录。面向想改代码、或者好奇「为什么不能 XXX」的人；只想用的话看 [README](../README.md)。

记录的都是真机上跑出来的结论（REDMI Turbo 5 Max / HyperOS / Android 17，ZTE 7530N / Android 11），
包括一些绕不过去的系统限制 —— 遇到同样问题时能省一晚上。

---

## 三个入口

| 协议 | 默认端口 | 用法 |
|---|---|---|
| SOCKS5 | 1080/tcp | 系统代理 / SwitchyOmega / curl `-x socks5h://IP:1080`，支持 CONNECT 与 UDP ASSOCIATE |
| HTTP | 8080/tcp | 系统代理填 `IP:8080`；支持 CONNECT 隧道（HTTPS）与明文绝对 URI；浏览器打开 `http://IP:8080` 有状态页 |
| FOU | 5555/udp | Foo-over-UDP（IP-in-UDP, ipproto 4）：收原始 IPv4 包，做真正的网络层网关 |

端口可在界面上改，三个端口不能重复。点「启动网关」后是前台服务 + WakeLock，锁屏也不断。

## 自带热点（Local-Only Hotspot）

没有路由器、或者手机根本没连 Wi-Fi 时，可以让 App 自己开一个热点，电脑连上就能用代理：

1. 填 SSID / 密码（至少 8 位），点「开启热点」；
2. 卡片里会显示**实际生效**的 SSID、密码、热点网段本机 IP（如 `10.203.135.177`），并画一个 Wi-Fi 二维码，另一台手机扫一下直接连；
3. 客户端连上热点后，代理指向卡片里的「网关地址」+ 服务端口即可（点状态文字可复制全部连接信息）。

实现上用的是 `WifiManager#startLocalOnlyHotspot`，也就是 **local-only AP**：只建 Wi-Fi，不做系统级 NAT 共享 ——
正好符合「客户端走代理上网」的用法，也是普通应用唯一能在代码里一键开 AP 的形态（不需要去设置里点「个人热点」）。

**关于固定 SSID（重要）**：Android 不允许第三方应用给 Hotspot 指定 SSID。
虽然我们反射调用了隐藏的 `startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)`，并且调用成功，
但框架在 `WifiServiceImpl` 里会把非特权应用的 SSID/密码换成随机的（实测 HyperOS / Android 17 得到 `AndroidShare_xxxx`），
因为自定义 SSID 需要 `NETWORK_SETTINGS` 签名权限（`pm grant` 也授不了，实测报 "not a changeable permission type"）。
所以：**SSID 每次开都会变**，App 会把真实 SSID 显示出来并给二维码，连接不受影响。
真要用固定名字，只能 root 或改用系统「个人热点」，二者都不在本 App 范围内。

命令行/自动化也能控制（Tasker、快捷指令都可以）：

```bash
adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd hotspot_on
adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd hotspot_off
adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd start    # 启动网关
adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd stop
```

权限：`CHANGE_WIFI_STATE`、`ACCESS_FINE_LOCATION`、`NEARBY_WIFI_DEVICES`（首次点开热点会申请）。

## Wi-Fi Direct 固定组（SSID 不随机，实测可行）

热点（LOHS）的 SSID 由系统随机生成，这是框架行为，普通应用改不了。但 **Wi-Fi Direct 的 GO 可以自己指定 SSID**：

- 用的是**公开 API**：`WifiP2pConfig.Builder#setNetworkName(String)` / `setPassphrase(String)`（API 29+，不需要反射）
- P2P 规范强制 SSID 以 `DIRECT-xy` 开头（x/y 取自字母数字），所以名字是 `DIRECT-gwGateway` —— 前缀两个字符也是我们写死的，**整体完全固定**
- 实测（2602BRT18C / Android 17）：两次建组都是 `SSID=DIRECT-gwGateway  PASS=gateway888`，本机在 `p2p-p2p0-N` 上拿到 `192.168.49.1/24`，客户端由系统 DHCP 分到 `192.168.49.100~254`
- 界面用法：先打开 Wi-Fi 开关（不用连任何网络）→ 点「Wi-Fi Direct 固定组」；此时热点按钮会置灰（同一射频不能并存）
- 客户端**当普通 WPA2 Wi-Fi 连**即可（在 Wi-Fi 列表里选 `DIRECT-gwGateway`，输密码），不需要走「Wi-Fi Direct 配对」流程

代价与坑：

- P2P 组**没有互联网共享**（正合我们的用法：客户端本来就该走代理），但 Windows / Android / iOS 会提示「已连接，无互联网」，Android 可能弹窗问是否保持连接，选「保持」
- 与系统热点互斥：开 P2P 组时不能同时开 LOHS 热点
- 客户端兼容性：Android 手机基本都能连；iPhone 能否连 `DIRECT-` 开头的 SSID 未验证；Windows 笔记本请以实际扫描为准

## 自己写 DHCP 服务下发代理：做不到（有硬证据）

两条都是实测，不是推测：

| 障碍 | 实测结果 |
|---|---|
| 端口特权 | 内核 `net.ipv4.ip_unprivileged_port_start = 1024`，应用进程 `bind(67)` → `BindException: EACCES (Permission denied)`（同一进程 `bind(1067)` 正常） |
| 端口占用 | `/proc/net/udp` 里 `00000000:0043` 恒在，持有者 uid 1073 = NetworkStack 的 DHCP 服务；热点和 P2P 组都由它服务 |

顺带一条：**80 端口也 < 1024**，所以「伪装成 captive portal 自动弹页面」这条路同样起不来。

结论：无 root 环境下，`DHCP option 252`（WPAD）与自带 DHCP 都不可行；能给客户端下发代理配置的唯一落地方式就是 **PAC URL**（见下一节）。

## 客户端「DNS 解析出错」是怎么回事

热点/P2P 组里的客户端拿到的 DNS 就是网关地址（如 `192.168.49.1`），但系统那个 dnsmasq 是
`--no-resolv` 启动、**没有任何上游 `--server=`**，实测查 `www.baidu.com` 回 `0x8185`（RCODE=5 REFUSED，ANCOUNT=0）。
热点模式和 P2P 模式都一样 —— 这是 local-only 组的固有行为，不是代理的 bug。

推论（很重要）：

- 凡是**绕过代理**的流量都会失败：客户端本地 DNS、系统联网探测、QUIC/UDP。表现就是「已连接，无互联网」「DNS 解析出错」。
- 正确姿势是**把域名交给网关解析**：客户端用 **HTTP 代理**或 **PAC**（`http://手机IP:8080/proxy.pac`）。
- 只配 SOCKS5 时，Windows 和一部分 App 会在本地解析 DNS → 必然报 DNS 错。Chrome/curl 请用 `socks5h://`（远程解析）。
- 想「整台设备透明上网（含 DNS）」，只有两条：客户端是 Linux/OpenWrt 时走 **FOU**，或者 root 后接管 iptables。

v1.6 起，失败会讲人话：`Relay.connectAny()` 逐地址重试（IPv4 优先、单个 6 秒，不再死等 15 秒）；
`UnknownHostException` 单独处理 —— HTTP 返回带原因与做法的 502 页面，SOCKS5 回 `0x04 Host unreachable`。

### 网关侧解析：多路上游兜底（v1.7）

客户端不做解析、全靠网关，所以网关这一层必须稳。但这台手机长期挂着 NekoBox(VPN)，
系统解析器会把查询交给 VPN 自己的 DNS（`tun0` 上的 `172.19.0.2`），它一抖网关就「解析失败」。
`Dns.java` 因此做两层：

1. 系统解析，**3 秒硬超时**（不再死等）；
2. 兜底：自己按 DNS 协议向 `223.5.5.5` / `119.29.29.29` / `114.114.114.114` / `8.8.8.8` 依次发 UDP 查询，
   结果过滤掉 `198.18.0.0/15`（sing-box 之类 fake-ip 常占）、`127/169.254/0.*`。

结果缓存 2 分钟。命中兜底时日志会打 `DNS 兜底 example.com via 223.5.5.5 -> 1.2.3.4`。

### 现场诊断：页面 `/dns` + 「最近的请求」（v1.7）

```bash
curl http://<手机IP>:8080/dns?host=www.baidu.com
# host: www.baidu.com
# result: OK  (4ms)
#   183.2.172.177
# trace:
```

```bash
curl 'http://<手机IP>:8080/dns?host=no-such-host-xyz123.com'
# result: FAIL  (682ms)
# reason: ... 系统与上游 DNS 都查不到
# trace: no-such-host-xyz123.com: 系统解析超时/失败；223.5.5.5 无结果/超时；...
```

状态页 `http://<手机IP>:8080/` 底部新增「最近的请求」：列出最近 10 条代理日志。
一句话判据：

| 现象 | 结论 |
|---|---|
| 客户端打开网页，状态页「最近的请求」里**没有**新增 `HTTP GET/CONNECT` | 请求根本没到网关 —— 代理没生效 / 端口填错 / 连的不是这个网络 |
| 有 `HTTP CONNECT 域名:443`，但客户端报错 | 看 `/dns?host=域名` 的结果：`OK` 说明是别的问题，`FAIL` 才是解析问题 |
| `/dns` 返回 `FAIL` 且 `trace` 里四个上游全超时 | 手机自身没网（VPN 挂了 / 飞行模式 / 无信号） |
| `/dns` 返回 `FAIL` 但 trace 显示上游正常应答无记录 | 域名真的不存在（例：`st.ztems.com` 在公网就是 NXDOMAIN） |

## Android 客户端填了 HTTP 代理还是报 DNS 错

先分清「代理没生效」和「网关解析不出来」。做两步：

1. 客户端浏览器打开 `http://192.168.49.1:8080`（换成你实际的热点网段 IP）。
   打不开 → 网络层就不通，跟代理无关；能打开 → 往下看。
2. 在那个页面的「最近的请求」里找有没有你刚发的请求，然后访问 `http://192.168.49.1:8080/dns?host=www.baidu.com`。

常见原因（按出现频率）：

| 原因 | 现象 | 处理 |
|---|---|---|
| 端口填成 1080 | SOCKS5 端口被当成 HTTP 用，浏览器报连接被重置 | HTTP 代理端口是 `8080` |
| 主机名填了域名或留空 | 代理不生效，本地解析失败 | 只填 IP：`192.168.49.1` |
| 保存后没重连 Wi-Fi | 配置没应用 | 断开重连一次该 Wi-Fi |
| 填成蜂窝 IP（如 `10.2.83.247`） | 客户端连不上代理 | 用热点/P2P 网段的 IP |
| 系统探针失败被误读 | 提示「DNS 解析出错」但网页其实能开 | Android 原生**不支持 PAC**，只能用静态代理；探针不走代理必然失败，忽略提示即可 |

顺带一条实测结论：日志里出现 `st.ztems.com` 解析失败不代表网关坏了 ——
该域名在公网就是 NXDOMAIN（阿里 DNS 直接答「不存在」），是某个 App 在探测死域名。

## 客户端自动配置：PAC（能做的和做不到的）HTTP 端口现在会下发 PAC：

```bash
curl http://<手机IP>:8080/proxy.pac     # 也是 /wpad.dat
```

内容是标准 `FindProxyForURL`，内网段走 DIRECT，其余 `PROXY ip:8080; SOCKS5 ip:1080`。
客户端只要在「自动配置脚本 / Automatic proxy configuration」里填这一个 URL，HTTP 和 SOCKS5 一起生效。
状态页 `http://<手机IP>:8080/` 上也会直接显示这个地址。

**做不到的：DHCP 自动下发（option 252 / WPAD）**。热点模式下的 DHCP 服务器属于系统的
`Tethering`/DHCP 服务，第三方应用不能给它加 option，也不能在 67 端口起自己的（端口被占、且需要特权）。
想「客户端连上 Wi-Fi 就自动会走代理」只有三条路：root 后自己当 DHCP/网关、改用系统热点（SSID 固定但同样是系统 DHCP）、
或者把手机换成一个能配 DHCP 252 的路由器（OpenWrt）。

## FOU 引擎：gVisor netstack（进程内绑定）

FOU 是 Linux 内核自带的隧道封装：UDP 载荷里**直接就是原始 IPv4 报文**，没有额外头（等价 `encap fou` + `ipproto 4`）。

手机侧没有 root，拿不到 TUN，也无法让内核转发，所以必须用户态终结。这里用 gVisor 的 netstack（tun2socks / sing-box 用的同一套）：

- 原始 IPv4 包注入 `channel.Endpoint`，NIC 开 **promiscuous + spoofing**（隧道里目的地址是任意的，回包要用那个地址当源）
- **TCP** → gVisor 完整状态机（SACK / 拥塞控制 / 窗口缩放 / RTO），应用侧拿到普通 `net.Conn`，再接真实 socket
- **UDP** → `udp.Forwarder` + 真实 `DialUDP`
- **ICMP echo** → 网关自己应答（可以 ping 通）
- 隧道对端按「内层 IP:端口」索引，多台客户端并发不串包

### 为什么是 gomobile bind 而不是子进程

同一个 Go 包有两种产出，都用 `gostack/fougw` 这一份代码：

| | 子进程（1.1 及以前） | 进程内绑定（现在） |
|---|---|---|
| 产物 | 静态 ELF 塞进 `jniLibs`，靠 `nativeLibraryDir` 执行 | `libgojni.so` + `classes.jar`（AAR） |
| 需要 NDK | 否 | 是（cgo） |
| 崩溃隔离 | 有（子进程死了 App 还活着） | 无（同进程，异常会带崩 App） |
| 拿 Java 的 fd | 不行 | 可以（以后做 VpnService 全局模式必须） |
| IPC | stdout JSON 行 | 直接函数调用 + 内存日志缓冲 |

选进程内绑定就是为最后两条：直接调用，以及将来能把 VpnService 的 TUN fd 递进去。代价是没有崩溃隔离，所以 `fougw` 包里每个入口（recvLoop / inject / handleTCP / relayTCP…）都包了 `recoverLog`，把 panic 挡在包内并记进日志。

### Linux 客户端（推荐）

```bash
# 1) 开一个 FOU 端口，声明内层是 IPv4
sudo ip fou add port 5555 ipproto 4

# 2) 建 IPIP 隧道，用 FOU 封装，远端指向手机 IP
sudo ip link add gw0 type ipip remote 192.168.1.50 local 192.168.1.100 \
     encap fou encap-sport auto encap-dport 5555

# 3) 配地址、拉起、改路由（0/1 + 128/1 两半默认路由，不动原默认路由）
sudo ip addr add 10.0.0.2/24 dev gw0
sudo ip link set gw0 up mtu 1280
sudo ip route add 0.0.0.0/1 dev gw0
sudo ip route add 128.0.0.0/1 dev gw0

# 验证
ping 8.8.8.8
curl -s https://ipinfo.io/ip     # 应该显示手机所在网络的出口 IP

# 撤掉
sudo ip route del 0.0.0.0/1; sudo ip route del 128.0.0.0/1
sudo ip link del gw0; sudo ip fou del port 5555
```

> 建议隧道 MTU ≤ 1280，服务端 MSS 也压到 1200，避免内层分片。
> macOS / Windows 没有原生 FOU，需要自己写客户端；报文格式见 `gostack/testclient/main.go`（手工拼 IP/TCP/UDP/ICMP 的完整示例）。

## 目录结构

```
app/src/main/java/com/dosgo/gateway/
├── MainActivity.java        主界面：本机 IP、端口配置、启停、日志
├── GatewayService.java      前台服务：拉起三个服务器 + 通知 + WakeLock
├── FouEngine.java           FOU 绑定层：调 gomobile 生成的 fougw.Fougw，轮询日志/统计
└── net/
    ├── Socks5Server.java    SOCKS5（RFC1928，CONNECT + UDP ASSOCIATE）
    ├── HttpProxyServer.java HTTP 代理（CONNECT + 绝对 URI + 状态页）
    ├── NetUtils.java        网卡 / IPv4 枚举
    ├── Relay.java           双向流中继
    └── Logs.java            环形日志

gostack/                     Go 模块（module fougw）
├── fougw/                   可绑定包：gateway.go / netstack.go / log.go / api.go
├── main.go                  CLI 外壳（桌面调试、adb 真机测试用）
└── testclient/              FOU 报文测试客户端（自己拼 IP/TCP/UDP）
```

## 构建

两步：先出 AAR，再打 APK。

```bash
bash build_aar.sh     # gomobile bind → app/libs/fougw.aar（需 NDK + JDK）
bash build_apk.sh     # 解 AAR、编 dex、塞 so、签名 → Gateway-1.2.apk
```

工具链位置（本机）：

| 组件 | 路径 |
|---|---|
| Go | `D:\toolchain\go` |
| NDK r27c | `D:\toolchain\ndk\android-ndk-r27c` |
| gomobile / gobind | `D:\toolchain\gopath\bin` |
| JDK | Android Studio 自带 JBR |

`build_aar.sh` 里几个必要设置，换成别的机器也要注意：

- `JAVA_HOME` 必须指向带 `javac` 的 JDK（gomobile 要编译它生成的绑定类）
- `GOSUMDB=off`：gomobile 在 Windows 临时目录里建 `pkg/sumdb` 会报 `Access is denied`
- `GOPROXY=https://goproxy.cn,direct`：临时目录的 `go mod tidy` 会重建整棵依赖树，`proxy.golang.org` 经本机代理经常 502

APK 打包用 SDK 里的 aapt2 / d8 / zipalign / apksigner，不依赖 Gradle。用 Android Studio 打开也走标准 Gradle 工程（AGP 8.13 / compileSdk 33 / Java 11）；手工打包时脚本会临时补上 manifest 的 `package` 属性（AGP 8+ 已禁止）。

签名密钥在 `keystore/gateway.jks`（口令 `gateway123`，仅测试用；上架请自行更换）。

## 测试

FOU 客户端要手工构造 IPv4 报文，所以桌面和真机都有一套：

```bash
# 桌面：先编 CLI 版网关和测试客户端
cd gostack
GOROOT=D:/toolchain/go CGO_ENABLED=0 GOOS=windows GOARCH=amd64 \
  go build -o /tmp/fougw.exe . && GOOS=windows go build -o /tmp/foutest.exe ./testclient
/tmp/fougw.exe -port 15555 &
/tmp/foutest.exe -gw 127.0.0.1:15555

# 真机：把客户端推到手机上，直接打 App 内的 5555
adb push foutest /data/local/tmp/ && adb shell /data/local/tmp/foutest -gw 127.0.0.1:5555
```

5 个用例：ICMP echo 应答、UDP 转发（真实 DNS 查询）、TCP 三次握手、TCP 承载 HTTP（约 18KB / 35 段）、FIN 正常关闭。

SOCKS5 / HTTP 用 curl 走 adb 转发：

```bash
adb forward tcp:11080 tcp:1080 && adb forward tcp:18080 tcp:8080
curl -x socks5h://127.0.0.1:11080 https://www.baidu.com
curl -x http://127.0.0.1:18080 --proxytunnel https://www.baidu.com
```

## 开机自启与「自动开 AP」

界面里有两个互斥的勾选（同一个 Wi-Fi 芯片只能当一种 AP，勾一个会把另一个取消）：

| 勾选项 | 存在哪 | 启动时做什么 |
| --- | --- | --- |
| 启动网关时自动开热点（Local-Only） | `gateway_hotspot.xml / auto` | 调 `HotspotController.start()`，SSID 可能被 ROM 改成 `AndroidShare_xxxx` |
| 启动网关时自动开固定名称热点（Wi-Fi Direct） | `gateway_p2p.xml / auto` | 调 `P2pController.start()`，SSID 固定 `DIRECT-gwGateway`，网关地址 `192.168.49.1` |

优先级：`GatewayService.startAll()` 里先看 Wi-Fi Direct，勾了就只开 P2P；否则再看 Local-Only 热点。两个都没勾就只起代理端口。

链路：`BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED` → `BootReceiver` → `startForegroundService(GatewayService)` → `startAll()` 按上面的优先级开 AP。

### 国产 ROM 的坑（HyperOS 实测）

- 必须在系统「自启动管理」里放行本应用，否则开机广播根本不会送到 `BootReceiver`（logcat 里连一条记录都没有，看起来像代码没生效）。App 里的「去系统里允许自启动」按钮会跳过去。
- **开机广播是延迟派发的**。本机（REDMI Turbo 5 Max / HyperOS / Android 17）实测：`sys.boot_completed=1` 在 23:01 左右，而 `BootReceiver` 收到 `BOOT_COMPLETED` 是 **23:04:42**，晚了约 3 分钟。中间这段时间 logcat 里毫无动静，不要以为自启失败。

### 自检命令

```bash
# 走一遍接收器 → 服务 → 开 AP 的完整链路（不用真重启）
adb shell am broadcast -a com.dosgo.gateway.action.START_NOW -n com.dosgo.gateway/.BootReceiver

# 看三个开关的持久化值
adb shell am start -n com.dosgo.gateway/.MainActivity --es cmd prefs
# 其它可用 cmd：auto_hs_on / auto_hs_off / auto_p2p_on / auto_p2p_off / boot_on / boot_off
```

`adb shell am broadcast -a android.intent.action.BOOT_COMPLETED` **不行**：这个广播是 protected，shell 会直接抛 `SecurityException: not allowed to send broadcast`，只能用上面的自定义 action 验证链路，真开机还得重启。

## 已知边界

- **没有崩溃隔离**：netstack 跑在 App 进程里，畸形报文触发的 panic 已被 `recoverLog` 拦住，但 Go runtime 级别的致命错误（如 cgo 崩溃）仍会带崩整个 App。
- APK 6.7 MB，三个 ABI 各带一个 6 MB 的 `libgojni.so`（解压后约 18 MB），只装 arm64 设备的话体积不占优。
- 只转发 IPv4；IPv6 报文直接丢弃。
- SOCKS5 无认证（局域网自用）。要暴露到公网请自行加认证或套隧道。
- Local-Only Hotspot 的 SSID 由系统随机生成（见上文）；Wi-Fi Direct 组的 SSID 可固定但受 `DIRECT-xy` 前缀约束。
- **不做 Wi-Fi 开关自愈**：把网关的 Wi-Fi 关掉再打开，热点 / P2P 组就没了，App 不会在后台自动重建，需要手动点一次按钮（v2.1 起移除了这套逻辑）。
- 开机自启只保证「开机时拉起一次」，运行过程中被系统杀掉不会自动复活（同样属于自愈范畴，未做）。
- 热点/P2P 网段本机 IP 现在会被优先选中：状态页与 PAC 里的地址会自动用 `ap*`/`p2p*` 网卡（之前会误用蜂窝 IP）。
- 热点与 Wi-Fi STA 并发取决于芯片能力：本机（HyperOS / Android 17）实测可在 4G + 热点同时工作。

## 许可

[MIT](LICENSE)。仅用于自有设备与自有网络；请自行确认当地法律法规与所用网络的服务条款。

