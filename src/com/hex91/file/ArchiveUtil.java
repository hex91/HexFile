package com.hex91.file;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 统一归档抽象（对标 MT 的多格式压缩查看器）。
 * 通过 open(File, password) 按扩展名分派到对应后端：
 *   .zip/.apk/.jar/.aar  -> ZIP（Zip4j 后端：普通 / ZipCrypto / WinZip AES(AE-2) 通吃，原生解密）
 *   .7z                  -> 7z（无密码 = Commons Compress；带密码 = 7-Zip native 引擎）
 *   .tar/.tar.gz/.tgz    -> TAR（含 gzip 解包）
 *   .rar                 -> RAR（7-Zip native 引擎 lib7-Zip-JBinding.so，支持 RAR4 + RAR5 + 密码）
 * 密码：打开加密归档抛 NeedsPasswordException，由 UI 弹密码框后带密码重开（ArchivePasswords 会话存储）。
 * 提取阶段的密码类失败抛 NEED_PASSWORD 标记消息，UI 据此弹框。
 * 浏览/解压/进入子目录的 UI 只需面向 Archive 接口，无需关心底层格式。
 */
public class ArchiveUtil {

    /** 加密归档需要密码（打开时已知）——UI 捕获后弹密码框重开 */
    public static class NeedsPasswordException extends IOException {
        public NeedsPasswordException(String msg) { super(msg); }
    }

    /** 提取阶段密码类失败的标记前缀（消息以它开头时 UI 弹密码框） */
    public static final String NEED_PASSWORD = "NEED_PASSWORD:";

    /** 归档内部条目（用于浏览界面） */
    public static class Entry {
        public final String full;   // 完整条目名，如 "a/b.txt"
        public final String name;   // 去掉前缀后的短名，如 "b.txt"
        public final boolean dir;
        public final long size;
        Entry(String full, String name, boolean dir, long size) {
            this.full = full; this.name = name; this.dir = dir; this.size = size;
        }
    }

    /** 归档统一接口 */
    public interface Archive extends AutoCloseable {
        /** 列出 prefix 下的直接子条目（不递归） */
        List<Entry> listChildren(String prefix) throws IOException;
        /** 解压全部内容到 outDir */
        void extractAll(File outDir) throws IOException;
        /** 解压单个条目（含其下全部子项，若 entry 是目录）到 outDir */
        void extractEntry(String entryName, File outDir) throws IOException;
        /** 格式标识：ZIP / 7Z / TAR / RAR（用于决定 dex/arsc 等专属菜单） */
        String format();
        void close() throws IOException;
    }

    /** 扁平条目（后端内部用于算直接子项） */
    private static class Raw {
        final String name; final boolean dir; final long size;
        Raw(String name, boolean dir, long size) { this.name = name; this.dir = dir; this.size = size; }
    }

