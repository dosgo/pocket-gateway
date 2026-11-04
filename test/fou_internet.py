"""通过 FOU 隧道访问真实公网 HTTP，验证网关确实把连接打出去了。

用法: python fou_internet.py [目标IP] [端口]
"""
import random
import socket
import struct
import sys
import time

sys.path.insert(0, "test")
import fou_test as F

DST = socket.inet_aton(sys.argv[1] if len(sys.argv) > 1 else "183.2.172.177")
DPORT = int(sys.argv[2]) if len(sys.argv) > 2 else 80
# 每轮换源端口：固定端口会撞上网关里上一轮的残留连接，SYN 被当成冲突回挑战 ACK
SPORT = random.randint(40000, 60000)


def seg(seq, ack, flags, data=b""):
    s = struct.pack("!HHLLBBHHH", SPORT, DPORT, seq, ack, 5 << 4, flags, 65535, 0, 0) + data
    s = s[:16] + struct.pack("!H", F.l4(6, F.CLIENT_IP, DST, s)) + s[18:]
    return F.ipv4(F.CLIENT_IP, DST, 6, s)


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.settimeout(8)

    isn = 8001
    sock.sendto(seg(isn, 0, 0x02), F.GW)
    try:
        pkt, _ = sock.recvfrom(65535)
    except socket.timeout:
        F.check("公网 SYN-ACK", False, "超时（%s:%d）" % (socket.inet_ntoa(DST), DPORT))
        return
    _, proto, src, s = F.parse_ip(pkt)
    seq, ack = struct.unpack("!LL", s[4:12])
    flags = s[13]
    F.check("公网 SYN-ACK", (flags & 0x12) == 0x12 and ack == isn + 1,
            "src=%s flags=0x%02x" % (socket.inet_ntoa(src), flags))

    srv_seq = seq + 1
    sock.sendto(seg(isn + 1, srv_seq, 0x10), F.GW)
    req = ("GET / HTTP/1.1\r\nHost: www.baidu.com\r\nConnection: close\r\n"
           "User-Agent: fou-test\r\n\r\n").encode()
    sock.sendto(seg(isn + 1, srv_seq, 0x18, req), F.GW)
    my_seq = isn + 1 + len(req)

    body = b""
    expected = srv_seq
    pending = {}
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            pkt, _ = sock.recvfrom(65535)
        except socket.timeout:
            break
        _, proto, src, s = F.parse_ip(pkt)
        doff = ((s[12] >> 4) & 0xF) * 4
        sq, ak = struct.unpack("!LL", s[4:12])
        fl = s[13]
        payload = s[doff:]
        if payload:
            if sq == expected:
                body += payload
                expected += len(payload)
                while expected in pending:
                    nx = pending.pop(expected)
                    body += nx
                    expected += len(nx)
            elif sq > expected:
                pending[sq] = payload
            sock.sendto(seg(my_seq, expected, 0x10), F.GW)
        if fl & 0x01:
            sock.sendto(seg(my_seq, expected + 1, 0x10), F.GW)
            break

    first = body.split(b"\r\n", 1)[0]
    F.check("公网 HTTP 响应", body.startswith(b"HTTP/1."),
            "%d 字节, 首行 %r" % (len(body), first[:60]))

    print("\n结果: %d 通过 / %d 失败" % (len(F.OK), len(F.FAIL)))
    if F.FAIL:
        sys.exit(1)


if __name__ == "__main__":
    main()
