package com.dosgo.gateway;

import android.content.Context;
import android.content.SharedPreferences;

import com.dosgo.gateway.net.Logs;

import org.json.JSONObject;

import fougw.Fougw;

/**
 * WireGuard 接入控制。
 *
 * <p>和 FOU 并列：FOU 是自研裸 IP-in-UDP，只有本 App 客户端能用；WireGuard 是标准协议，
 * 任何平台官方客户端扫码导入即可全流量（TCP/UDP/ICMP）经网关出去。
 *
 * <p>网关侧只做「UDP 监听 + 解密 + 注入 netstack 转发」，不需要 root / VpnService，
 * 也不需要真实 tun 设备 —— Go 那边把 WireGuard 的 tun.Device 接口接到了现有的
 * gVisor netstack 上。
 *
 * <p>密钥分两套：
 * <ul>
 *   <li>服务端密钥（本机）：留在设备上，公钥写进客户端配置的 [Peer]</li>
 *   <li>客户端密钥：由 App 代生成，配置文本里带着私钥，客户端扫码即可用</li>
 * </ul>
 */
public final class WgController {

    private static final String PREF = "gateway_wg";

    public static final int DEFAULT_PORT = 51820;
    public static final int DEFAULT_MTU = 1420;
    /** 隧道内网段：客户端拿 .2，网关侧不需要真占地址（promiscuous + spoofing） */
    public static final String CLIENT_IP = "10.66.66.2/32";
    public static final String CLIENT_DNS = "223.5.5.5, 119.29.29.29";

    public static volatile boolean active = false;
    public static volatile String note = "";

    private WgController() {
    }

    private static SharedPreferences prefs(Context c) {
        return AppPrefs.dp(c, PREF);
    }

    // ------------------------------------------------------------ 配置

    public static int port(Context c) {
        return prefs(c).getInt("port", DEFAULT_PORT);
    }

    public static void setPort(Context c, int p) {
        prefs(c).edit().putInt("port", p <= 0 || p > 65535 ? DEFAULT_PORT : p).apply();
    }

    public static boolean prefAuto(Context c) {
        return prefs(c).getBoolean("auto", false);
    }