    /** 按扩展名分派后端（password 可为 null = 未提供密码） */
    public static Archive open(File f, String password) throws IOException {
        String n = f.getName().toLowerCase();
        boolean isZipFamily = !(n.endsWith(".7z") || n.endsWith(".rar")
                || n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz")
                || n.endsWith(".tar.zst") || n.endsWith(".tzst") || n.endsWith(".zst"));
        // ZIP 族（.zip/.apk/.jar/.aar 等）：统一走 Zip4j 后端。
        // Zip4j 原生支持 普通ZIP / 传统ZipCrypto / WinZip AES(AE-2) 的浏览与解密；
        // 7-Zip-JBinding 内置的旧版 7-Zip 不支持 AES，遇加密 CEN 头会抛 "CEN header (encrypted)"。
        if (isZipFamily) {
            return new J4Archive(f, password);
        }
        if (n.endsWith(".7z")) {
            if (password != null) return new JBindingArchive(f, password);
            try {
                return new SevenZArchive(f);
            } catch (Throwable t) {
                // 头加密 7z 在 open 阶段就抛错；其余错误也可能是加密导致 → 都引导输密码重试
                throw new NeedsPasswordException("7z 打开失败(可能已加密): " + brief(t));
            }
        }
        if (n.endsWith(".rar")) return new JBindingArchive(f, password);
        if (n.endsWith(".tar.zst") || n.endsWith(".tzst"))
            return new TarArchive(f, false, true);
        if (n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz"))
            return new TarArchive(f, n.endsWith(".gz") || n.endsWith(".tgz"), false);
        if (n.endsWith(".zst")) return new ZstSingleArchive(f);   // 裸 zstd frame → 单条目伪归档
        // 兜底：其余一律按 ZIP（Zip4j）处理
        return new J4Archive(f, password);
    }

    /** 兼容旧调用（不带密码） */
    public static Archive open(File f) throws IOException {
        return open(f, ArchivePasswords.get(f));
    }

    /** 把异常压成一行简短信息 */
    static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    /** 异常是否与密码/加密相关（用于提取失败时决定弹密码框） */
    static boolean cryptoError(Throwable t) {
        while (t != null) {
            String cls = t.getClass().getSimpleName().toLowerCase();
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (m.startsWith(needPrefixLower()) || m.contains("password") || m.contains("密码")
                    || m.contains("crypto") || m.contains("encrypted") || m.contains("encrypt")
                    || cls.contains("crypto")) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String needPrefixLower() { return NEED_PASSWORD.toLowerCase(); }

    // ===================== 共享逻辑 =====================

    /** 从扁平条目列表里算 prefix 下的直接子项（不递归） */
    private static List<Entry> computeChildren(List<Raw> all, String prefix) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Raw r : all) {
            String name = r.name;
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            if (rest.isEmpty()) continue;
            boolean isDir = rest.contains("/") || r.dir || rest.endsWith("/");
            String shortName = rest.split("/", 2)[0];
            String key = (isDir ? "D:" : "F:") + shortName;
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(new Entry(prefix + shortName + (isDir ? "/" : ""), shortName, isDir, r.size));
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** 路径穿越防护：确保解压目标落在 outDir 内 */
    static File safeTarget(File outDir, String entryName) {
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

    /** 拷贝流（不关闭，由调用方管理） */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 归一化条目名：去前导 ./ 与 /，反斜杠转 / */
    private static String norm(String n) {
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        return n.replace('\\', '/');
    }

    // ===================== ZIP 后端（java.util.zip 快速路径；加密 ZIP 走 native 引擎） =====================

    private static class ZipArchive implements Archive {
        private final File file;
        ZipArchive(File f) { this.file = f; }

        public List<Entry> listChildren(String prefix) throws IOException {
            // java.util.zip 快速路径（ZipUtil 已按层级过滤）
            List<Entry> out = new ArrayList<>();
            for (ZipUtil.Entry e : ZipUtil.listChildren(file, prefix)) {
                out.add(new Entry(e.full, e.name, e.dir, e.size));
            }
            return out;
        }

        public void extractAll(File outDir) throws IOException {
            ZipUtil.extractAll(file, outDir);
        }

        public void extractEntry(String entryName, File outDir) throws IOException {
            ZipUtil.extractEntry(file, entryName, outDir);
        }

        public String format() { return "ZIP"; }
        public void close() { /* 流每次操作自行关闭 */ }
    }

    // ===================== 7z 后端（Commons Compress，无密码路径） =====================

    private static class SevenZArchive implements Archive {
        private final SevenZFile szf;
        SevenZArchive(File f) throws IOException { this.szf = new SevenZFile(f); }
        private List<Raw> allRaw() {
            List<Raw> raws = new ArrayList<>();
            for (SevenZArchiveEntry e : szf.getEntries()) {
                String nm = norm(e.getName());
                if (nm.isEmpty()) continue;
                raws.add(new Raw(nm, e.isDirectory(), e.getSize()));
            }
            return raws;
        }
        public List<Entry> listChildren(String prefix) throws IOException {
            return computeChildren(allRaw(), prefix);
        }
        public void extractAll(File outDir) throws IOException {
            extractMatching(outDir, null);
        }
        public void extractEntry(String entryName, File outDir) throws IOException {
            extractMatching(outDir, entryName);
        }
        private void extractMatching(File outDir, String want) throws IOException {
            outDir.mkdirs();
            String wantSlash = (want != null && !want.endsWith("/")) ? want + "/" : want;
            for (SevenZArchiveEntry e : szf.getEntries()) {
                String n = norm(e.getName());
                if (n.isEmpty()) continue;
                if (want != null && !n.equals(want) && !n.equals(wantSlash) && !n.startsWith(wantSlash))
                    continue;
                File target = safeTarget(outDir, n);
                if (target == null) continue;
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    target.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(target);
                    try {
                        InputStream in = szf.getInputStream(e);
                        copy(in, fos);
                        in.close();
                    } catch (Throwable t) {
                        fos.close();
                        if (cryptoError(t)) throw new IOException(NEED_PASSWORD
                                + "7z 内容已加密: " + n);
                        throw t instanceof IOException ? (IOException) t : new IOException(t);
                    }
                    fos.close();
                }
            }
        }
        public String format() { return "7Z"; }
        public void close() throws IOException { szf.close(); }
    }

    // ===================== 7-Zip native 引擎后端（RAR 全部 / 7z/zip 带密码） =====================

    private static class JBindingArchive implements Archive {
        private final IInArchive archive;
        private final RandomAccessFile raf;
        private final List<String> paths;
        private final List<Boolean> dirs;
        private final List<Long> sizes;
        private final boolean encrypted;
        private final String label;
        private final String password;      // null = 无密码（提取用三参重传）

        JBindingArchive(File f, String password) throws IOException {
            this.raf = new RandomAccessFile(f, "r");
            this.password = password;
            String ln = f.getName().toLowerCase();
            this.label = ln.endsWith(".7z") ? "7Z"
                    : (ln.endsWith(".zip") || ln.endsWith(".apk") || ln.endsWith(".jar")
                       || ln.endsWith(".aar")) ? "ZIP" : "RAR";
            IInArchive a;
            try {
                if (password != null) {
                    // 带密码打开（头加密归档必需；仅内容加密的也能开）。
                    // 注意：open 时传的密码对 extractSlow 无效，提取必须用三参重载。
                    a = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf), password);
                } else {
                    a = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf));
                }
            } catch (Throwable t) {
                try { raf.close(); } catch (Throwable t2) { /* ignore */ }
                throw new NeedsPasswordException(label + " 打开失败(可能已加密或密码错误): " + brief(t));
            }
            this.archive = a;
            int n = archive.getNumberOfItems();
            paths = new ArrayList<String>(n);
            dirs = new ArrayList<Boolean>(n);
            sizes = new ArrayList<Long>(n);
            boolean enc = false;
            for (int i = 0; i < n; i++) {
                String p;
                try { p = norm(archive.getStringProperty(i, PropID.PATH)); }
                catch (Throwable t) { p = ""; }
                if (p.isEmpty()) { p = "#unknown" + i; }
                long size = -1;
                boolean dir = false;
                try {
                    Object so = archive.getProperty(i, PropID.SIZE);
                    if (so instanceof Long) size = (Long) so;
                    else if (so instanceof Integer) size = (Integer) so;
                    Object dob = archive.getProperty(i, PropID.IS_FOLDER);
                    dir = dob instanceof Boolean && (Boolean) dob;
                    Object eob = archive.getProperty(i, PropID.ENCRYPTED);
                    if (eob instanceof Boolean && (Boolean) eob) enc = true;
                } catch (Throwable t) { /* 保留默认 */ }
                paths.add(p);
                dirs.add(dir);
                sizes.add(dir ? 0L : Math.max(size, 0L));
            }
            this.encrypted = enc;
            // 带密码打开时立即验证：解密第一个文件条目（输出丢弃，不占内存）。
            // 仅加密内容的归档 openInArchive 不校验密码，错密码也能"打开成功"，
            // 导致用户输了错密码却毫无提示——这里把验证提前到打开阶段。
            if (password != null) {
                try {
                    validatePassword();
                } catch (NeedsPasswordException npe) {
                    try { archive.close(); } catch (Throwable ig) { }
                    try { raf.close(); } catch (Throwable ig) { }
                    throw npe;
                }
            }
        }

