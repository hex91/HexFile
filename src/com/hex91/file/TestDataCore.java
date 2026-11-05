package com.hex91.file;

import java.util.ArrayList;
import java.util.Random;

/**
 * v1.71.0 工具箱「测试数据」核心：生成**校验位合法**的身份证号与银行卡号。
 *
 * <p>用途：分析恶意样本时，很多应用会索要实名信息、银行卡号。用自造的测试数据填进去，
 * 既能让流程走下去，又不会把自己的真实信息交给来路不明的应用。
 *
 * <p><b>重要边界</b>：这里只保证「格式 + 校验位」通过（身份证 ISO 7064 MOD 11-2、
 * 银行卡 Luhn）。号码不对应任何真实个人或银行账户，也**不能**通过需要联网核查的实名认证。
 * 请不要用于伪造证件或任何欺骗性场景。
 *
 * <p>全部为纯 Java，桌面可单测（见 {@code test/TestDataCoreTest.java}）。
 */
public final class TestDataCore {

    private TestDataCore() {
    }

    // ============================================================ 身份证

    /** 加权因子（前 17 位） */
    private static final int[] W = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /** 余数 → 校验字符 */
    private static final char[] CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    /** ISO 7064 MOD 11-2 校验位：输入前 17 位，返回第 18 位（0-9 或 X） */
    public static char idCheckDigit(String first17) {
        if (first17 == null || first17.length() != 17) {
            throw new IllegalArgumentException("需要 17 位数字");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = first17.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("第 " + (i + 1) + " 位不是数字");
            sum += (c - '0') * W[i];
        }
        return CHECK[sum % 11];
    }

    /**
     * 生成 18 位身份证号。
     *
     * @param region6 6 位行政区划码（如北京东城 110101）
     * @param year    四位出生年
     * @param month   1-12
     * @param day     1-31
     * @param male    true 男（第 17 位为奇数）
     * @param r       随机源，可为 null
     */
    public static String idNumber(String region6, int year, int month, int day, boolean male, Random r) {
        if (region6 == null || region6.length() != 6) throw new IllegalArgumentException("地区码需 6 位");
        for (int i = 0; i < 6; i++) {
            char c = region6.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("地区码只能是数字");
        }
        if (year < 1800 || year > 2099) throw new IllegalArgumentException("出生年超出范围");
        if (month < 1 || month > 12) throw new IllegalArgumentException("月份需 1-12");
        int max = daysInMonth(year, month);
        if (day < 1 || day > max) throw new IllegalArgumentException(month + " 月只有 " + max + " 天");
        if (r == null) r = new Random();

        StringBuilder sb = new StringBuilder(18);
        sb.append(region6);
        sb.append(pad4(year)).append(pad2(month)).append(pad2(day));
        // 顺序码 3 位：第 3 位（总第 17 位）奇偶决定性别。
        // 直接按性别取奇数/偶数，避免「先随机再 ±1 修正」在 999 边界上越界。
        int n = 1 + r.nextInt(499);              // 1~499
        int seq = male ? (2 * n - 1) : (2 * n);  // 男 1~997 奇，女 2~998 偶
        sb.append(pad3(seq));
        String first17 = sb.toString();
        return first17 + idCheckDigit(first17);
    }

