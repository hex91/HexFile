package com.hex91.file.fs;

import com.hex91.file.utils.HashUtil;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对比工具核心（v1.17.0）：
 *  - compareDirs：递归对比两个目录，按相对路径分类 相同/已修改/仅A有/仅B有
 *  - 快速判定：size + lastModified 不同 → 已修改；可选哈希校验（同大小同时间也验内容）
 *  - compareEntries：zip/APK 条目级对比（名称+大小），用于 APK 对比模式
 *  - 大目录保护：条目上限 20000，超出截断并标记 truncated
 */
public final class CompareUtil {

    public static final int SAME = 0;      // 相同
    public static final int MODIFIED = 1;  // 已修改（内容/时间不同）
    public static final int ADDED = 2;     // 仅 B 有（新增）
    public static final int DELETED = 3;   // 仅 A 有（删除）

    public static final int MAX_ITEMS = 20000;

    /**
     * 差异说明的类型码。{@link Item} 只给码和必要数据，面向用户的说明由 ui.CompareReport 生成
     * （逻辑层不产文案）。
     */
    public static final int NOTE_NONE = 0;
    /** 大小不同：sizeA → sizeB */
    public static final int NOTE_SIZE = 1;
    /** 大小相同但内容不同：noteA/noteB 为两侧 brief 哈希 */
    public static final int NOTE_HASH = 2;
    /** 大小相同、内容未校验但修改时间不同 */
    public static final int NOTE_TIME = 3;
    /** 归档条目同长度但 CRC32 不同 */
    public static final int NOTE_CRC = 4;

    /** 一条对比结果 */
    public static class Item {
        public String relPath;          // 相对路径（/ 分隔）
        public boolean dir;
        public long sizeA = -1, sizeB = -1;
        /** v2.12.0（F-12）：归档条目对比的 CRC32（-1 = 无此信息；普通目录对比一直是 -1） */
        public long crcA = -1, crcB = -1;
        public long timeA, timeB;
        public int status;
        /** 差异说明类型（NOTE_*）；具体文案由 ui.CompareReport.note() 生成 */
        public int noteKind = NOTE_NONE;
        /** NOTE_HASH 用：两侧 brief 哈希（可能为 null = 该侧读取失败） */
        public String noteA, noteB;

        public Item(String relPath, boolean dir) {
            this.relPath = relPath;
            this.dir = dir;
        }

        public boolean diff() { return status != SAME; }
    }

    /** 对比结果汇总 */
    public static class Result {
        public String rootA = "", rootB = "";
        public List<Item> items = new ArrayList<Item>();
        public int nSame, nModified, nAdded, nDeleted;
        public long bytesA, bytesB;             // 两侧文件总字节
        public int nDirsA, nDirsB, nFilesA, nFilesB;
        public boolean hashVerified = false;    // 是否做了内容哈希校验
        public boolean truncated = false;       // 条目超上限被截断
        public String error = null;             // 遍历错误（权限等），非致命
    }

    private CompareUtil() {}

    /** 递归收集目录内所有条目（相对路径 → File），跳过符号链接循环（File 层不跟随链接） */
    private static void walk(File root, final Map<String, File> out, final boolean[] stop)
            throws IOException {
        walkInto(root, "", out, stop);
    }

    /** 递归实现：prefix 为已积累的相对路径（"" = 根层） */
    private static void walkInto(File dir, String prefix, Map<String, File> out, boolean[] stop)
            throws IOException {
        File[] kids = dir.listFiles(new FileFilter() {
            public boolean accept(File f) { return true; }
        });
        if (kids == null) throw new IOException("cannot read: " + dir.getAbsolutePath());
        for (File k : kids) {
            if (out.size() >= MAX_ITEMS) return;
            String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
            out.put(rel, k);
            if (k.isDirectory()) walkInto(k, rel, out, stop);
        }
    }

