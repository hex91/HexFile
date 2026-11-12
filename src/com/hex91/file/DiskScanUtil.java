package com.hex91.file;

import com.hex91.file.core.*;
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
import java.util.PriorityQueue;

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

    // ---------- v1.73.0：单遍统计（目录占用 + 大文件 + 真实进度） ----------

    /** 一次扫描的全部结果 */
    public static final class Result {
        public long total;                      // 含子目录的总字节
        public int fileCount;                   // 文件总数
        public int dirCount;                    // 目录总数（不含 root 自身）
        public final List<DirStat> subDirs = new ArrayList<DirStat>();  // root 直接子目录，按大小降序
        public final List<FileSize> top = new ArrayList<FileSize>();    // 全局最大 topN，降序
    }

    /**
     * 扫描进度回调。total 为预统计得到的文件数（阶段 1 未完成时为 -1），
     * done 为已处理文件数，bytes 为已统计字节。回调在扫描线程，非 UI 线程。
     */
    public interface ScanProgress {
        void onProgress(int done, int total, long bytes);
        boolean isCancelled();
    }

    /** 内部取消信号：避免每层都返回标志位，递归里直接中断 */
    private static final class Cancelled extends RuntimeException {
        Cancelled() { super("scan cancelled"); }
    }

    /** 递归累加用的小载体，避免高频分配数组 */
    private static final class Acc {
        long size;
        int files;
    }

    /**
     * 单遍扫描：目录总大小 + 直接子目录占用 + 全局最大 topN 文件。
     * 相比旧实现（scanSize 一遍 + topFiles 再一遍）少遍历一次元数据，
     * 且通过 countEntries 预统计给出真实的百分比进度。
     * 被取消时返回 null。
     */
    public static Result analyze(File root, int topN, ScanProgress p) {
        Result r = new Result();
        if (root == null || !root.exists()) return r;
        int[] cnt = new int[2];                 // cnt[0]=文件数 cnt[1]=目录数
        try {
            countEntries(root, cnt, p);
            int total = cnt[0];
            if (p != null) p.onProgress(0, total, 0);
            PriorityQueue<FileSize> pq = new PriorityQueue<FileSize>(Math.max(1, topN) + 1,
                    new Comparator<FileSize>() {
                        public int compare(FileSize a, FileSize b) {
                            return Long.compare(a.size, b.size);
                        }
                    });
            int[] done = new int[1];
            long[] bytes = new long[1];
            Acc acc = new Acc();
            walk(root, true, r, pq, topN, total, done, bytes, p, acc);
            r.total = acc.size;
            r.top.addAll(pq);
            java.util.Collections.sort(r.top, new Comparator<FileSize>() {
                public int compare(FileSize a, FileSize b) { return Long.compare(b.size, a.size); }
            });
            java.util.Collections.sort(r.subDirs, new Comparator<DirStat>() {
                public int compare(DirStat a, DirStat b) { return Long.compare(b.size, a.size); }
            });
            return r;
        } catch (Cancelled c) {
            return null;
        }
    }

    /** 阶段 1：只数条目，不做 length() 统计，速度较快，用于给进度条一个真实分母 */
    private static void countEntries(File dir, int[] out, ScanProgress p) {
        countEntries(dir, out, p, 0);
    }

    private static void countEntries(File dir, int[] out, ScanProgress p, int depth) {
        if (depth > TreeOps.MAX_DEPTH) return;
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            if (p != null && p.isCancelled()) throw new Cancelled();
            // v1.97.0：链接不跟进（目标会被另一个入口统计到，跟进等于重复计数 + 环）。
            // 这里是「分母」，链接本身要计入条目数 —— 属于计数策略，不能套 LinkUtil.listNoFollow
            if (LinkUtil.isLink(c)) { out[0]++; continue; }
            if (c.isDirectory()) {
                out[1]++;
                countEntries(c, out, p, depth + 1);
            } else {
                out[0]++;
                if (p != null && (out[0] & 1023) == 0) p.onProgress(-1, out[0], 0);
            }
        }
    }

    /** 阶段 2：一次遍历同时得到大小、子目录占用、Top N */
    private static void walk(File dir, boolean isRoot, Result r, PriorityQueue<FileSize> pq,
            int topN, int total, int[] done, long[] bytes, ScanProgress p, Acc out) {
        walk(dir, isRoot, r, pq, topN, total, done, bytes, p, out, 0);
    }

    private static void walk(File dir, boolean isRoot, Result r, PriorityQueue<FileSize> pq,
            int topN, int total, int[] done, long[] bytes, ScanProgress p, Acc out, int depth) {
        if (depth > TreeOps.MAX_DEPTH) { out.size = 0; out.files = 0; return; }
        File[] cs = dir.listFiles();
        if (cs == null) { out.size = 0; out.files = 0; return; }
        long size = 0;
        int files = 0;
        for (File c : cs) {
            if (p != null && p.isCancelled()) throw new Cancelled();
            if (LinkUtil.isLink(c)) {
                // v1.97.0：链接不进递归，按「0 字节文件」计入条目数（真实大小归目标所有）。
                // 同 countEntries：这是计数策略而非遍历策略，所以不用 listNoFollow
                r.fileCount++;
                files++;
                done[0]++;
                continue;
            }
            if (c.isDirectory()) {
                r.dirCount++;
                Acc sub = new Acc();
                walk(c, false, r, pq, topN, total, done, bytes, p, sub, depth + 1);
                size += sub.size;
                files += sub.files;
                if (isRoot) {
                    DirStat s = new DirStat();
                    s.dir = c;
                    s.size = sub.size;
                    s.fileCount = sub.files;
                    r.subDirs.add(s);
                }
            } else {
                long len = c.length();
                size += len;
                files++;
                r.fileCount++;
                bytes[0] += len;
                done[0]++;
                pushTop(pq, c, len, topN);
                if (p != null && (done[0] & 63) == 0) p.onProgress(done[0], total, bytes[0]);
            }
        }
        out.size = size;
        out.files = files;
    }

    /** 维护容量为 topN 的小顶堆（堆顶是当前第 N 大） */
    private static void pushTop(PriorityQueue<FileSize> pq, File f, long len, int topN) {
        if (topN <= 0) return;
        if (pq.size() < topN) {
            pq.add(new FileSize(f, len));
        } else if (len > pq.peek().size) {
            pq.poll();
            pq.add(new FileSize(f, len));
        }
    }

    /** 递归统计目录大小；onDir 每个子目录回调（构建占用树），可空 */
    public interface DirVisitor { void onDir(File dir, long size, int fileCount); }

    public static long scanSize(File dir, DirVisitor v) {
        return scanSize(dir, v, 0);
    }

    private static long scanSize(File dir, DirVisitor v, int depth) {
        if (depth > TreeOps.MAX_DEPTH) return 0;
        // v1.99.1：遍历一律不跟进链接，规则收在 LinkUtil（见 listNoFollow 注释）
        File[] cs = LinkUtil.listNoFollow(dir);
        if (cs == null) return 0;
        long total = 0;
        int files = 0;
        List<File> subDirs = new ArrayList<File>();
        for (File c : cs) {
            if (c.isDirectory()) {
                subDirs.add(c);
                total += scanSize(c, v, depth + 1);
            } else {
                total += c.length();
                files++;
            }
        }
        if (v != null) v.onDir(dir, total, files);
        return total;
    }

    /**
     * 递归收集最大的 n 个文件。
     * <p>v1.97.0：软链接一律跳过 —— 原注释写「跳过符号链接不适用 File API」，但代码并没有跳，
     * 而是跟进到了目标（同一批文件被重复计入 Top N，链接成环时还会递归爆栈）。</p>
     */
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
        collect(dir, pq, n, 0);
    }

    private static void collect(File dir, java.util.PriorityQueue<FileSize> pq, int n, int depth) {
        if (depth > TreeOps.MAX_DEPTH) return;
        File[] cs = LinkUtil.listNoFollow(dir);
        if (cs == null) return;
        for (File c : cs) {
            if (c.isDirectory()) {
                collect(c, pq, n, depth + 1);
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
        collectFiles(dir, out, 0);
    }

    private static void collectFiles(File dir, List<File> out, int depth) {
        if (depth > TreeOps.MAX_DEPTH) return;
        // 链接不跟进，否则同一文件会被判重（规则见 LinkUtil.listNoFollow）
        File[] cs = LinkUtil.listNoFollow(dir);
        if (cs == null) return;
        for (File c : cs) {
            if (c.isDirectory()) collectFiles(c, out, depth + 1);
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
