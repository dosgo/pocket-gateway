package fougw

// WireGuard 接入层。
//
// 定位：和 FOU 并列的第四条腿 —— FOU 是自研的裸 IP-in-UDP（无加密、只有本 App
// 客户端能用），WireGuard 则是标准协议，任何平台的官方客户端扫码导入即可全流量
// 通过网关，TCP/UDP/ICMP 都覆盖。
//
// 复用点：解密后的内层 IP 包直接喂给同一个 netStack（promiscuous + spoofing +
// TCP/UDP forwarder），出站仍然是「netstack 里的连接 → 真实 socket」，所以
// 不需要 tun 设备、不需要 root、不需要 VpnService，和 FOU 共用一套转发逻辑。
//
// 对外只暴露 string/bool/error，符合 gomobile bind 的类型约束。

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// ---------------------------------------------------------------- 密钥

// WgGenKey 生成一对 Curve25519 密钥，返回 JSON：
//
//	{"private":"<base64>","public":"<base64>"}
//
// private 留在网关；public 写进客户端配置的 [Peer] PublicKey。
func WgGenKey() (string, error) {
	priv := make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		return "", err
	}
	// RFC 7748 clamp，和 wg genkey 一致
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	out := map[string]string{
		"private": base64.StdEncoding.EncodeToString(priv),
		"public":  base64.StdEncoding.EncodeToString(pub),
	}
	b, _ := json.Marshal(out)
	return string(b), nil
}

// WgPubKey 由私钥算公钥（客户端那边同理：私钥本地，公钥给网关）。
func WgPubKey(privB64 string) (string, error) {
	priv, err := base64.StdEncoding.DecodeString(strings.TrimSpace(privB64))
	if err != nil || len(priv) != 32 {
		return "", errors.New("私钥格式不对（需要 44 字符 base64）")
	}
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(pub), nil
}

func keyToHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return "", fmt.Errorf("密钥不是合法 base64: %v", err)
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("密钥长度 %d，应为 32 字节", len(raw))
	}
	return hex.EncodeToString(raw), nil
}

// ---------------------------------------------------------------- tun 适配器

// wgTun 把 WireGuard 的 tun.Device 接口怼到 netStack 上：
//   - Read  = netstack 想发出去的 IP 包（会被加密送回 peer）
//   - Write = peer 发来、已解密的 IP 包（注入 netstack 走转发）
type wgTun struct {
	ns      *netStack
	mtu     int
	events  chan tun.Event
	out     chan []byte // netstack 要发出去的 IP 包
	replies chan []byte // 本地自答的 ICMP echo reply（不进 netstack）
	closed  chan struct{}
	once    sync.Once
}

func newWgTun(ns *netStack, mtu int) *wgTun {
	t := &wgTun{
		ns:      ns,
		mtu:     mtu,
		events:  make(chan tun.Event, 4),
		out:     make(chan []byte, 256),
		replies: make(chan []byte, 64),
		closed:  make(chan struct{}),
	}
	t.events <- tun.EventUp

	// netstack 的 read 是阻塞的，而 ICMP 应答不走 netstack，两者都得能被 Read 取到，
	// 所以单独起一个泵把 netstack 的出包搬进 out，Read 用 select 同时等两路。
	go func() {
		for {
			pkt, ok := ns.read()
			if !ok {
				close(t.out)
				return
			}
			select {
			case t.out <- pkt:
			case <-t.closed:
				return
			}
		}
	}()
	return t
}

func (t *wgTun) File() *os.File { return nil } // 用户态设备，无 fd

func (t *wgTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, io.EOF
	}
	var pkt []byte
	select {
	case pkt = <-t.replies:
	case p, ok := <-t.out:
		if !ok {
			return 0, io.EOF
		}
		pkt = p
	case <-t.closed:
		return 0, io.EOF
	}
	n := copy(bufs[0][offset:], pkt)
	sizes[0] = n
	if n < len(pkt) {
		jlog("WG 出包被截断: %d -> %d", len(pkt), n)
	}
	return 1, nil
}

