package fougw

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// netStack 是 gVisor netstack 的封装：一个 NIC = 一条 FOU 隧道。
//
// 关键点：
//   - PromiscuousMode：隧道里目的 IP 是任意的（用户想访问哪就填哪），
//     所以必须让网卡收下所有目的地址的包，否则全被丢。
//   - Spoofing：回包要以"那个任意目的 IP"作为源地址发出去，
//     否则客户端会认为回包不是来自它要访问的主机。
//   - TCP/UDP Forwarder：把"没有人 listening 的连接"转交给应用层处理，
//     tun2socks 用的就是这套。
type netStack struct {
	s  *stack.Stack
	ep *channel.Endpoint
	g  *Gateway
}

func newNetStack(mtu uint32, g *Gateway) (*netStack, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	ep := channel.New(8192, mtu, "")
	if err := s.CreateNIC(1, ep); err != nil {
		return nil, fmt.Errorf("CreateNIC: %v", err)
	}
	if err := s.SetPromiscuousMode(1, true); err != nil {
		return nil, fmt.Errorf("SetPromiscuousMode: %v", err)
	}
	if err := s.SetSpoofing(1, true); err != nil {
		return nil, fmt.Errorf("SetSpoofing: %v", err)
	}
	// 隧道里出现 127.0.0.1 之类"火星地址"是正常的（客户端可能就想访问网关本机），
	// 默认会被丢，这里放开。
	loopback := tcpip.AllowExternalLoopbackTrafficOption(true)
	s.SetNetworkProtocolOption(ipv4.ProtocolNumber, &loopback)

	// 占位地址：netstack 要求至少绑一个，具体收发靠 promiscuous + spoofing
	addr := tcpip.AddrFrom4([4]byte{192, 168, 7, 1})
	err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: addr.WithPrefix(),
	}, stack.AddressProperties{})
	if err != nil {
		return nil, fmt.Errorf("AddProtocolAddress: %v", err)
	}

	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
	})

	// 大缓冲 + SACK：单条连接跑满局域网带宽
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	moderate := tcpip.TCPModerateReceiveBufferOption(false)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &tcpip.TCPReceiveBufferSizeRangeOption{
		Min: 4096, Default: 1 << 20, Max: 8 << 20,
	})
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &tcpip.TCPSendBufferSizeRangeOption{
		Min: 4096, Default: 1 << 20, Max: 8 << 20,
	})

	ns := &netStack{s: s, ep: ep, g: g}

	// 没有 listener 挡在前面的连接，全部交给我们的 forwarder
	tcpFwd := tcp.NewForwarder(s, 0, 4096, ns.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, ns.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	return ns, nil
}

// inject 把原始 IPv4 包投进 netstack 的接收路径。
func (n *netStack) inject(pkt []byte) {
	defer recoverLog("inject")
	pb := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(pkt),
	})
	n.ep.InjectInbound(header.IPv4ProtocolNumber, pb)
	pb.DecRef()
}

// read 取一个 netstack 要发出的 IP 包（阻塞）。endpoint 关闭时返回 false。
func (n *netStack) read() ([]byte, bool) {
	pb := n.ep.ReadContext(context.Background())
	if pb == nil {
		return nil, false
	}
	v := pb.ToView()
	out := make([]byte, v.Size())
	copy(out, v.AsSlice())
	v.Release()
	pb.DecRef()
	return out, true
}

func (n *netStack) close() {
	n.ep.Close()
	n.s.Close()
}

// ---------------------------------------------------------------- TCP

func (n *netStack) handleTCP(r *tcp.ForwarderRequest) {
	defer recoverLog("handleTCP")
	id := r.ID()

	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		// 建不出端点（内存/窗口不足）：回 RST，让客户端立刻知道失败
		r.Complete(true)
		return
	}
	r.Complete(false)

	conn := gonet.NewTCPConn(&wq, ep)
	go n.relayTCP(conn, id.LocalAddress.String(), id.LocalPort,
		id.RemoteAddress.String(), id.RemotePort)
}

func (n *netStack) relayTCP(conn *gonet.TCPConn, dst string, dport uint16,
	src string, sport uint16) {
	defer recoverLog("relayTCP")
	n.g.tcpOpen.Add(1)
	defer n.g.tcpOpen.Add(-1)
	defer conn.Close()

	target := net.JoinHostPort(dst, strconv.Itoa(int(dport)))
	real, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil {
		jlog("TCP %s:%d -> %s 连接失败: %v", src, sport, target, err)
		return
	}
	defer real.Close()
	if tc, ok := real.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	if n.g.verbose {
		jlog("TCP %s:%d -> %s 已建立", src, sport, target)
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		io.Copy(real, conn)
		if tc, ok := real.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		io.Copy(conn, real)
		conn.CloseWrite()
	}()
	wg.Wait()
}

// ---------------------------------------------------------------- UDP

// handleUDP 返回 false 表示"没接手"，协议栈会回 ICMP 端口不可达。
func (n *netStack) handleUDP(f *udp.ForwarderRequest) bool {
	defer recoverLog("handleUDP")
	id := f.ID()

	var wq waiter.Queue
	ep, terr := f.CreateEndpoint(&wq)
	if terr != nil {
		return false
	}

	conn := gonet.NewUDPConn(&wq, ep)
	go n.relayUDP(conn, id.LocalAddress.String(), id.LocalPort,
		id.RemoteAddress.String(), id.RemotePort)
	return true
}

func (n *netStack) relayUDP(conn *gonet.UDPConn, dst string, dport uint16,
	src string, sport uint16) {
	defer recoverLog("relayUDP")
	n.g.udpOpen.Add(1)
	defer n.g.udpOpen.Add(-1)
	defer conn.Close()

	target := net.JoinHostPort(dst, strconv.Itoa(int(dport)))
	raddr, err := net.ResolveUDPAddr("udp", target)
	if err != nil {
		return
	}
	real, err := net.DialUDP("udp", nil, raddr)
	if err != nil {
		jlog("UDP %s:%d -> %s 失败: %v", src, sport, target, err)
		return
	}
	defer real.Close()

	// 局域网/公网 UDP 会话不会太久，闲置 2 分钟就收掉，避免端点泄漏
	real.SetDeadline(time.Now().Add(2 * time.Minute))
	conn.SetReadDeadline(time.Now().Add(2 * time.Minute))

	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 65535)
		for {
			nr, rerr := conn.Read(buf)
			if nr > 0 {
				real.Write(buf[:nr])
				real.SetDeadline(time.Now().Add(2 * time.Minute))
			}
			if rerr != nil {
				return
			}
		}
	}()

	buf := make([]byte, 65535)
	for {
		nr, rerr := real.Read(buf)
		if nr > 0 {
			conn.Write(buf[:nr])
			conn.SetReadDeadline(time.Now().Add(2 * time.Minute))
		}
		if rerr != nil {
			break
		}
	}
	<-done
}
