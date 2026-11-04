package com.dosgo.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启。
 *
 * <p>Android 7 起是文件级加密，开机分两个阶段，对应两条广播：
 * <ul>
 *   <li>{@code LOCKED_BOOT_COMPLETED}：解密完成、但<b>用户还没解锁</b>。只有 {@code directBootAware}
 *       的组件能收到，且只能访问 DE（设备加密）存储；</li>
 *   <li>{@code BOOT_COMPLETED}：用户解锁之后才发。</li>
 * </ul>
 * 只监听后者的话，机器开着不输密码就一直不会自启 —— 这也是之前「解锁后过一会儿才起来」的原因。
 * 现在两条都监听：解锁前先尽量把服务拉起来，解锁后再补一次 AP（Direct Boot 阶段 Wi-Fi 往往还没就绪）。
 *
 * <p>前提：开关都读自 DE 存储（见 {@link AppPrefs#dp}），否则解锁前读到的是默认 false。
 *
 * <p>部分国产 ROM（HyperOS / EMUI / ColorOS）还要求用户在系统「自启动管理」里放行本应用，
 * 否则广播根本不会送到这里 —— 这一步只能在系统设置里点一次。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 手动触发（仅限显式指定组件的广播，方便 adb 验证自启链路）。 */
    public static final String ACTION_MANUAL = "com.dosgo.gateway.action.START_NOW";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        boolean locked = "android.intent.action.LOCKED_BOOT_COMPLETED".equals(a);
        boolean boot = locked
                || Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        boolean manual = ACTION_MANUAL.equals(a)
                && intent.getComponent() != null
                && context.getPackageName().equals(intent.getComponent().getPackageName());
        if (!boot && !updated && !manual) {
            return;
        }
        boolean want = manual || AppPrefs.bootStart(context);
        android.util.Log.i("GatewayBoot", "收到 " + a + "（" + (locked ? "解锁前" : "解锁后") + "）"
                + "，自启开关=" + want
                + "，自动热点=" + HotspotController.prefAuto(context)
                + "，自动直连组=" + P2pController.prefAuto(context)
                + "，服务运行中=" + GatewayService.running);
        if (!want) {
            return;
        }
        try {
            Intent i = new Intent(context, GatewayService.class);
            if (!locked && !manual && GatewayService.running) {
                // 服务已经在跑（解锁前就起来了），这里只补开 AP，不重启代理端口
                i.setAction(GatewayService.ACTION_AP_ENSURE);
            } else {
                i.setAction(GatewayService.ACTION_START);
                i.putExtra(GatewayService.EXTRA_SOCKS, AppPrefs.socksPort(context));
                i.putExtra(GatewayService.EXTRA_HTTP, AppPrefs.httpPort(context));
                i.putExtra(GatewayService.EXTRA_FOU, AppPrefs.fouPort(context));
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            android.util.Log.i("GatewayBoot", "已拉起 GatewayService，action=" + i.getAction());
            com.dosgo.gateway.net.Logs.add((locked ? "解锁前自启" : "开机自启") + "：网关已自动启动"
                    + "（端口 " + AppPrefs.socksPort(context) + "/"
                    + AppPrefs.httpPort(context) + "/" + AppPrefs.fouPort(context) + "）");
        } catch (Throwable t) {
            android.util.Log.e("GatewayBoot", "自启失败", t);
            com.dosgo.gateway.net.Logs.add("开机自启失败：" + t);
        }
    }
}
