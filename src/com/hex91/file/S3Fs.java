package com.hex91.file;

import com.hex91.file.core.*;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1.88.0 S3 兼容对象存储（阿里云 OSS / 腾讯云 COS / MinIO / AWS S3 / B2 …）。
 *
 * <p>为什么选 S3 协议做「云盘直连」，而不是 OneDrive / 阿里云盘这类官方 API：
 * 那两家都要先注册开发者应用、过审核、再走 OAuth 授权跳转——在「无账号、纯本地」的工具里，
 * 等于把能不能用交给别人的审核流程。S3 兼容协议只要控制台里的 AccessKey/SecretKey，
 * 填进去就能连，而且同一套代码覆盖了国内几家主流对象存储。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>路径风格</b>（{@code endpoint/bucket/key}）而不是虚拟主机风格：OSS/COS/MinIO
 *       都支持，也省了「bucket 名带点导致 TLS 证书不匹配」这类坑。</li>
 *   <li><b>目录是伪装</b>：S3 只有 key，列表靠 {@code delimiter=/} 的 CommonPrefixes；
 *       建目录 = PUT 一个以 {@code /} 结尾的空对象。</li>
 *   <li><b>上传先落临时文件</b>：SigV4 需要 payload 的 SHA-256 与 Content-Length，
 *       流式上传要么走 aws-chunked（各家兼容性不一），要么就得先知道长度。
 *       手机写临时文件远比「上传到一半发现签名对不上」便宜。</li>
 *   <li>所有请求头里的 {@code x-amz-*} 都参与签名（AWS 硬性要求）。</li>
 * </ul>
 */
public final class S3Fs implements NetFs {

    private final String endpoint;    // https://host[:port]（无末尾斜杠）
    private final String hostHeader;  // host[:port]，参与签名
    private final String bucket;
    private final String region;
    private final String ak, sk;
    private final File tempDir;       // 上传落盘目录

    private S3Fs(String endpoint, String hostHeader, String bucket, String region,
                 String ak, String sk, File tempDir) {
        this.endpoint = endpoint;
        this.hostHeader = hostHeader;
        this.bucket = bucket;
        this.region = region;
        this.ak = ak;
        this.sk = sk;
        this.tempDir = tempDir;
    }

