package com.dosgo.gateway;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

import com.dosgo.gateway.net.Logs;

import java.util.Locale;

/**
 * Wi-Fi Direct（P2P）组网：本机当 GO，SSID 由 WifiP2pConfig.Builder#setNetworkName 指定。
 *
 * P2P 规范强制 SSID 必须以 "DIRECT-xy" 开头（x/y 取自大小写字母与数字），
 * 所以最终名字形如 DIRECT-gwGateway —— 后缀完全可控，前缀两字符也是我们自己写死的。
 */
public class P2pController {

    /** P2P 规范强制 SSID 以 DIRECT-xy 开头，后面才是我们自己的名字。 */
    public static final String FIXED_SSID = "DIRECT-gwGateway";
    public static final String FIXED_PASS = "gateway888";

    public static volatile boolean active = false;
    public static volatile boolean starting = false;
    public static volatile String ssid = "";
    public static volatile String pass = "";
    public static volatile String iface = "";
    /** 用户点了「开组」就为 true，直到用户点「解散」（只用于按钮状态，不做自动重建）。 */
    public static volatile boolean wantActive = false;

    private static WifiP2pManager mgr;
    private static WifiP2pManager.Channel chan;
    /** 建组失败的重试计数：开机阶段 Wi-Fi P2P 服务常还没就绪，前几次会直接返回 ERROR。 */
    private static int retry = 0;
    private static final int MAX_RETRY = 3;

    // ------------------------------------------------------------ prefs（是否「启动网关时自动开组」）

    private static final String PREF = "gateway_p2p";
    private static final String KEY_AUTO = "auto";

    public static boolean prefAuto(Context ctx) {
        // DE 存储：开机解锁前也要能读到「自动开固定组」这个开关
        return AppPrefs.dp(ctx, PREF).getBoolean(KEY_AUTO, false);
    }

    public static void setPrefAuto(Context ctx, boolean v) {
        AppPrefs.dp(ctx, PREF).edit().putBoolean(KEY_AUTO, v).apply();
    }