        /** 丢弃输出的流（验证解密用，不占内存） */
        private static final class NullSink implements ISequentialOutStream {
            public int write(byte[] data) { return data.length; }
        }

        /** 解密第一个文件条目验证密码；失败抛 NeedsPasswordException（UI 弹"密码错误"重试） */
        private void validatePassword() throws IOException {
            int idx = -1;
            for (int i = 0; i < paths.size(); i++) {
                if (dirs.get(i)) continue;
                long sz = sizes.get(i);
                if (sz <= 0 || sz <= 512L << 20) { idx = i; break; }   // 未知大小/≤512MB 才验
            }
            if (idx < 0) return;
            try {
                // 三参重载：密码直达提取引擎（两参重载拿不到密码，永远 WRONG_PASSWORD）
                ExtractOperationResult r = password != null
                        ? archive.extractSlow(idx, new NullSink(), password)
                        : archive.extractSlow(idx, new NullSink());
                if (r != ExtractOperationResult.OK) {
                    throw new NeedsPasswordException(label + " 密码错误（解密验证: " + r + "）");
                }
            } catch (NeedsPasswordException npe) {
                throw npe;
            } catch (SevenZipException se) {
                throw new NeedsPasswordException(label + " 密码错误或无法解密: " + brief(se));
            } catch (RuntimeException re) {
                throw new NeedsPasswordException(label + " 密码错误或无法解密: " + brief(re));
            }
        }

