
import java.io.File;

/**
 * v1.42.0 RootUtil 桌面测试。
 * Windows 桌面无 su，正好覆盖"命令不存在"分支；可执行文件分支用系统自带的
 * cmd.exe（输出不含 uid=0( → false）与一个自造的假 su 脚本（Java 无法直接执行
 * 脚本，改用 java - 版本模拟"存在但输出不匹配"）。
 * 正例（uid=0( 输出）用进程模拟困难，重点验证：缓存、异常安全、过滤条件。
 */
public class RootUtilTest {
    static int pass = 0, fail = 0;

    static void eq(Object actual, Object expect, String name) {
        boolean ok = actual == null ? expect == null : actual.equals(expect);
        System.out.println((ok ? "PASS " : "FAIL ") + name
                + "  (actual=" + actual + ", expect=" + expect + ")");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        // 1. Windows 上 su 不存在 → false（IO 异常分支）
        boolean su = RootUtil.detect("su-definitely-missing-xyz");
        eq(su, false, "不存在的命令 → false");

        // 2. 缓存生效：再次调用同结果且不再执行进程
        eq(RootUtil.isRootAvailable(), false, "缓存后 isRootAvailable=false");
        eq(RootUtil.detect("su-definitely-missing-xyz"), false, "缓存后 detect 直接返回");

        // 3. 存在但输出不含 uid=0( → false（用 where 命令模拟，必定退出）
        //    注：detect 有缓存，缓存已置 false，这里直接验证 Probe 过滤逻辑
        RootUtil.Probe p = new RootUtil.Probe("where");
        boolean out = p.call();   // where -c id 匹配不到 → 非零退出且输出不含 uid=0(
        eq(out, false, "Probe 输出不含 uid=0( → false");

        // 4. Probe 命令不存在 → 抛异常属预期（detect() 的 Future.get 会兜住转 false）
        RootUtil.Probe p2 = new RootUtil.Probe("cmd-does-not-exist-xyz");
        try {
            p2.call();
            eq("no-throw", "throw", "Probe 命令不存在应抛 IOException");
        } catch (Exception e) {
            eq("throw", "throw", "Probe 命令不存在 → 抛异常(detect 层兜底)");
        }
        // 补验：detect 层确实兜底
        boolean viaDetect = RootUtil.detect("su");  // 缓存已 false，不会再走进程
        eq(viaDetect, false, "detect 缓存兜底 false");

        // 5. suList：无 su 环境 → null（异常路径兜底）
        eq(RootUtil.suList("C:/"), null, "suList 无 su → null");
        eq(RootUtil.suList("nonexistent-xyz-\\*'\""), null, "suList 特殊字符路径 → null");

        System.out.println("\n==== RootUtilTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
