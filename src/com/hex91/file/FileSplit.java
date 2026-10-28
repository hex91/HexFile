package com.hex91.file;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件分割/合并（对标 MT Manager「文件分割/合并」）。纯 Java、无 Android 依赖，可桌面测试。
 *
 * 分割：按分卷大小把源文件切成 <名>.001 / <名>.002 / …（MT 同名约定，序号 3 位起步、超 999 自然进位）。
 * 合并：从任一分卷（惯例取 .001）出发，按连续序号找齐所有分卷，按序拼接还原为原文件名。
 *
 * 冲突处理：分卷已存在则覆盖；合并目标已存在则改用 <原名>.merged，绝不静默覆盖用户文件。
 */
public final class FileSplit {

    private FileSplit() {}

    private static final int BUF = 1 << 23; // 8MB

    /** 一条合并结果信息，供 UI 报告 */
    public static final class MergeResult {
        public final File output;
        public final int parts;
        public final long bytes;
        MergeResult(File o, int p, long b) { output = o; parts = p; bytes = b; }
    }

    /**
     * 按分卷大小分割文件到 outDir。
     * @return 生成的分卷列表（按序）
     * @throws IOException 分卷小于等于 0、源不可读或写盘失败
     */
    public static List<File> split(File in, File outDir, long chunkSize) throws IOException {
        if (in == null || !in.isFile()) throw new IOException("源文件不存在: " + in);
        if (chunkSize <= 0) throw new IOException("分卷大小必须大于 0");
        if (!in.getParentFile().equals(outDir)) outDir.mkdirs();
        if (in.length() == 0) throw new IOException("空文件无需分割");
        if (in.length() <= chunkSize) throw new IOException("文件(" + in.length()
                + "B)不大于分卷大小(" + chunkSize + "B)，无需分割");

        String base = in.getName();
        List<File> parts = new ArrayList<File>();
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(in), BUF);
        try {
            byte[] buf = new byte[BUF];
            long remaining = in.length();
            int idx = 1;
            while (remaining > 0) {
                long thisChunk = Math.min(chunkSize, remaining);
                File part = new File(outDir, base + "." + pad(idx));
                if (part.exists()) part.delete();   // 覆盖旧分卷
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(part), BUF);
                try {
                    long left = thisChunk;
                    while (left > 0) {
                        int n = bis.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) break;
                        bos.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    bos.close();
                }
                parts.add(part);
                remaining -= thisChunk;
                idx++;
            }
        } finally {
            bis.close();
        }
        // 写清单（<base>.parts）：记录总卷数与原始大小，供合并时精确校验缺卷
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(outDir, base + ".parts")), "UTF-8");
        try { w.write("MTCLONE_SPLIT v1\nparts=" + parts.size() + "\nsize=" + in.length() + "\n"); }
        finally { w.close(); }
        return parts;
    }

    /**
     * 合并分卷（传入任意一分卷均可，从其序号向前找 .001 起点、向后找连续分卷）。
     * 输出到 outDir，文件名为去掉 .NNN 后缀的原名；若已存在则加 .merged 后缀。
     * @throws IOException 序号不连续（缺卷）或写盘失败
     */
    public static MergeResult merge(File part, File outDir) throws IOException {
        if (part == null || !part.isFile()) throw new IOException("分卷不存在: " + part);
        String[] parsed = parsePartName(part.getName());
        if (parsed == null) throw new IOException("不是分卷文件（缺 .NNN 序号后缀）: " + part.getName());
        String base = parsed[0];
        int start = Integer.parseInt(parsed[1]);

        // 向前找起点（.001 或存在的最小序号）
        int first = start;
        while (first > 1) {
            File prev = new File(part.getParentFile(), base + "." + pad(first - 1));
            if (!prev.isFile()) break;
            first--;
        }
        // 向后收集连续分卷
        List<File> seq = new ArrayList<File>();
        int i = first;
        while (true) {
            File p = new File(part.getParentFile(), base + "." + pad(i));
            if (!p.isFile()) break;
            seq.add(p);
            i++;
        }
        if (start > first && seq.indexOf(part) < 0) { /* 不可能，防御 */ }
        if (seq.isEmpty()) throw new IOException("找不到分卷序列");
        if (first != 1) throw new IOException("缺少起始分卷 " + base + ".001，无法合并");

        // 缺卷校验：有清单按清单精确校验；无清单用启发式（单卷可疑 + 非末卷必须等大小）
        File manifest = new File(part.getParentFile(), base + ".parts");
        int expectParts = -1;
        long expectSize = -1;
        if (manifest.isFile()) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(manifest), "UTF-8"));
                String ln;
                while ((ln = br.readLine()) != null) {
                    if (ln.startsWith("parts=")) expectParts = Integer.parseInt(ln.substring(6).trim());
                    else if (ln.startsWith("size=")) expectSize = Long.parseLong(ln.substring(5).trim());
                }
                br.close();
            } catch (Throwable ig) { expectParts = -1; }
        }
        if (expectParts >= 0) {
            if (seq.size() != expectParts)
                throw new IOException("分卷不完整: 应有 " + expectParts + " 卷，只找到 " + seq.size()
                        + " 卷（" + base + ".001 ~ " + pad(first + seq.size() - 1) + "）");
        } else {
            if (seq.size() < 2)
                throw new IOException("仅找到一个分卷 " + seq.get(0).getName() + "，分卷不完整");
            long ref = seq.get(0).length();
            for (int k = 0; k < seq.size() - 1; k++)
                if (seq.get(k).length() != ref)
                    throw new IOException("分卷大小不一致: " + seq.get(k).getName() + "，疑似缺卷");
        }

        File out = new File(outDir, base);
        if (out.exists()) out = new File(outDir, base + ".merged");
        outDir.mkdirs();

        long total = 0;
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), BUF);
        try {
            byte[] buf = new byte[BUF];
            for (File p : seq) {
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(p), BUF);
                try {
                    int n;
                    while ((n = bis.read(buf)) > 0) { bos.write(buf, 0, n); total += n; }
                } finally {
                    bis.close();
                }
            }
        } finally {
            bos.close();
        }
        // 大小校验：有清单时精确比对总字节数
        if (total == 0) {
            out.delete();
            throw new IOException("合并结果为空（分卷可能损坏）");
        }
        if (expectSize >= 0 && total != expectSize) {
            out.delete();
            throw new IOException("合并结果大小不符: 期望 " + expectSize + "B，实际 " + total + "B（分卷可能损坏）");
        }
        return new MergeResult(out, seq.size(), total);
    }

    /** 文件名是否为分卷（.NNN，3 位起步） */
    public static boolean isSplitPart(File f) {
        return f != null && f.isFile() && parsePartName(f.getName()) != null;
    }

    /** 解析 "base.NNN" → [base, NNN]；非分卷返回 null */
    private static String[] parsePartName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String num = name.substring(dot + 1);
        if (num.length() < 3) return null;
        for (int i = 0; i < num.length(); i++) {
            char c = num.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return new String[]{ name.substring(0, dot), num };
    }

    private static String pad(int n) { return String.format("%03d", n); }
}
