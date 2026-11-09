package com.hex91.file;

/**
 * v1.83.0 极简 Base64（标准字母表 + = 填充）。
 *
 * <p>不用 {@code android.util.Base64}：那是 Android 专有类，桌面单测跑不了；
 * 也不用 {@code java.util.Base64}：Android 上要 API 26，本应用 minSdk 21。
 * 加解密格式要跨版本稳定，所以这部分自己实现并单测。</p>
 */
public final class Base64Codec {

    private static final char[] A =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] REV = new int[128];

    static {
        java.util.Arrays.fill(REV, -1);
        for (int i = 0; i < A.length; i++) REV[A[i]] = i;
    }

    private Base64Codec() {}

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(A[(n >> 18) & 63]).append(A[(n >> 12) & 63])
                    .append(A[(n >> 6) & 63]).append(A[n & 63]);
            i += 3;
        }
        int left = data.length - i;
        if (left == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(A[(n >> 18) & 63]).append(A[(n >> 12) & 63]).append("==");
        } else if (left == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(A[(n >> 18) & 63]).append(A[(n >> 12) & 63])
                    .append(A[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** 解码；含非法字符时返回 null（不做宽松纠错） */
    public static byte[] decode(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        int len = s.length();
        // 先算有效字符数（忽略 '=' 与空白）
        int valid = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            if (c >= 128 || REV[c] < 0) return null;
            valid++;
        }
        if (valid % 4 == 1) return null;
        byte[] out = new byte[valid * 3 / 4];
        int buf = 0, bits = 0, o = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            if (c >= 128 || REV[c] < 0) return null;
            buf = (buf << 6) | REV[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        return out;
    }
}
