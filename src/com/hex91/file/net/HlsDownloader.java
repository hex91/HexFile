package com.hex91.file.net;

import com.hex91.file.fs.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HLS 下载器 —— v1.92.0：把一条 m3u8 拉成单个本地文件（TS 或 MP4）。
 *
 * <p>整条链路是「取清单 → 拉分片（必要时解密）→ 按序拼接 → 转封装」。
 * 每一步都有能让结果变成「文件有了但播不了」的细节：</p>
 *
 * <h3>为什么并发是 3 而不是 8</h3>
 * <p>分片本身很小（2~6 秒一段），瓶颈在延迟不在带宽。3 路已经能把「一次请求一个 RTT」
 * 的空等填满；再往上加，多数 CDN 会开始限速或返回 403，反而更慢，手机发热也明显。</p>
 *
 * <h3>为什么先写 {@code .seg} 再 rename</h3>
 * <p>下载可取消、可断网。直接写目标文件的话，中途退出会留下半截分片，
 * 下次重入把它当「已完成」读进来 —— 拼出来的视频在某一秒花屏或直接卡死，
 * 而且极难定位到是哪个分片。写临时名 + 完成后 rename，磁盘上永远只有完整分片。</p>
 *
 * <h3>为什么要校验解密结果</h3>
 * <p>见 {@link HlsCrypto#decrypt}：密钥错时 AES 不会报错，只会吐垃圾。
 * 拿到垃圾继续拼，用户看到的是「下载完成，但文件打不开」，比直接失败难查得多。</p>
 */
public final class HlsDownloader {

    /** 进度回调，全部在**工作线程**触发，UI 侧自行 post */
    public interface Cb {
        /** @param bytes 已下载字节；@param speedBps 平均速度，未知为 0 */
        void onProgress(int done, int total, long bytes, long speedBps);
    }

    /** 下载结果 */
    public static final class Result {
        /** 最终文件（remuxed 为 true 时是 mp4，否则是合并出的 ts/mp4） */
        public final File file;
        /** 是否成功转成 MP4 */
        public final boolean remuxed;
        /** 给用户看的补充说明（例如「转 MP4 失败，已保留 TS，可直接播放」） */
        public final String note;

        Result(File file, boolean remuxed, String note) {
            this.file = file;
            this.remuxed = remuxed;
            this.note = note;
        }
    }

    /** 取清单结果：可能是 master（要用户选档），也可能是可直接下载的 media */
    public static final class Fetched {
        public final M3u8Playlist master;
        public final HlsPlaylist media;
        /** 实际取到的 m3u8 地址（跟随重定向后的），相对路径以它为基准 */
        public final String url;

        Fetched(M3u8Playlist master, HlsPlaylist media, String url) {
            this.master = master;
            this.media = media;
            this.url = url;
        }

        /** 来源是 master（可能同时已经钻进了某一档，此时 {@link #media} 非空） */
        public boolean isMaster() {
            return master != null && master.kind == M3u8Playlist.KIND_MASTER;
        }

        /** 已经有可下载的分片清单 */
        public boolean ready() {
            return media != null && !media.isEmpty();
        }
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int BUF = 64 * 1024;
    /** 单文件上限：m3u8 一般几 KB，密钥 16 字节，给 8MB 足够且能挡住「下回来一个视频」 */
    private static final int MAX_TEXT = 8 * 1024 * 1024;
    private static final int THREADS = 3;
    private static final int RETRY = 2;

    private HlsDownloader() {}

    // ---------------------------------------------------------------- 取清单

    /**
     * 取并解析 m3u8。
     *
     * @param url      m3u8 地址（http/https）
     * @param referer  请求 Referer，可空；多数 CDN 不校验，少数校验（B 站/部分自建）
     * @param deep     是否自动钻进 master 的最高档（true 时 Fetched.media 一定是 media）
     */
    public static Fetched fetch(String url, String referer, boolean deep, Escape esc)
            throws IOException {
        if (url == null || url.trim().isEmpty()) throw new IOException("地址为空");
        String u = localize(url.trim());
        String text = readText(u, referer, 0, -1, esc);
        M3u8Playlist m = M3u8Playlist.parseOrEmpty(text, u);
        if (m.kind == M3u8Playlist.KIND_MASTER) {
            M3u8Playlist.Variant best = m.best();
            if (best == null) {
                // 是 master 但一个变体都没解析出来：把原文再按 media 试一次
                HlsPlaylist hp = HlsPlaylist.parseOrEmpty(text, u);
                if (hp.isEmpty()) throw new IOException("清单里没有可用的分片");
                return new Fetched(null, hp, u);
            }
            if (deep) {
                String sub = best.url;
                String t2 = readText(sub, referer == null ? origin(u) : referer, 0, -1, esc);
                HlsPlaylist hp = HlsPlaylist.parseOrEmpty(t2, sub);
                if (hp.isEmpty()) throw new IOException("最高画质的子清单里没有分片");
                return new Fetched(m, hp, sub);
            }
            return new Fetched(m, null, u);
        }
        HlsPlaylist hp = HlsPlaylist.parseOrEmpty(text, u);
        if (hp.isEmpty()) {
            String head = text.length() > 120 ? text.substring(0, 120) : text;
            throw new IOException("这不是有效的 m3u8（首行应为 #EXTM3U）：" + head.replace('\n', ' '));
        }
        return new Fetched(null, hp, u);
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载并合并。
     *
     * @param playlist 已解析的 media 清单
     * @param m3u8Url  清单地址（用于推导 Referer 与默认文件名）
     * @param outDir   输出目录
     * @param baseName 文件名（不含扩展名），空则自动推导
     * @param toMp4    true 时尝试转封装成 MP4（失败保留 TS）
     * @param esc      取消信号，可空
     */
    public static Result download(HlsPlaylist playlist, String m3u8Url, File outDir,
                                  String baseName, boolean toMp4, Cb cb, Escape esc)
            throws IOException {
        if (playlist == null || playlist.isEmpty()) throw new IOException("清单里没有分片");
        String block = playlist.blockReason();
        if (block != null) throw new IOException(block);
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("无法创建目录：" + outDir);

        String base = safeName(baseName);
        if (base.isEmpty()) base = safeName(deriveName(m3u8Url));
        if (base.isEmpty()) base = "video_" + System.currentTimeMillis();

        String referer = origin(m3u8Url);
        List<HlsPlaylist.Segment> segs = playlist.segments();
        List<Integer> todo = new ArrayList<Integer>();
        for (int i = 0; i < segs.size(); i++) {
            if (!segs.get(i).gap) todo.add(i);
        }
        if (todo.isEmpty()) throw new IOException("所有分片都被标记为缺失（EXT-X-GAP）");

        File parts = new File(outDir, "." + base + ".parts");

        // 断点续传必须绑定「这一次要下的清单」。
        // 只按 000001.seg 这种序号复用很危险：上一次下到一半取消留下 .parts，
        // 接着下另一个同名 base 的片子（index / playlist / video 这类名字太常见），
        // 或同一片源的清单更新了、直播窗口前移了 —— 序号一一对得上，
        // 于是把上一次的分片拼进来，得到「能播但内容是两段混在一起」的文件。
        String stampWant = fingerprint(m3u8Url, segs);
        File stamp = new File(parts, ".manifest");
        String stampHave = parts.isDirectory() ? readTextFile(stamp) : null;
        if (!stampWant.equals(stampHave)) {
            deleteDir(parts);                       // 清单对不上，旧分片全部作废
            if (!parts.exists() && !parts.mkdirs()) throw new IOException("无法创建临时目录：" + parts);
            writeTextFile(stamp, stampWant);
        }

        // 估算总量：各片 Content-Length 之和拿不到，先用 Range 声明的长度 + 「已下载」计数
        long declared = 0;
        for (int i : todo) {
            long d = segs.get(i).declaredBytes();
            if (d > 0) declared += d;
        }

        // ---- 密钥预取：同一把 key 只下一次（多把 key 时按 URI 缓存）
        Map<String, byte[]> keys = new HashMap<String, byte[]>();
        for (int i : todo) {
            HlsKey k = segs.get(i).key;
            if (k == null || !k.isAes128()) continue;
            if (k.uri == null) throw new IOException("分片声明了 AES-128 但没有给密钥地址");
            if (keys.containsKey(k.uri)) continue;
            byte[] kb = readBytes(k.uri, referer, 0, -1, MAX_TEXT, esc);
            if (kb.length != 16) {
                throw new IOException("密钥文件长度应为 16 字节，实际 " + kb.length
                        + "（可能取到了错误页面）");
            }
            keys.put(k.uri, kb);
        }

        final AtomicBoolean cancel = new AtomicBoolean(false);
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicLong bytes = new AtomicLong(0);
        final AtomicInteger cursor = new AtomicInteger(0);
        final long startedAt = System.currentTimeMillis();
        final int total = todo.size();

        // 断点：上次已下好的分片（.seg）直接跳过
        for (int i : todo) {
            File f = segFile(parts, i);
            if (f.isFile() && f.length() > 0) {
                done.incrementAndGet();
                bytes.addAndGet(f.length());
            }
        }

        if (done.get() < total) {
            final List<HlsPlaylist.Segment> segList = segs;
            final Map<String, byte[]> keyMap = keys;
            final File partsDir = parts;
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            // 闸门按「全部索引」计数：worker 遍历 0..total-1，每个索引恰好 countDown 一次
            // （已续传的走上面的 isFile 分支，其余走 finally）。v1.95.3 及以前是
            // total - resumed —— 但已续传的分片在遍历时又扣了一次，于是总扣数仍是 total，
            // 闸门提前归零，主线程 shutdownNow() 打断还在跑的分片：合并时
            // FileNotFoundException，或者「看起来成功」但内容缺片。
            final CountDownLatch latch = new CountDownLatch(total);
            final IOException[] fatal = new IOException[1];

            for (int n = 0; n < THREADS; n++) {
                pool.execute(new Runnable() {
                    public void run() {
                        while (true) {
                            int idx = cursor.getAndIncrement();
                            if (idx >= total) return;
                            if (cancel.get() || fatal[0] != null) return;
                            int si = todo.get(idx);
                            HlsPlaylist.Segment s = segList.get(si);
                            File out = segFile(partsDir, si);
                            if (out.isFile() && out.length() > 0) {
                                latch.countDown();
                                continue;
                            }
                            try {
                                byte[] key = s.key != null && s.key.isAes128()
                                        ? keyMap.get(s.key.uri) : null;
                                long n2 = fetchSegment(s, out, referer, key, cancel);
                                bytes.addAndGet(n2);
                                int d = done.incrementAndGet();
                                if (cb != null) {
                                    long ms = Math.max(1, System.currentTimeMillis() - startedAt);
                                    cb.onProgress(d, total, bytes.get(), bytes.get() * 1000L / ms);
                                }
                            } catch (IOException e) {
                                if (!cancel.get() && fatal[0] == null) fatal[0] = e;
                                return;
                            } finally {
                                latch.countDown();
                            }
                        }
                    }
                });
            }
            pool.shutdown();
            try {
                // 每 300ms 检查一次取消：用户点「取消」不该等到下一片下完才响应。
                // 同时必须看 fatal：worker 失败后其余 worker 会在取到索引时直接 return
                // （不扣闸门），闸门永远到不了 0 —— 只盯取消就成了无限空转，
                // 真实错误也永远到不了用户面前。
                while (!latch.await(300, TimeUnit.MILLISECONDS)) {
                    if (fatal[0] != null) break;
                    if (cancel.get() || esc != null && esc.cancelled()) {
                        cancel.set(true);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                cancel.set(true);
                Thread.currentThread().interrupt();
            }
            pool.shutdownNow();
            if (cancel.get() || esc != null && esc.cancelled()) {
                throw new IOException("已取消（已下载的分片会保留，下次可继续）");
            }
            if (fatal[0] != null) throw fatal[0];
        }

        // ---- 按序拼接
        File merged = new File(outDir, base + (playlist.likelyFmp4() ? ".mp4" : ".ts"));
        File tmp = new File(outDir, "." + base + ".merging");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            // fMP4 必须先写初始化段（moov/ftyp），否则后面全是无索引的 mdat
            if (playlist.initUrl != null) {
                byte[] init = readBytes(playlist.initUrl, referer,
                        playlist.initRangeOffset, playlist.initRangeLength, MAX_TEXT * 16, esc);
                os.write(init);
            }
            byte[] buf = new byte[BUF];
            for (int i : todo) {
                if (esc != null && esc.cancelled()) throw new IOException("已取消");
                File f = segFile(parts, i);
                InputStream in = new java.io.FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (IOException ignore) {}
                }
            }
            os.flush();
        } finally {
            if (os != null) try { os.close(); } catch (IOException ignore) {}
        }
        if (merged.exists() && !merged.delete()) {
            // 目标被占用（同名文件正在被别的应用播放），换一个名字而不是失败
            merged = new File(outDir, base + "_" + System.currentTimeMillis()
                    + (playlist.likelyFmp4() ? ".mp4" : ".ts"));
        }
        if (!tmp.renameTo(merged)) {
            throw new IOException("合并文件重命名失败：" + merged.getAbsolutePath());
        }
        deleteDir(parts);

        if (!toMp4) {
            return new Result(merged, false, merged.length() < 1024 * 1024
                    ? "文件偏小（" + (merged.length() / 1024) + " KB），源可能本身不完整" : null);
        }
        if (playlist.likelyFmp4()) {
            // fMP4 分片拼出来已经是可播的 mp4（init + media segments）
            return new Result(merged, true, null);
        }
        File mp4 = new File(outDir, base + ".mp4");
        if (mp4.exists() && !mp4.delete()) {
            mp4 = new File(outDir, base + "_" + System.currentTimeMillis() + ".mp4");
        }
        try {
            TsRemuxer.remuxToMp4(merged, mp4, null);
            long size = mp4.length();
            if (size <= 0) throw new IOException("输出为空");
            // 转成功就删掉中间 TS：手机空间紧张，且两份内容完全一样
            if (!merged.delete()) {
                // 删不掉也不影响使用
            }
            return new Result(mp4, true, null);
        } catch (Throwable t) {
            String why = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            // 转封装失败不是灾难：TS 本身就能播（内置播放器/电脑都能放），保留它
            return new Result(merged, false,
                    "已合并为 TS，但转 MP4 失败：" + why + "\n（TS 可直接播放，也可能需要在电脑上用工具转）");
        }
    }

    // ---------------------------------------------------------------- 网络

    /** 单片下载（含解密与重试），返回写入的字节数 */
    private static long fetchSegment(HlsPlaylist.Segment s, File out, String referer,
                                     byte[] key, AtomicBoolean cancel) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            if (cancel.get()) throw new IOException("已取消");
            if (attempt > 0) {
                try {
                    Thread.sleep(400L * attempt * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已取消");
                }
            }
            try {
                byte[] data = readBytes(s.url, referer, s.rangeOffset, s.rangeLength,
                        Integer.MAX_VALUE, null);
                if (key != null) {
                    try {
                        data = HlsCrypto.decrypt(data, key, s.key.ivFor(s.sequence));
                    } catch (java.security.GeneralSecurityException e) {
                        throw new DecryptError("分片 " + (s.sequence + 1) + " 解密失败：" + e.getMessage());
                    }
                }
                if (data.length == 0) throw new IOException("分片内容为空");
                if (s.hasRange() && data.length != s.rangeLength) {
                    // 服务器忽略 Range 返回了整文件：这里必须报错，否则拼出来是「整片重复」
                    throw new IOException("服务器未按 Range 返回（期望 " + s.rangeLength
                            + " 字节，实际 " + data.length + "）");
                }
                File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
                FileOutputStream fo = new FileOutputStream(tmp);
                try {
                    fo.write(data);
                    fo.flush();
                } finally {
                    try { fo.close(); } catch (IOException ignore) {}
                }
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new IOException("分片写入失败：" + out.getName());
                }
                return data.length;
            } catch (IOException e) {
                // 密钥/IV 与清单不匹配不是瞬态故障，重试三次只会让用户多等 2 秒半
                if (e instanceof DecryptError) throw e;
                last = e;
            }
        }
        throw new IOException("分片下载失败：" + last.getMessage());
    }

    /** 整读一个 URL（受 max 限制），支持 Range */
    static byte[] readBytes(String url, String referer, long off, long len, int max, Escape esc)
            throws IOException {
        if (isLocal(url)) return readLocal(url, off, len, max);
        HttpURLConnection c = open(url, referer, off, len);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code + "：" + shortUrl(url));
            }
            boolean ranged = off >= 0 && len > 0;
            boolean serverHonored = code == 206;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(
                    len > 0 && len < max ? (int) len : 8192);
            try {
                if (ranged && !serverHonored) {
                    // 服务器忽略了 Range：自己跳过 offset，只读 len
                    skipFully(in, off);
                }
                byte[] buf = new byte[BUF];
                long read = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (ranged) {
                        long keep = Math.min(n, len - read);
                        if (keep <= 0) break;
                        bo.write(buf, 0, (int) keep);
                        read += keep;
                        if (read >= len) break;
                    } else {
                        bo.write(buf, 0, n);
                        read += n;
                        if (bo.size() > max) throw new IOException("响应过大（超过 "
                                + (max / 1024 / 1024) + "MB）");
                    }
                    if (esc != null && esc.cancelled()) throw new IOException("已取消");
                }
            } finally {
                try { in.close(); } catch (IOException ignore) {}
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** 读文本（m3u8 / key），按 UTF-8 */
    static String readText(String url, String referer, long off, long len, Escape esc)
            throws IOException {
        byte[] b = readBytes(url, referer, off, len, MAX_TEXT, esc);
        return new String(b, "UTF-8");
    }

    private static HttpURLConnection open(String url, String referer, long off, long len)
            throws IOException {
        String u = url;
        // 地址里可能有空格/中文（从聊天里粘出来的），HttpURLConnection 会直接抛
        u = u.replace(" ", "%20");
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
            // 少数站按 Origin 做防盗链，两个都带上比只带一个成功率高
            c.setRequestProperty("Origin", referer.endsWith("/")
                    ? referer.substring(0, referer.length() - 1) : referer);
        }
        if (off >= 0 && len > 0) {
            c.setRequestProperty("Range", "bytes=" + off + "-" + (off + len - 1));
        }
        return c;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("响应比声明的 Range 短");
                s = 1;
            }
            left -= s;
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 本地路径规范成 file:// 绝对地址（m3u8 里的相对分片靠它做 base） */
    static String localize(String u) {
        if (u == null) return null;
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")) return u;
        if (u.startsWith("/") || u.matches("^[A-Za-z]:[\\\\/].*")) {
            return "file://" + new File(u).getAbsolutePath().replace('\\', '/');
        }
        return u;
    }

    public static boolean isLocal(String url) {
        if (url == null) return false;
        return url.startsWith("file://") || url.startsWith("/")
                || url.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String localPath(String url) {
        if (url.startsWith("file://")) {
            String p = url.substring("file://".length());
            // file:///a/b 与 file://a/b 两种写法都出现过
            if (p.startsWith("localhost/")) p = p.substring("localhost".length());
            try {
                p = java.net.URLDecoder.decode(p, "UTF-8");
            } catch (Throwable ignore) {
            }
            return p;
        }
        return url;
    }

    /** 本地文件按 Range 读（大 TS 分片只取需要的一段） */
    private static byte[] readLocal(String url, long off, long len, int max) throws IOException {
        File f = new File(localPath(url));
        if (!f.isFile()) throw new IOException("文件不存在：" + f.getAbsolutePath());
        long start = off >= 0 ? off : 0;
        long want = len > 0 ? len : (f.length() - start);
        if (want <= 0) throw new IOException("区间超出文件长度：" + f.getName());
        if (want > max) throw new IOException("文件过大（超过 " + (max / 1024 / 1024) + "MB）");
        byte[] out = new byte[(int) want];
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            int read = 0;
            while (read < out.length) {
                int n = raf.read(out, read, out.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < out.length) {
                byte[] cut = new byte[read];
                System.arraycopy(out, 0, cut, 0, read);
                return cut;
            }
            return out;
        } finally {
            try { raf.close(); } catch (IOException ignore) {}
        }
    }

    /** 取消信号：Activity 传一个原子标志进来即可，避免下载器依赖 Android */
    public interface Escape {
        boolean cancelled();
    }

    private static File segFile(File dir, int index) {
        return new File(dir, String.format(Locale.US, "%06d.seg", index));
    }

    /** 从地址推导文件名（最后一段去扩展名），去掉 URL 编码与非法字符 */
    public static String deriveName(String url) {
        if (url == null) return "";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        int slash = u.lastIndexOf('/');
        String last = slash >= 0 ? u.substring(slash + 1) : u;
        int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        try {
            last = java.net.URLDecoder.decode(last, "UTF-8");
        } catch (Throwable ignore) {
        }
        if (last.isEmpty() || last.equals("index") || last.equals("playlist")
                || last.equals("master") || last.equals("prog_index")) {
            // "index.m3u8" 这类通用名做文件名毫无信息量，退到上一级目录名
            String dir = u.substring(0, Math.max(0, slash));
            int s2 = dir.lastIndexOf('/');
            if (s2 >= 0 && dir.length() > s2 + 1) return safeName(dir.substring(s2 + 1));
            return "";
        }
        return safeName(last);
    }

    static String safeName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        String r = sb.toString().trim();
        while (r.startsWith(".")) r = r.substring(1);
        return r;
    }

    /** 取 origin（scheme://host/）作为 Referer */
    static String origin(String url) {
        if (url == null) return null;
        int se = url.indexOf("://");
        if (se <= 0) return null;
        int start = se + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return url.substring(0, end) + "/";
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() > 70 ? u.substring(0, 70) + "…" : u;
    }

    /**
     * 清单指纹：地址 + 分片总数 + 首末分片的序号/地址。
     *
     * <p>任何一项变化都意味着「这不是同一份清单」，旧 .seg 不能复用。
     * 带一次性 token 的地址会让指纹每次都不同（多下一次），方向是安全的。</p>
     */
    public static String fingerprint(String m3u8Url, List<HlsPlaylist.Segment> segs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(m3u8Url).append('|').append(segs.size());
        if (!segs.isEmpty()) {
            HlsPlaylist.Segment f = segs.get(0);
            HlsPlaylist.Segment l = segs.get(segs.size() - 1);
            sb.append('|').append(f.sequence).append('|').append(f.url);
            sb.append('|').append(l.sequence).append('|').append(l.url);
        }
        return sb.toString();
    }

    static String readTextFile(File f) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
        }
    }

    static void writeTextFile(File f, String s) {
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(f);
            fo.write(s.getBytes("UTF-8"));
            fo.flush();
        } catch (IOException ignore) {
            // 指纹写不进去不致命：下次会当作「指纹不匹配」从头下，只是多花流量
        } finally {
            if (fo != null) try { fo.close(); } catch (IOException ignore) {}
        }
    }

    /** 解密失败：密钥或 IV 与清单不匹配，重试无意义，直接终止整次下载 */
    static final class DecryptError extends IOException {
        DecryptError(String msg) { super(msg); }
    }

    private static void deleteDir(File dir) {
        // v1.99.1：清理分片目录统一走 TreeOps —— 链接只删链（自写递归会把链接目标删掉）、
        // 带深度上限（自写递归遇到链接环会 StackOverflow）
        TreeOps.delete(dir);
    }
}
