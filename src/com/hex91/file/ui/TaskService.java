package com.hex91.file.ui;

import com.hex91.file.*;
import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.FileTask;
import com.hex91.file.fs.FileTaskEngine;
import com.hex91.file.MainActivity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * v1.98.0：文件任务的前台服务（F-07）。
 *
 * <p><b>不做的后果</b>：{@link FileTaskEngine} 跑在进程内的普通线程上。用户复制几个 G 的文件、
 * 或者压缩一个大目录时切到微信，几秒后进程进入 cached 状态，系统随时可以冻结甚至杀掉它 ——
 * 任务静默失败，回来后列表里只看到没拷完的 {@code .part}，没有任何解释。</p>
 *
 * <p><b>做法</b>：任务一旦被提交，就 {@code startForegroundService}，用一个常驻通知承载进度：
 * 通知栏能看到百分比和速度、点一下回到主界面、附带「取消」按钮。任务全部结束时自己
 * {@code stopForeground + stopSelf}，不留常驻通知。</p>
 *
 * <p><b>几个必须守住的点</b>：</p>
 * <ul>
 *   <li>{@code startForegroundService} 之后必须在 <b>5 秒内</b> {@code startForeground}，否则
 *       {@code ForegroundServiceDidNotStartInTimeException} 直接崩 —— 所以 onCreate 里第一件事就是
 *       建通道 + startForeground，连引擎监听都排在后面；</li>
 *   <li>通知更新走 {@code NotificationManager.notify(同一个 id)}，不会重复弹，也不会打断前台状态；</li>
 *   <li>服务只持有 {@link FileTaskEngine} 的弱引用语义（引擎是进程级单例），不持有 Activity；</li>
 *   <li>启动失败（后台限制、通知被禁）只记日志并停掉自己，绝不让「任务跑得好好的却因为通知崩掉」。</li>
 * </ul>
 */
public class TaskService extends Service {

    private static final String TAG = "HexFile";
    private static final String CHANNEL = "hex_task";
    private static final int NOTI_ID = 0x4802;
    static final String ACTION_CANCEL = "com.hex91.file.action.TASK_CANCEL";
    /** 通知栏「取消」要取消哪个任务（R-05） */
    static final String EXTRA_TASK_ID = "task_id";

    /** 是否已在运行（避免每次进度回调都 startForegroundService 一次） */
    static volatile boolean running = false;

    /**
     * v1.99.2（R-04）：失败后的退避截止时间（{@code SystemClock.uptimeMillis()}）。
     *
     * <p>startForeground 失败后服务会立刻退出，而引擎每 120ms 就回调一次进度、
     * 每次都重新 {@code startForegroundService} —— 不挡的话就是每 120ms 起一次服务再崩一次。</p>
     */
    static volatile long retryAfter = 0L;

    /** startForeground 是否成功落地；没落地就不该再往通知栏写（R-04） */
    private boolean foreground;

    private Handler ui;

    /** 引擎回调可能在 worker 线程 → 统一回主线程刷通知 */
    private final FileTaskEngine.Listener watcher = new FileTaskEngine.Listener() {
        public void onTasksChanged() {
            Handler h = ui;
            if (h != null) h.post(refresh);
        }
    };

