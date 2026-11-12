package com.hex91.file.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * v1.70.0 工具箱：时间戳与日期互转核心。
 *
 * <p>为什么不用 java.time：minSdk 21，项目没开 core library desugaring，
 * {@code java.time} 在 Android 8.0 以下直接崩。这里统一用 {@link SimpleDateFormat} + {@link Calendar}。
 *
 * <p>时间戳自动识别单位：≤ 10 位按秒、≤ 13 位按毫秒、≤ 16 位按微秒、更长按纳秒 —— 手输时不用记单位。
 */
public final class TimeCore {

    /** 解析成功后的结果（毫秒 + 原始单位猜测） */
    public static final class Stamp {
        public long millis;
        public String unit = "秒";
        public boolean negative;
    }

    private TimeCore() {
    }

    // ============================================================ 时间戳 → 时间

    /** 解析时间戳字符串：允许负数、允许带小数（小数部分按当前单位折算） */
    public static Stamp parseStamp(String s) {
        if (s == null) throw new IllegalArgumentException("请输入时间戳");
        String t = s.trim().replace("_", "").replace(" ", "").replace(",", "");
        if (t.isEmpty()) throw new IllegalArgumentException("请输入时间戳");
        boolean neg = t.startsWith("-");
        if (neg || t.startsWith("+")) t = t.substring(1);
        // 整数位数（去掉小数点后的部分不影响单位判断）
        int dot = t.indexOf('.');
        String intPart = dot >= 0 ? t.substring(0, dot) : t;
        if (intPart.isEmpty() || !isDigits(intPart)) {
            throw new IllegalArgumentException("时间戳只能是数字（例如 1700000000 或 1700000000123）");
        }
        int digits = intPart.length();
        Stamp st = new Stamp();
        long v;
        try {
            v = Long.parseLong(intPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("时间戳过长，超出 64 位整数范围");
        }
        if (digits <= 10) {
            st.unit = "秒";
            st.millis = v * 1000L;
        } else if (digits <= 13) {
            st.unit = "毫秒";
            st.millis = v;
        } else if (digits <= 16) {
            st.unit = "微秒";
            st.millis = v / 1000L;
        } else {
            st.unit = "纳秒";
            st.millis = v / 1000000L;
        }
        st.negative = neg;
        if (neg) st.millis = -st.millis;
        return st;
    }

    /** 本地时间 + UTC + ISO8601 + RFC1123 + 星期 + 相对时间，一行一条方便直接复制 */
    public static String describe(long millis) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("本地时间   ").append(fmt(millis, "yyyy-MM-dd HH:mm:ss.SSS", null)).append('\n');
        sb.append("UTC        ").append(fmt(millis, "yyyy-MM-dd HH:mm:ss.SSS", "UTC")).append("  (UTC)\n");
        sb.append("ISO 8601   ").append(fmt(millis, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", null)).append('\n');
        sb.append("RFC 1123   ").append(fmt(millis, "EEE, dd MMM yyyy HH:mm:ss zzz", "UTC")).append('\n');
        sb.append("中文       ").append(fmt(millis, "yyyy 年 M 月 d 日 HH:mm:ss", null))
          .append("  ").append(weekday(millis)).append('\n');
        sb.append("相对       ").append(relative(millis)).append('\n');
        sb.append("秒 / 毫秒  ").append(millis / 1000L).append(" / ").append(millis);
        return sb.toString();
    }

    public static String fmt(long millis, String pattern, String tzId) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.CHINA);
        if (tzId != null) f.setTimeZone(TimeZone.getTimeZone(tzId));
        return f.format(new Date(millis));
    }

