package com.dosgo.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.dosgo.gateway.net.Logs;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 客户端模式：把整台设备的 IP 流量原样丢给网关，不依赖系统代理。
 *
 * 为什么需要它：Android 的 Wi-Fi 静态代理是存在「网络配置」里的，
 * 首次手动配置会生效，但 Wi-Fi 自动重连后系统不会重新下发代理设置
 * （老 ROM 比如中兴远航 10 尤其明显），于是表现成「连得上但打不开网页 / DNS 报错」。
 * 代理设置这块我们改不了，只能绕开它 —— 用 VpnService 抓 TUN，
 * 把原始 IP 包按 FOU（IP-in-UDP）发给网关，网关那边的 gVisor 协议栈负责终结。
 *
 * 客户端不需要任何协议栈：一个 UDP socket 就够。
 * 网关的 FOU 层按「内层 IP:端口」索引外层地址，所以同一个 socket 可以承载所有连接。
 */
public class ClientVpnService extends VpnService {

    public static final String ACTION_START = "com.dosgo.gateway.action.CLIENT_START";
    public static final String ACTION_STOP = "com.dosgo.gateway.action.CLIENT_STOP";
    public static final String EXTRA_GW = "gw";
    public static final String EXTRA_PORT = "port";

    public static volatile boolean running = false;
    public static volatile String gwIp = "";
    public static volatile int gwPort = 5555;
    public static volatile long upPkts = 0;
    public static volatile long downPkts = 0;
    public static volatile long upBytes = 0;
    public static volatile long downBytes = 0;
    public static volatile String lastError = "";

    /** TUN 里本机的地址（只在这条隧道里有效）。 */
    private static final String TUN_IP = "10.7.0.2";
    private static final int TUN_MTU = 1400;
    private static final int BUF = 32768;
    /**
     * FOU 外层 UDP socket 的内核收发缓冲。Android 默认值常只有一两百 KB，
     * 蜂窝侧一个大窗口突发就能把它灌满；UDP 不重传，丢一个包内层 TCP 就退窗。
     * 内核会按 net.core.rmem_max/wmem_max 截断，所以下面读回实际值并写日志。
     */
    private static final int UDP_BUF = 1 << 20;