        private List<Raw> allRaw() {
            List<Raw> raws = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                raws.add(new Raw(paths.get(i), dirs.get(i), sizes.get(i)));
            }
            return raws;
        }

        public List<Entry> listChildren(String prefix) throws IOException {
            return computeChildren(allRaw(), prefix);
        }

        public void extractAll(File outDir) throws IOException {
            extractMatching(outDir, null);
        }

        public void extractEntry(String entryName, File outDir) throws IOException {
            extractMatching(outDir, entryName);
        }

        private void extractMatching(File outDir, String want) throws IOException {
            outDir.mkdirs();
            String wantSlash = (want != null && !want.endsWith("/")) ? want + "/" : want;
            for (int i = 0; i < paths.size(); i++) {
                String n = paths.get(i);
                if (want != null && !n.equals(want) && !n.equals(wantSlash) && !n.startsWith(wantSlash))
                    continue;
                File target = safeTarget(outDir, n);
                if (target == null) continue;
                if (dirs.get(i).booleanValue()) {
                    target.mkdirs();
                    continue;
                }
                target.getParentFile().mkdirs();
                final FileOutputStream fos = new FileOutputStream(target);
                try {
                    ExtractOperationResult r;
                    if (password != null) {
                        // 三参重载：密码直达提取引擎（两参重载 open 时传的密码无效）
                        r = archive.extractSlow(i, new ISequentialOutStream() {
                            public int write(byte[] data) throws SevenZipException {
                                try { fos.write(data); } catch (IOException e) { throw new SevenZipException(e); }
                                return data.length;
                            }
                        }, password);
                    } else {
                        r = archive.extractSlow(i, new ISequentialOutStream() {
                            public int write(byte[] data) throws SevenZipException {
                                try { fos.write(data); } catch (IOException e) { throw new SevenZipException(e); }
                                return data.length;
                            }
                        });
                    }
                    if (r == ExtractOperationResult.WRONG_PASSWORD) {
                        throw new IOException(NEED_PASSWORD + label + " 密码错误（条目: " + n + "）");
                    }
                    if (r != ExtractOperationResult.OK) {
                        throw new IOException(label + " 提取失败: " + n + " -> " + r);
                    }
                } catch (SevenZipException se) {
                    fos.close();
                    if (cryptoError(se)) throw new IOException(NEED_PASSWORD
                            + label + " 解密失败（密码错误或未提供，加密条目: " + n + "）");
                    throw se instanceof IOException ? (IOException) se : new IOException(se);
                } finally {
                    try { fos.close(); } catch (Throwable ignored) { }
                }
            }
        }

        public String format() { return label; }

        public void close() throws IOException {
            try { archive.close(); } catch (Throwable t) { /* ignore */ }
            raf.close();
        }
    }

    // ===================== ZIP 后端（Zip4j：普通 ZIP / ZipCrypto / WinZip AES 通吃） =====================
    // 读取与解密统一用 Zip4j（AE-2 参考实现，纯 Java）。它原生支持 AES(AE-2) 与传统 ZipCrypto，
    // 不会像 7-Zip-JBinding 旧版那样在加密 CEN 头上抛 "CEN header (encrypted)"。
    // APK 本质也是 ZIP，未加密时无需密码即可浏览/提取。

    private static class J4Archive implements Archive {
        private final net.lingala.zip4j.ZipFile zip;
        private final String password;          // null = 未提供密码
        private final List<Raw> raws;
        private final boolean encrypted;

        J4Archive(File f, String password) throws IOException {
            this.password = (password == null || password.isEmpty()) ? null : password;
            net.lingala.zip4j.ZipFile z;
            try {
                z = new net.lingala.zip4j.ZipFile(f);
            } catch (Exception e) {
                throw new IOException("ZIP 打开失败: " + brief(e));
            }
            this.zip = z;
            List<Raw> rs = new ArrayList<Raw>();
            boolean enc = false;
            try {
                for (net.lingala.zip4j.model.FileHeader h : z.getFileHeaders()) {
                    String nm = norm(h.getFileName());
                    if (nm.isEmpty()) continue;
                    if (h.isEncrypted()) enc = true;
                    rs.add(new Raw(nm, h.isDirectory(), h.getUncompressedSize()));
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                try { z.close(); } catch (Throwable ig) { }
                throw new IOException("ZIP 读取失败: " + brief(e));
            }
            this.raws = rs;
            this.encrypted = enc;
            // 已加密但未提供密码 → 打开即要求密码（MT 行为：弹密码框）
            if (enc && this.password == null) {
                try { z.close(); } catch (Throwable ig) { }
                throw new NeedsPasswordException("ZIP 已加密，需要密码");
            }
            // 已加密且提供了密码（可能错误）→ 立即校验，避免"看到文件列表、解压时才报密码错"。
            // Zip4j 在 AES / ZipCrypto 错密码时于读取流时立即抛 Wrong Password，借此把校验提前到打开阶段。
            if (enc && this.password != null) {
                try {
                    validatePassword();
                } catch (NeedsPasswordException npe) {
                    try { z.close(); } catch (Throwable ig) { }
                    throw npe;
                }
            }
        }

        /**
         * 解密首个已加密文件条目验证密码；失败抛 NeedsPasswordException（UI 弹"密码错误"重试）。
         * 用 getInputStream 读 1 字节触发解密与密码校验（AES/ZipCrypto 错密码即抛 ZipException，
         * 不写盘、不占内存）。7z/RAR 后端早已如此，这里让 ZIP 族与其一致。
         */
        private void validatePassword() throws IOException {
            net.lingala.zip4j.model.FileHeader pick = null;
            // 优先取"非空且已加密"的文件条目；拿不到则退化为任意已加密条目 / 任意文件条目
            for (net.lingala.zip4j.model.FileHeader h : zip.getFileHeaders()) {
                if (!h.isDirectory() && h.isEncrypted() && h.getUncompressedSize() > 0) { pick = h; break; }
            }
            if (pick == null) {
                for (net.lingala.zip4j.model.FileHeader h : zip.getFileHeaders()) {
                    if (!h.isDirectory() && h.isEncrypted()) { pick = h; break; }
                }
            }
            if (pick == null) {
                for (net.lingala.zip4j.model.FileHeader h : zip.getFileHeaders()) {
                    if (!h.isDirectory()) { pick = h; break; }
                }
            }
            if (pick == null) return;   // 全是目录，无法验证（极少见）
            try {
                zip.setPassword(password.toCharArray());
                java.io.InputStream in = zip.getInputStream(pick);
                try {
                    byte[] buf = new byte[1];
                    in.read(buf);   // 触发解密与密码校验（AES/ZipCrypto 错密码即抛 ZipException）
                } finally {
                    try { in.close(); } catch (Throwable ig) { }
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                throw new NeedsPasswordException("ZIP 密码错误: " + brief(e));
            }
        }

        private List<Raw> allRaw() { return raws; }

        public List<Entry> listChildren(String prefix) throws IOException {
            return computeChildren(raws, prefix);
        }

        public void extractAll(File outDir) throws IOException {
            try {
                if (password != null && encrypted) zip.setPassword(password.toCharArray());
                zip.extractAll(outDir.getPath());
            } catch (net.lingala.zip4j.exception.ZipException e) {
                // 加密归档解密失败（密码错/未提供）→ 引导输密码重试
                if (password == null || encrypted)
                    throw new IOException(NEED_PASSWORD + "ZIP 解密失败（密码错误或未提供）: " + brief(e));
                throw new IOException("ZIP 解压失败: " + brief(e));
            }
        }

        public void extractEntry(String entryName, File outDir) throws IOException {
            outDir.mkdirs();
            String want = norm(entryName);
            String wantSlash = want.endsWith("/") ? want : want + "/";
            try {
                if (password != null && encrypted) zip.setPassword(password.toCharArray());
                List<net.lingala.zip4j.model.FileHeader> sel =
                        new ArrayList<net.lingala.zip4j.model.FileHeader>();
                boolean isDirEntry = false;
                for (net.lingala.zip4j.model.FileHeader h : zip.getFileHeaders()) {
                    String nm = norm(h.getFileName());
                    if (nm.equals(want) || nm.equals(wantSlash)) {
                        sel.add(h);
                        if (h.isDirectory()) isDirEntry = true;
                    } else if (nm.startsWith(wantSlash)) {
                        sel.add(h);
                    }
                }
                if (sel.isEmpty()) {
                    throw new IOException("ZIP 内找不到条目: " + entryName);
                }
                // 若选中的是纯目录条目，展开其下全部子项一并解压
                if (isDirEntry && sel.size() == 1) {
                    sel.clear();
                    for (net.lingala.zip4j.model.FileHeader h : zip.getFileHeaders()) {
                        String nm = norm(h.getFileName());
                        if (nm.equals(wantSlash) || nm.startsWith(wantSlash)) sel.add(h);
                    }
                }
                for (net.lingala.zip4j.model.FileHeader h : sel) {
                    zip.extractFile(h, outDir.getPath());
                }
            } catch (net.lingala.zip4j.exception.ZipException e) {
                if (password == null || encrypted)
                    throw new IOException(NEED_PASSWORD + "ZIP 解密失败（密码错误或未提供）: " + brief(e));
                throw new IOException("ZIP 解压失败: " + brief(e));
            }
        }

        public String format() { return "ZIP"; }

        public void close() throws IOException {
            try { zip.close(); } catch (Throwable t) { /* ignore */ }
        }
    }

    // ===================== TAR 后端（含 gzip） =====================

    private static class TarArchive implements Archive {
        private final File file;
        private final boolean gz;
        private final boolean zst;
        TarArchive(File f, boolean gz, boolean zst) { this.file = f; this.gz = gz; this.zst = zst; }
        private TarArchiveInputStream openStream() throws IOException {
            InputStream raw = new FileInputStream(file);
            InputStream base;
            if (zst) base = new com.github.luben.zstd.ZstdInputStream(raw);        // .tar.zst
            else base = gz ? new GZIPInputStream(raw) : raw;                        // .tgz / .tar
            return new TarArchiveInputStream(base);
        }
        private List<Raw> allRaw() throws IOException {
            List<Raw> raws = new ArrayList<>();
            TarArchiveInputStream tis = openStream();
            TarArchiveEntry e;
            while ((e = tis.getNextTarEntry()) != null) {
                String nm = norm(e.getName());
                if (nm.isEmpty()) continue;
                raws.add(new Raw(nm, e.isDirectory(), e.getSize()));
            }
            tis.close();
            return raws;
        }
        public List<Entry> listChildren(String prefix) throws IOException {
            return computeChildren(allRaw(), prefix);
        }
        public void extractAll(File outDir) throws IOException { extractMatching(outDir, null); }
        public void extractEntry(String entryName, File outDir) throws IOException {
            extractMatching(outDir, entryName);
        }
        private void extractMatching(File outDir, String want) throws IOException {
            outDir.mkdirs();
            String wantSlash = (want != null && !want.endsWith("/")) ? want + "/" : want;
            TarArchiveInputStream tis = openStream();
            TarArchiveEntry e;
            while ((e = tis.getNextTarEntry()) != null) {
                String n = norm(e.getName());
                if (n.isEmpty()) continue;
                if (want != null && !n.equals(want) && !n.equals(wantSlash) && !n.startsWith(wantSlash))
                    continue;
                File target = safeTarget(outDir, n);
                if (target == null) continue;
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    target.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(target);
                    copy(tis, fos);
                    fos.close();
                }
            }
            tis.close();
        }
        public String format() { return "TAR"; }
        public void close() { /* 流每次操作自行关闭 */ }
    }

    /**
     * 裸 .zst（单文件 zstd frame）→ 单条目伪归档：可像压缩包一样点开预览/提取，
     * 条目名 = 去掉 .zst 后缀的文件名（zstd frame 不记录内容大小，size 显示 0）。
     */
    private static class ZstSingleArchive implements Archive {
        private final File file;
        ZstSingleArchive(File f) { this.file = f; }

        private String entryName() {
            String n = file.getName();
            return n.toLowerCase().endsWith(".zst") ? n.substring(0, n.length() - 4) : n;
        }

        private InputStream frame() throws IOException {
            return new com.github.luben.zstd.ZstdInputStream(new FileInputStream(file));
        }

        public List<Entry> listChildren(String prefix) throws IOException {
            List<Entry> out = new ArrayList<>();
            String nm = entryName();
            if (prefix == null || prefix.isEmpty())
                out.add(new Entry(nm, nm, false, 0));
            return out;
        }

        public void extractAll(File outDir) throws IOException { extractEntry(null, outDir); }

        public void extractEntry(String entryName, File outDir) throws IOException {
            outDir.mkdirs();
            File target = safeTarget(outDir, entryName != null ? entryName : entryName());
            if (target == null) throw new IOException("条目名非法: " + entryName);
            FileOutputStream fos = new FileOutputStream(target);
            try { copy(frame(), fos); } finally { fos.close(); }
        }

        public String format() { return "ZST"; }
        public void close() { /* 流每次操作自行关闭 */ }
    }
}
