package com.hex91.file.net;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.60.0 FTP 网络存储实现：纯 {@link Socket} 手写客户端，零第三方依赖。
 *
 * <p>实现范围：登录（USER/PASS，支持匿名）、二进制模式（TYPE I）、
 * 被动模式数据连接（PASV，数据端口连接复用控制连接的 host 地址，兼容 NAT/多网卡）、
 * LIST 目录解析（UNIX ls 风格 + Windows/IIS DOS 风格）、RETR/STOR 流式收发、
 * MKD/RMD/DELE/RNFR+RNTO、目录递归删除。</p>
 *
 * <p>控制连接使用 UTF-8 编码（发送 OPTS UTF8 ON，失败忽略——老服务器 GBK 文件名可能乱码）。
 * 实例非线程安全：所有操作共用一条控制连接，须顺序调用（Activity 的用法即顺序）。</p>
 *
 * <p>远程路径约定与 {@link NetFs} 一致：'/' 分隔、空串表示登录后的起始目录。</p>
 */
public final class FtpFs implements NetFs {

    private static final String TAG = "FtpFs";

    private final String host;
    private final int port;
    private Socket ctrl;                 // 控制连接
    private BufferedReader rin;          // 控制连接读（按行）
    private OutputStream rout;           // 控制连接写

