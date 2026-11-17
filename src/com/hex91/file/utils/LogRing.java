package com.hex91.file.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.12.0（F-18）：固定容量的日志环形缓冲。
 *
 * <p>为什么要它：release 包里 {@code Log.x} 只进 logcat，用户报「闪退 / 点了没反应」时
 * 拿不到任何现场。崩溃那一瞬间的堆栈能说明「崩在哪」，但说明不了「崩之前做了什么」——
 * 而后者往往才是原因。环形缓冲只留最近几百行，内存固定（不随运行时长增长），
 * 崩溃时整段倒进崩溃报告里。</p>
 *
 * <p><b>它不拦截 {@code android.util.Log}</b>：那需要改全部几百处调用点，代价远超收益。
 * 目前由三处主动喂：{@link Bg} 的后台任务失败、{@link CrashLog} 的崩溃记录、
 * 以及需要留痕的关键路径（任务提交/取消、导入完成）。看日志时注意这个前提：
 * 这里不是 logcat 的镜像，是「本应用主动认为值得留的几行」。</p>
 *
 * <p>纯 JDK，桌面可直接单测。</p>
 */
public final class LogRing {

    /** 容量：200 行 × 平均 80 字符 ≈ 16 KB 常驻，一次性崩溃报告塞得进 256 KB 上限 */
    private static final int CAP = 200;

    private static final String[] LINES = new String[CAP];
    private static int head;      // 下一条写入位置
    private static int count;     // 已有条数（≤ CAP）

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private LogRing() {}

    /** 记一行（tag 建议用类名简写） */
    public static void add(String tag, String msg) {
        if (msg == null) return;
        addLine((tag == null || tag.length() == 0 ? "-" : tag) + ": " + msg);
    }

    /** 记一行（已拼好的整行） */
    public static synchronized void addLine(String line) {
        if (line == null) return;
        // 单行过长（比如把整段堆栈塞进来）会挤掉几十行有用的上下文，先截断
        if (line.length() > 400) line = line.substring(0, 400) + "…";
        LINES[head] = TS.format(new Date()) + " " + line;
        head = (head + 1) % CAP;
        if (count < CAP) count++;
    }

    /** 最近 max 行，按时间正序（老的在前）；缓冲为空时返回空串 */
    public static synchronized String dump(int max) {
        if (count == 0) return "";
        int n = Math.min(max <= 0 ? count : max, count);
        StringBuilder sb = new StringBuilder(n * 80);
        int start = (head - n + CAP * 2) % CAP;
        for (int i = 0; i < n; i++) {
            String s = LINES[(start + i) % CAP];
            if (s == null) continue;
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized int size() {
        return count;
    }

    /** 只给单测/诊断用：清空 */
    public static synchronized void clear() {
        for (int i = 0; i < CAP; i++) LINES[i] = null;
        head = 0;
        count = 0;
    }
}
