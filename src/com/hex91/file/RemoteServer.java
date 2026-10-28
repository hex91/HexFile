package com.hex91.file;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 远程管理 HTTP 服务器（v1.50.0）：基于 NanoHTTPD 2.3.1（MIT，libs/nanohttpd-2.3.1.jar）。
 *
 * <p>提供局域网内浏览器对手机文件的访问：</p>
 * <ul>
 *   <li>GET  /{key}/ 或 /{key}/browse?path=… —— 目录浏览（HTML）</li>
 *   <li>GET  /{key}/raw?path=…    —— 在线查看（按扩展名给 MIME）</li>
 *   <li>GET  /{key}/download?path=… —— 下载（带 Content-Length）</li>
 *   <li>POST /{key}/upload?path=dir —— multipart 上传（字段名 file）</li>
 *   <li>GET  /{key}/action?op=del|mkdir|rename&path=…[&arg=新名]</li>
 * </ul>
 *
 * <p>安全：所有 URI 必须携带访问密钥前缀（/{key}/…），否则 403；所有文件路径经
 * canonical 化并强制限制在服务根目录内，杜绝目录穿越。</p>
 */
public final class RemoteServer extends NanoHTTPD {

    private static final String TAG = "RemoteServer";

    private final File root;
    private final String key;
    private final String base;   // "/{key}"：页面内链接统一用绝对路径，避免相对路径解析成 /KEY/KEY/…
    private final File tmpDir;   // 上传临时目录（java.io.tmpdir 在 Android 上不可靠，用应用缓存目录）
    private final Listener listener;

    /** 当前线程正在处理的上传文件原始名（由 {@link UploadTempFileManager} 捕获） */
    private static final ThreadLocal<String> UPLOAD_NAME = new ThreadLocal<>();

    /** 事件回调（供界面显示访问日志） */
    public interface Listener {
        void onLog(String line);
    }

    public RemoteServer(int port, File root, String key, File tmpDir, Listener listener) {
        super(port);
        this.root = root;
        this.key = key;
        this.base = "/" + key;
        this.tmpDir = tmpDir;
        this.listener = listener;
        setTempFileManagerFactory(new TempFileManagerFactory() {
            @Override
            public TempFileManager create() { return new UploadTempFileManager(); }
        });
    }

    /**
     * 上传临时文件管理：不经 {@code java.io.tmpdir}（Android 上未定义，NanoHTTPD 默认实现会写错位置），
     * 改用应用缓存目录；同时捕获 part 头里的原始 filename 供上传落盘命名。
     */
    private class UploadTempFileManager implements TempFileManager {
        private final List<TempFile> tracked = new ArrayList<>();

        @Override
        public TempFile createTempFile(String filename_hint) throws Exception {
            if (filename_hint != null && !filename_hint.trim().isEmpty()) {
                UPLOAD_NAME.set(filename_hint.trim());
            }
            tmpDir.mkdirs();
            TempFile f = new DefaultTempFile(tmpDir);
            tracked.add(f);
            return f;
        }

        @Override
        public void clear() {
            UPLOAD_NAME.set(null);
            for (TempFile f : tracked) {
                try { f.delete(); } catch (Exception ignore) {}
            }
        }
    }

    // ----------------------------------------------------------------
    // 请求分发
    // ----------------------------------------------------------------

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            // 密钥校验：/KEY 或 /KEY/... 之外的路径一律拒绝
            if (!uri.equals(base) && !uri.startsWith(base + "/")) {
                return plain(Response.Status.FORBIDDEN, "拒绝访问：链接缺少访问密钥");
            }
            String sub = uri.substring(key.length() + 1); // 去掉 /KEY
            if (sub.startsWith("/")) sub = sub.substring(1);

            Map<String, List<String>> params = session.getParameters();
            String path = first(params, "path");

            if (session.getMethod() == Method.POST && sub.equals("upload")) {
                return handleUpload(session, path);
            }
            if (session.getMethod() == Method.GET && sub.equals("action")) {
                return handleAction(first(params, "op"), path, first(params, "arg"));
            }

