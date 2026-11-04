package com.dosgo.gateway.net;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** 全局环形日志缓冲，UI 轮询取用。纯 Java，可在桌面环境直接跑。 */
public final class Logs {

    private static final int MAX = 200;
    private static final Deque<String> BUF = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Logs() {
    }

    public static synchronized void add(String msg) {
        BUF.addLast(FMT.format(new Date()) + "  " + msg);
        while (BUF.size() > MAX) {
            BUF.removeFirst();
        }
        try {
            android.util.Log.i("Gateway", msg);
        } catch (Throwable ignore) {
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : BUF) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        BUF.clear();
    }

    /** 最近 n 条（新的在最后）。状态页用它展示「客户端的请求到底有没有进来」。 */
    public static synchronized java.util.List<String> recent(int n) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        int skip = Math.max(0, BUF.size() - n);
        int i = 0;
        for (String s : BUF) {
            if (i++ >= skip) {
                out.add(s);
            }
        }
        return out;
    }
}
