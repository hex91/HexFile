package com.hex91.file;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * APK 解包/打包（v1.57.0）：
 *   unpack：全部条目按原路径提取到目录（跳过旧签名），另存 AndroidManifest.xml.txt（可读 XML）；
 *   pack：目录 → 未签名 APK。resources.arsc 与 lib/*.so 用 STORED + 4096 对齐
 *   （extractNativeLibs=false 直载 .so 与 API30+ arsc 的硬性要求），其余 DEFLATED。
 *   打包产物交 ApkSignerTool 签名后才可安装。
 */
public final class ApkPackager {

    private static final int ALIGN = 4096;

    private ApkPackager() {}

    /** 解包 APK 到目录；返回统计描述 */
    public static String unpack(File apk, File outDir) throws IOException {
        if (outDir.exists() && !outDir.isDirectory()) throw new IOException("目标被文件占用");
        outDir.mkdirs();
        int files = 0;
        long bytes = 0;
        ZipFile zf = new ZipFile(apk);
        byte[] buf = new byte[65536];
        byte[] manifest = null;
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (isOldSignature(name)) continue;
                File out = new File(outDir, name);
                // 防路径穿越
                if (!out.getCanonicalPath().startsWith(outDir.getCanonicalPath() + File.separator))
                    throw new IOException("危险条目路径: " + name);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                InputStream in = new BufferedInputStream(zf.getInputStream(ze), 131072);
                OutputStream fo = new BufferedOutputStream(new FileOutputStream(out), 131072);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); bytes += n; }
                } finally {
                    try { in.close(); fo.close(); } catch (IOException ignored) {}
                }
                files++;
                if ("AndroidManifest.xml".equals(name)) manifest = readAll(zf.getInputStream(ze));
            }
        } finally {
            zf.close();
        }
        // 附加可读 Manifest
        if (manifest != null) {
            try {
                String pretty = AxmlFile.parse(manifest).prettyXml();
                OutputStream fo = new FileOutputStream(new File(outDir, "AndroidManifest.xml.txt"));
                fo.write(pretty.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) { /* 可读化失败不影响解包 */ }
        }
        return files + " 个文件（" + ApkInfoUtil.fmtSize(bytes) + "）";
    }

    /** 打包目录为未签名 APK（含 AndroidManifest.xml 的目录才合法） */
    public static void pack(File dir, File outApk) throws IOException {
        if (!new File(dir, "AndroidManifest.xml").isFile())
            throw new IOException("目录中无 AndroidManifest.xml，不是解包产物");
        RelFile[] all = collect(dir, "");
        CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(outApk));
        ZipOutputStream zo = new ZipOutputStream(cos);
        try {
            for (RelFile f : all) {
                String name = f.rel;
                if (isOldSignature(name)) continue;
                byte[] data = readAll(new FileInputStream(f.f));
                boolean store = "resources.arsc".equals(name)
                        || (name.startsWith("lib/") && name.endsWith(".so"));
                ZipEntry ne = new ZipEntry(name);
                ne.setTime(315532800000L);   // 固定 1980，避免 EXTT 扩展破坏对齐
                if (store) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ne.setCrc(crc.getValue());
                    long posBefore = cos.count;
                    int pad = (int) ((ALIGN - ((posBefore + 30
                            + name.getBytes("UTF-8").length) % ALIGN)) % ALIGN);
                    ne.setExtra(new byte[pad]);
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                zo.putNextEntry(ne);
                zo.write(data);
                zo.closeEntry();
            }
        } finally {
            zo.close();
            cos.close();
        }
    }

    private static final class RelFile {
        final File f; final String rel;
        RelFile(File f, String rel) { this.f = f; this.rel = rel; }
    }

    private static RelFile[] collect(File dir, String prefix) {
        java.util.List<RelFile> out = new java.util.ArrayList<RelFile>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
                if (c.isDirectory()) {
                    for (RelFile r : collect(c, rel)) out.add(r);
                } else {
                    out.add(new RelFile(c, rel));
                }
            }
        }
        return out.toArray(new RelFile[out.size()]);
    }

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String base = name.substring("META-INF/".length());
        return base.equals("MANIFEST.MF")
                || base.endsWith(".SF") || base.endsWith(".RSA")
                || base.endsWith(".DSA") || base.endsWith(".EC");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
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