    private FtpFs(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** 连接并登录。user 为空按匿名（anonymous / anonymous@）处理。 */
    public static FtpFs connect(String host, int port, String user, String pass) throws IOException {
        FtpFs fs = new FtpFs(host, port);
        fs.openCtrl();
        fs.cmd("TYPE I"); // 二进制
        try { fs.cmd("OPTS UTF8 ON"); } catch (IOException ignore) {} // 老服务器不支持
        String u = (user == null || user.isEmpty()) ? "anonymous" : user;
        String p = (pass == null || pass.isEmpty()) ? "hexfile@" : pass;
        int code = fs.send("USER " + u);
        if (code == 331) code = fs.send("PASS " + p);
        if (code != 230 && code != 202)
            throw NetFsError.protoFail(NetFsError.PROTO_FTP, "LOGIN", fs.lastReply, NetFsError.HINT_FTP_LOGIN);
        return fs;
    }

    // ----------------------------------------------------------------
    // 控制连接基础：回复读取 / 命令发送
    // ----------------------------------------------------------------

    private String lastReply = "";

    /** 建立控制连接并读取 220 欢迎语。 */
    private void openCtrl() throws IOException {
        ctrl = new Socket();
        ctrl.connect(new InetSocketAddress(host, port), 10000);
        ctrl.setSoTimeout(30000);
        rin = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        rout = ctrl.getOutputStream();
        int code = readReply();
        if (code != 220) throw NetFsError.badGreeting(lastReply);
    }

    /** 读一条完整回复（多行 1xx/2xx 直到「同 code + 空格」的终止行），返回状态码。 */
    private int readReply() throws IOException {
        String line = rin.readLine();
        if (line == null) throw NetFsError.disconnected(NetFsError.PROTO_FTP);
        // 多行回复形如 "220-..."，后续行以 "220 " 结束
        while (line.length() >= 4 && line.charAt(3) == '-') {
            line = rin.readLine();
            if (line == null) throw NetFsError.disconnected(NetFsError.PROTO_FTP);
        }
        lastReply = line;
        int code = 0;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (Exception e) {
            // 服务器回了非标准状态行（有些嵌入式 FTP 会多打几行横幅）。往下走会当未知码处理，
            // 但排查「连上了却列不出目录」时，只有这条日志能看出是回复格式的问题
            Log.w(TAG, "Unparsable status line: " + (line.length() > 40 ? line.substring(0, 40) : line), e);
        }
        return code;
    }

    /** 发命令并读回复，返回状态码。 */
    private int send(String cmd) throws IOException {
        rout.write((cmd + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rout.flush();
        return readReply();
    }

    /** 发命令并要求 2xx 成功，否则抛错。 */
    private void cmd(String cmd) throws IOException {
        int code = send(cmd);
        if (code < 200 || code >= 300) throw NetFsError.protoFail(NetFsError.PROTO_FTP, cmd.split(" ")[0], lastReply, NetFsError.HINT_NONE);
    }

    // ----------------------------------------------------------------
    // 被动模式数据连接
    // ----------------------------------------------------------------

    /** PASV → 建立数据连接（IP 用控制连接的 host，兼容服务器返回内网地址的场景）。 */
    private Socket dataSocket() throws IOException {
        int code = send("PASV");
        if (code != 227) throw NetFsError.pasvFail(NetFsError.PROTO_FTP, lastReply, NetFsError.HINT_FTP_PASV);
        int s = lastReply.indexOf('('), e = lastReply.indexOf(')');
        if (s < 0 || e < 0) throw NetFsError.replyParse(NetFsError.PROTO_FTP, lastReply);
        String[] parts = lastReply.substring(s + 1, e).split(",");
        if (parts.length != 6) throw NetFsError.replyParse(NetFsError.PROTO_FTP, lastReply);
        int p1, p2;
        try {
            p1 = Integer.parseInt(parts[4].trim());
            p2 = Integer.parseInt(parts[5].trim());
        } catch (NumberFormatException nfe) {
            throw NetFsError.replyParse(NetFsError.PROTO_FTP, lastReply);
        }
        Socket d = new Socket();
        d.connect(new InetSocketAddress(host, p1 * 256 + p2), 10000); // host 而非服务器返回的 IP
        d.setSoTimeout(30000);
        return d;
    }

    // ----------------------------------------------------------------
    // LIST 解析
    // ----------------------------------------------------------------

    @Override
    public List<NetEntry> list(String path) throws IOException {
        String target = path == null || path.isEmpty() ? "." : path;
        Socket d = dataSocket();
        BufferedReader din = new BufferedReader(
                new InputStreamReader(d.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        int code;
        try {
            code = send("LIST " + target);
            if (code != 150 && code != 125) throw NetFsError.protoFail(NetFsError.PROTO_FTP, "LIST", lastReply, NetFsError.HINT_NONE);
            List<NetEntry> dirs = new ArrayList<>(), files = new ArrayList<>();
            String line;
            while ((line = din.readLine()) != null) {
                NetEntry en = parseListLine(line);
                if (en != null) (en.dir ? dirs : files).add(en);
            }
            int done = readReply(); // 数据传完后的 226（不读会错位到下一条命令的回复）
            if (done != 226 && done != 250)
                throw NetFsError.transferIncomplete(NetFsError.PROTO_FTP, "LIST", lastReply);
            sortByName(dirs);
            sortByName(files);
            List<NetEntry> out = new ArrayList<>(dirs.size() + files.size());
            out.addAll(dirs);
            out.addAll(files);
            return out;
        } finally {
            try { d.close(); } catch (IOException ignore) {}
        }
    }

    private static void sortByName(List<NetEntry> l) {
        Collections.sort(l, new java.util.Comparator<NetEntry>() {
            public int compare(NetEntry a, NetEntry b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    /** 解析一行 LIST 输出；无法识别 / "." ".." 返回 null。 */
    public static NetEntry parseListLine(String line) {
        if (line == null || line.isEmpty()) return null;
        if (line.startsWith("total ") || line.startsWith("Total")) return null; // UNIX total 行

        // ---- UNIX ls 风格: drwxr-xr-x 1 owner group 4096 Oct 30 12:34 name
        if (line.length() > 10 && (line.charAt(0) == 'd' || line.charAt(0) == '-' || line.charAt(0) == 'l')) {
            String[] t = line.split("\\s+", 9);
            if (t.length >= 9) {
                String name = t[8];
                // "name -> target" 符号链接取目标前缀
                int arrow = name.indexOf(" -> ");
                if (arrow > 0) name = name.substring(0, arrow);
                if (name.equals(".") || name.equals("..")) return null;
                long size = 0;
                try { size = Long.parseLong(t[4]); } catch (NumberFormatException ignore) {}
                boolean dir = t[0].charAt(0) == 'd';
                if (dir) size = 0; // 目录 size 无意义
                if (!dir && t[0].charAt(0) == 'l') {
                    // 链接指向目录的判断不可靠，按文件处理（symlink 到目录时点开会失败，可接受）
                    dir = false;
                }
                return new NetEntry(name, dir, size, parseUnixTime(t[5], t[6], t[7]));
            }
        }

        // ---- Windows/IIS DOS 风格: 10-30-2024 12:34PM <DIR> name | 10-30-2024 12:34PM 1234 name
        String[] t = line.trim().split("\\s+", 4);
        if (t.length >= 4 && t[0].matches("\\d{1,2}-\\d{1,2}-\\d{2,4}")) {
            boolean dir = t[2].equalsIgnoreCase("<DIR>");
            String name = t[3];
            if (name.equals(".") || name.equals("..")) return null;
            long size = 0;
            if (!dir) { try { size = Long.parseLong(t[2]); } catch (NumberFormatException ignore) {} }
            return new NetEntry(name, dir, size, parseDosTime(t[0], t[1]));
        }
        return null; // 未知格式行跳过
    }

    /** "Oct 30 12:34"（当年）/ "Oct 30 2023"（含年份）→ epoch millis；解析失败返回 0。 */
    private static long parseUnixTime(String mon, String day, String rest) {
        if (rest != null && rest.indexOf(':') >= 0) {
            return tryTime("MMM d HH:mm yyyy", mon + " " + day + " " + rest + " " + yearNow());
        }
        return tryTime("MMM d yyyy", mon + " " + day + " " + rest);
    }

    private static int yearNow() {
        return java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
    }

    /** "10-30-2024" + "12:34PM" → epoch millis（服务器本地时区）。 */
    private static long parseDosTime(String date, String time) {
        String[] parts = date.split("-");
        if (parts.length != 3) return 0;
        String year = parts[2].length() == 2 ? ("20" + parts[2]) : parts[2];
        String isoDate = year + "-" + pad2(parts[0]) + "-" + pad2(parts[1]);
        long v = tryTime("yyyy-MM-dd hh:mma", isoDate + " " + time.toUpperCase(Locale.US));
        return v != 0 ? v : tryTime("yyyy-MM-dd HH:mm", isoDate + " " + time);
    }

    private static String pad2(String s) {
        return s.length() < 2 ? "0" + s : s;
    }

    private static long tryTime(String pattern, String input) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(java.util.TimeZone.getDefault());
        try {
            Date d = f.parse(input.trim());
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    // ----------------------------------------------------------------
    // 收发流（RETR / STOR）
    // ----------------------------------------------------------------

    @Override
    public InputStream openRead(String path) throws IOException {
        return retr(path, 0);
    }

    /**
     * v1.88.0 断点续传：REST 让服务器把读指针挪到 offset，再 RETR。
     * 服务器不支持 REST（回 5xx）时抛错，由上层退化为从头下载。
     */
    @Override
    public InputStream openReadFrom(String path, long offset) throws IOException {
        if (offset <= 0) return retr(path, 0);
        return retr(path, offset);
    }

    @Override
    public boolean supportsResume() {
        return true;
    }

    @Override
    public boolean supportsAppend() {
        return true;
    }

    /** PASV → (REST offset) → RETR。offset>0 时要求服务器接受 REST。 */
    private InputStream retr(String path, long offset) throws IOException {
        Socket d = dataSocket();
        if (offset > 0) {
            int r = send("REST " + offset);
            if (r != 350) {
                try { d.close(); } catch (IOException ignore) {}
                throw NetFsError.protoFail(NetFsError.PROTO_FTP, "REST", lastReply, NetFsError.HINT_NONE);
            }
        }
        int code = send("RETR " + path);
        if (code != 150 && code != 125) {
            try { d.close(); } catch (IOException ignore) {}
            throw NetFsError.protoFail(NetFsError.PROTO_FTP, "RETR", lastReply, NetFsError.HINT_FTP_NO_PERM);
        }
        return new DataIn(d);
    }

    /** RETR 数据流：close 时读完剩余并等待 226 传输完成回复。 */
    private final class DataIn extends InputStream {
        private final Socket sock;
        private final InputStream in;
        private boolean closed;

        DataIn(Socket s) throws IOException { this.sock = s; this.in = s.getInputStream(); }

        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { in.close(); } catch (IOException ignore) {}
            try { sock.close(); } catch (IOException ignore) {}
            int code = readReply(); // 226 = 传输完成
            if (code != 226 && code != 250)
                throw NetFsError.transferIncomplete(NetFsError.PROTO_FTP, "RETR", lastReply);
        }
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        return stor(path, false);
    }

    /** v1.88.0 上传续传：APPE（追加）而不是 STOR（覆盖）。 */
    @Override
    public OutputStream openWriteFrom(String path, long offset) throws IOException {
        return stor(path, offset > 0);
    }

    /** PASV → STOR/APPE。append=true 时保留远端已有内容，从末尾续写。 */
    private OutputStream stor(String path, boolean append) throws IOException {
        Socket d = dataSocket();
        int code = send((append ? "APPE " : "STOR ") + path);
        if (code != 150 && code != 125) {
            try { d.close(); } catch (IOException ignore) {}
            throw NetFsError.protoFail(NetFsError.PROTO_FTP, "STOR", lastReply, NetFsError.HINT_FTP_NO_PERM);
        }
        return new DataOut(d);
    }

    /** STOR 数据流：close 时等待 226 确认上传落盘。 */
    private final class DataOut extends OutputStream {
        private final Socket sock;
        private final OutputStream out;
        private boolean closed;

        DataOut(Socket s) throws IOException { this.sock = s; this.out = s.getOutputStream(); }

        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void flush() throws IOException { out.flush(); }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            out.close();
            sock.close();
            int code = readReply();
            if (code != 226 && code != 250)
                throw NetFsError.transferIncomplete(NetFsError.PROTO_FTP, "STOR", lastReply);
        }
    }

    // ----------------------------------------------------------------
    // 目录 / 删除 / 重命名
    // ----------------------------------------------------------------

    @Override
    public void mkdir(String path) throws IOException {
        int code = send("MKD " + path);
        if (code == 550 && lastReply.contains("exist")) return; // 已存在，容忍（与 WebDavFs 405 语义一致）
        if (code < 200 || code >= 300)
            throw NetFsError.protoFail(NetFsError.PROTO_FTP, "MKD", lastReply, NetFsError.HINT_NONE);
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        if (dir) {
            for (NetEntry c : list(path)) delete(NetFs.join(path, c.name), c.dir);
            cmd("RMD " + path);
        } else {
            cmd("DELE " + path);
        }
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        int code = send("RNFR " + path);
        if (code != 350) throw NetFsError.protoFail(NetFsError.PROTO_FTP, "RNFR", lastReply, NetFsError.HINT_NONE);
        cmd("RNTO " + NetFs.join(NetFs.parentOf(path), newName));
    }

    @Override
    public void close() {
        // QUIT 写入走独立线程：本方法可能被主线程调用（返回键断开），
        // 主线程做网络 I/O 会抛 NetworkOnMainThreadException 直接崩溃
        final Socket c = ctrl;
        ctrl = null;
        if (c == null) return;
        new Thread(new Runnable() {
            public void run() {
                try { c.getOutputStream().write("QUIT\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); } catch (IOException ignore) {}
                try { c.close(); } catch (IOException ignore) {}
            }
        }, "FtpFs-quit").start();
    }
}
