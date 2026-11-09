package com.hex91.file;

/**
 * 一个「复制 / 移动」任务的运行状态（v1.77.0）。
 *
 * <p>为什么单独抽一个类：文件复制要能显示**真实**进度、随时取消、并把结果留在
 * 任务面板里回看。这些状态要在 UI 线程与工作线程之间共享，所以字段全部
 * {@code volatile}、读写不加锁——读到的是几十毫秒前的快照，对进度条没有影响，
 * 但能保证 UI 线程永远不会被拷贝线程卡住。</p>
 *
 * <p>纯 Java，不引用 {@code android.*}，便于桌面单测（test/FileTaskTest.java）。</p>
 */
public final class FileTask {

    public static final int COPY = 0;
    public static final int MOVE = 1;

    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    /** 有文件失败，其余成功 */
    public static final int PARTIAL = 3;
    public static final int FAILED = 4;
    public static final int CANCELLED = 5;

    public final int id;
    public final int kind;
    /** 显示用标题，如「复制 3 项」 */
    public final String title;
    public final String dstPath;

    public volatile int state = QUEUED;

    /** 总量：先递归量一遍再拷，进度才有分母 */
    public volatile long totalBytes;
    public volatile int totalFiles;

    /**
     * 已完成量。字节在拷贝时按块累加；「跳过同路径」「同分区 rename 秒移动」
     * 这类没有实际读写的路径由引擎一次性补记，保证结束时百分比一定到 100。
     */
    public volatile long doneBytes;
    public volatile int doneFiles;
    public volatile int failedFiles;

    public volatile boolean cancelRequested;
    /** 最近一次错误（含文件名），失败/部分失败时展示 */
    public volatile String message;

    public volatile long startedAt;
    public volatile long finishedAt;
    /** 节流用：上次向上层抛进度的时间戳（包内可见，UI 不需要） */
    volatile long lastNotify;

    FileTask(int id, int kind, String title, String dstPath) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.dstPath = dstPath;
    }

    public boolean finished() {
        return state == DONE || state == PARTIAL || state == FAILED || state == CANCELLED;
    }

    public boolean cancelable() {
        return state == QUEUED || state == RUNNING;
    }

    public boolean isMove() {
        return kind == MOVE;
    }

    /**
     * 0~100。总字节为 0（全是空文件）时退化为按文件数，两个都 0 时给 0：
     * 空目录任务在完成判定里直接算 100，不会出现「转完了进度还是 0」。
     */
    public int percent() {
        if (state == DONE || state == PARTIAL) return 100;
        if (totalBytes > 0) {
            long p = doneBytes * 100L / totalBytes;
            return (int) Math.max(0, Math.min(100, p));
        }
        if (totalFiles > 0) {
            int p = doneFiles * 100 / totalFiles;
            return Math.max(0, Math.min(100, p));
        }
        return 0;
    }

    /** 平均速度（字节/秒），未开始或已结束过久返回 0 由调用方决定是否显示 */
    public long speed(long nowMs) {
        if (startedAt <= 0) return 0;
        long end = finishedAt > 0 ? finishedAt : nowMs;
        long ms = Math.max(1, end - startedAt);
        return doneBytes * 1000L / ms;
    }

    public String stateText() {
        switch (state) {
            case QUEUED: return "排队中";
            case RUNNING: return percent() + "%";
            case DONE: return "已完成";
            case PARTIAL: return "部分失败";
            case FAILED: return "失败";
            case CANCELLED: return "已取消";
            default: return "";
        }
    }
}
