package com.hex91.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * strings 提取核心（v1.38.0，纯 Java 无 Android 依赖，对标 Unix strings 思路）：
 *  - 单遍流式扫描，同时提取 ASCII 可打印串（0x20~0x7E）与 UTF-16LE 串（可打印低字节 + 0x00 高字节）
 *  - 每条命中记录文件偏移（十六进制展示由 UI 层负责）
 *  - 缓冲区跨界由状态机承接，字符串跨 64KB 边界不漏
 *  - 命中数上限 MAX_HITS，超出标记 truncated 防止超大文件拖垮内存
 *  - 最短长度 minLen 默认 4（与 Unix strings 一致），可调 2~32
 */
public final class StringExtract {

    /** 条目上限：防超大文件（数百 MB so）命中几十万条撑爆内存 */
    public static final int MAX_HITS = 50000;

    public static class Hit {
        public long offset;     // 文件内字节偏移
        public String text;     // 提取到的字符串
        public boolean utf16;   // true = UTF-16LE 串
        public Hit(long offset, String text, boolean utf16) {
            this.offset = offset; this.text = text; this.utf16 = utf16;
        }
    }

    public static class Result {
        public List<Hit> hits = new ArrayList<Hit>();
        public boolean truncated = false;   // 命中超上限被截断
        public long scannedBytes = 0;       // 实际扫描字节数
        public String error = null;
    }

    private StringExtract() {}

    /** 默认最短长度 4 */
    public static Result extract(File f) {
        return extract(f, 4);
    }

    /**
     * 提取文件中的可读字符串（耗时操作，放后台线程）。
     * @param minLen 最短长度（2~32，超出范围收敛到边界）
     */
    public static Result extract(File f, int minLen) {
        if (minLen < 2) minLen = 2;
        if (minLen > 32) minLen = 32;
        Result r = new Result();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            scan(in, minLen, r);
        } catch (Exception e) {
            r.error = e.getMessage();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        return r;
    }

    /** 单遍扫描：ASCII 与 UTF-16LE 两个独立状态机并行推进 */
    private static void scan(InputStream in, int minLen, Result r) throws IOException {
        byte[] buf = new byte[65536];

        // --- ASCII 状态机 ---
        StringBuilder asc = new StringBuilder();
        long ascStart = -1;

        // --- UTF-16LE 状态机：prev = 待配对的低字节候选（-1 无）---
        int u16prev = -1;
        long u16prevPos = -1;
        StringBuilder u16 = new StringBuilder();
        long u16Start = -1;

        long base = 0;          // buf[0] 的文件绝对偏移
        int n;
        boolean stop = false;
        while (!stop && (n = in.read(buf)) > 0) {
            r.scannedBytes += n;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                long pos = base + i;
                boolean pr = b >= 0x20 && b <= 0x7E;

                // ---- ASCII ----
                if (pr) {
                    if (asc.length() == 0) ascStart = pos;
                    asc.append((char) b);
                } else {
                    if (asc.length() >= minLen) {
                        r.hits.add(new Hit(ascStart, asc.toString(), false));
                        if (r.hits.size() >= MAX_HITS) { r.truncated = true; stop = true; break; }
                    }
                    asc.setLength(0);
                }

                // ---- UTF-16LE ----
                if (u16prev == -1) {
                    if (pr) {
                        u16prev = b; u16prevPos = pos;
                    } else if (u16.length() > 0) {
                        // 串中出现了非可打印低字节（如 \0\0 双终止）→ 断串
                        if (u16.length() >= minLen) {
                            r.hits.add(new Hit(u16Start, u16.toString(), true));
                            if (r.hits.size() >= MAX_HITS) { r.truncated = true; stop = true; break; }
                        }
                        u16.setLength(0);
                    }
                } else if (b == 0) {
                    if (u16.length() == 0) u16Start = u16prevPos;
                    u16.append((char) u16prev);
                    u16prev = -1;
                } else {
                    // 配对断裂：清当前串；当前字节可作新串首低字节
                    if (u16.length() >= minLen) {
                        r.hits.add(new Hit(u16Start, u16.toString(), true));
                        if (r.hits.size() >= MAX_HITS) { r.truncated = true; stop = true; break; }
                    }
                    u16.setLength(0);
                    u16prev = pr ? b : -1;
                    u16prevPos = pos;
                }
            }
            base += n;
        }
        if (!stop) {
            // 收尾：ASCII
            if (asc.length() >= minLen) r.hits.add(new Hit(ascStart, asc.toString(), false));
            // 收尾：UTF-16LE（最后若停在待配对状态，串已在配对成功时入 builder）
            if (u16.length() >= minLen) r.hits.add(new Hit(u16Start, u16.toString(), true));
        }
    }

    /** 偏移格式化：8 位大写十六进制（32 位文件内唯一） */
    public static String hex(long offset) {
        return String.format(java.util.Locale.US, "%08X", offset);
    }
}
