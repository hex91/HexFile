package com.hex91.file;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


/**
 * ZIP 工具集（零第三方依赖，纯 java.util.zip）
 * 对标 MT 压缩引擎：浏览内部条目 / 解压全部 / 解压单条 / 打包
 */
public class ZipUtil {

    /** 压缩包内部条目（用于浏览界面） */
    public static class Entry {
        public final String full;   // 完整条目名，如 "a/b.txt"
        public final String name;   // 去掉前缀后的短名，如 "b.txt"
        public final boolean dir;
        public final long size;
        Entry(String full, String name, boolean dir, long size) {
            this.full = full; this.name = name; this.dir = dir; this.size = size;
        }
    }

    /**
     * 列出 zip 在 prefix 下的直接子条目（不递归）。
     * 例如 prefix="" 返回根目录条目；prefix="a/" 返回 a 目录下的条目。
     */
    public static List<Entry> listChildren(File zip, String prefix) throws IOException {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 用 ZipFile（读 central directory）而非 ZipInputStream：
        // 流式 local header 的 size 常为 -1，ZipFile 的 getSize() 始终准确
        try (ZipFile zf = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (!name.startsWith(prefix)) continue;
                String rest = name.substring(prefix.length());
                if (rest.isEmpty()) continue;
                boolean entryIsDir = ze.isDirectory() || rest.endsWith("/");
                String shortName = rest.split("/", 2)[0];
                // rest 含 "/" 说明是更深层的祖先子项，当前层级一律当作目录项展示
                boolean isDir = rest.contains("/") || entryIsDir;
                String key = (isDir ? "D:" : "F:") + shortName;
                if (seen.contains(key)) continue;
                seen.add(key);
                long size = isDir ? 0L : ze.getSize();
                if (size < 0) size = 0L;
                out.add(new Entry(prefix + shortName + (isDir ? "/" : ""), shortName, isDir, size));
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** 解压 zip 全部内容到 outDir */
    public static boolean extractAll(File zip, File outDir) {
        try {
            outDir.mkdirs();
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    File target = safeTarget(outDir, ze.getName());
                    if (target == null) { zis.closeEntry(); continue; }
                    if (ze.isDirectory()) {
                        target.mkdirs();
                    } else {
                        target.getParentFile().mkdirs();
                        FileOutputStream fos = new FileOutputStream(target);
                        copyStream(zis, fos);
                        fos.close();
                    }
                    zis.closeEntry();
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解压单个条目（含其下全部子项，若 entry 是目录）到 outDir */
    public static boolean extractEntry(File zip, String entryName, File outDir) {
        try {
            outDir.mkdirs();
            String want = entryName.endsWith("/") ? entryName : entryName + "/";
            boolean found = false;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    String n = ze.getName();
                    if (n.equals(entryName) || n.equals(want) || n.startsWith(want)) {
                        File target = safeTarget(outDir, n);
                        if (target == null) { zis.closeEntry(); continue; }
                        if (ze.isDirectory()) {
                            target.mkdirs();
                        } else {
                            target.getParentFile().mkdirs();
                            copyStream(zis, new FileOutputStream(target));
                        }
                        found = true;
                    }
                    zis.closeEntry();
                }
            }
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== 条目写入 / 删除（重建式，v1.14） =====================

    /**
     * 写入或替换 zip 内单个条目（entryName 以 / 结尾视为目录条目）。
     * 通过重建 zip 实现：原条目按原压缩方式复制，目标条目用 data 覆盖。
     * .apk 会顺带剥离 META-INF 旧签名（.SF/.RSA/.DSA/MANIFEST.MF），便于重签。
     */
    public static boolean writeEntry(File zip, String entryName, byte[] data) {
        InputStream in = new ByteArrayInputStream(data);
        try {
            return writeEntry(zip, entryName, in, data.length);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 流式写入或替换 zip 内单个条目（大文件不整体进内存）。
     * 语义同 writeEntry(File, String, byte[])。
     */
    public static boolean writeEntry(File zip, String entryName, File dataFile) {
        InputStream in;
        try {
            in = new FileInputStream(dataFile);
        } catch (IOException e) {
            return false;
        }
        try {
            return writeEntry(zip, entryName, in, dataFile.length());
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean writeEntry(File zip, String entryName,
            InputStream data, long size) {
        try {
            File tmp = tempFile(zip);
            CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(tmp));
            try (ZipFile zf = new ZipFile(zip);
                 ZipOutputStream zos = new ZipOutputStream(cos)) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().equals(entryName)) continue;   // 旧内容跳过
                    if (isSignatureFile(zip, ze.getName())) continue;
                    copyEntry(zf, ze, zos, cos.count);
                }
                putData(zos, cos.count, entryName, data);
            }
            return replaceOriginal(zip, tmp);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除 zip 内一组条目（目录条目自动匹配其下全部子项）。
     * .apk 同样剥离旧签名文件。一次重建完成全部删除。
     */
    public static boolean removeEntries(File zip, List<String> entryNames) {
        try {
            // 统一转成 "name/" 前缀匹配集合
            Set<String> kill = new HashSet<>();
            for (String n : entryNames) {
                kill.add(n);
                if (!n.endsWith("/")) kill.add(n + "/");
            }
            File tmp = tempFile(zip);
            CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(tmp));
            try (ZipFile zf = new ZipFile(zip);
                 ZipOutputStream zos = new ZipOutputStream(cos)) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String n = ze.getName();
                    boolean hit = false;
                    for (String k : kill) {
                        if (n.equals(k) || n.equals(stripSlash(k)) || n.startsWith(k)) {
                            hit = true; break;
                        }
                    }
                    if (hit || isSignatureFile(zip, n)) continue;
                    copyEntry(zf, ze, zos, cos.count);
                }
            }
            return replaceOriginal(zip, tmp);
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** .apk 的旧签名文件（重建时剥离，保留其它 META-INF 如 services/） */
    private static boolean isSignatureFile(File zip, String entryName) {
        if (!zip.getName().toLowerCase().endsWith(".apk")) return false;
        String n = entryName.toUpperCase();
        if (!n.startsWith("META-INF/")) return false;
        return n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
                || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF");
    }

    private static File tempFile(File zip) {
        return new File(zip.getParentFile(),
                zip.getName() + ".reb" + System.currentTimeMillis() + ".tmp");
    }

    /** 原条目按原压缩方式复制到新 zip（STORED 需缓冲算 CRC；按 locPos 对齐） */
    private static void copyEntry(ZipFile zf, ZipEntry ze, ZipOutputStream zos,
            long locPos) throws IOException {
        ZipEntry ne = new ZipEntry(ze.getName());
        ne.setTime(ze.getTime());
        if (ze.getMethod() == ZipEntry.STORED) {
            byte[] data = readAll(zf.getInputStream(ze));
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            ne.setCrc(crc.getValue());
            alignEntry(ne, locPos);
            zos.putNextEntry(ne);
            zos.write(data);
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(ne);
            copyStream(zf.getInputStream(ze), zos);
        }
        zos.closeEntry();
    }

    private static void putData(ZipOutputStream zos, long locPos, String entryName,
            InputStream data) throws IOException {
        ZipEntry ne = new ZipEntry(entryName);
        ne.setTime(System.currentTimeMillis());
        zos.putNextEntry(ne);
        if (data != null) copyStream(data, zos);
        zos.closeEntry();
    }

    /** 统计写入字节数的输出流：用于在 putNextEntry 前得知当前 LOC 头的绝对偏移 */
    private static class CountingOutputStream extends java.io.FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream out) { super(out); }
        public void write(int b) throws IOException { out.write(b); count++; }
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }

    /**
     * APK 对齐：重建后 STORED 条目数据区按 4 字节对齐（.so 按 4096）。
     * Android 11+ 上 targetSdk≥30 的 APK 要求 resources.arsc 未压缩且 4 字节对齐，
     * 否则 getPackageArchiveInfo 返回 null（"无法解析"）且无法安装。
     * 用 zip extra 字段做填充，等效 zipalign。
     * locPos = 该条目 local header 的绝对偏移（由计数流实时得知）。
     */
    private static void alignEntry(ZipEntry ne, long locPos) {
        if (ne.getMethod() != ZipEntry.STORED) return;
        String name = ne.getName();
        int nameLen = name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int align = name.toLowerCase().endsWith(".so") ? 4096 : 4;
        int pad = (int) ((align - ((locPos + 30 + nameLen) % align)) % align);
        if (pad > 0) ne.setExtra(new byte[pad]);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        copyStream(in, bo);
        return bo.toByteArray();
    }

    /** 读取整个文件为字节数组（加密写出用） */
    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readAll(fis);
        }
    }

    /** 临时文件替换原 zip（先删旧再改名，失败时尽力回滚） */
    private static boolean replaceOriginal(File zip, File tmp) {
        if (!tmp.renameTo(zip)) {
            if (zip.delete() && tmp.renameTo(zip)) return true;
            tmp.delete();
            return false;
        }
        return true;
    }

    /** 把 sources 中的文件/目录打包成 zip（相对路径基于各自父目录） */
    public static boolean createZip(File outZip, List<File> sources) {        try {
            outZip.getParentFile().mkdirs();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
                for (File s : sources) {
                    addToZip(zos, s, s.getParentFile());
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addToZip(ZipOutputStream zos, File file, File base) throws IOException {
        String rel = relativePath(file, base);
        if (file.isDirectory()) {
            if (!rel.endsWith("/")) rel += "/";
            zos.putNextEntry(new ZipEntry(rel));
            zos.closeEntry();
            File[] kids = file.listFiles();
            if (kids != null) for (File k : kids) addToZip(zos, k, base);
        } else {
            zos.putNextEntry(new ZipEntry(rel));
            FileInputStream fis = new FileInputStream(file);
            copyStream(fis, zos);
            fis.close();
            zos.closeEntry();
        }
    }

    /** 计算 file 相对 base 的路径（minSdk 21 安全，不依赖 File.toPath） */
    private static String relativePath(File file, File base) {
        String basePath = base.getAbsolutePath();
        if (!basePath.endsWith("/") && !basePath.endsWith("\\")) basePath += "/";
        String filePath = file.getAbsolutePath().replace('\\', '/');
        basePath = basePath.replace('\\', '/');
        if (filePath.startsWith(basePath)) {
            return filePath.substring(basePath.length());
        }
        return file.getName();
    }

    /** 防目录穿越：确保解压目标落在 outDir 内 */
    private static File safeTarget(File outDir, String entryName) {
        File f = new File(outDir, entryName);
        try {
            String outCanon = outDir.getCanonicalPath();
            String fCanon = f.getCanonicalPath();
            if (!fCanon.startsWith(outCanon + File.separator) && !fCanon.equals(outCanon)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        // 注意：不关闭流！Zip 流由 try-with-resources 管理，
        // 文件流由调用方显式关闭，否则会毒害后续条目读写
    }

    // ===================== 加密 ZIP（基于开源 Zip4j，v1.33.1 修复） =====================
    // 改用 net.lingala.zip4j（Apache-2.0，AE-2 参考实现）：
    //  - AES 加密产物可被 7-Zip / WinRAR / ZArchiver 直接打开（此前自写实现 AES extra 字段顺序错位，标准工具无法识别 → “未知方式”）。
    //  - 另提供「兼容 ZipCrypto」模式，可被 Windows 资源管理器 / 系统解压直接打开（弱加密，仅防随手翻看）。

    private static net.lingala.zip4j.model.enums.AesKeyStrength aesStrength(int strength) {
        if (strength == 1) return net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_128;
        if (strength == 2) return net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_192;
        return net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256; // 默认 3
    }

    /**
     * 创建 AES 加密 ZIP（标准 AE-2）。strength: 1=AES128, 2=AES192, 3=AES256（默认 3）。
     * 目录会被递归展开为文件并加密；产物可被 7-Zip / WinRAR / ZArchiver 打开。
     */
    public static boolean createZipEncrypted(File outZip, java.util.List<File> sources,
            String password, int strength) {
        try {
            if (strength < 1 || strength > 3) strength = 3;
            net.lingala.zip4j.ZipFile zip =
                    new net.lingala.zip4j.ZipFile(outZip, password.toCharArray());
            java.util.Map<File, String> names = new java.util.HashMap<File, String>();
            java.util.List<File> files = flatten(sources, names);
            if (files.isEmpty()) { zip.close(); return false; }
            for (File f : files) {
                net.lingala.zip4j.model.ZipParameters zp =
                        new net.lingala.zip4j.model.ZipParameters();
                zp.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE);
                zp.setEncryptFiles(true);
                zp.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
                zp.setAesKeyStrength(aesStrength(strength));
                zp.setFileNameInZip(names.get(f));
                zip.addFile(f, zp);
            }
            zip.close();
            return outZip.exists();
        } catch (Exception e) {
            android.util.Log.w("ZipUtil", "encrypted zip create failed", e);
            return false;
        }
    }

    /**
     * 创建「兼容 ZipCrypto」加密 ZIP：可被 Windows 资源管理器 / 系统解压 / 7-Zip 直接打开
     * （传统 ZipCrypto 弱加密，仅防随手翻看，不建议用于敏感数据）。
     */
    public static boolean createZipEncryptedCompat(File outZip, java.util.List<File> sources,
            String password) {
        try {
            net.lingala.zip4j.ZipFile zip =
                    new net.lingala.zip4j.ZipFile(outZip, password.toCharArray());
            java.util.Map<File, String> names = new java.util.HashMap<File, String>();
            java.util.List<File> files = flatten(sources, names);
            if (files.isEmpty()) { zip.close(); return false; }
            for (File f : files) {
                net.lingala.zip4j.model.ZipParameters zp =
                        new net.lingala.zip4j.model.ZipParameters();
                zp.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE);
                zp.setEncryptFiles(true);
                zp.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD);
                zp.setFileNameInZip(names.get(f));
                zip.addFile(f, zp);
            }
            zip.close();
            return outZip.exists();
        } catch (Exception e) {
            android.util.Log.w("ZipUtil", "encrypted zip create failed", e);
            return false;
        }
    }

    /** 把 sources（文件+目录）展开为平铺文件列表，并在 names 中记录 zip 内相对路径（保留目录结构）。
     *  - 文件源：条目名 = 文件名（根级）。
     *  - 目录源：条目名 = 相对该目录的路径（根级展开，文件夹内容平铺到 zip 根）。 */
    private static java.util.List<File> flatten(java.util.List<File> sources,
            java.util.Map<File, String> names) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        for (File s : sources) {
            if (s.isDirectory()) {
                walk(s, "", out, names);
            } else {
                out.add(s);
                names.put(s, s.getName());
            }
        }
        return out;
    }

    private static void walk(File dir, String rel, java.util.List<File> out,
            java.util.Map<File, String> names) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String childRel = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) {
                walk(k, childRel, out, names);
            } else {
                out.add(k);
                names.put(k, childRel);
            }
        }
    }
}
