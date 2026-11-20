package com.hex91.file.fs;

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
    /** v2.15.0（F-19）：用户主动暂停，字节级续传点已记在任务里，等「继续」 */
    public static final int PAUSED = 6;

    /** 任务标题稳定码（"COPY:3" / "MOVE:1"），文案由 ui/FsText 映射 */
    public static final String TITLE_COPY = "COPY";
    public static final String TITLE_MOVE = "MOVE";

    /** 状态稳定码；RUNNING 特殊，直接给 "NN%"，无需资源 */
    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_PARTIAL = "PARTIAL";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_PAUSED = "PAUSED";

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

    /**
     * v1.99.2（R-07）：原始来源与目标目录，供「重试」重建同一个任务。
     *
     * <p>只存在内存里（由引擎在 submit 时填），界面拿它做重试；序列化那部分在
     * {@link TaskJournal} —— 进程被杀时内存里的这些都随进程消失了，靠账本恢复。</p>
     */
    public volatile java.util.List<java.io.File> srcs;
    public volatile java.io.File dstDir;

    public volatile boolean cancelRequested;

    // ---- v2.15.0（F-19）：暂停与字节级续传
    /**
     * 暂停请求。工作线程在 128KB 块边界检查它：置位后当前块写完就停，
     * 而不是像取消那样丢掉半截文件。
     */
    public volatile boolean pauseRequested;

    /**
     * 暂停那一刻正在写的文件与已写入的字节数（续传起点）。
     *
     * <p>为什么要记「文件 + 偏移」而不是「任务级已拷多少」：拷贝的粒度是文件，
     * 一停就只能从这个文件的半截 {@code .part} 接着写；前面的文件已经 rename 成正式
     * 名字，跳过的依据是 {@link #copied}。</p>
     */
    public volatile java.io.File resumeSrc;
    public volatile java.io.File resumePart;
    public volatile long resumeOffset;
    /** 暂停时的源长度与 mtime：续传前比对，任一项变了就不敢接半截 */
    public volatile long resumeSrcLen;
    public volatile long resumeSrcMtime;

    /**
     * 已经拷完的源文件绝对路径（仅任务未结束时非空）。
     *
     * <p>续传要跳过这些文件：不记的话「继续」会把已完成的几百个文件重拷一遍，
     * 进度还会翻倍（doneBytes 重复累加）。只记文件不记目录 —— 目录跳过会把子项一起丢掉。</p>
     */
    java.util.Set<String> copied;

    /** 最近一次错误（含文件名），失败/部分失败时展示；诊断串，文案由 ui/FsText 映射 */
    public volatile String message;
    /** 结构化错误：FsError 走资源映射，非结构化错误回落到 message */
    public volatile Throwable error;
    /** 单文件失败时的文件名，UI 拼在映射文案前面 */
    public volatile String errorName;

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

    /** 可以把正在跑（或还没开跑）的任务暂停 */
    public boolean pausable() {
        return state == QUEUED || state == RUNNING;
    }

    /** 已暂停、等「继续」 */
    public boolean resumable() {
        return state == PAUSED;
    }

    /**
     * 能不能取消。v2.15.0 起「已暂停」也算：暂停只是不再读写，
     * 用户随时可能决定不干了，此时不给取消就只能先继续再取消，很别扭。
     */
    public boolean cancelable() {
        return state == QUEUED || state == RUNNING || state == PAUSED;
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
        // 暂停期间不该显示「0 B/s 还在跑」，也不该按墙钟继续摊薄：直接给 0，界面不显示速率
        if (state == PAUSED) return 0;
        if (startedAt <= 0) return 0;
        long end = finishedAt > 0 ? finishedAt : nowMs;
        long ms = Math.max(1, end - startedAt);
        return doneBytes * 1000L / ms;
    }

    public String stateText() {
        switch (state) {
            case QUEUED: return STATE_QUEUED;
            case RUNNING: return percent() + "%";
            case DONE: return STATE_DONE;
            case PARTIAL: return STATE_PARTIAL;
            case FAILED: return STATE_FAILED;
            case CANCELLED: return STATE_CANCELLED;
            case PAUSED: return STATE_PAUSED;
            default: return "";
        }
    }
}
