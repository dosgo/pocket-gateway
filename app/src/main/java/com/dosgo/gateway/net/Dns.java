package com.dosgo.gateway.net;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 网关侧域名解析。
 *
 * 为什么不用 {@code InetAddress.getAllByName} 一个就够：这台手机上长期跑着 NekoBox(VPN)，
 * 系统解析器会把查询交给 VPN 自己的 DNS(tun0 上的 172.19.0.2)；那个上游一抖、或者被规则拦掉，
 * 网关这边就「解析失败」，客户端看到的就是 DNS 错误。所以这里做两层：
 *
 *   1) 系统解析（带 3 秒硬超时，绝不死等）；
 *   2) 兜底：自己按 DNS 协议向 223.5.5.5 / 119.29.29.29 / 114.114.114.114 发 UDP 查询。
 *
 * 结果带 2 分钟缓存。客户端自己不做解析、全靠网关，所以这一层稳定与否直接决定「能不能上网」。
 */
public final class Dns {

    private static final String[] UPSTREAMS = {
            "223.5.5.5", "119.29.29.29", "114.114.114.114", "8.8.8.8"
    };

    private static final int SYSTEM_TIMEOUT_MS = 3000;
    private static final int UPSTREAM_TIMEOUT_MS = 2500;
    private static final long CACHE_MS = 120_000L;

