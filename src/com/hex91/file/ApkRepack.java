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

    /**
     * v1.66.0：备份名统一为 <name>_bak.apk（修改版统一为 <name>_edit.apk）。
     * 备份已存在则直接返回（保留最早的原版，多次修改不会覆盖掉真原包）。
     */
    public static File backupOriginal(File apk) throws IOException {
        String n = apk.getName();
        String base = n.toLowerCase().endsWith(".apk") ? n.substring(0, n.length() - 4) : n;
        File bak = new File(apk.getParentFile(), base + "_bak.apk");
        if (bak.exists()) return bak;
        java.io.InputStream in = new java.io.FileInputStream(apk);
        java.io.FileOutputStream out = new java.io.FileOutputStream(bak);
        byte[] buf = new byte[1 << 16];
        int k;
        try {
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        } finally {
            in.close();
            out.close();
        }
        return bak;
    }

    public static void repack(File srcApk, Map<String, byte[]> replace, File outApk) throws IOException {
        repack(srcApk, replace, java.util.Collections.<String>emptySet(), outApk);
    }

    /**
     * @param remove 需从产物中剔除的条目名（DEX 编辑回写：多 dex 合并后移除多余的 classes2..N.dex）
     */
    public static void repack(File srcApk, Map<String, byte[]> replace, java.util.Set<String> remove, File outApk) throws IOException {
        repack(srcApk, replace, remove, java.util.Collections.<String, File>emptyMap(), outApk);
    }

    /**
     * v1.66.0：支持新增条目（add，压缩包内原本不存在的路径，如往 lib/assets 加文件）。
     * .so 新增按 STORED + 4096 对齐（extractNativeLibs=false 直载要求），其余 DEFLATED。
     */
    public static void repack(File srcApk, Map<String, byte[]> replace, java.util.Set<String> remove,
            Map<String, File> add, File outApk) throws IOException {
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
                writeOne(zo, cos, name, data, ze.getMethod() == ZipEntry.STORED, ze.getTime());
            }
            // 新增条目（原包中不存在的路径）
            for (Map.Entry<String, File> e : add.entrySet()) {
                byte[] data = readAll(new java.io.FileInputStream(e.getValue()));
                boolean so = e.getKey().toLowerCase().endsWith(".so");
                writeOne(zo, cos, e.getKey(), data, so, System.currentTimeMillis());
            }
        } finally {
            zf.close();
            zo.close();
            cos.close();
        }
    }

    /** 写单个条目：保持压缩方式、DOS 时间钳制、STORED 条目 4096 对齐 */
    private static void writeOne(ZipOutputStream zo, CountingOutputStream cos,
            String name, byte[] data, boolean store, long time) throws IOException {
        byte[] nameBytes = name.getBytes("UTF-8");
        ZipEntry ne = new ZipEntry(name);
        long t = time;
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
