package com.hex91.file;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HEX 编辑器核心（v1.31.0，纯 Java 无 Android 依赖）：
 *  - 类型编码 patternFor：把用户输入按「HEX / TEXT / Int8~UInt64 / Float32~64」+ 大小端
 *    编码为目标字节序列，同时供「搜索」与「编辑写回」复用。
 *  - findAll：KMP 子序列搜索，返回所有匹配的绝对偏移。
 *  - patch：在内存字节数组中按偏移写入（边界保护），编辑写回时同步刷新视图数据。
 */
public final class HexCore {

    public enum Type {
        HEX, TEXT,
        INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64,
        FLOAT32, FLOAT64
    }

    private HexCore() {}

    public static boolean isNumeric(Type t) {
        switch (t) {
            case INT8: case UINT8: case INT16: case UINT16:
            case INT32: case UINT32: case INT64: case UINT64:
            case FLOAT32: case FLOAT64:
                return true;
            default:
                return false;
        }
    }

    /** 把输入按类型与大小端编码为目标字节序列。失败时抛 IllegalArgumentException。 */
    public static byte[] patternFor(Type t, String input, boolean bigEndian) {
        if (input == null) throw new IllegalArgumentException("输入为空");
        input = input.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("输入为空");
        switch (t) {
            case HEX:
                return hexToBytes(input);
            case TEXT:
                return input.getBytes(StandardCharsets.UTF_8);
            case INT8:
                return pack(parseLong(input, -128, 127), 1, bigEndian);
            case UINT8:
                return pack(parseLong(input, 0, 255), 1, bigEndian);
            case INT16:
                return pack(parseLong(input, -32768, 32767), 2, bigEndian);
            case UINT16:
                return pack(parseLong(input, 0, 65535), 2, bigEndian);
            case INT32:
                return pack(parseLong(input, -2147483648L, 2147483647L), 4, bigEndian);
            case UINT32: {
                long v = Long.parseLong(input);
                if (v < 0 || v > 0xFFFFFFFFL) throw new IllegalArgumentException("超出范围 0~4294967295");
                return pack(v, 4, bigEndian);
            }
            case INT64:
                return pack(Long.parseLong(input), 8, bigEndian);
            case UINT64: {
                BigInteger bi;
                try {
                    bi = new BigInteger(input);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("请输入非负整数");
                }
                if (bi.signum() < 0 || bi.bitLength() > 64)
                    throw new IllegalArgumentException("超出 unsigned 64-bit 范围");
                return packUnsigned64(bi, bigEndian);
            }
            case FLOAT32: {
                int bits = Float.floatToRawIntBits(Float.parseFloat(input));
                return pack((long) bits, 4, bigEndian);
            }
            case FLOAT64: {
                long bits = Double.doubleToRawLongBits(Double.parseDouble(input));
                return pack(bits, 8, bigEndian);
            }
        }
        throw new IllegalArgumentException("未知类型");
    }