    private final Runnable refresh = new Runnable() {
        public void run() {
            try {
                if (!foreground) return;      // 前台状态没落地（R-04），别再碰通知栏
                FileTaskEngine e = FileTaskEngine.get();
                if (!e.hasActive()) {
                    finish();
                    return;
                }
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTI_ID, build());
            } catch (Throwable t) {
                Log.w(TAG, "任务通知刷新失败", t);
            }
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        // 5 秒规则的落点：先落地前台状态，再订阅引擎
        try {
            startForeground(NOTI_ID, build());
            foreground = true;
        } catch (Throwable t) {
            // v1.99.2（R-04）：原来这里只 log —— 那等于把 5 秒倒计时留在原地跑。
            // startForegroundService 之后没落地前台状态，API 26+ 会在 5 秒后抛
            // ForegroundServiceDidNotStartInTimeException 把整个进程带走（任务也跟着死）；
            // 所以必须立刻退出这个服务：任务本身在引擎线程里跑，通知没有就没有。
            Log.w(TAG, "startForeground 失败，本任务改在后台线程执行（无通知）", t);
            retryAfter = android.os.SystemClock.uptimeMillis() + 60_000L;
            stopSelf();
            return;             // 不订阅引擎、不置 running —— 这个实例已经没用了
        }
        FileTaskEngine.get().addListener(watcher);
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            // v1.99.2（R-05）：通知栏那条「取消」对着的是通知上正在跑的任务，
            // 只该取消它。原来调 cancelAll()，用户排在后面的复制任务会被一起干掉 ——
            // 通知上根本没有任何「全部取消」的暗示。
            int id = intent.getIntExtra(EXTRA_TASK_ID, -1);
            FileTaskEngine e = FileTaskEngine.get();
            if (id >= 0) e.cancel(id);
            else e.cancelAll();               // 老版本发来的 PendingIntent（无 extra）保持旧行为
            ui.post(refresh);
            return START_NOT_STICKY;
        }
        ui.post(refresh);
        // 不要 START_STICKY：进程被杀后重启一个空任务服务毫无意义，还会留一条僵尸通知
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        FileTaskEngine.get().removeListener(watcher);
        running = false;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 任务开始前调用（主界面提交任务时）。
     *
     * <p>已在运行则直接返回；{@code startForegroundService} 抛异常（后台限制）时静默降级 ——
     * 任务本身仍在跑，只是没有通知栏进度。</p>
     */
    public static void start(Context c) {
        if (running || c == null) return;
        if (android.os.SystemClock.uptimeMillis() < retryAfter) return;   // 见 retryAfter
        Context app = c.getApplicationContext();
        try {
            Intent i = new Intent(app, TaskService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            retryAfter = android.os.SystemClock.uptimeMillis() + 60_000L;
            Log.w(TAG, "任务通知服务启动失败（任务仍在后台执行）", t);
        }
    }

    /** 任务结束：撤掉通知并退出前台状态 */
    private void finish() {
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ 通知

    private Notification.Builder builder() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "文件任务",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setDescription("复制 / 移动 / 压缩等文件任务的进度");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b;
    }

    private Notification build() {
        List<FileTask> tasks = FileTaskEngine.get().tasks();
        FileTask run = null;
        int queued = 0;
        for (FileTask t : tasks) {
            if (t.state == FileTask.QUEUED) queued++;
            else if (t.state == FileTask.RUNNING && run == null) run = t;
        }

        Notification.Builder b = builder()
                .setSmallIcon(R.drawable.ic_file)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);

        if (run != null) {
            int pct = run.percent();
            StringBuilder text = new StringBuilder();
            if (run.totalBytes > 0) {
                text.append(FileOps.formatSize(run.doneBytes)).append(" / ")
                        .append(FileOps.formatSize(run.totalBytes));
            }
            if (run.totalFiles > 0) {
                if (text.length() > 0) text.append(" · ");
                text.append(run.doneFiles).append('/').append(run.totalFiles).append(" 个文件");
            }
            if (queued > 0) text.append(" · 还有 ").append(queued).append(" 项排队");
            b.setContentTitle(run.title + "  " + pct + "%");
            b.setContentText(text.length() == 0 ? run.title : text.toString());
            // totalBytes 为 0（全是空文件/空目录）时用不确定进度，避免卡在 0%
            b.setProgress(100, pct, run.totalBytes <= 0 && run.totalFiles <= 0);
        } else {
            b.setContentTitle("准备文件任务…");
            b.setContentText(queued > 0 ? "排队 " + queued + " 项" : "正在统计文件");
            b.setProgress(0, 0, true);
        }

        Intent cancel = new Intent(this, TaskService.class).setAction(ACTION_CANCEL);
        if (run != null) cancel.putExtra(EXTRA_TASK_ID, run.id);
        // requestCode 固定 + FLAG_UPDATE_CURRENT：同一个 PendingIntent 的 extra 每次都刷新，
        // 于是「取消」永远指向通知上此刻显示的那条任务
        PendingIntent cp = PendingIntent.getService(this, 2, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "取消", cp).build());
        return b.build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
