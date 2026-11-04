package com.dosgo.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.dosgo.gateway.net.Logs;
import com.dosgo.gateway.net.NetUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * 热点控制：走 WifiManager#startLocalOnlyHotspot（Local-Only Hotspot）。
 *
 * 为什么用 Local-Only Hotspot 而不是系统热点（设置里的「个人热点 / 网络共享」）：
 *  - 系统热点属于「Tethering」，第三方应用无权开关，必须用户手动去设置里点；
 *  - 本应用只提供 SOCKS5/HTTP/FOU 代理，客户端在热点网段里连代理即可上网，
 *    根本不需要系统帮它做 NAT 上游，所以 Local-Only Hotspot 正好够用；
 *  - Local-Only Hotspot 是普通应用唯一能「代码里一键开启」的 AP 形态。
 *
 * 关于 SSID：Android 13(API 33) 起可以带 SoftApConfiguration 指定 SSID/密码。
 * 部分 ROM 会做隐私处理，把 SSID 覆盖成 AndroidShare_xxxx，
 * 这种情况我们读回真实 SSID 显示给用户（脚本/客户端按真实 SSID 连接即可）。
 */
public final class HotspotController {

    public static final String PREF = "gateway_hotspot";
    public static final String KEY_SSID = "ssid";
    public static final String KEY_PASS = "pass";
    public static final String KEY_AUTO = "auto";

    public static final String DEFAULT_SSID = "Gateway";
    public static final String DEFAULT_PASS = "gateway888";

    /** 热点是否已开启（以系统回调为准）。 */
    public static volatile boolean active = false;
    /** 实际生效的 SSID / 密码（可能与用户填写不同）。 */
    public static volatile String ssid = "";
    public static volatile String pass = "";
    /** 热点网段里本机的 IP（客户端要连的地址）。 */
    public static volatile String apIp = "";
    /** 附加说明 / 错误信息。 */
    public static volatile String note = "";
    /** 系统是否覆盖了我们指定的 SSID。 */
    public static volatile boolean ssidOverridden = false;
    /** 实际走的通道：custom = 自定义 SSID 的隐藏重载；standard = 标准 LOHS（系统随机 SSID）。 */
    public static volatile String path = "";
    /** 用户点了「开热点」就为 true，直到用户关掉（只用于按钮状态，不做自动重开）。 */
    public static volatile boolean wantActive = false;

    private static WifiManager.LocalOnlyHotspotReservation reservation;
    private static boolean starting = false;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Executor EXEC = new Executor() {
        @Override
        public void execute(Runnable r) {
            main.post(r);
        }
    };

    private HotspotController() {
    }

    // ------------------------------------------------------------ prefs

    public static SharedPreferences prefs(Context ctx) {
        // 放 DE 存储：开机解锁前也要能读到「自动开热点」这个开关
        return AppPrefs.dp(ctx, PREF);
    }

    public static String prefSsid(Context ctx) {
        return prefs(ctx).getString(KEY_SSID, DEFAULT_SSID);
    }

    public static String prefPass(Context ctx) {
        return prefs(ctx).getString(KEY_PASS, DEFAULT_PASS);
    }

