package com.hex91.file.apk;

import com.hex91.file.core.EntryNames;
import com.hex91.file.fs.TempFiles;
import com.hex91.file.fs.ZipUtil;
import com.hex91.file.core.AtomicReplace;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 归档统一写后端（v1.16）：按扩展名把「写条目 / 删条目 / 重命名 / 新建目录」分派到具体格式。
 *   .zip/.apk/.jar -> ZIP（ZipUtil，重建式；APK 自动剥旧签名 + STORED 对齐）
 *   .tar/.tgz/.tar.gz -> TAR（commons-compress，重建式）
 *   .7z            -> 7z（commons-compress SevenZOutputFile，重建式，LZMA2；v1.16 起可写）
 *   .rar           -> 专有格式，全球没有任何免费/付费通用库支持写入（MT 亦只读），
 *                     UI 侧提供「转存为 ZIP」绕行。
 * 只读格式由调用方先用 canWrite 判断并给用户说明，不会走到写路径。
 *
 * <p>v2.13.1（F-11）：补上重命名与新建目录。tar / 7z 都是「读旧 → 写新」的重建式后端，
 * 原来「写」有一份循环、「删」又各有一份，逻辑几乎一样；这一轮把四种操作收进 {@link Edit}，
 * 每个后端只留一个循环 —— 否则「重命名时的跳过条件」和「删除时的跳过条件」迟早分叉，
 * 表现成「删得掉但改不动」这类最难查的不一致。</p>
 */
public class ArchiveWrite {

    private ArchiveWrite() {}

    /** 是否支持写入该归档（ZIP/APK/JAR/TAR/TGZ/7Z） */
    public static boolean canWrite(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar")
                || n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz")
                || n.endsWith(".7z");
    }

    /** 写入或替换归档内单个条目（内存数据） */
    public static boolean writeEntry(File archive, String entryName, byte[] data) {
        if (!ZipUtil.entryNameSafe(entryName)) return false;  // v1.66.3 写入侧穿越防护（覆盖 tar/7z）
        if (isTar(archive)) return TarArc.writeEntry(archive, entryName, data);
        if (is7z(archive)) return SevenZArc.writeEntry(archive, entryName, data);
        return ZipUtil.writeEntry(archive, entryName, data);
    }

    /** 写入或替换归档内单个条目（文件流式写入，避免大文件整体进内存） */
    public static boolean writeEntry(File archive, String entryName, File dataFile) {
        if (!ZipUtil.entryNameSafe(entryName)) return false;  // v1.66.3 写入侧穿越防护（覆盖 tar/7z）
        if (isTar(archive)) return TarArc.writeEntry(archive, entryName, dataFile);
        if (is7z(archive)) return SevenZArc.writeEntry(archive, entryName, dataFile);
        return ZipUtil.writeEntry(archive, entryName, dataFile);
    }

    /** 删除归档内一组条目（目录条目自动匹配其下全部子项） */
    public static boolean removeEntries(File archive, List<String> entryNames) {
        if (isTar(archive)) return TarArc.removeEntries(archive, entryNames);
        if (is7z(archive)) return SevenZArc.removeEntries(archive, entryNames);
        return ZipUtil.removeEntries(archive, entryNames);
    }

    /**
     * 重命名 / 移动归档内条目（v2.13.1，F-11）。
     *
     * <p>目录条目按前缀迁移其下全部子项：把 {@code res/layout} 改成 {@code res/ui}，
     * 它下面所有条目一起改名。判据是 {@code from + "/"} 前缀而不是 {@code startsWith(from)}，
     * 否则 {@code res/layoutx/} 会被当成子项一起搬走（见 {@link EntryNames#renamed}）。</p>
     *
     * <p>目标名冲突由调用方先查：UI 手里就有当前目录的条目表，查一次是免费的；
     * 后端不做「合并」—— 真撞了会得到两个同名条目，比直接失败更难收拾。</p>
     */
    public static boolean renameEntry(File archive, String from, String to) {
        if (archive == null || from == null || to == null) return false;
        if (!canWrite(archive)) return false;
        if (!ZipUtil.entryNameSafe(from) || !ZipUtil.entryNameSafe(to)) return false;
        if (EntryNames.stripSlash(from).equals(EntryNames.stripSlash(to))) return true;
        if (isTar(archive)) return TarArc.renameEntry(archive, from, to);
        if (is7z(archive)) return SevenZArc.renameEntry(archive, from, to);
        return ZipUtil.renameEntry(archive, from, to);
    }

