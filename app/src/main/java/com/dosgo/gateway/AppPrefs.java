package com.dosgo.gateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 与界面无关的持久化设置：开机自启开关 + 上次用的端口。
 *
 * <b>为什么全放「设备加密存储」(DE, device-protected storage)：</b>
 * Android 7 起默认是文件级加密，数据分两个区：
 * <ul>
 *   <li><b>DE</b>：开机引导完成就能读，不用等用户解锁；</li>
 *   <li><b>CE</b>（普通的 {@code getSharedPreferences}）：要等用户输入一次锁屏密码才解密。</li>
 * </ul>
 * 「开机自启」如果存在 CE 里，解锁前的 {@code LOCKED_BOOT_COMPLETED} 阶段读到的永远是默认值 false，
 * 表现就是「不解锁不自启、解锁后才慢悠悠起来」。所以这里统一写到 DE，
 * 老版本留在 CE 的值首次访问时自动搬过去。
 */
public final class AppPrefs {

    private static final String PREF = "gateway_main";
    private static final String KEY_BOOT = "boot_start";
    private static final String KEY_SOCKS = "socks_port";
    private static final String KEY_HTTP = "http_port";
    private static final String KEY_FOU = "fou_port";
    /** 标记：CE → DE 搬迁已完成，避免每次启动都重复尝试。 */
    private static final String KEY_MOVED = "_moved_to_de";

    private AppPrefs() {
    }

    /**
     * 取一个「解锁前也能读」的 SharedPreferences。
     *
     * 首次调用会把同名的旧 CE 文件整体搬到 DE（{@code moveSharedPreferencesFrom}），
     * 之后一直读 DE。设备不支持 DE 时退回普通存储，功能不受影响。
     */
    public static SharedPreferences dp(Context c, String name) {
        Context app = c.getApplicationContext();
        Context de;
        try {
            de = app.createDeviceProtectedStorageContext();
        } catch (Throwable t) {
            de = null;   // 老系统 / 不支持文件级加密的设备
        }
        if (de == null) {
            return app.getSharedPreferences(name, Context.MODE_PRIVATE);
        }
        SharedPreferences p = de.getSharedPreferences(name, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_MOVED, false)) {
            boolean moved = false;
            try {
                // 先搬再打标记：反过来会先把 DE 文件创建出来，导致搬迁永远失败
                moved = de.moveSharedPreferencesFrom(app, name);   // 源文件会被删掉
            } catch (Throwable ignore) {
            }
            if (moved) {
                p = de.getSharedPreferences(name, Context.MODE_PRIVATE);   // 底层文件换过，重新取
            }
            p.edit().putBoolean(KEY_MOVED, true).commit();
        }
        return p;
    }

    private static SharedPreferences p(Context c) {
        return dp(c, PREF);
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
