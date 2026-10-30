import com.hex91.file.FtpFs;
import com.hex91.file.NetFs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * v1.60.0 FtpFs 全链路 JVM 测试：内置迷你 FTP 服务器（Socket 桩，内存文件系统）。
 *
 * <p>覆盖：登录、PASV 数据连接、STOR/RETR 收发一致性、LIST 解析（目录前+排序）、
 * MKD/DELE/RMD/RNFR+RNTO、递归删除、错误路径（读不存在文件抛 IOException）、
 * parseListLine 对 UNIX/DOS 风格行的单元解析。</p>
 */
public class FtpFsTest {

    static int passed = 0;

    static void check(boolean cond, String msg) {
        if (!cond) throw new RuntimeException("断言失败: " + msg);
        passed++;
        System.out.println("  ok: " + msg);
    }

    public static void main(String[] args) throws Exception {
        testParseListLine();
        MiniFtpServer server = new MiniFtpServer();
        Thread t = new Thread(server);
        t.setDaemon(true);
        t.start();
        // 等 server 就绪
        for (int i = 0; i < 50 && server.port == 0; i++) Thread.sleep(20);
        if (server.port == 0) throw new RuntimeException("迷你 FTP 服务器启动失败");
        testFullLoop(server.port);
        System.out.println("FtpFsTest PASS (" + passed + " checks)");
    }

    // ----------------------------------------------------------------
    // LIST 行解析单元测试
    // ----------------------------------------------------------------

    static void testParseListLine() {
        NetFs.NetEntry d = FtpFs.parseListLine("drwxr-xr-x   2 owner    group        4096 Oct 30 12:34 sub");
        check(d != null && d.dir && "sub".equals(d.name) && d.size == 0, "UNIX 目录行解析");

        NetFs.NetEntry f = FtpFs.parseListLine("-rw-r--r--   1 owner    group      123456 Oct 30 2023 hello.txt");
        check(f != null && !f.dir && "hello.txt".equals(f.name) && f.size == 123456,
                "UNIX 文件行解析（size=123456）");
        check(f.mtime > 0, "UNIX 老文件含年份 mtime 解析");

        NetFs.NetEntry recent = FtpFs.parseListLine("-rw-r--r--   1 owner group 77 Oct 30 21:00 a.log");
        check(recent != null && recent.mtime > 0, "UNIX 近期文件（补当前年）mtime 解析");

        NetFs.NetEntry link = FtpFs.parseListLine("lrwxrwxrwx   1 owner group 7 Oct 30 2024 ln -> target");
        check(link != null && "ln".equals(link.name) && !link.dir, "符号链接行取目标前缀");

        check(FtpFs.parseListLine("total 4") == null, "total 行跳过");

        NetFs.NetEntry dos = FtpFs.parseListLine("10-30-2024  12:34PM               1234 doc.pdf");
        check(dos != null && !dos.dir && "doc.pdf".equals(dos.name) && dos.size == 1234, "DOS 文件行解析");

        NetFs.NetEntry dosDir = FtpFs.parseListLine("10-30-2024  12:34PM       <DIR>          pics");
        check(dosDir != null && dosDir.dir && "pics".equals(dosDir.name), "DOS 目录行解析");

        NetFs.NetEntry dot = FtpFs.parseListLine("drwxr-xr-x 1 owner group 4096 Oct 30 12:00 ..");
        check(dot == null, "UNIX .. 行跳过");

        NetFs.NetEntry dosDot = FtpFs.parseListLine("10-30-2024  12:00PM       <DIR>          .");
        check(dosDot == null, "DOS . 行跳过");
    }

    // ----------------------------------------------------------------
    // 全链路：连接 → 写 → 读 → 列 → 改名 → 递归删 → 错误路径
    // ----------------------------------------------------------------

    static void testFullLoop(int port) throws Exception {
        FtpFs fs = FtpFs.connect("127.0.0.1", port, "test", "pass");
        System.out.println("  ok: 连接并登录");

        byte[] payload = "你好 FTP 1234 HexFile".getBytes(StandardCharsets.UTF_8);

        // 上传
        OutputStream out = fs.openWrite("hello.txt");
        out.write(payload);
        out.close(); // close 时应收到 226
        check(new String(serverFsGet("hello.txt"), StandardCharsets.UTF_8)
                .equals(new String(payload, StandardCharsets.UTF_8)), "STOR 上传内容落盘一致");

        // 下载
        InputStream in = fs.openRead("hello.txt");
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) got.write(buf, 0, n);
        in.close(); // close 时应收到 226
        check(java.util.Arrays.equals(got.toByteArray(), payload), "RETR 下载内容逐字节一致");

