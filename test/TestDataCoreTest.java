import com.hex91.file.RegionTable;
import com.hex91.file.TestDataCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.Random;

/**
 * v1.71.0 测试数据生成器（身份证 / 银行卡）核心测试。
 * 纯 JVM 跑，不需要设备：./test/run_core.sh TestDataCoreTest
 */
public class TestDataCoreTest {

    static int pass = 0, fail = 0;

    static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + "\n       expect: " + expect + "\n       actual: " + actual);
        }
    }

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what);
        }
    }

    static String readAll(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) return null;
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    public static void main(String[] a) throws Exception {
        System.out.println("== 身份证：校验位 / 校验 / 解析 ==");
        // 公开流传的示例号：北京朝阳 1949-12-31 女，末位 X
        eq("校验位 11010519491231002 → X", 'X', TestDataCore.idCheckDigit("11010519491231002"));
        ok("示例号 11010519491231002X 通过校验", TestDataCore.idValid("11010519491231002X"));
        ok("末位改错则不通过", !TestDataCore.idValid("110105194912310021"));
        ok("17 位不通过", !TestDataCore.idValid("11010519491231002"));
        ok("含字母不通过", !TestDataCore.idValid("1101051949A231002X"));
        ok("2 月 30 日不通过", !TestDataCore.idValid("110101199102300011"));
        eq("出生日期解析", "1949-12-31", TestDataCore.idBirth("11010519491231002X"));
        eq("性别解析（第 17 位 2 → 女）", false, TestDataCore.idMale("11010519491231002X"));

        System.out.println();
        System.out.println("== 身份证：按参数生成 ==");
        Random r = new Random(20261104L);
        String m = TestDataCore.idNumber("110101", 1991, 10, 1, true, r);
        eq("长度 18", 18, m.length());
        eq("前 6 位为地区码", "110101", m.substring(0, 6));
        eq("第 7-14 位为生日", "19911001", m.substring(6, 14));
        ok("生成结果通过校验", TestDataCore.idValid(m));
        ok("男 → 第 17 位奇数", ((m.charAt(16) - '0') & 1) == 1);
        eq("解析回男性", true, TestDataCore.idMale(m));

        String f = TestDataCore.idNumber("110101", 1991, 10, 1, false, r);
        ok("女 → 第 17 位偶数", ((f.charAt(16) - '0') & 1) == 0);
        eq("解析回女性", false, TestDataCore.idMale(f));
        ok("同参数生成仍通过校验", TestDataCore.idValid(f));

        // 连续 500 次都不出错，且地区/生日/性别三项全部吻合
        boolean bulk = true;
        for (int i = 0; i < 500; i++) {
            boolean male = r.nextBoolean();
            int day = 1 + r.nextInt(28);
            String s = TestDataCore.idNumber("440304", 2000, 2, day, male, r);
            if (!TestDataCore.idValid(s)) { bulk = false; break; }
            if (!s.startsWith("440304200002")) { bulk = false; break; }
            if (TestDataCore.idMale(s) != male) { bulk = false; break; }
        }
        ok("500 次批量生成全部合规", bulk);

        System.out.println();
        System.out.println("== 身份证：参数校验 ==");
        try {
            TestDataCore.idNumber("11010", 1991, 10, 1, true, r);
            ok("5 位地区码应报错", false);
        } catch (IllegalArgumentException e) {
            ok("5 位地区码报错", true);
        }
        try {
            TestDataCore.idNumber("110101", 1991, 2, 30, true, r);
            ok("2 月 30 日应报错", false);
        } catch (IllegalArgumentException e) {
            ok("2 月 30 日按当月天数报错", true);
        }

        System.out.println();
        System.out.println("== 日期工具 ==");
        eq("2000-02 闰年 29 天", 29, TestDataCore.daysInMonth(2000, 2));
        eq("1900-02 平年 28 天", 28, TestDataCore.daysInMonth(1900, 2));
        eq("2024-02 闰年 29 天", 29, TestDataCore.daysInMonth(2024, 2));
        eq("4 月 30 天", 30, TestDataCore.daysInMonth(2026, 4));

        System.out.println();
        System.out.println("== 银行卡：Luhn ==");
        eq("411111111111111 的校验位", '1', TestDataCore.luhnDigit("411111111111111"));
        ok("4111111111111111 通过 Luhn", TestDataCore.luhnValid("4111111111111111"));
        ok("4111111111111112 不通过", !TestDataCore.luhnValid("4111111111111112"));
        ok("含空格不通过（先 digitsOnly）", !TestDataCore.luhnValid("4111 1111 1111 1111"));
        eq("digitsOnly 去空格", "4111111111111111", TestDataCore.digitsOnly("4111 1111-1111 1111"));

        System.out.println();
        System.out.println("== 银行卡：按银行生成 ==");
        boolean cardOk = true;
        StringBuilder detail = new StringBuilder();
        for (TestDataCore.Bank b : TestDataCore.Bank.values()) {
            String c = TestDataCore.cardNumber(b, r);
            boolean lenOk = false;
            for (String[] bin : b.bins) {
                if (c.startsWith(bin[0]) && c.length() == Integer.parseInt(bin[1])) lenOk = true;
            }
            if (!lenOk || !TestDataCore.luhnValid(c)) {
                cardOk = false;
                detail.append("\n       ").append(b.shortName).append(" -> ").append(c)
                        .append(" luhn=").append(TestDataCore.luhnValid(c));
            }
        }
        ok("6 家银行生成的卡号 BIN/长度/Luhn 全对" + detail, cardOk);

        eq("工商默认 BIN 622202 → 19 位", 19,
                TestDataCore.cardNumber(TestDataCore.Bank.ICBC, new Random(1)).length());
        ok("招商 622588 → 16 位", TestDataCore.cardNumber(TestDataCore.Bank.CMB, new Random(2)).length() == 16);
        eq("4 位分组", "6222 0201 2345 6789 012", TestDataCore.groupCard("6222020123456789012"));
        eq("BIN 回查", "622202", TestDataCore.binOf("6222020123456789012"));

        System.out.println();
        System.out.println("== 行政区划码表 ==");
        String txt = readAll("res/raw/regions.txt");
        ok("能读到 res/raw/regions.txt", txt != null && txt.length() > 1000);
        if (txt != null) {
            RegionTable t = RegionTable.parse(txt);
            ok("条目数 > 3000（实际 " + t.size() + "）", t.size() > 3000);
            eq("省级 31 个", 31, t.provinces().size());
            eq("110101 名称", "东城区", t.name("110101"));
            eq("110101 父级", "110100", t.get("110101").parent);
            eq("110100 父级", "110000", t.get("110100").parent);
            eq("110000 父级为空", "", t.get("110000").parent);
            eq("东城完整地址", "北京市 东城区", t.fullName("110101"));
            eq("深圳福田完整地址", "广东省 深圳市 福田区", t.fullName("440304"));
            eq("东莞（无区县）不重复", "广东省 东莞市", t.fullName("441900"));
            eq("中山（无区县）不重复", "广东省 中山市", t.fullName("442000"));
            eq("直辖市市级显示为省名", "北京市", t.cityLabel("110100"));
            eq("普通市显示市名", "深圳市", t.cityLabel("440300"));
            ok("北京的市辖区非空（" + t.children("110000").size() + "）", t.children("110000").size() >= 1);
            ok("东城区的父级下有多个区（" + t.children("110100").size() + "）", t.children("110100").size() >= 10);
            eq("按名称反查默认区县", "110101", t.findDistrict("北京市", "东城区"));
        }

        System.out.println();
        System.out.println("== 生成器 + 码表联动 ==");
        if (txt != null) {
            RegionTable t = RegionTable.parse(txt);
            String id = TestDataCore.idNumber("110101", 1991, 10, 1, true, new Random(7));
            ok("默认参数生成合规（" + id + "）", TestDataCore.idValid(id));
            eq("地区码能在码表里查到", "东城区", t.name(TestDataCore.idRegion(id)));
        }

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
