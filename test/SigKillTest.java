package bin.HexFile;

import java.io.File;
import java.util.List;

/**
 * SigKill 桌面单测（纯逻辑，standalone main，无 junit 依赖）
 * 1) 合成 smali：验证 checkSignatures→0、Signature.verify/equals/hashCode→恒真 的改写正确性
 * 2) 可选集成测试：传入一个 APK 路径作为唯一参数时，抽 dex→baksmali→SigKill→smali 回编译，
 *    校验回编译不报错（验证 patch 不会破坏 smali 可汇编性）。
 */
public class SigKillTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            integrate(new File(args[0]));
        } else {
            synthetic();
        }
        System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    // ---------------- 合成测试 ----------------

    private static void synthetic() throws Exception {
        File root = new File("test/_sigkill_syn");
        deleteR(root);
        root.mkdirs();
        File dir = new File(root, "test");
        dir.mkdirs();

        // 含 checkSignatures 调用的类
        File caller = new File(dir, "Caller.smali");
        write(caller,
                ".class public Ltest/Caller;\n" +
                ".super Ljava/lang/Object;\n" +
                ".method public check(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/String;)V\n" +
                "    .registers 4\n" +
                "    invoke-virtual {p1, p2, p3}, Landroid/content/pm/PackageManager;->checkSignatures(Ljava/lang/String;Ljava/lang/String;)I\n" +
                "    move-result v0\n" +
                "    if-eqz v0, :m\n" +
                "    return-void\n" +
                "    :m\n" +
                "    return-void\n" +
                ".end method\n" +
                // invoke 与 move-result 之间隔 .line 指令（baksmali 常见输出）也应命中
                ".method public check2(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/String;)V\n" +
                "    .registers 4\n" +
                "    invoke-virtual {p1, p2, p3}, Landroid/content/pm/PackageManager;->checkSignatures(Ljava/lang/String;Ljava/lang/String;)I\n" +
                "    .line 42\n" +
                "    move-result v1\n" +
                "    return-void\n" +
                ".end method\n");

        // 含 Signature 三个方法的类（注意：真实存在于 android.content.pm 包）
        File sig = new File(dir, "Signature.smali");
        write(sig,
                ".class public Landroid/content/pm/Signature;\n" +
                ".super Ljava/lang/Object;\n" +
                ".method public verify([B)Z\n" +
                "    .registers 2\n" +
                "    .prologue\n" +
                "    const/4 v0, 0x0\n" +
                "    return v0\n" +
                ".end method\n" +
                ".method public equals(Ljava/lang/Object;)Z\n" +
                "    .registers 2\n" +
                "    const/4 v0, 0x0\n" +
                "    return v0\n" +
                ".end method\n" +
                ".method public hashCode()I\n" +
                "    .registers 1\n" +
                "    const/4 v0, 0x0\n" +
                "    return v0\n" +
                ".end method\n");

        // 负例 1：其它类也有 equals/hashCode —— 绝不能改（否则破坏全 app 语义）
        File decoy = new File(dir, "Decoy.smali");
        write(decoy,
                ".class public Ltest/Decoy;\n" +
                ".super Ljava/lang/Object;\n" +
                ".method public equals(Ljava/lang/Object;)Z\n" +
                "    .registers 2\n" +
                "    const/4 v0, 0x0\n" +
                "    return v0\n" +
                ".end method\n" +
                ".method public hashCode()I\n" +
                "    .registers 1\n" +
                "    const/4 v0, 0x0\n" +
                "    return v0\n" +
                ".end method\n");

        // 正例：Signature.equals 调用点 → move-result 强置 1（框架类只能 patch 调用点）
        File caller2 = new File(dir, "Caller2.smali");
        write(caller2,
                ".class public Ltest/Caller2;\n" +
                ".super Ljava/lang/Object;\n" +
                ".method public cmp(Landroid/content/pm/Signature;Landroid/content/pm/Signature;)Z\n" +
                "    .registers 3\n" +
                "    invoke-virtual {p1, p2}, Landroid/content/pm/Signature;->equals(Ljava/lang/Object;)Z\n" +
                "    move-result v2\n" +
                "    return v2\n" +
                ".end method\n");

        // 负例 2：接口里的 abstract equals/hashCode —— 无方法体，注入指令会破坏回编译
        File ifc = new File(dir, "Ifc.smali");
        write(ifc,
                ".class public abstract interface Ltest/Ifc;\n" +
                ".super Ljava/lang/Object;\n" +
                ".method public abstract equals(Ljava/lang/Object;)Z\n" +
                ".end method\n" +
                ".method public abstract hashCode()I\n" +
                ".end method\n");

        List<SigKill.Patch> patches = SigKill.run(root);

        // 应有 6 处 patch：checkSignatures×2 + verify + equals + hashCode + Signature.equals 调用点（负例 0 处）
        check("patch 总数 = 6（实际 " + patches.size() + "）", patches.size() == 6);
        for (SigKill.Patch p : patches) {
            check("负例文件未被改（" + p.file + "）", !p.file.contains("Decoy") && !p.file.contains("Ifc"));
        }
        int cs = 0, vf = 0, eq = 0, hc = 0, call = 0;
        for (SigKill.Patch p : patches) {
            if (p.kind.equals("checkSignatures→0")) cs++;
            else if (p.kind.equals("Signature调用→true")) call++;
            else if (p.kind.contains("verify")) vf++;
            else if (p.kind.contains("equals")) eq++;
            else if (p.kind.contains("hashCode")) hc++;
        }
        check("checkSignatures 改 0 命中 2", cs == 2);
        check("Signature 调用点强 true 命中 1", call == 1);
        check("verify 命中 1", vf == 1);
        check("equals 命中 1", eq == 1);
        check("hashCode 命中 1", hc == 1);

        // 校验改写后的文本
        String callerTxt = read(caller);
        check("Caller move-result 被替换为 const/4 v0, 0x0",
                callerTxt.contains("const/4 v0, 0x0") && !callerTxt.contains("move-result v0"));
        check("Caller check2 隔 .line 也命中（const/4 v1, 0x0）", callerTxt.contains("const/4 v1, 0x0"));
        check("Caller 保留后续 if-eqz", callerTxt.contains("if-eqz v0, :m"));

        String caller2Txt = read(caller2);
        check("Caller2 Signature.equals 调用点强 true（const/4 v2, 0x1）",
                caller2Txt.contains("const/4 v2, 0x1") && !caller2Txt.contains("move-result v2"));

        String sigTxt = read(sig);
        check("Signature.verify 体变为 const/4 v0, 0x1 + return v0",
                countOcc(sigTxt, "const/4 v0, 0x1") >= 3 && countOcc(sigTxt, "return v0") >= 3);
        check("Signature 原 const/4 v0, 0x0 已清除", !sigTxt.contains("const/4 v0, 0x0"));
        check("Signature.verify 保留 .registers/.prologue 指令", sigTxt.contains(".registers 2") && sigTxt.contains(".prologue"));

        deleteR(root);
    }

    // ---------------- 集成测试（需要 smali jar） ----------------

    private static void integrate(File apk) throws Exception {
        if (!apk.exists()) { System.out.println("APK 不存在，跳过集成测试: " + apk); return; }
        // 依赖运行时 classpath 含 baksmali/smali；此处仅调用 SmaliTool 走真实反编译/回编译
        File work = new File("test/_sigkill_int");
        deleteR(work);
        work.mkdirs();

        // 抽取 APK 内全部 dex
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        int dexIdx = 0, patched = 0;
        boolean allAsmOk = true;
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry ze = en.nextElement();
            String nm = ze.getName();
            if (!nm.toLowerCase().endsWith(".dex")) continue;
            File dex = new File(work, "src_" + (dexIdx++) + ".dex");
            java.io.InputStream in = zf.getInputStream(ze);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(dex);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close();

            File smali = new File(work, "smali_" + dexIdx);
            String err = SmaliTool.decompile(dex, smali, 4);
            if (err != null) { System.out.println("[FAIL] 反编译 " + nm + ": " + err); allAsmOk = false; continue; }

            List<SigKill.Patch> ps = SigKill.run(smali);
            patched += ps.size();
            System.out.println("  " + nm + " -> patch " + ps.size() + " 处");

            File outDex = new File(work, "out_" + dexIdx + ".dex");
            err = SmaliTool.assemble(smali, outDex, 4);
            if (err != null) { System.out.println("[FAIL] 回编译 " + nm + ": " + err); allAsmOk = false; }
        }
        zf.close();
        check("集成：APK=" + apk.getName() + " 反编译+patch+回编译 无错误", allAsmOk);
        System.out.println("  共 patch " + patched + " 处（0 也正常，仅验证流水线不破坏 smali）");
        if (!Boolean.getBoolean("sigkill.keep")) deleteR(work);
        else System.out.println("  工作目录保留: " + work.getAbsolutePath());
    }

    // ---------------- 小工具 ----------------

    private static void write(File f, String s) throws Exception {
        java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8");
        w.write(s); w.close();
    }
    private static String read(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s;
        while ((s = br.readLine()) != null) sb.append(s).append("\n");
        br.close();
        return sb.toString();
    }
    private static int countOcc(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteR(c);
        f.delete();
    }
}