    /**
     * 在归档内新建目录条目（v2.13.1，F-11）。
     *
     * <p>三种格式对「目录」的表达不同但都很轻：zip 与 tar 就是「名字以 / 结尾、内容为空」，
     * 7z 有独立的 directory 标志位。父目录不自动补条目 —— 归档不像文件系统那样需要父目录
     * 存在才能放子项，补一堆空目录只会让包变大。</p>
     *
     * <p>目标已存在同样由调用方先查（这里直接写会得到重复条目）。</p>
     */
    public static boolean mkdir(File archive, String dirPath) {
        if (archive == null || !canWrite(archive)) return false;
        String n = EntryNames.dir(dirPath);
        if (n.isEmpty() || !ZipUtil.entryNameSafe(n)) return false;
        if (isTar(archive)) return TarArc.mkdir(archive, n);
        if (is7z(archive)) return SevenZArc.mkdir(archive, n);
        return ZipUtil.writeEntry(archive, n, new byte[0]);
    }

    private static boolean isTar(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz");
    }

    private static boolean is7z(File f) {
        return f != null && f.getName().toLowerCase().endsWith(".7z");
    }

    /**
     * 一次重建要做的修改。四种操作互斥（都为空 = 纯复制，实际不会这样调用）：
     * 重命名 / 删除 / 写入或替换 / 新建目录。
     */
    private static final class Edit {
        String from, to;        // 重命名
        Set<String> kill;       // 删除（含子项）
        String addName;         // 写入、替换或新建
        boolean addDir;         // addName 是目录条目
        InputStream data;
        long size;

        static Edit rename(String from, String to) {
            Edit e = new Edit();
            e.from = from;
            e.to = to;
            return e;
        }

        static Edit kill(Set<String> names) {
            Edit e = new Edit();
            e.kill = names;
            return e;
        }

        static Edit add(String name, boolean dir, InputStream data, long size) {
            Edit e = new Edit();
            e.addName = name;
            e.addDir = dir;
            e.data = data;
            e.size = size;
            return e;
        }
    }

    // ===================== 7Z 后端（SevenZOutputFile 重建式，v1.16） =====================

    private static final class SevenZArc {

        static boolean writeEntry(File sz, String entryName, byte[] data) {
            InputStream in = new ByteArrayInputStream(data);
            try {
                return rebuild(sz, Edit.add(entryName, false, in, data.length));
            } finally {
                closeQuiet(in);
            }
        }

        static boolean writeEntry(File sz, String entryName, File dataFile) {
            InputStream in;
            try {
                in = new FileInputStream(dataFile);
            } catch (IOException e) {
                return false;
            }
            try {
                return rebuild(sz, Edit.add(entryName, false, in, dataFile.length()));
            } finally {
                closeQuiet(in);
            }
        }

        static boolean mkdir(File sz, String dirName) {
            return rebuild(sz, Edit.add(dirName, true, null, 0));
        }

        static boolean renameEntry(File sz, String from, String to) {
            return rebuild(sz, Edit.rename(from, to));
        }

        static boolean removeEntries(File sz, List<String> entryNames) {
            Set<String> kill = new HashSet<String>();
            for (String n : entryNames) {
                kill.add(n);
                if (!n.endsWith("/")) kill.add(n + "/");
            }
            return rebuild(sz, Edit.kill(kill));
        }

