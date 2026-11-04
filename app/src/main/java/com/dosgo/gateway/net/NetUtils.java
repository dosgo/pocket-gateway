package com.dosgo.gateway.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** 本机网卡 / IPv4 地址探测。 */
public final class NetUtils {

    public static final class Nic {
        public final String name;
        public final String ip;

        Nic(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }

        public String label() {
            return name + "  " + ip;
        }
    }

    private NetUtils() {
    }

    public static List<Nic> interfaces() {
        List<Nic> out = new ArrayList<Nic>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName();
                if (name == null || name.startsWith("dummy")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        out.add(new Nic(name, a.getHostAddress()));
                    }
                }
            }
        } catch (Exception ignore) {
        }
        Collections.sort(out, new Comparator<Nic>() {
            @Override
            public int compare(Nic a, Nic b) {
                return score(a.name) - score(b.name);
            }
        });
        return out;
    }

    private static int score(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        // 热点/P2P 网段是「客户端要连的地址」，优先级最高
        if (n.startsWith("ap") || n.startsWith("p2p") || n.startsWith("swlan") || n.startsWith("softap")) {
            return 0;
        }
        if (n.startsWith("wlan")) {
            return 1;
        }
        if (n.startsWith("eth")) {
            return 2;
        }
        if (n.startsWith("rmnet") || n.startsWith("ccmni")) {
            return 3;
        }
        if (n.startsWith("tun") || n.startsWith("ppp")) {
            return 5;
        }
        return 4;
    }

    /** 主 IP：Wi-Fi 优先，其次以太网/热点，再次数据流量。 */
    public static String primaryIp() {
        List<Nic> list = interfaces();
        return list.isEmpty() ? null : list.get(0).ip;
    }
}
