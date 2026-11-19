package com.hex91.file.apk;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.File;

/**
 * smali 编译/反编译工具（v1.7）—— org.smali:baksmali/smali:2.5.2 编程 API 封装。
 * 与 MT Manager 同源的 dexlib2 引擎，纯 Java 零 native 依赖。
 *
 * <p>v2.45.0（F-14/32）：本类是纯 apk 层、无 {@code Context}，不再产出中文文案。
 * 所有错误返回稳定 ASCII 码（{@code E_*}），多行报告用 {@code KEY=VALUE} 码行，
 * 由 {@code ui/SmaliReport} 按资源翻译成人类文本。调用方只判返回值是否为 null，
 * 与码的内容无关，故契约不变。</p>
 */
public final class SmaliTool {

    private SmaliTool() {}

    /** 稳定错误码（返回给调用方显示前经 ui/SmaliReport 翻译） */
    public static final String
            E_DECOMPILE_FAIL = "E_DECOMPILE_FAIL",
            E_COMPILE_NO_OUTPUT = "E_COMPILE_NO_OUTPUT",
            E_COMPILE_FAIL = "E_COMPILE_FAIL",
            E_TOO_SMALL = "E_TOO_SMALL",
            E_TOO_BIG = "E_TOO_BIG",
            E_READ_INCOMPLETE = "E_READ_INCOMPLETE",
            E_NOT_DEX = "E_NOT_DEX";