    public static boolean prefAuto(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO, false);
    }

    public static void save(Context ctx, String s, String p, boolean auto) {
        prefs(ctx).edit()
                .putString(KEY_SSID, s)
                .putString(KEY_PASS, p)
                .putBoolean(KEY_AUTO, auto)
                .apply();
    }

    // ------------------------------------------------------------ 启动 / 停止

    public static boolean isStarting() {
        return starting;
    }

    /** 开热点。返回 null 表示已发起（结果看回调），否则返回错误串。 */
    public static synchronized String start(Context ctx) {
        if (active) {
            return null;
        }
        if (starting) {
            return "正在开启…";
        }
        final String wSsid = prefSsid(ctx);
        final String wPass = prefPass(ctx);
        if (wSsid == null || wSsid.trim().isEmpty()) {
            return "SSID 不能为空";
        }
        if (wPass == null || wPass.length() < 8) {
            return "热点密码至少 8 位";
        }
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return "拿不到 WifiManager";
        }
        note = "";
        starting = true;
        wantActive = true;
        WifiManager.LocalOnlyHotspotCallback cb = callback(wSsid.trim(), wPass);
        try {
            // 1) 先试「指定 SSID」的隐藏重载（SystemApi，公开 SDK 里没有，用反射）
            boolean custom = tryStartWithConfig(wm, wSsid.trim(), wPass, cb);
            if (custom) {
                path = "custom";
            } else {
                // 2) 退回标准 LOHS：能开，但 SSID 由系统随机生成
                path = "standard";
                wm.startLocalOnlyHotspot(cb, main);
                note = "本系统只允许随机 SSID，已按系统分配的名称开启";
            }
            Logs.add("热点请求已提交 SSID=" + wSsid);
            return null;
        } catch (SecurityException e) {
            starting = false;
            note = "缺少权限：" + e.getMessage();
            Logs.add(note);
            return note;
        } catch (Throwable t) {
            starting = false;
            note = "开启失败：" + t;
            Logs.add(note);
            return note;
        }
    }

    /**
     * 反射调用 WifiManager#startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)。
     * 这个重载在 AOSP 里是 SystemApi（第三方可编译不了，但运行时可调用），
     * 各大 ROM 对「非特权应用能不能自定义 SSID」的策略不一致，所以失败就返回 false 走标准 LOHS。
     */
    private static boolean tryStartWithConfig(WifiManager wm, String ssid, String pass,
                                              WifiManager.LocalOnlyHotspotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            Class<?> cfgCls = Class.forName("android.net.wifi.SoftApConfiguration");
            Class<?> builderCls = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object builder = builderCls.getConstructor().newInstance();
            builderCls.getMethod("setSsid", String.class).invoke(builder, ssid);
            // SECURITY_TYPE_WPA2_PSK == 1
            builderCls.getMethod("setPassphrase", String.class, int.class).invoke(builder, pass, 1);
            Object cfg = builderCls.getMethod("build").invoke(builder);
            java.lang.reflect.Method m = WifiManager.class.getMethod("startLocalOnlyHotspot",
                    cfgCls, Executor.class, WifiManager.LocalOnlyHotspotCallback.class);
            m.invoke(wm, cfg, EXEC, cb);
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            Logs.add("自定义 SSID 通道不可用（" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()) + "）");
            return false;
        }
    }

    private static WifiManager.LocalOnlyHotspotCallback callback(final String wSsid, final String wPass) {
        return new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                starting = false;
                reservation = r;
                active = true;
                ssid = wSsid;
                pass = wPass;
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        SoftApConfiguration c = r.getSoftApConfiguration();
                        if (c != null) {
                            String s = c.getSsid();
                            String p = c.getPassphrase();
                            if (s != null && !s.isEmpty()) {
                                ssid = s;
                            }
                            if (p != null) {
                                pass = p;
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                }
                ssidOverridden = !wSsid.equals(ssid);
                if (ssidOverridden) {
                    if ("custom".equals(path)) {
                        note = "已请求自定义 SSID，但系统强制覆盖为 " + ssid + "（该 ROM 限制），网络照样可用";
                    } else {
                        note = "该 ROM 不允许普通应用自定义 SSID（隐藏接口被拒），系统分配为 " + ssid;
                    }
                } else {
                    note = "";
                }
                apIp = detectApIp();
                Logs.add("热点已开启 " + ssid + " / " + pass + "  网关 " + (apIp.isEmpty() ? "待分配" : apIp));
                // ap0 的地址往往比热点回调晚一两秒才配上，这里补一次探测
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!active) {
                            return;
                        }
                        String ip = detectApIp();
                        if (ip != null && !ip.isEmpty() && !ip.equals(apIp)) {
                            apIp = ip;
                            Logs.add("热点网段本机 IP " + ip);
                        }
                    }
                }, 2500);
                if (ssidOverridden) {
                    Logs.add(note);
                }
            }

            @Override
            public void onStopped() {
                starting = false;
                active = false;
                reservation = null;
                apIp = "";
                note = "";
                wantActive = false;
                Logs.add("热点已关闭（系统回调）");
            }

            @Override
            public void onFailed(int reason) {
                starting = false;
                active = false;
                wantActive = false;
                reservation = null;
                note = "热点开启失败：" + reasonText(reason);
                Logs.add(note);
            }
        };
    }

    /** 关热点。 */
    public static synchronized void stop() {
        wantActive = false;
        try {
            if (reservation != null) {
                reservation.close();
            }
        } catch (Throwable ignore) {
        }
        reservation = null;
        active = false;
        starting = false;
        apIp = "";
        note = "";
    }

    // ------------------------------------------------------------ 辅助

    /** 热点网卡上的 IPv4（ap0 / swlan0 / wlan1 之类）。 */
    public static String detectApIp() {
        try {
            List<NetUtils.Nic> list = NetUtils.interfaces();
            for (int i = 0; i < list.size(); i++) {
                String n = list.get(i).name.toLowerCase(Locale.ROOT);
                if (n.startsWith("ap") || n.startsWith("swlan")
                        || n.startsWith("softap") || n.startsWith("wlan1")) {
                    return list.get(i).ip;
                }
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "没有可用信道";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "与当前 Wi-Fi 模式冲突（先断开 Wi-Fi 再试）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "系统策略禁止共享（检查是否开着「个人热点」或企业策略）";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
            default:
                return "系统拒绝（" + reason + "），可先断开 Wi-Fi 或稍后重试";
        }
    }
}
