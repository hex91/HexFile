package com.hex91.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘扫描（v1.59.0）：目录占用统计 / 大文件排行 / 重复文件三阶段查找 / 哈希校验。
 *  全部 File API 递归，无 root 依赖；统计中的无权限目录按 0 计并跳过。
 */
public final class DiskScanUtil {

    private DiskScanUtil() {}

    /** 目录统计结果 */
    public static final class DirStat {
        public File dir;
        public long size;                       // 含子目录总字节
        public int fileCount;
        public final List<File> children = new ArrayList<File>();   // 子目录
    }

    /** 单文件大小扫描结果 */
    public static final class FileSize {
        public final File file;
        public final long size;
        public FileSize(File f, long s) { file = f; size = s; }
    }

    /** 递归统计目录大小；onDir 每个子目录回调（构建占用树），可空 */
    public interface DirVisitor { void onDir(File dir, long size, int fileCount); }

    public static long scanSize(File dir, DirVisitor v) {
        File[] cs = dir.listFiles();
        if (cs == null) return 0;
        long total = 0;
        int files = 0;
        List<File> subDirs = new ArrayList<File>();
        for (File c : cs) {
            if (c.isDirectory()) {
                subDirs.add(c);
                total += scanSize(c, v);
            } else {
                total += c.length();
                files++;
            }
        }
        if (v != null) v.onDir(dir, total, files);
        return total;
    }

    /** 递归收集最大的 n 个文件（跳过符号链接不适用 File API，按普通文件处理） */
    public static List<FileSize> topFiles(File root, final int n, java.util.Set<Long> minSizeFilter) {
        final java.util.PriorityQueue<FileSize> pq = new java.util.PriorityQueue<FileSize>(
                n + 1, new Comparator<FileSize>() {
            public int compare(FileSize a, FileSize b) { return Long.compare(a.size, b.size); }
        });
        collect(root, pq, n);
        List<FileSize> out = new ArrayList<FileSize>(pq);
        java.util.Collections.sort(out, new Comparator<FileSize>() {
            public int compare(FileSize a, FileSize b) { return Long.compare(b.size, a.size); }
        });
        return out;
    }

    private static void collect(File dir, java.util.PriorityQueue<FileSize> pq, int n) {
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            if (c.isDirectory()) {
                collect(c, pq, n);
            } else {
                long len = c.length();
                if (pq.size() < n) {
                    pq.add(new FileSize(c, len));
                } else if (len > pq.peek().size) {
                    pq.poll();
                    pq.add(new FileSize(c, len));
                }
            }
        }
    }

    // ---------- 重复文件查找（三阶段：大小 → 头4KB → 全量 MD5） ----------

    /** 一组互为重复的文件 */
    public static final class DupGroup {
        public long size;
        public final List<File> files = new ArrayList<File>();
    }

    public interface Progress { void onStage(String stage); void onFile(int done, int total); }

    /**
     * 在 root 下递归找重复文件。minSize 以下跳过（哈希开销不划算）。
     * 返回按组内文件数降序的重复组列表。
     */
    public static List<DupGroup> findDuplicates(File root, long minSize, Progress cb)
            throws IOException {
        // 阶段 1：按大小分组
        if (cb != null) cb.onStage("收集文件…");
        List<File> all = new ArrayList<File>();
        collectFiles(root, all);
        Map<Long, List<File>> bySize = new HashMap<Long, List<File>>();
        for (File f : all) {
            long len = f.length();
            if (len < minSize) continue;
            List<File> g = bySize.get(len);
            if (g == null) { g = new ArrayList<File>(); bySize.put(len, g); }
            g.add(f);
        }
        // 阶段 2：同大小的取头 4KB 哈希再分
        if (cb != null) cb.onStage("快速指纹…");
        List<File> candidates = new ArrayList<File>();
        for (List<File> g : bySize.values()) if (g.size() > 1) candidates.addAll(g);
        Map<Long, List<File>> cand = new HashMap<Long, List<File>>();
        cand.putAll(bySize);
        Map<String, List<File>> headMap = new HashMap<String, List<File>>();
        int done = 0;
        for (List<File> g : bySize.values()) {
            if (g.size() == 1) continue;
            Map<String, List<File>> sub = new HashMap<String, List<File>>();
            for (File f : g) {
                String h = hashHead(f);
                List<File> l = sub.get(h);
                if (l == null) { l = new ArrayList<File>(); sub.put(h, l); }
                l.add(f);
                if (cb != null) cb.onFile(++done, candidates.size());
            }
            for (List<File> l : sub.values()) if (l.size() > 1) {
                for (File f : l) {
                    String key = f.length() + "|" + hashHead(f);
                    List<File> hl = headMap.get(key);
                    if (hl == null) { hl = new ArrayList<File>(); headMap.put(key, hl); }
                    hl.add(f);
                }
            }
        }
        // 阶段 3：候选组全量 MD5 确认
        if (cb != null) cb.onStage("全量校验…");
        List<DupGroup> out = new ArrayList<DupGroup>();
        int total2 = 0;
        for (List<File> g : headMap.values()) total2 += g.size();
        done = 0;
        for (List<File> g : headMap.values()) {
            Map<String, List<File>> full = new HashMap<String, List<File>>();
            for (File f : g) {
                String h;
                try { h = hashFull(f, "MD5"); }
                catch (Exception e) { h = "err:" + f.getAbsolutePath(); }
                List<File> l = full.get(h);
                if (l == null) { l = new ArrayList<File>(); full.put(h, l); }
                l.add(f);
                if (cb != null) cb.onFile(++done, total2);
            }
            for (List<File> l : full.values()) {
                if (l.size() > 1) {
                    DupGroup grp = new DupGroup();
                    grp.size = l.get(0).length();
                    grp.files.addAll(l);
                    out.add(grp);
                }
            }
        }
        java.util.Collections.sort(out, new Comparator<DupGroup>() {
            public int compare(DupGroup a, DupGroup b) {
                long la = a.size * (a.files.size() - 1), lb = b.size * (b.files.size() - 1);
                return Long.compare(lb, la);   // 可释放空间大的在前
            }
        });
        return out;
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            if (c.isDirectory()) collectFiles(c, out);
            else if (c.isFile()) out.add(c);
        }
    }

    private static String hashHead(File f) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n > 0) md.update(buf, 0, n);
                return hex(md.digest());
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "err:" + f.getAbsolutePath();
        }
    }

    /** 流式计算全文件哈希（MD5/SHA-1/SHA-256） */
    public static String hashFull(File f, String algo) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } finally {
            in.close();
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
