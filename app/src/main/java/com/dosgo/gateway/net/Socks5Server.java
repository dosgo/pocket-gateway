package com.dosgo.gateway.net;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * SOCKS5 代理服务器（RFC 1928 / 1929 无认证）。
 * 支持 CONNECT 与 UDP ASSOCIATE。
 */
public class Socks5Server {

    private static final int VER = 0x05;
    private static final int CMD_CONNECT = 0x01;
    private static final int CMD_UDP = 0x03;

    private final int port;
    private volatile ServerSocket server;
    private volatile boolean running;

    public Socks5Server(int port) {
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
        }, "socks5-accept");
        t.setDaemon(true);
        t.start();
        Logs.add("SOCKS5 已启动  0.0.0.0:" + port);
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
                }, "socks5-conn");
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
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();

        // ---- 握手 ----
        int ver = in.read();
        if (ver != VER) {
            Relay.closeQuietly(s);
            return;
        }
        int nMethods = in.read();
        if (nMethods <= 0 || nMethods > 8) {
            Relay.closeQuietly(s);
            return;
        }
        readFully(in, new byte[nMethods]);
        out.write(new byte[]{VER, 0x00});
        out.flush();

        // ---- 请求 ----
        int rVer = in.read();
        int cmd = in.read();
        in.read(); // RSV
        int atyp = in.read();
        if (rVer != VER) {
            Relay.closeQuietly(s);
            return;
        }

        String host = null;
        InetAddress literal = null;
        if (atyp == 0x01) {
            byte[] a = new byte[4];
            readFully(in, a);
            literal = InetAddress.getByAddress(a);
            host = literal.getHostAddress();
        } else if (atyp == 0x03) {
            int len = in.read();
            if (len <= 0) {
                reply(out, 0x08, null, 0);
                Relay.closeQuietly(s);
                return;
            }
            byte[] d = new byte[len];
            readFully(in, d);
            host = new String(d, "US-ASCII");
        } else if (atyp == 0x04) {
            byte[] a = new byte[16];
            readFully(in, a);
            literal = InetAddress.getByAddress(a);
            host = literal.getHostAddress();
        } else {
            reply(out, 0x08, null, 0);
            Relay.closeQuietly(s);
            return;
        }
        int dport = (in.read() & 0xff) << 8 | (in.read() & 0xff);

        if (cmd == CMD_CONNECT) {
            doConnect(s, in, out, host, dport);
        } else if (cmd == CMD_UDP) {
            doUdp(s, out);
        } else {
            reply(out, 0x07, null, 0);
            Relay.closeQuietly(s);
        }
    }

    private void doConnect(Socket s, InputStream in, OutputStream out, String host, int dport) {
        Socket remote = null;
        try {
            remote = Relay.connectAny(host, dport, 6000);
            reply(out, 0x00, remote.getLocalAddress(), remote.getLocalPort());
            s.setSoTimeout(0);
            Logs.add("SOCKS5 连接 " + host + ":" + dport + "  ← " + src(s));
            Relay.bridge(s, in, out, remote, remote.getInputStream(), remote.getOutputStream());
        } catch (UnknownHostException uhe) {
            // 0x04 = Host unreachable：让客户端知道是「域名查不到」，不是「被拒绝」
            try {
                reply(out, 0x04, null, 0);
            } catch (Exception ignore) {
            }
            Relay.closeQuietly(remote);
            Relay.closeQuietly(s);
            Logs.add("SOCKS5 解析失败 " + host + " " + uhe.getMessage());
        } catch (Exception e) {
            try {
                reply(out, 0x05, null, 0);
            } catch (Exception ignore) {
            }
            Relay.closeQuietly(remote);
            Relay.closeQuietly(s);
            Logs.add("SOCKS5 失败 " + host + ":" + dport + " " + e.getMessage());
        }
    }

    /** UDP ASSOCIATE：单端口双向中继，客户端用 SOCKS5 UDP 头封装。 */
    private void doUdp(Socket s, OutputStream out) throws IOException {
        DatagramSocket ds = new DatagramSocket(new InetSocketAddress(0));
        InetAddress replyAddr = s.getLocalAddress();
        reply(out, 0x00, replyAddr, ds.getLocalPort());
        Logs.add("SOCKS5 UDP 中继 " + replyAddr.getHostAddress() + ":" + ds.getLocalPort());

        DatagramSocket ctrl = ds;
        byte[] buf = new byte[65535];
        InetSocketAddress client = null;
        ctrl.setSoTimeout(300000);
        try {
            while (running && !s.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ctrl.receive(p);
                int len = p.getLength();
                byte[] data = p.getData();
                int off = p.getOffset();

                boolean isFromClient;
                if (client == null) {
                    if (len > 10 && data[off] == 0 && data[off + 1] == 0) {
                        client = new InetSocketAddress(p.getAddress(), p.getPort());
                        isFromClient = true;
                    } else {
                        continue;
                    }
                } else {
                    isFromClient = p.getPort() == client.getPort() && p.getAddress().equals(client.getAddress());
                }

                if (isFromClient) {
                    // RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA
                    int atyp = data[off + 3] & 0xff;
                    int idx = off + 4;
                    InetAddress dstAddr;
                    if (atyp == 0x01) {
                        byte[] a = new byte[4];
                        System.arraycopy(data, idx, a, 0, 4);
                        dstAddr = InetAddress.getByAddress(a);
                        idx += 4;
                    } else if (atyp == 0x03) {
                        int dl = data[idx] & 0xff;
                        idx += 1;
                        String h = new String(data, idx, dl, "US-ASCII");
                        idx += dl;
                        dstAddr = InetAddress.getByName(h);
                    } else if (atyp == 0x04) {
                        byte[] a = new byte[16];
                        System.arraycopy(data, idx, a, 0, 16);
                        dstAddr = InetAddress.getByAddress(a);
                        idx += 16;
                    } else {
                        continue;
                    }
                    int dstPort = (data[idx] & 0xff) << 8 | (data[idx + 1] & 0xff);
                    idx += 2;
                    int dataLen = off + len - idx;
                    if (dataLen > 0) {
                        ctrl.send(new DatagramPacket(data, idx, dataLen, dstAddr, dstPort));
                    }
                } else {
                    // 远端回包 → 加 SOCKS5 UDP 头转给客户端
                    byte[] src = p.getAddress().getAddress();
                    byte[] head;
                    if (src.length == 4) {
                        head = new byte[10];
                        head[3] = 0x01;
                        System.arraycopy(src, 0, head, 4, 4);
                    } else {
                        head = new byte[22];
                        head[3] = 0x04;
                        System.arraycopy(src, 0, head, 4, 16);
                    }
                    head[8] = (byte) ((p.getPort() >> 8) & 0xff);
                    head[9] = (byte) (p.getPort() & 0xff);
                    byte[] pkt = new byte[head.length + len];
                    System.arraycopy(head, 0, pkt, 0, head.length);
                    System.arraycopy(data, off, pkt, head.length, len);
                    ctrl.send(new DatagramPacket(pkt, pkt.length, client));
                }
            }
        } catch (SocketTimeoutException ignore) {
        } catch (Exception ignore) {
        } finally {
            Relay.closeQuietly(ctrl);
            Relay.closeQuietly(s);
        }
    }

    /** 客户端来源地址，用于判断请求是否真的走了代理。 */
    private static String src(Socket s) {
        try {
            return s.getInetAddress().getHostAddress() + ":" + s.getPort();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void reply(OutputStream out, int rep, InetAddress addr, int port) throws IOException {
        byte[] b;
        if (addr != null && addr.getAddress() != null && addr.getAddress().length == 16) {
            b = new byte[22];
            b[3] = 0x04;
            System.arraycopy(addr.getAddress(), 0, b, 4, 16);
        } else {
            b = new byte[10];
            b[3] = 0x01;
            if (addr != null && addr.getAddress() != null) {
                System.arraycopy(addr.getAddress(), 0, b, 4, 4);
            }
        }
        b[0] = (byte) VER;
        b[1] = (byte) rep;
        b[8] = (byte) ((port >> 8) & 0xff);
        b[9] = (byte) (port & 0xff);
        out.write(b);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }
}