        // 根目录 LIST：目录在前、按名排序
        List<NetFs.NetEntry> root = fs.list("");
        boolean hasHello = false;
        for (NetFs.NetEntry e : root) {
            if ("hello.txt".equals(e.name)) { hasHello = true; check(!e.dir && e.size == payload.length, "根列表含 hello.txt 且 size 正确"); }
        }
        check(hasHello, "根列表含上传文件");
        boolean sorted = true;
        boolean fileSeen = false;
        for (NetFs.NetEntry e : root) {
            if (e.dir) { if (fileSeen) sorted = false; } // 文件之后不应再出现目录
            else fileSeen = true;
        }
        check(sorted, "目录排前");
        for (int i = 1; i < root.size(); i++) {
            NetFs.NetEntry a = root.get(i - 1), b = root.get(i);
            if (!a.dir && !b.dir) check(a.name.compareToIgnoreCase(b.name) <= 0, "文件按名排序");
        }

        // mkdir + 子目录写入 + LIST 子目录
        fs.mkdir("docs");
        OutputStream o2 = fs.openWrite("docs/说明 文件.txt");
        o2.write("inner".getBytes(StandardCharsets.UTF_8));
        o2.close();
        List<NetFs.NetEntry> docs = fs.list("docs");
        check(docs.size() == 1 && "说明 文件.txt".equals(docs.get(0).name) && docs.get(0).size == 5,
                "子目录 LIST（中文与空格文件名）");

        // 重命名
        fs.rename("hello.txt", "renamed.bin");
        check(serverFsGet("renamed.bin") != null && !serverFs.files.containsKey("hello.txt"), "RNFR+RNTO 重命名生效");

        // 递归删除目录（docs/docs2/内含文件）
        fs.mkdir("docs2");
        OutputStream o3 = fs.openWrite("docs2/inner.txt");
        o3.write("x".getBytes(StandardCharsets.UTF_8));
        o3.close();
        fs.delete("docs2", true);
        check(!serverFs.files.containsKey("docs2/inner.txt") && !serverFs.dirs.contains("docs2"), "目录递归删除");

        // 错误路径：读不存在的文件
        boolean threw = false;
        try { fs.openRead("no_such_file.bin"); } catch (Exception e) { threw = true; }
        check(threw, "读不存在文件抛异常");

