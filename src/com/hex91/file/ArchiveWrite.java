package com.hex91.file;

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
 * 归档统一写后端（v1.16）：按扩展名把「写条目 / 删条目」分派到具体格式。
 *   .zip/.apk/.jar -> ZIP（ZipUtil，重建式；APK 自动剥旧签名 + STORED 对齐）
 *   .tar/.tgz/.tar.gz -> TAR（commons-compress，重建式）
 *   .7z            -> 7z（commons-compress SevenZOutputFile，重建式，LZMA2；v1.16 起可写）
 *   .rar           -> 专有格式，全球没有任何免费/付费通用库支持写入（MT 亦只读），
 *                     UI 侧提供「转存为 ZIP」绕行。
 * 只读格式由调用方先用 canWrite 判断并给用户说明，不会走到写路径。
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
        if (isTar(archive)) return TarArc.writeEntry(archive, entryName, data);
        if (is7z(archive)) return SevenZArc.writeEntry(archive, entryName, data);
        return ZipUtil.writeEntry(archive, entryName, data);
    }

    /** 写入或替换归档内单个条目（文件流式写入，避免大文件整体进内存） */
    public static boolean writeEntry(File archive, String entryName, File dataFile) {
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

    private static boolean isTar(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase();
        return n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz");
    }

    private static boolean is7z(File f) {
        return f != null && f.getName().toLowerCase().endsWith(".7z");
    }

    // ===================== 7Z 后端（SevenZOutputFile 重建式，v1.16） =====================

    private static final class SevenZArc {

        static boolean writeEntry(File sz, String entryName, byte[] data) {
            InputStream in = new ByteArrayInputStream(data);
            try {
                return rebuild(sz, entryName, null, in, data.length);
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
                return rebuild(sz, entryName, null, in, dataFile.length());
            } finally {
                closeQuiet(in);
            }
        }

        static boolean removeEntries(File sz, List<String> entryNames) {
            try {
                Set<String> kill = new HashSet<String>();
                for (String n : entryNames) {
                    kill.add(n);
                    if (!n.endsWith("/")) kill.add(n + "/");
                }
                return rebuild(sz, null, kill, null, 0);
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 重建式写 7z：全部旧条目（除被替换/删除的）经 SevenZFile 读出，
         * 用 SevenZOutputFile 以 LZMA2 重写（内容方法与原压缩方法无关）。
         * replace != null = 写入模式；kill != null = 删除模式。
         */
        private static boolean rebuild(File sz, String replace, Set<String> kill,
                InputStream data, long size) {
            try {
                File tmp = tempFile(sz);
                try (SevenZFile in = new SevenZFile(sz);
                     SevenZOutputFile out = new SevenZOutputFile(tmp)) {
                    for (SevenZArchiveEntry e : in.getEntries()) {
                        String n = e.getName();
                        if (replace != null && n.equals(replace)) continue;   // 旧内容跳过
                        if (kill != null && (kill.contains(n) || prefixHit(kill, n))) continue;
                        SevenZArchiveEntry ne = new SevenZArchiveEntry();
                        ne.setName(n);
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
                    if (replace != null) {
                        SevenZArchiveEntry ne = new SevenZArchiveEntry();
                        ne.setName(replace);
                        ne.setDirectory(false);
                        ne.setSize(Math.max(size, 0));
                        ne.setLastModifiedDate(new Date());
                        out.putArchiveEntry(ne);
                        if (data != null && size > 0) {
                            byte[] buf = new byte[65536];
                            int k;
                            while ((k = data.read(buf)) > 0) out.write(buf, 0, k);
                        }
                        out.closeArchiveEntry();
                    }
                }
                return replaceOriginal(sz, tmp);
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
            return new File(sz.getParentFile(),
                    sz.getName() + ".reb" + System.currentTimeMillis() + ".tmp");
        }

        private static boolean replaceOriginal(File sz, File tmp) {
            if (!tmp.renameTo(sz)) {
                if (sz.delete() && tmp.renameTo(sz)) return true;
                tmp.delete();
                return false;
            }
            return true;
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
                return writeEntry(tar, entryName, in, data.length);
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
                return writeEntry(tar, entryName, in, dataFile.length());
            } finally {
                closeQuiet(in);
            }
        }

        private static boolean writeEntry(File tar, String entryName,
                InputStream data, long size) {
            try {
                File tmp = tempFile(tar);
                try (TarArchiveInputStream in = openIn(tar);
                     OutputStream fos = new FileOutputStream(tmp);
                     OutputStream base = gz(tar) ? new GZIPOutputStream(fos) : fos;
                     TarArchiveOutputStream out = new TarArchiveOutputStream(base)) {
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        if (e.getName().equals(entryName)) continue;   // 旧内容跳过
                        copyEntry(in, e, out);
                    }
                    putData(out, entryName, data, size);
                }
                return replaceOriginal(tar, tmp);
            } catch (Exception e) {
                return false;
            }
        }

        static boolean removeEntries(File tar, List<String> entryNames) {
            try {
                Set<String> kill = new HashSet<>();
                for (String n : entryNames) {
                    kill.add(n);
                    if (!n.endsWith("/")) kill.add(n + "/");
                }
                File tmp = tempFile(tar);
                try (TarArchiveInputStream in = openIn(tar);
                     OutputStream fos = new FileOutputStream(tmp);
                     OutputStream base = gz(tar) ? new GZIPOutputStream(fos) : fos;
                     TarArchiveOutputStream out = new TarArchiveOutputStream(base)) {
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        String n = e.getName();
                        boolean hit = false;
                        for (String k : kill) {
                            if (n.equals(k) || n.startsWith(k)) { hit = true; break; }
                        }
                        if (hit) continue;
                        copyEntry(in, e, out);
                    }
                }
                return replaceOriginal(tar, tmp);
            } catch (Exception e) {
                return false;
            }
        }

        private static TarArchiveInputStream openIn(File tar) throws IOException {
            InputStream raw = new FileInputStream(tar);
            InputStream base = gz(tar) ? new GZIPInputStream(raw) : raw;
            return new TarArchiveInputStream(base);
        }

        /** 原条目按原大小/时间复制到新 tar */
        private static void copyEntry(TarArchiveInputStream in, TarArchiveEntry e,
                TarArchiveOutputStream out) throws IOException {
            TarArchiveEntry ne = new TarArchiveEntry(e.getName());
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
            return new File(tar.getParentFile(),
                    tar.getName() + ".reb" + System.currentTimeMillis() + ".tmp");
        }

        private static boolean replaceOriginal(File tar, File tmp) {
            if (!tmp.renameTo(tar)) {
                if (tar.delete() && tmp.renameTo(tar)) return true;
                tmp.delete();
                return false;
            }
            return true;
        }

        private static void closeQuiet(InputStream in) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