func (t *wgTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		if len(b) <= offset {
			continue
		}
		pkt := b[offset:]
		// ICMP echo：netstack 里没有 ICMP 协议，直接本地回一个 reply，
		// 这样客户端 ping 网关上看到的目标是通的（和 FOU 的行为一致）。
		if len(pkt) > 20 && pkt[0]>>4 == 4 && pkt[9] == 1 {
			if reply := icmpEchoReply(pkt); reply != nil {
				select {
				case t.replies <- reply:
				default: // 队列满了就丢，别卡住 WireGuard 的收包协程
				}
				continue
			}
		}
		t.ns.inject(pkt)
	}
	return len(bufs), nil
}

func (t *wgTun) MTU() (int, error) { return t.mtu, nil }

func (t *wgTun) Name() (string, error) { return "wg-gateway", nil }

func (t *wgTun) Events() <-chan tun.Event { return t.events }

func (t *wgTun) BatchSize() int { return 1 }

func (t *wgTun) Close() error {
	t.once.Do(func() {
		close(t.closed)
		close(t.events)
		t.ns.close()
	})
	return nil
}

// ---------------------------------------------------------------- 实例

type wgPeerCfg struct {
	PublicKey string `json:"publicKey"`
	AllowedIP string `json:"allowedIP"` // 空则默认 0.0.0.0/0
}

type wgCfg struct {
	Port       int         `json:"port"`
	MTU        int         `json:"mtu"`
	PrivateKey string      `json:"privateKey"`
	Peers      []wgPeerCfg `json:"peers"`
}

type wgInstance struct {
	dev  *device.Device
	tun  *wgTun
	ns   *netStack
	g    *Gateway // 只用来读 tcpOpen/udpOpen 计数
	port int
}

var (
	wgMu  sync.Mutex
	wgCur *wgInstance
)

// WgStart 启动 WireGuard 监听。cfg JSON：
//
//	{"port":51820,"mtu":1420,"privateKey":"<base64>",
//	 "peers":[{"publicKey":"<base64>","allowedIP":"0.0.0.0/0"}]}
//
// 已在运行时先停旧的，方便 Java 侧改端口/换 peer 后直接重启。
func WgStart(cfgJSON string) error {
	wgMu.Lock()
	defer wgMu.Unlock()

	stopWgLocked()

	var cfg wgCfg
	cfg.Port = 51820
	cfg.MTU = 1420
	if strings.TrimSpace(cfgJSON) != "" {
		if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
			return fmt.Errorf("配置不是合法 JSON: %v", err)
		}
	}
	if cfg.Port <= 0 || cfg.Port > 65535 {
		return fmt.Errorf("端口非法: %d", cfg.Port)
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1420
	}
	if cfg.MTU > 1420 {
		cfg.MTU = 1420 // 外层 UDP + WG 头，再大就分片了
	}
	privHex, err := keyToHex(cfg.PrivateKey)
	if err != nil {
		return fmt.Errorf("服务端私钥: %v", err)
	}

	g := &Gateway{verbose: false}
	ns, err := newNetStack(uint32(cfg.MTU), g)
	if err != nil {
		return fmt.Errorf("netstack: %v", err)
	}
	t := newWgTun(ns, cfg.MTU)

	dev := device.NewDevice(t, conn.NewStdNetBind(),
		device.NewLogger(device.LogLevelError, "wg "))
	if dev == nil {
		ns.close()
		return errors.New("WireGuard 设备创建失败")
	}

	var sb strings.Builder
	fmt.Fprintf(&sb, "private_key=%s\n", privHex)
	fmt.Fprintf(&sb, "listen_port=%d\n", cfg.Port)
	sb.WriteString("replace_peers=true\n")
	n := 0
	for _, p := range cfg.Peers {
		pub, perr := keyToHex(p.PublicKey)
		if perr != nil {
			dev.Close()
			ns.close()
			return fmt.Errorf("peer 公钥: %v", perr)
		}
		allowed := strings.TrimSpace(p.AllowedIP)
		if allowed == "" {
			allowed = "0.0.0.0/0"
		}
		fmt.Fprintf(&sb, "public_key=%s\n", pub)
		// replace_allowed_ips 必须写在 allowed_ip 之前：UAPI 里它是「先清空再加」，
		// 放到后面会把刚加的 allowed_ip 全清掉，peer 变成只握手不过数据的哑巴。
		sb.WriteString("replace_allowed_ips=true\n")
		fmt.Fprintf(&sb, "allowed_ip=%s\n", allowed)
		n++
	}
	if n == 0 {
		jlog("WG 警告：没有配置任何 peer，启动后不会有人能连上")
	}

	if err := dev.IpcSet(sb.String()); err != nil {
		dev.Close()
		ns.close()
		return fmt.Errorf("应用配置失败: %v", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		ns.close()
		return fmt.Errorf("启动失败: %v", err)
	}

	wgCur = &wgInstance{dev: dev, tun: t, ns: ns, g: g, port: cfg.Port}
	jlog("WG 已启动 UDP %d，MTU %d，peer %d 个", cfg.Port, cfg.MTU, n)
	return nil
}

