package com.hex91.file.apk;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * 常见格式：魔数 + 类型名 + 说明码。
     *
     * <p>v2.32.0（F-14/19）：第三列原来是中文说明句，直接拼进「文件解析」报告。
     * apk 层不该知道语言，所以改存**说明码**（同 {@link #magicName} 的 ASCII 记号，
     * 只是把 ZIP 的三个变体分开），文案由 ui 层 {@code FileReport} 按码取资源。</p>
     */
    private static final Object[][] MAGICS = {
            // {bytes, 名称, 说明码}
            {new int[]{0x7F, 'E', 'L', 'F'}, "ELF", "ELF"},
            {new int[]{'P', 'K', 0x03, 0x04}, "ZIP", "ZIP"},
            {new int[]{'P', 'K', 0x05, 0x06}, "ZIP", "ZIP_EMPTY"},
            {new int[]{'P', 'K', 0x07, 0x08}, "ZIP", "ZIP_SPLIT"},
            {new int[]{'d', 'e', 'x', '\n', '0'}, "DEX", "DEX"},
            {new int[]{0xFE, 0xED, 0xFE, 0xED}, "JKS", "JKS"},
            {new int[]{0xBC, 0xCF, 0x25, 0x20}, "BKS", "BKS"},
            {new int[]{0xCE, 0xCE, 0xCE, 0xCE}, "BKS", "BKS_OLD"},
            {new int[]{0x30, 0x82}, "DER", "DER"},
            {new int[]{0x30, 0x81}, "DER", "DER"},
            {new int[]{0x02, 0x00, 0x0C, 0x00}, "ARSC", "ARSC"},
            {new int[]{0x03, 0x00, 0x08, 0x00}, "AXML", "AXML"},
            {new int[]{0x89, 'P', 'N', 'G'}, "PNG", "PNG"},
            {new int[]{0xFF, 0xD8, 0xFF}, "JPEG", "JPEG"},
            {new int[]{'G', 'I', 'F', '8'}, "GIF", "GIF"},
            {new int[]{'R', 'I', 'F', 'F'}, "RIFF", "RIFF"},
            {new int[]{0x1F, 0x8B}, "GZIP", "GZIP"},
            {new int[]{0xFD, '7', 'z', 'X', 'Z', 0x00}, "XZ", "XZ"},
            {new int[]{0x28, 0xB5, 0x2F, 0xFD}, "ZSTD", "ZSTD"},
            {new int[]{'7', 'z', 0xBC, 0xAF, 0x27, 0x1C}, "7Z", "7Z"},
            {new int[]{'R', 'a', 'r', '!'}, "RAR", "RAR"},
            {new int[]{'%', 'P', 'D', 'F'}, "PDF", "PDF"},
            {new int[]{0xCA, 0xFE, 0xBA, 0xBE}, "CLASS", "CLASS"},
            {new int[]{'M', 'Z'}, "PE", "PE"},
            {new int[]{0x00, 'a', 's', 'm'}, "WASM", "WASM"},
            {new int[]{0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00}, "TTF", "TTF"},
            {new int[]{'O', 'T', 'T', 'O'}, "OTF", "OTF"},
            {new int[]{'u', 's', 't', 'a', 'r'}, "TAR", "TAR"},
            {new int[]{0x53, 0x51, 0x4C, 0x69, 0x74, 0x65}, "SQLITE", "SQLITE"},
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

    /** 魔数对应的说明码（无法识别返回 null），文案在 ui 层按码取 */
    public static String magicDescTag(byte[] d) {
        if (d == null) return null;
        for (Object[] m : MAGICS) {
            int[] sig = (int[]) m[0];
            if (d.length < sig.length) continue;
            boolean hit = true;
            for (int i = 0; i < sig.length; i++) {
                if ((d[i] & 0xFF) != (sig[i] & 0xFF)) { hit = false; break; }
            }
            if (hit) return (String) m[2];
        }
        return null;
    }

    /**
     * 结合扩展名细化类型 —— 只回**类型码**（v2.32.0，F-14/19）。
     *
     * <p>取值：{@code APK} / {@code JAR} / {@code AAB} / {@code PKCS12} / {@code X509} /
     * {@code DER} / {@code JKS_MISMATCH} / {@code BKS_MISMATCH} / {@code UNKNOWN}，
     * 其余直接回魔数名（{@code ELF} / {@code ZIP} / {@code PNG} …）。
     * 文案由 ui 层 {@code FileReport.typeText} 拼，apk 层不再产出中文。</p>
     */
    public static String typeCode(byte[] d, String fileName) {
        String magic = magicName(d);
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if ("ZIP".equals(magic)) {
            if (lower.endsWith(".apk")) return "APK";
            if (lower.endsWith(".jar")) return "JAR";
            if (lower.endsWith(".aab")) return "AAB";
        }
        if ("DER".equals(magic)) {
            if (lower.endsWith(".p12") || lower.endsWith(".pfx")) return "PKCS12";
            if (lower.endsWith(".cer") || lower.endsWith(".crt") || lower.endsWith(".pem"))
                return "X509";
            return "DER";
        }
        if (magic == null) {
            if (lower.endsWith(".jks")) return "JKS_MISMATCH";
            if (lower.endsWith(".bks")) return "BKS_MISMATCH";
            return "UNKNOWN";
        }
        return magic;
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

        /** ELF 类型码（NONE/REL/EXEC/DYN/CORE 或 0x…），注解文案在 ui 层 */
        public String typeCode() {
            switch (type) {
                case 0: return "NONE";
                case 1: return "REL";
                case 2: return "EXEC";
                case 3: return "DYN";
                case 4: return "CORE";
                default: return "0x" + Integer.toHexString(type);
            }
        }

        public String machineName() {
            switch (machine) {
                case 3: return "x86";
                case 8: return "MIPS";
                case 20: return "PowerPC";
                case 21: return "PowerPC64";
                case 40: return "ARM";
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
