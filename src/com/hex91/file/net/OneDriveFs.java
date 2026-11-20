package com.hex91.file.net;

import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.TempFiles;
import com.hex91.file.core.Iso8601;
import com.hex91.file.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v1.89.0 OneDrive（Microsoft Graph）文件系统。
 *
 * <p>认证走 {@link OAuthClient} 的设备码流程，这里只负责「拿着 refresh_token 干活」，
 * 以及 token 过期时自动刷新并回写。</p>
 *
 * <p>四个 Graph 特有的坑：</p>
 * <ol>
 *   <li><b>路径寻址要写成 {@code root:/a/b:} 的形式</b>，冒号是语法的一部分；
 *       路径里的空格/中文/{@code #}/{@code %} 必须逐段百分号编码（见 {@link CloudPath}），
 *       否则 {@code #} 之后会被当 fragment 丢掉，表现为「文件夹能进去但列不出文件」。</li>
 *   <li><b>下载不能直接打 {@code /content}</b>：它会 302 到预签名地址，
 *       而 {@code HttpURLConnection} 跨主机重定向时会把 {@code Authorization} 头一起带过去，
 *       目标存储会因此拒绝（签名冲突）。所以先取 {@code @microsoft.graph.downloadUrl}，
 *       再对那个地址<b>不带 Authorization</b> 地 GET。</li>
 *   <li><b>simple upload 上限 250MB</b>，且必须先知道 Content-Length，所以上传先落临时文件。</li>
 *   <li><b>改名和删除只认 item id</b>，路径寻址不支持 DELETE/PATCH，得先查一次 id。</li>
 * </ol>
 */
public final class OneDriveFs implements NetFs {

    private static final String BASE = "https://graph.microsoft.com/v1.0";
    /** 单次 simple upload 的上限（Graph 官方限制 250MB） */
    public static final long MAX_SIMPLE_UPLOAD = 250L * 1024 * 1024;

    private final String clientId;
    private String refresh;
    private OAuthToken token;
    private final OAuthClient.TokenSink sink;
    private final File tempDir;

    private OneDriveFs(String clientId, String refresh, OAuthClient.TokenSink sink, File tempDir) {
        this.clientId = clientId;
        this.refresh = refresh;
        this.sink = sink;
        this.tempDir = tempDir;
    }

    /**
     * 连接并验活（列一次根目录，把「没授权/权限不足」当场暴露）。
     *
     * @param clientId     Azure 应用的 Application (client) ID
     * @param refreshToken 设备码授权拿到的 refresh_token
     * @param tempDir      上传临时目录（调用方传应用 cache）
     */
    public static OneDriveFs connect(String clientId, String refreshToken,
                                     OAuthClient.TokenSink sink, File tempDir) throws IOException {
        if (OAuthClient.isBlank(clientId)) throw NetFsError.fieldRequired("Client ID");
        if (OAuthClient.isBlank(refreshToken))
            throw NetFsError.notAuthed(NetFsError.CLOUD_ONEDRIVE);
        OneDriveFs fs = new OneDriveFs(clientId.trim(), refreshToken.trim(), sink, tempDir);
        fs.access();                    // 先换一次 access，失败信息更准确
        fs.list("");
        return fs;
    }

    // ---------------------------------------------------------------- 认证

    private synchronized String access() throws IOException {
        if (token != null && token.hasAccess() && !token.expired(System.currentTimeMillis()))
            return token.access;
        if (OAuthClient.isBlank(refresh))
            throw NetFsError.authLost(NetFsError.CLOUD_ONEDRIVE);
        OAuthToken t;
        try {
            t = OAuthClient.refreshMs(clientId, refresh);
        } catch (OAuthToken.OAuthError e) {
            throw NetFsError.authExpired(NetFsError.CLOUD_ONEDRIVE, e.code, e);
        }
        token = t;
        // 微软通常不轮换 refresh_token，但一旦轮换就必须落盘
        if (t.hasRefresh() && !t.refresh.equals(refresh)) {
            refresh = t.refresh;
            if (sink != null) sink.onToken(t);
        }
        return t.access;
    }

    // ---------------------------------------------------------------- HTTP

    private static final class Resp {
        int code;
        String body;
    }

    private Resp raw(String method, String url, String jsonBody, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (jsonBody != null) {
                byte[] b = jsonBody.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                try { os.write(b); } finally { try { os.close(); } catch (IOException ignore) {} }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            r.body = readAll(r.code >= 400 ? c.getErrorStream() : c.getInputStream());
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** 带鉴权的调用：401 时刷新一次再重试（只重试一次，避免死循环） */
    private String call(String method, String pathOrUrl, String jsonBody) throws IOException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : BASE + pathOrUrl;
        Resp r = raw(method, url, jsonBody, access());
        if (r.code == 401) {
            synchronized (this) {
                token = null;                       // 强制下次重新换 access
            }
            r = raw(method, url, jsonBody, access());
        }
        if (r.code == 204) return "";
        if (r.code >= 400) throw httpErr(method + " " + pathOrUrl, r);
        return r.body;
    }

    private static NetFsError httpErr(String op, Resp r) {
        Object root = Json.parseOrNull(r.body);
        String code = Json.strPath(root, "error.code");
        String msg = Json.strPath(root, "error.message");
        int hint = NetFsError.HINT_NONE;
        if ("itemNotFound".equals(code)) hint = NetFsError.HINT_CLOUD_NOT_FOUND;
        else if ("invalidAuthenticationToken".equals(code) || r.code == 401) hint = NetFsError.HINT_CLOUD_AUTH_EXPIRED;
        else if ("accessDenied".equals(code) || "forbidden".equals(code)) hint = NetFsError.HINT_CLOUD_NO_PERM;
        else if (r.code == 507 || "quotaLimitReached".equals(code)) hint = NetFsError.HINT_CLOUD_QUOTA;
        else if (r.code == 413) hint = NetFsError.HINT_CLOUD_413;
        String detail = code.isEmpty() ? abbreviate(r.body) : code + (msg.isEmpty() ? "" : " · " + msg);
        return NetFsError.httpDetailFail(op, r.code, hint, detail);
    }

    // ---------------------------------------------------------------- 列目录

    @Override
    public List<NetEntry> list(String path) throws IOException {
        String p = CloudPath.normalize(path);
        String url = CloudPath.graphChildren(p)
                + "?$select=name,size,lastModifiedDateTime,folder,file&$top=200";
        List<NetEntry> dirs = new ArrayList<NetEntry>(), files = new ArrayList<NetEntry>();
        for (int guard = 0; guard < 100; guard++) {          // 100 页封顶，防服务端 nextLink 死循环
            String body = call("GET", url, null);
            Object root = Json.parseOrNull(body);
            for (Object item : Json.arr(root, "value")) {
                String name = Json.str(item, "name");
                if (name.isEmpty()) continue;
                boolean dir = Json.get(item, "folder") instanceof Map;
                long size = Json.num(item, "size", 0);
                long mtime = Iso8601.parse(Json.str(item, "lastModifiedDateTime"));
                (dir ? dirs : files).add(new NetEntry(name, dir, dir ? 0 : size, mtime));
            }
            String next = Json.str(root, "@odata.nextLink");
            if (next.isEmpty()) break;
            url = next;                                      // nextLink 是绝对地址，已带分页参数
        }
        sortByName(dirs);
        sortByName(files);
        List<NetEntry> out = new ArrayList<NetEntry>(dirs.size() + files.size());
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    private static void sortByName(List<NetEntry> l) {
        Collections.sort(l, new Comparator<NetEntry>() {
            public int compare(NetEntry a, NetEntry b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    // ---------------------------------------------------------------- 下载

    @Override
    public InputStream openRead(String path) throws IOException {
        return get(path, 0);
    }

    @Override
    public boolean supportsResume() {
        return true;                                         // downloadUrl 支持 Range
    }

    @Override
    public InputStream openReadFrom(String path, long offset) throws IOException {
        return get(path, offset);
    }

    private InputStream get(String path, long offset) throws IOException {
        String url = downloadUrl(path);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        // 预签名地址自带鉴权，带 Authorization 反而会被拒
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
        int code = c.getResponseCode();
        if (offset > 0) {
            if (code == 200) { drain(c); throw NetFsError.rangeIgnored(); }
            if (code != 206) {
                drain(c);
                throw NetFsError.httpFail(path, code, NetFsError.HINT_NONE);
            }
            return c.getInputStream();
        }
        if (code != 200) {
            drain(c);
            throw NetFsError.httpFail(path, code, NetFsError.HINT_NONE);
        }
        return c.getInputStream();
    }

    /** 取预签名下载地址（有效期约 1 小时，够一次下载） */
    private String downloadUrl(String path) throws IOException {
        String p = CloudPath.normalize(path);
        if (p.isEmpty()) throw NetFsError.rootNotAllowed("download");
        String body = call("GET", CloudPath.graphItem(p) + "?$select=id,size,@microsoft.graph.downloadUrl", null);
        Object root = Json.parseOrNull(body);
        String url = Json.str(root, "@microsoft.graph.downloadUrl");
        if (url.isEmpty()) throw NetFsError.noDownloadUrl(p);
        return url;
    }

    // ---------------------------------------------------------------- 上传

    @Override
    public OutputStream openWrite(String path) throws IOException {
        File dir = tempDir;
        if (dir == null) throw NetFsError.tmpDirMissing();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw NetFsError.tmpDirFail(dir.getAbsolutePath());
        return new SpoolOut(File.createTempFile("odup", TempFiles.PART, dir), path);
    }

    private final class SpoolOut extends OutputStream {
        private final File tmp;
        private final String path;
        private final FileOutputStream out;
        private boolean closed;

        SpoolOut(File tmp, String path) throws IOException {
            this.tmp = tmp;
            this.path = CloudPath.normalize(path);
            this.out = new FileOutputStream(tmp);
        }

        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void flush() throws IOException { out.flush(); }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { out.close(); } catch (IOException ignore) {}
            try {
                long len = tmp.length();
                if (len > MAX_SIMPLE_UPLOAD)
                    throw NetFsError.uploadTooLarge("250MB", FileOps.formatSize(len), "SMB / WebDAV");
                if (CloudPath.name(path).isEmpty()) throw NetFsError.emptyUploadPath();
                mkdirs(CloudPath.parent(path));        // Graph 不会自动建父目录，会直接 404

                String url = BASE + CloudPath.graphContent(path);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                try {
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(300000);
                    c.setRequestMethod("PUT");
                    c.setRequestProperty("Authorization", "Bearer " + access());
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode((int) len);
                    OutputStream os = c.getOutputStream();
                    InputStream in = new FileInputStream(tmp);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        try { in.close(); } catch (IOException ignore) {}
                        try { os.close(); } catch (IOException ignore) {}
                    }
                    int code = c.getResponseCode();
                    String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    if (code >= 400) {
                        Resp r = new Resp();
                        r.code = code;
                        r.body = body;
                        throw httpErr("Upload " + path, r);
                    }
                } finally {
                    c.disconnect();
                }
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
    }

    // ---------------------------------------------------------------- 目录 / 删除 / 改名

    @Override
    public void mkdir(String path) throws IOException {
        String p = CloudPath.normalize(path);
        if (p.isEmpty()) return;
        CloudPath.NameError reason = CloudPath.illegalNameReason(CloudPath.name(p));
        if (reason != null) throw NetFsError.badName(reason);
        createFolder(CloudPath.parent(p), CloudPath.name(p));
    }

    /** 逐级补齐父目录（上传前调用；已存在会被 conflictBehavior=replace 吃掉） */
    private void mkdirs(String dir) throws IOException {
        String p = CloudPath.normalize(dir);
        if (p.isEmpty()) return;
        String acc = "";
        for (String seg : CloudPath.segments(p)) {
            acc = acc.isEmpty() ? seg : acc + "/" + seg;
            createFolder(CloudPath.parent(acc), seg);
        }
    }

    private void createFolder(String parent, String name) throws IOException {
        CloudPath.NameError reason = CloudPath.illegalNameReason(name);
        if (reason != null) throw NetFsError.badName(reason);
        String body = "{\"name\":" + OAuthClient.quote(name)
                + ",\"folder\":{},\"@microsoft.graph.conflictBehavior\":\"replace\"}";
        call("POST", CloudPath.graphChildren(parent), body);
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        String id = itemId(path);
        call("DELETE", "/me/drive/items/" + CloudPath.encodeSegment(id), null);
        // 目录由服务端递归删除，不需要自己遍历
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        CloudPath.NameError reason = CloudPath.illegalNameReason(newName);
        if (reason != null) throw NetFsError.badName(reason);
        String id = itemId(path);
        call("PATCH", "/me/drive/items/" + CloudPath.encodeSegment(id),
                "{\"name\":" + OAuthClient.quote(newName) + "}");
    }

    private String itemId(String path) throws IOException {
        String p = CloudPath.normalize(path);
        if (p.isEmpty()) throw NetFsError.rootNotAllowed("operate");
        String body = call("GET", CloudPath.graphItem(p) + "?$select=id", null);
        String id = Json.str(Json.parseOrNull(body), "id");
        if (id.isEmpty()) throw NetFsError.noItemId(p);
        return id;
    }

    @Override
    public void close() {
        // 无长连接（HttpURLConnection 由连接池管理）
    }

    // ---------------------------------------------------------------- 工具

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es != null) {
                byte[] b = new byte[4096];
                while (es.read(b) >= 0) { /* drain */ }
                es.close();
            }
        } catch (Exception ignore) {}
        c.disconnect();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }
}
