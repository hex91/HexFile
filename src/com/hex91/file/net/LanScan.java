package com.hex91.file.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v2.14.0（F-20）：局域网服务发现。
 *
 * <p>「添加服务器」原先要求用户自己知道 NAS 的 IP、端口和协议，而家用环境里
 * 这三样通常是「路由器后台查一下」才能拿到。这里退回到最土也最可靠的办法：
 * 拿到本机所在 /24 网段，对每个地址的常见端口做一次 TCP connect，能连上的
 * 再问一句「你是什么协议」。</p>
 *
 * <p>为什么不用 mDNS/SSDP：那两种只覆盖「愿意广播自己」的设备，而 SMB 共享
 * 恰恰是 Windows 与多数 NAS 默认<b>不</b>通过 mDNS 暴露的（Windows 要用 WS-Discovery，
 * 报文是 SOAP，解析成本远高于收益）。TCP 端口探测对「端口开着就是服务」这件事
 * 没有假设，也不会因为路由器屏蔽组播而整片失效。</p>
 *
 * <p>纯 JDK、无 Android 依赖：{@link #classify} 与 {@link #detailOf} 是纯函数，
 * 探测部分可以在桌面 JVM 里对着一个本地 ServerSocket 真跑一遍。
 * 这也是本类唯一的「正确性证据」—— 真机只是把同一段代码放进 Wi-Fi 环境。</p>
 */
public final class LanScan {

    /** 探测端口：SMB、Synology 默认 WebDAV、FTP、SFTP、HTTP（群晖/威联通网页服务也在 80/5000） */
    public static final int[] PORTS = {445, 5005, 21, 22, 80};

    /** 连接超时：局域网内 ARP 都通的话 RTT &lt; 5ms，350ms 足够；不可达地址靠它快速跳过 */
    public static final int CONNECT_MS = 350;

    /** 读应答超时：FTP/SSH 是连上就发 banner，HTTP 需要我们主动发一句 */
    public static final int READ_MS = 400;

    /** 并发上限：1270 个 (地址,端口) 组合在 48 并发下约 8 秒扫完，再高对家用路由器不礼貌 */
    private static final int MAX_THREADS = 48;

    /** 单次读取上限：只为拿首行与几个响应头，读太多会被慢服务拖住 */
    private static final int MAX_READ = 512;

    public static final String T_SMB = "smb";
    public static final String T_WEBDAV = "webdav";
    public static final String T_HTTP = "http";
    public static final String T_FTP = "ftp";
    public static final String T_SFTP = "sftp";

    private LanScan() {
    }

    /** 一台「看起来能连」的主机 + 端口 + 协议判断 */
    public static final class Hit {
        public final String host;
        public final int port;
        /** {@link #T_SMB} 等；未知协议返回空串（调用方应跳过） */
        public final String type;
        /** 给人看的应答摘要（banner / Server 头 / DAV 值），可能为空 */
        public final String detail;

        Hit(String host, int port, String type, String detail) {
            this.host = host;
            this.port = port;
            this.type = type;
            this.detail = detail == null ? "" : detail;
        }

        @Override
        public String toString() {
            return host + ":" + port + (type.isEmpty() ? "" : " (" + type + ")");
        }
    }

    /** 扫描结束时回调一次；{@code hits} 已按地址、端口排好序 */
    public static final class Result {
        public final List<Hit> hits;
        public final int probed;
        public final boolean cancelled;

        Result(List<Hit> hits, int probed, boolean cancelled) {
            this.hits = hits;
            this.probed = probed;
            this.cancelled = cancelled;
        }
    }

    /** 边扫边报，便于进度条显示「已发现 N 台」；回调来自工作线程，UI 侧自行转主线程 */
    public interface Sink {
        void onHit(Hit h);

        void onDone(Result r);
    }

    /** 取消标志：返回 true 后不再发起新的探测，已在连接中的最多再等一个超时 */
    public interface Cancel {
        boolean cancelled();
    }

    // ---------------------------------------------------------------- 地址枚举

    /**
     * 本机所在网段的 /24 前缀（形如 {@code "192.168.1."}）。
     *
     * <p>刻意只取 /24：家里和多数办公室就是 /24，而 /16 段有 65534 个地址，
     * 扫完要几分钟且几乎全是空的。IPv6 不参与（NAS 的 IPv6 地址与端口映射关系
     * 太不直观，填进表单反而连不上）。</p>
     */
    public static List<String> localPrefixes() {
        Set<String> out = new LinkedHashSet<String>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    out.add((b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".");
                }
            }
        } catch (Exception e) {
            // 枚举网卡在部分 ROM 上会抛（权限或 SELinux），此时宁可返回空表让调用方
            // 提示「没扫到」，也不要让「添加服务器」整页崩掉
            return new ArrayList<String>(out);
        }
        return new ArrayList<String>(out);
    }

    /** {@code "192.168.1."} → 1..254 的主机地址；不合法输入返回空表 */
    public static List<String> hostsIn24(String prefix) {
        List<String> out = new ArrayList<String>(254);
        if (prefix == null) return out;
        String p = prefix.trim();
        if (!p.endsWith(".")) p = p + ".";
        // 只接受 a.b.c. 的形式，避免把 "abc" 拼成 "abc.1"
        String[] seg = p.substring(0, p.length() - 1).split("\\.", -1);
        if (seg.length != 3) return out;
        for (String s : seg) {
            if (s.isEmpty() || s.length() > 3) return out;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) < '0' || s.charAt(i) > '9') return out;
            }
            if (Integer.parseInt(s) > 255) return out;
        }
        for (int i = 1; i <= 254; i++) out.add(p + i);
        return out;
    }

    /** 汇总本机所有网段的待扫地址（去重，保持网卡顺序） */
    public static List<String> hostsToScan() {
        Set<String> out = new LinkedHashSet<String>();
        for (String p : localPrefixes()) out.addAll(hostsIn24(p));
        return new ArrayList<String>(out);
    }

    // ---------------------------------------------------------------- 协议判断

    /**
     * 根据端口与应答首部判断协议（纯函数）。
     *
     * <p>先看应答再看端口：同样是 5005，装的是 Synology WebDAV 还是普通网页，
     * 只有 {@code DAV:} 头能分辨。应答什么都没有时才退回端口经验值。</p>
     */
    public static String classify(int port, String resp) {
        String r = resp == null ? "" : resp;
        if (r.startsWith("220")) return T_FTP;                       // RFC 959 就绪应答
        if (r.contains("SSH-")) return T_SFTP;                       // RFC 4253 版本行
        if (hasDavHeader(r)) return T_WEBDAV;                        // RFC 4918
        if (r.startsWith("HTTP/") || r.contains("HTTP/1.")) return T_HTTP;
        if (port == 445) return T_SMB;                               // SMB 等客户端先开口，无 banner
        if (port == 21) return T_FTP;
        if (port == 22) return T_SFTP;
        if (port == 5005) return T_WEBDAV;
        return "";
    }

    /** 响应头里是否有 {@code DAV:} 这一行（逐行匹配，避免把正文里的 "DAV:" 当响应头） */
    public static boolean hasDavHeader(String resp) {
        if (resp == null || resp.isEmpty()) return false;
        for (String line : resp.split("\r?\n")) {
            if (line.regionMatches(true, 0, "DAV:", 0, 4)) return true;
        }
        return false;
    }

    /** 应答摘要：首行 + Server 头 + DAV 值，截到 80 字（列表里一行放得下） */
    public static String detailOf(String resp) {
        if (resp == null || resp.isEmpty()) return "";
        String first = "";
        String server = "";
        String dav = "";
        for (String raw : resp.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (first.isEmpty()) first = line;
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("server:")) server = line.substring(7).trim();
            else if (low.startsWith("dav:")) dav = line.substring(4).trim();
        }
        StringBuilder b = new StringBuilder();
        if (!first.isEmpty()) b.append(first);
        if (!server.isEmpty()) append(b, server);
        if (!dav.isEmpty()) append(b, "DAV " + dav);
        String s = b.toString();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static void append(StringBuilder b, String part) {
        if (b.length() > 0) b.append(" · ");
        b.append(part);
    }

    // ---------------------------------------------------------------- 探测

    /** 探测单个地址+端口；连不上或协议未知返回 null */
    static Hit probe(String host, int port, int connectMs, int readMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectMs);
            s.setSoTimeout(readMs);
            String resp = readQuiet(s);
            // SMB 由客户端先发 negotiate，给它发 HTTP 请求只会让对端立刻断开；
            // 其余端口没等到 banner 时才补一句 OPTIONS（HTTP/WebDAV 都靠它应答）
            if (resp.isEmpty() && port != 445) {
                sendOptions(s, host, port);
                resp = readQuiet(s);
            }
            String type = classify(port, resp);
            if (type.isEmpty()) return null;
            return new Hit(host, port, type, detailOf(resp));
        } catch (IOException e) {
            // 端口关着/主机不在线是最常见的结果，不是错误；扫描本身不记录失败明细，
            // 否则 1200 个失败要把日志刷满
            return null;
        } finally {
            closeQuiet(s);
        }
    }

    private static void sendOptions(Socket s, String host, int port) throws IOException {
        String req = "OPTIONS / HTTP/1.0\r\nHost: " + host + (port == 80 ? "" : ":" + port)
                + "\r\nUser-Agent: HexFile-LanScan\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(req.getBytes("UTF-8"));
        o.flush();
    }

    /** 读一段应答；超时视为「对端不主动说话」，返回已读到的部分 */
    private static String readQuiet(Socket s) {
        InputStream in;
        try {
            in = s.getInputStream();
        } catch (IOException e) {
            return "";
        }
        byte[] buf = new byte[MAX_READ];
        int n = 0;
        try {
            n = in.read(buf);
        } catch (SocketTimeoutException e) {
            return "";
        } catch (IOException e) {
            return "";
        }
        if (n <= 0) return "";
        return new String(buf, 0, n, java.nio.charset.Charset.forName("UTF-8"));
    }

    /** 关闭失败不影响扫描结果，但也不用空 catch（O-20 的基线不该被这种收尾撑大） */
    private static boolean closeQuiet(Socket s) {
        if (s == null) return true;
        try {
            s.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 主流程

    /**
     * 扫描一组地址的若干端口。
     *
     * <p>阻塞到全部完成（或取消）才返回；期间每命中一台就回调 {@link Sink#onHit}，
     * 便于 UI 边扫边显示。</p>
     */
    public static Result scan(List<String> hosts, int[] ports, int connectMs,
                              Cancel cancel, Sink sink) {
        final List<Hit> hits = Collections.synchronizedList(new ArrayList<Hit>());
        if (hosts == null || hosts.isEmpty() || ports == null || ports.length == 0) {
            Result empty = new Result(new ArrayList<Hit>(), 0, false);
            if (sink != null) sink.onDone(empty);
            return empty;
        }
        // 端口去重：调用方传 {445,445} 不该探两次
        final List<Integer> ps = new ArrayList<Integer>();
        for (int p : ports) {
            if (p > 0 && p <= 65535 && !ps.contains(p)) ps.add(p);
        }
        if (ps.isEmpty()) {
            Result empty = new Result(new ArrayList<Hit>(), 0, false);
            if (sink != null) sink.onDone(empty);
            return empty;
        }

        final int timeout = connectMs <= 0 ? CONNECT_MS : connectMs;
        final AtomicInteger probed = new AtomicInteger();
        // 回调抛错（页面已销毁、View 已 detach）说明这个 Sink 已经没用了：
        // 第一次抛完就不再调，省得每命中一台都抛一次
        final AtomicBoolean sinkDead = new AtomicBoolean();
        int total = hosts.size() * ps.size();
        final ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(MAX_THREADS, total)), new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger();

                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "lanscan-" + seq.incrementAndGet());
                        t.setDaemon(true);      // 扫描线程不该拖住 JVM/测试进程退出
                        return t;
                    }
                });

        try {
            for (final String host : hosts) {
                for (final int port : ps) {
                    pool.execute(new Runnable() {
                        public void run() {
                            if (cancel != null && cancel.cancelled()) return;
                            probed.incrementAndGet();
                            Hit h = probe(host, port, timeout, READ_MS);
                            if (h == null) return;
                            hits.add(h);
                            if (sink != null && !sinkDead.get()) {
                                try {
                                    sink.onHit(h);
                                } catch (RuntimeException e) {
                                    sinkDead.set(true);   // 回调失效，扫描继续，结果照样在 Result 里
                                }
                            }
                        }
                    });
                }
            }
        } finally {
            pool.shutdown();
        }

        // 「一开始就取消了」与「扫到一半取消」都要如实报 cancelled=true：
        // 前者不预置的话，任务全是空转，池子正常结束，调用方会以为扫完了
        boolean stopped = cancel != null && cancel.cancelled();
        if (stopped) pool.shutdownNow();
        while (!pool.isTerminated()) {
            try {
                if (pool.awaitTermination(100, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException e) {
                stopped = true;
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                break;
            }
            if (!stopped && cancel != null && cancel.cancelled()) {
                stopped = true;
                pool.shutdownNow();     // 未开始的任务直接丢弃，正在 connect 的最多再等一个超时
            }
        }

        List<Hit> sorted = new ArrayList<Hit>(hits);
        Collections.sort(sorted, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                long x = ipKey(a.host);
                long y = ipKey(b.host);
                if (x != y) return x < y ? -1 : 1;
                return a.port < b.port ? -1 : a.port == b.port ? 0 : 1;
            }
        });
        Result r = new Result(sorted, probed.get(), stopped);
        if (sink != null && !sinkDead.get()) {
            try {
                sink.onDone(r);
            } catch (RuntimeException e) {
                sinkDead.set(true);           // 同上：结果已经在返回值里，回调失败不影响调用方
            }
        }
        return r;
    }

    /** 点分十进制 → 可比较的长整数；解析失败排到最后（不应发生，防御用） */
    public static long ipKey(String ip) {
        if (ip == null) return Long.MAX_VALUE;
        String[] seg = ip.split("\\.");
        if (seg.length != 4) return Long.MAX_VALUE;
        long v = 0;
        for (String s : seg) {
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
            if (n < 0 || n > 255) return Long.MAX_VALUE;
            v = (v << 8) | n;
        }
        return v;
    }
}
