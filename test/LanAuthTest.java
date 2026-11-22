import com.hex91.file.net.LanProtocol;

import java.util.HashSet;

/**
 * v2.113.0（R-47）：局域网配对码用例。
 *
 * <p>配对码是「同网任何人都能往我手机里塞文件」这个洞的唯一门槛，所以三件事必须钉住：</p>
 * <ol>
 *   <li><b>生成</b>：长度对、全是数字、每次不一样 —— 生成器退化成常量的话，
 *       门槛就等于公开；</li>
 *   <li><b>比对</b>：错一位、错长度、空值、带空白都要按预期处理，
 *       绝不能出现「错码也放行」这种最坏情况；</li>
 *   <li><b>过渡态</b>：接收端还没起来时 expected 为空，此时放行是刻意的兼容行为，
 *       但必须只能由「空」触发，不能由「null 密码 vs null 输入」这种意外组合触发。</li>
 * </ol>
 */
public class LanAuthTest {

    static int pass = 0, fail = 0;

    static void ok(boolean v, String msg) {
        if (v) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    static void eq(int want, int got, String msg) {
        boolean v = want == got;
        ok(v, msg + (v ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    public static void main(String[] args) {
        // ---- 生成
        String a = LanProtocol.newPairCode();
        String b = LanProtocol.newPairCode();
        eq(LanProtocol.PAIR_CODE_LEN, a.length(), "生成：长度为 6");
        ok(a.matches("[0-9]{6}"), "生成：全部是数字（" + a + "）");
        ok(!a.equals(b), "生成：两次不重复（" + a + " / " + b + "）");

        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < 500; i++) seen.add(LanProtocol.newPairCode());
        // 100 万种里取 500 个，重复极少；低于 450 说明生成器有严重倾向性
        ok(seen.size() > 450, "生成：500 次至少 450 个不同（实际 " + seen.size() + "）");

        // ---- 比对
        ok(LanProtocol.matchCode("123456", "123456"), "比对：正确码通过");
        ok(!LanProtocol.matchCode("123456", "123457"), "比对：错一位拒绝");
        ok(!LanProtocol.matchCode("123456", "12345"), "比对：少一位拒绝");
        ok(!LanProtocol.matchCode("123456", "1234567"), "比对：多一位拒绝");
        ok(!LanProtocol.matchCode("123456", null), "比对：没给码拒绝");
        ok(!LanProtocol.matchCode("123456", ""), "比对：空字符串拒绝");
        ok(LanProtocol.matchCode("123456", " 123456 "), "比对：首尾空白不影响");
        ok(!LanProtocol.matchCode("123456", "abcdef"), "比对：非数字拒绝");
        ok(!LanProtocol.matchCode("123456", "１２３４５６"), "比对：全角数字不算同一个码");

        // ---- 过渡态：只有 expected 为空才放行
        ok(LanProtocol.matchCode(null, null), "过渡态：expected 为 null 时放行");
        ok(LanProtocol.matchCode("", "whatever"), "过渡态：expected 为空串时放行");
        ok(LanProtocol.matchCode(null, "123456"), "过渡态：expected 为 null 时任意输入都放行");

        System.out.println();
        System.out.println("LanAuthTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
