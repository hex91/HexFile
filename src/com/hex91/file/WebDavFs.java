package com.hex91.file;

import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.56.0 WebDAV 网络存储实现：HttpURLConnection + 系统自带 XmlPullParser，零第三方依赖。
 *
 * <p>支持方法：PROPFIND(Depth 1) 列目录 / GET 下载 / PUT 上传 / MKCOL 建目录 /
 * DELETE 删除 / MOVE 重命名。认证：HTTP Basic（用户名留空则匿名）。</p>
 *
 * <p>base URL 形如 {@code http://192.168.1.10:5005/dav/}（须以 / 结尾），
 * 远程路径为其下的相对路径（'/' 分隔，空串表示根）。</p>
 */
public final class WebDavFs implements NetFs {

    private final String base;        // 以 / 结尾的 base URL
    private final String authHeader;  // "Basic base64(...)"；匿名时为 null

    public WebDavFs(String baseUrl, String user, String pass) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (!b.endsWith("/")) b = b + "/";
        this.base = b;
        if (user == null || user.isEmpty()) {
            this.authHeader = null;
        } else {
            String raw = user + ":" + (pass == null ? "" : pass);
            this.authHeader = "Basic " + Base64.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
        }
    }

    // ----------------------------------------------------------------
    // 底层连接
    // ----------------------------------------------------------------

    private HttpURLConnection conn(String method, String remotePath) throws IOException {
        String url = base + encPath(remotePath);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        return c;
    }

    /** 路径按段 URL 编码（保留 '/'），保证中文/空格/特殊字符可用。 */
    private static String encPath(String path) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    private static void consume(HttpURLConnection c) {
        try { InputStream e = c.getErrorStream(); if (e != null) { byte[] b = new byte[4096]; while (e.read(b) >= 0) {} e.close(); } } catch (IOException ignore) {}
        c.disconnect();
    }

    private static IOException httpErr(String op, HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        String msg = c.getResponseMessage();
        return new IOException(op + " 失败: HTTP " + code + (msg == null ? "" : " " + msg)
                + hintOf(code));
    }

    private static String hintOf(int code) {
        if (code == 401) return "（认证失败：用户名或密码错误）";
        if (code == 403) return "（服务器拒绝：无权限）";
        if (code == 404) return "（路径不存在：检查服务器地址）";
        if (code == 405) return "（服务器不支持该操作）";
        return "";
    }

    // ----------------------------------------------------------------
    // NetFs
    // ----------------------------------------------------------------

    @Override
    public List<NetEntry> list(String path) throws IOException {
        HttpURLConnection c = conn("PROPFIND", path);
        c.setRequestProperty("Depth", "1");
        c.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
        c.setDoOutput(true);
        OutputStream out = c.getOutputStream();
        out.write(PROPFIND_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.close();

        int code = c.getResponseCode();
        if (code != 207 && code != 200) {
            consume(c);
            throw httpErr("列目录 " + path, c);
        }

        List<NetEntry> dirs = new ArrayList<>(), files = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        InputStream in = c.getInputStream();
        try {
            p.setInput(in, null);
            // null = 未在收集；非 null（含空串）= 正在收集当前元素文本
            String href = null, size = null, lm = null;
            boolean isDir = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("href".equals(n)) href = "";
                    else if ("getcontentlength".equals(n)) size = "";
                    else if ("getlastmodified".equals(n)) lm = "";
                    else if ("collection".equals(n)) isDir = true;
                } else if (ev == XmlPullParser.TEXT) {
                    String t = p.getText();
                    if (t == null) continue;
                    if (href != null) href += t;
                    else if (size != null) size += t;
                    else if (lm != null) lm += t;
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if ("href".equals(n) && href != null) href = href.trim();
                    else if ("getcontentlength".equals(n) && size != null) size = size.trim();
                    else if ("getlastmodified".equals(n) && lm != null) lm = lm.trim();
                    else if ("response".equals(n)) {
                        String h = decodeHref(href);
                        if (h != null && !h.isEmpty() && !normHref(h).equals(normHref(path))) {
                            // 跳过自身（目录自身可能带或不带尾斜杠）
                            String name = NetFs.baseName(h.endsWith("/") ? h.substring(0, h.length() - 1) : h);
                            if (!name.isEmpty()) {
                                long sz = 0, mt = 0;
                                try { if (size != null) sz = Long.parseLong(size); } catch (NumberFormatException ignore) {}
                                if (lm != null && !lm.isEmpty()) mt = parseHttpDate(lm);
                                (isDir ? dirs : files).add(new NetEntry(name, isDir, sz, mt));
                            }
                        }
                        href = null; size = null; lm = null; isDir = false;
                    }
                }
                ev = p.next();
            }
        } catch (org.xmlpull.v1.XmlPullParserException xpe) {
            throw new IOException("响应解析失败（非 WebDAV 服务？）: " + xpe.getMessage(), xpe);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
            c.disconnect();
        }
        sortByName(dirs);
        sortByName(files);
        List<NetEntry> outList = new ArrayList<>(dirs.size() + files.size());
        outList.addAll(dirs);
        outList.addAll(files);
        return outList;
    }

    private static void sortByName(List<NetEntry> l) {
        Collections.sort(l, new java.util.Comparator<NetEntry>() {
            public int compare(NetEntry a, NetEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
            + "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>"
            + "</D:prop></D:propfind>";

    /** href 去编码、去掉 base 前缀，归一为远程相对路径。 */
    private String decodeHref(String href) {
        if (href == null) return null;
        try {
            href = java.net.URLDecoder.decode(href, "UTF-8");
        } catch (Exception ignore) {}
        // 可能返回绝对 URL、绝对路径或相对路径 → 统一截取 base 之后的部分
        String path = href;
        try {
            URL u = new URL(href);
            path = u.getPath();
        } catch (Exception ignore) {}
        String basePath = base.substring(base.indexOf("://") + 3); // host:port/dav/
        basePath = basePath.substring(basePath.indexOf('/'));      // /dav/
        if (path != null && path.startsWith(basePath)) path = path.substring(basePath.length());
        while (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    private static String normHref(String p) {
        if (p == null) return "";
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static long parseHttpDate(String s) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        try {
            Date d = f.parse(s.trim());
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        HttpURLConnection c = conn("GET", path);
        int code = c.getResponseCode();
        if (code != 200) { consume(c); throw httpErr("读取 " + path, c); }
        return c.getInputStream(); // close 时断开连接
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        ensureParents(path);
        HttpURLConnection c = conn("PUT", path);
        c.setDoOutput(true);
        c.setChunkedStreamingMode(64 * 1024);
        return new HttpOutStream(c); // close 时等待响应码并校验
    }

    /** PUT 输出流：close 时读取响应码，非 2xx 抛错（避免上传失败被静默吞掉）。 */
    private static final class HttpOutStream extends OutputStream {
        private final HttpURLConnection c;
        private final OutputStream out;
        private boolean closed;

        HttpOutStream(HttpURLConnection c) throws IOException {
            this.c = c;
            this.out = c.getOutputStream();
        }

        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { out.close(); } catch (IOException ignore) {}
            int code;
            try { code = c.getResponseCode(); } catch (IOException e) { throw new IOException("上传失败: " + e.getMessage(), e); }
            consume(c);
            if (code < 200 || code >= 300) throw new IOException("上传失败: HTTP " + code + hintOf(code));
        }
    }

    /** 逐级补齐父目录（MKCOL）。 */
    private void ensureParents(String path) throws IOException {
        String parent = NetFs.parentOf(path);
        if (parent.isEmpty()) return;
        String[] segs = parent.split("/");
        String cur = "";
        for (String s : segs) {
            if (s.isEmpty()) continue;
            cur = cur.isEmpty() ? s : cur + "/" + s;
            if (!folderExists(cur)) mkdir(cur);
        }
    }

    private boolean folderExists(String path) {
        HttpURLConnection c;
        try {
            c = conn("PROPFIND", path);
            c.setRequestProperty("Depth", "0");
            int code = c.getResponseCode();
            consume(c);
            return code == 207 || code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        HttpURLConnection c = conn("MKCOL", path);
        int code;
        try { code = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
        consume(c);
        if (code != 201 && code != 405) throw httpErr("创建目录 " + path, c); // 405 = 已存在
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        HttpURLConnection c = conn("DELETE", path);
        int code;
        try { code = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
        consume(c);
        if (code != 204 && code != 200) throw httpErr("删除 " + path, c);
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        String target = NetFs.join(NetFs.parentOf(path), newName);
        HttpURLConnection c = conn("MOVE", path);
        c.setRequestProperty("Destination", base + encPath(target));
        c.setRequestProperty("Overwrite", "T");
        int code;
        try { code = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
        consume(c);
        if (code != 201 && code != 204 && code != 200) throw httpErr("重命名 " + path, c);
    }

    @Override
    public void close() {
        // HttpURLConnection 无常驻连接需要显式关闭（底层由连接池管理）
    }
}
