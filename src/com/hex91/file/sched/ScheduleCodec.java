package com.hex91.file.sched;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务表的文本编解码（v2.17.0，F-23）。
 *
 * <p>和 {@code TaskJournal} 同一套思路：几行纯文本、带格式头、任何解析异常都当成
 * 「没有任务」而不是抛给 UI。行格式固定 8 列、用制表符分隔，字段里的
 * {@code \ \t \n} 做转义 —— 路径里出现制表符罕见但不是不可能（Linux/ext4 允许），
 * 不转义的话一条任务会被切成两半，读回来是一条语法错乱、目标路径被截断的任务。</p>
 *
 * <p>{@code MAGIC} 变化时直接视为「空表」：宁可让用户重建，也不能按旧布局误读新数据。</p>
 */
public final class ScheduleCodec {

    private static final String MAGIC = "hexfile-schedule-1";
    /** 固定列数：id / enabled / action / param / lastRun / rule / target / lastResult */
    private static final int COLS = 8;

    private ScheduleCodec() {}

    public static String encode(List<ScheduleItem> list) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ScheduleItem it = list.get(i);
                if (it == null) continue;
                sb.append(it.id).append('\t')
                        .append(it.enabled ? '1' : '0').append('\t')
                        .append(esc(it.action)).append('\t')
                        .append(it.param).append('\t')
                        .append(it.lastRun).append('\t')
                        .append(esc(it.rule)).append('\t')
                        .append(esc(it.target)).append('\t')
                        .append(esc(it.lastResult)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 读回任务表；文本为空、缺头、列数不对、数字坏掉 → 返回空列表或跳过那一行 */
    public static List<ScheduleItem> decode(String text) {
        List<ScheduleItem> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) return out;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String[] c = line.split("\t", -1);
            if (c.length < COLS) continue;          // 半截行（写到一半断电）直接丢
            ScheduleItem it = new ScheduleItem();
            it.id = (int) lng(c[0], -1);
            if (it.id < 0) continue;
            it.enabled = "1".equals(c[1]);
            it.action = unesc(c[2]);
            it.param = (int) lng(c[3], 0);
            it.lastRun = lng(c[4], 0);
            it.rule = unesc(c[5]);
            it.target = unesc(c[6]);
            it.lastResult = unesc(c[7]);
            if (!it.actionKnown()) continue;        // 认不出的动作不执行，也不写回
            out.add(it);
        }
        return out;
    }

    /** 新建任务时分配一个没被占用的 id（删掉的 id 会被复用，无害：没有外部引用） */
    public static int nextId(List<ScheduleItem> list) {
        int max = 0;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ScheduleItem it = list.get(i);
                if (it != null && it.id > max) max = it.id;
            }
        }
        return max + 1;
    }

    private static long lng(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 转义：反斜杠优先，否则 "\\t" 会被当成「转义后的反斜杠 + 字母 t」 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    static String unesc(String s) {
        if (s == null || s.indexOf('\\') < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 't') sb.append('\t');
            else if (n == 'n') sb.append('\n');
            else if (n == 'r') sb.append('\r');
            else if (n == '\\') sb.append('\\');
            else {
                // 认不出的转义原样保留（老版本可能写过别的），不要吞掉字符
                sb.append('\\').append(n);
            }
        }
        return sb.toString();
    }
}
