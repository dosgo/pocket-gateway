package com.dosgo.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.dosgo.gateway.net.HttpProxyServer;
import com.dosgo.gateway.net.Logs;
import com.dosgo.gateway.net.NetUtils;
import com.dosgo.gateway.net.Socks5Server;

/** 前台服务：同时承载 SOCKS5 / HTTP / FOU 三个网关。 */
public class GatewayService extends Service {

    public static final String ACTION_START = "com.dosgo.gateway.action.START";
    public static final String ACTION_STOP = "com.dosgo.gateway.action.STOP";
    /** 只补一次「自动开 AP」，不动已经在跑的代理端口（解锁后由 BootReceiver 触发）。 */
    public static final String ACTION_AP_ENSURE = "com.dosgo.gateway.action.AP_ENSURE";
    public static final String EXTRA_SOCKS = "socks_port";
    public static final String EXTRA_HTTP = "http_port";
    public static final String EXTRA_FOU = "fou_port";

    private static final String CHANNEL = "gateway";
    private static final int NOTI_ID = 1001;

    public static volatile boolean running = false;
    public static volatile int lastSocksPort = 1080;
    public static volatile int lastHttpPort = 8080;
    public static volatile int lastFouPort = 5555;

    private Socks5Server socks;
    private HttpProxyServer http;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_AP_ENSURE.equals(intent.getAction())) {
            // 解锁后再来一次：Direct Boot 阶段 Wi-Fi 经常还没就绪，AP 建不起来
            if (running) {
                ensureAutoAp();
            } else {
                startAll(lastSocksPort, lastHttpPort, lastFouPort);
            }
            return START_NOT_STICKY;
        }
        int sp = intent == null ? lastSocksPort : intent.getIntExtra(EXTRA_SOCKS, lastSocksPort);
        int hp = intent == null ? lastHttpPort : intent.getIntExtra(EXTRA_HTTP, lastHttpPort);
        int fp = intent == null ? lastFouPort : intent.getIntExtra(EXTRA_FOU, lastFouPort);
        if (!running) {
            startAll(sp, hp, fp);
        }
        return START_NOT_STICKY;
    }

    /**
     * 按用户的「自动开」勾选把对应的 AP 打开（两个开关互斥）。
     *
     * 会被调两次：服务首次启动时一次；解锁后 {@code BOOT_COMPLETED} 再来一次。
     * 因为 Direct Boot 阶段（还没解锁）Wi-Fi 常常没就绪，第一次多半建不起来，
     * 第二次才是真正成功的那次。
     */
    private void ensureAutoAp() {
        try {
            if (P2pController.prefAuto(this)) {
                if (!P2pController.active && !P2pController.isStarting()) {
                    P2pController.resetRetry();   // 解锁后这一轮重新给满重试次数
                    String err = P2pController.start(this, P2pController.FIXED_SSID,
                            P2pController.FIXED_PASS);
                    Logs.add(err == null
                            ? "自动开 AP：已发起 Wi-Fi Direct 固定组（" + P2pController.FIXED_SSID + "）"
                            : "Wi-Fi Direct 组未开启：" + err);
                }
            } else if (HotspotController.prefAuto(this) && !HotspotController.active) {
                String err = HotspotController.start(this);
                Logs.add(err == null ? "自动开 AP：已发起 Local-Only 热点" : "热点未开启：" + err);
            }
        } catch (Throwable t) {
            Logs.add("自动开 AP 失败：" + t);
        }
    }

    private void startAll(int sp, int hp, int fp) {
        lastSocksPort = sp;
        lastHttpPort = hp;
        lastFouPort = fp;
        Logs.clear();
        startForegroundSafely(sp, hp, fp);
        acquireWakeLock();

        try {
            socks = new Socks5Server(sp);
            socks.start();
        } catch (Exception e) {
            Logs.add("SOCKS5 启动失败: " + e.getMessage());
            socks = null;
        }
        try {
            http = new HttpProxyServer(hp);
            http.start();
        } catch (Exception e) {
            Logs.add("HTTP 启动失败: " + e.getMessage());
            http = null;
        }
        try {
            FouEngine.start(fp, 1400);
        } catch (Throwable e) {
            Logs.add("FOU 启动失败: " + e.getMessage());
        }

        String ip = NetUtils.primaryIp();
        Logs.add("网关已就绪，本机 IP " + (ip == null ? "未知" : ip));

        ensureAutoAp();

        running = true;
        updateNotification(ip);
    }

    private void shutdown() {
        running = false;
        if (socks != null) {            socks.stop();
            socks = null;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        FouEngine.stop();
        HotspotController.stop();
        P2pController.stop();
        P2pController.stop();
        Logs.add("网关已停止");
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) {
            shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 通知 / 唤醒锁

    private void startForegroundSafely(int sp, int hp, int fp) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "网关服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentTitle("网关运行中")
                .setContentText("SOCKS5 :" + sp + "  ·  HTTP :" + hp + "  ·  FOU :" + fp)
                .setSmallIcon(R.drawable.ic_gateway)
                .setOngoing(true)
                .setContentIntent(pi);
        startForeground(NOTI_ID, b.build());
    }

    private void updateNotification(String ip) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentTitle("网关运行中  " + (ip == null ? "" : ip))
                .setContentText("SOCKS5 :" + lastSocksPort + "  ·  HTTP :" + lastHttpPort
                        + "  ·  FOU :" + lastFouPort + "/udp")
                .setSmallIcon(R.drawable.ic_gateway)
                .setOngoing(true)
                .setContentIntent(pi);
        try {
            nm.notify(NOTI_ID, b.build());
        } catch (Exception ignore) {
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:service");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignore) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignore) {
        }
        wakeLock = null;
    }
}
