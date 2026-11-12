package com.hex91.file.apk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * 文件结构解析（v1.68.0，纯 Java，可桌面单测）：
 *  - 魔数识别：ELF / ZIP / APK / DEX / JKS / BKS / PKCS12 / ARSC / AXML / 图片 / 压缩格式…
 *  - ELF 解析：位宽、字节序、类型、机器、入口、程序头、节表（名称/类型/地址/偏移/大小）+ .comment
 *  - 签名信息：ZIP/APK 的 META-INF 签名文件与证书主题（需真实文件，非内存切片）
 *
 * 只读、不修改文件；所有越界访问都做保护（Hex 编辑器只加载前 4MB，文件可能被截断）。
 */
public final class FileInspect {

    private FileInspect() {
    }

    // ============================================================ 魔数

    /** 常见格式：魔数 + 类型说明 */
    private static final Object[][] MAGICS = {
            // {bytes, 需要的前缀长度, 名称, 说明}
            {new int[]{0x7F, 'E', 'L', 'F'}, "ELF", "可执行文件 / 动态库 / 内核镜像"},
            {new int[]{'P', 'K', 0x03, 0x04}, "ZIP", "ZIP 压缩包（APK/JAR/AAB 同族）"},
            {new int[]{'P', 'K', 0x05, 0x06}, "ZIP", "空 ZIP / 已归档"},
            {new int[]{'P', 'K', 0x07, 0x08}, "ZIP", "分卷 ZIP"},
            {new int[]{'d', 'e', 'x', '\n', '0'}, "DEX", "Dalvik 字节码"},
            {new int[]{0xFE, 0xED, 0xFE, 0xED}, "JKS", "Java KeyStore（JDK keytool 默认）"},
            {new int[]{0xBC, 0xCF, 0x25, 0x20}, "BKS", "BouncyCastle KeyStore（Android 常用）"},
            {new int[]{0xCE, 0xCE, 0xCE, 0xCE}, "BKS", "BouncyCastle KeyStore（旧版）"},
            {new int[]{0x30, 0x82}, "DER", "ASN.1 DER（PKCS12 / X.509 证书 / 私钥）"},
            {new int[]{0x30, 0x81}, "DER", "ASN.1 DER（PKCS12 / X.509 证书 / 私钥）"},
            {new int[]{0x02, 0x00, 0x0C, 0x00}, "ARSC", "Android resources.arsc 资源表"},
            {new int[]{0x03, 0x00, 0x08, 0x00}, "AXML", "Android 二进制 XML（AndroidManifest 等）"},
            {new int[]{0x89, 'P', 'N', 'G'}, "PNG", "PNG 图片"},
            {new int[]{0xFF, 0xD8, 0xFF}, "JPEG", "JPEG 图片"},
            {new int[]{'G', 'I', 'F', '8'}, "GIF", "GIF 图片"},
            {new int[]{'R', 'I', 'F', 'F'}, "RIFF", "RIFF 容器（WAV/AVI/WEBP）"},
            {new int[]{0x1F, 0x8B}, "GZIP", "gzip 压缩"},
            {new int[]{0xFD, '7', 'z', 'X', 'Z', 0x00}, "XZ", "xz 压缩"},
            {new int[]{0x28, 0xB5, 0x2F, 0xFD}, "ZSTD", "zstd 压缩"},
            {new int[]{'7', 'z', 0xBC, 0xAF, 0x27, 0x1C}, "7Z", "7-Zip 压缩"},
            {new int[]{'R', 'a', 'r', '!'}, "RAR", "RAR 压缩"},
            {new int[]{'%', 'P', 'D', 'F'}, "PDF", "PDF 文档"},
            {new int[]{0xCA, 0xFE, 0xBA, 0xBE}, "CLASS", "Java class 字节码"},
            {new int[]{'M', 'Z'}, "PE", "Windows PE（exe/dll）"},
            {new int[]{0x00, 'a', 's', 'm'}, "WASM", "WebAssembly 模块"},
            {new int[]{0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00}, "TTF", "TrueType 字体"},
            {new int[]{'O', 'T', 'T', 'O'}, "OTF", "OpenType 字体"},
            {new int[]{'u', 's', 't', 'a', 'r'}, "TAR", "tar 归档"},
            {new int[]{0x53, 0x51, 0x4C, 0x69, 0x74, 0x65}, "SQLITE", "SQLite 数据库"},
    };

