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
 *
 * <p>v2.78.0（F-14/65）：本类只产「码」，不产文案 —— 单位返回 {@link #UNIT_SECOND} 这类常量，
 * 相对时间返回 {@link Relative}，错误返回 {@link TimeException#code}，由 UI 层映射到 R.string。
 * 好处是 test/ToolboxCoreTest 仍是纯 JVM 测试，不必为了取字符串而塞一个 Context 进来。
 */
public final class TimeCore {

    /** 时间戳单位码：UI 层按码取资源 */
    public static final int UNIT_SECOND = 0;
    public static final int UNIT_MILLIS = 1;
    public static final int UNIT_MICROS = 2;
    public static final int UNIT_NANOS = 3;

    /** 相对时间的单位码 */
    public static final int REL_MILLIS = 0;
    public static final int REL_SECONDS = 1;
    public static final int REL_MINUTES = 2;
    public static final int REL_HOURS = 3;
    public static final int REL_DAYS = 4;
    public static final int REL_MONTHS = 5;
    public static final int REL_YEARS = 6;

    /** 错误码：UI 层按码取资源，日志里只出现编号 */
    public static final int ERR_EMPTY_STAMP = 1;
    public static final int ERR_STAMP_NOT_NUMBER = 2;
    public static final int ERR_STAMP_OVERFLOW = 3;
    public static final int ERR_EMPTY_TIME = 4;
    public static final int ERR_TIME_UNKNOWN = 5;

    /** 解析失败：{@link #code} 决定给用户看哪句提示 */
    public static final class TimeException extends IllegalArgumentException {
        public final int code;

        public TimeException(int code) {
            super("TimeCore error " + code);
            this.code = code;
        }
    }

    /** 解析成功后的结果（毫秒 + 原始单位猜测） */
    public static final class Stamp {
        public long millis;
        public int unit = UNIT_SECOND;
        public boolean negative;
    }

    /** 相对时间：{@link #count} 配 {@link #unit}，{@link #future} 决定「前 / 后」 */
    public static final class Relative {
        public long count;
        public int unit = REL_MILLIS;
        public boolean future;
        /** 只有年份走一位小数，此时 count 是十分之一年的整数（35 = 3.5 年） */
        public boolean oneDecimal;
    }

    private TimeCore() {
    }

    // ============================================================ 时间戳 → 时间

    /** 解析时间戳字符串：允许负数、允许带小数（小数部分按当前单位折算） */
    public static Stamp parseStamp(String s) {
        if (s == null) throw new TimeException(ERR_EMPTY_STAMP);
        String t = s.trim().replace("_", "").replace(" ", "").replace(",", "");
        if (t.isEmpty()) throw new TimeException(ERR_EMPTY_STAMP);
        boolean neg = t.startsWith("-");
        if (neg || t.startsWith("+")) t = t.substring(1);
        // 整数位数（去掉小数点后的部分不影响单位判断）
        int dot = t.indexOf('.');
        String intPart = dot >= 0 ? t.substring(0, dot) : t;
        if (intPart.isEmpty() || !isDigits(intPart)) {
            throw new TimeException(ERR_STAMP_NOT_NUMBER);
        }
        int digits = intPart.length();
        Stamp st = new Stamp();
        long v;
        try {
            v = Long.parseLong(intPart);
        } catch (NumberFormatException e) {
            throw new TimeException(ERR_STAMP_OVERFLOW);
        }
        if (digits <= 10) {
            st.unit = UNIT_SECOND;
            st.millis = v * 1000L;
        } else if (digits <= 13) {
            st.unit = UNIT_MILLIS;
            st.millis = v;
        } else if (digits <= 16) {
            st.unit = UNIT_MICROS;
            st.millis = v / 1000L;
        } else {
            st.unit = UNIT_NANOS;
            st.millis = v / 1000000L;
        }
        st.negative = neg;
        if (neg) st.millis = -st.millis;
        return st;
    }

    public static String fmt(long millis, String pattern, String tzId) {
        return fmt(millis, pattern, tzId, Locale.getDefault());
    }

    /** 指定语言格式化：RFC 1123 这类规范格式要用 {@link Locale#US}，否则中文环境会输出「周三」 */
    public static String fmt(long millis, String pattern, String tzId, Locale locale) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        if (tzId != null) f.setTimeZone(TimeZone.getTimeZone(tzId));
        return f.format(new Date(millis));
    }

    /** 星期几：0 = 周日 … 6 = 周六，名字由 UI 层按码查表 */
    public static int weekdayIndex(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.DAY_OF_WEEK) - 1;
    }

    /** 相对现在多久：只给数字和单位码，前后由 {@link Relative#future} 表示 */
    public static Relative relative(long millis) {
        long diff = System.currentTimeMillis() - millis;
        Relative r = new Relative();
        r.future = diff < 0;
        long d = Math.abs(diff);
        if (d < 1000) {
            r.count = d;
            r.unit = REL_MILLIS;
        } else if (d < 60_000) {
            r.count = d / 1000;
            r.unit = REL_SECONDS;
        } else if (d < 3_600_000) {
            r.count = d / 60_000;
            r.unit = REL_MINUTES;
        } else if (d < 86_400_000L) {
            r.count = d / 3_600_000;
            r.unit = REL_HOURS;
        } else if (d < 30L * 86_400_000L) {
            r.count = d / 86_400_000L;
            r.unit = REL_DAYS;
        } else if (d < 365L * 86_400_000L) {
            r.count = d / (30L * 86_400_000L);
            r.unit = REL_MONTHS;
        } else {
            r.count = Math.round(d / (365.0 * 86_400_000L) * 10L);
            r.unit = REL_YEARS;
            r.oneDecimal = true;
        }
        return r;
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
        if (s == null) throw new TimeException(ERR_EMPTY_TIME);
        String t = s.trim();
        if (t.isEmpty()) throw new TimeException(ERR_EMPTY_TIME);
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
        Locale locale = Locale.getDefault();
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, locale);
                f.setLenient(false);
                // 带 Z 或 +08:00 时区后缀时一律按 UTC 解析，再由 offset 折算（
                // 否则在 +08:00 机器上解析 +08:00 时间会被重复折算一次）
                if (utc || offset != null) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(t);
                if (d == null) continue;
                long ms = d.getTime();
                if (p.startsWith("HH")) {
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
        throw new TimeException(ERR_TIME_UNKNOWN);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
}