    /** 大小端打包（value 的低 n 字节）。 */
    static byte[] pack(long value, int n, boolean be) {
        byte[] b = new byte[n];
        if (be) {
            for (int i = 0; i < n; i++) b[i] = (byte) ((value >>> (8 * (n - 1 - i))) & 0xFF);
        } else {
            for (int i = 0; i < n; i++) b[i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return b;
    }

    static byte[] packUnsigned64(BigInteger v, boolean be) {
        byte[] tb = v.toByteArray(); // 大端，可能含前导 0x00 符号位
        byte[] be8 = new byte[8];
        int start = (tb.length > 8 && tb[0] == 0) ? 1 : 0;
        int cnt = Math.min(8, tb.length - start);
        System.arraycopy(tb, tb.length - cnt, be8, 8 - cnt, cnt);
        if (!be) reverse(be8);
        return be8;
    }

    static void reverse(byte[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            byte t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    static long parseLong(String s, long min, long max) {
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("请输入整数");
        }
        if (v < min || v > max) throw new IllegalArgumentException("超出范围 " + min + "~" + max);
        return v;
    }

    public static byte[] hexToBytes(String s) {
        s = s.replaceAll("\\s+", "").toUpperCase(Locale.US);
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("十六进制长度必须为偶数");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = hexVal(s.charAt(2 * i));
            int lo = hexVal(s.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法十六进制字符: " + s.charAt(2 * i + (lo < 0 ? 1 : 0)));
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    public static String bytesToHex(byte[] b, int off, int len) {
        char[] HEX = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = b[off + i] & 0xFF;
            sb.append(HEX[v >> 4]).append(HEX[v & 0xF]);
        }
        return sb.toString();
    }

    /** KMP 搜索，返回所有匹配的绝对偏移（data 与 pat 均按无符号比较）。 */
    public static int[] findAll(byte[] data, byte[] pat) {
        if (pat == null || pat.length == 0 || data == null) return new int[0];
        if (pat.length > data.length) return new int[0];
        int[] lps = buildLps(pat);
        List<Integer> r = new ArrayList<>();
        int j = 0;
        for (int i = 0; i < data.length; i++) {
            while (j > 0 && (data[i] & 0xFF) != (pat[j] & 0xFF)) j = lps[j - 1];
            if ((data[i] & 0xFF) == (pat[j] & 0xFF)) {
                j++;
                if (j == pat.length) {
                    r.add(i - pat.length + 1);
                    j = lps[j - 1];
                }
            }
        }
        int[] out = new int[r.size()];
        for (int k = 0; k < out.length; k++) out[k] = r.get(k);
        return out;
    }

    static int[] buildLps(byte[] pat) {
        int[] lps = new int[pat.length];
        int len = 0;
        for (int i = 1; i < pat.length; i++) {
            while (len > 0 && (pat[i] & 0xFF) != (pat[len] & 0xFF)) len = lps[len - 1];
            if ((pat[i] & 0xFF) == (pat[len] & 0xFF)) len++;
            lps[i] = len;
        }
        return lps;
    }

    /** 在内存字节数组中按偏移写入（边界保护）。 */
    public static void patch(byte[] data, int off, byte[] bytes) {
        if (data == null || bytes == null) throw new IllegalArgumentException("数据为空");
        if (off < 0 || off + bytes.length > data.length)
            throw new IllegalArgumentException("写入越界（仅支持前 4MB 已加载区域）");
        System.arraycopy(bytes, 0, data, off, bytes.length);
    }

    // ============ v1.32.0 借鉴 bined 的增强逻辑 ============

    public static final int BASE_HEX = 16, BASE_BIN = 2, BASE_OCT = 8, BASE_DEC = 10;

    /** 某进制下单字节占位宽度（HEX=2 / DEC=3 / OCT=3 / BIN=8）。 */
    public static int codeWidth(int base) {
        switch (base) {
            case BASE_BIN: return 8;
            case BASE_OCT: return 3;
            case BASE_DEC: return 3;
            default: return 2; // HEX
        }
    }

    /** 把单字节按进制的格式化字符串（定宽，前补零）。 */
    public static String formatCode(int v, int base) {
        v &= 0xFF;
        switch (base) {
            case BASE_BIN: return String.format(Locale.US, "%8s", Integer.toBinaryString(v)).replace(' ', '0');
            case BASE_OCT: return String.format(Locale.US, "%3s", Integer.toOctalString(v)).replace(' ', '0');
            case BASE_DEC: return String.format(Locale.US, "%3s", Integer.toString(v)).replace(' ', '0');
            default: return String.format(Locale.US, "%2s", Integer.toHexString(v).toUpperCase(Locale.US)).replace(' ', '0');
        }
    }

    /** 行内第 col 个字节在 HEX 区渲染后的起始字符下标（用于匹配高亮）。 */
    public static int byteStart(int col, int base) {
        int w = codeWidth(base);
        return 10 + col * (w + 1) + (col >= 8 ? 1 : 0);
    }

    /** 在 off 处插入 ins（后续字节后移），返回新数组。 */
    public static byte[] spliceInsert(byte[] data, int off, byte[] ins) {
        if (data == null || ins == null) throw new IllegalArgumentException("数据为空");
        if (off < 0 || off > data.length) throw new IllegalArgumentException("插入偏移越界");
        byte[] r = new byte[data.length + ins.length];
        System.arraycopy(data, 0, r, 0, off);
        System.arraycopy(ins, 0, r, off, ins.length);
        System.arraycopy(data, off, r, off + ins.length, data.length - off);
        return r;
    }

    /** 删除 [off, off+len) 区间，返回新数组。 */
    public static byte[] removeRange(byte[] data, int off, int len) {
        if (data == null) throw new IllegalArgumentException("数据为空");
        if (off < 0 || len < 0 || off + len > data.length) throw new IllegalArgumentException("删除越界");
        byte[] r = new byte[data.length - len];
        System.arraycopy(data, 0, r, 0, off);
        System.arraycopy(data, off + len, r, off, data.length - off - len);
        return r;
    }

    /** 一次编辑操作（供撤销/重做）。insert=true 表示插入模式，否则覆盖。 */
    public static final class EditAction {
        public final boolean insert;
        public final int offset;
        public final byte[] oldData; // 覆盖模式的原始字节；插入模式为 null
        public final byte[] newData; // 写入/插入的字节
        public EditAction(boolean insert, int offset, byte[] oldData, byte[] newData) {
            this.insert = insert;
            this.offset = offset;
            this.oldData = oldData;
            this.newData = newData;
        }
    }

    /** 对 data 应用一次“撤销”，返回撤销后的数组（不触碰文件）。 */
    public static byte[] undoData(byte[] data, EditAction a) {
        if (a.insert) return removeRange(data, a.offset, a.newData.length);
        return spliceInsert(removeRange(data, a.offset, a.newData.length), a.offset, a.oldData);
    }

    /** 对 data 应用一次“重做”，返回重做后的数组（不触碰文件）。 */
    public static byte[] redoData(byte[] data, EditAction a) {
        if (a.insert) return spliceInsert(data, a.offset, a.newData);
        return spliceInsert(removeRange(data, a.offset, a.oldData.length), a.offset, a.newData);
    }

    /** 复制 [from, to) 区间（自动收紧边界、交换顺序），供选区复制。 */
    public static byte[] copyRange(byte[] data, int from, int to) {
        if (data == null) return new byte[0];
        if (from < 0) from = 0;
        if (to > data.length) to = data.length;
        if (from > to) { int t = from; from = to; to = t; }
        byte[] r = new byte[to - from];
        System.arraycopy(data, from, r, 0, r.length);
        return r;
    }
}
