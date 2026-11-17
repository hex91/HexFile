import com.hex91.file.net.LanScan;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v2.14.0（F-20）：局域网服务发现的用例。
 *
 * <p>要锁住的是两类东西：</p>
 * <ul>
 *   <li><b>判断协议靠应答而不是靠端口</b>。5005 上跑的可能是 Synology WebDAV，
 *       也可能是某个普通网页；只有 {@code DAV:} 头能分辨。反过来 FTP 挪到 2121
 *       也还是 FTP，认端口就会漏。</li>
 *   <li><b>扫描本身不误报</b>。关着的端口必须是「没命中」，而不是「连上了但协议为空」——
 *       后者会把一堆空气设备塞进选择列表，用户点进去全连不上。</li>
 * </ul>
 *
 * <p>真实 socket 这一段在桌面就能跑：本机开一个 ServerSocket 假装 FTP / WebDAV，
 * 让 {@link LanScan#scan} 真去连一次。真机验证的是同一段代码在 Wi-Fi 下能不能
 * 拿到地址，不需要两台手机。</p>
 */
public class LanScanTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        classifyCases();
        detailCases();
        hostCases();
        realSockets();

        System.out.println();
        System.out.println("LanScanTest  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 纯函数

    static void classifyCases() {
        // 端口只做兜底，应答优先
        ok("ftp".equals(LanScan.classify(21, "220 Synology FTP server ready\r\n")),
                "classify: 21 的 220 banner → ftp");
        ok("ftp".equals(LanScan.classify(2121, "220 hello\r\n")),
                "classify: 非默认端口也按 banner 认 FTP");
        ok("sftp".equals(LanScan.classify(22, "SSH-2.0-OpenSSH_8.4\r\n")),
                "classify: SSH 版本行 → sftp");
        ok("sftp".equals(LanScan.classify(2222, "SSH-2.0-dropbear\r\n")),
                "classify: 非默认端口的 SSH 也认");
        ok("webdav".equals(LanScan.classify(5005,
                        "HTTP/1.0 207 Multi-Status\r\nDAV: 1,2\r\nServer: nginx\r\n\r\n")),
                "classify: 有 DAV 头 → webdav");
        ok("webdav".equals(LanScan.classify(80,
                        "HTTP/1.1 401 Unauthorized\r\nDAV: 1,2,3\r\n\r\n")),
                "classify: 80 上的 DAV 头同样认 webdav（401 也算）");
        ok("http".equals(LanScan.classify(80,
                        "HTTP/1.1 200 OK\r\nServer: lighttpd\r\n\r\n")),
                "classify: 无 DAV 头的 HTTP → http");
        ok("smb".equals(LanScan.classify(445, "")),
                "classify: 445 无 banner（SMB 等客户端先开口）→ smb");
        ok("webdav".equals(LanScan.classify(5005, "")),
                "classify: 5005 无应答按经验值 → webdav");
        ok("".equals(LanScan.classify(8080, "")),
                "classify: 无应答且端口没有经验值 → 空（不当成命中）");
        ok("".equals(LanScan.classify(3306, "J5.7.31-0ubuntu0.20.04.1")),
                "classify: 认不出的应答 → 空");
    }

    static void detailCases() {
        String d = LanScan.detailOf("HTTP/1.0 207 Multi-Status\r\nDAV: 1,2\r\n"
                + "Server: FakeNAS/1.0\r\n\r\n");
        ok(d.contains("207 Multi-Status") && d.contains("FakeNAS") && d.contains("DAV 1,2"),
                "detailOf: 首行 + Server + DAV 都进摘要");
        ok(d.indexOf('\r') < 0 && d.indexOf('\n') < 0, "detailOf: 摘要里不留换行（列表单行显示）");
        ok("".equals(LanScan.detailOf("")) && "".equals(LanScan.detailOf(null)),
                "detailOf: 空应答给空串");
        StringBuilder longLine = new StringBuilder("HTTP/1.0 200 ");
        for (int i = 0; i < 20; i++) longLine.append("xxxxxxxxxx");
        String cut = LanScan.detailOf(longLine + "\r\n");
        ok(cut.length() <= 81 && cut.endsWith("…"), "detailOf: 超长摘要截到 80 字并加省略号");

        ok(LanScan.hasDavHeader("HTTP/1.0 200\r\nDAV: 1\r\n"), "hasDavHeader: 命中行首 DAV:");
        ok(!LanScan.hasDavHeader("HTTP/1.0 200\r\nX-DAV: 1\r\n"),
                "hasDavHeader: X-DAV 之类的其它头不算");
        ok(!LanScan.hasDavHeader(null), "hasDavHeader: null 安全");
    }

    static void hostCases() {
        List<String> a = LanScan.hostsIn24("192.168.1.");
        ok(a.size() == 254 && a.get(0).equals("192.168.1.1") && a.get(253).equals("192.168.1.254"),
                "hostsIn24: 192.168.1. → 1..254 共 254 个");
        ok(a.indexOf("192.168.1.0") < 0 && a.indexOf("192.168.1.255") < 0,
                "hostsIn24: 不含 .0 与 .255");
        List<String> b = LanScan.hostsIn24("10.0.0");
        ok(b.size() == 254 && b.get(0).equals("10.0.0.1"), "hostsIn24: 缺尾点也认");
        ok(LanScan.hostsIn24("1.2").isEmpty(), "hostsIn24: 段数不足 → 空");
        ok(LanScan.hostsIn24("1.2.3.4.").isEmpty(), "hostsIn24: 四段（/32）→ 空，不硬猜网段");
        ok(LanScan.hostsIn24("256.1.1.").isEmpty(), "hostsIn24: 越界 256 → 空");
        ok(LanScan.hostsIn24("192.168.1a.").isEmpty(), "hostsIn24: 非数字段 → 空");
        ok(LanScan.hostsIn24(null).isEmpty(), "hostsIn24: null → 空");

        ok(LanScan.ipKey("192.168.1.9") < LanScan.ipKey("192.168.1.10")
                        && LanScan.ipKey("192.168.1.10") < LanScan.ipKey("192.168.1.100")
                        && LanScan.ipKey("192.168.1.100") < LanScan.ipKey("192.168.2.1"),
                "ipKey: 按数值比较，9 < 10 < 100 < 2.1");
        ok(LanScan.ipKey("abc") == Long.MAX_VALUE && LanScan.ipKey(null) == Long.MAX_VALUE
                        && LanScan.ipKey("1.2.3.999") == Long.MAX_VALUE,
                "ipKey: 畸形地址排到最后");

        List<String> pf = LanScan.localPrefixes();
        boolean fmt = true;
        for (String p : pf) {
            if (!p.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.")) fmt = false;
            if (p.startsWith("127.")) fmt = false;
        }
        ok(fmt, "localPrefixes: 只给 a.b.c. 形式且不含回环（本机得到 " + pf.size() + " 段）");
    }

    // ------------------------------------------------------------ 真 socket

    static void realSockets() throws Exception {
        ServerSocket ftp = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int ftpPort = ftp.getLocalPort();
        Thread t1 = new Thread(new Runnable() {
            public void run() {
                bannerOn(ftp, "220 (fake ftp) ready\r\n");
            }
        });
        t1.setDaemon(true);
        t1.start();

        // 只探这一个端口：断言「命中 1 台、类型 ftp、探测计数 1」
        final List<LanScan.Hit> seen = new ArrayList<LanScan.Hit>();
        final int[] doneCalls = {0};
        LanScan.Result r1 = LanScan.scan(Arrays.asList("127.0.0.1"), new int[]{ftpPort}, 500,
                new LanScan.Cancel() { public boolean cancelled() { return false; } },
                new LanScan.Sink() {
                    public void onHit(LanScan.Hit h) { seen.add(h); }
                    public void onDone(LanScan.Result r) { doneCalls[0]++; }
                });
        ok(r1.hits.size() == 1, "scan: 本机假 FTP 命中 1 台");
        ok(r1.hits.size() == 1 && "ftp".equals(r1.hits.get(0).type), "scan: 识别为 ftp");
        ok(r1.hits.size() == 1 && r1.hits.get(0).detail.contains("fake ftp"),
                "scan: 摘要带上对端 banner");
        ok(r1.probed == 1 && !r1.cancelled, "scan: probed=1、未取消");
        ok(seen.size() == 1 && doneCalls[0] == 1, "scan: onHit 与 onDone 各回调到位");
        ftp.close();

        // WebDAV：客户端要先发 OPTIONS，服务端读到请求再回 207 + DAV 头
        ServerSocket dav = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int davPort = dav.getLocalPort();
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                davReply(dav, "HTTP/1.0 207 Multi-Status\r\nDAV: 1,2\r\nServer: FakeNAS/1.0\r\n\r\n");
            }
        });
        t2.setDaemon(true);
        t2.start();
        LanScan.Result r2 = LanScan.scan(Arrays.asList("127.0.0.1"), new int[]{davPort}, 500,
                null, null);
        ok(r2.hits.size() == 1 && "webdav".equals(r2.hits.get(0).type),
                "scan: 先发 OPTIONS 认出 WebDAV");
        ok(r2.hits.size() == 1 && r2.hits.get(0).detail.contains("FakeNAS"),
                "scan: WebDAV 摘要带 Server 头");
        dav.close();

        // 关着的端口：必须一台都不报
        int dead = freePort();
        LanScan.Result r3 = LanScan.scan(Arrays.asList("127.0.0.1"), new int[]{dead}, 300,
                null, null);
        ok(r3.hits.isEmpty() && r3.probed == 1,
                "scan: 关着的端口不产生命中（probed 仍然计数）");

        // 端口去重：同一个端口传两遍只探一次
        int dead2 = freePort();
        LanScan.Result r4 = LanScan.scan(Arrays.asList("127.0.0.1"), new int[]{dead2, dead2}, 300,
                null, null);
        ok(r4.probed == 1, "scan: 重复端口只探一次");

        // 空输入：立即返回，不算取消
        LanScan.Result r5 = LanScan.scan(new ArrayList<String>(), LanScan.PORTS, 300, null, null);
        ok(r5.hits.isEmpty() && r5.probed == 0 && !r5.cancelled, "scan: 空地址表直接返回");

        // 提前取消：一个都不探
        final AtomicBoolean stop = new AtomicBoolean(true);
        LanScan.Result r6 = LanScan.scan(LanScan.hostsIn24("127.0.0."), LanScan.PORTS, 200,
                new LanScan.Cancel() { public boolean cancelled() { return stop.get(); } }, null);
        ok(r6.hits.isEmpty() && r6.cancelled, "scan: 预置取消 → cancelled=true 且无命中");

        // 排序：同一台机器的两个命中按端口升序
        ServerSocket a1 = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        ServerSocket a2 = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final int pLow = Math.min(a1.getLocalPort(), a2.getLocalPort());
        final int pHigh = Math.max(a1.getLocalPort(), a2.getLocalPort());
        daemonBanner(a1, "220 low\r\n");
        daemonBanner(a2, "220 high\r\n");
        LanScan.Result r7 = LanScan.scan(Arrays.asList("127.0.0.1"), new int[]{pHigh, pLow}, 500,
                null, null);
        ok(r7.hits.size() == 2 && r7.hits.get(0).port == pLow && r7.hits.get(1).port == pHigh,
                "scan: 结果按端口升序排列");
        a1.close();
        a2.close();
    }

    static void daemonBanner(final ServerSocket ss, final String text) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                bannerOn(ss, text);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 连一次、发 banner 就关（模拟 FTP/SSH 的「连上就说话」）；server 由调用方持有 */
    static void bannerOn(ServerSocket ss, String text) {
        try {
            Socket s = ss.accept();
            OutputStream o = s.getOutputStream();
            o.write(text.getBytes("UTF-8"));
            o.flush();
            s.close();
        } catch (Exception e) {
            System.out.println("  (banner server 结束: " + e + ")");
        }
    }

    /** 收请求 → 回一段 HTTP 响应（模拟 WebDAV/网页服务） */
    static void davReply(ServerSocket ss, String resp) {
        try {
            Socket s = ss.accept();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[1024];
            in.read(buf);                       // 只读一次，能拿到 OPTIONS 头就够
            OutputStream o = s.getOutputStream();
            o.write(resp.getBytes("UTF-8"));
            o.flush();
            s.close();
        } catch (Exception e) {
            System.out.println("  (dav server 结束: " + e + ")");
        }
    }

    /** 占一个端口再立刻释放，得到一个「确定没人监听」的端口号 */
    static int freePort() throws Exception {
        ServerSocket s = new ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        return p;
    }
}
