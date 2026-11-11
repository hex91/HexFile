import com.hex91.file.HlsDownloader;
import com.hex91.file.HlsPlaylist;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.95.4：用本地 HTTP 服务复现下载器的两个 P0。
 *
 * <p><b>P0-1</b>（续传时闸门提前归零）：留下两片「已下载」的 .seg，让第三片慢 300ms。
 * 旧代码 {@code new CountDownLatch(total - resumed)} 加上「已存在也 countDown」，
 * 会在第三片还没写完时归零 → shutdownNow + 立即合并 → FileNotFoundException。</p>
 *
 * <p><b>P0-2</b>（分片失败后无限等）：让第二片返回 404。失败的 worker 把 fatal 写好，
 * 其余 worker 取到索引后直接 return（不扣闸门），旧代码的主循环只看取消 → 永远转。
 * 这里 20 秒不返回即判定失败并退出。</p>
 */
public class HlsDownloaderTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eql(long expect, long got, String msg) {
        if (expect == got) { pass++; System.out.println("PASS  " + msg + " = " + got); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
    }

    static String eqs(String expect, String got, String msg) {
        if (expect == null ? got == null : expect.equals(got)) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg + " 期望 " + expect + " 实际 " + got); }
        return got;
    }

    // ---------------------------------------------------------------- 测试服务

    static final int SEG_SIZE = 188 * 3;
    static ServerSocket server;
    static int port;
    static volatile boolean running = true;
    static volatile boolean failSeg1 = false;
    /** 需要「慢」的分片序号（-1 = 都不慢），用来制造「未下完就合并」的窗口 */
    static volatile int slowIndex = -1;
    static volatile int slowMs = 0;

    public static void main(String[] args) throws Exception {
        startServer();
        try {
            resumeCase();
            failFastCase();
        } finally {
            running = false;
            try { server.close(); } catch (IOException ignore) {}
        }
        System.out.println("-----------------------------------------");
        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void startServer() throws IOException {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        Thread w = new Thread(new Runnable() {
                            public void run() { handle(s); }
                        });
                        w.setDaemon(true);
                        w.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static void handle(Socket s) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String first = br.readLine();
            if (first == null) return;
            while (true) {
                String h = br.readLine();
                if (h == null || h.isEmpty()) break;
            }
            String path = first.split(" ")[1];
            int code = 200;
            byte[] body;
            if (path.endsWith(".m3u8")) {
                body = playlist().getBytes("UTF-8");
            } else if (path.endsWith("seg1.ts") && failSeg1) {
                code = 404;
                body = "not found".getBytes("UTF-8");
            } else {
                int idx = idxOf(path);
                if (idx == slowIndex && slowMs > 0) {
                    try { Thread.sleep(slowMs); } catch (InterruptedException ignore) {}
                }
                body = segBytes(idx);
            }
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                    + "Content-Type: " + (path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t") + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (Exception ignore) {
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    static int idxOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.startsWith("seg") && name.endsWith(".ts")) {
            try { return Integer.parseInt(name.substring(3, name.length() - 3)); } catch (Exception ignore) {}
        }
        return -1;
    }

    /** 合法 TS 帧：每 188 字节首字节 0x47，便于 HlsCrypto / 合并逻辑按 TS 处理 */
    static byte[] segBytes(int idx) {
        byte[] b = new byte[SEG_SIZE];
        for (int i = 0; i < b.length; i++) b[i] = (byte) ('k' + (idx < 0 ? 0 : idx));
        b[0] = 0x47;
        b[188] = 0x47;
        b[376] = 0x47;
        return b;
    }

    static String playlist() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < 3; i++) {
            sb.append("#EXTINF:4,\nhttp://127.0.0.1:").append(port).append("/seg").append(i).append(".ts\n");
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    static String url() {
        return "http://127.0.0.1:" + port + "/v.m3u8";
    }

    // ---------------------------------------------------------------- P0-1

    static void resumeCase() throws Exception {
        failSeg1 = false;
        slowIndex = 2;
        slowMs = 2000;

        File dir = Files.createTempDirectory("hls_resume").toFile();
        final HlsPlaylist pl = HlsPlaylist.parse(playlist(), url());
        ok(pl.segments().size() == 3, "清单解析出 3 片（前置条件）");

        // 第一次：第三片很慢，600ms 时点取消 → 留下 seg0/seg1 与 .manifest
        final AtomicBoolean stop = new AtomicBoolean(false);
        Thread canceller = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(600); } catch (InterruptedException ignore) {}
                stop.set(true);
            }
        });
        canceller.setDaemon(true);
        canceller.start();

        boolean cancelled = false;
        try {
            HlsDownloader.download(pl, url(), dir, "v", false, null, new HlsDownloader.Escape() {
                public boolean cancelled() { return stop.get(); }
            });
        } catch (IOException e) {
            cancelled = e.getMessage() != null && e.getMessage().contains("取消");
        }
        ok(cancelled, "第一次下载按预期取消");

        File parts = new File(dir, ".v.parts");
        int have = 0;
        for (int i = 0; i < 3; i++) {
            if (new File(parts, String.format("%06d.seg", i)).isFile()) have++;
        }
        eql(2, have, "取消后留下 2 片已完成的 .seg");
        ok(new File(parts, ".manifest").isFile(), "parts 目录写了清单指纹");

        // 第二次：续传。第三片仍稍慢（300ms），旧代码会在这里提前合并。
        slowIndex = 2;
        slowMs = 300;
        HlsDownloader.Result r = HlsDownloader.download(pl, url(), dir, "v", false, null, null);
        ok(r.file != null && r.file.isFile(), "续传下载产出文件：" + (r.file == null ? "null" : r.file.getName()));
        eql(SEG_SIZE * 3L, r.file.length(), "合并文件大小 = 全部分片（续传没有缺片）");
        ok(!parts.isDirectory(), "完成后清理了 parts 目录");
    }

    // ---------------------------------------------------------------- P0-2

    static void failFastCase() throws Exception {
        failSeg1 = true;
        slowIndex = -1;
        slowMs = 0;

        File dir = Files.createTempDirectory("hls_fail").toFile();
        final HlsPlaylist pl = HlsPlaylist.parse(playlist(), url());
        final Throwable[] err = new Throwable[1];

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    HlsDownloader.download(pl, url(), dir, "v", false, null, null);
                } catch (Throwable e) {
                    err[0] = e;
                }
            }
        });
        t.setDaemon(true);
        long t0 = System.currentTimeMillis();
        t.start();
        t.join(20000);
        long ms = System.currentTimeMillis() - t0;

        ok(!t.isAlive(), "分片 404 时不会无限等待（" + ms + "ms 返回）");
        if (t.isAlive()) {
            System.out.println("FAIL  下载线程 20 秒未返回，旧 bug 复现，后续断言跳过");
            fail++;
            System.out.println("-----------------------------------------");
            System.out.println("pass=" + pass + " fail=" + fail);
            System.exit(1);
        }
        ok(err[0] instanceof IOException, "抛出 IOException 而不是别的");
        String msg = err[0] == null ? "" : String.valueOf(err[0].getMessage());
        ok(msg.contains("404"), "错误信息带上真实原因（" + msg + "）");
    }
}