        fs.close();
        System.out.println("  ok: QUIT 断开");
    }

    // ---- 服务器内存文件系统访问（测试断言用） ----
    static MiniFtpServer serverFs;

    static byte[] serverFsGet(String path) {
        return serverFs == null ? null : serverFs.files.get(path);
    }

    // ----------------------------------------------------------------
    // 迷你 FTP 服务器（协议桩）
    // ----------------------------------------------------------------

    /** 单客户端迷你 FTP 服务器：支持 USER/PASS/TYPE/OPTS/PASV/LIST/RETR/STOR/MKD/DELE/RMD/RNFR/RNTO/QUIT。 */
    static final class MiniFtpServer implements Runnable {
        final TreeMap<String, byte[]> files = new TreeMap<>();
        final TreeSet<String> dirs = new TreeSet<>();
        volatile int port;

        MiniFtpServer() {
            dirs.add("etc");
            files.put("readme.txt", "mini ftp server".getBytes(StandardCharsets.UTF_8));
        }

        public void run() {
            try {
                ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                port = ss.getLocalPort();
                serverFs = this;
                Socket c = ss.accept();
                serve(c);
            } catch (Exception e) {
                System.err.println("MiniFtpServer 异常: " + e);
            }
        }

        void serve(Socket ctrl) throws Exception {
            ctrl.setSoTimeout(30000);
            PrintStream w = new PrintStream(ctrl.getOutputStream(), true, "UTF-8");
            BufferedReader r = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), "UTF-8"));
            w.println("220 Mini FTP ready");
            ServerSocket pasv = null;
            String rnfr = null;
            String line;
            while ((line = r.readLine()) != null) {
                String cmd = line.toUpperCase();
                if (cmd.startsWith("USER") || cmd.startsWith("PASS")) w.println("230 Logged in");
                else if (cmd.startsWith("TYPE") || cmd.startsWith("OPTS")) w.println("200 ok");
                else if (cmd.startsWith("PASV")) {
                    if (pasv != null) try { pasv.close(); } catch (Exception ignore) {}
                    pasv = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                    int p = pasv.getLocalPort();
                    w.println("227 Entering Passive Mode (127,0,0,1," + (p / 256) + "," + (p % 256) + ")");
                } else if (cmd.startsWith("LIST")) {
                    String path = arg(line);
                    if (pasv == null) { w.println("425 no PASV"); continue; }
                    w.println("150 Opening data connection");
                    Socket d = pasv.accept();
                    StringBuilder sb = new StringBuilder();
                    // 根目录 "." → 全部；否则列该目录前缀
                    String prefix = (path.isEmpty() || ".".equals(path)) ? "" : path + "/";
                    for (String dir : dirs) {
                        if (prefix.isEmpty() ? !dir.contains("/") : !dir.equals(path) && dir.startsWith(prefix) && !dir.substring(prefix.length()).contains("/"))
                            sb.append("drwxr-xr-x   2 owner group 4096 Oct 30 12:34 ").append(base(dir)).append("\r\n");
                    }
                    for (Map.Entry<String, byte[]> e : files.entrySet()) {
                        if (prefix.isEmpty() ? !e.getKey().contains("/") : e.getKey().startsWith(prefix) && !e.getKey().substring(prefix.length()).contains("/"))
                            sb.append("-rw-r--r--   1 owner group ").append(e.getValue().length)
                              .append(" Oct 30 2024 ").append(base(e.getKey())).append("\r\n");
                    }
                    d.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    d.close();
                    try { pasv.close(); } catch (Exception ignore) {}
                    pasv = null;
                    w.println("226 Transfer complete");
                } else if (cmd.startsWith("RETR")) {
                    String path = arg(line);
                    byte[] data = files.get(path);
                    if (data == null || pasv == null) { w.println("550 File not found"); continue; }
                    w.println("150 Opening data connection");
                    Socket d = pasv.accept();
                    d.getOutputStream().write(data);
                    d.close();
                    try { pasv.close(); } catch (Exception ignore) {}
                    pasv = null;
                    w.println("226 Transfer complete");
                } else if (cmd.startsWith("STOR")) {
                    String path = arg(line);
                    if (pasv == null) { w.println("425 no PASV"); continue; }
                    w.println("150 Ready to receive");
                    Socket d = pasv.accept();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    InputStream din = d.getInputStream();
                    while ((n = din.read(buf)) > 0) bos.write(buf, 0, n);
                    d.close();
                    try { pasv.close(); } catch (Exception ignore) {}
                    pasv = null;
                    files.put(path, bos.toByteArray());
                    w.println("226 Transfer complete");
                } else if (cmd.startsWith("MKD")) {
                    String path = arg(line);
                    if (dirs.contains(path)) w.println("550 Directory already exists");
                    else { dirs.add(path); w.println("257 created"); }
                } else if (cmd.startsWith("DELE")) {
                    String path = arg(line);
                    if (files.remove(path) != null) w.println("250 deleted");
                    else w.println("550 not found");
                } else if (cmd.startsWith("RMD")) {
                    String path = arg(line);
                    if (dirs.remove(path)) w.println("250 removed");
                    else w.println("550 not found");
                } else if (cmd.startsWith("RNFR")) {
                    rnfr = arg(line);
                    w.println("350 ready");
                } else if (cmd.startsWith("RNTO")) {
                    String to = arg(line);
                    if (rnfr != null && files.containsKey(rnfr)) { files.put(to, files.remove(rnfr)); w.println("250 renamed"); }
                    else if (rnfr != null && dirs.remove(rnfr)) { dirs.add(to); w.println("250 renamed"); }
                    else w.println("550 not found");
                    rnfr = null;
                } else if (cmd.startsWith("QUIT")) {
                    w.println("221 bye");
                    ctrl.close();
                    return;
                } else {
                    w.println("502 not implemented: " + line);
                }
            }
        }

        static String arg(String line) {
            int i = line.indexOf(' ');
            return i < 0 ? "" : line.substring(i + 1).trim();
        }

        static String base(String path) {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }
    }
}
