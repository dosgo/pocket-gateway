// foutest —— 手工构造 IPv4 报文，从隧道外部打 FOU 网关做端到端验证。
//
// 这个客户端不依赖任何系统协议栈：自己拼 IP/TCP/UDP/ICMP 头，把报文塞进
// 外层 UDP 载荷发给网关，再把网关回的报文按 TCP 序号规则拼回来。
// 编译到 arm64 推到手机上跑，就能验证"App 里那个 Go 进程"真的在干活。
package main

import (
	"math/rand"
	"encoding/binary"
	"flag"
	"fmt"
	"net"
	"os"
	"time"
)

var (
	flagGW   = flag.String("gw", "127.0.0.1:5555", "网关 FOU 地址")
	flagTCP  = flag.String("tcp", "183.2.172.177:80", "TCP 测试目标")
	flagHost = flag.String("host", "www.baidu.com", "HTTP Host 头")
	flagDNS  = flag.String("dns", "223.5.5.5:53", "UDP/DNS 测试目标")
)

var (
	cliIP   = net.IPv4(10, 9, 9, 9).To4()
	gwAddr  *net.UDPAddr
	sock    *net.UDPConn
	okCount int
	failCnt int
)

func check(name string, pass bool, detail ...string) {
	var d string
	if len(detail) > 0 {
		d = detail[0]
	}
	if pass {
		okCount++
		fmt.Printf("  [PASS] %s%s\n", name, suffix(d))
	} else {
		failCnt++
		fmt.Printf("  [FAIL] %s%s\n", name, suffix(d))
	}
}

func suffix(s string) string {
	if s == "" {
		return ""
	}
	return "  (" + s + ")"
}

// ---------------------------------------------------------------- 报文构造

func csum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = (s & 0xffff) + (s >> 16)
	}
	return ^uint16(s)
}

func l4sum(proto byte, src, dst net.IP, seg []byte) uint16 {
	ph := make([]byte, 12)
	copy(ph[0:4], src.To4())
	copy(ph[4:8], dst.To4())
	ph[9] = proto
	binary.BigEndian.PutUint16(ph[10:12], uint16(len(seg)))
	return csum(append(ph, seg...))
}

func ipv4(src, dst net.IP, proto byte, payload []byte) []byte {
	p := make([]byte, 20+len(payload))
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	binary.BigEndian.PutUint16(p[4:6], uint16(time.Now().UnixNano()&0xffff))
	p[8] = 64
	p[9] = proto
	copy(p[12:16], src.To4())
	copy(p[16:20], dst.To4())
	binary.BigEndian.PutUint16(p[10:12], csum(p[:20]))
	copy(p[20:], payload)
	return p
}

func tcpPacket(sport, dport uint16, seq, ack uint32, flags byte, payload ...[]byte) []byte {
	var data []byte
	if len(payload) > 0 {
		data = payload[0]
	}
	dst := dstIP
	s := make([]byte, 20+len(data))
	binary.BigEndian.PutUint16(s[0:2], sport)
	binary.BigEndian.PutUint16(s[2:4], dport)
	binary.BigEndian.PutUint32(s[4:8], seq)
	binary.BigEndian.PutUint32(s[8:12], ack)
	s[12] = 5 << 4
	s[13] = flags
	binary.BigEndian.PutUint16(s[14:16], 64240)
	copy(s[20:], data)
	binary.BigEndian.PutUint16(s[16:18], l4sum(6, cliIP, dst, s))
	return ipv4(cliIP, dst, 6, s)
}

func udpPacket(sport, dport uint16, data []byte) []byte {
	dst := dstIP
	s := make([]byte, 8+len(data))
	binary.BigEndian.PutUint16(s[0:2], sport)
	binary.BigEndian.PutUint16(s[2:4], dport)
	binary.BigEndian.PutUint16(s[4:6], uint16(8+len(data)))
	copy(s[8:], data)
	binary.BigEndian.PutUint16(s[6:8], l4sum(17, cliIP, dst, s))
	return ipv4(cliIP, dst, 17, s)
}