    /** 前 16 字节的十六进制（空格分隔），用于「文件头」展示 */
    public static String hexHead(byte[] d, int n) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        int len = Math.min(n, d.length);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hex2(d[i] & 0xFF));
        }
        return sb.toString().toUpperCase(Locale.US);
    }

    /** 按魔数判断格式名（无法识别返回 null） */
    public static String magicName(byte[] d) {
        if (d == null) return null;
        for (Object[] m : MAGICS) {
            int[] sig = (int[]) m[0];
            if (d.length < sig.length) continue;
            boolean hit = true;
            for (int i = 0; i < sig.length; i++) {
                if ((d[i] & 0xFF) != (sig[i] & 0xFF)) { hit = false; break; }
            }
            if (hit) return (String) m[1];
        }
        return null;
    }

    private static String magicDesc(byte[] d) {
        if (d == null) return "";
        for (Object[] m : MAGICS) {
            int[] sig = (int[]) m[0];
            if (d.length < sig.length) continue;
            boolean hit = true;
            for (int i = 0; i < sig.length; i++) {
                if ((d[i] & 0xFF) != (sig[i] & 0xFF)) { hit = false; break; }
            }
            if (hit) return (String) m[2];
        }
        return "未知格式";
    }

    /** 结合扩展名细化类型（APK / JAR / PKCS12 vs 证书 / BKS…） */
    public static String typeLabel(byte[] d, String fileName) {
        String magic = magicName(d);
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if ("ZIP".equals(magic)) {
            if (lower.endsWith(".apk")) return "APK（ZIP 容器）";
            if (lower.endsWith(".jar")) return "JAR（ZIP 容器）";
            if (lower.endsWith(".aab")) return "AAB（ZIP 容器）";
        }
        if ("DER".equals(magic)) {
            if (lower.endsWith(".p12") || lower.endsWith(".pfx")) return "PKCS#12 keystore";
            if (lower.endsWith(".cer") || lower.endsWith(".crt") || lower.endsWith(".pem"))
                return "X.509 证书";
            return "ASN.1 DER（可能是 PKCS12/证书）";
        }
        if (magic == null) {
            if (lower.endsWith(".jks")) return "JKS（魔数不符）";
            if (lower.endsWith(".bks")) return "BKS（魔数不符）";
            return "未知/纯文本";
        }
        return magic + " · " + magicDesc(d);
    }

    /** DEX 版本号（如 035），非 DEX 返回 null */
    public static String dexVersion(byte[] d) {
        if (d == null || d.length < 8) return null;
        if (d[0] != 'd' || d[1] != 'e' || d[2] != 'x' || d[3] != '\n') return null;
        return new String(new byte[]{d[4], d[5], d[6]}, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** JKS 版本（1/2），非 JKS 返回 -1 */
    public static int jksVersion(byte[] d) {
        if (d == null || d.length < 8) return -1;
        if ((d[0] & 0xFF) != 0xFE || (d[1] & 0xFF) != 0xED
                || (d[2] & 0xFF) != 0xFE || (d[3] & 0xFF) != 0xED) return -1;
        int major = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);
        int minor = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        return major * 100 + minor;   // 2.0 → 200
    }

    // ============================================================ ELF

    public static final class Section {
        public int nameIdx;
        public String name = "";
        public long type, flags, addr, offset, size, entsize;
        public int link, info;
        public boolean outside;   // 偏移超出已读取范围

        public String flagsText() {
            StringBuilder sb = new StringBuilder();
            if ((flags & 0x1) != 0) sb.append('W');
            if ((flags & 0x2) != 0) sb.append('A');
            if ((flags & 0x4) != 0) sb.append('X');
            return sb.length() == 0 ? "-" : sb.toString();
        }

        public String typeName() {
            switch ((int) type) {
                case 0: return "NULL";
                case 1: return "PROGBITS";
                case 2: return "SYMTAB";
                case 3: return "STRTAB";
                case 4: return "RELA";
                case 5: return "HASH";
                case 6: return "DYNAMIC";
                case 7: return "NOTE";
                case 8: return "NOBITS";
                case 9: return "REL";
                case 11: return "DYNSYM";
                case 14: return "INIT_ARRAY";
                case 15: return "FINI_ARRAY";
                case 0x6ffffff6: return "GNU_HASH";
                case 0x6ffffffd: return "VERDEF";
                case 0x6ffffffe: return "VERNEED";
                case 0x6fffffff: return "VERSYM";
                default: return "0x" + Long.toHexString(type);
            }
        }
    }

    public static final class Elf {
        public int bits = 32;
        public boolean little = true;
        public int type, machine;
        public long version, entry, phoff, shoff;
        public int flags, ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
        public boolean truncated;      // 节表/字符串表超出已读取范围
        public final List<Section> sections = new ArrayList<Section>();
        public String comment;         // .comment（编译器/构建信息）

        public String typeName() {
            switch (type) {
                case 0: return "NONE";
                case 1: return "REL（可重定位目标）";
                case 2: return "EXEC（可执行）";
                case 3: return "DYN（共享库 / PIE）";
                case 4: return "CORE（核心转储）";
                default: return "0x" + Integer.toHexString(type);
            }
        }

        public String machineName() {
            switch (machine) {
                case 3: return "x86";
                case 8: return "MIPS";
                case 20: return "PowerPC";
                case 21: return "PowerPC64";
                case 40: return "ARM (32 位)";
                case 62: return "x86-64";
                case 183: return "AArch64 (ARM64)";
                case 243: return "RISC-V";
                default: return "0x" + Integer.toHexString(machine);
            }
        }
    }

    /** 解析 ELF 头 + 节表；非 ELF 返回 null */
    public static Elf parseElf(byte[] d) {
        if (d == null || d.length < 52) return null;
        if ((d[0] & 0xFF) != 0x7F || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') return null;
        Elf e = new Elf();
        e.bits = (d[4] == 2) ? 64 : 32;
        e.little = (d[5] != 2);
        try {
            if (e.bits == 64) {
                e.type = u16(d, 16, e.little);
                e.machine = u16(d, 18, e.little);
                e.version = u32(d, 20, e.little);
                e.entry = u64(d, 24, e.little);
                e.phoff = u64(d, 32, e.little);
                e.shoff = u64(d, 40, e.little);
                e.flags = (int) u32(d, 48, e.little);
                e.ehsize = u16(d, 52, e.little);
                e.phentsize = u16(d, 54, e.little);
                e.phnum = u16(d, 56, e.little);
                e.shentsize = u16(d, 58, e.little);
                e.shnum = u16(d, 60, e.little);
                e.shstrndx = u16(d, 62, e.little);
            } else {
                e.type = u16(d, 16, e.little);
                e.machine = u16(d, 18, e.little);
                e.version = u32(d, 20, e.little);
                e.entry = u32(d, 24, e.little);
                e.phoff = u32(d, 28, e.little);
                e.shoff = u32(d, 32, e.little);
                e.flags = (int) u32(d, 36, e.little);
                e.ehsize = u16(d, 40, e.little);
                e.phentsize = u16(d, 42, e.little);
                e.phnum = u16(d, 44, e.little);
                e.shentsize = u16(d, 46, e.little);
                e.shnum = u16(d, 48, e.little);
                e.shstrndx = u16(d, 50, e.little);
            }
        } catch (IndexOutOfBoundsException ex) {
            e.truncated = true;
            return e;
        }
        parseSections(d, e);
        return e;
    }

    private static void parseSections(byte[] d, Elf e) {
        if (e.shoff <= 0 || e.shnum <= 0 || e.shentsize <= 0) return;
        if (e.shoff >= d.length) { e.truncated = true; return; }
        byte[] shstr = null;
        for (int i = 0; i < e.shnum; i++) {
            long base = e.shoff + (long) i * e.shentsize;
            if (base + e.shentsize > d.length) { e.truncated = true; break; }
            int p = (int) base;
            Section s = new Section();
            s.nameIdx = (int) u32(d, p, e.little);
            s.type = u32(d, p + 4, e.little);
            if (e.bits == 64) {
                s.flags = u64(d, p + 8, e.little);
                s.addr = u64(d, p + 16, e.little);
                s.offset = u64(d, p + 24, e.little);
                s.size = u64(d, p + 32, e.little);
                s.link = (int) u32(d, p + 40, e.little);
                s.info = (int) u32(d, p + 44, e.little);
                s.entsize = u64(d, p + 56, e.little);
            } else {
                s.flags = u32(d, p + 8, e.little);
                s.addr = u32(d, p + 12, e.little);
                s.offset = u32(d, p + 16, e.little);
                s.size = u32(d, p + 20, e.little);
                s.link = (int) u32(d, p + 24, e.little);
                s.info = (int) u32(d, p + 28, e.little);
                s.entsize = u32(d, p + 36, e.little);
            }
            s.outside = s.offset >= d.length;
            e.sections.add(s);
        }
        // 字符串表：e_shstrndx 指向节名表
        if (e.shstrndx > 0 && e.shstrndx < e.sections.size()) {
            Section str = e.sections.get(e.shstrndx);
            if (str.offset + str.size <= d.length && str.size > 0 && str.size < (1 << 22)) {
                shstr = new byte[(int) str.size];
                System.arraycopy(d, (int) str.offset, shstr, 0, shstr.length);
            }
        }
        for (Section s : e.sections) {
            if (shstr != null && s.nameIdx >= 0 && s.nameIdx < shstr.length) {
                int i = s.nameIdx;
                StringBuilder sb = new StringBuilder();
                while (i < shstr.length && shstr[i] != 0) sb.append((char) (shstr[i++] & 0xFF));
                s.name = sb.toString();
            }
            if (".comment".equals(s.name) && s.offset + s.size <= d.length && s.size > 0 && s.size < 8192) {
                byte[] c = new byte[(int) s.size];
                System.arraycopy(d, (int) s.offset, c, 0, c.length);
                e.comment = printable(c);
            }
        }
    }

    private static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xFF;
            if (v == 0) sb.append(' ');
            else if (v >= 0x20 && v < 0x7F) sb.append((char) v);
            else sb.append('.');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ============================================================ 汇总文本

    /** 完整的「文件解析」文本（Hex 编辑器信息面板用） */
    public static String describe(byte[] data, String fileName, long realSize) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("文件名: ").append(fileName).append('\n');
        sb.append("文件大小: ").append(realSize).append(" 字节")
                .append(data != null && realSize > data.length
                        ? "（已读取前 " + data.length + " 字节）" : "").append('\n');
        if (data == null || data.length == 0) {
            sb.append("\n（空文件）");
            return sb.toString();
        }
        sb.append("文件头: ").append(hexHead(data, 16)).append('\n');
        sb.append("魔数/类型: ").append(typeLabel(data, fileName)).append('\n');
        String dex = dexVersion(data);
        if (dex != null) sb.append("dex 版本: ").append(dex).append('\n');
        int jks = jksVersion(data);
        if (jks > 0) sb.append("JKS 版本: ").append(jks / 100).append('.').append(jks % 100).append('\n');

        Elf elf = parseElf(data);
        if (elf != null) {
            sb.append('\n').append("== ELF 头 ==\n");
            sb.append("位宽: ").append(elf.bits).append(" 位 · 字节序: ")
                    .append(elf.little ? "小端 (LE)" : "大端 (BE)").append('\n');
            sb.append("类型: ").append(elf.typeName()).append('\n');
            sb.append("机器: ").append(elf.machineName()).append('\n');
            sb.append("入口地址: 0x").append(Long.toHexString(elf.entry)).append('\n');
            sb.append("程序头: ").append(elf.phnum).append(" 个 @ 0x")
                    .append(Long.toHexString(elf.phoff)).append('\n');
            sb.append("节头: ").append(elf.shnum).append(" 个 @ 0x")
                    .append(Long.toHexString(elf.shoff)).append("（条目 ").append(elf.shentsize)
                    .append(" 字节）\n");
            sb.append("标志: 0x").append(Integer.toHexString(elf.flags)).append('\n');
            if (elf.comment != null && elf.comment.length() > 0)
                sb.append(".comment: ").append(elf.comment).append('\n');
            sb.append('\n').append("== 节表 ==\n");
            int shown = 0;
            for (Section s : elf.sections) {
                if (s.name == null || s.name.length() == 0) {
                    if (s.type == 0) continue;          // 跳过 NULL 节
                }
                if (shown >= 40) { sb.append("…（共 ").append(elf.sections.size()).append(" 个节）\n"); break; }
                sb.append(String.format(Locale.US, "%-18s %-10s %-3s 0x%08x  %8d  %s",
                        s.name.length() == 0 ? "(匿名)" : s.name, s.typeName(), s.flagsText(),
                        s.addr, s.size, s.outside ? "（超出已读范围）" : ""));
                sb.append('\n');
                shown++;
            }
            if (elf.truncated) sb.append("（节表超出已读取范围，仅解析到可读部分）\n");
        } else if ("ARSC".equals(magicName(data))) {
            sb.append("\n资源表（resources.arsc）：可用长按菜单「ARSC 编辑」查看条目。\n");
        } else if ("AXML".equals(magicName(data))) {
            sb.append("\n二进制 XML：可用「Manifest 编辑器++」或反编译查看。\n");
        }
        return sb.toString();
    }

    /**
     * ZIP/APK 的签名信息：META-INF 下的签名块 + 证书主题/有效期。
     * 需要真实文件（内存切片读不到中央目录）。
     */
    public static String signatureInfo(File f) {
        StringBuilder sb = new StringBuilder();
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            List<String> blocks = new ArrayList<String>();
            List<String> certs = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName().toUpperCase(Locale.US);
                if (!n.startsWith("META-INF/")) continue;
                if (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n.endsWith(".SF")) {
                    blocks.add(ze.getName() + "  (" + ze.getSize() + " 字节)");
                    if (!n.endsWith(".SF")) {
                        InputStream in = zf.getInputStream(ze);
                        try {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                            // 注意：APK v1 的 .RSA/.DSA/.EC 是 PKCS#7 SignedData（不是裸证书），
                            // 必须用 generateCertificates 解析，generateCertificate 会报 "Too short"
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            java.util.Collection<? extends java.security.cert.Certificate> chain =
                                    cf.generateCertificates(
                                            new java.io.ByteArrayInputStream(bo.toByteArray()));
                            for (java.security.cert.Certificate c : chain) {
                                if (!(c instanceof X509Certificate)) continue;
                                X509Certificate x = (X509Certificate) c;
                                certs.add("主题: " + x.getSubjectDN().getName()
                                        + "\n签发者: " + x.getIssuerDN().getName()
                                        + "\n有效期: " + x.getNotBefore() + " ~ " + x.getNotAfter()
                                        + "\n算法: " + x.getSigAlgName()
                                        + "\nSHA-256: " + KeyStoreUtil.fingerprint(x, "SHA-256"));
                            }
                        } finally {
                            in.close();
                        }
                    }
                }
            }
            if (blocks.isEmpty()) {
                sb.append("META-INF 下没有签名块（未签名或 v2/v3 仅用 APK Signing Block）。\n");
            } else {
                sb.append("== 签名块 ==\n");
                for (String s : blocks) sb.append(s).append('\n');
            }
            for (String c : certs) sb.append('\n').append("== 证书 ==\n").append(c).append('\n');
        } catch (Throwable t) {
            sb.append("签名信息解析失败: ").append(t.getMessage()).append('\n');
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** 读文件前 n 字节（读取失败返回 null） */
    public static byte[] readHead(File f, int n) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(n, Math.max(0, f.length()))];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    // ============================================================ 小工具

    static int u16(byte[] d, int p, boolean le) {
        if (p + 2 > d.length) throw new IndexOutOfBoundsException("u16@" + p);
        int b0 = d[p] & 0xFF, b1 = d[p + 1] & 0xFF;
        return le ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    static long u32(byte[] d, int p, boolean le) {
        if (p + 4 > d.length) throw new IndexOutOfBoundsException("u32@" + p);
        long b0 = d[p] & 0xFF, b1 = d[p + 1] & 0xFF, b2 = d[p + 2] & 0xFF, b3 = d[p + 3] & 0xFF;
        return le ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                  : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    static long u64(byte[] d, int p, boolean le) {
        if (p + 8 > d.length) throw new IndexOutOfBoundsException("u64@" + p);
        long v = 0;
        if (le) {
            for (int i = 7; i >= 0; i--) v = (v << 8) | (d[p + i] & 0xFFL);
        } else {
            for (int i = 0; i < 8; i++) v = (v << 8) | (d[p + i] & 0xFFL);
        }
        return v;
    }

    static String hex2(int v) {
        return (v < 16 ? "0" : "") + Integer.toHexString(v);
    }
}
