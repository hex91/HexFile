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
}
