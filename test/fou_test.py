#!/usr/bin/env python3
"""FOU (IP-in-UDP) 网关实测：手工构造原始 IPv4 报文，验证 ICMP / UDP / TCP 三条路径。"""
import socket
import struct
import random
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

GW = ("127.0.0.1", 15555)          # FouServer 监听地址
CLIENT_IP = bytes([10, 0, 0, 2])   # 假装隧道对端地址
CLIENT_PORT = random.randint(40000, 50000)
OK = []
FAIL = []
CRLF = b"\r\n"


def check(name, cond, extra=""):
    if cond:
        OK.append(name)
        print("  [PASS] %s %s" % (name, extra))
    else:
        FAIL.append(name)
        print("  [FAIL] %s %s" % (name, extra))


def csum(b):
    if len(b) % 2:
        b += b"\x00"
    s = 0
    for i in range(0, len(b), 2):
        s += (b[i] << 8) | (b[i + 1])
    while s >> 16:
        s = (s & 0xffff) + (s >> 16)
    return (~s) & 0xffff


def ipv4(src, dst, proto, payload):
    total = 20 + len(payload)
    h = struct.pack("!BBHHHBBH4s4s", 0x45, 0, total, 0, 0, 64, proto, 0, src, dst)
    h = struct.pack("!BBHHHBBH4s4s", 0x45, 0, total, 0, 0, 64, proto, csum(h), src, dst)
    return h + payload


def l4(proto, src, dst, seg):
    pseudo = src + dst + struct.pack("!BBH", 0, proto, len(seg))
    return csum(pseudo + seg)


def tcp_packet(sport, dport, seq, ack, flags, data=b"", win=65535):
    dst = bytes([127, 0, 0, 1])
    seg = struct.pack("!HHLLBBHHH", sport, dport, seq, ack, 5 << 4, flags, win, 0, 0) + data
    seg = seg[:16] + struct.pack("!H", l4(6, CLIENT_IP, dst, seg)) + seg[18:]
    return ipv4(CLIENT_IP, dst, 6, seg)


def udp_packet(sport, dport, data):
    dst = bytes([127, 0, 0, 1])
    seg = struct.pack("!HHHH", sport, dport, 8 + len(data), 0) + data
    seg = seg[:6] + struct.pack("!H", l4(17, CLIENT_IP, dst, seg)) + seg[8:]
    return ipv4(CLIENT_IP, dst, 17, seg)


def icmp_packet(ident, seq, data):
    dst = bytes([127, 0, 0, 1])
    body = struct.pack("!BBHHH", 8, 0, 0, ident, seq) + data
    body = body[:2] + struct.pack("!H", csum(body)) + body[4:]
    return ipv4(CLIENT_IP, dst, 1, body)


def parse_ip(pkt):
    ihl = (pkt[0] & 0x0F) * 4
    proto = pkt[9]
    src = pkt[12:16]
    return ihl, proto, src, pkt[ihl:]


# ----------------------------------------------------------------- 本地测试服务
def start_http():
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith("/blob:"):
                body = b"X" * int(self.path.split(":", 1)[1])
            elif self.path == "/big":
                body = b"X" * 200000
            else:
                body = b"HELLO-FOU-TCP"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    srv = HTTPServer(("127.0.0.1", 8898), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def start_udp_echo():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 9998))

    def loop():
        while True:
            d, a = s.recvfrom(65535)
            s.sendto(b"ECHO:" + d, a)

    threading.Thread(target=loop, daemon=True).start()
    return s


# ----------------------------------------------------------------- 测试用例
def test_icmp(sock):
    print("ICMP echo")
    sock.sendto(icmp_packet(0x1234, 1, b"pingpayload"), GW)
    sock.settimeout(3)
    try:
        pkt, _ = sock.recvfrom(65535)
    except socket.timeout:
        check("ICMP echo 应答", False, "超时")
        return
    ihl, proto, src, l4p = parse_ip(pkt)
    check("ICMP echo 应答", proto == 1 and l4p[0] == 0 and l4p[8:] == b"pingpayload",
          "src=%s payload=%r" % (socket.inet_ntoa(src), l4p[8:]))


def test_udp(sock):
    print("UDP 转发")
    sock.sendto(udp_packet(CLIENT_PORT, 9998, b"dns-like-query"), GW)
    sock.settimeout(3)
    try:
        pkt, _ = sock.recvfrom(65535)
    except socket.timeout:
        check("UDP 回包", False, "超时")
        return
    ihl, proto, src, l4p = parse_ip(pkt)
    sport, dport, ulen, _ = struct.unpack("!HHHH", l4p[:8])
    payload = l4p[8:ulen]
    check("UDP 回包", proto == 17 and payload == b"ECHO:dns-like-query",
          "sport=%d dport=%d payload=%r" % (sport, dport, payload))


