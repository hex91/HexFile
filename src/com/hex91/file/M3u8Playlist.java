package com.hex91.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * HLS 主播放列表（m3u8 master playlist）解析器 —— v1.91.0。
 *
 * <p>为什么自己解析：ExoPlayer 内部会做自适应码率（ABR），但「选画质」这个动作
 * 在播放器里必须由人决定 —— 用户点 720P，就该锁 720P，而不是下一分钟又被 ABR 切回 1080P。
 * 锁定码率最稳的做法是：拿 master 里对应档位的**子播放列表地址**重新 prepare，
 * 播放器就只剩这一档，ABR 无从切换。</p>
 *
 * <p>因此本类只干一件事：把 master 里的 {@code #EXT-X-STREAM-INF} 变体解析成
 * 「显示名 + 地址 + 分辨率 + 码率」，供画质菜单使用。解析失败不影响播放 ——
 * 调用方拿不到变体时按「自动」处理，直接把原始 m3u8 交给 ExoPlayer。</p>
 *
 * <p>实现要点（都是真实踩过的坑）：</p>
 * <ol>
 *   <li>HLS 规范要求行尾是 CRLF，直接 {@code split("\n")} 会留下 {@code \r}，
 *       拼出来的子地址带 {@code \r} 必然 404，所以每行都要 strip。</li>
 *   <li>属性串用逗号分隔，但 {@code CODECS="avc1.64001f,mp4a.40.2"} 的引号里有逗号，
 *       必须做「引号感知」的切分，否则 CODECS 会被切成两半、后续属性全错位。</li>
 *   <li>{@code #EXT-X-I-FRAME-STREAM-INF} 是拖动条预览用的 I 帧轨道，
 *       不是可播放档位，混进画质菜单会让用户选到一张「只有缩略图」的列表。</li>
 *   <li>子播放列表地址可以是绝对地址、协议相对（{@code //host/x.m3u8}）、
 *       相对路径（含 {@code ../}）、甚至只改查询串 —— 都要按 base 正确解析。</li>
 * </ol>
 */
public final class M3u8Playlist {

    /** 无法识别（空内容 / 不是 HLS） */
    public static final int KIND_UNKNOWN = 0;
    /** master playlist：含多档码率（画质可选） */
    public static final int KIND_MASTER = 1;
    /** media playlist：就是一路流（画质不可选） */
    public static final int KIND_MEDIA = 2;

    /** 一档画质 */
    public static final class Variant {
        /** 已解析成绝对地址的子播放列表 */
        public final String url;
        public final int width;
        public final int height;
        /** 峰值码率（bps），未知为 0 */
        public final long bandwidth;
        /** 显示名：如 1080p / 720p；无分辨率信息时用码率档（如 1.8 Mbps） */
        public final String label;
        /** RFC 6381 编码串，未知为 "" */
        public final String codecs;

        Variant(String url, int width, int height, long bandwidth, String label, String codecs) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.label = label;
            this.codecs = codecs;
        }

        /** 纵向像素（短边），无分辨率信息返回 0 */
        public int shortSide() {
            return width > 0 && height > 0 ? Math.min(width, height) : 0;
        }

        /** 画质菜单第二行的补充说明：「1920×1080 · 4.2 Mbps」 */
        public String detail() {
            StringBuilder sb = new StringBuilder();
            if (width > 0 && height > 0) sb.append(width).append('×').append(height);
            if (bandwidth > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(mbpsText(bandwidth));
            }
            if (sb.length() == 0) sb.append("码率未知");
            return sb.toString();
        }

        @Override
        public String toString() {
            return label + " (" + detail() + ") " + url;
        }
    }

    public final int kind;
    private final List<Variant> variants;

    private M3u8Playlist(int kind, List<Variant> variants) {
        this.kind = kind;
        this.variants = variants;
    }

    /** 是否含有可选画质（≥2 档；只有一档时「选画质」没有意义） */
    public boolean hasChoice() {
        return variants.size() >= 2;
    }

    public List<Variant> variants() {
        return Collections.unmodifiableList(variants);
    }

    /** 码率从高到低（画质菜单的常见顺序），同码率按像素降序 */
    public List<Variant> byQualityDesc() {
        List<Variant> out = new ArrayList<Variant>(variants);
        Collections.sort(out, new Comparator<Variant>() {
            public int compare(Variant a, Variant b) {
                int r = Long.compare(b.bandwidth, a.bandwidth);
                if (r != 0) return r;
                return Integer.compare(b.shortSide(), a.shortSide());
            }
        });
        return out;
    }

    /** 最高画质（码率优先，其次像素） */
    public Variant best() {
        List<Variant> s = byQualityDesc();
        return s.isEmpty() ? null : s.get(0);
    }

    /** 按显示名找档位（"1080p"），找不到返回 null */
    public Variant byLabel(String label) {
        if (label == null) return null;
        for (Variant v : variants) if (label.equals(v.label)) return v;
        return null;
    }

    /** 解析失败时的空结果（KIND_UNKNOWN） */
    public static M3u8Playlist empty() {
        return new M3u8Playlist(KIND_UNKNOWN, Collections.<Variant>emptyList());
    }

    /**
     * 解析 m3u8 文本。
     *
     * @param text    m3u8 内容（UTF-8 解码后的字符串）
     * @param baseUrl 该 m3u8 自身的地址，用于把相对路径补成绝对地址；本地文件可传 null
     */
    public static M3u8Playlist parse(String text, String baseUrl) {
        if (text == null) return empty();
        String[] raw = text.split("\n", -1);
        List<Variant> out = new ArrayList<Variant>();
        boolean master = false;
        boolean media = false;
        // 待绑定的 STREAM-INF 属性（下一行非注释行是它的地址）
        String pendingAttr = null;

        for (int i = 0; i < raw.length; i++) {
            String line = stripCr(raw[i]).trim();
            if (line.isEmpty()) continue;

            if (line.charAt(0) == '#') {
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    master = true;
                    pendingAttr = line.substring("#EXT-X-STREAM-INF:".length());
                } else if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                    // 只要 I 帧轨道也说明是 master，但它本身不是可播放档位
                    master = true;
                } else if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-TARGETDURATION")
                        || line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                    media = true;
                }
                continue;
            }

            // 普通行 = 子播放列表地址
            if (pendingAttr != null) {
                String url = resolve(baseUrl, line);
                if (url != null) out.add(buildVariant(pendingAttr, url));
                pendingAttr = null;
            }
        }
        if (master) return new M3u8Playlist(KIND_MASTER, out);
        if (media) return new M3u8Playlist(KIND_MEDIA, Collections.<Variant>emptyList());
        // 既没有 STREAM-INF 也没有 EXTINF：可能是被截断的列表，按 media 处理（交给播放器自己判断）
        return new M3u8Playlist(text.trim().startsWith("#EXTM3U") ? KIND_MEDIA : KIND_UNKNOWN,
                Collections.<Variant>emptyList());
    }

    /** parse 的安全版：任何异常都返回 empty()，绝不让「解析画质」把播放流程带崩 */
    public static M3u8Playlist parseOrEmpty(String text, String baseUrl) {
        try {
            return parse(text, baseUrl);
        } catch (Throwable t) {
            return empty();
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Variant buildVariant(String attrLine, String url) {
        String resolution = attrValue(attrLine, "RESOLUTION");
        int w = 0, h = 0;
        if (resolution != null) {
            int x = resolution.indexOf('x');
            if (x > 0) {
                w = parseInt(resolution.substring(0, x), 0);
                h = parseInt(resolution.substring(x + 1), 0);
            }
        }
        long bw = parseLong(attrValue(attrLine, "BANDWIDTH"), 0);
        String codecs = attrValue(attrLine, "CODECS");
        String label = attrValue(attrLine, "NAME");
        if (label == null || label.isEmpty()) {
            int shortSide = (w > 0 && h > 0) ? Math.min(w, h) : 0;
            label = shortSide > 0 ? shortSide + "p" : (bw > 0 ? mbpsText(bw) : "源");
        }
        return new Variant(url, w, h, bw, label, codecs == null ? "" : codecs);
    }

    /** 引号感知的属性提取：只在引号外按逗号切分，返回 KEY=value 里的 value（去引号）。public 供单测 */
    public static String attrValue(String line, String key) {
        if (line == null) return null;
        int n = line.length();
        int start = 0;
        boolean inQuote = false;
        for (int i = 0; i <= n; i++) {
            char c = i < n ? line.charAt(i) : ',';
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                String part = line.substring(start, i).trim();
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(key)) {
                    String v = part.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
                start = i + 1;
            }
        }
        return null;
    }

    /**
     * 把 {@code uri} 按 {@code base} 解析成绝对地址。
     * 不依赖 java.net.URI（对含空格/中文的地址会抛异常），全部手写。
     */
    public static String resolve(String base, String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        if (u.isEmpty()) return null;
        int colon = schemeEnd(u);
        if (colon > 0) return u;                       // 已是绝对地址
        if (u.startsWith("//")) {                      // 协议相对
            String scheme = schemeOf(base);
            return scheme == null ? u : scheme + ":" + u;
        }
        if (base == null || base.isEmpty()) return u;   // 无 base，只能原样返回
        if (u.startsWith("/")) {                        // 站点绝对路径
            String origin = originOf(base);
            return origin == null ? u : origin + u;
        }
        // 相对路径：以 base 所在目录为基准
        String b = base;
        int hash = b.indexOf('#');
        if (hash >= 0) b = b.substring(0, hash);
        int q = b.indexOf('?');
        if (q >= 0) b = b.substring(0, q);
        int slash = b.lastIndexOf('/');
        String dir = slash > 0 ? b.substring(0, slash + 1) : b;
        return normalizePath(dir + u);
    }

    /** 折叠 {@code ./} 与 {@code ../}（保留 {@code scheme://} 与查询串不动） */
    public static String normalizePath(String url) {
        int cut = url.length();
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        if (q >= 0) cut = q;
        if (h >= 0 && h < cut) cut = h;
        String head = url.substring(0, cut);
        String tail = url.substring(cut);

        int schemeAt = schemeEnd(head);
        String prefix = "";
        if (schemeAt > 0) {
            int hostStart = head.indexOf("//", schemeAt);
            if (hostStart > 0) {
                int pathStart = head.indexOf('/', hostStart + 2);
                if (pathStart < 0) return url;         // 无路径，没什么可折叠
                prefix = head.substring(0, pathStart);
                head = head.substring(pathStart);
            }
        }
        String[] segs = head.split("/", -1);
        List<String> keep = new ArrayList<String>();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!keep.isEmpty()) keep.remove(keep.size() - 1);
                continue;
            }
            keep.add(s);
        }
        StringBuilder sb = new StringBuilder(prefix);
        for (String s : keep) sb.append('/').append(s);
        if (head.endsWith("/") && sb.length() > 0) sb.append('/');
        return sb + tail;
    }

    /** 返回 "scheme://host[:port][/…]" 的前缀，取不到返回 null */
    static String originOf(String url) {
        if (url == null) return null;
        int se = schemeEnd(url);
        if (se <= 0) return null;
        int start = url.indexOf("//", se);
        if (start < 0) return null;
        start += 2;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return url.substring(0, end);
    }

    static String schemeOf(String url) {
        int se = schemeEnd(url);
        return se > 0 ? url.substring(0, se) : null;
    }

    /** 冒号下标（"https://x" → 5）；不是合法 scheme 返回 -1 */
    static int schemeEnd(String url) {
        if (url == null || url.isEmpty()) return -1;
        char c0 = url.charAt(0);
        if (!((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z'))) return -1;
        for (int i = 1; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ':') return i;
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!ok) return -1;
        }
        return -1;
    }

    static String stripCr(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == '\r' || s.charAt(n - 1) == '\n')) n--;
        return n == s.length() ? s : s.substring(0, n);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 码率人类可读：4200000 → "4.2 Mbps"，800000 → "800 Kbps" */
    static String mbpsText(long bps) {
        if (bps <= 0) return "码率未知";
        if (bps >= 1000000L) {
            double m = bps / 1000000.0;
            return trim1(m) + " Mbps";
        }
        return Math.round(bps / 1000.0) + " Kbps";
    }

    private static String trim1(double v) {
        long r = Math.round(v * 10);
        if (r % 10 == 0) return String.valueOf(r / 10);
        return (r / 10) + "." + (r % 10);
    }
}
