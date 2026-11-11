package com.hex91.file;

import java.util.Locale;

/**
 * 流地址识别与清洗 —— v1.91.0。
 *
 * <p>播放器有三个入口都会拿到「一段用户复制的文字」而不是干净的 URL：
 * 剪贴板、系统「打开方式」、以及 m3u8 链接被别处拦截后转交过来。这段文字常带
 * 前后缀（「复制这段内容后打开…」「链接：xxx 提取码: abcd」）或者被中括号/引号包着，
 * 直接塞给播放器只会得到一个 404。本类负责从这堆文字里挑出唯一像样的地址。</p>
 *
 * <p>安全考量：只放行 http/https/rtsp/rtmps/rtsp/udp/file/content 这几类协议。
 * {@code javascript:}、{@code intent:}、{@code data:} 之类的伪协议一律拒绝 ——
 * 播放器页面是从外部 Intent 可达的（exported），把任意 scheme 传给底层
 * DataSource 等于把「打开任意东西」的能力暴露给第三方应用。</p>
 */
public final class StreamUrl {

    public static final int KIND_LOCAL = 0;
    public static final int KIND_HLS = 1;
    public static final int KIND_DASH = 2;
    public static final int KIND_RTSP = 3;
    public static final int KIND_RTMP = 4;
    public static final int KIND_PROGRESSIVE = 5;
    public static final int KIND_UNKNOWN = 6;

    private StreamUrl() {
    }

    /**
     * 清洗：去首尾空白与包裹字符（引号、尖括号、中括号、全角空格），
     * 若整段是「文字 + 链接」则抽出第一个合法地址。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 全角空格与不间断空格：从网页/微信复制时极常见，trim() 不处理
        s = stripChars(s, " \t\r\n\u00A0\u3000");
        if (s.isEmpty()) return null;
        s = stripChars(s, "\"'<>()[]【】《》“”‘’");
        if (isSupported(s)) return s;
        String found = firstUrl(s);
        return found;
    }

    /** 从任意文本里扫描第一个受支持的地址（找不到返回 null） */
    public static String firstUrl(String text) {
        if (text == null) return null;
        int n = text.length();
        for (int i = 0; i + 3 <= n; i++) {
            if (text.charAt(i) != ':' || text.charAt(i + 1) != '/' || text.charAt(i + 2) != '/') {
                continue;
            }
            // 向左回退到 scheme 起点
            int st = i;
            while (st > 0) {
                char c = text.charAt(st - 1);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
                if (!ok) break;
                st--;
            }
            // 向右吃到空白或明显的分隔符
            int en = i + 3;
            while (en < n) {
                char c = text.charAt(en);
                if (c <= ' ' || c == '"' || c == '\'' || c == '<' || c == '>'
                        || c == ')' || c == ']' || c == '}' || c == '【' || c == '】'
                        || c == '，' || c == '。' || c == '、') {
                    break;
                }
                en++;
            }
            String cand = trimTrailingPunct(text.substring(st, en));
            if (cand != null && isSupported(cand) && cand.length() > cand.indexOf("://") + 3) {
                return cand;
            }
        }
        return null;
    }

    /** 协议白名单 */
    public static boolean isSupported(String url) {
        String s = schemeOf(url);
        if (s == null) return false;
        s = s.toLowerCase(Locale.US);
        return s.equals("http") || s.equals("https") || s.equals("rtsp")
                || s.equals("rtmp") || s.equals("rtmps") || s.equals("udp")
                || s.equals("file") || s.equals("content");
    }

