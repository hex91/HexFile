package com.hex91.file;

import java.math.BigInteger;
import java.util.Locale;

/**
 * v1.70.0 工具箱：进制转换核心（BigInteger，任意长度整数）。
 *
 * <p>支持 2~36 进制互转，自动吃掉 {@code 0x / 0b / 0o} 前缀与分隔符（空格、下划线、逗号），
 * 负数原样保留符号。除目标进制外还额外给出：十进制位宽、二进制分组、大端 HEX 字节。
 */
public final class RadixCore {

    public static final int[] BASES = {2, 8, 10, 16, 36};

    public static final class Result {
        /** 与 {@link #BASES} 一一对应的结果字符串 */
        public final String[] values = new String[BASES.length];
        /** 该数的二进制位数（不含符号位） */
        public int bits;
        /** 大端 HEX 字节（不足一字节补前导 0） */
        public String hexBytes = "";
        /** 字节数（向上取整，0 也算 1 字节） */
        public int byteLen;
    }

    private RadixCore() {
    }

    /**
     * @param input 输入串（可带 0x/0b/0o 前缀、分隔符、负号）
     * @param base  输入进制 2~36（前缀与 base 冲突时以 auto 前缀为准）
     */
    public static Result convert(String input, int base) {
        if (input == null) throw new IllegalArgumentException("请输入要转换的数");
        BigInteger v = parse(input, base);
        Result r = new Result();
        for (int i = 0; i < BASES.length; i++) {
            r.values[i] = toBase(v, BASES[i]);
        }
        BigInteger abs = v.abs();
        r.bits = abs.bitLength();
        if (r.bits == 0) r.bits = 1;
        r.byteLen = (abs.bitLength() + 7) / 8;
        if (r.byteLen == 0) r.byteLen = 1;
        r.hexBytes = hexBytes(v, r.byteLen);
        return r;
    }

    public static BigInteger parse(String input, int base) {
        String s = input.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("请输入要转换的数");
        boolean neg = false;
        if (s.startsWith("-")) {
            neg = true;
            s = s.substring(1).trim();
        } else if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("0x")) { base = 16; s = s.substring(2); }
        else if (lower.startsWith("0b")) { base = 2; s = s.substring(2); }
        else if (lower.startsWith("0o")) { base = 8; s = s.substring(2); }
        else if (s.startsWith("0") && s.length() > 1 && base == 10) {
            // 010 在十进制语境里仍按十进制处理，不猜八进制（避免用户误判）
        }
        if (base < 2 || base > 36) throw new IllegalArgumentException("进制必须在 2~36 之间");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '_' || c == ',' || c == '\'' || c == ':') continue;
            int d = digitVal(c);
            if (d < 0) throw new IllegalArgumentException("非法字符：" + c);
            if (d >= base) throw new IllegalArgumentException("字符 " + c + " 不属于 " + base + " 进制");
            sb.append(c);
        }
        if (sb.length() == 0) throw new IllegalArgumentException("请输入要转换的数");
        BigInteger v = new BigInteger(sb.toString(), base);
        return neg ? v.negate() : v;
    }

    public static String toBase(BigInteger v, int base) {
        if (base < 2 || base > 36) throw new IllegalArgumentException("进制必须在 2~36 之间");
        return v.toString(base).toUpperCase(Locale.US);
    }

    /** 二进制按 4 位分组（从右往左），便于肉眼对齐 */
    public static String groupBits(String bits) {
        StringBuilder sb = new StringBuilder(bits.length() + bits.length() / 4 + 1);
        int first = bits.length() % 4;
        if (first == 0) first = 4;
        sb.append(bits, 0, first);
        for (int i = first; i < bits.length(); i += 4) {
            sb.append(' ').append(bits, i, Math.min(i + 4, bits.length()));
        }
        return sb.toString();
    }

    /** 结果 HEX 字节：按 byteLen 大端输出，每字节两位 */
    private static String hexBytes(BigInteger v, int byteLen) {
        BigInteger abs = v.abs();
        StringBuilder sb = new StringBuilder(byteLen * 3);
        for (int i = byteLen - 1; i >= 0; i--) {
            int b = abs.shiftRight(i * 8).and(BigInteger.valueOf(0xFF)).intValue();
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return (v.signum() < 0 ? "- " : "") + sb;
    }

    private static int digitVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'z') return c - 'a' + 10;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 10;
        return -1;
    }
}
