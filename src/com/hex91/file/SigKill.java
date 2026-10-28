package com.hex91.file;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 去除签名校验（对标 MT Manager 同名功能）：对 baksmali 反编译后的 smali 目录做启发式 patch，
 * 让签名校验逻辑在字节码层恒为真，从而绕过应用的签名一致性检查。
 *
 * 策略（安全、不破坏 getPackageInfo 返回的 signatures 对象）：
 *  1. PackageManager.checkSignatures(...) 调用点返回值强置 0（SIGNATURE_MATCH = 0）——任意类安全
 *  2. Landroid/content/pm/Signature;->verify(...) / ->equals(...) 调用点返回值强置 1（true）
 *     —— Signature 是框架类、不在目标 APK 内，所以以调用点 patch 为主
 *  3. 若 APK 自带 Landroid/content/pm/Signature; 副本，则直接重写其 verify/equals/hashCode 方法体为恒真
 *
 * 重要作用域限制：verify/equals/hashCode 方法体重写只作用于 .class 声明为 Landroid/content/pm/Signature; 的文件，
 * 且跳过 abstract/native 方法（无方法体），否则会误伤任意类的 equals/hashCode 语义并破坏接口回编译。
 *
 * 说明：不采用“清空 signatures 字段”的做法——那会让 sig.equals(expected) 因 null 触发 NPE 或判定失败，
 * 反而把校验变严。改 equals/verify/hashCode 为恒真后，sig 仍是非空对象，比较恒通过。
 *
 * 纯 Java、无 Android 依赖，可在桌面用真实 APK 反编译产物直接测试。
 */
public final class SigKill {

    private SigKill() {}

    /** 一处 patch 记录，供 UI 报告 */
    public static final class Patch {
        public final String file;   // 相对 smali 根的路径
        public final int line;      // 1-based 行号
        public final String kind;
        public final String detail;
        Patch(String f, int l, String k, String d) { file = f; line = l; kind = k; detail = d; }
        @Override public String toString() { return file + ":" + line + " [" + kind + "] " + detail; }
    }

    /** 对 smaliRoot 下所有 *.smali 做原地 patch，返回全部 patch 记录（空=未改动） */
    public static List<Patch> run(File smaliRoot) {
        List<Patch> out = new ArrayList<Patch>();
        if (smaliRoot == null || !smaliRoot.isDirectory()) return out;
        List<File> files = new ArrayList<File>();
        walk(smaliRoot, files);
        String rootPath = smaliRoot.getAbsolutePath();
        if (!rootPath.endsWith(File.separator)) rootPath += File.separator;
        for (File f : files) {
            if (!f.getName().endsWith(".smali")) continue;
            String rel = f.getAbsolutePath();
            if (rel.startsWith(rootPath)) rel = rel.substring(rootPath.length());
            processFile(f, rel, out);
        }
        return out;
    }

