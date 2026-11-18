package com.hex91.file.sched;

import java.util.Calendar;

/**
 * 定时任务的触发规则（v2.17.0，F-23）。
 *
 * <p>规则用一段人能读懂、也能手改的短文本：</p>
 * <ul>
 *   <li>{@code daily 23:00} —— 每天 23:00</li>
 *   <li>{@code weekly 1,3,5 07:30} —— 周一、三、五 07:30（1=周一，7=周日）</li>
 * </ul>
 *
 * <p><b>为什么不用 cron 表达式</b>：cron 的 {@code *}{@code /}{@code ,} 表达能力远超这里需要的
 * 范围，代价是用户得先学一遍，而写错一个字符的后果是「不执行」——还是静默的。两种规则覆盖
 * 「每天清一次缓存」「每周日压缩一次备份」这类全部真实需求。</p>
 *
 * <p>纯 JDK、不用 {@code java.time}：minSdk 21 上没有它，而 {@link Calendar} 处理
 * 「加一天」时会自己处理夏令时（中国没有 DST，但别国的用户有，{@code +86400000} 会在
 * 换季那天差一小时）。</p>
 */
public final class ScheduleRule {

    public static final int KIND_DAILY = 0;
    public static final int KIND_WEEKLY = 1;

    private ScheduleRule() {}

    /** 解析结果；不合法的规则一律 {@code null}，调用方据此提示，而不是当成「永不触发」 */
    public static final class Parsed {
        public final int kind;
        public final int hour;
        public final int minute;
        /** 星期位掩码：bit0 = 周一 … bit6 = 周日（daily 时全 0，不参与判断） */
        public final int mask;

        Parsed(int kind, int hour, int minute, int mask) {
            this.kind = kind;
            this.hour = hour;
            this.minute = minute;
            this.mask = mask;
        }

        public boolean on(int dow) {          // dow: 1=周一 … 7=周日
            return (mask & (1 << (dow - 1))) != 0;
        }
    }

    /** 解析规则；不合法返回 null（绝不抛，UI 要能把它当普通输入错误处理） */
    public static Parsed parse(String rule) {
        if (rule == null) return null;
        String s = rule.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return null;
        // 容忍多空格：手打的时候很容易连着敲两个
        String[] p = s.split("\\s+");
        if (p.length < 2) return null;
        if ("daily".equals(p[0])) {
            if (p.length != 2) return null;
            int[] hm = hm(p[1]);
            if (hm == null) return null;
            return new Parsed(KIND_DAILY, hm[0], hm[1], 0);
        }
        if ("weekly".equals(p[0])) {
            if (p.length != 3) return null;
            int mask = 0;
            String[] ds = p[1].split(",");
            if (ds.length == 0 || ds.length > 7) return null;
            for (int i = 0; i < ds.length; i++) {
                int d = num(ds[i]);
                if (d < 1 || d > 7) return null;
                mask |= 1 << (d - 1);
            }
            int[] hm = hm(p[2]);
            if (hm == null || mask == 0) return null;
            return new Parsed(KIND_WEEKLY, hm[0], hm[1], mask);
        }
        return null;
    }

    /** 规则是否合法 */
    public static boolean valid(String rule) {
        return parse(rule) != null;
    }

    /**
     * 下一次触发时刻（毫秒）。不合法返回 -1。
     *
     * <p>「刚好等于当前时刻」也算已过，推到下一个周期：闹钟与执行都在分钟边界上，
     * 若用 {@code >=} 就会在 23:00:00.000 那一刻算出「下一次 = 现在」，
     * 执行完重排又得到同一个时刻，一分钟内反复触发。</p>
     */
    public static long nextRun(String rule, long now) {
        Parsed p = parse(rule);
        if (p == null) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, p.hour);
        c.set(Calendar.MINUTE, p.minute);
        if (p.kind == KIND_DAILY) {
            if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
            return c.getTimeInMillis();
        }
        // 从今天开始最多看 8 天：今天我可能已经错过（1），剩下 7 天内必有一天匹配
        for (int i = 0; i < 8; i++) {
            if (i > 0) c.add(Calendar.DAY_OF_MONTH, 1);
            if (p.on(dow(c)) && c.getTimeInMillis() > now) return c.getTimeInMillis();
        }
        return -1;
    }

    /**
     * 最近一次「本该触发」的时刻（毫秒）。不合法返回 -1。
     *
     * <p>这是「到点没到点」的唯一判据：闹钟醒来时判断
     * {@code prevRun(rule, now) > item.lastRun} —— 说明最近这个计划点还没跑过。
     * 比「now 与 nextRun 的差小于某个容差」稳得多：Doze 会把闹钟推迟几分钟到几小时，
     * 容差写小了漏跑、写大了重跑，而「计划点 vs 上次执行」两个都是确切时刻，没有容差可调。</p>
     */
    public static long prevRun(String rule, long now) {
        Parsed p = parse(rule);
        if (p == null) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, p.hour);
        c.set(Calendar.MINUTE, p.minute);
        if (p.kind == KIND_DAILY) {
            if (c.getTimeInMillis() > now) c.add(Calendar.DAY_OF_MONTH, -1);
            return c.getTimeInMillis();
        }
        for (int i = 0; i < 8; i++) {
            if (i > 0) c.add(Calendar.DAY_OF_MONTH, -1);
            if (p.on(dow(c)) && c.getTimeInMillis() <= now) return c.getTimeInMillis();
        }
        return -1;
    }

    /** 规则里出现的时间（时:分）—— UI 编辑时用它回填，避免把 "daily 23:00" 再拆一遍 */
    public static int[] timeOf(String rule) {
        Parsed p = parse(rule);
        return p == null ? null : new int[]{p.hour, p.minute};
    }

    /** 星期位掩码 → 「1,3,5」这种文本（编辑时回填） */
    public static String weekdays(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            if ((mask & (1 << (i - 1))) == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    public static int dow(Calendar c) {
        // Calendar：SUNDAY=1 … SATURDAY=7；这里换成 1=周一 … 7=周日
        return ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1;
    }

    /** "07:30" → {7,30}；接受 "7:30"；不接受 24:00 与 23:60 */
    private static int[] hm(String s) {
        int i = s.indexOf(':');
        if (i <= 0 || i == s.length() - 1) return null;
        int h = num(s.substring(0, i));
        int m = num(s.substring(i + 1));
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return new int[]{h, m};
    }

    /** 只接受纯数字（"+7"、"-1"、" 7" 都不算），失败返回 -1 */
    private static int num(String s) {
        if (s == null || s.isEmpty() || s.length() > 2) return -1;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