func icmpEcho(id, seq uint16, payload []byte) []byte {
	c := make([]byte, 8+len(payload))
	c[0] = 8
	binary.BigEndian.PutUint16(c[4:6], id)
	binary.BigEndian.PutUint16(c[6:8], seq)
	copy(c[8:], payload)
	binary.BigEndian.PutUint16(c[2:4], csum(c))
	return ipv4(cliIP, dstIP, 1, c)
}

// parseIP 返回 (IP 头长, 协议号, 源 IP, L4 载荷)
func parseIP(pkt []byte) (int, byte, net.IP, []byte) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return 0, 0, nil, nil
	}
	ihl := int(pkt[0]&0x0f) * 4
	if len(pkt) < ihl {
		return 0, 0, nil, nil
	}
	return ihl, pkt[9], net.IP(pkt[12:16]), pkt[ihl:]
}

// recv 收一个报文，超时返回 nil
func recv(ms int) []byte {
	sock.SetReadDeadline(time.Now().Add(time.Duration(ms) * time.Millisecond))
	buf := make([]byte, 65535)
	n, _, err := sock.ReadFromUDP(buf)
	if err != nil {
		return nil
	}
	return buf[:n]
}

var dstIP net.IP

// ---------------------------------------------------------------- 用例

func testICMP() {
	fmt.Println("ICMP echo")
	d := net.IPv4(8, 8, 8, 8).To4()
	dstIP = d
	sock.WriteToUDP(icmpEcho(0x1234, 1, []byte("fou-ping-0123456789")), gwAddr)
	for {
		pkt := recv(3000)
		if pkt == nil {
			check("ICMP 应答", false, "超时")
			return
		}
		_, proto, src, body := parseIP(pkt)
		if proto != 1 {
			continue
		}
		check("ICMP 应答", len(body) >= 8 && body[0] == 0 && src.Equal(d),
			fmt.Sprintf("type=%d 载荷 %d 字节", body[0], len(body)-8))
		return
	}
}

