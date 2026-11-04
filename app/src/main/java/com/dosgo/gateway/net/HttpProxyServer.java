package com.dosgo.gateway.net;

import com.dosgo.gateway.GatewayService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 代理。
 *
 * 支持三件事：
 *   1) CONNECT 隧道（HTTPS 全靠它，代理只搬密文，不解密、不需要证书，所以不会触发证书固定）；
 *   2) 绝对 URI 的明文请求转发；
 *   3) 直接访问本机时的状态页 / PAC / DNS 诊断。
 *
 * 关键点：客户端把「域名」交给这里解析，所以热点/P2P 组没有上游 DNS 也能上网。
 */
public class HttpProxyServer {

    private final int port;
    private volatile ServerSocket server;
    private volatile boolean running;

    public HttpProxyServer(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port), 128);
        server = ss;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
        Logs.add("HTTP 已启动  0.0.0.0:" + port);
    }

    public void stop() {
        running = false;
        Relay.closeQuietly(server);
        server = null;
    }

    private void acceptLoop() {
        ServerSocket ss = server;
        while (running && ss != null && !ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(s);
                        } catch (Exception e) {
                            Relay.closeQuietly(s);
                        }
                    }
                }, "http-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handle(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        s.setSoTimeout(30000);
        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();

        String requestLine = readLine(in, 8192);
        if (requestLine == null || requestLine.isEmpty()) {
            Relay.closeQuietly(s);
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            writeSimple(out, 400, "Bad Request", "请求行非法");
            Relay.closeQuietly(s);
            return;
        }
        String method = parts[0];
        String target = parts[1];

        List<String> headers = new ArrayList<String>();
        int n = 0;
        String line;
        while ((line = readLine(in, 8192)) != null && !line.isEmpty() && ++n <= 200) {
            headers.add(line);
        }

        // 1) CONNECT 隧道
        if ("CONNECT".equalsIgnoreCase(method)) {
            String[] hp = splitHostPort(target, 443);
            Socket remote = null;
            try {
                remote = Relay.connectAny(hp[0], Integer.parseInt(hp[1]), 6000);
                out.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: Gateway/1.0\r\n\r\n"
                        .getBytes("US-ASCII"));
                out.flush();
                s.setSoTimeout(0);
                Logs.add("HTTP CONNECT " + hp[0] + ":" + hp[1] + "  ← " + src(s));
                Relay.bridge(s, in, out, remote, remote.getInputStream(), remote.getOutputStream());
            } catch (UnknownHostException uhe) {
                Relay.closeQuietly(remote);
                writeSimple(out, 502, "Bad Gateway",
                        errorPage(hp[0] + ":" + hp[1], "域名解析失败（手机侧 DNS 查不到这个域名）"));
                Relay.closeQuietly(s);
                Logs.add("HTTP CONNECT 解析失败 " + hp[0] + " " + uhe.getMessage());
            } catch (Exception e) {
                Relay.closeQuietly(remote);
                writeSimple(out, 502, "Bad Gateway",
                        errorPage(hp[0] + ":" + hp[1], "连不上目标：" + e.getMessage()));
                Relay.closeQuietly(s);
                Logs.add("HTTP CONNECT 失败 " + hp[0] + ":" + hp[1] + " " + e.getMessage());
            }
            return;
        }

        // 2) 不带 scheme 的请求 → 当作直接访问本机（状态页 / PAC / DNS 诊断）
        if (!target.toLowerCase().startsWith("http://")) {
            String pathOnly = target;
            int q = pathOnly.indexOf('?');
            if (q >= 0) {
                pathOnly = pathOnly.substring(0, q);
            }
            String lower = pathOnly.toLowerCase();
            if (lower.endsWith("/proxy.pac") || lower.endsWith("/wpad.dat")) {
                Logs.add("HTTP 下发 PAC " + pathOnly);
                writePac(out);
            } else if (lower.startsWith("/dns")) {
                writeDns(out, dnsHostArg(target));
            } else {
                writeSimple(out, 200, "OK", statusPage());
            }
            Relay.closeQuietly(s);
            return;
        }

        // 3) 明文 HTTP 转发
        URL url = new URL(target);
        String host = url.getHost();
        int p = url.getPort() == -1 ? 80 : url.getPort();
        String path = url.getFile();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        Socket remote = null;
        try {
            remote = Relay.connectAny(host, p, 6000);
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            for (String h : headers) {
                String lh = h.toLowerCase();
                if (lh.startsWith("proxy-connection:")
                        || lh.startsWith("proxy-authorization:")
                        || lh.startsWith("connection:")
                        || lh.startsWith("keep-alive:")) {
                    continue;
                }
                if (lh.startsWith("host:")) {
                    hasHost = true;
                }
                sb.append(h).append("\r\n");
            }
            if (!hasHost) {
                sb.append("Host: ").append(p == 80 ? host : host + ":" + p).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            OutputStream rout = remote.getOutputStream();
            rout.write(sb.toString().getBytes("UTF-8"));
            rout.flush();
            s.setSoTimeout(0);
            Logs.add("HTTP " + method + " " + host + path + "  ← " + src(s));
            Relay.bridge(s, in, out, remote, remote.getInputStream(), rout);
        } catch (UnknownHostException uhe) {
            Relay.closeQuietly(remote);
            writeSimple(out, 502, "Bad Gateway",
                    errorPage(host + ":" + p, "域名解析失败（手机侧 DNS 查不到这个域名）"));
            Relay.closeQuietly(s);
            Logs.add("HTTP 解析失败 " + host + " " + uhe.getMessage());
        } catch (Exception e) {
            Relay.closeQuietly(remote);
            writeSimple(out, 502, "Bad Gateway",
                    errorPage(host + ":" + p, "连不上目标：" + e.getMessage()));
            Relay.closeQuietly(s);
        }
    }

    private static String dnsHostArg(String uri) {
        int q = uri.indexOf('?');
        if (q >= 0) {
            String[] kvs = uri.substring(q + 1).split("&");
            for (String kv : kvs) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if ("host".equalsIgnoreCase(k)) {
                    String v = eq < 0 ? "" : kv.substring(eq + 1);
                    try {
                        return java.net.URLDecoder.decode(v, "UTF-8");
                    } catch (Exception e) {
                        return v;
                    }
                }
            }
        }
        int sl = uri.indexOf("/dns/");
        if (sl >= 0) {
            return uri.substring(sl + 5);
        }
        return null;
    }

    /**
     * DNS 诊断页：客户端浏览器直接打开就能看到「网关替它解析出来的结果」。
     * 客户端本地 DNS 在热点/P2P 组里必然不可用，所以这个页面是区分
     * 「代理没生效」和「域名真不存在」最直接的办法。
     */
    private void writeDns(OutputStream out, String host) {
        StringBuilder sb = new StringBuilder();
        if (host == null || host.trim().isEmpty()) {
            sb.append("用法: /dns?host=www.baidu.com\n");
        } else {
            host = host.trim();
            sb.append("host: ").append(host).append('\n');
            long t0 = System.currentTimeMillis();
            try {
                java.net.InetAddress[] addrs = Dns.resolve(host);
                sb.append("result: OK  (").append(System.currentTimeMillis() - t0).append("ms)\n");
                for (java.net.InetAddress a : addrs) {
                    sb.append("  ").append(a.getHostAddress()).append('\n');
                }
            } catch (Throwable t) {
                sb.append("result: FAIL  (").append(System.currentTimeMillis() - t0).append("ms)\n");
                sb.append("reason: ").append(t.getMessage()).append('\n');
            }
            sb.append("trace: ").append(Dns.lastTrace).append('\n');
            Logs.add("DNS 诊断 " + host + " -> " + (sb.indexOf("result: OK") >= 0 ? "OK" : "FAIL"));
        }
        writeSimple(out, 200, "OK", sb.toString());
    }

    /**
     * PAC（Proxy Auto-Config）：Windows「使用设置脚本」/ macOS「自动代理配置」/ 浏览器
     * 只要填 http://本机IP:端口/proxy.pac 一个地址，就能同时拿到 HTTP 和 SOCKS5 代理。
     * 注意：DHCP 自动下发（option 252 / WPAD）做不到，DHCP 服务器是系统热点自带的，第三方应用改不了。
     */
    private void writePac(OutputStream out) {
        String ip = NetUtils.primaryIp();
        String pac = "function FindProxyForURL(url, host) {\n"
                + "  if (isPlainHostName(host) || shExpMatch(host, \"127.*\")"
                + " || shExpMatch(host, \"10.*\") || shExpMatch(host, \"192.168.*\")"
                + " || shExpMatch(host, \"172.1[6-9].*\") || shExpMatch(host, \"172.2[0-9].*\")"
                + " || shExpMatch(host, \"172.3[01].*\")) return \"DIRECT\";\n"
                + "  return \"PROXY " + ip + ":" + port + "; SOCKS5 " + ip + ":"
                + GatewayService.lastSocksPort + "\";\n"
                + "}\n";
        try {
            byte[] body = pac.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/x-ns-proxy-autoconfig\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("US-ASCII"));
            out.write(body);
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private String statusPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>网关运行中</title><style>body{background:#0b0f14;color:#e6edf3;font-family:-apple-system,sans-serif;");
        sb.append("padding:24px;line-height:1.7}h1{font-size:20px}code{background:#161b22;padding:2px 6px;border-radius:4px;");
        sb.append("color:#7ee787}p{color:#9da7b3;font-size:14px}</style></head><body>");
        sb.append("<h1>网关运行中</h1>");
        sb.append("<p>本机 IP：<code>").append(NetUtils.primaryIp()).append("</code></p>");
        sb.append("<p>SOCKS5：<code>").append(NetUtils.primaryIp()).append(":")
                .append(GatewayService.lastSocksPort).append("</code></p>");
        sb.append("<p>HTTP：<code>").append(NetUtils.primaryIp()).append(":")
                .append(port).append("</code></p>");
        sb.append("<p>FOU：<code>").append(NetUtils.primaryIp()).append(":")
                .append(GatewayService.lastFouPort).append("/udp</code></p>");
        sb.append("<p>PAC 自动配置：<code>http://").append(NetUtils.primaryIp())
                .append(':').append(port).append("/proxy.pac</code></p>");
        sb.append("<p>DNS 诊断：<code>http://").append(NetUtils.primaryIp())
                .append(':').append(port).append("/dns?host=www.baidu.com</code></p>");
        sb.append("<p style=\"color:#6e7681;font-size:12px\">Windows：Internet 选项 → 局域网设置 → 使用自动配置脚本，填上面的 PAC 地址；")
                .append("macOS：网络 → 高级 → 代理 → 自动代理配置。</p>");
        sb.append("<p style=\"color:#6e7681;font-size:12px\">Android：长按本 Wi-Fi → 修改网络 → 高级 → 代理「手动」→ "
                + "主机名填上面的 IP、端口填上面的 HTTP 端口 → 保存后<strong>断开重连一次</strong>；"
                + "填完先用浏览器打开本页确认能打开，再试别的网站。</p>");

        // 现场诊断：客户端的请求到底有没有进来
        sb.append("<h2 style=\"font-size:16px;margin-top:26px\">最近的请求</h2>");
        List<String> rec = Logs.recent(10);
        if (rec.isEmpty()) {
            sb.append("<p style=\"color:#d29922\">这里什么都没有 —— 如果你在客户端已经打开过网页，"
                    + "说明那些请求<strong>根本没有走到这个代理</strong>（代理设置没生效/端口填错/连的不是这个网络）。</p>");
        } else {
            sb.append("<pre style=\"background:#161b22;color:#9da7b3;font-size:12px;padding:10px;"
                    + "border-radius:6px;overflow:auto;white-space:pre-wrap\">");
            for (String l : rec) {
                sb.append(escapeHtml(l)).append('\n');
            }
            sb.append("</pre>");
            sb.append("<p style=\"color:#6e7681;font-size:12px\">看到 <code>HTTP CONNECT 域名:443</code> 或 "
                    + "<code>HTTP GET 域名</code> 就说明客户端在走代理，代理这边正在替它解析域名。</p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String[] splitHostPort(String hp, int defPort) {
        String host = hp;
        int p = defPort;
        if (hp.startsWith("[")) {
            int end = hp.indexOf(']');
            host = hp.substring(1, end);
            if (hp.length() > end + 1 && hp.charAt(end + 1) == ':') {
                p = Integer.parseInt(hp.substring(end + 2));
            }
        } else {
            int i = hp.lastIndexOf(':');
            if (i > 0) {
                host = hp.substring(0, i);
                try {
                    p = Integer.parseInt(hp.substring(i + 1));
                } catch (Exception ignore) {
                }
            }
        }
        return new String[]{host, String.valueOf(p)};
    }

    /**
     * 客户端来源地址。排查「代理到底有没有生效」时最关键的一行：
     * 客户端重连后如果这里再也不出现它的 IP，说明请求根本没走代理（系统代理设置没生效）。
     */
    private static String src(Socket s) {
        try {
            return s.getInetAddress().getHostAddress() + ":" + s.getPort();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 统一的失败页：把「为什么失败 + 怎么办」直接写给客户端，省得用户只看到一句 Bad Gateway。 */
    private String errorPage(String target, String why) {
        return "<!doctype html><meta charset=utf-8><title>网关：访问失败</title>"
                + "<div style=\"font:14px/1.7 -apple-system,Segoe UI,sans-serif;max-width:640px;margin:40px auto;padding:0 16px\">"
                + "<h2 style=\"color:#c0392b\">访问失败</h2>"
                + "<p><b>目标：</b><code>" + target + "</code></p>"
                + "<p><b>原因：</b>" + why + "</p>"
                + "<p style=\"color:#555\">若所有网站都打不开：客户端请用 <b>HTTP 代理</b> 或 <b>PAC</b>"
                + "（<code>http://本机IP:" + port + "/proxy.pac</code>），把域名交给网关解析；"
                + "只配 SOCKS5 时，客户端会在本地查 DNS —— 而热点/P2P 组没有上游 DNS，必然解析失败。</p>"
                + "</div>";
    }

    private static void writeSimple(OutputStream out, int code, String reason, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("US-ASCII"));
            out.write(b);
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < max) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
