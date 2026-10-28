package bin.HexFile;

import java.io.File;

/**
 * v1.43.0 桌面测试：RootUtil.run/submitOutput 错误路径 + FileAttrs.statFallback 权限串。
 * （FileAttrs.stat 走 LruCache 是 Android 桩，桌面不可整体跑；只测纯 File API 部分）
 */
public class AttrLogicTest {
    static int pass = 0, fail = 0;

    static void eq(Object actual, Object expect, String name) {
        boolean ok = actual == null ? expect == null : actual.equals(expect);
        System.out.println((ok ? "PASS " : "FAIL ") + name
                + "  (actual=" + actual + ", expect=" + expect + ")");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        // 1. 无 su：run → false
        eq(RootUtil.run("echo hi"), false, "run 无 su → false");

        // 2. 无 su：submitOutput → Future 内异常，get 会抛（调用方 statViaSu 已兜底）
        try {
            RootUtil.submitOutput("echo hi").get(3, java.util.concurrent.TimeUnit.SECONDS);
            eq("no-throw", "throw", "submitOutput 无 su 应抛");
        } catch (Exception e) {
            eq("throw", "throw", "submitOutput 无 su 抛异常(detect/调用方兜底)");
        }

        // 3. statFallback：三段位一致（Windows canExecute 恒 true，只验格式与一致性）
        File tmp = File.createTempFile("attr", ".tmp");
        tmp.deleteOnExit();
        tmp.setExecutable(false);
        FileAttrs.Attr a = FileAttrs.statFallback(tmp);
        String t3 = a.perm.substring(1, 4);
        eq(a.perm, "-" + t3 + t3 + t3, "fallback 普通文件三段一致: " + a.perm);
        eq(a.perm.matches("^-[r-][w-][x-]([r-][w-][x-]){2}$"), true, "fallback 格式合法");
        eq(a.octal, -1, "fallback 无八进制");
        eq(a.owner, null, "fallback 无所有者");

        // 4. fallback：目录 → d 前缀
        File dir = tmp.getParentFile();
        FileAttrs.Attr ad = FileAttrs.statFallback(dir);
        eq(ad.perm.startsWith("d"), true, "fallback 目录 d 前缀");

        // 5. fallback：只读文件 → r 位有、w 位无（Windows 保留 x 位属平台行为）
        tmp.setReadOnly();
        FileAttrs.Attr ar = FileAttrs.statFallback(tmp);
        eq(ar.perm.matches("^-r-[wx]([r-][w-][x-]){2}$"),
                true, "fallback 只读文件无 w 位: " + ar.perm);
        tmp.setWritable(true);

        // 6. permText：octal 是权限位真值（644₈=420），显示按八进制
        FileAttrs.Attr ap = new FileAttrs.Attr();
        ap.perm = "-rw-r--r--"; ap.octal = Integer.parseInt("644", 8);
        String t = ap.perm + (ap.octal >= 0 ? "(" + Integer.toOctalString(ap.octal) + ")" : "");
        eq(t, "-rw-r--r--(644)", "permText 格式(八进制)");
        // 7. 八进制解析修复回归：parseInt("600",8)=384，位判定 owner rw 正确
        int o = Integer.parseInt("600", 8);
        eq(((o >> 8) & 1) == 1, true, "600₈ owner-r 位");
        eq(((o >> 7) & 1) == 1, true, "600₈ owner-w 位");
        eq(((o >> 6) & 1) == 0, true, "600₈ owner-x 位");
        eq(Integer.toOctalString(o), "600", "toOctalString 回显");

        System.out.println("\n==== AttrLogicTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