    /** 判定类型（按协议 + 扩展名/查询串） */
    public static int kindOf(String url) {
        if (url == null) return KIND_UNKNOWN;
        String s = schemeOf(url);
        if (s == null) return KIND_UNKNOWN;
        s = s.toLowerCase(Locale.US);
        if (s.equals("file") || s.equals("content")) return KIND_LOCAL;
        if (s.equals("rtsp")) return KIND_RTSP;
        if (s.equals("rtmp") || s.equals("rtmps")) return KIND_RTMP;
        if (s.equals("udp")) return KIND_PROGRESSIVE;
        String path = pathOf(url).toLowerCase(Locale.US);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return KIND_HLS;
        if (path.endsWith(".mpd")) return KIND_DASH;
        if (path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".m4v")
                || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".flv") || path.endsWith(".mov") || path.endsWith(".avi")
                || path.endsWith(".3gp")) {
            return KIND_PROGRESSIVE;
        }
        // 没有扩展名的直播地址（CDN 常见 /live/12345 这种）按渐进流处理，
        // 交给 ExoPlayer 自己嗅探容器格式，比直接拒绝更有用
        return s.equals("http") || s.equals("https") ? KIND_PROGRESSIVE : KIND_UNKNOWN;
    }

    public static String kindLabel(int kind) {
        switch (kind) {
            case KIND_LOCAL: return "本地文件";
            case KIND_HLS: return "HLS 直播/点播（m3u8）";
            case KIND_DASH: return "DASH（mpd）";
            case KIND_RTSP: return "RTSP 摄像头/串流";
            case KIND_RTMP: return "RTMP 推拉流";
            case KIND_PROGRESSIVE: return "直连视频/渐进流";
            default: return "未知";
        }
    }

    /**
     * 取一个便于显示的标题：末段路径，去掉查询串，URL 解码。
     * 解码失败（畸形 %）就返回原文，不抛异常。
     */
    public static String fileName(String url) {
        if (url == null) return "";
        String p = pathOf(url);
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        if (name.isEmpty()) {
            String origin = originOf(url);
            return origin == null ? url : origin;
        }
        return decode(name);
    }

    /** "scheme://host[:port]" */
    public static String originOf(String url) {
        if (url == null) return null;
        int se = schemeEnd(url);
        if (se <= 0) return null;
        int start = url.indexOf("//", se);
        if (start < 0) return null;
        start += 2;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return url.substring(0, end);
    }

    /** 去掉查询串与 fragment 的路径部分 */
    public static String pathOf(String url) {
        if (url == null) return "";
        int se = schemeEnd(url);
        int start = 0;
        if (se > 0) {
            int sl = url.indexOf("//", se);
            if (sl > 0) {
                int p = url.indexOf('/', sl + 2);
                start = p >= 0 ? p : url.length();
            } else {
                start = se + 1;
            }
        }
        int end = url.length();
        int q = url.indexOf('?', start);
        if (q >= 0) end = q;
        int h = url.indexOf('#', start);
        if (h >= 0 && h < end) end = h;
        return url.substring(start, Math.max(start, end));
    }

    // ---------------------------------------------------------------- 内部

    static String schemeOf(String url) {
        int se = schemeEnd(url);
        return se > 0 ? url.substring(0, se) : null;
    }

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

    private static String stripChars(String s, String set) {
        int st = 0, en = s.length();
        while (st < en && set.indexOf(s.charAt(st)) >= 0) st++;
        while (en > st && set.indexOf(s.charAt(en - 1)) >= 0) en--;
        return s.substring(st, en);
    }

    /** 去掉中文语境里粘在结尾的标点（"…m3u8。" / "…m3u8，"） */
    private static String trimTrailingPunct(String s) {
        int en = s.length();
        while (en > 0) {
            char c = s.charAt(en - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '。' || c == '，'
                    || c == '、' || c == '；' || c == '！' || c == '？' || c == '!'
                    || c == '?') {
                // 问号可能是查询串开头（?a=1），只在它前面没有 '?' 且整串不以 '?' 结尾时才削
                if (c == '?' && s.indexOf('?') != en - 1) break;
                en--;
            } else {
                break;
            }
        }
        return s.substring(0, en);
    }

    /** 只处理 %XX（UTF-8 单/多字节），失败原样返回 */
    static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
            byte[] src = s.getBytes("UTF-8");
            for (int i = 0; i < src.length; i++) {
                if (src[i] == '%' && i + 2 < src.length) {
                    int hi = hex(src[i + 1]), lo = hex(src[i + 2]);
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                out.write(src[i]);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