    public static String weekday(long millis) {
        String[] w = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return w[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    public static String relative(long millis) {
        long diff = System.currentTimeMillis() - millis;
        boolean future = diff < 0;
        long d = Math.abs(diff);
        String s;
        if (d < 1000) s = d + " 毫秒";
        else if (d < 60_000) s = (d / 1000) + " 秒";
        else if (d < 3_600_000) s = (d / 60_000) + " 分钟";
        else if (d < 86_400_000L) s = (d / 3_600_000) + " 小时";
        else if (d < 30L * 86_400_000L) s = (d / 86_400_000L) + " 天";
        else if (d < 365L * 86_400_000L) s = (d / (30L * 86_400_000L)) + " 个月";
        else s = String.format(Locale.US, "%.1f", d / (365.0 * 86_400_000L)) + " 年";
        return future ? s + "后" : s + "前";
    }

    // ============================================================ 时间 → 时间戳

    /**
     * 解析常见写法：
     * <ul>
     *   <li>{@code 2024-01-01 12:00:00} / {@code 2024-01-01 12:00} / {@code 2024-01-01}</li>
     *   <li>{@code 2024/01/01 12:00:00}、{@code 2024.01.01 12:00:00}</li>
     *   <li>{@code 20240101 120000}、{@code 20240101120000}</li>
     *   <li>带时区后缀 {@code 2024-01-01T12:00:00Z} 或 {@code +08:00}</li>
     *   <li>纯数字则原样当时间戳处理（走 {@link #parseStamp}）</li>
     * </ul>
     */
    public static long parseDateTime(String s) {
        if (s == null) throw new IllegalArgumentException("请输入时间");
        String t = s.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("请输入时间");
        if (t.matches("^-?\\d+(\\.\\d+)?$")) {
            // 纯数字先看长度：8/12/14 位是「紧凑日期」（20240101 / 202401011200 / 20240101120000），
            // 其余按时间戳处理，避免 20240101120000 被当成微秒戳
            int n = t.startsWith("-") ? t.length() - 1 : t.length();
            if (n != 8 && n != 12 && n != 14) return parseStamp(t).millis;
        }

        boolean utc = false;
        String offset = null;
        if (t.endsWith("Z") || t.endsWith("z")) {
            utc = true;
            t = t.substring(0, t.length() - 1);
        } else if (t.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            offset = t.substring(t.lastIndexOf('+') > 0 ? t.lastIndexOf('+') : t.lastIndexOf('-'))
                    .replace(":", "");
            t = t.substring(0, t.length() - offset.length());
        }
        // 只把「日期段」里的 / 和 . 换成 -，时间段的 "." 可能是毫秒分隔符（2023-11-14 22:13:20.123）
        t = t.replace('T', ' ');
        int sp2 = t.indexOf(' ');
        if (sp2 > 0) {
            t = t.substring(0, sp2).replace('/', '-').replace('.', '-')
                    + t.substring(sp2).replace('/', '-');
        } else {
            t = t.replace('/', '-').replace('.', '-');
        }
        t = t.trim();
        // 去掉多余空白
        t = t.replaceAll("\\s+", " ");

        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "yyyyMMdd HHmmss", "yyyyMMddHHmmss", "yyyyMMdd HHmm", "yyyyMMdd", "HH:mm:ss", "HH:mm"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.CHINA);
                f.setLenient(false);
                // 带 Z 或 +08:00 时区后缀时一律按 UTC 解析，再由 offset 折算（
                // 否则在 +08:00 机器上解析 +08:00 时间会被重复折算一次）
                if (utc || offset != null) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(t);
                if (d == null) continue;
                long ms = d.getTime();
                if (p.startsWith("HH") ) {
                    // 只给了时分秒：补今天的日期
                    Calendar now = Calendar.getInstance();
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(ms);
                    now.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY));
                    now.set(Calendar.MINUTE, c.get(Calendar.MINUTE));
                    now.set(Calendar.SECOND, c.get(Calendar.SECOND));
                    now.set(Calendar.MILLISECOND, 0);
                    ms = now.getTimeInMillis();
                }
                if (offset != null) {
                    int sign = offset.startsWith("-") ? -1 : 1;
                    String d4 = offset.replace("+", "").replace("-", "");
                    int hh = Integer.parseInt(d4.substring(0, 2));
                    int mm = d4.length() >= 4 ? Integer.parseInt(d4.substring(2, 4)) : 0;
                    ms -= sign * (hh * 3600_000L + mm * 60_000L);
                }
                return ms;
            } catch (Exception ignore) {
                // 换下一个格式
            }
        }
        throw new IllegalArgumentException("认不出这个时间格式，试试 2024-01-01 12:00:00");
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
}
