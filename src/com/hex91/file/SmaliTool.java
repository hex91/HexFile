package com.hex91.file;

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
 */
public final class SmaliTool {

    private SmaliTool() {}

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
            return ok ? null : "反编译失败（未知原因）";
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
            return ok ? "编译未产生输出" : "编译失败（语法或结构错误，检查最近改过的 smali）";
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
            byte[] buf = new byte[1 << 16];
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

    private static String fmtSize(long n) {
        if (n >= 1 << 20) return String.format("%.1f MB (%s 字节)", n / 1048576.0, fmtN(n));
        if (n >= 1024) return String.format("%.1f KB (%s 字节)", n / 1024.0, fmtN(n));
        return n + " 字节";
    }

    /**
     * Dex 属性（v1.66.5）：版本 / String·Type·Proto·Field·Method·Class 数量 / 校验状态。
     * 数量直接读 dex 头部索引区大小字段（与加载器语义一致）。
     * @return 多行展示文本；魔数不合法或文件过小返回 null
     */
    public static String dexProps(File dexFile) {
        java.io.FileInputStream fi = null;
        try {
            byte[] h = new byte[112];
            fi = new java.io.FileInputStream(dexFile);
            if (!readFull(fi, h)) return null;
            if (h[0] != 'd' || h[1] != 'e' || h[2] != 'x' || h[3] != '\n') return null;

            String ver = new String(h, 4, 3, "UTF-8");
            String api = ver.equals("037") ? "（Android 7.0+）"
                    : ver.equals("038") ? "（Android 8.0+）"
                    : ver.equals("039") ? "（Android 10+）" : "";
            Object[] ck = computeChecksums(dexFile);
            int ckCalc = (Integer) ck[0];
            byte[] sigCalc = (byte[]) ck[1];
            boolean ckOk = u32(h, 8) == ckCalc;
            boolean sigOk = true;
            for (int i = 0; i < 20; i++) if (h[12 + i] != sigCalc[i]) { sigOk = false; break; }

            StringBuilder sb = new StringBuilder();
            sb.append("Dex 版本: ").append(ver).append(api).append('\n');
            sb.append("文件大小: ").append(fmtSize(dexFile.length())).append('\n');
            sb.append("String 数: ").append(fmtN(u32(h, 56))).append('\n');
            sb.append("Type 数:   ").append(fmtN(u32(h, 64))).append('\n');
            sb.append("Proto 数:  ").append(fmtN(u32(h, 72))).append('\n');
            sb.append("Field 数:  ").append(fmtN(u32(h, 80))).append('\n');
            sb.append("Method 数: ").append(fmtN(u32(h, 88))).append('\n');
            sb.append("Class 数:  ").append(fmtN(u32(h, 96))).append('\n');
            sb.append("校验和:    ").append(ckOk ? "正常" : "不一致（可用 Dex 修复）").append('\n');
            sb.append("SHA-1 签名: ").append(sigOk ? "正常" : "不一致（可用 Dex 修复）");
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (fi != null) try { fi.close(); } catch (java.io.IOException ignored) {}
        }
    }

    /**
     * Dex 修复（v1.66.5）：修正 file_size / header_size / endian_tag，
     * 重算 SHA-1 签名（offset 12，覆盖字节 32..末尾）与 Adler-32 校验和
     * （offset 8，覆盖字节 12..末尾），原地覆盖（临时文件 + safeReplace），
     * 最后用 dexlib2 实际解析验证可加载性。
     * @param report 修复动作明细（调用方传入，追加写）
     * @return null=成功（含「无需修复」）；否则错误描述
     */
    public static String repairDex(File dexFile, StringBuilder report) {
        try {
            long len = dexFile.length();
            if (len < 112 || len > Integer.MAX_VALUE - 1024)
                return "文件大小异常，不是有效的 DEX";
            java.io.FileInputStream fi = new java.io.FileInputStream(dexFile);
            byte[] all = new byte[(int) len];
            boolean full;
            try {
                full = readFull(fi, all);
            } finally {
                fi.close();
            }
            if (!full) return "文件读取不完整";
            if (all[0] != 'd' || all[1] != 'e' || all[2] != 'x' || all[3] != '\n')
                return "魔数不是 DEX（无法按 DEX 修复）";

            java.util.List<String> fixes = new java.util.ArrayList<String>();
            // 1) file_size（offset 32）= 实际文件长度——头部编辑/手动拼接后最常见的损坏
            int fs = u32(all, 32);
            if (fs != (int) len) {
                putU32(all, 32, (int) len);
                fixes.add("file_size: " + fmtN(fs) + " → " + fmtN(len));
            }
            // 2) header_size（offset 36）标准恒为 0x70
            int hs = u32(all, 36);
            if (hs != 0x70) {
                putU32(all, 36, 0x70);
                fixes.add("header_size: 0x" + Integer.toHexString(hs) + " → 0x70");
            }
            // 3) endian_tag（offset 40）标准恒为 0x12345678（小端）
            int et = u32(all, 40);
            if (et != 0x12345678) {
                putU32(all, 40, 0x12345678);
                fixes.add("endian_tag: 0x" + Integer.toHexString(et) + " → 0x12345678");
            }
            // 4) SHA-1 签名（offset 12，20 字节，覆盖字节 32..末尾）
            java.security.MessageDigest sha =
                    java.security.MessageDigest.getInstance("SHA-1");
            sha.update(all, 32, all.length - 32);
            byte[] dig = sha.digest();
            boolean sigBad = false;
            for (int i = 0; i < 20; i++) if (all[12 + i] != dig[i]) { sigBad = true; break; }
            if (sigBad) {
                System.arraycopy(dig, 0, all, 12, 20);
                fixes.add("SHA-1 签名已重算");
            }
            // 5) Adler-32 校验和（offset 8，覆盖字节 12..末尾，不含魔数与校验和自身）
            java.util.zip.Adler32 adler = new java.util.zip.Adler32();
            adler.update(all, 12, all.length - 12);
            int ck = (int) adler.getValue();
            if (u32(all, 8) != ck) {
                putU32(all, 8, ck);
                fixes.add("Adler-32 校验和已重算");
            }

            // dexlib2 实际解析验证（直接从内存字节加载——避免 mmap 锁文件，
            // Windows 上 MappedByteBuffer 未 GC 前文件无法 rename/delete）→ 原地替换
            String parseErr = null;
            try {
                new org.jf.dexlib2.dexbacked.DexBackedDexFile(
                        Opcodes.forApi(34), all);
            } catch (Throwable t) {
                parseErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            File tmp = new File(dexFile.getParentFile(), dexFile.getName() + ".fixtmp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            try {
                fo.write(all);
            } finally {
                fo.close();
            }
            ApkEditSession.safeReplace(tmp, dexFile);

            if (fixes.isEmpty()) report.append("头部校验均正常，无需修复");
            else for (int i = 0; i < fixes.size(); i++) {
                if (i > 0) report.append('\n');
                report.append(fixes.get(i));
            }
            report.append(parseErr == null
                    ? "\ndexlib2 解析校验: 通过"
                    : "\ndexlib2 解析校验: 仍失败（" + parseErr + "）——存在头部校验之外的结构级损坏");
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
