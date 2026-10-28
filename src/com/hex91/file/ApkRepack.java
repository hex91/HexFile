package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK 重打包（v1.9，ARSC 编辑器保存用）：
 *   1. 复制全部条目，跳过旧签名文件（META-INF/*.SF|*.RSA|*.DSA|*.EC|MANIFEST.MF）；
 *   2. replace 中的条目（如 resources.arsc）用新内容替换；
 *   3. 保持条目原压缩方式；原本 STORED 的条目（含 extractNativeLibs=false 直载的 .so）
 *      用 extra 字段补零对齐到 4096（PackageManager 对直载 so 有硬性对齐要求）；
 *   4. 输出为“未签名”apk，交由 ApkSignerTool 签 v1+v2+v3。
 *
 * 注意：ZipOutputStream 对 DEFLATED 条目会写 16 字节 data descriptor，
 * 手工累加偏移会漂移——必须用计数流读真实位置。
 */
public final class ApkRepack {

    private static final int ALIGN = 4096;

    public static void repack(File srcApk, Map<String, byte[]> replace, File outApk) throws IOException {
        repack(srcApk, replace, java.util.Collections.<String>emptySet(), outApk);
    }

    /**
     * @param remove 需从产物中剔除的条目名（DEX 编辑回写：多 dex 合并后移除多余的 classes2..N.dex）
     */
    public static void repack(File srcApk, Map<String, byte[]> replace, java.util.Set<String> remove, File outApk) throws IOException {
        ZipFile zf = new ZipFile(srcApk);
        CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(outApk));
        ZipOutputStream zo = new ZipOutputStream(cos);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (isOldSignature(name)) continue;
                if (remove.contains(name)) continue;

                byte[] data;
                if (replace.containsKey(name)) {
                    data = replace.get(name);
                } else {
                    data = readAll(zf.getInputStream(ze));
                }
                byte[] nameBytes = name.getBytes("UTF-8");
                // 保持原压缩方式：原本 STORED 的 .so（extractNativeLibs=false 直载）需 4096 对齐；
                // 原本 DEFLATED 的 .so（安装时解压）保持 DEFLATED，避免体积翻倍
                boolean store = ze.getMethod() == ZipEntry.STORED;

                ZipEntry ne = new ZipEntry(name);
                // 时间戳钳制到 DOS 合法区间（1980-2099）：否则 ZipEntry.setTime 会写入
                // mtime，ZipOutputStream 额外插入 9 字节 EXTT 扩展时间戳，破坏 4096 对齐计算
                long t = ze.getTime();
                if (t < 315532800000L) t = 315532800000L;       // 1980-01-01
                if (t > 4102444800000L) t = 4102444800000L;     // 2099-12-31
                ne.setTime(t);
                if (store) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ne.setCrc(crc.getValue());
                    // 数据起点 = 当前位置 + 30 + nameLen + extraLen，对齐到 4096
                    long posBefore = cos.count;
                    int pad = (int) ((ALIGN - ((posBefore + 30 + nameBytes.length) % ALIGN)) % ALIGN);
                    ne.setExtra(new byte[pad]);
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                zo.putNextEntry(ne);
                zo.write(data);
                zo.closeEntry();
            }
        } finally {
            zf.close();
            zo.close();
            cos.close();
        }
    }

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String base = name.substring("META-INF/".length());
        return base.equals("MANIFEST.MF")
                || base.endsWith(".SF") || base.endsWith(".RSA")
                || base.endsWith(".DSA") || base.endsWith(".EC");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream out) { super(out); }
        public void write(int b) throws IOException { out.write(b); count++; }
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }
}