    /** 完整校验：18 位、全数字（末位可为 X）、出生日期真实存在、校验位正确 */
    public static boolean idValid(String id) {
        if (id == null || id.length() != 18) return false;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        char last = Character.toUpperCase(id.charAt(17));
        if (!((last >= '0' && last <= '9') || last == 'X')) return false;
        int y = Integer.parseInt(id.substring(6, 10));
        int m = Integer.parseInt(id.substring(10, 12));
        int d = Integer.parseInt(id.substring(12, 14));
        if (m < 1 || m > 12) return false;
        if (d < 1 || d > daysInMonth(y, m)) return false;
        try {
            return idCheckDigit(id.substring(0, 17)) == last;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String idRegion(String id) {
        return id != null && id.length() >= 6 ? id.substring(0, 6) : "";
    }

    /** 出生日期 yyyy-MM-dd；非法返回空串 */
    public static String idBirth(String id) {
        if (id == null || id.length() < 14) return "";
        return id.substring(6, 10) + "-" + id.substring(10, 12) + "-" + id.substring(12, 14);
    }

    /** 性别：第 17 位奇数为男 */
    public static boolean idMale(String id) {
        if (id == null || id.length() < 17) return false;
        char c = id.charAt(16);
        if (c < '0' || c > '9') return false;
        return ((c - '0') & 1) == 1;
    }

    public static int daysInMonth(int year, int month) {
        switch (month) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12:
                return 31;
            case 4: case 6: case 9: case 11:
                return 30;
            case 2:
                boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
                return leap ? 29 : 28;
            default:
                return 0;
        }
    }

    // ============================================================ 银行卡

    /** 银行 + 公开卡 BIN 段（只用于让测试数据「看起来对」，长度按该 BIN 的实际位数） */
    public enum Bank {
        ICBC("中国工商银行", "工商",
                new String[][]{{"622202", "19"}, {"622203", "19"}, {"622208", "19"},
                        {"621226", "19"}, {"622200", "19"}}),
        CCB("中国建设银行", "建设",
                new String[][]{{"622700", "19"}, {"621700", "19"}, {"436742", "16"}}),
        CMB("招商银行", "招商",
                new String[][]{{"622588", "16"}, {"621483", "16"}, {"622575", "16"}}),
        ABC("中国农业银行", "农业",
                new String[][]{{"622848", "19"}, {"622845", "19"}, {"621336", "19"}}),
        PSBC("中国邮政储蓄银行", "邮政",
                new String[][]{{"622188", "19"}, {"621098", "19"}, {"621599", "19"}}),
        CITIC("中信银行", "中信",
                new String[][]{{"622690", "16"}, {"622691", "16"}, {"621771", "16"}});

        public final String full;
        public final String shortName;
        public final String[][] bins;

        Bank(String full, String shortName, String[][] bins) {
            this.full = full;
            this.shortName = shortName;
            this.bins = bins;
        }
    }

    /** Luhn 校验位：输入缺校验位的数字串，返回应补的那一位 */
    public static char luhnDigit(String payload) {
        if (payload == null || payload.length() == 0) throw new IllegalArgumentException("空串");
        int sum = 0;
        boolean dbl = true;                       // 从右往左，校验位左侧那位开始翻倍
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (d < 0 || d > 9) throw new IllegalArgumentException("含非数字");
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    public static boolean luhnValid(String num) {
        if (num == null || num.length() < 8) return false;
        for (int i = 0; i < num.length(); i++) {
            char c = num.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        try {
            return luhnDigit(num.substring(0, num.length() - 1)) == num.charAt(num.length() - 1);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 生成卡号：随机挑该行一个 BIN，补随机位，末位 Luhn */
    public static String cardNumber(Bank bank, Random r) {
        if (bank == null) bank = Bank.ICBC;
        if (r == null) r = new Random();
        String[] pick = bank.bins[r.nextInt(bank.bins.length)];
        String bin = pick[0];
        int len = Integer.parseInt(pick[1]);
        StringBuilder sb = new StringBuilder(len);
        sb.append(bin);
        while (sb.length() < len - 1) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString() + luhnDigit(sb.toString());
    }

    /** 4 位一组，便于核对：6222 0200 0000 0000 000 */
    public static String groupCard(String num) {
        if (num == null) return "";
        StringBuilder sb = new StringBuilder(num.length() + num.length() / 4);
        for (int i = 0; i < num.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(num.charAt(i));
        }
        return sb.toString();
    }

    /** 该卡号属于哪个 BIN 段（用于回显「622202 · 工商」） */
    public static String binOf(String card) {
        if (card == null) return "";
        for (Bank b : Bank.values()) {
            for (String[] bin : b.bins) {
                if (card.startsWith(bin[0])) return bin[0];
            }
        }
        return card.length() >= 6 ? card.substring(0, 6) : card;
    }

    /** 把整段卡号的数字挑出来（去掉空格/横线） */
    public static String digitsOnly(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    // ============================================================ 小工具

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String pad3(int v) {
        if (v < 10) return "00" + v;
        if (v < 100) return "0" + v;
        return String.valueOf(v);
    }

    private static String pad4(int v) {
        if (v < 1000) return "0" + v;
        return String.valueOf(v);
    }

    /** 供界面列出「工商/建设/…」的顺序 */
    public static ArrayList<Bank> bankList() {
        ArrayList<Bank> l = new ArrayList<Bank>();
        for (Bank b : Bank.values()) l.add(b);
        return l;
    }
}