def tcp_download(sock, sport, path, timeout=30, drop_nth=0):
    """走一遍握手 → GET → 收完 → FIN，返回 (body, segs, fin_seen)。

    按真实 TCP 语义只累积 ACK 连续前缀；drop_nth>0 时故意丢掉第 n 个数据段，
    用于验证网关的 RTO 重传。"""
    isn = 1000 + sport % 1000
    sock.sendto(tcp_packet(sport, 8898, isn, 0, 0x02), GW)
    sock.settimeout(5)
    try:
        pkt, _ = sock.recvfrom(65535)
    except socket.timeout:
        return None, 0, False
    ihl, proto, src, seg = parse_ip(pkt)
    data_off = ((seg[12] >> 4) & 0xF) * 4
    seq, ack = struct.unpack("!LL", seg[4:12])
    flags = seg[13]
    if (flags & 0x12) != 0x12 or ack != isn + 1:
        return None, 0, False
    srv_isn = seq
    sock.sendto(tcp_packet(sport, 8898, isn + 1, srv_isn + 1, 0x10), GW)

    req = ("GET " + path + " HTTP/1.1" + CRLF.decode()
           + "Host: 127.0.0.1" + CRLF.decode()
           + "Connection: close" + CRLF.decode() + CRLF.decode())
    req = req.encode()
    sock.sendto(tcp_packet(sport, 8898, isn + 1, srv_isn + 1, 0x18, req), GW)
    my_seq = isn + 1 + len(req)

    expected = srv_isn + 1          # 期望的下一个服务端序号
    pending = {}                    # 乱序缓存: seq -> payload
    body = b""
    segs = 0
    got_data = 0
    fin_seq = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            pkt, _ = sock.recvfrom(65535)
        except socket.timeout:
            break
        ihl, proto, src, seg = parse_ip(pkt)
        data_off = ((seg[12] >> 4) & 0xF) * 4
        seq, ack = struct.unpack("!LL", seg[4:12])
        flags = seg[13]
        payload = seg[data_off:]

        if payload:
            got_data += 1
            if drop_nth and got_data == drop_nth:
                continue            # 模拟链路丢包
            if seq == expected:
                body += payload
                expected += len(payload)
                while expected in pending:
                    nxt = pending.pop(expected)
                    body += nxt
                    expected += len(nxt)
                segs += 1
            elif seq > expected:
                pending[seq] = payload   # 缺口：缓存，等重传补齐
            # 无论是否连续，都 ACK 当前连续前缀（重复 ACK 触发对端重传）
            sock.sendto(tcp_packet(sport, 8898, my_seq, expected, 0x10), GW)
        if flags & 0x01:
            # FIN 可能早于补洞数据到达，先记住它的序号
            fin_seq = seq
            if seq == expected:
                sock.sendto(tcp_packet(sport, 8898, my_seq, expected + 1, 0x10), GW)
                break

    while expected in pending:
        nxt = pending.pop(expected)
        body += nxt
        expected += len(nxt)
    fin_seen = fin_seq is not None and fin_seq == expected
    if fin_seen:
        sock.sendto(tcp_packet(sport, 8898, my_seq, expected + 1, 0x10), GW)
    return body, segs, fin_seen


def body_length(body):
    idx = body.find(b"\r\n\r\n")
    return len(body) - (idx + 4) if idx >= 0 else len(body)


def test_tcp(sock):
    print("TCP 用户态协议栈")
    body, segs, fin = tcp_download(sock, CLIENT_PORT, "/")
    check("TCP 三次握手 + HTTP 响应", body is not None and b"HELLO-FOU-TCP" in body,
          "%d 字节" % (len(body) if body else 0))
    check("TCP 连接关闭(FIN)", fin)


def test_tcp_big(sock):
    print("TCP 大流量 200KB")
    body, segs, fin = tcp_download(sock, CLIENT_PORT + 7, "/big", timeout=40)
    got = body_length(body or b"")
    check("200KB 多分段完整收到", got == 200000, "%d 段 / %d 字节" % (segs, got))
    check("大流量正常 FIN", fin)


def test_tcp_loss(sock):
    print("TCP 丢包重传（故意丢第 5 段）")
    body, segs, fin = tcp_download(sock, CLIENT_PORT + 13, "/big", timeout=40, drop_nth=5)
    got = body_length(body or b"")
    check("丢包后靠 RTO 重传补齐", got == 200000,
          "%d 段 / %d 字节" % (segs, got))


def main():
    start_http()
    start_udp_echo()
    time.sleep(0.3)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))

    test_icmp(sock)
    test_udp(sock)
    test_tcp(sock)
    test_tcp_big(sock)
    test_tcp_loss(sock)

    print("\n结果: %d 通过 / %d 失败" % (len(OK), len(FAIL)))
    if FAIL:
        print("失败项:", FAIL)
        sys.exit(1)


if __name__ == "__main__":
    main()