    public static void setPrefAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
    }

    /** 服务端私钥（首次调用时生成并落盘）。 */
    public static String serverPriv(Context c) {
        String k = prefs(c).getString("srv_priv", "");
        if (k == null || k.isEmpty()) {
            k = genPair(c, "srv_priv", "srv_pub");
        }
        return k;
    }

    public static String serverPub(Context c) {
        serverPriv(c);
        return prefs(c).getString("srv_pub", "");
    }

    /** 客户端私钥（本 App 代生成，写在 .conf 里）。 */
    public static String clientPriv(Context c) {
        String k = prefs(c).getString("cli_priv", "");
        if (k == null || k.isEmpty()) {
            k = genPair(c, "cli_priv", "cli_pub");
        }
        return k;
    }

    public static String clientPub(Context c) {
        clientPriv(c);
        return prefs(c).getString("cli_pub", "");
    }

    private static String genPair(Context c, String privKey, String pubKey) {
        try {
            String json = Fougw.wgGenKey();
            JSONObject o = new JSONObject(json);
            String priv = o.getString("private");
            String pub = o.getString("public");
            prefs(c).edit().putString(privKey, priv).putString(pubKey, pub).apply();
            return priv;
        } catch (Throwable t) {
            Logs.add("WG 生成密钥失败: " + t.getMessage());
            return "";
        }
    }

    /** 换一套客户端密钥（旧配置立即失效）。 */
    public static void resetClientKey(Context c) {
        prefs(c).edit().remove("cli_priv").remove("cli_pub").apply();
        clientPriv(c);
        note = "客户端密钥已重置，旧配置需要重新导入";
        Logs.add(note);
    }

    // ------------------------------------------------------------ 生命周期

    /** 启动 WireGuard；返回错误信息，成功返回 null。 */
    public static synchronized String start(Context c) {
        String priv = serverPriv(c);
        String peer = clientPub(c);
        if (priv == null || priv.isEmpty() || peer == null || peer.isEmpty()) {
            note = "密钥没生成出来，无法启动";
            return note;
        }
        int p = port(c);
        String cfg = "{\"port\":" + p + ",\"mtu\":" + DEFAULT_MTU
                + ",\"privateKey\":\"" + priv + "\""
                + ",\"peers\":[{\"publicKey\":\"" + peer + "\",\"allowedIP\":\"0.0.0.0/0\"}]}";
        try {
            Fougw.wgStart(cfg);
            active = Fougw.wgRunning();
            note = active ? "" : "启动后状态异常";
            Logs.add(active ? "WG 隧道已启动，UDP " + p : "WG 启动失败：" + note);
            return active ? null : note;
        } catch (Throwable t) {
            active = false;
            note = String.valueOf(t.getMessage());
            Logs.add("WG 启动失败：" + note);
            return note;
        }
    }

    public static synchronized void stop() {
        try {
            Fougw.wgStop();
        } catch (Throwable ignore) {
        }
        active = false;
        Logs.add("WG 隧道已停止");
    }

    /** 进程被杀后 Go 侧状态清零，这里按 pref 补一次。 */
    public static synchronized void syncActive() {
        try {
            if (active && !Fougw.wgRunning()) {
                active = false;
            }
        } catch (Throwable ignore) {
        }
    }

    /** 客户端 .conf 全文；endpointIp 是本机在热点侧/局域网的地址。 */
    public static String clientConfig(Context c, String endpointIp) {
        String ip = endpointIp == null || endpointIp.isEmpty() ? "192.168.43.1" : endpointIp;
        return "[Interface]\n"
                + "PrivateKey = " + clientPriv(c) + "\n"
                + "Address = " + CLIENT_IP + "\n"
                + "DNS = " + CLIENT_DNS + "\n"
                + "MTU = " + DEFAULT_MTU + "\n"
                + "\n"
                + "[Peer]\n"
                + "PublicKey = " + serverPub(c) + "\n"
                + "AllowedIPs = 0.0.0.0/0\n"
                + "Endpoint = " + ip + ":" + port(c) + "\n"
                + "PersistentKeepalive = 25\n";
    }

    /**
     * 把 Go 侧的日志环拉出来写进界面日志。
     *
     * <p>日志环原来是 FOU 的轮询线程在拉；只开 WireGuard、不开网关的时候没人拉，
     * Go 侧的错误就彻底黑了，所以 WG 卡片刷新时也顺手拉一次（谁先拉谁拿到）。
     */
    public static void drainLogs() {
        try {
            String json = Fougw.logsJSON();
            if (json == null || json.length() <= 2) {
                return;
            }
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Logs.add("WG " + arr.getString(i));
            }
        } catch (Throwable ignore) {
            // Go 侧 panic 会以 Java 异常抛上来，必须兜住 Throwable
        }
    }

    /** Go 侧状态 JSON：running/port/peers(tx/rx/握手时间)/tcp/udp。 */
    public static String statsJson() {
        try {
            return Fougw.wgStats();
        } catch (Throwable t) {
            return "{\"running\":false,\"peers\":[]}";
        }
    }

    /** 给 UI 用的一行摘要。 */
    public static String summary() {
        try {
            JSONObject o = new JSONObject(statsJson());
            if (!o.optBoolean("running", false)) {
                return "";
            }
            org.json.JSONArray peers = o.optJSONArray("peers");
            if (peers == null || peers.length() == 0) {
                return "等待客户端连接";
            }
            JSONObject p = peers.getJSONObject(0);
            long ago = p.optLong("handshakeAgo", -1);
            long rx = p.optLong("rxBytes", 0);
            long tx = p.optLong("txBytes", 0);
            if (ago < 0) {
                return "等待握手（客户端还没连上来）";
            }
            return "已握手 " + ago + "s 前    ↓" + human(rx) + "  ↑" + human(tx);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String human(long n) {
        if (n < 1024) {
            return n + "B";
        }
        if (n < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1fK", n / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1fM", n / 1048576.0);
    }
}
