package com.hex91.file.fs;

import com.hex91.file.utils.TaskJournal;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 文件任务队列（v1.77.0）：把「复制 / 移动」从阻塞调用改成可看进度、可取消的后台任务。
 *
 * <p>以前 {@link FileOps#copy} 是同步 64KB 循环，调用方只能套一个不确定进度条干等；
 * 几万文件的目录既不知道还剩多久，也中断不了，中断后还会留下半截文件。这里解决三件事：</p>
 * <ol>
 *   <li><b>真实进度</b>：先把源递归量一遍（字节 + 文件数）作为分母，拷贝时按块累加。</li>
 *   <li><b>可取消</b>：每个 128KB 块检查一次取消标志，抛 {@link Cancel} 打断深递归。</li>
 *   <li><b>不留半截</b>：写入 {@code 名字.part}，写完才 rename 成正式名字；
 *       取消或出错时删掉 .part，正式文件要么完整要么不存在。</li>
 * </ol>
 *
 * <p>单线程串行执行：手机存储是同一块闪存，多线程并发拷贝只会互相拖慢并让进度乱跳。
 * 队列本身也是「用户操作顺序」的体现，第二个任务排在后面才符合直觉。</p>
 */
public final class FileTaskEngine {

    /** 任务列表变化（含进度），可能在**工作线程**回调，UI 侧自行切主线程 */
    public interface Listener {
        void onTasksChanged();
    }

    /** 进度回调（引擎内部用，测试可传 null） */
    public interface Sink {
        void onProgress(FileTask t);
    }

    /**
     * root 区回退：/data 等 File API 无权限的目录用 su 兜底。
     * 做成接口注入而不是直接调 {@link RootIO}，是为了让引擎保持纯 Java 可单测。
     */
    public interface RootFallback {
        boolean copy(File src, File dstDir);

        boolean move(File src, File dstDir);

        boolean delete(File f);
    }

    /** 取消信号：用异常打断多层递归，比每层返回 boolean 干净 */
    static final class Cancel extends RuntimeException {
        Cancel() { super("cancelled"); }
    }

    /**
     * v2.15.0（F-19）：暂停信号。与 {@link Cancel} 的区别只有一处，但很关键 ——
     * 取消要删掉半截 {@code .part}，暂停要留着它当续传起点。
     */
    static final class Pause extends RuntimeException {
        Pause() { super("paused"); }
    }

    private static final FileTaskEngine INSTANCE = new FileTaskEngine();

    public static FileTaskEngine get() { return INSTANCE; }

    private static final int BUF = 128 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final long NOTIFY_MS = 120;
    private static final int HISTORY_MAX = 20;

    private static final class Job {
        final FileTask task;
        final List<File> srcs;
        final File dstDir;
        /**
         * v2.15.0（F-19）：已经量过分母。续传时不能再量一遍 ——
         * totalBytes 会翻倍，百分比一下就掉到 50%。
         */
        boolean measured;

        Job(FileTask task, List<File> srcs, File dstDir) {
            this.task = task;
            this.srcs = srcs;
            this.dstDir = dstDir;
        }
    }

    private final Object lock = new Object();
    /**
     * v2.4.0（O-21）：账本写入锁。
     *
     * <p>与 {@link #lock} 分开：{@code lock} 保护任务队列，持锁时间要短；
     * 账本落盘是 IO，不能占着队列锁。两者只在此处按 {@code journalLock → lock}
     * 的顺序嵌套，不会死锁。</p>
     */
    private final Object journalLock = new Object();
    private final LinkedList<Job> pending = new LinkedList<Job>();
    /** 含已完成的任务，供面板回看；超出上限时从头部淘汰已结束的 */
    private final ArrayList<FileTask> all = new ArrayList<FileTask>();
    /**
     * v2.15.0（F-19）：暂停中的任务 → 它原来的 Job。
     *
     * <p>存在这里而不是任务对象上：Job 里有「量过分母」与来源列表，属于引擎内部状态；
     * 放 {@link FileTask} 里会让界面误以为可以自己拼一个回来。任务真正结束（或被取消）
     * 时必须移除，否则内存里会一直留着一份来源列表。</p>
     */
    private final java.util.HashMap<Integer, Job> pausedJobs = new java.util.HashMap<Integer, Job>();

    private int seq = 1;
    private Thread worker;
    /** 主界面任务条（单槽，onPause 时会被置空） */
    private volatile Listener listener;
    /**
     * v1.98.0：附加监听者（前台服务用它刷通知）。
     *
     * <p>为什么不合进 {@code listener}：界面在 onPause 里会 {@code setListener(null)} 主动断开
     * 引用，但那正是「切到后台、最需要通知栏进度」的时刻 —— 服务必须有独立的一份订阅。</p>
     */
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> extra =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();
    private volatile RootFallback root;

    private FileTaskEngine() {}

    public void setListener(Listener l) { listener = l; }

    public void addListener(Listener l) {
        if (l != null && !extra.contains(l)) extra.add(l);
    }

    public void removeListener(Listener l) { extra.remove(l); }

    public void setRootFallback(RootFallback r) { root = r; }

    // ------------------------------------------------------------------ 未完成任务账本（R-07）

    /** 账本容器；null = 不记账（桌面单测、只关心拷贝行为的调用方） */
    private TaskJournal.Store journalStore;

    /**
     * v1.99.2（R-07）：设置账本容器。
     *
     * <p><b>刻意不在这里写一次</b>：启动时 {@code all} 是空的，一写就把上个进程留下的账本
     * 清成「没有未完成任务」—— 而那份内容正是这次要读的东西。账本由 submit 与
     * 任务结束时的 {@link #writeJournal()} 维护。</p>
     *
     * <p>v1.99.3：参数从 {@code File} 换成 {@link TaskJournal.Store} —— 生产用
     * {@link TaskStore}（SharedPreferences），单测可注入内存实现。</p>
     */
    public void setJournalStore(TaskJournal.Store store) {
        journalStore = store;
    }

    /** 兼容旧调用点：直接给一个文件当账本 */
    public void setJournalFile(final File f) {
        journalStore = f == null ? null : new TaskJournal.Store() {
            public void save(java.util.List<TaskJournal.Entry> es) {
                TaskJournal.save(f, es);
            }
        };
    }

    /** v1.99.3：按 id 找任务；找不到返回 null */
    private FileTask findLocked(int id) {
        for (FileTask t : all) if (t.id == id) return t;
        return null;
    }

    /**
     * 把「还没结束的任务」写回账本。任务进队列、每次结束各写一次 —— 都很短（几条路径），
     * 相比拷贝几个 G 的耗时可以忽略；换来的是进程被清掉后能告诉用户「上次做到哪」。
     *
     * <p>已请求取消的任务不记：用户按了取消，重启后再劝他重试是反直觉的。</p>
     *
     * <p><b>v2.4.0（O-21）：快照与落盘必须在同一把锁里。</b>原来 {@code store.save(es)}
     * 在 {@code lock} 之外，两个线程可以这样交错：A 构建 {@code [T1]} → B 构建
     * {@code [T1,T2]} 并保存 → A 的 {@code save([T1])} 后落盘，账本把 T2 丢了。
     * 后果不是文件损坏，而是杀进程重启后「上次没做完」的提示漏项。
     * 锁顺序固定为 {@code journalLock → lock}：{@link #writeJournal()} 的两个调用点
     * （{@link #submit}、任务收尾的 finally）都在 {@code lock} 之外，不会反向嵌套。</p>
     */
    private void writeJournal() {
        final TaskJournal.Store store = journalStore;
        if (store == null) return;
        synchronized (journalLock) {
            java.util.List<TaskJournal.Entry> es = new java.util.ArrayList<TaskJournal.Entry>();
            synchronized (lock) {
                for (FileTask t : all) {
                    if (t.finished() || t.cancelRequested) continue;
                    if (t.srcs == null || t.dstDir == null) continue;
                    es.add(new TaskJournal.Entry(t.kind, t.dstDir, t.srcs));
                }
            }
            store.save(es);
        }
    }

    /**
     * v1.99.2（R-07）：按同一个来源与目标再提交一次，用于任务面板的「重试」。
     *
     * <p>v1.99.3：改为按 id 重试 —— 面板 / 任务中心拿到的只有列表里的快照，
     * 传对象会让人以为「重试的是那个旧对象」，其实提交的是新任务。</p>
     */
    public FileTask retry(int id) {
        FileTask old;
        synchronized (lock) {
            old = findLocked(id);
            if (old == null) return null;
            // 只在「跑完了但没成功」时允许：还在队列里再提一遍就是重复拷两份；
            // DONE 再重试则是把已经做完的事再做一次（用户要的是失败行上的按钮）
            if (!old.finished() || old.state == FileTask.DONE) return null;
            if (old.srcs == null || old.srcs.isEmpty() || old.dstDir == null) return null;
        }
        // 源可能已被用户删掉/移走：这时重提一个注定失败的任务，只会再报一次失败。
        // 目录里至少还剩一项就允许（部分成功的场景：失败的往往是其中几个）
        boolean any = false;
        for (java.io.File f : old.srcs) {
            if (f != null && f.exists()) { any = true; break; }
        }
        if (!any) return null;
        return submit(old.kind, old.srcs, old.dstDir);
    }

    /** 兼容旧调用点 */
    public FileTask retry(FileTask old) {
        return old == null ? null : retry(old.id);
    }

    /**
     * 提交一批文件/目录到 dstDir。
     * 立即返回任务对象（此时状态 QUEUED），真正的量体积与拷贝在工作线程里做。
     */
    public FileTask submit(int kind, List<File> srcs, File dstDir) {
        if (srcs == null || srcs.isEmpty() || dstDir == null) return null;
        FileTask t;
        synchronized (lock) {
                    t = new FileTask(seq++, kind,
                            (kind == FileTask.MOVE ? FileTask.TITLE_MOVE : FileTask.TITLE_COPY)
                                    + ":" + srcs.size(),
                            dstDir.getPath());
            t.srcs = new ArrayList<File>(srcs);      // R-07：留一份给「重试」与账本
            t.dstDir = dstDir;
            // v2.15.0（F-19）：记下已拷完的文件路径，续传据此跳过；任务结束即置空
            t.copied = new java.util.HashSet<String>();
            pending.add(new Job(t, t.srcs, dstDir));
            all.add(t);
            // 淘汰策略：只从头部删「已结束」的，绝不碰队列里的
            while (all.size() > HISTORY_MAX) {
                FileTask head = all.get(0);
                if (!head.finished()) break;
                all.remove(0);
            }
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    public void run() { loop(); }
                }, "hf-file-task");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
        writeJournal();
        fire();
        return t;
    }

    /** 当前任务快照（活动在前、已完成在后），面板与状态栏共用 */
    public List<FileTask> tasks() {
        synchronized (lock) {
            return new ArrayList<FileTask>(all);
        }
    }

    /**
     * 是否还有未结束的任务。
     *
     * <p>v1.77.1 修正：原来只判断 {@code pending} 是否为空，而 worker 一旦把任务取走
     * 就从 pending 里移除了，于是<b>正在拷贝时</b>这里返回 false —— 任务条的「取消」
     * 按钮因此走成「打开任务面板」，只有进弹窗才取消得掉。现在把正在跑的任务也算上。</p>
     */
    public boolean isBusy() {
        return hasActive();
    }

    /** 存在排队中或正在执行的任务（任务条按钮该显示「取消」而不是「查看」） */
    public boolean hasActive() {
        synchronized (lock) {
            for (FileTask t : all) {
                if (t.cancelable()) return true;
            }
            return false;
        }
    }

    /**
     * v2.15.0（F-19）：是否还有**在做功**的任务（排队 / 运行）。
     *
     * <p>与 {@link #hasActive()} 的差别就是「已暂停」：暂停的任务不该继续占着前台服务
     * 与常驻通知（用户明确说了先停一停），但任务本身没结束，所以也不能从列表里清掉。</p>
     */
    public boolean hasRunning() {
        synchronized (lock) {
            for (FileTask t : all) {
                if (t.pausable()) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ 暂停 / 继续（F-19）

    /**
     * 暂停一个任务。正在跑的会在下一个 128KB 块边界停下（当前块写完），
     * 排队中的直接转「已暂停」，不占 worker。
     *
     * @return 是否真的置上了暂停标志
     */
    public boolean pause(int id) {
        synchronized (lock) {
            FileTask t = findLocked(id);
            if (t == null || !t.pausable()) return false;
            t.pauseRequested = true;
        }
        fire();
        return true;
    }

    /**
     * 继续一个已暂停的任务：把它原来的 Job 放回队首之前（保持用户操作顺序就是放队尾，
     * 但用户刚点了「继续」，排到一堆新任务后面等于点了没用 —— 放队首）。
     */
    public boolean resume(int id) {
        Job job;
        FileTask t;
        synchronized (lock) {
            t = findLocked(id);
            if (t == null || !t.resumable()) return false;
            job = pausedJobs.remove(id);
            if (job == null) {
                // 按理走不到：暂停时一定存了 Job。防御性地用任务自己的来源重建一个，
                // 代价是「重新量一遍 + 已完成的文件靠 .part 与 copied 缺省跳过」——
                // 能跑完，只是可能重拷几个文件
                if (t.srcs == null || t.dstDir == null) return false;
                job = new Job(t, t.srcs, t.dstDir);
            }
            t.pauseRequested = false;
            // 注意：这里**不能**清 resumeSrc —— 它就是续传起点，由 copyFile 的
            // resumeBase 用掉之后自己清。第一版在这里顺手清了，结果「继续」变成了
            // 从头重拷（内容仍对，但进度虚高、白读一遍盘）
            t.state = FileTask.QUEUED;
            pending.addFirst(job);
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    public void run() { loop(); }
                }, "hf-file-task");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
        fire();
        return true;
    }

    /** 取消全部未结束任务（已经在拷的当前块会先写完，再退出） */
    public void cancelAll() {
        synchronized (lock) {
            for (FileTask t : all) {
                if (t.cancelable()) {
                    t.cancelRequested = true;
                    // 已暂停的任务没有线程会看到这个标志，就地收尾
                    if (t.state == FileTask.PAUSED) endPausedLocked(t);
                }
            }
        }
        writeJournal();
        fire();
    }

    /** 只取消某个任务；排队中的会被 worker 跳过（进入 exec 时状态直接判取消） */
    public void cancel(int id) {
        boolean paused = false;
        synchronized (lock) {
            for (FileTask t : all) {
                if (t.id == id && t.cancelable()) {
                    t.cancelRequested = true;
                    if (t.state == FileTask.PAUSED) {
                        endPausedLocked(t);
                        paused = true;
                    }
                }
            }
        }
        if (paused) writeJournal();
        fire();
    }

    /**
     * v2.15.0（F-19）：暂停任务的收尾（取消时走这里）。
     *
     * <p>要点是**删掉 {@code .part} 并解除「正在写」登记**：暂停时故意让它继续挂在
     * {@link TempFiles} 里（否则设置页的遗留清理会把续传起点当垃圾删掉），
     * 一旦用户改成取消，这份半截文件就没有存在理由了。</p>
     */
    private void endPausedLocked(FileTask t) {
        t.state = FileTask.CANCELLED;
        t.finishedAt = System.currentTimeMillis();
        pausedJobs.remove(t.id);
        discardResume(t);
    }

    /** 丢掉续传现场：删半截文件、撤销临时文件登记、清空已拷完集合 */
    private void discardResume(FileTask t) {
        File part = t.resumePart;
        if (part != null) {
            TempFiles.end(part);        // 与暂停时的「保留登记」配对
            part.delete();
        }
        t.resumeSrc = null;
        t.resumePart = null;
        t.resumeOffset = 0;
        t.copied = null;
    }

    /** 清掉已完成/失败的历史，面板上「清理」用 */
    public void clearFinished() {
        synchronized (lock) {
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).finished()) all.remove(i);
            }
        }
        fire();
    }

    // ------------------------------------------------------------------ 工作线程

    private void loop() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (pending.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                job = pending.removeFirst();
            }
            exec(job);
        }
    }

    private void exec(Job job) {
        final FileTask t = job.task;
        if (t.cancelRequested) {                  // 排队时就被取消：不必再量体积
            t.state = FileTask.CANCELLED;
            t.finishedAt = System.currentTimeMillis();
            discardResume(t);
            writeJournal();
            fire();
            return;
        }
        if (t.pauseRequested) {                   // 排队时就点了暂停：不占 worker
            t.state = FileTask.PAUSED;
            synchronized (lock) { pausedJobs.put(t.id, job); }
            fire();
            return;
        }
        t.state = FileTask.RUNNING;
        // 续传不重置 startedAt：速度按「从第一次开始算」的总时均，比暂停后重新计更接近事实
        if (t.startedAt <= 0) t.startedAt = System.currentTimeMillis();
        fire();
        try {
            if (!job.measured) {
                // 量分母前先清零：暂停后再量一遍不能在上一次的结果上累加（会让分母翻倍）
                t.totalBytes = 0;
                t.totalFiles = 0;
                for (File f : job.srcs) measure(f, t, 0);
                job.measured = true;
                fire();
            }
            for (File f : job.srcs) {
                if (t.cancelRequested) throw new Cancel();
                if (t.pauseRequested) throw new Pause();
                if (isCopied(t, f)) continue;     // 续传：这一整项上次就拷完了
                try {
                    if (t.isMove()) moveOne(f, job.dstDir, t);
                    else copyOne(f, job.dstDir, t);
                } catch (Cancel c) {
                    throw c;
                } catch (Pause p) {
                    throw p;
                } catch (Throwable e) {
                    // 单个文件失败不该让整批停下：记下来继续，最后报「部分失败」
                    t.failedFiles++;
                    t.error = e;
                    t.errorName = f.getName();
                    t.message = f.getName() + ": " + shortMsg(e);
                }
            }
            t.state = t.failedFiles > 0 ? FileTask.PARTIAL : FileTask.DONE;
        } catch (Cancel c) {
            t.state = FileTask.CANCELLED;
        } catch (Pause p) {
            t.state = FileTask.PAUSED;
        } catch (Throwable e) {
            t.state = FileTask.FAILED;
            t.error = e;
            t.errorName = null;
            t.message = shortMsg(e);
        } finally {
            if (t.state == FileTask.PAUSED) {
                // 暂停：留着续传现场，不记 finishedAt、不结账；账本里继续留这条，
                // 万一进程被清掉，「上次有任务没做完」还能把来源捞回来
                synchronized (lock) { pausedJobs.put(t.id, job); }
                fire();
            } else {
                t.finishedAt = System.currentTimeMillis();
                if (t.state == FileTask.DONE || t.state == FileTask.PARTIAL) {
                    // 补记：跳过/秒移动的项没有走过字节累加，收尾对齐总量
                    t.doneBytes = Math.max(t.doneBytes, t.totalBytes);
                    t.doneFiles = Math.max(t.doneFiles, t.totalFiles - t.failedFiles);
                }
                synchronized (lock) { pausedJobs.remove(t.id); }
                discardResume(t);
                writeJournal();      // R-07：这个任务结束了，账本里就不该再有它
                fire();
            }
        }
    }

    private static boolean isCopied(FileTask t, File f) {
        return t.copied != null && f != null && t.copied.contains(f.getAbsolutePath());
    }

    private static void markCopied(FileTask t, File f) {
        if (t.copied != null && f != null) t.copied.add(f.getAbsolutePath());
    }

    private void copyOne(File src, File dstDir, FileTask t) throws IOException {
        File dst = new File(dstDir, src.getName());
        if (samePath(src, dst)) {
            // 复制到自己所在目录：以前 FileOps 会「先把源截断再读」，直接把文件清空。
            // 这里当无事发生（记完成），数据不会被毁。
            creditDone(src, t);
            markCopied(t, src);
            return;
        }
        try {
            copyTree(src, dstDir, t, sink);
        } catch (IOException e) {
            RootFallback r = root;
            if (r == null || !r.copy(src, dstDir)) throw e;
            creditDone(src, t);          // su cp 没有进度回调，按整份完成记
        }
        markCopied(t, src);
    }

    private void moveOne(File src, File dstDir, FileTask t) throws IOException {
        File dst = new File(dstDir, src.getName());
        if (samePath(src, dst)) {
            creditDone(src, t);
            markCopied(t, src);
            return;
        }
        if (!dstDir.isDirectory() && !dstDir.mkdirs() && !dstDir.isDirectory())
            throw FsError.of(FsError.Code.MKDIR_FAIL, dstDir);
        if (src.renameTo(dst)) {          // 同分区：秒完成，不需要逐字节拷
            creditDone(src, t);
            markCopied(t, src);           // 续传时源已经不在，靠这份记录跳过
            pump(t);
            return;
        }
        copyTree(src, dstDir, t, sink);   // 跨分区：拷贝后删源
        // v1.99.1：递归删除已统一在 TreeOps（链接只删链、深度上限、失败不外抛）——
        // 原来这里又实现了一份，与 TreeOps.delete 逐行同义，改一处漏一处
        if (!TreeOps.delete(src)) {
            RootFallback r = root;
            if (r == null || !r.delete(src))
                throw FsError.of(FsError.Code.DELETE_SRC_FAIL, src.getPath());
        }
        markCopied(t, src);
    }

    private final Sink sink = new Sink() {
        public void onProgress(FileTask t) { pump(t); }
    };

    private void pump(FileTask t) {
        long now = System.currentTimeMillis();
        if (now - t.lastNotify < NOTIFY_MS) return;
        t.lastNotify = now;
        fire();
    }

    private void fire() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onTasksChanged();
            } catch (Throwable ignored) {
                // 列表刷新失败不该反过来影响拷贝
            }
        }
        for (Listener e : extra) {
            try {
                e.onTasksChanged();
            } catch (Throwable ignored) {
                // 通知刷新失败同理：不能影响正在跑的任务
            }
        }
    }

    // ------------------------------------------------------------------ 拷贝核心

    /**
     * 把 src 拷进 dstDir（同名目录合并、同名文件覆盖）。
     *
     * @param t   可为 null（只要结果不要进度，单测用）
     * @param sink 可为 null
     */
    public static void copyTree(File src, File dstDir, FileTask t, Sink sink) throws IOException {
        if (!dstDir.isDirectory() && !dstDir.mkdirs() && !dstDir.isDirectory())
            throw FsError.of(FsError.Code.MKDIR_FAIL, dstDir);
        copyNode(src, new File(dstDir, src.getName()), t, sink, 0);
    }

    private static void copyNode(File src, File dst, FileTask t, Sink sink, int depth)
            throws IOException {
        if (t != null && t.cancelRequested) throw new Cancel();
        // v2.15.0（F-19）：暂停在文件边界也生效，不必等下一个 128KB 块
        if (t != null && t.pauseRequested) throw new Pause();
        if (depth > MAX_DEPTH) throw FsError.of(FsError.Code.DEEP_PATH, src.getPath());
        // v1.97.0：软链接不能跟进。src.isDirectory()/listFiles() 会跟随链接，
        // 于是「复制一个链接目录」会把目标整棵树复制一份（链接成环时还会递归到栈溢出）。
        // 这里按原样复刻成链接；文件系统不支持（FAT32 等）就跳过该条，不判整单失败。
        if (LinkUtil.isLink(src)) {
            // 复刻失败（FAT32 不支持链接 / 目标不可写）就跳过该条：整单不该因为一个链接失败，
            // 但也绝不能退回「跟进链接把目标复制过来」那种行为。
            if (TreeOps.SYSTEM.copyLink(src, dst) && t != null) {
                t.doneFiles++;
                markCopied(t, src);
            }
            return;
        }
        // 只记文件路径（链接在上面已返回），所以这里命中一定是「上次拷完的文件」
        if (t != null && isCopied(t, src)) return;
        if (src.isDirectory()) {
            if (dst.isFile()) {                       // 同名文件挡路：目录优先，让位
                if (!dst.delete()) throw FsError.of(FsError.Code.TARGET_BUSY, dst);
            }
            if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory())
                throw FsError.of(FsError.Code.CREATE_FAIL, dst);
            File[] kids = src.listFiles();
            if (kids == null) throw FsError.of(FsError.Code.READ_FAIL, src.getPath());
            for (File k : kids) {
                copyNode(k, new File(dst, k.getName()), t, sink, depth + 1);
            }
        } else {
            copyFile(src, dst, t, sink);
            if (t != null) {
                t.doneFiles++;
                markCopied(t, src);
            }
        }
    }

    /** 单文件拷贝：写 .part → rename，保证目标不会出现「半截文件」 */
    private static void copyFile(File src, File dst, FileTask t, Sink sink) throws IOException {
        if (dst.isDirectory()) dst = uniqueFile(dst.getParentFile(), src.getName());
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw FsError.of(FsError.Code.CREATE_FAIL, parent);
        File part = new File(parent, dst.getName() + TempFiles.PART);
        // v2.15.0（F-19）：这个文件上次被暂停在 half-way 的话，从那里接着写
        long base = resumeBase(t, src, part);
        // v2.4.0（R-08）：登记为「本进程正在写的临时文件」，遗留扫描就不会把它列出来
        // 让用户删掉 —— 删了之后 rename 必然失败，几 GB 的拷贝白跑。
        // end 必须在 rename 之前：改名之后这个路径已不存在，留在注册表里只会挡住下一次
        // 同名写入（同一个目标连拷两次的场景）。
        TempFiles.begin(part);
        boolean keep = false;
        try {
            writePart(src, part, t, sink, base);
            if (!part.renameTo(dst)) {
                // 少数文件系统不支持覆盖式 rename（目标已存在时）
                if (!dst.delete() || !part.renameTo(dst)) {
                    part.delete();
                    throw FsError.of(FsError.Code.WRITE_FAIL, dst.getPath());
                }
            }
        } catch (Pause p) {
            // 暂停：半截 .part 是续传起点，既不能删也不能解除登记 ——
            // 解除之后设置页的遗留清理会把它当垃圾删掉，续传就白记了
            keep = true;
            throw p;
        } finally {
            if (!keep) TempFiles.end(part);
        }
    }

    /**
     * v2.15.0（F-19）：判断这个文件能不能从已有 {@code .part} 接着写。
     *
     * @return 0 = 从头写（没有可用的半截）；&gt;0 = 已写字节数，从这个偏移续写
     *
     * <p>校验四项，任一不过就宁可重来：part 还在、长度正好等于记录的偏移、
     * 源长度与 mtime 都没变。源在暂停期间被改过时，半截内容与新源对不上，
     * 续写的产物会是两段不同版本的拼接 —— 这种「看起来成功、内容是坏的」比重拷十分钟糟得多。</p>
     */
    private static long resumeBase(FileTask t, File src, File part) {
        if (t == null || t.resumeSrc == null || !samePath(src, t.resumeSrc)) return 0;
        long off = t.resumeOffset;
        File savedPart = t.resumePart;
        long savedLen = t.resumeSrcLen;
        long savedMtime = t.resumeSrcMtime;
        // 用掉即清：一次暂停只对应一个文件，留着会被下一个文件误接
        t.resumeSrc = null;
        t.resumePart = null;
        t.resumeOffset = 0;
        boolean ok = off > 0
                && part.isFile()
                && part.length() == off
                && (savedPart == null || samePath(savedPart, part))
                && src.length() == savedLen
                && src.lastModified() == savedMtime;
        if (!ok) {
            // 这段字节不能信：把已计入的进度扣回来，从头写
            if (t.doneBytes >= off) t.doneBytes -= off;
            TempFiles.end(part);
            part.delete();
            return 0;
        }
        return off;
    }

    /** 续传时跳过源文件的前 n 字节：skip 一次跳不完（或返回 0）时退化成逐字节读 */
    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) {
                left -= s;
                continue;
            }
            if (in.read() < 0) throw FsError.of(FsError.Code.RESUME_EOF, left);
            left--;
        }
    }

    /**
     * 把 src 写进 part；取消/失败时清掉半截文件，成功时只 flush，不负责 rename。
     *
     * <p>v2.15.0（F-19）：{@code base > 0} 表示前面这部分已经在 part 里（上次暂停留下的），
     * 从源的 base 偏移处接着读、以追加方式写。暂停时只 flush 不删，并把
     * 「文件 + 已写字节」记进任务，成为下一次的续传起点。</p>
     */
    private static void writePart(File src, File part, FileTask t, Sink sink, long base)
            throws IOException {
        InputStream in = null;
        OutputStream out = null;
        long written = base;
        try {
            in = new FileInputStream(src);
            if (base > 0) {
                skipFully(in, base);
                out = new FileOutputStream(part, true);
            } else {
                out = new FileOutputStream(part);
            }
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (t != null && t.cancelRequested) throw new Cancel();
                if (t != null && t.pauseRequested) {
                    // 128KB 边界的检查点：当前块已落盘，先把这一段的偏移记下来再退出
                    out.flush();
                    t.resumeSrc = src;
                    t.resumePart = part;
                    t.resumeOffset = written;
                    t.resumeSrcLen = src.length();
                    t.resumeSrcMtime = src.lastModified();
                    throw new Pause();
                }
                out.write(buf, 0, n);
                written += n;
                if (t != null) {
                    t.doneBytes += n;
                    if (sink != null) sink.onProgress(t);
                }
            }
            out.flush();
        } catch (Pause p) {
            closeQuietly(in);
            closeQuietly(out);
            throw p;                                  // 半截文件保留，等「继续」
        } catch (Cancel c) {
            closeQuietly(in);
            closeQuietly(out);
            part.delete();                            // 取消：清掉半截临时文件
            throw c;                                  // 必须原样抛出，交由引擎记为「已取消」
        } catch (IOException e) {
            closeQuietly(in);
            closeQuietly(out);
            part.delete();
            throw e;
        } catch (Throwable e) {
            closeQuietly(in);
            closeQuietly(out);
            part.delete();
            throw new IOException(shortMsg(e));
        }
        closeQuietly(in);
        closeQuietly(out);
    }

    /** 递归量体积：字节进 totalBytes、文件进 totalFiles（目录本身不计） */
    static void measure(File f, FileTask t, int depth) {
        // t 允许为 null（量体积可以不挂任务），所以必须在最前面挡住，
        // 否则下面 t.totalFiles++ 会 NPE —— 前面只写了 t != null 的半截守卫。
        if (t == null || f == null) return;
        // 量体积也要能取消：几十万文件的大目录量一遍要好几秒，
        // 期间点取消却毫无反应，用户会以为按钮坏了。
        if (t.cancelRequested) throw new Cancel();
        // 量体积也接受暂停：几十万文件量一遍要好几秒，否则「暂停」在这几秒里像没反应。
        // 续传时 job.measured 仍是 false，会先清零再量，不会把分母算两遍。
        if (t.pauseRequested) throw new Pause();
        if (depth > MAX_DEPTH) return;
        // v1.99.2（R-03）：量分母时链接不跟进。
        // 进度是 doneBytes/totalBytes，分母把链接目标的整棵树算进来、分子却只在
        // copyNode 里给复刻成功的链接 +1 —— 只要目录里有一条指向大目录的链接，
        // 百分比就会停在一个永远到不了的值上（链接成环时还会量到上限 64 层）。
        // 链接在复制时被复刻成「一条链接」，所以这里按 1 个条目计，分子分母才对得上。
        if (LinkUtil.isLink(f)) {
            t.totalFiles++;
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) measure(k, t, depth + 1);
        } else {
            t.totalFiles++;
            long len = f.length();
            if (len > 0) t.totalBytes += len;
        }
    }

    /** 没有实际读写的完成路径（跳过 / rename 秒移动）补记进度 */
    private static void creditDone(File src, FileTask t) {
        FileTask tmp = new FileTask(-1, t.kind, "", "");
        measure(src, tmp, 0);
        t.doneBytes += tmp.totalBytes;
        t.doneFiles += tmp.totalFiles;
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 文件要覆盖的路径上是一个同名目录 → 换个名字，别把目录删了 */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "(" + System.currentTimeMillis() + ")" + ext);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static String shortMsg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
