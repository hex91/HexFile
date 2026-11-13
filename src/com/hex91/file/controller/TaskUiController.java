package com.hex91.file.controller;

import com.hex91.file.utils.TaskJournal;
import com.hex91.file.utils.TaskStore;
import com.hex91.file.R;
import com.hex91.file.ui.HfDialogBuilder;
import com.hex91.file.ui.TaskService;
import com.hex91.file.ui.UiDecor;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.FileTask;
import com.hex91.file.fs.FileTaskEngine;
import com.hex91.file.fs.RootIO;
import android.os.Handler;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 任务条 + 任务中心入口 + 「上次有任务被中断」提示（v2.0.0，O-15 刀4）。
 *
 * <p>从这里开始，MainActivity 不再直接持有 {@code FileTaskEngine.Listener} —— 接上与摘下的时机
 * 交给 {@link #attach()} / {@link #detach()}，销毁时 {@link #destroy()} 收掉队列与监听，
 * 免得任务线程把已销毁的界面拖住。</p>
 *
 * <p>Handler 仍然用宿主那一个（{@link Host#ui()}，就是原来的 {@code taskUi}）：
 * 读目录的 20 秒看门狗也在用它，两个控制器共用一个主线程队列，不需要也不应该各开一个。</p>
 */
public final class TaskUiController {

    public interface Host {
        android.app.Activity activity();

        android.content.Context ctx();

        /** activity.findViewById(id) */
        View find(int id);

        void toast(String s);

        /** 两栏一起刷新（任务收尾后让新文件立刻出现） */
        void refreshAll();

        /** 宿主的主线程 Handler（原 taskUi） */
        Handler ui();

        /** 把任务投递到界面队列（原 listLeft.post） */
        void post(Runnable r);

        /** !isFinishing() && !isDestroyed() */
        boolean alive();

        /** 任务面板：跳转任务中心页（原 showTaskPanel） */
        void openPanel();
    }

    private final Host host;

    private View taskBar;
    private TextView taskText, taskAction;
    private ProgressBar taskProgress;
    /** 已结束任务计数：变化时才刷新窗格，避免每次进度回调都重列目录 */
    private int taskDoneSeen;

    /** 任务条自动收起：全部结束后停留 8 秒，让用户看得见结果 */
    private final Runnable hideTaskBar = new Runnable() {
        public void run() {
            if (taskBar != null && !FileTaskEngine.get().isBusy()) {
                taskBar.setVisibility(View.GONE);
            }
        }
    };

    private final FileTaskEngine.Listener taskListener = new FileTaskEngine.Listener() {
        public void onTasksChanged() {
            // v1.98.0：有活动任务就进前台服务 —— 用户切到别的应用后进程会被冻结/回收，
            // 大文件复制、压缩会在后台静默失败；前台服务同时给通知栏进度（可取消）。
            // start() 内部有 running 判断，进度回调再频繁也只启动一次。
            if (FileTaskEngine.get().hasActive()) TaskService.start(host.ctx());
            host.ui().post(new Runnable() {
                public void run() { renderTaskBar(); }
            });
        }
    };

    public TaskUiController(Host host) {
        this.host = host;
    }

    /** 装配任务条（原 setupTaskBar），onCreate 里调一次 */
    public void setup() {
        taskBar = host.find(R.id.task_bar);
        if (taskBar == null) return;
        taskText = (TextView) host.find(R.id.task_text);
        taskAction = (TextView) host.find(R.id.task_action);
        taskProgress = (ProgressBar) host.find(R.id.task_progress);
        UiDecor.paintPill(taskAction, host.ctx(), UiDecor.KIND_SOFT);
        taskAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // v1.77.1：这里原来先判断 isBusy()，而 isBusy() 在「正在拷贝」时返回 false
                // （worker 已把任务从 pending 取走），于是点「取消」反而打开了任务面板。
                // 现在用 hasActive()，并且一点就取消，不再弹二次确认。
                if (FileTaskEngine.get().hasActive()) {
                    FileTaskEngine.get().cancelAll();
                    renderTaskBar();                 // 立刻把「取消中」画出来
                } else {
                    host.openPanel();
                }
            }
        });
        host.find(R.id.task_body).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.openPanel(); }
        });
        // root 区（/data 等）File API 无权限时的兜底，注入给引擎，引擎本身保持纯 Java
        FileTaskEngine.get().setRootFallback(new FileTaskEngine.RootFallback() {
            public boolean copy(File src, File dstDir) { return RootIO.copyInto(src, dstDir); }

            public boolean move(File src, File dstDir) { return RootIO.moveInto(src, dstDir); }

            public boolean delete(File f) { return RootIO.delete(f); }
        });
    }

    /** 回前台：接上进度回调并补一次刷新（原 onResume 里的两行） */
    public void attach() {
        FileTaskEngine.get().setListener(taskListener);
        renderTaskBar();
    }

    /** 离开前台：不持有 Activity 引用，避免任务线程把已销毁的界面拖住 */
    public void detach() {
        FileTaskEngine.get().setListener(null);
        host.ui().removeCallbacks(hideTaskBar);
    }

    /** Activity 销毁：收掉队列与监听（原 onDestroy 里的两行） */
    public void destroy() {
        host.ui().removeCallbacksAndMessages(null);
        FileTaskEngine.get().setListener(null);
    }

    /** 任务条：一个活动任务显示「正在复制 3 项 · 42% · 12 MB/s」，全部结束显示结果 */
    void renderTaskBar() {
        if (taskBar == null) return;
        List<FileTask> list = FileTaskEngine.get().tasks();
        FileTask running = null, last = null;
        int active = 0, done = 0;
        for (FileTask t : list) {
            if (t.cancelable()) {
                active++;
                if (running == null || t.state == FileTask.RUNNING) running = t;
            } else {
                done++;
                last = t;
            }
        }
        if (done != taskDoneSeen) {
            taskDoneSeen = done;
            if (active == 0) host.refreshAll();     // 收尾刷新：新文件立刻出现在对面窗格
        }
        if (active == 0 && last == null) {
            taskBar.setVisibility(View.GONE);
            return;
        }
        taskBar.setVisibility(View.VISIBLE);
        if (active > 0) {
            host.ui().removeCallbacks(hideTaskBar);
            int pct = running.percent();
            taskProgress.setProgress(pct);
            if (running.cancelRequested) {
                // 取消标志每 128KB 才检查一次，写大块数据时还会晚几百毫秒；
                // 这段时间必须给出反馈，否则按钮看起来「点了没反应」，用户会连点。
                taskText.setText("正在取消 " + running.title + "…");
                taskAction.setText("取消中");
                taskAction.setEnabled(false);
                taskAction.setAlpha(0.45f);
            } else {
                StringBuilder sb = new StringBuilder("正在").append(running.title)
                        .append(" · ").append(pct).append('%');
                long sp = running.speed(System.currentTimeMillis());
                if (sp > 0) sb.append(" · ").append(FileOps.formatSize(sp)).append("/s");
                if (active > 1) sb.append(" · 另 ").append(active - 1).append(" 项排队");
                taskText.setText(sb.toString());
                taskAction.setText("取消");
                taskAction.setEnabled(true);
                taskAction.setAlpha(1f);
            }
        } else {
            taskProgress.setProgress(100);
            taskText.setText(resultLine(last));
            taskAction.setText("查看");
            taskAction.setEnabled(true);
            taskAction.setAlpha(1f);
            host.ui().removeCallbacks(hideTaskBar);
            host.ui().postDelayed(hideTaskBar, 8000);
        }
    }

    private String resultLine(FileTask t) {
        switch (t.state) {
            case FileTask.DONE:
                return "✓ " + t.title + "完成 · " + FileOps.formatSize(t.totalBytes);
            case FileTask.PARTIAL:
                return "⚠ " + t.title + "，失败 " + t.failedFiles + " 项";
            case FileTask.FAILED:
                return "✗ " + t.title + "失败";
            case FileTask.CANCELLED:
                return "已取消 " + t.title;
            default:
                return t.title;
        }
    }

    /**
     * v1.99.3：任务账本（{@code SharedPreferences} 的 {@code running_task_json}）。
     *
     * <p>启动时键还在，就说明上个进程没跑完 —— 被系统清了，或者用户强退了。
     * 先 toast 一句（用户可能根本没注意应用被重启过），再弹详情问要不要重试。
     * 用户点「忽略」只移除这一个键，不碰同一份偏好里的其它设置，更不会删任何文件。</p>
     */
    public void setupJournal() {
        final TaskStore store = TaskStore.get(host.ctx());
        // 先读再设：setJournalStore 不写盘，顺序本身不致命，但读在前总是对的
        final List<TaskJournal.Entry> pending = store.load();
        FileTaskEngine.get().setJournalStore(store);
        if (pending.isEmpty()) return;
        host.toast("上次有任务被中断");
        host.post(new Runnable() {
            public void run() { showInterrupted(pending, store); }
        });
    }

    private void showInterrupted(final List<TaskJournal.Entry> pending, final TaskStore store) {
        if (!host.alive()) return;
        int items = 0;
        for (TaskJournal.Entry e : pending) items += e.srcs.size();
        StringBuilder sb = new StringBuilder();
        sb.append(pending.size()).append(" 个任务（共 ").append(items)
                .append(" 项文件）上次没有做完。\n\n可能是被系统清理，或应用被强制结束。\n\n");
        sb.append("目标目录：").append(pending.get(0).dstDir.getPath());
        if (pending.size() > 1) sb.append(" 等");
        new HfDialogBuilder(host.ctx())
                .setTitle("上次有任务没做完")
                .setMessage(sb.toString())
                .setPositiveButton("重试", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int n = 0;
                        for (TaskJournal.Entry e : pending) {
                            if (FileTaskEngine.get().submit(e.kind, e.srcs, e.dstDir) != null) n++;
                        }
                        TaskService.start(host.ctx());
                        host.toast("已重新提交 " + n + " 个任务");
                    }
                })
                .setNegativeButton("忽略", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        store.clear();      // 只清 running_task_json 这一个键
                    }
                })
                .show();
    }

}
