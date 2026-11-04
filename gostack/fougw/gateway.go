package fougw

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

// Config 是网关启动参数。
type Config struct {
	Port    int  `json:"port"`
	MTU     int  `json:"mtu"`
	Verbose bool `json:"verbose"`
}

type clientEp struct {
	addr *net.UDPAddr
	at   time.Time
}

// Gateway 是 FOU 网关实例：一个 UDP 监听 + 一个 gVisor netstack。
type Gateway struct {
	conn    *net.UDPConn
	st      *netStack
	mtu     int
	verbose bool

	// 隧道对端索引。必须按「内层 IP:端口」索引，不能只按 IP：
	// 同一台机器并发多条连接时，不同连接可能来自不同的外层 UDP 端口
	// （每个 FOU 客户端一个），只按 IP 索引会让回包串到别的端口去。
	mu      sync.RWMutex
	byTuple map[string]*clientEp // 内层客户端 ip:port -> 外层地址
	byIP    map[string]*clientEp // 内层 IP -> 最近一次出现的外层地址（ICMP 无端口时用）

	closed atomic.Bool

	rxPackets atomic.Uint64
	rxBytes   atomic.Uint64
	txPackets atomic.Uint64
	txBytes   atomic.Uint64
	tcpOpen   atomic.Int64
	udpOpen   atomic.Int64
	badPacket atomic.Uint64
}

// ---------------------------------------------------------------- 生命周期

var (
	curMu   sync.Mutex
	current *Gateway
)

// Start 启动网关。cfgJSON 形如 {"port":5555,"mtu":1400,"verbose":false}，空串用默认值。
// 已在运行时先停旧的，方便 Java 侧改端口后直接重启。
func Start(cfgJSON string) error {
	cfg := Config{Port: 5555, MTU: 1400}
	if cfgJSON != "" {
		if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
			return fmt.Errorf("参数解析失败: %v", err)
		}
	}
	if cfg.Port <= 0 || cfg.Port > 65535 {
		return fmt.Errorf("端口非法: %d", cfg.Port)
	}
	if cfg.MTU < 576 || cfg.MTU > 65535 {
		cfg.MTU = 1400
	}

	curMu.Lock()
	defer curMu.Unlock()
	if current != nil {
		current.stop()
		current = nil
	}

	// 监听放在 netstack 之前：端口被占时要尽快失败，别让上层以为起来了
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: cfg.Port})
	if err != nil {
		return fmt.Errorf("FOU 端口 %d 绑定失败: %v", cfg.Port, err)
	}
	conn.SetReadBuffer(4 << 20)
	conn.SetWriteBuffer(4 << 20)

	g := &Gateway{
		conn:    conn,
		mtu:     cfg.MTU,
		verbose: cfg.Verbose,
		byTuple: make(map[string]*clientEp),
		byIP:    make(map[string]*clientEp),
	}

	st, err := newNetStack(uint32(cfg.MTU), g)
	if err != nil {
		conn.Close()
		return fmt.Errorf("协议栈初始化失败: %v", err)
	}
	g.st = st

	go g.recvLoop()
	go g.outLoop()
	go g.statLoop()

	current = g
	jlog("FOU 网关已启动，UDP %d，MTU %d（gVisor netstack）", conn.LocalAddr().(*net.UDPAddr).Port, cfg.MTU)
	return nil
}

// Stop 关闭网关，幂等。
func Stop() {
	curMu.Lock()
	defer curMu.Unlock()
	if current != nil {
		current.stop()
		current = nil
	}
}

func (g *Gateway) stop() {
	if g.closed.Swap(true) {
		return
	}
	g.conn.Close()
	g.st.close()
	jlog("FOU 网关已停止")
}

// Running 返回是否在运行。
func Running() bool {
	curMu.Lock()
	defer curMu.Unlock()
	return current != nil && !current.closed.Load()
}

// StatsJSON 返回运行统计（JSON 对象）。
func StatsJSON() string {
	curMu.Lock()
	g := current
	curMu.Unlock()
	if g == nil {
		return `{"running":false}`
	}
	g.mu.RLock()
	clients := len(g.byIP)
	g.mu.RUnlock()
	b, err := json.Marshal(map[string]interface{}{
		"running": !g.closed.Load(),
		"port":    g.conn.LocalAddr().(*net.UDPAddr).Port,
		"mtu":     g.mtu,
		"rx":      g.rxPackets.Load(),
		"rxb":     g.rxBytes.Load(),
		"tx":      g.txPackets.Load(),
		"txb":     g.txBytes.Load(),
		"tcp":     g.tcpOpen.Load(),
		"udp":     g.udpOpen.Load(),
		"bad":     g.badPacket.Load(),
		"clients": clients,
	})
	if err != nil {
		return `{"running":false}`
	}
	return string(b)
}

// ---------------------------------------------------------------- 主循环