func dnsQuery(name string) []byte {
	q := []byte{0xAB, 0xCD, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	for _, part := range splitDot(name) {
		q = append(q, byte(len(part)))
		q = append(q, part...)
	}
	q = append(q, 0, 0, 1, 0, 1)
	return q
}

func splitDot(s string) []string {
	var out []string
	cur := ""
	for _, c := range s {
		if c == '.' {
			out = append(out, cur)
			cur = ""
		} else {
			cur += string(c)
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func testUDP() {
	fmt.Println("UDP / DNS")
	host, portStr, _ := net.SplitHostPort(*flagDNS)
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	dstIP = net.ParseIP(host).To4()
	if dstIP == nil {
		check("DNS 解析目标", false, "不是 IP: "+host)
		return
	}

	sock.WriteToUDP(udpPacket(40002, uint16(port), dnsQuery("www.baidu.com")), gwAddr)
	for {
		pkt := recv(5000)
		if pkt == nil {
			check("UDP 转发", false, "超时")
			return
		}
		_, proto, src, body := parseIP(pkt)
		if proto != 17 || len(body) < 8+12 {
			continue
		}
		// body 开头还有 8 字节 UDP 头，DNS 报文从 body[8] 开始
		dns := body[8:]
		ancount := binary.BigEndian.Uint16(dns[6:8])
		check("UDP 转发", src.Equal(dstIP) && ancount > 0,
			fmt.Sprintf("DNS 应答 %d 条记录, %d 字节", ancount, len(dns)))
		return
	}
}

func testTCP() {
	fmt.Println("TCP / HTTP")
	host, portStr, _ := net.SplitHostPort(*flagTCP)
	var dport int
	fmt.Sscanf(portStr, "%d", &dport)
	dstIP = net.ParseIP(host).To4()
	if dstIP == nil {
		check("TCP 目标", false, "不是 IP: "+host)
		return
	}
	// 每次随机源端口：固定端口会撞上网关里上一轮残留的 TIME_WAIT 连接，
	// 新 SYN 被当成旧连接的重传而回纯 ACK。真实客户端由内核挑端口。
	sport := uint16(20000 + rand.Intn(40000))
	isn := uint32(1001)

	sock.WriteToUDP(tcpPacket(sport, uint16(dport), isn, 0, 0x02), gwAddr)
	var srvNext uint32
	for {
		pkt := recv(6000)
		if pkt == nil {
			check("TCP 三次握手", false, "SYN 超时")
			return
		}
		_, proto, _, seg := parseIP(pkt)
		if proto != 6 || len(seg) < 20 || binary.BigEndian.Uint16(seg[2:4]) != sport {
			continue
		}
		seq := binary.BigEndian.Uint32(seg[4:8])
		ack := binary.BigEndian.Uint32(seg[8:12])
		if seg[13]&0x12 != 0x12 || ack != isn+1 {
			check("TCP 三次握手", false, fmt.Sprintf("flags=0x%02x ack=%d", seg[13], ack))
			return
		}
		srvNext = seq + 1
		check("TCP 三次握手", true, "SYN-ACK 正常")
		break
	}

	sock.WriteToUDP(tcpPacket(sport, uint16(dport), isn+1, srvNext, 0x10), gwAddr)
	req := []byte("GET / HTTP/1.1\r\nHost: " + *flagHost +
		"\r\nConnection: close\r\nUser-Agent: foutest\r\n\r\n")
	sock.WriteToUDP(tcpPacket(sport, uint16(dport), isn+1, srvNext, 0x18, req), gwAddr)
	myNext := isn + 1 + uint32(len(req))

	var body []byte
	pending := map[uint32][]byte{}
	deadline := time.Now().Add(25 * time.Second)
	finSeen := false
	segs := 0
	for time.Now().Before(deadline) && !finSeen {
		pkt := recv(6000)
		if pkt == nil {
			break
		}
		_, proto, _, seg := parseIP(pkt)
		if proto != 6 || len(seg) < 20 || binary.BigEndian.Uint16(seg[2:4]) != sport {
			continue
		}
		doff := int(seg[12]>>4) * 4
		if len(seg) < doff {
			continue
		}
		seq := binary.BigEndian.Uint32(seg[4:8])
		flags := seg[13]
		payload := seg[doff:]
		if flags&0x01 != 0 {
			finSeen = true
		}
		if len(payload) > 0 {
			segs++
			switch {
			case seq == srvNext:
				body = append(body, payload...)
				srvNext += uint32(len(payload))
				for {
					nx, has := pending[srvNext]
					if !has {
						break
					}
					body = append(body, nx...)
					srvNext += uint32(len(nx))
					delete(pending, srvNext-uint32(len(nx)))
				}
			case seq > srvNext:
				pending[seq] = append([]byte(nil), payload...)
			}
			sock.WriteToUDP(tcpPacket(sport, uint16(dport), myNext, srvNext, 0x10), gwAddr)
		}
	}
	if finSeen {
		sock.WriteToUDP(tcpPacket(sport, uint16(dport), myNext, srvNext+1, 0x10), gwAddr)
	}

	head := body
	if i := indexOf(body, []byte("\r\n\r\n")); i >= 0 {
		head = body[:i]
	}
	check("TCP 承载 HTTP", len(body) > 0 && len(head) > 8 && string(head[:5]) == "HTTP/",
		fmt.Sprintf("%d 字节 / %d 段, 首行 %q", len(body), segs, firstLine(head)))
	check("TCP 正常 FIN", finSeen)
}

func firstLine(b []byte) string {
	for i, c := range b {
		if c == '\r' || c == '\n' {
			return string(b[:i])
		}
		if i > 60 {
			break
		}
	}
	return string(b)
}

func indexOf(b, sub []byte) int {
	for i := 0; i+len(sub) <= len(b); i++ {
		match := true
		for j := range sub {
			if b[i+j] != sub[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

func main() {
	flag.Parse()
	var err error
	gwAddr, err = net.ResolveUDPAddr("udp4", *flagGW)
	if err != nil {
		fmt.Println("网关地址错误:", err)
		os.Exit(1)
	}
	sock, err = net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		fmt.Println("绑定本地端口失败:", err)
		os.Exit(1)
	}
	fmt.Printf("网关 %s  内层客户端 %s\n\n", gwAddr, cliIP)
	testICMP()
	testUDP()
	testTCP()
	fmt.Printf("\n结果: %d 通过 / %d 失败\n", okCount, failCnt)
	if failCnt > 0 {
		os.Exit(1)
	}
}
