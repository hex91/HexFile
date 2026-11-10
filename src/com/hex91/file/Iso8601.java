package com.hex91.file;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * v1.89.0 ISO-8601 时间解析（纯 Java，可单测）。
 *
 * <p>云盘返回的时间戳看着一样，其实各家写法不同，都会碰到：</p>
 * <ul>
 *   <li>Graph：{@code 2023-05-24T00:00:00Z}；</li>
 *   <li>阿里云盘：{@code 2023-05-24T00:00:00.000Z} 与 {@code +08:00} 两种都有；</li>
 *   <li>部分网关会返回 7 位小数（{@code .1234567Z}）—— {@code SimpleDateFormat}
 *       对 3 位以上小数会直接抛 ParseException，所以这里自己截断。</li>
 * </ul>
 *
 * <p>解析失败返回 0（调用方按「时间未知」处理），不抛异常：mtime 只用于断点续传的
 * 新鲜度判断，解析不出来就退化成「按大小判断」，不该因此让整个列表失败。</p>
 */
public final class Iso8601 {

    private Iso8601() {}

    public static long parse(String s) {
        if (s == null) return 0;
        String v = s.trim();
        if (v.length() < 19) return 0;                  // 至少 yyyy-MM-ddTHH:mm:ss
        try {
            int year = num(v, 0, 4);
            int month = num(v, 5, 7);
            int day = num(v, 8, 10);
            int hour = num(v, 11, 13);
            int minute = num(v, 14, 16);
            int second = num(v, 17, 19);
            if (month < 1 || month > 12 || day < 1 || day > 31) return 0;
            // Calendar 是宽容模式：99 小时会被"滚"成 4 天后的 3 点，静默算出错得离谱的时间，
            // 所以这里先把明显非法的值挡掉（续传判断宁可认为「时间未知」）
            if (hour > 23 || minute > 59 || second > 60) return 0;

            int millis = 0;
            int i = 19;
            if (i < v.length() && v.charAt(i) == '.') {
                int start = ++i;
                while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
                String frac = v.substring(start, i);
                if (!frac.isEmpty()) {
                    // 只取前 3 位，多余的直接丢（7 位精度在这里没有意义）
                    String ms = frac.length() >= 3 ? frac.substring(0, 3) : pad(frac, 3);
                    millis = Integer.parseInt(ms);
                }
            }

            long offsetMin = 0;
            if (i < v.length()) {
                char c = v.charAt(i);
                if (c == 'Z' || c == 'z') {
                    // UTC，偏移 0
                } else if (c == '+' || c == '-') {
                    int sign = c == '-' ? -1 : 1;
                    String rest = v.substring(i + 1).replace(":", "");
                    if (rest.length() >= 2) {
                        int oh = num(rest, 0, 2);
                        int om = rest.length() >= 4 ? num(rest, 2, 4) : 0;
                        offsetMin = sign * (oh * 60L + om);
                    }
                }
            }

            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(year, month - 1, day, hour, minute, second);
            cal.set(Calendar.MILLISECOND, millis);
            return cal.getTimeInMillis() - offsetMin * 60_000L;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int num(String s, int from, int to) {
        return Integer.parseInt(s.substring(from, to));
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append('0');
        return sb.toString();
    }
}
