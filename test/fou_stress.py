"""FOU 网关压力测试：大文件完整性 + 多连接并发。

复用 fou_test.py 的报文构造与客户端逻辑，只换负载。
"""
import random
import socket
import sys
import threading
import time

sys.path.insert(0, "test")
import fou_test as F


def test_big(amount_kb):
    # 端口随机：真实客户端每条连接用新源端口，不会撞上上一条的 TIME_WAIT
    sport = random.randint(40000, 60000)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    size = amount_kb * 1024
    body, segs, fin = F.tcp_download(sock, sport, "/blob:%d" % size, timeout=120)
    got = F.body_length(body or b"")
    F.check("%dKB 单连接完整" % amount_kb, got == size,
            "%d 段 / %d 字节" % (segs, got))
    F.check("%dKB 正常 FIN" % amount_kb, fin)


def test_concurrent(n):
    results = [None] * n

    def worker(i):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("127.0.0.1", 0))
        try:
            body, segs, fin = F.tcp_download(
                sock, F.CLIENT_PORT + 10 + i, "/", timeout=30)
            ok = bool(body) and b"HELLO-FOU-TCP" in body
            results[i] = (ok, segs, fin)
        except Exception as e:
            results[i] = (False, 0, "err:%s" % e)

    ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    t0 = time.time()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    ok = sum(1 for r in results if r and r[0])
    F.check("%d 路并发连接" % n, ok == n, "%d/%d 成功, 耗时 %.2fs" %
            (ok, n, time.time() - t0))


def main():
    F.start_http()
    F.start_udp_echo()
    time.sleep(0.4)

    test_big(1024)
    test_big(3072)
    test_concurrent(8)

    print("\n结果: %d 通过 / %d 失败" % (len(F.OK), len(F.FAIL)))
    if F.FAIL:
        print("失败项:", F.FAIL)
        sys.exit(1)


if __name__ == "__main__":
    main()