    /**
     * 对比两个目录（耗时操作，放后台线程）。
     * @param verifyHash true = 对 size 相同且 mtime 相同的文件也做 SHA-256 内容校验
     */
    public static Result compareDirs(File a, File b, boolean verifyHash) {
        Result r = new Result();
        r.rootA = a.getAbsolutePath();
        r.rootB = b.getAbsolutePath();
        r.hashVerified = verifyHash;
        Map<String, File> ma = new HashMap<String, File>();
        Map<String, File> mb = new HashMap<String, File>();
        try {
            walk(a, ma, null);
            walk(b, mb, null);
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        r.truncated = ma.size() + mb.size() >= MAX_ITEMS;

        // 并集相对路径
        Map<String, Item> merged = new HashMap<String, Item>();
        for (Map.Entry<String, File> e : ma.entrySet()) {
            File f = e.getValue();
            Item it = new Item(e.getKey(), f.isDirectory());
            it.sizeA = f.isDirectory() ? 0 : f.length();
            it.timeA = f.lastModified();
            merged.put(e.getKey(), it);
        }        for (Map.Entry<String, File> e : mb.entrySet()) {
            File f = e.getValue();
            Item it = merged.get(e.getKey());
            if (it == null) {
                it = new Item(e.getKey(), f.isDirectory());
                merged.put(e.getKey(), it);
            }
            it.sizeB = f.isDirectory() ? 0 : f.length();
            it.timeB = f.lastModified();
        }
        for (Item it : merged.values()) {
            if (it.sizeA < 0) { it.status = ADDED; r.nAdded++; }
            else if (it.sizeB < 0) { it.status = DELETED; r.nDeleted++; }
            else if (it.dir) { it.status = SAME; r.nSame++; }
            else if (it.sizeA != it.sizeB) {
                // 大小不同必为修改（哈希模式下也先看大小，省一次全量哈希）
                it.status = MODIFIED;
                it.noteKind = NOTE_SIZE;
                r.nModified++;
            } else if (verifyHash) {
                // 大小相同：以内容为准（SHA-256），mtime 差异不误判
                String ha = HashUtil.hash(new File(a, it.relPath), "SHA-256");
                String hb = HashUtil.hash(new File(b, it.relPath), "SHA-256");
                if (ha != null && ha.equals(hb)) { it.status = SAME; r.nSame++; }
                else {
                    it.status = MODIFIED;
                    it.noteKind = NOTE_HASH;
                    it.noteA = HashUtil.brief(ha);
                    it.noteB = HashUtil.brief(hb);
                    r.nModified++;
                }
            } else if (it.timeA != it.timeB) {
                it.status = MODIFIED;
                it.noteKind = NOTE_TIME;
                r.nModified++;
            } else { it.status = SAME; r.nSame++; }
            if (it.status != SAME) r.items.add(it);
        }
        // 统计（含相同项）
        for (Map.Entry<String, File> e : ma.entrySet()) {
            if (e.getValue().isDirectory()) r.nDirsA++;
            else { r.nFilesA++; r.bytesA += e.getValue().length(); }
        }
        for (Map.Entry<String, File> e : mb.entrySet()) {
            if (e.getValue().isDirectory()) r.nDirsB++;
            else { r.nFilesB++; r.bytesB += e.getValue().length(); }
        }
        // 排序：差异项 → 目录优先 → 路径字典序
        Collections.sort(r.items, new Comparator<Item>() {
            public int compare(Item x, Item y) {
                if (x.dir != y.dir) return x.dir ? -1 : 1;
                return x.relPath.compareTo(y.relPath);
            }
        });
        return r;
    }

    /** 归档条目对比输入（名称已归一化为 / 分隔，size 为解压后大小） */
    public static class ArchEntry {
        public String name;
        public long size;
        public boolean dir;
        /**
         * v2.12.0（F-12）：条目 CRC32；-1 表示来源没给（非 zip，或读取失败）。
         *
         * <p>zip 的中央目录里现成带着 CRC32，读它不需要多解一次压。之所以要比它，
         * 是因为「只比 size」会漏掉最常见的一类改动：**改了内容但长度没变**——
         * 换一张同尺寸图标、改一个 smali 常量、替换 resources.arsc 里的同长字符串，
         * size 一模一样，对比结果却显示「完全相同」。这正是 APK 对比最容易骗人的地方。</p>
         */
        public long crc = -1;

        public ArchEntry(String name, long size, boolean dir) {
            this(name, size, dir, -1);
        }

        public ArchEntry(String name, long size, boolean dir, long crc) {
            this.name = name; this.size = size; this.dir = dir; this.crc = crc;
        }
    }

    /**
     * 对比两个归档（zip/APK）的条目清单（耗时操作，放后台线程）。
     * 目录条目（name 以 / 结尾或 dir=true）单独归类；size 不同 → 已修改；size 相同但 CRC32 不同 → 也已修改（v2.12.0 F-12；两侧都没有 CRC 时退化为只比 size）。
     */
    public static Result compareEntries(List<ArchEntry> la, List<ArchEntry> lb) {
        Result r = new Result();
        Map<String, Item> merged = new HashMap<String, Item>();
        for (ArchEntry e : la) {
            String key = e.dir ? e.name + "/" : e.name;
            Item it = new Item(key, e.dir);
            it.sizeA = e.dir ? 0 : e.size;
            it.crcA = e.dir ? -1 : e.crc;
            merged.put(key, it);
        }
        for (ArchEntry e : lb) {
            String key = e.dir ? e.name + "/" : e.name;
            Item it = merged.get(key);
            if (it == null) {
                it = new Item(key, e.dir);
                merged.put(key, it);
            }
            it.sizeB = e.dir ? 0 : e.size;
            it.crcB = e.dir ? -1 : e.crc;
        }
        for (Item it : merged.values()) {
            if (it.sizeA < 0) { it.status = ADDED; r.nAdded++; }
            else if (it.sizeB < 0) { it.status = DELETED; r.nDeleted++; }
            else if (it.dir) { it.status = SAME; r.nSame++; }
            else if (it.sizeA != it.sizeB) {
                it.status = MODIFIED;
                it.noteKind = NOTE_SIZE;
                r.nModified++;
            } else if (it.crcA >= 0 && it.crcB >= 0 && it.crcA != it.crcB) {
                // 同长度、不同内容：只比 size 看不见的那一类（F-12）
                it.status = MODIFIED;
                it.noteKind = NOTE_CRC;
                r.nModified++;
            } else { it.status = SAME; r.nSame++; }
            if (it.status != SAME) r.items.add(it);
        }
        Collections.sort(r.items, new Comparator<Item>() {
            public int compare(Item x, Item y) {
                if (x.dir != y.dir) return x.dir ? -1 : 1;
                return x.relPath.compareTo(y.relPath);
            }
        });
        return r;
    }

    /** 从 zip 文件读取条目清单（名称+解压大小+是否目录） */
    public static List<ArchEntry> readZipEntries(File zip) {
        List<ArchEntry> out = new ArrayList<ArchEntry>();
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(zip);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                out.add(new ArchEntry(e.getName(), e.getSize(), e.isDirectory(), e.getCrc()));
            }
        } catch (Exception e) {
            // 读失败返回空列表，UI 提示
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    public static String fmtSize(long len) {
        if (len < 1024) return len + " B";
        if (len < 1048576) return String.format(java.util.Locale.US, "%.1f KB", len / 1024.0);
        if (len < 1073741824) return String.format(java.util.Locale.US, "%.1f MB", len / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", len / 1073741824.0);
    }
}
