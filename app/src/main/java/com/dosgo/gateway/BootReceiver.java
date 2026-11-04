package com.dosgo.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启：收到 BOOT_COMPLETED 后，只要用户勾了「开机自动启动网关」，就把前台服务拉起来。
 * 热点是否跟着开，由服务里读的 HotspotController.prefAuto（「启动网关时自动开热点」）决定。
 *
 * 注意：BOOT_COMPLETED 广播是允许启动前台服务的（系统白名单里的一条豁免），
 * 但部分国产 ROM（HyperOS / EMUI / ColorOS）还要求用户在「自启动管理」里放行本应用，
 * 否则广播根本不会送到这里 —— 这一步只能由用户在系统设置里点一次。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 手动触发（仅限显式指定组件的广播，方便 adb 验证自启链路）。 */
    public static final String ACTION_MANUAL = "com.dosgo.gateway.action.START_NOW";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        boolean manual = ACTION_MANUAL.equals(a)
                && intent.getComponent() != null
                && context.getPackageName().equals(intent.getComponent().getPackageName());
        if (!boot && !updated && !manual) {
            return;
        }
        boolean want = manual || AppPrefs.bootStart(context);
        android.util.Log.i("GatewayBoot", "收到开机广播 " + a + "，自启开关=" + want
                + "，自动热点=" + HotspotController.prefAuto(context)
                + "，自动直连组=" + P2pController.prefAuto(context));
        if (!want) {
            return;
        }
        try {
            Intent i = new Intent(context, GatewayService.class);
            i.setAction(GatewayService.ACTION_START);
            i.putExtra(GatewayService.EXTRA_SOCKS, AppPrefs.socksPort(context));
            i.putExtra(GatewayService.EXTRA_HTTP, AppPrefs.httpPort(context));
            i.putExtra(GatewayService.EXTRA_FOU, AppPrefs.fouPort(context));
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            android.util.Log.i("GatewayBoot", "已拉起 GatewayService");
            com.dosgo.gateway.net.Logs.add("开机自启：网关已自动启动"
                    + "（端口 " + AppPrefs.socksPort(context) + "/"
                    + AppPrefs.httpPort(context) + "/" + AppPrefs.fouPort(context) + "）");
        } catch (Throwable t) {
            android.util.Log.e("GatewayBoot", "自启失败", t);
            com.dosgo.gateway.net.Logs.add("开机自启失败：" + t);
        }
    }
}
