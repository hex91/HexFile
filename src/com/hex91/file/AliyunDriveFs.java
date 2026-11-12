package com.hex91.file;

import com.hex91.file.core.*;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.89.0 阿里云盘文件系统（adrive v2 接口 + refresh_token）。
 *
 * <p>这里用的不是「开放平台」而是阿里云盘客户端自己用的接口，原因是现实：
 * 开放平台（{@code openapi.alipark.com}）要注册企业开发者、提交应用审核，
 * 个人拿不到；而 {@code auth.aliyundrive.com} + {@code api.aliyundrive.com/adrive/v2}
 * 这套接口接受 refresh_token，功能完整（列目录/下载/上传/改名/删除/移动）。</p>
 *
 * <p>由此带来三个必须在实现里处理的问题：</p>
 * <ol>
 *   <li><b>refresh_token 是一次性轮换的</b>：每次刷新都会给新的，旧的立刻作废。
 *       所以刷新成功后必须 {@link OAuthClient.TokenSink#onToken} 回写，否则下次必然失败；</li>
 *   <li><b>它是 file_id 模型，不是路径模型</b>：{@link NetFs} 用的是路径，
 *       所以每次操作都要把 {@code a/b/c} 逐级换成 id —— 用 {@link #idCache} 缓存，
 *       并在改名/删除后把相关前缀整段失效（否则改名后还拿旧 id 去操作，报 NotFound）；</li>
 *   <li><b>上传分片数由服务端在 create 时给出</b>：客户端按 part_number 顺序、
 *       每片 {@code ceil(size/片数)} 字节切片 PUT，最后必须调 complete，
 *       否则文件会一直停在「上传中」状态。</li>
 * </ol>
 */
public final class AliyunDriveFs implements NetFs {

    private static final String API = "https://api.aliyundrive.com";
    /** 分片大小：4MB 在移动网络下重传代价小，4GB 以内够用 */
    private static final long PART_SIZE = 4L * 1024 * 1024;

    private String refresh;
    private OAuthToken token;
    private final OAuthClient.TokenSink sink;
    private final File tempDir;
    private String driveId;                       // 懒加载（/user/get）
    private final Map<String, String> idCache = new HashMap<String, String>();

    private AliyunDriveFs(String refresh, OAuthClient.TokenSink sink, File tempDir) {
        this.refresh = refresh;
        this.sink = sink;
        this.tempDir = tempDir;
    }

    public static AliyunDriveFs connect(String refreshToken, OAuthClient.TokenSink sink,
                                        File tempDir) throws IOException {
        if (OAuthClient.isBlank(refreshToken))
            throw new IOException("尚未授权：请先填入阿里云盘的 refresh_token");
        AliyunDriveFs fs = new AliyunDriveFs(refreshToken.trim(), sink, tempDir);
        fs.access();
        fs.driveId();
        fs.list("");
        return fs;
    }

    // ---------------------------------------------------------------- 认证

    private synchronized String access() throws IOException {
        if (token != null && token.hasAccess() && !token.expired(System.currentTimeMillis()))
            return token.access;
        if (OAuthClient.isBlank(refresh))
            throw new IOException("阿里云盘授权已丢失，请重新填入 refresh_token");
        OAuthToken t;
        try {
            t = OAuthClient.refreshAliyun(refresh);
        } catch (OAuthToken.OAuthError e) {
            throw new IOException("阿里云盘授权已失效（" + e.code + "）：refresh_token 是一次性的，"
                    + "请重新从浏览器获取新的 token", e);
        }
        token = t;
        if (t.hasRefresh() && !t.refresh.equals(refresh)) {
            refresh = t.refresh;                  // 轮换：必须回写，否则下次刷新失败
            if (sink != null) sink.onToken(t);
        }
        return t.access;
    }

    // ---------------------------------------------------------------- HTTP

    /** 调一个 adrive 接口，返回响应体；401 时刷新一次重试 */
    private String api(String path, String json) throws IOException {
        String resp = post(path, json, access());
        if (isAuthError(resp)) {
            synchronized (this) {
                token = null;
            }
            resp = post(path, json, access());
        }
        Object root = Json.parseOrNull(resp);
        String code = Json.str(root, "code");
        // 阿里云盘成功响应不带 code（或 code="success"），错误响应才有
        if (!code.isEmpty() && !"success".equalsIgnoreCase(code)) {
            throw new IOException(path + " 失败: " + code
                    + (Json.str(root, "message").isEmpty() ? "" : " · " + Json.str(root, "message"))
                    + hintOf(code));
        }
        return resp;
    }

    private static boolean isAuthError(String resp) {
        Object root = Json.parseOrNull(resp);
        String code = Json.str(root, "code");
        return "AccessTokenInvalid".equals(code) || "AccessTokenExpired".equals(code);
    }

    private String post(String path, String json, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setDoOutput(true);
            byte[] b = (json == null ? "{}" : json).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            try { os.write(b); } finally { try { os.close(); } catch (IOException ignore) {} }
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400 && body.trim().isEmpty())
                throw new IOException(path + " 失败: HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String hintOf(String code) {
        if (code == null) return "";
        if (code.startsWith("NotFound")) return "（文件或目录不存在，可能在别处被删/移动）";
        if (code.contains("QuotaExhausted")) return "（云盘空间不足）";
        if (code.contains("TooManyRequests")) return "（请求过于频繁，稍后再试）";
        if (code.contains("ForbiddenFile")) return "（该文件类型不允许上传）";
        if (code.contains("Name")) return "（名称不合法或重名）";
        return "";
    }

    // ---------------------------------------------------------------- 目录 id

    private synchronized String driveId() throws IOException {
        if (driveId != null) return driveId;
        String resp = api("/adrive/v2/user/get", "{}");
        String id = Json.str(Json.parseOrNull(resp), "default_drive_id");
        if (id.isEmpty()) id = Json.str(Json.parseOrNull(resp), "drive_id");
        if (id.isEmpty()) throw new IOException("取不到云盘 drive_id（授权可能不完整）");
        driveId = id;
        return driveId;
    }

    /**
     * 路径 → file_id（根为 "root"）。
     *
     * <p>走缓存：列目录时会顺手把每个子项写进缓存，所以「进目录 → 打开文件」这类连续操作
     * 只有第一次需要多打一次 list。</p>
     */
    private String resolve(String path) throws IOException {
        String p = CloudPath.normalize(path);
        if (p.isEmpty()) return "root";
        String cached = idCache.get(p);
        if (cached != null) return cached;

        String parentPath = "";
        String parentId = "root";
        for (String seg : CloudPath.segments(p)) {
            String cur = parentPath.isEmpty() ? seg : parentPath + "/" + seg;
            String hit = idCache.get(cur);
            if (hit != null) {
                parentId = hit;
                parentPath = cur;
                continue;
            }
            String found = null;
            for (Item e : listRaw(parentId)) {
                String key = parentPath.isEmpty() ? e.name : parentPath + "/" + e.name;
                idCache.put(key, e.id);
                if (e.name.equals(seg)) found = e.id;
            }
            if (found == null) throw new IOException("路径不存在: " + cur);
            parentId = found;
            parentPath = cur;
        }
        return parentId;
    }

    private void invalidate(String path) {
        String p = CloudPath.normalize(path);
        List<String> keys = new ArrayList<String>(idCache.keySet());
        for (String k : keys) {
            if (k.equals(p) || k.startsWith(p + "/")) idCache.remove(k);
        }
    }

    // ---------------------------------------------------------------- 列目录

    /**
     * 内部条目（带 file_id）。
     *
     * <p>不继承 {@link NetFs.NetEntry}：那个类是 final，而且 id 只在本实现内部有用，
     * 没必要污染公共模型。</p>
     */
    private static final class Item {
        final String id;
        final String name;
        final boolean dir;
        final long size;
        final long mtime;

        Item(String id, String name, boolean dir, long size, long mtime) {
            this.id = id;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }

        NetEntry toEntry() {
            return new NetEntry(name, dir, size, mtime);
        }
    }

    private List<Item> listRaw(String parentId) throws IOException {
        List<Item> out = new ArrayList<Item>();
        String marker = "";
        for (int guard = 0; guard < 100; guard++) {
            StringBuilder req = new StringBuilder();
            req.append("{\"drive_id\":").append(OAuthClient.quote(driveId()))
                    .append(",\"parent_file_id\":").append(OAuthClient.quote(parentId))
                    .append(",\"limit\":200,\"order_by\":\"name\",\"order_direction\":\"ASC\"");
            if (!marker.isEmpty()) req.append(",\"marker\":").append(OAuthClient.quote(marker));
            req.append('}');

            Object root = Json.parseOrNull(api("/adrive/v2/file/list", req.toString()));
            for (Object o : Json.arr(root, "items")) {
                String id = Json.str(o, "file_id");
                String name = Json.str(o, "name");
                if (id.isEmpty() || name.isEmpty()) continue;
                boolean dir = "folder".equals(Json.str(o, "type"));
                long size = Json.num(o, "size", 0);
                String t = Json.str(o, "updated_at");
                if (t.isEmpty()) t = Json.str(o, "created_at");
                out.add(new Item(id, name, dir, dir ? 0 : size, Iso8601.parse(t)));
            }
            String next = Json.str(root, "next_marker");
            if (next.isEmpty()) break;
            marker = next;
        }
        return out;
    }

    @Override
    public List<NetEntry> list(String path) throws IOException {
        String id = resolve(path);
        List<Item> items = listRaw(id);
        List<NetEntry> dirs = new ArrayList<NetEntry>(), files = new ArrayList<NetEntry>();
        String p = CloudPath.normalize(path);
        for (Item it : items) {
            String key = p.isEmpty() ? it.name : p + "/" + it.name;
            idCache.put(key, it.id);             // 顺手缓存，进下一级不用再查
            (it.dir ? dirs : files).add(it.toEntry());
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
        return true;                              // 下载地址指向 OSS，支持 Range
    }

    @Override
    public InputStream openReadFrom(String path, long offset) throws IOException {
        return get(path, offset);
    }

    private InputStream get(String path, long offset) throws IOException {
        String id = resolve(path);
        String req = "{\"drive_id\":" + OAuthClient.quote(driveId())
                + ",\"file_id\":" + OAuthClient.quote(id) + ",\"expire_sec\":14400}";
        Object root = Json.parseOrNull(api("/adrive/v2/file/get_download_url", req));
        String url = Json.str(root, "url");
        if (url.isEmpty()) throw new IOException("拿不到下载地址：" + path);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        // 预签名地址，不要带 Authorization
        c.setRequestProperty("Referer", "https://www.alipan.com/");
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
        int code = c.getResponseCode();
        if (offset > 0) {
            if (code == 200) { drain(c); throw new IOException("服务端忽略了 Range，无法续传"); }
            if (code != 206) { drain(c); throw new IOException("续传失败: HTTP " + code); }
            return c.getInputStream();
        }
        if (code != 200) { drain(c); throw new IOException("下载 " + path + " 失败: HTTP " + code); }
        return c.getInputStream();
    }

    // ---------------------------------------------------------------- 上传

    @Override
    public OutputStream openWrite(String path) throws IOException {
        File dir = tempDir;
        if (dir == null) throw new IOException("缺少上传临时目录");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IOException("无法创建上传临时目录: " + dir.getAbsolutePath());
        return new SpoolOut(File.createTempFile("aliup", ".part", dir), path);
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
                String name = CloudPath.name(path);
                String reason = CloudPath.illegalNameReason(name);
                if (reason != null) throw new IOException(reason);
                long size = tmp.length();
                String parentId = resolve(CloudPath.parent(path));

                int parts = (int) Math.max(1, (size + PART_SIZE - 1) / PART_SIZE);
                if (parts > 1000) throw new IOException("文件过大（超过 4GB），请改用 SMB/WebDAV");

                StringBuilder req = new StringBuilder();
                req.append("{\"drive_id\":").append(OAuthClient.quote(driveId()))
                        .append(",\"parent_file_id\":").append(OAuthClient.quote(parentId))
                        .append(",\"name\":").append(OAuthClient.quote(name))
                        .append(",\"type\":\"file\",\"size\":").append(size)
                        .append(",\"check_name_mode\":\"overwrite\",\"part_info_list\":[");
                for (int i = 1; i <= parts; i++) {
                    if (i > 1) req.append(',');
                    req.append("{\"part_number\":").append(i).append('}');
                }
                req.append("]}");

                Object created = Json.parseOrNull(api("/adrive/v2/file/create", req.toString()));
                String fileId = Json.str(created, "file_id");
                String uploadId = Json.str(created, "upload_id");
                if (fileId.isEmpty()) throw new IOException("创建上传任务失败（没返回 file_id）");

                if (Json.bool(created, "rapid_upload", false)) {
                    invalidate(CloudPath.parent(path));
                    return;                        // 秒传：服务端已有相同内容
                }

                List<Object> partList = Json.arr(created, "part_info_list");
                if (partList.isEmpty()) throw new IOException("服务端没有返回分片上传地址");

                FileInputStream in = new FileInputStream(tmp);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int idx = 0;
                    for (Object po : partList) {
                        int num = (int) Json.num(po, "part_number", idx + 1);
                        String url = Json.str(po, "upload_url");
                        long off = (long) (num - 1) * PART_SIZE;
                        long len = Math.min(PART_SIZE, size - off);
                        if (url.isEmpty() || len <= 0) { idx++; continue; }
                        putPart(url, in, off, len, buf);
                        idx++;
                    }
                } finally {
                    try { in.close(); } catch (IOException ignore) {}
                }

                String done = "{\"drive_id\":" + OAuthClient.quote(driveId())
                        + ",\"file_id\":" + OAuthClient.quote(fileId)
                        + ",\"upload_id\":" + OAuthClient.quote(uploadId) + "}";
                Json.parseOrNull(api("/adrive/v2/file/complete", done));
                invalidate(CloudPath.parent(path));
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
    }

    /** PUT 一个分片：必须用 offset 定位读本地文件（分片顺序由 part_number 决定） */
    private void putPart(String url, FileInputStream in, long off, long len, byte[] buf) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(300000);
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode((int) len);
            OutputStream os = c.getOutputStream();
            try {
                in.getChannel().position(off);
                long left = len;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = in.read(buf, 0, want);
                    if (n <= 0) throw new IOException("本地文件提前结束（上传中文件被改动？）");
                    os.write(buf, 0, n);
                    left -= n;
                }
            } finally {
                try { os.close(); } catch (IOException ignore) {}
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                String body = readAll(c.getErrorStream());
                throw new IOException("分片上传失败: HTTP " + code
                        + (body.isEmpty() ? "" : " — " + abbreviate(body)));
            }
            readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- 目录 / 删除 / 改名

    @Override
    public void mkdir(String path) throws IOException {
        String p = CloudPath.normalize(path);
        if (p.isEmpty()) return;
        String name = CloudPath.name(p);
        String reason = CloudPath.illegalNameReason(name);
        if (reason != null) throw new IOException(reason);
        String parentId = resolve(CloudPath.parent(p));
        String req = "{\"drive_id\":" + OAuthClient.quote(driveId())
                + ",\"parent_file_id\":" + OAuthClient.quote(parentId)
                + ",\"name\":" + OAuthClient.quote(name)
                + ",\"type\":\"folder\",\"check_name_mode\":\"refuse\"}";
        String resp = api("/adrive/v2/file/create", req);
        String id = Json.str(Json.parseOrNull(resp), "file_id");
        if (!id.isEmpty()) idCache.put(p, id);
        invalidate(CloudPath.parent(p));
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        String id = resolve(path);
        String req = "{\"drive_id\":" + OAuthClient.quote(driveId())
                + ",\"file_id\":" + OAuthClient.quote(id) + "}";
        api("/adrive/v2/file/delete", req);
        invalidate(path);                 // 目录由服务端递归删除
    }

    @Override
    public void rename(String path, String newName) throws IOException {
        String reason = CloudPath.illegalNameReason(newName);
        if (reason != null) throw new IOException(reason);
        String id = resolve(path);
        String req = "{\"drive_id\":" + OAuthClient.quote(driveId())
                + ",\"file_id\":" + OAuthClient.quote(id)
                + ",\"name\":" + OAuthClient.quote(newName) + "}";
        api("/adrive/v2/file/update", req);
        invalidate(CloudPath.parent(path));
    }

    @Override
    public void close() {
        // 无长连接
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
