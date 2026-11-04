package com.dosgo.gateway.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Comparator;

/** 双向字节流中继。 */
public final class Relay {

    private Relay() {
    }

    /**
     * 解析并连接：IPv4 优先，逐个候选地址重试（某个 AAAA 不通时不再白等 15 秒）。
     * 域名不存在会抛 {@link java.net.UnknownHostException}，调用方据此区分「解析失败」和「连接失败」。
     */
    public static Socket connectAny(String host, int port, int perAddrMs) throws IOException {
        InetAddress[] addrs = Dns.resolve(host);
        Arrays.sort(addrs, new Comparator<InetAddress>() {
            @Override
            public int compare(InetAddress a, InetAddress b) {
                boolean a4 = a instanceof Inet4Address;
                boolean b4 = b instanceof Inet4Address;
                if (a4 == b4) {
                    return 0;
                }
                return a4 ? -1 : 1;
            }
        });
        IOException last = null;
        for (InetAddress a : addrs) {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(a, port), perAddrMs);
                return s;
            } catch (IOException e) {
                last = e;
                closeQuietly(s);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("no address for " + host);
    }

    public static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * 双向对拷：a→b 在当前线程，b→a 在子线程；任意方向结束则整体关闭。
     * 传入已创建的流可保留 BufferedReader 里未消费的缓冲数据。
     */
    public static void bridge(final Socket sa, final InputStream ain, final OutputStream aout,
                              final Socket sb, final InputStream bin, final OutputStream bout) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                copy(bin, aout);
            }
        }, "relay-b2a");
        t.setDaemon(true);
        t.start();
        copy(ain, bout);
        closeQuietly(sa);
        closeQuietly(sb);
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignore) {
        }
    }
}