    private static final Map<String, Entry> CACHE = new HashMap<String, Entry>();
    private static final Random RND = new Random();
    private static final ExecutorService SYS = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dns-sys");
            t.setDaemon(true);
            return t;
        }
    });

    private Dns() {
    }

    private static final class Entry {
        final InetAddress[] addrs;
        final long expire;

        Entry(InetAddress[] a) {
            addrs = a;
            expire = System.currentTimeMillis() + CACHE_MS;
        }
    }

    /** 上一次解析失败时，逐个上游的记录，给 /dns 诊断页看。 */
    public static volatile String lastTrace = "";

    public static InetAddress[] resolve(String host) throws UnknownHostException {
        if (host == null || host.trim().isEmpty()) {
            throw new UnknownHostException("empty host");
        }
        host = host.trim();
        if (isNumeric(host)) {
            return new InetAddress[]{InetAddress.getByName(host)};
        }
        String key = host.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Entry e = CACHE.get(key);
            if (e != null && e.expire > System.currentTimeMillis()) {
                return e.addrs;
            }
        }

        // 1) 系统解析
        InetAddress[] sys = resolveSystem(key);
        if (sys != null && sys.length > 0) {
            put(key, sys);
            return sys;
        }

        // 2) 上游兜底
        StringBuilder trace = new StringBuilder("系统解析超时/失败");
        for (String up : UPSTREAMS) {
            InetAddress[] r = queryUpstream(key, up);
            if (r != null && r.length > 0) {
                Logs.add("DNS 兜底 " + key + " via " + up + " -> " + r[0].getHostAddress());
                put(key, r);
                lastTrace = key + ": " + trace + "；" + up + " 成功 -> " + r[0].getHostAddress();
                return r;
            }
            trace.append("；").append(up).append(" 无结果/超时");
        }
        lastTrace = key + ": " + trace;
        throw new UnknownHostException("Unable to resolve host \"" + host + "\": 系统与上游 DNS 都查不到（"
                + "域名可能不存在，或网络侧 DNS 被拦）");
    }

    /** 只做系统解析，带超时；失败返回 null，不抛。 */
    private static InetAddress[] resolveSystem(final String host) {
        Future<InetAddress[]> f = SYS.submit(new Callable<InetAddress[]>() {
            @Override
            public InetAddress[] call() {
                try {
                    return InetAddress.getAllByName(host);
                } catch (Throwable t) {
                    return null;
                }
            }
        });
        try {
            InetAddress[] r = f.get(SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null || r.length == 0) {
                return null;
            }
            List<InetAddress> ok = new ArrayList<InetAddress>(r.length);
            for (InetAddress a : r) {
                if (!isBogus(a.getAddress())) {
                    ok.add(a);
                }
            }
            return ok.isEmpty() ? null : ok.toArray(new InetAddress[0]);
        } catch (Throwable t) {
            f.cancel(true);
            return null;
        }
    }

    private static void put(String key, InetAddress[] addrs) {
        synchronized (CACHE) {
            if (CACHE.size() > 500) {
                CACHE.clear();
            }
            CACHE.put(key, new Entry(addrs));
        }
    }

    /** 自己发一条 A 查询。返回 null 表示超时或应答里没有可用 A 记录。 */
    private static InetAddress[] queryUpstream(String host, String upstream) {
        DatagramSocket s = null;
        try {
            int id = RND.nextInt(0xFFFF);
            byte[] q = buildQuery(host, id);
            s = new DatagramSocket();
            s.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            InetAddress dst = InetAddress.getByName(upstream);
            s.send(new DatagramPacket(q, q.length, dst, 53));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                s.receive(p);
                byte[] resp = new byte[p.getLength()];
                System.arraycopy(buf, 0, resp, 0, p.getLength());
                int rid = ((resp[0] & 0xFF) << 8) | (resp[1] & 0xFF);
                if (rid != id) {
                    continue;
                }
                return parseAnswer(resp);
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static byte[] buildQuery(String host, int id) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((id >> 8) & 0xFF);
        o.write(id & 0xFF);
        o.write(0x01);          // RD
        o.write(0x00);
        o.write(0x00); o.write(0x01);   // QDCOUNT = 1
        o.write(0x00); o.write(0x00);   // ANCOUNT
        o.write(0x00); o.write(0x00);   // NSCOUNT
        o.write(0x00); o.write(0x00);   // ARCOUNT
        for (String part : host.split("\\.")) {
            byte[] b = part.getBytes("US-ASCII");
            if (b.length == 0 || b.length > 63) {
                throw new IllegalArgumentException("bad label");
            }
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0x00);
        o.write(0x00); o.write(0x01);   // TYPE A
        o.write(0x00); o.write(0x01);   // CLASS IN
        return o.toByteArray();
    }

    private static InetAddress[] parseAnswer(byte[] r) throws Exception {
        if (r.length < 12) {
            return null;
        }
        int rcode = r[3] & 0x0F;
        if (rcode != 0) {
            return null;
        }
        int qd = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        int an = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
        int p = 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(r, p);
            if (p < 0) {
                return null;
            }
            p += 4;
        }
        List<InetAddress> out = new ArrayList<InetAddress>();
        for (int i = 0; i < an; i++) {
            p = skipName(r, p);
            if (p < 0 || p + 10 > r.length) {
                break;
            }
            int type = ((r[p] & 0xFF) << 8) | (r[p + 1] & 0xFF);
            int rdlen = ((r[p + 8] & 0xFF) << 8) | (r[p + 9] & 0xFF);
            p += 10;
            if (rdlen < 0 || p + rdlen > r.length) {
                break;
            }
            if (type == 1 && rdlen == 4) {
                byte[] ip = new byte[]{r[p], r[p + 1], r[p + 2], r[p + 3]};
                if (!isBogus(ip)) {
                    out.add(InetAddress.getByAddress(ip));
                }
            }
            p += rdlen;
        }
        return out.isEmpty() ? null : out.toArray(new InetAddress[0]);
    }

    private static int skipName(byte[] b, int p) {
        while (p >= 0 && p < b.length) {
            int len = b[p] & 0xFF;
            if (len == 0) {
                return p + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return p + 2;
            }
            if ((len & 0xC0) != 0) {
                return -1;
            }
            p += 1 + len;
        }
        return -1;
    }

    /** 过滤掉拿来做连接目标没有意义的地址（含 sing-box 之类 fake-ip 常占的 198.18.0.0/15）。 */
    private static boolean isBogus(byte[] ip) {
        if (ip == null || ip.length != 4) {
            return true;
        }
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (a == 0 || a == 127 || a >= 224) {
            return true;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        return false;
    }

    private static boolean isNumeric(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 字面量
        }
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dot = true;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dot;
    }
}