// WgStop 关闭 WireGuard（不动 FOU）。
func WgStop() {
	wgMu.Lock()
	defer wgMu.Unlock()
	stopWgLocked()
}

func stopWgLocked() {
	if wgCur == nil {
		return
	}
	// dev.Close() 会连带关掉 tun（进而关 netstack）
	wgCur.dev.Close()
	wgCur = nil
	jlog("WG 已停止")
}

// WgRunning 是否有活跃实例。
func WgRunning() bool {
	wgMu.Lock()
	defer wgMu.Unlock()
	return wgCur != nil
}

// WgStats 返回 JSON：
//
//	{"running":true,"port":51820,"peers":[{"publicKey":"..","endpoint":"..",
//	  "rxBytes":123,"txBytes":456,"lastHandshake":1699999999,"handshakeAgo":5}]}
func WgStats() string {
	wgMu.Lock()
	cur := wgCur
	wgMu.Unlock()

	out := map[string]interface{}{"running": cur != nil, "port": 0, "peers": []interface{}{}}
	if cur == nil {
		b, _ := json.Marshal(out)
		return string(b)
	}
	out["port"] = cur.port

	uapi, err := cur.dev.IpcGet()
	if err != nil {
		b, _ := json.Marshal(out)
		return string(b)
	}

	var peers []interface{}
	var p map[string]interface{}
	for _, line := range strings.Split(uapi, "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		if k == "public_key" {
			if p != nil {
				peers = append(peers, p)
			}
			p = map[string]interface{}{"publicKey": v, "endpoint": "",
				"rxBytes": 0, "txBytes": 0, "lastHandshake": 0, "handshakeAgo": -1}
			continue
		}
		if p == nil {
			continue
		}
		switch k {
		case "endpoint":
			p["endpoint"] = v
		case "rx_bytes":
			if n, e := strconv.ParseInt(v, 10, 64); e == nil {
				p["rxBytes"] = n
			}
		case "tx_bytes":
			if n, e := strconv.ParseInt(v, 10, 64); e == nil {
				p["txBytes"] = n
			}
		case "last_handshake_time_sec":
			if n, e := strconv.ParseInt(v, 10, 64); e == nil && n > 0 {
				p["lastHandshake"] = n
				p["handshakeAgo"] = int64(time.Now().Unix()) - n
			}
		}
	}
	if p != nil {
		peers = append(peers, p)
	}
	out["peers"] = peers
	if out["peers"] == nil {
		out["peers"] = []interface{}{}
	}

	// netstack 侧的并发连接数，UI 上顺带显示
	out["tcp"] = cur.g.tcpOpen.Load()
	out["udp"] = cur.g.udpOpen.Load()

	b, _ := json.Marshal(out)
	return string(b)
}