    public static synchronized String start(Context ctx, final String name, final String passphrase) {
        if (active) {
            return null;
        }
        if (Build.VERSION.SDK_INT < 29) {
            return "P2P 自定义 SSID 需要 Android 10+";
        }
        Context app = ctx.getApplicationContext();
        if (mgr == null) {
            mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
            if (mgr == null) {
                return "本机不支持 Wi-Fi Direct";
            }
            chan = mgr.initialize(app, Looper.getMainLooper(), new WifiP2pManager.ChannelListener() {
                @Override
                public void onChannelDisconnected() {
                    // Wi-Fi 被关掉/重启后框架会断开 P2P 通道，丢掉句柄让下次 start 重新 initialize
                    Logs.add("P2P 通道断开（Wi-Fi 状态变化），组随之失效");
                    mgr = null;
                    chan = null;
                    active = false;
                    wantActive = false;
                }
            });
            if (chan == null) {
                return "P2P 通道初始化失败";
            }
        }
        WifiP2pConfig cfg;
        try {
            cfg = new WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build();
        } catch (Throwable t) {
            return "P2P 配置非法：" + t;
        }
        starting = true;
        wantActive = true;
        Logs.add("P2P 请求建组 SSID=" + name);
        try {
            mgr.createGroup(chan, cfg, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    starting = false;
                    retry = 0;
                    Logs.add("P2P createGroup 返回成功，等组信息…");
                    mgr.requestGroupInfo(chan, new WifiP2pManager.GroupInfoListener() {
                        @Override
                        public void onGroupInfoAvailable(WifiP2pGroup g) {
                            if (g == null) {
                                // 建组刚成功时 groupInfo 常常还读不到。SSID/密码就是我们要的那个
                                // （P2P 的 networkName 不会被系统随机化），直接用请求参数兜底。
                                ssid = name;
                                pass = passphrase;
                                iface = "";
                                active = ifaceAlive();
                                if (active) {
                                    Logs.add("P2P 组已建立（组信息暂不可读，用请求参数）SSID=" + ssid);
                                } else {
                                    Logs.add("P2P 组信息暂不可读，等接口就绪后自动标记");
                                }
                                return;
                            }
                            ssid = g.getNetworkName() == null ? "" : g.getNetworkName();
                            pass = g.getPassphrase() == null ? "" : g.getPassphrase();
                            iface = g.getInterface() == null ? "" : g.getInterface();
                            active = true;
                            Logs.add("P2P 组已建立  SSID=" + ssid
                                    + "  PASS=" + pass
                                    + "  IF=" + iface
                                    + "  GO=" + g.isGroupOwner());
                        }
                    });
                }

                @Override
                public void onFailure(final int reason) {
                    active = false;
                    starting = false;
                    Logs.add("P2P createGroup 失败 reason=" + reason + " (" + reasonText(reason) + ")");
                    // 刚开机（尤其解锁瞬间）P2P 服务往往还没就绪，直接返回 ERROR。
                    // 这里只做有限次重试，不是常驻守护：三次都不行就认输，等用户手动点。
                    if (wantActive && retry < MAX_RETRY) {
                        retry++;
                        final int n = retry;
                        Logs.add("P2P 建组重试中（第 " + n + "/" + MAX_RETRY + " 次，2.5 秒后）");
                        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (wantActive && !active && !starting) {
                                    start(app, name, passphrase);
                                }
                            }
                        }, 2500L);
                    }
                }
            });
            return null;
        } catch (SecurityException e) {
            return "缺少权限：" + e.getMessage();
        } catch (Throwable t) {
            return "P2P 异常：" + t;
        }
    }

    public static boolean isStarting() {
        return starting;
    }

    /** 重置失败计数：「解锁后再补一次」这种新一轮尝试要重新给满次数。 */
    public static void resetRetry() {
        retry = 0;
    }

    public static synchronized void stop() {
        active = false;
        starting = false;
        wantActive = false;
        retry = 0;
        if (mgr == null || chan == null) {
            return;
        }
        try {
            mgr.removeGroup(chan, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Logs.add("P2P 组已解散");
                }

                @Override
                public void onFailure(int reason) {
                    Logs.add("P2P 解散失败 reason=" + reason);
                }
            });
        } catch (Throwable t) {
            Logs.add("P2P 解散异常 " + t);
        }
    }

    // ------------------------------------------------------------ 存活检测

    /** 正在运行的 p2p 网卡名（没有则返回空串）。 */
    public static String ifaceName() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
                if (!n.startsWith("p2p") || !ni.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    if (as.nextElement() instanceof java.net.Inet4Address) {
                        return ni.getName();
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    /**
     * 用网卡状态补正 active。
     *
     * 建组成功后系统常常还没把组信息准备好，requestGroupInfo 拿到 null，
     * 这时 active 会被判成 false，界面就显示「未开启」——但网卡其实已经有地址了。
     * 界面每次刷新前调一次，把状态纠回来。
     */
    public static void syncActive() {
        if (active || !ifaceAlive()) {
            return;
        }
        active = true;
        if (ssid == null || ssid.isEmpty()) {
            ssid = FIXED_SSID;
        }
        if (pass == null || pass.isEmpty()) {
            pass = FIXED_PASS;
        }
        if (iface == null || iface.isEmpty()) {
            iface = ifaceName();
        }
        Logs.add("P2P 组已就绪（按网卡状态补正）IF=" + iface);
    }

    /** 系统是否已经把这个组拆了：网卡上还有没有 p2p* 的 IPv4 地址。 */
    public static boolean ifaceAlive() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
                if (!n.startsWith("p2p")) {
                    continue;
                }
                if (!ni.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    if (as.nextElement() instanceof java.net.Inet4Address) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static String reasonText(int r) {
        switch (r) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "设备不支持";
            case WifiP2pManager.BUSY:
                return "系统忙（可能正在连 Wi-Fi 或已有组）";
            case WifiP2pManager.ERROR:
                return "内部错误";
            default:
                return "未知";
        }
    }

    /** 探测：应用进程能不能绑 UDP 67（自己写 DHCP 服务的前提）。 */
    public static String probeDhcpPort() {
        StringBuilder sb = new StringBuilder();
        sb.append("bind67=").append(tryBind(67));
        sb.append("  bind1067=").append(tryBind(1067));
        return sb.toString();
    }

    private static String tryBind(int port) {
        java.net.DatagramSocket s = null;
        try {
            s = new java.net.DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(port));
            return "OK";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