    /**
     * 反编译 .dex 文件到输出目录（目录结构按类名组织，*.smali）
     * @return 成功返回 null；失败返回错误描述
     */
    public static String decompile(File dexFile, File outDir, int jobs) {
        try {
            DexBackedDexFile dex = DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(34));
            BaksmaliOptions opt = new BaksmaliOptions();
            opt.apiLevel = 34;
            boolean ok = Baksmali.disassembleDexFile(dex, outDir, jobs, opt);
            return ok ? null : E_DECOMPILE_FAIL;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 把整个 smali 目录树汇编成单个 .dex 文件（smali → dex 回编译）
     * @param smaliDir 含 *.smali 的根目录（递归）
     * @param outDex   输出 dex 文件
     * @return 成功返回 null；失败返回错误描述
     */
    public static String assemble(File smaliDir, File outDex, int jobs) {
        return assembleMulti(new File[]{smaliDir}, outDex, jobs);
    }

    /**
     * 读 dex 头 method_ids_size（offset 88, u32 LE）—— 写回 APK 前检查 64K 方法引用限制。
     * 单个 dex 的方法引用数超 65535 时 Android 运行时无法加载。
     * @return 方法引用数；文件不合法返回 -1
     */
    public static int dexMethodCount(File dexFile) {
        java.io.FileInputStream fi = null;
        try {
            byte[] head = new byte[96];
            fi = new java.io.FileInputStream(dexFile);
            int n = 0, r;
            while (n < head.length && (r = fi.read(head, n, head.length - n)) > 0) n += r;
            if (n < 96) return -1;
            if (head[0] != 'd' || head[1] != 'e' || head[2] != 'x' || head[3] != '\n') return -1;
            return (head[88] & 0xFF) | (head[89] & 0xFF) << 8
                    | (head[90] & 0xFF) << 16 | (head[91] & 0xFF) << 24;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (fi != null) try { fi.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 多个 smali 目录合并汇编为单个 dex（Dex 编辑器++ 工程：跨 dex 的类合并到一起）
     */
    public static String assembleMulti(File[] smaliDirs, File outDex, int jobs) {
        try {
            SmaliOptions opt = new SmaliOptions();
            opt.apiLevel = 34;
            opt.outputDexFile = outDex.getAbsolutePath();
            opt.jobs = jobs;
            opt.verboseErrors = true;
            String[] paths = new String[smaliDirs.length];
            for (int i = 0; i < smaliDirs.length; i++) paths[i] = smaliDirs[i].getAbsolutePath();
            boolean ok = Smali.assemble(opt, paths);
            if (ok && outDex.exists() && outDex.length() > 0) return null;
            return ok ? E_COMPILE_NO_OUTPUT : E_COMPILE_FAIL;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    // ---------------- v1.66.5：Dex 属性 / Dex 修复 ----------------

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    /** 读满 len 字节到 buf（流可能分次返回）；读不足返回 false */
    private static boolean readFull(java.io.InputStream in, byte[] buf) throws java.io.IOException {
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        return n == buf.length;
    }

    /** 单遍流式计算 {Adler-32(字节12..末尾), SHA-1(字节32..末尾)}——即 dex 头部存储的校验值 */
    private static Object[] computeChecksums(File dexFile) throws Exception {
        java.util.zip.Adler32 adler = new java.util.zip.Adler32();
        java.security.MessageDigest sha =
                java.security.MessageDigest.getInstance("SHA-1");
        java.io.InputStream in = new java.io.FileInputStream(dexFile);
        try {
            byte[] buf = new byte[STREAM_BUF];
            boolean first = true;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (first) {
                    if (n > 12) adler.update(buf, 12, n - 12);
                    if (n > 32) sha.update(buf, 32, n - 32);
                    first = false;
                } else {
                    adler.update(buf, 0, n);
                    sha.update(buf, 0, n);
                }
            }
        } finally {
            in.close();
        }
        return new Object[] { (int) adler.getValue(), sha.digest() };
    }

    private static String fmtN(long n) {
        return String.format("%,d", n);
    }

    // dexProps 码行：V=版本 API=版本码 SZ=字节 S/T/P/F/M/C=各计数 CK/SG=OK|BAD
    // 数值用 fmtN 便于人类读报告原文；UI 侧 SmaliReport 按 KEY 解析后套资源模板。

    /**
     * Dex 属性（v1.66.5；v2.45.0 码行化）：版本 / 各索引区数量 / 校验状态。
     * 数量直接读 dex 头部索引区大小字段（与加载器语义一致）。
     * @return KEY=VALUE 码行文本（由 ui/SmaliReport 翻译）；魔数不合法或文件过小返回 null
     */
    public static String dexProps(File dexFile) {
        java.io.FileInputStream fi = null;
        try {
            byte[] h = new byte[112];
            fi = new java.io.FileInputStream(dexFile);
            if (!readFull(fi, h)) return null;
            if (h[0] != 'd' || h[1] != 'e' || h[2] != 'x' || h[3] != '\n') return null;

            String ver = new String(h, 4, 3, "UTF-8");
            String api = ver.equals("037") ? "37" : ver.equals("038") ? "38"
                    : ver.equals("039") ? "39" : "0";
            Object[] ck = computeChecksums(dexFile);
            int ckCalc = (Integer) ck[0];
            byte[] sigCalc = (byte[]) ck[1];
            boolean ckOk = u32(h, 8) == ckCalc;
            boolean sigOk = true;
            for (int i = 0; i < 20; i++) if (h[12 + i] != sigCalc[i]) { sigOk = false; break; }

            StringBuilder sb = new StringBuilder();
            sb.append("V=").append(ver).append('\n');
            sb.append("API=").append(api).append('\n');
            sb.append("SZ=").append(dexFile.length()).append('\n');
            sb.append("S=").append(fmtN(u32(h, 56))).append('\n');
            sb.append("T=").append(fmtN(u32(h, 64))).append('\n');
            sb.append("P=").append(fmtN(u32(h, 72))).append('\n');
            sb.append("F=").append(fmtN(u32(h, 80))).append('\n');
            sb.append("M=").append(fmtN(u32(h, 88))).append('\n');
            sb.append("C=").append(fmtN(u32(h, 96))).append('\n');
            sb.append("CK=").append(ckOk ? "OK" : "BAD").append('\n');
            sb.append("SG=").append(sigOk ? "OK" : "BAD");
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (fi != null) try { fi.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /**
     * Dex 修复（v1.66.5；v1.67.2 流式 + 原地修补）：修正 file_size / header_size / endian_tag，
     * 重算 SHA-1 签名（offset 12，覆盖字节 32..末尾）与 Adler-32 校验和
     * （offset 8，覆盖字节 12..末尾）。
     *
     * 两点设计约束决定了实现顺序：
     *  1) file_size 本身在 SHA-1 覆盖范围内 → 必须先修头部再算签名；
     *  2) Adler-32 覆盖签名区 → 必须先回填签名再算校验和。
     *
     * 全程流式：峰值内存固定 256KB（与 DEX 体积无关），因此没有大小上限。
     * 只原地改写头部 136 字节（112 字节头 + 20 字节签名 + 4 字节校验和），
     * 代码/数据区一个字节都不碰——不再整文件复制替换，也不需要临时文件。
     * 头部与校验值都正常时直接返回、零写盘。
     *
     * @param report 修复动作明细（调用方传入，追加写）
     * @return null=成功（含「无需修复」）；否则错误描述
     */
    /** 流式缓冲（256KB）：IO 次数与内存占用都不随文件增长 */
    private static final int STREAM_BUF = 1 << 18;

    /** 超过此大小跳过 dexlib2 深度解析（其需整文件入内存），改用流式结构校验 */
    private static final long DEEP_VERIFY_MAX = 32L * 1024 * 1024;

    public static String repairDex(File dexFile, StringBuilder report) {
        long t0 = System.currentTimeMillis();
        try {
            long len = dexFile.length();
            if (len < 112) return E_TOO_SMALL;
            if (len > Integer.MAX_VALUE - 1024L) return E_TOO_BIG;

            // 只读 112 字节头部
            byte[] h = new byte[112];
            java.io.FileInputStream hi = new java.io.FileInputStream(dexFile);
            boolean full;
            try {
                full = readFull(hi, h);
            } finally {
                hi.close();
            }
            if (!full) return E_READ_INCOMPLETE;
            if (h[0] != 'd' || h[1] != 'e' || h[2] != 'x' || h[3] != '\n')
                return E_NOT_DEX;

            byte[] origH = h.clone();   // 原始头（含旧签名/校验和），写入失败时回滚用

            java.util.List<String> fixes = new java.util.ArrayList<String>();
            // 1) file_size（offset 32）= 实际文件长度——头部编辑/手动拼接后最常见的损坏
            int fs = u32(h, 32);
            if (fs != (int) len) {
                putU32(h, 32, (int) len);
                fixes.add("FIX_FILE_SIZE=" + fs + ":" + len);
            }
            // 2) header_size（offset 36）标准恒为 0x70
            int hs = u32(h, 36);
            if (hs != 0x70) {
                putU32(h, 36, 0x70);
                fixes.add("FIX_HEADER_SIZE=" + Integer.toHexString(hs));
            }
            // 3) endian_tag（offset 40）标准恒为 0x12345678（小端）
            int et = u32(h, 40);
            if (et != 0x12345678) {
                putU32(h, 40, 0x12345678);
                fixes.add("FIX_ENDIAN=" + Integer.toHexString(et));
            }

            // 先流式单遍算出当前文件的校验值：决定要不要写盘
            Object[] ck = computeChecksums(dexFile);
            int ckCalc = (Integer) ck[0];
            byte[] sigCalc = (byte[]) ck[1];
            boolean sigOk = true;
            for (int i = 0; i < 20; i++) if (h[12 + i] != sigCalc[i]) { sigOk = false; break; }
            boolean ckOk = u32(h, 8) == ckCalc;

            if (fixes.isEmpty() && sigOk && ckOk) {
                // 快路径：零写盘，只做可加载性校验
                report.append("NO_FIX\n");
                report.append(verifyAfter(dexFile, len));
                report.append("\nMS_NOWRITE:").append(System.currentTimeMillis() - t0);
                return null;
            }

            // 4) 原地改写：先落修正后的头部，再流式计算 SHA-1（覆盖 32..末尾）
            java.security.MessageDigest sha =
                    java.security.MessageDigest.getInstance("SHA-1");
            java.io.RandomAccessFile raf = null;
            try {
                raf = new java.io.RandomAccessFile(dexFile, "rw");
                raf.seek(0);
                raf.write(h, 0, 112);          // 头部修正（签名/校验和仍为旧值）
                sha.update(h, 32, 112 - 32);   // 头部 32..112 也在签名范围内
                raf.seek(112);
                byte[] buf = new byte[STREAM_BUF];
                int n;
                while ((n = raf.read(buf)) > 0) sha.update(buf, 0, n);
                byte[] dig = sha.digest();

                boolean sigDiffers = false;
                for (int i = 0; i < 20; i++)
                    if (origH[12 + i] != dig[i]) { sigDiffers = true; break; }
                if (sigDiffers) fixes.add("FIX_SHA1");
                raf.seek(12);
                raf.write(dig, 0, 20);

                // 5) 回填签名 → 流式算 Adler-32（覆盖 12..末尾，含刚写入的新签名）
                raf.seek(12);
                java.util.zip.Adler32 adler = new java.util.zip.Adler32();
                while ((n = raf.read(buf)) > 0) adler.update(buf, 0, n);
                int newCk = (int) adler.getValue();
                if (u32(origH, 8) != newCk) fixes.add("FIX_ADLER");
                putU32(h, 8, newCk);
                raf.seek(8);
                raf.write(h, 8, 4);
            } catch (Throwable t) {
                // 只回滚头部 136 字节；代码/数据区自始至终没动过
                if (raf != null) {
                    try {
                        raf.seek(0);
                        raf.write(origH, 0, 112);
                    } catch (Throwable ignored) {}
                }
                throw t;
            } finally {
                if (raf != null) try { raf.close(); } catch (java.io.IOException ignored) {}
            }

            // 6) 可加载性校验（小文件 dexlib2 真解析 / 大文件流式结构校验）
            String verify = verifyAfter(dexFile, len);

            for (int i = 0; i < fixes.size(); i++) {
                if (i > 0) report.append('\n');
                report.append(fixes.get(i));
            }
            report.append('\n').append(verify);
            report.append("\nSTREAM_FIX_MS:").append(System.currentTimeMillis() - t0);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 修复后校验：≤32MB 用 dexlib2 真实解析（能抓结构级损坏，代价是整文件入内存）；
     * 更大的 DEX 走流式结构校验，避免为了「验证」反而 OOM。
     */
    private static String verifyAfter(File dexFile, long len) {
        if (len <= DEEP_VERIFY_MAX) {
            try {
                byte[] all = new byte[(int) len];
                java.io.FileInputStream fi = new java.io.FileInputStream(dexFile);
                boolean full;
                try {
                    full = readFull(fi, all);
                } finally {
                    fi.close();
                }
                if (!full) return "DEXLIB_READ_INCOMPLETE";
                new DexBackedDexFile(Opcodes.forApi(34), all);
                return "DEXLIB_OK";
            } catch (OutOfMemoryError oom) {
                return "DEXLIB_OOM_SKIP";
            } catch (Throwable t) {
                return "DEXLIB_FAIL:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            }
        }
        String e = structuralCheck(dexFile, len);
        return e == null ? "STRUCT_OK" : "STRUCT_FAIL:" + e;
    }

    /**
     * 流式结构校验（不整文件入内存）：头部各索引区、map_list 是否都落在文件内。
     * 用于超大 DEX——dexlib2 深度解析需整文件入内存，在这里反而会成为 OOM 源。
     * @return null=通过；否则问题描述
     */
    private static String structuralCheck(File dexFile, long len) {
        byte[] h = new byte[112];
        java.io.FileInputStream fi = null;
        try {
            fi = new java.io.FileInputStream(dexFile);
            if (!readFull(fi, h)) return "HEAD_READ_INCOMPLETE";
        } catch (java.io.IOException e) {
            return "HEAD_READ_FAIL:" + e.getMessage();
        } finally {
            if (fi != null) try { fi.close(); } catch (java.io.IOException ignored) {}
        }

        String[] names = {"string_ids", "type_ids", "proto_ids", "field_ids",
                "method_ids", "class_defs"};
        int[] cntOff = {56, 64, 72, 80, 88, 96};
        int[] itemSize = {4, 4, 12, 8, 8, 32};
        for (int i = 0; i < names.length; i++) {
            int cnt = u32(h, cntOff[i]);
            int off = u32(h, cntOff[i] + 4);
            if (cnt < 0 || off < 0) return "OOB:" + names[i];
            if (cnt == 0) continue;
            if (off < 112 || (long) off + (long) cnt * itemSize[i] > len)
                return "OOB:" + names[i];
        }

        int mapOff = u32(h, 52);
        if (mapOff == 0) return null;              // 无 map_list（不常见但可接受）
        if (mapOff < 112 || (long) mapOff + 4 > len) return "OOB:map_list";
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(dexFile, "r");
            raf.seek(mapOff);
            byte[] b4 = new byte[4];
            raf.readFully(b4);
            int n = u32(b4, 0);
            if (n <= 0 || n > 4096) return "MAP_COUNT:" + n;
            if ((long) mapOff + 4 + (long) n * 12 > len) return "OOB:map_list";
            byte[] it = new byte[12];
            for (int i = 0; i < n; i++) {
                raf.readFully(it);
                int moff = u32(it, 8);
                if (moff < 0 || moff > len) return "OOB:map_entry";
            }
        } catch (java.io.IOException e) {
            return "MAP_READ_FAIL:" + e.getMessage();
        } finally {
            if (raf != null) try { raf.close(); } catch (java.io.IOException ignored) {}
        }
        return null;
    }
}