        /**
         * 重建式写 7z：全部旧条目（除被替换/删除的）经 SevenZFile 读出，
         * 用 SevenZOutputFile 以 LZMA2 重写（内容方法与原压缩方法无关）。
         * 重命名在复制时直接改名；新增条目追加在末尾。
         */
        private static boolean rebuild(File sz, Edit ed) {
            try {
                File tmp = tempFile(sz);
                int hit = 0;
                try (SevenZFile in = new SevenZFile(sz);
                     SevenZOutputFile out = new SevenZOutputFile(tmp)) {
                    for (SevenZArchiveEntry e : in.getEntries()) {
                        String n = e.getName();
                        if (ed.kill != null && (ed.kill.contains(n) || prefixHit(ed.kill, n))) continue;
                        if (ed.addName != null && n.equals(ed.addName)) continue;  // 旧内容跳过，新内容末尾追加
                        SevenZArchiveEntry ne = new SevenZArchiveEntry();
                        String nn = ed.from == null ? n : EntryNames.renamed(n, ed.from, ed.to);
                        if (!nn.equals(n)) hit++;
                        ne.setName(nn);
                        ne.setDirectory(e.isDirectory());
                        ne.setSize(e.getSize());
                        try {   // 无时间戳的条目会抛 UnsupportedOperationException
                            Date mtime = e.getLastModifiedDate();
                            if (mtime != null) ne.setLastModifiedDate(mtime);
                        } catch (UnsupportedOperationException ignored) { }
                        out.putArchiveEntry(ne);
                        if (!e.isDirectory()) {
                            InputStream is = in.getInputStream(e);
                            byte[] buf = new byte[65536];
                            int k;
                            while ((k = is.read(buf)) > 0) out.write(buf, 0, k);
                            is.close();
                        }
                        out.closeArchiveEntry();
                    }
                    if (ed.addName != null) {
                        SevenZArchiveEntry ne = new SevenZArchiveEntry();
                        ne.setName(ed.addName);
                        ne.setDirectory(ed.addDir);
                        ne.setSize(ed.addDir ? 0 : Math.max(ed.size, 0));
                        ne.setLastModifiedDate(new Date());
                        out.putArchiveEntry(ne);
                        if (!ed.addDir && ed.data != null && ed.size > 0) {
                            byte[] buf = new byte[65536];
                            int k;
                            while ((k = ed.data.read(buf)) > 0) out.write(buf, 0, k);
                        }
                        out.closeArchiveEntry();
                    }
                }
                if (ed.from != null && hit == 0) {
                    // 重命名一条都没匹配上：原包不动，交由调用方报「找不到」
                    tmp.delete();
                    return false;
                }
                // v1.99.2（R-06）：同 ZipUtil，失败原因留日志
                AtomicReplace.Result r = AtomicReplace.replaceWith(tmp, sz);
                if (!r.ok) android.util.Log.w("ArchiveWrite", "写回失败: " + r.message);
                return r.ok;
            } catch (Exception e) {
                // 加密 7z（SevenZFile 打不开）等都会落到这里，返回 false 由 UI 提示
                return false;
            }
        }

        /** 目录删除：kill 集合含 "dir/" 时匹配其下全部子项 */
        private static boolean prefixHit(Set<String> kill, String name) {
            for (String k : kill) {
                if (k.endsWith("/") && name.startsWith(k)) return true;
            }
            return false;
        }

        private static File tempFile(File sz) {
            return TempFiles.rebTmp(sz);
        }

        private static void closeQuiet(InputStream in) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    // ===================== TAR / TGZ 后端（重建式，v1.15） =====================

    private static final class TarArc {

        private static boolean gz(File tar) {
            String n = tar.getName().toLowerCase();
            return n.endsWith(".gz") || n.endsWith(".tgz");
        }

        static boolean writeEntry(File tar, String entryName, byte[] data) {
            InputStream in = new ByteArrayInputStream(data);
            try {
                return rebuild(tar, Edit.add(entryName, false, in, data.length));
            } finally {
                closeQuiet(in);
            }
        }