    private static void walk(File dir, List<File> acc) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(f, acc);
            else acc.add(f);
        }
    }

    private static void processFile(File file, String rel, List<Patch> out) {
        List<String> src;
        try {
            src = readLines(file);
        } catch (IOException e) {
            return;
        }
        List<String> dst = new ArrayList<String>(src.size() + 8);

        String cls = null;                  // 当前文件 .class 声明的类描述符（如 Landroid/content/pm/Signature;）
        boolean inMethod = false;
        boolean rewriteBody = false;        // 正在重写 verify/equals/hashCode 方法体
        String rewriteKind = "";            // 重写的具体类型（用于 patch 记录）
        int regCount = -1;                  // 当前方法 .registers 数值（-1 未知）
        int paramCount = 0;                 // 当前方法参数占用寄存器数（含 this）
        int forceMoveResult = -1;           // >=0：上一 invoke 的 move-result 强置该值（0=checkSignatures，1=Signature.verify/equals）
        int checkSigLine = -1;

        for (int i = 0; i < src.size(); i++) {
            String raw = src.get(i);
            String t = raw.trim();
            int lineNo = i + 1;

            // --- 类声明：verify/equals/hashCode 重写仅限 android.content.pm.Signature 自身 ---
            if (t.startsWith(".class ")) {
                cls = extractClassDesc(t);
                dst.add(raw);
                continue;
            }
            // --- 方法开始 ---
            if (t.startsWith(".method ")) {
                inMethod = true;
                regCount = -1;
                paramCount = parseParamWords(t);
                // 仅 Signature 类内的具体方法（abstract/native 无方法体，注入指令会破坏回编译）
                rewriteBody = "Landroid/content/pm/Signature;".equals(cls)
                        && !t.contains(" abstract ") && !t.contains(" native ")
                        && isTargetMethod(t);
                rewriteKind = rewriteBody ? rewriteKindOf(t) : "";
                dst.add(raw);
                continue;
            }
            // --- 方法结束 ---
            if (t.equals(".end method")) {
                if (rewriteBody) {
                    // 保留此前已写入的指令类行？不——指令行已全部跳过，仅保留 .directives。
                    // 注入恒真返回体（v0 必存在：非静态方法至少有 this=v0）
                    dst.add("    const/4 v0, 0x1");
                    dst.add("    return v0");
                    out.add(new Patch(rel, lineNo, rewriteKind,
                            "方法体改为恒真返回（const/4 v0, 0x1; return v0）"));
                    rewriteBody = false;
                }
                inMethod = false;
                dst.add(raw);
                continue;
            }
            // --- 寄存器声明 ---
            if (t.startsWith(".registers")) {
                regCount = parseRegCount(t);
                dst.add(raw);
                continue;
            }
            if (t.startsWith(".locals")) {
                if (regCount < 0) regCount = parseRegCount(t);
                dst.add(raw);
                continue;
            }
            // --- 重写方法体：仅保留指令无关的 .directives，丢弃代码行/标签 ---
            if (inMethod && rewriteBody) {
                if (t.startsWith(".")) dst.add(raw);   // .registers/.locals/.prologue/.line/.param/.annotation/.catch/.packed-switch…
                // 其余（指令、:label）丢弃，由结尾注入的 const/return 取代
                continue;
            }
            // --- checkSignatures 调用：标记下一行 move-result 改 0 ---
            if (inMethod && t.contains("PackageManager;->checkSignatures")) {
                dst.add(raw);
                forceMoveResult = 0;
                checkSigLine = lineNo;
                continue;
            }
            // --- Signature.verify/equals 调用点：结果强置 true（框架类不在 APK 内，只能 patch 调用点） ---
            if (inMethod && t.startsWith("invoke")
                    && (t.contains("Landroid/content/pm/Signature;->verify(")
                        || t.contains("Landroid/content/pm/Signature;->equals("))) {
                dst.add(raw);
                forceMoveResult = 1;
                continue;
            }
            // --- move-result 紧跟目标 invoke：强置结果 ---
            if (forceMoveResult >= 0) {
                if (t.startsWith("move-result")) {
                    String reg = extractMoveResultReg(t);
                    if (reg != null) {
                        String repl;
                        String kind;
                        if (forceMoveResult == 0) {
                            if (t.startsWith("move-result-wide")) repl = "    const-wide " + reg + ", 0x0";
                            else repl = "    const/4 " + reg + ", 0x0";
                            kind = "checkSignatures→0";
                        } else {
                            repl = "    const/4 " + reg + ", 0x1";
                            kind = "Signature调用→true";
                        }
                        dst.add(repl);
                        out.add(new Patch(rel, lineNo, kind,
                                "返回值强置 " + (forceMoveResult == 0 ? "0（SIGNATURE_MATCH）" : "1（true）")
                                        + "，原: " + t));
                    } else {
                        dst.add(raw);
                    }
                    forceMoveResult = -1;
                    continue;
                } else if (t.isEmpty() || t.startsWith("#") || t.startsWith(".")) {
                    dst.add(raw);   // 空白/注释/.line 等指令，继续等待 move-result
                    continue;
                } else {
                    forceMoveResult = -1;  // 异常结构，放弃本次 patch
                }
            }
            dst.add(raw);
        }

        if (dst.equals(src)) return;  // 无改动，不写盘
        try {
            writeLines(file, dst);
        } catch (IOException e) {
            // 写盘失败不抛，交给上层流程报错
        }
    }

    // ---------------- 解析辅助 ----------------

    /** 是否是需要重写的签名相关小方法（verify/equals/hashCode） */
    private static boolean isTargetMethod(String methodLine) {
        return matches(methodLine, "verify", "([B)Z")
                || matches(methodLine, "verify", "([B[B)Z")
                || matches(methodLine, "equals", "(Ljava/lang/Object;)Z")
                || matches(methodLine, "hashCode", "()I");
    }

    /** 从 `.class public final Landroid/content/pm/Signature;` 提取类描述符（最后一个 token） */
    private static String extractClassDesc(String classLine) {
        String t = classLine.trim();
        int sp = t.lastIndexOf(' ');
        return sp < 0 ? t.substring(7).trim() : t.substring(sp + 1).trim();
    }

    /** 返回重写类型的中文标签（用于 patch 记录） */
    private static String rewriteKindOf(String methodLine) {
        if (matches(methodLine, "verify", "([B)Z") || matches(methodLine, "verify", "([B[B)Z"))
            return "Signature.verify→true";
        if (matches(methodLine, "equals", "(Ljava/lang/Object;)Z")) return "Signature.equals→true";
        if (matches(methodLine, "hashCode", "()I")) return "Signature.hashCode→1";
        return "签名方法体→恒真";
    }

    /** 匹配 `.method <flags> <name><desc>`，忽略 flags；desc 已是完整 (params)ret 形态 */
    private static boolean matches(String methodLine, String name, String desc) {
        // 形如: .method public verify([B)Z  或  .method protected static foo(...)V
        int idx = methodLine.indexOf(name + desc);
        if (idx < 0) return false;
        // 确保前面是空白或方法名起始
        int pre = idx - 1;
        return pre < 0 || Character.isWhitespace(methodLine.charAt(pre));
    }

    /** 解析方法参数占用的寄存器字数（含非静态方法的 this） */
    private static int parseParamWords(String methodLine) {
        int p = methodLine.indexOf('(');
        int q = methodLine.indexOf(')', p);
        if (p < 0 || q < 0 || q < p) return 0;
        String desc = methodLine.substring(p + 1, q);
        int words = 0;
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == '[') { i++; continue; }                 // 数组前缀，基数稍后计
            if (c == 'L') { while (i < desc.length() && desc.charAt(i) != ';') i++; words++; i++; }
            else { words++; i++; }                            // 基本类型 B/C/D/F/I/J/S/Z 各 1 字
        }
        if (!methodLine.contains(" static ")) words += 1;     // 非静态方法含 this
        return words;
    }

    private static int parseRegCount(String line) {
        // .registers 5  /  .locals 3
        String[] sp = line.trim().split("\\s+");
        for (int i = 1; i < sp.length; i++) {
            try { return Integer.parseInt(sp[i]); } catch (NumberFormatException ignore) {}
        }
        return -1;
    }

    private static String extractMoveResultReg(String line) {
        // move-result v0  /  move-result-wide v1
        String[] sp = line.trim().split("\\s+");
        for (int i = 1; i < sp.length; i++) {
            String s = sp[i];
            if (s.startsWith("v") || s.startsWith("p")) return s;
        }
        return null;
    }

    // ---------------- IO ----------------

    private static List<String> readLines(File f) throws IOException {
        List<String> ls = new ArrayList<String>();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        String s;
        while ((s = br.readLine()) != null) ls.add(s);
        br.close();
        return ls;
    }

    private static void writeLines(File f, List<String> ls) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        for (String s : ls) { w.write(s); w.write("\n"); }
        w.close();
    }
}
