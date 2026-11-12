package com.hex91.file.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HLS 媒体播放列表（media playlist）解析器 —— v1.92.0，纯 Java。
 *
 * <p>{@link M3u8Playlist} 只管「选哪一档画质」（master 里的变体），本类管「这一档有哪些分片、
 * 要不要解密、按什么顺序拼」——下载合并需要的全部信息。</p>
 *
 * <p>看起来只是「按行读地址」，实际上漏掉任何一条都会得到「下完了但播不了」：</p>
 * <ol>
 *   <li><b>{@code #EXT-X-BYTERANGE}</b>：一个 .ts 里塞了整部片子，靠字节区间切片。
 *       不按 Range 取，每一片都会拿到整个文件，合并结果是一部片子重复 N 遍。
 *       offset 省略时表示「接着上一个同 URL 分片的结尾」，不是从 0 开始。</li>
 *   <li><b>{@code #EXT-X-MAP}</b>：fMP4 分片的初始化段（moov/ftyp）。少下载它，
 *       拼出来是一堆没有索引的 mdat，任何播放器都打不开。</li>
 *   <li><b>{@code #EXT-X-KEY} 是「状态」不是「一次事件」</b>：它从出现的那一行起对后续所有
 *       分片生效，直到下一个 KEY。所以要存进每个分片，而不是整个列表记一个。</li>
 *   <li><b>{@code #EXT-X-GAP}</b>：明确标记「这段没内容」。当成正常分片去请求会拿到 404，
 *       整条下载因此失败 —— 其实跳过它就是完整可播的。</li>
 *   <li><b>行尾 CRLF + BOM</b>：前者让地址尾部带 {@code \r} 必 404，后者让首行判断失效。</li>
 * </ol>
 */
public final class HlsPlaylist {

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_MASTER = 1;
    public static final int KIND_MEDIA = 2;

    /** 一个分片 */
    public static final class Segment {
        /** 已补成绝对地址 */
        public final String url;
        public final long durationMs;
        /** 媒体序列号（含 MEDIA-SEQUENCE 基数），AES-128 隐式 IV 就是它 */
        public final long sequence;
        /** 本片的密钥（明文时是 {@link HlsKey#NONE}） */
        public final HlsKey key;
        /** HTTP Range 起点，-1 表示整文件 */
        public final long rangeOffset;
        /** HTTP Range 长度，-1 表示到结尾 */
        public final long rangeLength;
        /** 前面有 #EXT-X-DISCONTINUITY（时间轴不连续，合并后可能有一段跳变） */
        public final boolean discontinuity;
        /** #EXT-X-GAP：这段确实没有内容，下载时应跳过 */
        public final boolean gap;

        Segment(String url, long durationMs, long sequence, HlsKey key,
                long rangeOffset, long rangeLength, boolean discontinuity, boolean gap) {
            this.url = url;
            this.durationMs = durationMs;
            this.sequence = sequence;
            this.key = key;
            this.rangeOffset = rangeOffset;
            this.rangeLength = rangeLength;
            this.discontinuity = discontinuity;
            this.gap = gap;
        }

        public boolean hasRange() {
            return rangeOffset >= 0;
        }

        public boolean encrypted() {
            return key != null && !key.isNone();
        }

        /** 单片预计字节数（有 Range 长度时），否则 -1 */
        public long declaredBytes() {
            return rangeLength > 0 ? rangeLength : -1;
        }
    }

    public final int kind;
    private final List<Segment> segments;
    /** fMP4 初始化段地址，无则 null */
    public final String initUrl;
    public final long initRangeOffset;
    public final long initRangeLength;
    /** 有 #EXT-X-ENDLIST：点播（下载是完整的）；没有则是直播，只能拿当前窗口 */
    public final boolean hasEndList;
    /** #EXT-X-MEDIA-SEQUENCE 基数 */
    public final long mediaSequence;
    /** 列表里是否出现过 DISCONTINUITY */
    public final boolean hasDiscontinuity;

    private HlsPlaylist(int kind, List<Segment> segments, String initUrl,
                        long initRangeOffset, long initRangeLength,
                        boolean hasEndList, long mediaSequence) {
        this.kind = kind;
        this.segments = segments;
        this.initUrl = initUrl;
        this.initRangeOffset = initRangeOffset;
        this.initRangeLength = initRangeLength;
        this.hasEndList = hasEndList;
        this.mediaSequence = mediaSequence;
        boolean disc = false;
        for (Segment s : segments) if (s.discontinuity) disc = true;
        this.hasDiscontinuity = disc;
    }

    public static HlsPlaylist empty() {
        return new HlsPlaylist(KIND_UNKNOWN, Collections.<Segment>emptyList(),
                null, -1, -1, false, 0);
    }

    public List<Segment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public int count() {
        return segments.size();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** 直播流：没有 ENDLIST，分片列表会滚动，现在下的是当前窗口 */
    public boolean isLive() {
        return kind == KIND_MEDIA && !hasEndList;
    }

    public long totalDurationMs() {
        long sum = 0;
        for (Segment s : segments) sum += Math.max(0, s.durationMs);
        return sum;
    }

    /** 跳过 GAP 之后真正要下载的分片数 */
    public int downloadableCount() {
        int n = 0;
        for (Segment s : segments) if (!s.gap) n++;
        return n;
    }

    /** 是否加密（任一分片带 key） */
    public boolean encrypted() {
        for (Segment s : segments) if (s.encrypted()) return true;
        return false;
    }

    /**
     * 全部加密分片用的是同一把 key 时返回它，否则 null（有多把 key 或没加密）。
     * <p>用于界面上一句话说明，以及「只下一次密钥文件」的优化判断。</p>
     */
    public HlsKey uniformKey() {
        HlsKey k = null;
        boolean sawPlain = false;
        for (Segment s : segments) {
            if (s.gap || s.key == null) continue;
            if (s.key.isNone()) { sawPlain = true; continue; }
            if (k == null) k = s.key;
            else if (!k.sameAs(s.key)) return null;
        }
        // 有的片加密、有的片明文（切码率时 KEY 没清干净），此时没有「统一密钥」
        return sawPlain ? null : k;
    }

    /** 有没有解不了的加密（SAMPLE-AES / FairPlay） */
    public String blockReason() {
        for (Segment s : segments) {
            if (s.key == null || s.key.supported()) continue;
            return s.key.unsupportedReason();
        }
        return null;
    }

    /** 加密说明：未加密 / AES-128 / SAMPLE-AES */
    public String encryptionText() {
        HlsKey u = uniformKey();
        if (u != null) return u.label();
        for (Segment s : segments) {
            if (s.encrypted()) return "多把密钥";
        }
        return "未加密";
    }

    /** fMP4 分片（有 MAP 或分片后缀是 m4s/mp4）：拼接方式与 TS 不同 */
    public boolean likelyFmp4() {
        if (initUrl != null) return true;
        String ext = firstSegmentExt();
        return "m4s".equals(ext) || "mp4".equals(ext) || "m4a".equals(ext);
    }

    /** 第一个非跳过分片的扩展名（小写，无点），取不到返回 "" */
    public String firstSegmentExt() {
        for (Segment s : segments) {
            if (s.gap) continue;
            return extOf(s.url);
        }
        return "";
    }

    /** 合并文件的建议扩展名：fMP4 用 mp4，其余用 ts */
    public String suggestedExt() {
        return likelyFmp4() ? "mp4" : "ts";
    }

    static String extOf(String url) {
        if (url == null) return "";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        int slash = u.lastIndexOf('/');
        if (slash >= 0) u = u.substring(slash + 1);
        int dot = u.lastIndexOf('.');
        if (dot < 0 || dot == u.length() - 1) return "";
        return u.substring(dot + 1).toLowerCase(Locale.US);
    }

    // ---------------------------------------------------------------- 解析

    public static HlsPlaylist parseOrEmpty(String text, String baseUrl) {
        try {
            return parse(text, baseUrl);
        } catch (Throwable t) {
            return empty();
        }
    }

    /**
     * @param text    m3u8 文本
     * @param baseUrl 该 m3u8 的绝对地址（用于补相对路径）；本地文件可传 null
     */
    public static HlsPlaylist parse(String text, String baseUrl) {
        if (text == null || text.trim().isEmpty()) return empty();

        String[] raw = text.split("\n", -1);
        List<Segment> out = new ArrayList<Segment>();
        // 同一 URL 的上一个区间结尾：BYTERANGE 省略 offset 时接着它
        Map<String, Long> lastEnd = new HashMap<String, Long>();

        boolean master = false;
        boolean sawExtM3u = false;
        boolean endList = false;
        long mediaSeq = 0;
        long pendingDurMs = -1;
        long pendingRangeLen = -1;
        long pendingRangeOff = -1;
        boolean pendingDisc = false;
        boolean pendingGap = false;
        HlsKey curKey = HlsKey.NONE;
        String initUrl = null;
        long initOff = -1, initLen = -1;
        int seqIndex = 0;

        for (int i = 0; i < raw.length; i++) {
            String line = stripCr(raw[i]);
            // BOM 只在首行出现，去掉后 #EXTM3U 才认得出
            if (i == 0) line = stripBom(line);
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.charAt(0) == '#') {
                if (line.startsWith("#EXTM3U")) {
                    sawExtM3u = true;
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    master = true;
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    endList = true;
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    mediaSeq = parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), 0);
                } else if (line.startsWith("#EXTINF:")) {
                    String v = line.substring("#EXTINF:".length());
                    int comma = v.indexOf(',');
                    if (comma >= 0) v = v.substring(0, comma);
                    pendingDurMs = Math.round(parseDouble(v, 0) * 1000);
                } else if (line.startsWith("#EXT-X-KEY:")) {
                    curKey = HlsKey.parse(line.substring("#EXT-X-KEY:".length()), baseUrl);
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    String attrs = line.substring("#EXT-X-MAP:".length());
                    String uri = M3u8Playlist.attrValue(attrs, "URI");
                    if (uri != null) initUrl = M3u8Playlist.resolve(baseUrl, uri);
                    String br = M3u8Playlist.attrValue(attrs, "BYTERANGE");
                    long[] r = parseRange(br);
                    initOff = r[0];
                    initLen = r[1];
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    // 返回 {offset, length}：写反的话第一片从 0 取 N 字节还算对，
                    // 从第二片起 offset 会变成「长度」—— 合并出来是一部片子重复
                    long[] r = parseRange(line.substring("#EXT-X-BYTERANGE:".length()));
                    pendingRangeOff = r[0];
                    pendingRangeLen = r[1];
                } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    // 注意 #EXT-X-DISCONTINUITY-SEQUENCE 也匹配这个前缀，但它只是序号，不该标记分片
                    if (!line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) pendingDisc = true;
                } else if (line.startsWith("#EXT-X-GAP")) {
                    pendingGap = true;
                }
                continue;
            }

            // 普通行 = 分片地址（master 的变体地址也走这里，但 master 不收分片）
            String url = M3u8Playlist.resolve(baseUrl, line);
            if (url == null) continue;
            if (master) {
                // master 里的变体行由 M3u8Playlist 解析，这里只记「是 master」就够
                continue;
            }

            long off = -1, len = -1;
            if (pendingRangeLen >= 0) {
                len = pendingRangeLen;
                Long prevEnd = lastEnd.get(url);
                off = pendingRangeOff >= 0 ? pendingRangeOff
                        : (prevEnd == null ? 0 : prevEnd);
                lastEnd.put(url, off + len);
            }

            out.add(new Segment(url, pendingDurMs < 0 ? 0 : pendingDurMs,
                    mediaSeq + seqIndex, curKey, off, len, pendingDisc, pendingGap));

            seqIndex++;
            pendingDurMs = -1;
            pendingRangeLen = -1;
            pendingRangeOff = -1;
            pendingDisc = false;
            pendingGap = false;
        }

        if (master) {
            // 变体列表交给 M3u8Playlist；这里保留 MAP 之类的信息没意义
            return new HlsPlaylist(KIND_MASTER, Collections.<Segment>emptyList(),
                    null, -1, -1, false, mediaSeq);
        }
        // 规范要求 m3u8 首行是 #EXTM3U。不校验的话，一个 404 HTML 页面
        // 也会被当成「只有一个分片」的列表，然后下载出 4KB 的 <html>…
        if (sawExtM3u) {
            return new HlsPlaylist(KIND_MEDIA, out, initUrl, initOff, initLen,
                    endList, mediaSeq);
        }
        return empty();
    }

    /**
     * 解析 {@code n[@o]} 形式的 BYTERANGE。
     *
     * @return {offset, length}，未声明的那一项为 -1
     */
    static long[] parseRange(String s) {
        if (s == null) return new long[]{-1, -1};
        String v = s.trim();
        if (v.isEmpty()) return new long[]{-1, -1};
        int at = v.indexOf('@');
        long len = parseLong(at >= 0 ? v.substring(0, at) : v, -1);
        if (len <= 0) return new long[]{-1, -1};
        long off = at >= 0 ? parseLong(v.substring(at + 1), -1) : -1;
        return new long[]{off, len};
    }

    static String stripCr(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == '\r' || s.charAt(n - 1) == '\n')) n--;
        return n == s.length() ? s : s.substring(0, n);
    }

    static String stripBom(String s) {
        return s.length() > 0 && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
