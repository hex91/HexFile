import com.hex91.file.utils.CrashLog;
import com.hex91.file.utils.LogRing;

/**
 * v2.12.0（F-18）：崩溃诊断两件纯逻辑的用例。
 *
 * <p>崩录本身必须在真机上验（要真装 Application、真崩一次），但下面两条是纯函数，
 * 且都属于「写错了会静默出问题」的类型：</p>
 * <ul>
 *   <li><b>环形缓冲</b>：容量到了必须覆盖最老的，且 dump 的顺序是「老的在前」。
 *       顺序反了，排查时看到的第一个异常就是最不相关的那个。</li>
 *   <li><b>脱敏</b>：诊断包是要用户发给别人看的东西，漏一个 {@code token} 就是事故。</li>
 * </ul>
 */
public class CrashLogTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) {
        // ---- LogRing：顺序与容量
        LogRing.clear();
        ok(LogRing.dump(10).length() == 0, "LogRing 空缓冲 dump 为空串");

        LogRing.addLine("line-1");
        LogRing.addLine("line-2");
        String two = LogRing.dump(10);
        ok(two.indexOf("line-1") >= 0 && two.indexOf("line-1") < two.indexOf("line-2"),
                "LogRing dump 顺序 = 老的在前");

        LogRing.clear();
        for (int i = 0; i < 250; i++) LogRing.add("t", "m" + i);
        ok(LogRing.size() == 200, "LogRing 容量上限 200（实际 " + LogRing.size() + "）");
        String d = LogRing.dump(1000);
        ok(d.indexOf("m49") < 0, "LogRing 最老的 50 条已被覆盖");
        ok(d.indexOf("m249") > d.indexOf("m50"), "LogRing 最新的在最后");
        ok(d.split("\n").length == 200, "LogRing dump 行数 = 200（实际 " + d.split("\n").length + "）");

        // 单行超长要截断，否则一段堆栈就能挤掉几十行上下文
        LogRing.clear();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 900; i++) huge.append('x');
        LogRing.addLine(huge.toString());
        String one = LogRing.dump(1);
        ok(one.length() < 500, "LogRing 单行截断到 <500 字符（实际 " + one.length() + "）");

        // ---- 脱敏：敏感词与它的取值都必须从输出里消失（打码留键名等于没脱敏）
        String[] secrets = {
                "password=abc123",
                "ks_pass: hunter2",
                "token eyJhbGciOi",
                "api_secret=deadbeef",
                "sig=Zm9vYmFy",
                "Authorization: Bearer_qwerty",
                "credential_path=/sdcard/a.p12",
        };
        String[] values = {"abc123", "hunter2", "eyJhbGciOi", "deadbeef", "Zm9vYmFy",
                "Bearer_qwerty", "a.p12"};
        String[] keywords = {"pass", "token", "secret", "sig", "auth", "credential"};
        for (int i = 0; i < secrets.length; i++) {
            String r = CrashLog.redact("before " + secrets[i] + " after");
            String low = r.toLowerCase();
            boolean keywordLeft = false;
            for (String k : keywords) if (low.indexOf(k) >= 0) keywordLeft = true;
            ok(r.indexOf("[redacted]") >= 0 && !keywordLeft
                            && r.indexOf(values[i]) < 0 && r.startsWith("before "),
                    "redact 抹掉敏感词与取值 → " + secrets[i] + " ⇒ " + r);
        }
        // 大小写混写也要命中
        String up = CrashLog.redact("PASSWORD=1");
        ok(up.indexOf("PASSWORD") < 0 && up.toLowerCase().indexOf("password") < 0,
                "redact 不区分大小写 → " + up);
        // 正常文本不该被改
        String plain = CrashLog.redact("com.hex91.file.MainActivity: onClick line 42");
        ok(plain.indexOf("[redacted]") < 0, "redact 不动普通堆栈行");
        ok("".equals(CrashLog.redact(null)), "redact(null) = 空串");

        System.out.println();
        System.out.println("==== CrashLogTest: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}