// recvLoop 收外层 UDP，把内层 IPv4 包喂给 netstack。
func (g *Gateway) recvLoop() {
	defer recoverLog("recvLoop")
	buf := make([]byte, 65535)
	for {
		n, from, err := g.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 20 || buf[0]>>4 != 4 {
			g.badPacket.Add(1)
			continue
		}
		pkt := buf[:n]
		ihl := int(pkt[0]&0x0f) * 4
		if ihl < 20 || n < ihl {
			g.badPacket.Add(1)
			continue
		}

		// 记下这个内层源 IP:端口 对应的外层隧道地址，回包时按内层目的 IP:端口 反查
		innerIP := net.IP(pkt[12:16]).String()
		now := time.Now()
		ep := &clientEp{addr: from, at: now}
		key := innerIP
		if n >= ihl+4 {
			key = net.JoinHostPort(innerIP, strconv.Itoa(int(binary.BigEndian.Uint16(pkt[ihl:ihl+2]))))
		}
		g.mu.Lock()
		g.byTuple[key] = ep
		g.byIP[innerIP] = ep
		g.mu.Unlock()

		g.rxPackets.Add(1)
		g.rxBytes.Add(uint64(n))

		// ICMP echo 自己回，不走 netstack（netstack 只认自己的地址，这里目的 IP 是任意的）
		if pkt[9] == 1 {
			if reply := icmpEchoReply(pkt); reply != nil {
				g.sendToClient(reply)
			}
			continue
		}

		g.st.inject(pkt)
	}
}

// outLoop 从 netstack 取出发往客户端的包，塞回外层 UDP。
func (g *Gateway) outLoop() {
	defer recoverLog("outLoop")
	for {
		pkt, ok := g.st.read()
		if !ok {
			return
		}
		g.sendToClient(pkt)
	}
}

func (g *Gateway) sendToClient(pkt []byte) {
	if len(pkt) < 20 {
		return
	}
	dstIP := net.IP(pkt[16:20]).String()
	ihl := int(pkt[0]&0x0f) * 4

	// 回包的目的端口就是客户端的源端口，用它精确定位是哪条隧道
	key := dstIP
	if (pkt[9] == 6 || pkt[9] == 17) && len(pkt) >= ihl+4 {
		key = net.JoinHostPort(dstIP, strconv.Itoa(int(binary.BigEndian.Uint16(pkt[ihl+2:ihl+4]))))
	}

	g.mu.RLock()
	ep := g.byTuple[key]
	if ep == nil {
		ep = g.byIP[dstIP]
	}
	if ep == nil && len(g.byIP) == 1 {
		for _, v := range g.byIP {
			ep = v
		}
	}
	g.mu.RUnlock()
	if ep == nil {
		return
	}
	if _, err := g.conn.WriteToUDP(pkt, ep.addr); err == nil {
		g.txPackets.Add(1)
		g.txBytes.Add(uint64(len(pkt)))
	}
}

func (g *Gateway) statLoop() {
	defer recoverLog("statLoop")
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for range t.C {
		if g.closed.Load() {
			return
		}
		g.gc()
		g.mu.RLock()
		n := len(g.byIP)
		g.mu.RUnlock()
		logStdout(map[string]interface{}{
			"t":       "stat",
			"rx":      g.rxPackets.Load(),
			"rxb":     g.rxBytes.Load(),
			"tx":      g.txPackets.Load(),
			"txb":     g.txBytes.Load(),
			"tcp":     g.tcpOpen.Load(),
			"udp":     g.udpOpen.Load(),
			"bad":     g.badPacket.Load(),
			"clients": n,
		})
	}
}

// gc 清掉 5 分钟没动静的隧道端点，避免长跑之后 map 无限增长。
func (g *Gateway) gc() {
	cut := time.Now().Add(-5 * time.Minute)
	g.mu.Lock()
	for k, v := range g.byTuple {
		if v.at.Before(cut) {
			delete(g.byTuple, k)
		}
	}
	for k, v := range g.byIP {
		if v.at.Before(cut) {
			delete(g.byIP, k)
		}
	}
	g.mu.Unlock()
}

// recoverLog 防止单条畸形报文把整个进程带走（进程内模式没有崩溃隔离，
// 至少要把 panic 挡住并记下来）。
func recoverLog(where string) {
	if r := recover(); r != nil {
		jlog("内部异常（%s）已拦截: %v", where, r)
	}
}

// ---------------------------------------------------------------- ICMP

var gid atomic.Uint32

// icmpEchoReply 就地把 echo request 变成 echo reply（改类型 + 重算校验和）。
func icmpEchoReply(in []byte) []byte {
	ihl := int(in[0]&0x0f) * 4
	if len(in) < ihl+8 {
		return nil
	}
	body := in[ihl:]
	if body[0] != 8 {
		return nil
	}
	reply := make([]byte, 20+len(body))
	reply[0] = 0x45
	binary.BigEndian.PutUint16(reply[2:4], uint16(len(reply)))
	binary.BigEndian.PutUint16(reply[4:6], uint16(gid.Add(1)))
	reply[8] = 64
	reply[9] = 1
	copy(reply[12:16], in[16:20])
	copy(reply[16:20], in[12:16])
	binary.BigEndian.PutUint16(reply[10:12], onesSum(reply[:20]))

	c := reply[20:]
	copy(c, body)
	c[0] = 0
	c[1] = 0
	c[2], c[3] = 0, 0
	binary.BigEndian.PutUint16(c[2:4], onesSum(c))
	return reply
}

// onesSum 计算 16 位反码和。
func onesSum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

var errNotRunning = errors.New("网关未运行")
