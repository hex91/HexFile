package com.hex91.file;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * v1.81.0 文件内容搜索（grep）。
 *
 * <p>设计约束：手机上一次要扫上万个小文件，所以
 * <ul>
 *   <li>单文件默认只扫前 4MB（超大日志/镜像跳过，可调）；</li>
 *   <li>先读头部 4KB 判二进制（含 0x00 即跳过），避免在 so/apk 上白耗 IO；</li>
 *   <li>按块读（64KB）再按行切，不做逐字节 read()，也不把整文件读进内存；</li>
 *   <li>单行最长保留 8KB（压缩过的 js 一行几十 MB 也不会吃内存），超长行仍参与行号计数；</li>
 *   <li>命中数达上限立即返回，调用方可随时收工。</li>
 * </ul>
 * 纯 Java（不依赖 Android），桌面可直接单测。</p>
 */
public final class ContentGrep {

    private ContentGrep() {}

    /** 单行最多保留的字节数（超出部分丢弃，但仍算同一行） */
    public static final int MAX_LINE_BYTES = 8192;
    /** 判定二进制时检查的头部字节数 */
    public static final int HEAD_BYTES = 4096;
    /** 默认单文件扫描上限 */
    public static final long DEFAULT_MAX_FILE = 4L * 1024 * 1024;

    public static class Match {
        public final int line;      // 行号，从 1 开始
        public final String text;   // 该行内容（最多 MAX_LINE_BYTES 解码）

        public Match(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    public static class Result {
        public int hits;
        public int lines;
        public boolean binary;      // 因二进制跳过（没有扫描）
        public boolean truncated;   // 命中数已达上限
        public boolean tooBig;      // 超过 maxBytes，未扫描
        public final List<Match> matches = new ArrayList<Match>();

        public Match first() { return matches.isEmpty() ? null : matches.get(0); }
    }

    /**
     * 扫描一个文件。
     *
     * @param maxHits  最多记录多少条命中
     * @param maxBytes 单文件字节上限；&lt;=0 表示不限制
     */
    public static Result grepFile(File f, String query, boolean regex, boolean ignoreCase,
                                  int maxHits, long maxBytes) {
        Result r = new Result();
        if (f == null || !f.isFile()) return r;
        if (maxBytes > 0 && f.length() > maxBytes) {
            r.tooBig = true;
            return r;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return grep(in, query, regex, ignoreCase, maxHits);
        } catch (Exception e) {
            return r;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** 扫描流（不关闭调用方的流） */
    public static Result grep(InputStream raw, String query, boolean regex, boolean ignoreCase,
                              int maxHits) {
        Result r = new Result();
        if (query == null || query.isEmpty() || maxHits <= 0) return r;

        Pattern pat = null;
        if (regex) {
            try {
                pat = Pattern.compile(query,
                        ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            } catch (PatternSyntaxException e) {
                return r;   // 调用方已提前校验，这里只是防御
            }
        }
        String needle = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;

        BufferedInputStream in = (raw instanceof BufferedInputStream)
                ? (BufferedInputStream) raw : new BufferedInputStream(raw, 65536);
        try {
            // ---- 二进制快速判定 ----
            in.mark(HEAD_BYTES + 1);
            byte[] head = new byte[HEAD_BYTES];
            int hn = readSome(in, head);
            in.reset();
            if (looksBinary(head, hn)) {
                r.binary = true;
                return r;
            }

            // ---- 分块读 + 按行切 ----
            byte[] chunk = new byte[65536];
            byte[] line = new byte[MAX_LINE_BYTES];
            int lineLen = 0;
            boolean overflow = false;
            int lineNo = 1;
            int n;
            while ((n = in.read(chunk)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte c = chunk[i];
                    if (c == '\n') {
                        r.lines = lineNo;
                        if (!overflow && matches(line, lineLen, pat, needle, ignoreCase)) {
                            r.matches.add(new Match(lineNo, decode(line, lineLen)));
                            r.hits++;
                            if (r.hits >= maxHits) {
                                r.truncated = true;
                                return r;
                            }
                        }
                        lineNo++;
                        lineLen = 0;
                        overflow = false;
                    } else if (c == '\r') {
                        // CRLF / 老式 CR：忽略换行符本身
                    } else if (lineLen < MAX_LINE_BYTES) {
                        line[lineLen++] = c;
                    } else {
                        overflow = true;
                    }
                }
            }
            // 末尾没有换行的最后一行
            if (lineLen > 0 && !overflow) {
                r.lines = lineNo;
                if (matches(line, lineLen, pat, needle, ignoreCase)) {
                    r.matches.add(new Match(lineNo, decode(line, lineLen)));
                    r.hits++;
                }
            }
            return r;
        } catch (Exception e) {
            return r;
        }
    }

    /** 头部是否像二进制：出现 NUL，或控制字符占比过高 */
    public static boolean looksBinary(byte[] head, int len) {
        if (len <= 0) return false;
        int ctrl = 0;
        for (int i = 0; i < len; i++) {
            int b = head[i] & 0xFF;
            if (b == 0) return true;
            // 允许 \t \n \r \f \b，其余 C0 控制字符计数
            if (b < 0x09 || (b > 0x0D && b < 0x20)) ctrl++;
        }
        return ctrl * 100 / len > 10;   // 超过 10% 视为二进制
    }

    private static boolean matches(byte[] line, int len, Pattern pat, String needle,
                                   boolean ignoreCase) {
        String s = decode(line, len);
        if (pat != null) return pat.matcher(s).find();
        if (ignoreCase) return s.toLowerCase(Locale.ROOT).contains(needle);
        return s.contains(needle);
    }

    private static String decode(byte[] b, int len) {
        try {
            return new String(b, 0, len, "UTF-8");
        } catch (Exception e) {
            return new String(b, 0, len);
        }
    }

    private static int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    /**
     * 结果行摘要：去首尾空白 + 控制字符换空格 + 限制长度。
     * 有命中词时尽量把命中位置放到可见区域内。
     */
    public static String snippet(String line, String query, int maxLen) {
        if (line == null) return "";
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        String s = sb.toString().trim();
        if (s.length() <= maxLen) return s;

        int at = -1;
        if (query != null && !query.isEmpty()) {
            at = s.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        }
        if (at < 0) return s.substring(0, maxLen) + "…";
        int start = Math.max(0, at - maxLen / 3);
        int end = Math.min(s.length(), start + maxLen);
        start = Math.max(0, end - maxLen);
        return (start > 0 ? "…" : "") + s.substring(start, end) + (end < s.length() ? "…" : "");
    }
}