    /**
     * 连接并验证（列一次根目录，把「端点/密钥/桶名错了」在上层直接暴露出来）。
     *
     * @param endpointUrl 形如 {@code https://oss-cn-hangzhou.aliyuncs.com}；
     *                    不带 http(s):// 时按 https 处理
     * @param bucket      存储桶名
     * @param region      区域，如 {@code cn-hangzhou} / {@code us-east-1}
     * @param tempDir     上传临时目录（调用方传应用 cache）
     */
    public static S3Fs connect(String endpointUrl, String bucket, String region,
                               String ak, String sk, File tempDir) throws IOException {
        String ep = endpointUrl == null ? "" : endpointUrl.trim();
        if (ep.isEmpty()) throw new IOException("对象存储地址为空（例：https://oss-cn-hangzhou.aliyuncs.com）");
        if (!ep.startsWith("http://") && !ep.startsWith("https://")) ep = "https://" + ep;
        while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        if (bucket == null || bucket.trim().isEmpty()) throw new IOException("存储桶（bucket）不能为空");
        if (ak == null || ak.trim().isEmpty()) throw new IOException("AccessKey 不能为空");
        if (sk == null || sk.trim().isEmpty()) throw new IOException("SecretKey 不能为空");
        String rg = (region == null || region.trim().isEmpty()) ? "us-east-1" : region.trim();

        String host;
        try {
            URL u = new URL(ep);
            host = u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (Exception e) {
            throw new IOException("对象存储地址无法解析: " + ep, e);
        }

        S3Fs fs = new S3Fs(ep, host, bucket.trim(), rg, ak.trim(), sk.trim(), tempDir);
        fs.list("");                       // 连通性 + 凭据校验
        return fs;
    }

    // ----------------------------------------------------------------
    // 请求构造
    // ----------------------------------------------------------------

    /** 远程路径 → 对象 key（去掉首尾斜杠）；空路径 = 桶根 */
    private static String keyOf(String path) {
        String k = path == null ? "" : path.trim();
        while (k.startsWith("/")) k = k.substring(1);
        while (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        return k;
    }

    private HttpURLConnection conn(String method, String key, String canonicalQuery,
                                   String payloadHash, Map<String, String> extraSigned) throws IOException {
        String path = "/" + bucket + (key.isEmpty() ? "" : "/" + S3Signer.encodePath(key));
        String url = endpoint + path + (canonicalQuery == null || canonicalQuery.isEmpty()
                ? "" : "?" + canonicalQuery);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);

        long now = System.currentTimeMillis();
        Map<String, String> signed = new TreeMap<String, String>();
        signed.put("host", hostHeader);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", S3Signer.amzDate(now));
        if (extraSigned != null) signed.putAll(extraSigned);
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if ("host".equals(e.getKey())) continue;      // Host 由 HttpURLConnection 自己写
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.setRequestProperty("Authorization", S3Signer.authorization(
                ak, sk, region, "s3", method, path, canonicalQuery == null ? "" : canonicalQuery,
                signed, payloadHash, now));
        return c;
    }

    private static String hintOf(int code) {
        if (code == 301) return "（桶不在该区域：检查 region 是否填对）";
        if (code == 403) return "（签名/权限失败：检查 AccessKey、SecretKey、桶权限，以及手机时间是否准确）";
        if (code == 404) return "（桶或对象不存在：检查 bucket 名与 endpoint）";
        return "";
    }

    /**
     * 错误信息：S3 把原因放在 XML 的 {@code <Message>} 里，尽量提取出来。
     *
     * <p>读 body 与断开连接必须由本方法负责。v1.95.3 及以前是调用点先
     * {@code consume(c)}（读干 errorStream 再 disconnect），然后才进来取
     * {@code getErrorStream()} —— 这时已经是 null，{@code <Message>} 永远取不到，
     * 用户只看到「HTTP 403」，看不到 SignatureDoesNotMatch / NoSuchBucket 这类真正的原因。
     * disconnect() 之后再 getResponseCode() 也不可靠，所以一并前置。</p>
     */
    private static IOException httpErr(String op, HttpURLConnection c) {
        int code = -1;
        String body = "";
        try {
            code = c.getResponseCode();
        } catch (Exception ignore) {}
        try {
            InputStream es = c.getErrorStream();
            if (es != null) {
                byte[] b = new byte[4096];
                int n = es.read(b);
                if (n > 0) body = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                es.close();
            }
        } catch (Exception ignore) {
        } finally {
            try { c.disconnect(); } catch (Exception ignore) {}
        }
        String msg = pick(body, "Message");
        if (msg.isEmpty()) msg = pick(body, "Code");
        return new IOException(op + " 失败: HTTP " + code + hintOf(code)
                + (msg.isEmpty() ? "" : " · " + msg));
    }

    /** 从 XML 里粗暴取一个标签的值（只用于拼错误提示，不做完整解析） */
    private static String pick(String xml, String tag) {
        if (xml == null) return "";
        int s = xml.indexOf("<" + tag + ">");
        int e = xml.indexOf("</" + tag + ">");
        if (s < 0 || e < s) return "";
        return xml.substring(s + tag.length() + 2, e).trim();
    }

    private static void consume(HttpURLConnection c) {
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

    // ----------------------------------------------------------------
    // 列目录
    // ----------------------------------------------------------------

    @Override
    public List<NetEntry> list(String path) throws IOException {
        String prefix = keyOf(path);
        if (!prefix.isEmpty()) prefix += "/";

        List<NetEntry> dirs = new ArrayList<NetEntry>(), files = new ArrayList<NetEntry>();
        String token = null;
        for (int guard = 0; guard < 100; guard++) {          // 100 页 × 1000 条封顶，防死循环
            // 参数必须字典序：continuation-token < delimiter < list-type < max-keys < prefix
            StringBuilder q = new StringBuilder();
            if (token != null) q.append("continuation-token=").append(S3Signer.uriEncode(token, true));
            if (q.length() > 0) q.append('&');
            q.append("delimiter=%2F&list-type=2&max-keys=1000");
            if (!prefix.isEmpty()) q.append("&prefix=").append(S3Signer.uriEncode(prefix, true));

            HttpURLConnection c = conn("GET", keyOf(path), q.toString(), S3Signer.EMPTY_SHA256, null);
            int code = c.getResponseCode();
            if (code != 200) { throw httpErr("列目录 " + path, c); }

            String next = null;
            boolean truncated = false;
            XmlPullParser p = Xml.newPullParser();
            InputStream in = c.getInputStream();
            try {
                p.setInput(in, null);
                String curKey = null, curSize = null, curLm = null, curPrefix = null;
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String n = p.getName();
                        if ("Key".equals(n)) curKey = "";
                        else if ("Size".equals(n)) curSize = "";
                        else if ("LastModified".equals(n)) curLm = "";
                        else if ("Prefix".equals(n)) curPrefix = "";
                        else if ("NextContinuationToken".equals(n)) next = "";
                        else if ("IsTruncated".equals(n)) {
                            String t = p.nextText();
                            truncated = "true".equalsIgnoreCase(t == null ? "" : t.trim());
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        String t = p.getText();
                        if (t == null) continue;
                        if (curKey != null) curKey += t;
                        else if (curSize != null) curSize += t;
                        else if (curLm != null) curLm += t;
                        else if (curPrefix != null) curPrefix += t;
                        else if (next != null) next += t;
                    } else if (ev == XmlPullParser.END_TAG) {
                        String n = p.getName();
                        if ("Key".equals(n) && curKey != null) {
                            // Contents 是逐条结束于 </Contents>，这里先攒着
                        } else if ("Contents".equals(n)) {
                            String name = curKey == null ? "" : curKey.substring(
                                    Math.min(prefix.length(), curKey.length()));
                            if (!name.isEmpty() && !name.endsWith("/") && name.indexOf('/') < 0) {
                                long sz = 0, mt = 0;
                                try { if (curSize != null) sz = Long.parseLong(curSize.trim()); } catch (Exception ignore) {}
                                if (curLm != null) mt = Iso8601.parse(curLm.trim());
                                files.add(new NetEntry(name, false, sz, mt));
                            }
                            curKey = null; curSize = null; curLm = null;
                        } else if ("CommonPrefixes".equals(n)) {
                            String full = curPrefix == null ? "" : curPrefix.trim();
                            if (full.endsWith("/")) full = full.substring(0, full.length() - 1);
                            String name = full.substring(Math.min(prefix.length(), full.length()));
                            if (!name.isEmpty() && name.indexOf('/') < 0)
                                dirs.add(new NetEntry(name, true, 0, 0));
                            curPrefix = null;
                        } else if ("NextContinuationToken".equals(n) && next != null) {
                            next = next.trim();
                        }
                    }
                    ev = p.next();
                }
            } catch (org.xmlpull.v1.XmlPullParserException xe) {
                throw new IOException("响应解析失败（该端点不是 S3 兼容服务？）: " + xe.getMessage(), xe);
            } finally {
                try { in.close(); } catch (IOException ignore) {}
                c.disconnect();
            }
            if (!truncated || next == null || next.isEmpty()) break;
            token = next;
        }
        sortByName(dirs);
        sortByName(files);
        List<NetEntry> out = new ArrayList<NetEntry>(dirs.size() + files.size());
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    private static void sortByName(List<NetEntry> l) {
        Collections.sort(l, new java.util.Comparator<NetEntry>() {
            public int compare(NetEntry a, NetEntry b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    // ----------------------------------------------------------------
    // 读写
    // ----------------------------------------------------------------

    @Override
    public InputStream openRead(String path) throws IOException {
        return get(path, 0);
    }

    /** v1.88.0 续传：S3 原生支持 Range（回 206）。 */
    @Override
    public InputStream openReadFrom(String path, long offset) throws IOException {
        return get(path, offset);
    }

    @Override
    public boolean supportsResume() {
        return true;
    }

    private InputStream get(String path, long offset) throws IOException {
        HttpURLConnection c = conn("GET", keyOf(path), "", S3Signer.EMPTY_SHA256, null);
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
        int code = c.getResponseCode();
        if (offset > 0) {
            if (code == 200) { consume(c); throw new IOException("服务端忽略了 Range，无法续传"); }
            if (code != 206) { throw httpErr("续传 " + path, c); }
            return c.getInputStream();
        }
        if (code != 200) { throw httpErr("读取 " + path, c); }
        return c.getInputStream();
    }

    /**
     * 上传：先写临时文件，close 时按实际长度与 SHA-256 一次性 PUT。
     *
     * <p>中途失败只会留下一个临时文件（调用方 close 时删），远端对象保持原样，
     * 不会出现「传了一半的坏对象」。</p>
     */
    @Override
    public OutputStream openWrite(String path) throws IOException {
        File dir = tempDir;
        if (dir == null) throw new IOException("缺少上传临时目录");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
            throw new IOException("无法创建上传临时目录: " + dir.getAbsolutePath());
        final File tmp = File.createTempFile("s3up", ".part", dir);
        return new SpoolOut(tmp, path);
    }

    /** 上传缓冲流：close 时算哈希 + 上传 + 删临时文件 */
    private final class SpoolOut extends OutputStream {
        private final File tmp;
        private final String path;
        private final FileOutputStream out;
        private boolean closed;

        SpoolOut(File tmp, String path) throws IOException {
            this.tmp = tmp;
            this.path = path;
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
                String hash = fileSha256(tmp);
                long len = tmp.length();
                if (len > Integer.MAX_VALUE) throw new IOException("单文件超过 2GB，当前实现不支持");
                HttpURLConnection c = conn("PUT", keyOf(path), "", hash, null);
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
                if (code < 200 || code >= 300) { throw httpErr("上传 " + path, c); }
                consume(c);
            } finally {
                if (!tmp.delete()) tmp.deleteOnExit();
            }
        }
    }

    private static String fileSha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            return S3Signer.hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    // ----------------------------------------------------------------
    // 目录 / 删除 / 重命名
    // ----------------------------------------------------------------

    /** S3 没有目录：建目录 = PUT 一个 {path}/ 的空对象 */
    @Override
    public void mkdir(String path) throws IOException {
        String key = keyOf(path);
        if (key.isEmpty()) return;
        HttpURLConnection c = conn("PUT", key + "/", "", S3Signer.EMPTY_SHA256, null);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(0);
        c.getOutputStream().close();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { throw httpErr("创建目录 " + path, c); }
        consume(c);
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        if (dir) {
            for (NetEntry c : list(path)) delete(NetFs.join(path, c.name), c.dir);
            String key = keyOf(path);
            if (!key.isEmpty()) deleteKey(key + "/");    // 目录占位对象，不存在会 404，忽略
        } else {
            deleteKey(keyOf(path));
        }
    }

    private void deleteKey(String key) throws IOException {
        HttpURLConnection c = conn("DELETE", key, "", S3Signer.EMPTY_SHA256, null);
        int code = c.getResponseCode();
        if (code == 404) { consume(c); return; }        // 占位对象可能本来就不存在
        if (code != 204 && code != 200) { throw httpErr("删除 " + key, c); }
        consume(c);
    }

    /** S3 无 rename：CopyObject + 删源 */
    @Override
    public void rename(String path, String newName) throws IOException {
        String src = keyOf(path);
        String dst = keyOf(NetFs.join(NetFs.parentOf(keyOf(path)), newName));
        Map<String, String> extra = new TreeMap<String, String>();
        extra.put("x-amz-copy-source", "/" + bucket + "/" + S3Signer.encodePath(src));
        HttpURLConnection c = conn("PUT", dst, "", S3Signer.EMPTY_SHA256, extra);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(0);
        c.getOutputStream().close();
        int code = c.getResponseCode();
        // CopyObject 可能回 200 但在 body 里报错（Error 节点）
        String body = "";
        try {
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                byte[] b = new byte[2048];
                int n = in.read(b);
                if (n > 0) body = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                in.close();
            }
        } catch (Exception ignore) {}
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("重命名失败: HTTP " + code + hintOf(code));
        if (body.contains("<Error>"))
            throw new IOException("重命名失败: " + pick(body, "Message"));
        deleteKey(src);
    }

    @Override
    public void close() {
        // 无长连接需要显式关闭（HttpURLConnection 由连接池管理）
    }
}