    private ParcelFileDescriptor tun;
    private DatagramSocket udp;
    private Thread upThread;
    private Thread downThread;
    private android.net.ConnectivityManager.NetworkCallback netCb;
    private long lastKickMs = 0L;
    /** 泵线程代际号：restartSelf() 会换一个新 TUN，旧泵线程必须立刻退出。 */
    private volatile long pumpGen = 0L;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 8+ 用 startForegroundService() 拉起服务后，必须在 5 秒内调用
        // startForeground()，否则系统抛 ForegroundServiceDidNotStartInTimeException
        // 直接杀掉整个进程 —— 表现就是「点一下客户端隧道，App 直接闪退」。
        // 所以第一件事无条件挂前台通知，绝不能放在 b.establish() 之后：
        // VPN 授权被别的 App 占用、或者 establish() 抛异常时，那条路径根本走不到。
        if (intent == null || !ACTION_STOP.equals(intent.getAction())) {
            if (!foreground()) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (intent == null) {
            // 被系统重启但没有 Intent：没有网关地址可用，不要留一个空壳服务
            Logs.add("客户端隧道重启缺少参数，已停止");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        String gw = intent.getStringExtra(EXTRA_GW);
        int port = intent.getIntExtra(EXTRA_PORT, 5555);
        if (gw == null || gw.trim().isEmpty()) {
            lastError = "网关地址为空";
            Logs.add("客户端隧道启动失败：网关地址为空");
            stopSelf();
            return START_NOT_STICKY;
        }
        startTunnel(gw.trim(), port);
        if (!running) {
            // establish() 失败或抛异常：收尾，别留一个没有隧道的僵尸前台服务
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startTunnel(String gw, int port) {
        stopTunnel();
        try {
            // MainActivity 已请求过授权，但服务也可能被开机广播 / 外部 Intent
            // 直接拉起。授权被系统撤销或别的 VPN 占用时，establish() 会抛
            // SecurityException —— 提前判掉，给可读错误而不是静默失败。
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                lastError = "VPN 授权失效，请在 App 里重新点「启动客户端隧道」";
                Logs.add("客户端隧道建立失败：" + lastError);
                closeQuietly();
                return;
            }
            Builder b = new Builder();
            b.setSession("网关客户端");
            b.setMtu(TUN_MTU);
            b.addAddress(TUN_IP, 32);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("223.5.5.5");
            b.addDnsServer("119.29.29.29");
            // 关键：本应用自己不进隧道，否则发给网关的 UDP 会被自己再抓一遍，直接死循环
            try {
                b.addDisallowedApplication(getPackageName());
            } catch (Throwable ignore) {
            }
            tun = b.establish();
            if (tun == null) {
                lastError = "系统没有授予 VPN（可能被其他 VPN 占用）";
                Logs.add("客户端隧道建立失败：" + lastError);
                closeQuietly();
                return;
            }
            gwIp = gw;
            gwPort = port;
            upPkts = downPkts = upBytes = downBytes = 0;
            lastError = "";
            running = true;
            // 重复 startForeground 是合法的：这次只是把通知文案从「正在启动」
            // 更新成真实网关地址。restartSelf() 也走这条路重新挂通知。
            if (!foreground()) {
                lastError = "前台服务通知更新失败";
                Logs.add("客户端前台通知更新失败");
                closeQuietly();
                return;
            }
            Logs.add("客户端隧道已启动 → " + gw + ":" + port + "（整机流量走 FOU，无需配置系统代理）");
            // socket 的创建/连接放在泵线程里（主线程做网络操作会抛 NetworkOnMainThreadException）
            startPumpLoop();
            watchNetwork();
        } catch (Throwable t) {
            lastError = "启动异常：" + t;
            Logs.add("客户端隧道启动异常：" + t);
            closeQuietly();
        }
    }

    /**
     * 泵循环：一个 socket 出错就重建，整条隧道自愈。
     * Wi-Fi 断开重连、切飞行模式、从 Wi-Fi 切到 4G 都会让旧 socket 的路由失效，
     * 这里靠「send 抛错 / receive 抛错 / 系统网络回调」三处触发重连。
     */
    private void startPumpLoop() {
        if (tun == null) {
            return;
        }
        // 每次建隧道领一个代际号。旧泵线程可能阻塞在旧 TUN 的 read() 上，
        // 关掉 fd 后会抛 EBADF 并进入重连循环；只看 running 的话，
        // startTunnel() 把 running 重新置 true 后旧线程会「复活」，
        // 和新泵线程并存，每 1.5s 无限重连刷日志、抢 socket。
        final long myGen = ++pumpGen;
        final FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        final FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        upThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] upBuf = new byte[BUF];
                DatagramPacket upPkt = new DatagramPacket(upBuf, upBuf.length);
                while (running && myGen == pumpGen) {
                    DatagramSocket s = null;
                    Thread down = null;
                    try {
                        s = new DatagramSocket();
                        s.setSoTimeout(0);
                        try {
                            s.setReceiveBufferSize(UDP_BUF);
                            s.setSendBufferSize(UDP_BUF);
                            Logs.add("FOU socket 缓冲 rx=" + s.getReceiveBufferSize()
                                    + "B tx=" + s.getSendBufferSize() + "B（申请 " + UDP_BUF + "B）");
                        } catch (Throwable ignore) {
                        }
                        s.connect(InetAddress.getByName(gwIp), gwPort);
                        udp = s;
                        final DatagramSocket fs = s;
                        down = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                byte[] b = new byte[BUF];
                                // 复用同一个包对象：每包 new DatagramPacket 在几千 pps 下
                                // 会制造明显的 GC 抖动，直接表现为吞吐周期性掉坑
                                DatagramPacket dp = new DatagramPacket(b, b.length);
                                while (running && myGen == pumpGen) {
                                    try {
                                        // receive() 会把 length 改成实际收到长度，下次必须复位
                                        dp.setLength(b.length);
                                        fs.receive(dp);
                                        int len = dp.getLength();
                                        out.write(b, 0, len);
                                        downPkts++;
                                        downBytes += len;
                                    } catch (Throwable t) {
                                        break;
                                    }
                                }
                            }
                        }, "gw-vpn-down");
                        down.start();
                        downThread = down;
                        while (running && myGen == pumpGen) {
                            int n = in.read(upBuf);
                            if (n <= 0) {
                                continue;
                            }
                            try {
                                upPkt.setLength(n);
                                fs.send(upPkt);
                                upPkts++;
                                upBytes += n;
                            } catch (Throwable t) {
                                if (running) {
                                    Logs.add("隧道上行断开，准备重连：" + t.getMessage());
                                }
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        if (running) {
                            Logs.add("隧道连接失败，1.5 秒后重试：" + t.getMessage());
                        }
                    } finally {
                        if (s != null) {
                            try {
                                s.close();
                            } catch (Throwable ignore) {
                            }
                        }
                        if (down != null) {
                            try {
                                down.join(500);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                    if (!running || myGen != pumpGen) {
                        break;
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "gw-vpn-up");
        upThread.start();
    }

    /** 整条隧道重来一遍：关掉旧 TUN 重新 establish（底层网络变化后的可靠恢复方式）。 */
    private void restartSelf() {
        String gw = gwIp;
        int p = gwPort;
        if (gw == null || gw.isEmpty()) {
            return;
        }
        Logs.add("重启客户端隧道 → " + gw + ":" + p);
        stopTunnel();
        startTunnel(gw, p);
    }

    /** 网络恢复时把旧 socket 关掉，让泵循环自然重建一条（Wi-Fi 重连后的关键一步）。 */
    private void kickSocket() {
        DatagramSocket s = udp;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 监听系统网络变化：Wi-Fi 重连/切换网络后立刻重建隧道连接。 */
    private void watchNetwork() {
        if (netCb != null) {
            return;
        }
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network n) {
                    if (!running) {
                        return;
                    }
                    // VPN 自己成为默认网络时也会回调，忽略它，否则刚建好就自踢一次
                    try {
                        android.net.ConnectivityManager c =
                                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                        android.net.NetworkCapabilities nc = c == null ? null : c.getNetworkCapabilities(n);
                        if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            return;
                        }
                    } catch (Throwable ignore) {
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastKickMs < 5000) {
                        return;
                    }
                    lastKickMs = now;
                    // 只换 socket 不够：底层网络断开时系统会把 VPN 网络一起下线，
                    // 必须重新 establish 一个新的 TUN，VPN 才会重新参与路由。
                    Logs.add("底层网络已恢复，重启客户端隧道");
                    kickSocket();
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (running) {
                                restartSelf();
                            }
                        }
                    }, 1500);
                }
            };
            // 刚 establish VPN 时系统会立刻回调一次 onAvailable（VPN 成为默认网络）。
            // MIUI 上这次回调的 NetworkCapabilities 常常不带 TRANSPORT_VPN，
            // 光靠 hasTransport 过滤不住，会把刚建好的隧道自己踢掉重启一次。
            // 把节流起点设为注册时刻，5 秒内的回调一律忽略。
            lastKickMs = System.currentTimeMillis();
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable t) {
            Logs.add("注册网络回调失败（不影响隧道）：" + t);
        }
    }

    private void unwatchNetwork() {
        if (netCb == null) {
            return;
        }
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(netCb);
            }
        } catch (Throwable ignore) {
        }
        netCb = null;
    }

    private void stopTunnel() {
        if (!running) {
            closeQuietly();
            return;
        }
        running = false;
        Logs.add("客户端隧道已停止  上行 " + upPkts + " 包 / 下行 " + downPkts + " 包");
        closeQuietly();
    }

    private void closeQuietly() {
        running = false;
        // 作废所有在跑的泵线程：restartSelf() 里 running 会被重新置 true，
        // 必须靠代际号让旧线程退出，不能只依赖 running。
        pumpGen++;
        unwatchNetwork();
        if (udp != null) {
            try {
                udp.close();
            } catch (Throwable ignore) {
            }
            udp = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Throwable ignore) {
            }
            tun = null;
        }
        upThread = null;
        downThread = null;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
    }

    /** 返回 false 表示系统拒绝了前台服务，此时必须 stopSelf()，否则 5 秒后被杀。 */
    private boolean foreground() {
        try {
            String ch = "gateway_client";
            String text = (gwIp == null || gwIp.isEmpty())
                    ? "正在启动客户端隧道…"
                    : "整机流量经 " + gwIp + ":" + gwPort + " 转发";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(ch, "客户端隧道",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(c);
            }
            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                n = new Notification.Builder(this, ch)
                        .setContentTitle("网关客户端")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("网关客户端")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .build();
            }
            startForeground(1002, n);
            return true;
        } catch (Throwable t) {
            // 这里绝不能写「不影响 tunnel」：startForeground 失败 = 系统会杀进程。
            Logs.add("客户端前台服务启动失败（系统拒绝，必须停止服务）：" + t);
            lastError = "前台服务启动失败：" + t;
            return false;
        }
    }

    @Override
    public void onRevoke() {
        // 用户在系统里关掉了 VPN / 切换了其它 VPN
        Logs.add("客户端隧道被系统撤销");
        stopTunnel();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }
}
