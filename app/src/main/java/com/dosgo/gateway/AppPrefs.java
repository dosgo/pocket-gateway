package com.dosgo.gateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 与界面无关的持久化设置：开机自启开关 + 上次用的端口。
 *
 * 单独放一份 prefs 是因为：开机自启要由广播接收器（没有界面）读取，
 * 端口也得在开机时能拿到用户上次填的值，否则自启时只能退回默认 1080/8080/5555。
 */
public final class AppPrefs {

    private static final String PREF = "gateway_main";
    private static final String KEY_BOOT = "boot_start";
    private static final String KEY_SOCKS = "socks_port";
    private static final String KEY_HTTP = "http_port";
    private static final String KEY_FOU = "fou_port";

    private AppPrefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean bootStart(Context c) {
        return p(c).getBoolean(KEY_BOOT, false);
    }

    public static void setBootStart(Context c, boolean v) {
        p(c).edit().putBoolean(KEY_BOOT, v).apply();
    }

    public static void savePorts(Context c, int sp, int hp, int fp) {
        p(c).edit()
                .putInt(KEY_SOCKS, sp)
                .putInt(KEY_HTTP, hp)
                .putInt(KEY_FOU, fp)
                .apply();
    }

    public static int socksPort(Context c) {
        return p(c).getInt(KEY_SOCKS, 1080);
    }

    public static int httpPort(Context c) {
        return p(c).getInt(KEY_HTTP, 8080);
    }

    public static int fouPort(Context c) {
        return p(c).getInt(KEY_FOU, 5555);
    }
}