        static boolean writeEntry(File tar, String entryName, File dataFile) {
            InputStream in;
            try {
                in = new FileInputStream(dataFile);
            } catch (IOException e) {
                return false;
            }
            try {
                return rebuild(tar, Edit.add(entryName, false, in, dataFile.length()));
            } finally {
                closeQuiet(in);
            }
        }

        /** tar 的目录条目就是「名字以 / 结尾、内容为空」的普通条目 */
        static boolean mkdir(File tar, String dirName) {
            return rebuild(tar, Edit.add(dirName, true, null, 0));
        }

        static boolean renameEntry(File tar, String from, String to) {
            return rebuild(tar, Edit.rename(from, to));
        }

        static boolean removeEntries(File tar, List<String> entryNames) {
            Set<String> kill = new HashSet<String>();
            for (String n : entryNames) {
                kill.add(n);
                if (!n.endsWith("/")) kill.add(n + "/");
            }
            return rebuild(tar, Edit.kill(kill));
        }

        private static boolean rebuild(File tar, Edit ed) {
            try {
                File tmp = tempFile(tar);
                int[] hit = {0};
                try (TarArchiveInputStream in = openIn(tar);
                     OutputStream fos = new FileOutputStream(tmp);
                     OutputStream base = gz(tar) ? new GZIPOutputStream(fos) : fos;
                     TarArchiveOutputStream out = new TarArchiveOutputStream(base)) {
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        String n = e.getName();
                        if (ed.kill != null && killHit(ed.kill, n)) continue;
                        if (ed.addName != null && n.equals(ed.addName)) continue;  // 旧内容跳过
                        String nn = ed.from == null ? n : EntryNames.renamed(n, ed.from, ed.to);
                        if (!nn.equals(n)) hit[0]++;
                        copyEntry(in, e, out, nn);
                    }
                    if (ed.addName != null) putData(out, ed.addName, ed.data, ed.size);
                }
                if (ed.from != null && hit[0] == 0) {
                    tmp.delete();      // 重命名没匹配上：原包一个字节都不动
                    return false;
                }
                AtomicReplace.Result r = AtomicReplace.replaceWith(tmp, tar);
                if (!r.ok) android.util.Log.w("ArchiveWrite", "写回失败: " + r.message);
                return r.ok;
            } catch (Exception e) {
                return false;
            }
        }

        /** 删除命中：精确名或子项前缀 */
        private static boolean killHit(Set<String> kill, String name) {
            for (String k : kill) {
                if (name.equals(k) || name.startsWith(k)) return true;
            }
            return false;
        }

        private static TarArchiveInputStream openIn(File tar) throws IOException {
            InputStream raw = new FileInputStream(tar);
            InputStream base = gz(tar) ? new GZIPInputStream(raw) : raw;
            return new TarArchiveInputStream(base);
        }

        /** 原条目按原大小/时间复制到新 tar，条目名用 newName */
        private static void copyEntry(TarArchiveInputStream in, TarArchiveEntry e,
                TarArchiveOutputStream out, String newName) throws IOException {
            TarArchiveEntry ne = new TarArchiveEntry(newName);
            ne.setSize(e.getSize());
            if (e.getLastModifiedDate() != null) ne.setModTime(e.getLastModifiedDate());
            out.putArchiveEntry(ne);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.closeArchiveEntry();
        }

        private static void putData(TarArchiveOutputStream out, String entryName,
                InputStream data, long size) throws IOException {
            TarArchiveEntry ne = new TarArchiveEntry(entryName);
            ne.setSize(size);
            ne.setModTime(new java.util.Date(System.currentTimeMillis()));
            out.putArchiveEntry(ne);
            if (data != null && size > 0) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = data.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.closeArchiveEntry();
        }

        private static File tempFile(File tar) {
            return TempFiles.rebTmp(tar);
        }

        private static void closeQuiet(InputStream in) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