            File target = resolve(path); // null = 根目录
            if (sub.equals("download")) return serveFile(target, false);
            if (sub.equals("raw")) return serveFile(target, true);
            // 默认：浏览（若 target 是文件则直接给文件）
            if (target != null && target.isFile()) return serveFile(target, true);
            return listPage(target == null ? root : target, first(params, "msg"));
        } catch (ResponseException re) {
            return newFixedLengthResponse(re.getStatus(), "text/plain; charset=utf-8", re.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "serve", t);
            return plain(Response.Status.INTERNAL_ERROR, "服务器内部错误: " + t);
        }
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> l = params.get(name);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    // ----------------------------------------------------------------
    // 文件操作
    // ----------------------------------------------------------------

    /** 把请求参数解析为根目录内的 File；path 为空返回 null（代表根）。越界抛异常。 */
    private File resolve(String path) throws IOException, ResponseException {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(root, path);
        String canon = f.getCanonicalPath();
        String rootCanon = root.getCanonicalPath();
        if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
            throw new ResponseException(Response.Status.FORBIDDEN, "路径越界");
        }
        return f;
    }

    private Response serveFile(File f, boolean inline) throws IOException {
        if (f == null || !f.isFile()) return plain(Response.Status.NOT_FOUND, "文件不存在");
        String mime = mimeOf(f.getName());
        InputStream in = new FileInputStream(f);
        Response r = newFixedLengthResponse(Response.Status.OK, mime, in, f.length());
        String encName;
        try {
            encName = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            encName = "file";
        }
        r.addHeader("Content-Disposition",
                (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encName);
        return r;
    }

    private Response handleUpload(IHTTPSession session, String path) throws IOException, ResponseException {
        File dir = resolve(path);
        if (dir == null) dir = root;
        if (!dir.isDirectory()) return plain(Response.Status.BAD_REQUEST, "目标不是目录");
        Map<String, String> files = new java.util.HashMap<>();
        session.parseBody(files); // multipart → 临时文件（经 UploadTempFileManager 落在应用缓存目录）
        String tmp = files.get("file");
        if (tmp == null) return plain(Response.Status.BAD_REQUEST, "未找到上传字段 file");

        // 原始文件名由 UploadTempFileManager 从 part 头捕获
        String name = UPLOAD_NAME.get();
        if (name == null || name.isEmpty()) name = "upload.bin";
        name = new File(name.replace('\\', '/')).getName(); // 去路径只留文件名
        if (name.isEmpty() || name.startsWith(".")) name = "upload.bin";

        File dest = uniqueDest(new File(dir, name));
        copyFile(new File(tmp), dest);
        log("上传 " + dest.getName() + " (" + sizeText(dest.length()) + ") → " + dir.getPath());
        return redirect(pageUrl(dir, "已上传 " + escape(name)));
    }

    private Response handleAction(String op, String path, String arg) throws IOException, ResponseException {
        File f = resolve(path);
        if (f == null) return plain(Response.Status.BAD_REQUEST, "缺少 path");
        String parent = f.getParent();
        boolean ok = false;
        String msg;
        if ("del".equals(op)) {
            ok = deleteRecursive(f);
            msg = (ok ? "已删除 " : "删除失败 ") + f.getName();
        } else if ("mkdir".equals(op)) {
            arg = sanitizeName(arg, "新建目录");
            File d = uniqueDest(new File(f, arg));
            ok = d.mkdir();
            msg = (ok ? "已创建目录 " : "创建失败 ") + d.getName();
        } else if ("rename".equals(op)) {
            arg = sanitizeName(arg, "重命名");
            File to = uniqueDest(new File(parent, arg));
            ok = f.renameTo(to);
            msg = (ok ? "已重命名为 " : "重命名失败 ") + to.getName();
        } else {
            return plain(Response.Status.BAD_REQUEST, "未知操作: " + op);
        }
        log(msg);
        File back = (ok && "mkdir".equals(op)) ? null : new File(parent);
        return redirect(pageUrl(back, msg));
    }

    private static String sanitizeName(String s, String what) {
        if (s == null) s = "";
        s = s.replace("/", "").replace("\\", "").trim();
        if (s.isEmpty() || s.startsWith(".")) s = what;
        return s;
    }

    /** 目标已存在时追加序号 (1)/(2)…，避免覆盖 */
    private static File uniqueDest(File f) {
        if (!f.exists()) return f;
        String name = f.getName();
        String base, ext;
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        else { base = name; ext = ""; }
        File dir = f.getParentFile();
        for (int i = 1; i < 1000; i++) {
            File t = new File(dir, base + " (" + i + ")" + ext);
            if (!t.exists()) return t;
        }
        return f;
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) if (!deleteRecursive(c)) return false;
        }
        return f.delete();
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
            try { out.close(); } catch (IOException ignore) {}
            src.delete(); // 清掉 NanoHTTPD 临时文件
        }
    }

    // ----------------------------------------------------------------
    // 目录浏览页
    // ----------------------------------------------------------------

    private Response listPage(File dir, String msg) throws IOException {
        if (!dir.isDirectory()) return plain(Response.Status.NOT_FOUND, "目录不存在: " + dir);
        File[] children = dir.listFiles();
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        if (children != null) {
            for (File c : children) (c.isDirectory() ? dirs : files).add(c);
        }
        Collections.sort(dirs);
        Collections.sort(files);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>").append(escape(dir.getName())).append(" - HexFile 远程管理</title><style>")
          .append("body{font-family:-apple-system,sans-serif;margin:0;background:#f5f5f5;color:#222}")
          .append(".bar{background:#2d6cdf;color:#fff;padding:12px 16px;font-size:18px;font-weight:bold}")
          .append(".path{padding:8px 16px;font-size:13px;color:#666;word-break:break-all}")
          .append("table{width:100%;border-collapse:collapse;background:#fff}")
          .append("td{padding:10px 8px;border-bottom:1px solid #eee;font-size:14px}")
          .append("a{color:#2d6cdf;text-decoration:none}")
          .append(".sz{color:#888;white-space:nowrap}.tm{color:#888;white-space:nowrap}")
          .append(".ops{white-space:nowrap;text-align:right}")
          .append(".form{padding:12px 16px;background:#fff;border-bottom:1px solid #eee}")
          .append("input[type=text],input[type=file]{font-size:14px;margin:4px 0}")
          .append("button,.btn{background:#2d6cdf;color:#fff;border:0;border-radius:6px;padding:8px 14px;font-size:14px;cursor:pointer;margin-right:6px}")
          .append(".msg{background:#e8f0fe;color:#1a56c4;padding:8px 16px;font-size:13px}")
          .append("</style></head><body>");

        String relPath = relOf(dir);
        sb.append("<div class='bar'>📂 HexFile 远程管理</div>")
          .append("<div class='path'>").append(escape(relPath)).append("</div>");
        if (msg != null && !msg.isEmpty()) sb.append("<div class='msg'>").append(escape(msg)).append("</div>");

        // 上传 + 新建目录表单
        String encDir = enc(relOf(dir));
        sb.append("<div class='form'>")
          .append("<form method='post' action='").append(base).append("/upload?path=").append(encDir)
          .append("' enctype='multipart/form-data' style='display:inline-block'>")
          .append("<input type='file' name='file' required><button type='submit'>上传到此处</button></form>")
          .append("<button class='btn' onclick='mkdir()'>新建目录</button>")
          .append("<script>")
          .append("function mkdir(){var n=prompt('目录名:');if(n)location.href='").append(base)
          .append("/action?op=mkdir&path=").append(encDir).append("&arg='+encodeURIComponent(n);}")
          .append("function del(p){if(confirm('确定删除 '+p+' ?'))location.href='").append(base)
          .append("/action?op=del&path='+p;}")
          .append("function ren(p){var n=prompt('新名称:',decodeURIComponent(p.split('/').pop()));")
          .append("if(n)location.href='").append(base).append("/action?op=rename&path='+p+'&arg='+encodeURIComponent(n);}")
          .append("</script></div>");

        sb.append("<table>");
        if (!dir.equals(root)) {
            sb.append("<tr><td colspan='4'><a href='").append(base).append("/browse?path=")
              .append(enc(relOf(dir.getParentFile()))).append("'>⬆️ ..（上级目录）</a></td></tr>");
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (File d : dirs) {
            row(sb, d, "📁", true, fmt, encDir);
        }
        for (File f : files) {
            row(sb, f, "📄", false, fmt, encDir);
        }
        if (dirs.isEmpty() && files.isEmpty()) {
            sb.append("<tr><td colspan='4' style='color:#999;text-align:center;padding:24px'>空目录</td></tr>");
        }
        sb.append("</table>");
        sb.append("<div style='padding:14px;color:#aaa;font-size:12px;text-align:center'>HexFile 远程管理 · 仅限局域网 · 请勿泄露链接</div>");
        sb.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", sb.toString());
    }

    private void row(StringBuilder sb, File f, String icon, boolean isDir,
                     SimpleDateFormat fmt, String encDir) {
        String rel = relOf(f);
        String enc = enc(rel);
        String href = isDir ? (base + "/browse?path=" + enc) : (base + "/raw?path=" + enc);
        sb.append("<tr><td><a href='").append(href).append("'>").append(icon).append(' ')
          .append(escape(f.getName())).append("</a></td>");
        sb.append("<td class='sz'>").append(isDir ? "目录" : sizeText(f.length())).append("</td>");
        sb.append("<td class='tm'>").append(fmt.format(new Date(f.lastModified()))).append("</td>");
        sb.append("<td class='ops'>");
        if (!isDir) {
            sb.append("<a href='").append(base).append("/download?path=").append(enc).append("'>下载</a> ");
        }
        sb.append("<a href='javascript:void(0)' onclick=\"del('").append(enc).append("')\">删除</a> ")
          .append("<a href='javascript:void(0)' onclick=\"ren('").append(enc).append("')\">改名</a>");
        sb.append("</td></tr>");
    }

    // ----------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------

    private Response redirect(String url) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/html; charset=utf-8",
                "<html><body><a href='" + url + "'>返回</a></body></html>");
        r.addHeader("Location", url);
        return r;
    }

    private Response plain(Response.Status status, String text) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", text);
    }

    private String pageUrl(File dir, String msg) {
        return base + "/browse?path=" + enc(relOf(dir == null ? root : dir))
                + (msg != null && !msg.isEmpty() ? "&msg=" + enc(msg) : "");
    }

    private String relOf(File f) {
        try {
            String rc = root.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (fc.equals(rc)) return "/";
            return fc.substring(rc.length()).replace(File.separatorChar, '/');
        } catch (IOException e) {
            return "/";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static String sizeText(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        for (String unit : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024;
            if (v < 1024) return String.format(java.util.Locale.US, "%.1f %s", v, unit);
        }
        return String.format(java.util.Locale.US, "%.1f PB", v / 1024);
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "text/plain; charset=utf-8";
        if (n.endsWith(".xml") || n.endsWith(".smali")) return "text/plain; charset=utf-8";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private void log(String line) {
        Log.i(TAG, line);
        if (listener != null) listener.onLog(line);
    }
}
