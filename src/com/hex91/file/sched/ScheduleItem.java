package com.hex91.file.sched;

/**
 * 一条定时任务（v2.17.0，F-23）。
 *
 * <p>纯数据、不引用 {@code android.*}：序列化与执行都按它来，桌面单测直接造对象即可。</p>
 */
public final class ScheduleItem {

    /** 清空目录内容（保留目录本身） */
    public static final String ACTION_CLEAN = "clean";
    /** 把目录压成同名 .zip（覆盖旧的） */
    public static final String ACTION_ZIP = "zip";
    /** 删除目录里超过 N 天没修改的文件 */
    public static final String ACTION_EXPIRE = "expire";

    public int id;
    public boolean enabled = true;
    /** 见 {@link ScheduleRule}：daily 23:00 / weekly 1,3 07:30 */
    public String rule = "daily 23:00";
    /** clean / zip / expire */
    public String action = ACTION_CLEAN;
    /** 目标目录绝对路径 */
    public String target = "";
    /** 仅 expire 用：保留天数 */
    public int param = 7;
    /** 上次执行时刻（0 = 从未） */
    public long lastRun;
    /** 上次执行的结果（人话，失败时是原因） */
    public String lastResult = "";

    public boolean actionKnown() {
        return ACTION_CLEAN.equals(action) || ACTION_ZIP.equals(action) || ACTION_EXPIRE.equals(action);
    }

    public ScheduleItem copy() {
        ScheduleItem c = new ScheduleItem();
        c.id = id;
        c.enabled = enabled;
        c.rule = rule;
        c.action = action;
        c.target = target;
        c.param = param;
        c.lastRun = lastRun;
        c.lastResult = lastResult;
        return c;
    }
}
