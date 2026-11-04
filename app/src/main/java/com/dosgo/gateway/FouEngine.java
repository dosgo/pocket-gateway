package com.dosgo.gateway;

import com.dosgo.gateway.net.Logs;

import org.json.JSONArray;
import org.json.JSONObject;

import fougw.Fougw;

import java.util.Locale;

/**
 * FOU 网关进程内绑定。
 *
 * <p>协议栈是 Go 写的（gVisor netstack），通过 gomobile bind 编成 AAR，
 * Java 直接静态调用，跑在同一个进程里 —— 没有子进程、没有 stdout 管道、
 * 也不依赖 nativeLibraryDir 的可执行权限。
 *
 * <p>日志和统计改成轮询：Go 侧写进内存环形缓冲，这里每秒拉一次，
 * 因为进程内没有 stdin/stdout 可以当消息通道。
 */
public final class FouEngine {

    /** 每秒刷新的吞吐统计，供 UI 直接显示。 */
    public static volatile String stats = "";
    public static volatile boolean ready = false;

    private static Thread poller;
    private static volatile boolean running;

    private FouEngine() {
    }

    public static String version() {
        try {
            return Fougw.version();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    public static synchronized void start(int port, int mtu) throws Exception {
        stop();
        String cfg = "{\"port\":" + port + ",\"mtu\":" + mtu + ",\"verbose\":false}";
        Fougw.setStdout(false);
        Fougw.start(cfg);
        ready = true;
        running = true;

        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "fou-poll");
        poller.setDaemon(true);
        poller.start();

        Logs.add("FOU 进程内协议栈就绪，UDP " + port + " MTU " + mtu);
    }

    public static synchronized void stop() {
        running = false;
        ready = false;
        stats = "";
        try {
            Fougw.stop();
        } catch (Throwable t) {
            Logs.add("FOU 停止异常: " + t.getMessage());
        }
        Thread t = poller;
        poller = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public static boolean isRunning() {
        if (!running) {
            return false;
        }
        try {
            return Fougw.running();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void pollLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            drainLogs();
            refreshStats();
        }
    }

    private static void drainLogs() {
        try {
            String json = Fougw.logsJSON();
            if (json == null || json.length() <= 2) {
                return;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Logs.add("FOU " + arr.getString(i));
            }
        } catch (Throwable ignore) {
            // Go 侧 panic 会以 Java 异常抛上来，这里的 catch 必须兜住 Throwable
        }
    }

    private static void refreshStats() {
        try {
            JSONObject o = new JSONObject(Fougw.statsJSON());
            if (!o.optBoolean("running")) {
                return;
            }
            stats = String.format(Locale.US,
                    "收 %s / %d 包    发 %s / %d 包    连接 TCP %d · UDP %d    客户端 %d",
                    human(o.optLong("rxb")), o.optLong("rx"),
                    human(o.optLong("txb")), o.optLong("tx"),
                    o.optLong("tcp"), o.optLong("udp"), o.optLong("clients"));
        } catch (Throwable ignore) {
        }
    }

    private static String human(long v) {
        if (v < 1024) {
            return v + "B";
        }
        if (v < 1024 * 1024) {
            return String.format(Locale.US, "%.1fKB", v / 1024.0);
        }
        if (v < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1fMB", v / 1048576.0);
        }
        return String.format(Locale.US, "%.2fGB", v / 1073741824.0);
    }
}
