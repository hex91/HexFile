package com.hex91.file;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.util.List;

import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.ZstdInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Zstd 压缩/解压（v1.36.0）——基于开源 zstd-jni（BSD-2，Android 走官方 AAR natives）
 * 与 commons-compress TAR，不重复造轮子。
 *
 * - 压缩：单文件 → <名>.zst（纯 zstd frame）；目录/多源 → <名>.tar.zst（TAR 流过 ZstdOutputStream）
 * - 解压：支持 .zst / .tar.zst / .tzst；流式读前 512 字节探测 ustar 魔数自动解包
 * - 默认压缩级别 3（MT 同默认）；级别 1..19
 */
public class ZstdUtil {

    public static final int DEFAULT_LEVEL = 3;
    private static final byte[] ZSTD_MAGIC = {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};

    /** 按文件名判断是否 zstd 产物（.zst / .tar.zst / .tzst） */
    public static boolean isZstName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".zst") || n.endsWith(".tzst");
    }

    /** 按魔数（28 B5 2F FD）判断文件内容是否 zstd frame */
    public static boolean isZst(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[4];
            int off = 0;
            while (off < 4) {
                int r = in.read(b, off, 4 - off);
                if (r < 0) break;
                off += r;
            }
            if (off < 4) return false;
            for (int i = 0; i < 4; i++) if (b[i] != ZSTD_MAGIC[i]) return false;
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ig) { }
        }
    }

    /** 去掉 .tar.zst / .tzst / .zst 后缀，得到还原基础名 */
    public static String stripName(String name) {
        String n = name;
        if (n.toLowerCase().endsWith(".tar.zst")) return n.substring(0, n.length() - 8);
        if (n.toLowerCase().endsWith(".tzst")) return n.substring(0, n.length() - 5);
        if (n.toLowerCase().endsWith(".zst")) return n.substring(0, n.length() - 4);
        return n;
    }

    /**
     * 压缩。单文件 → out（纯 frame，建议命名 <名>.zst）；
     * 目录或多个来源 → out（TAR+Zstd，建议命名 <名>.tar.zst）。
     */
    public static void compress(File out, List<File> sources, int level) throws IOException {
        if (sources == null || sources.isEmpty())
            throw new IOException("没有可压缩的内容");
        if (level < 1) level = 1;
        if (level > 19) level = 19;
        boolean single = sources.size() == 1 && sources.get(0).isFile();
        if (single) {
            compressFrame(out, sources.get(0), level);
        } else {
            compressTarZst(out, sources, level);
        }
    }

    /** 单文件 → 纯 zstd frame（.zst） */
    private static void compressFrame(File out, File src, int level) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        try {
            ZstdOutputStream zos = new ZstdOutputStream(
                    new BufferedOutputStream(new FileOutputStream(out)), level);
            try {
                copyStream(fis, zos);
            } finally {
                zos.close();
            }
        } finally {
            fis.close();
        }
    }

    /** 目录/多源 → TAR 流过 ZstdOutputStream（.tar.zst） */
    private static void compressTarZst(File out, List<File> sources, int level) throws IOException {
        ZstdOutputStream zos = new ZstdOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)), level);
        TarArchiveOutputStream tos =
                new TarArchiveOutputStream(new BufferedOutputStream(zos), "UTF-8");
        tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        try {
            for (File s : sources) tarAdd(tos, s, s.getParentFile());
        } finally {
            tos.close(); // finish 并关闭底层 ZstdOutputStream
        }
    }

    private static void tarAdd(TarArchiveOutputStream tos, File file, File base) throws IOException {
        String rel = file.getName(); // 相对各自父目录（多源时保留顶层名）
        if (file.getParentFile() != null && base != null && !file.equals(base)) {
            String p = file.getCanonicalPath();
            String b = base.getCanonicalPath() + File.separator;
            if (p.startsWith(b)) rel = p.substring(b.length()).replace('\\', '/');
        }
        if (file.isDirectory()) {
            if (!rel.endsWith("/")) rel += "/";
            tos.putArchiveEntry(new TarArchiveEntry(file, rel));
            tos.closeArchiveEntry();
            File[] kids = file.listFiles();
            if (kids != null) for (File k : kids) tarAdd(tos, k, base);
        } else {
            TarArchiveEntry e = new TarArchiveEntry(file, rel);
            tos.putArchiveEntry(e);
            FileInputStream fis = new FileInputStream(file);
            copyStream(fis, tos);
            fis.close();
            tos.closeArchiveEntry();
        }
    }

    /**
     * 解压到 outDir（不存在则创建）。流式探测：解帧头 512 字节判断 ustar；
     * 是 tar → 解包整个 TAR；否则 → 单文件还原（名 = 去后缀）。
     * 返回实际输出（目录或文件）。
     */
    public static File extract(File src, File outDir) throws IOException {
        if (!isZst(src)) throw new IOException("不是 zstd 文件（魔数不符）: " + src.getName());
        if (!outDir.exists() && !outDir.mkdirs())
            throw new IOException("无法创建输出目录: " + outDir);

        ZstdInputStream zis = new ZstdInputStream(
                new BufferedInputStream(new FileInputStream(src)));
        try {
            byte[] head = new byte[512];
            int off = 0;
            while (off < 512) {
                int r = zis.read(head, off, 512 - off);
                if (r < 0) break;
                off += r;
            }
            boolean isTar = off >= 512 && "ustar".getBytes("UTF-8")[0] == head[257]
                    && "ustar".equals(new String(head, 257, 5, "UTF-8"));
            if (isTar) {
                return unpackTar(new SequenceInputStream(
                        new ByteArrayInputStream(head, 0, off), zis), outDir);
            } else {
                File out = new File(outDir, stripName(src.getName()));
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    if (off > 0) fos.write(head, 0, off);
                    copyStream(zis, fos);
                } finally {
                    fos.close();
                }
                return out;
            }
        } finally {
            zis.close();
        }
    }

    private static File unpackTar(InputStream in, File outDir) throws IOException {
        TarArchiveInputStream tis = new TarArchiveInputStream(in, "UTF-8");
        try {
            TarArchiveEntry e;
            while ((e = tis.getNextTarEntry()) != null) {
                String name = safeName(e.getName());
                if (name == null || name.isEmpty()) continue;
                File f = new File(outDir, name);
                String outCanon = outDir.getCanonicalPath();
                String fCanon = f.getCanonicalPath();
                if (!fCanon.startsWith(outCanon + File.separator) && !fCanon.equals(outCanon))
                    continue; // 防路径穿越
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    try { copyStream(tis, fos); } finally { fos.close(); }
                }
            }
        } finally {
            tis.close();
        }
        return outDir;
    }

    /** 规范化 tar 条目名：去前导 ./ 与 /，拒绝 ../ */
    private static String safeName(String raw) {
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        if (n.contains("../") || n.equals("..") || n.contains("/../")) return null;
        return n;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
