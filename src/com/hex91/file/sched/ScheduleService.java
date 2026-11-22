package com.hex91.file.sched;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.hex91.file.R;

import java.util.List;

/**
 * 定时任务的前台服务（v2.113.0，R-41）。
 *
 * <p><b>为什么必须换掉 goAsync</b>：广播的异步预算大约 10 秒。清空一个几万文件的目录、
 * 压缩几百 MB 的目录都会超时，超时后进程随时可能被系统杀掉 —— 目录清了一半、
 * zip 留下半截，而用户看到的是「什么都没发生」。{@code goAsync()} 只是把 10 秒的
 * ANR 变成了「随时被杀」，并没有真的延长生命周期。</p>
 *
 * <p><b>做法</b>：receiver 只负责 {@code startForegroundService}，执行放进本服务。
 * 服务一创建就 {@code startForeground}（满足 5 秒规则），然后在工作线程里跑
 * {@link ScheduleRunner}，结束时写结果通知并停掉自己。</p>
 *
 * <p><b>被中断要能看出来</b>：开始前置一个「正在跑」标志，正常结束清掉。
 * 下次启动时若发现标志还在，说明上次是被杀掉的，写一条 {@code interrupted} 记录，
 * 由列表页提示「上次没跑完」——而不是让用户以为任务成功执行了 0 项。</p>
 */
public class ScheduleService extends Service {

    private static final String TAG = "HexFile";
    private static final String CHANNEL = "hex_schedule";
    /** 前台常驻通知：只在执行期间存在，结束就撤 */
    private static final int NOTI_ID = 7302;
    /**
     * 结果通知用另一个 id：结束时会 {@code stopForeground(REMOVE)}，
     * 那条操作会连带删掉同 id 的通知 —— 结果通知若复用 7302，刚发出去就被自己抹掉，
     * 用户永远只看到「正在执行」然后什么都没留下。
     */
    private static final int RESULT_ID = 7303;

    /** 前台状态是否落地。没落地就不该再往通知栏写 */
    private volatile boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        // 5 秒规则的落点：先落地前台状态，再开线程
        try {
            startForeground(NOTI_ID, buildProgress());
            foreground = true;
        } catch (Throwable t) {
            // 通知被禁用 / 后台限制：任务本身在普通线程里也能跑完，只是没有前台保护。
            // 绝不因为「通知发不出去」就把这次定时任务取消 —— 那才是真正的静默失败。
            Log.w(TAG, "ScheduleService: startForeground failed, running unprotected", t);
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                work();
            }
        }, "schedule-run");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 真正的活在 onCreate 里已经开工。这里只回一个「别自动重启」：
        // START_STICKY 会在被杀后重跑，而重跑一次「清空目录」并不是用户想要的。
        return START_NOT_STICKY;
    }

    private void work() {
        Context ctx = getApplicationContext();
        boolean interrupted = ScheduleRunState.begin(ctx);
        if (interrupted) Log.w(TAG, "Previous scheduled run was interrupted");

        List<ScheduleItem> list = null;
        int ran = 0;
        int failed = 0;
        StringBuilder lines = new StringBuilder();
        try {
            // R-42：服务执行路径同样要拿到真实卷根，否则「保存时拦住了、到点又放行」
            VolumeRoots.of(ctx);
            list = ScheduleStore.load(ctx);
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.size(); i++) {
                ScheduleItem it = list.get(i);
                if (it == null || !it.enabled) continue;
                long due = ScheduleRule.prevRun(it.rule, now);
                // due > lastRun 才执行：计划点晚于上次执行，说明这一轮还没跑过。
                // 设备睡了三天醒来，只补跑最近一次，不会连跑三次（那才是灾难）。
                if (due <= 0 || due <= it.lastRun) continue;
                ScheduleRunner.Result r = ScheduleRunner.run(it, now, null);
                it.lastRun = now;
                it.lastResult = r.ok()
                        ? ctx.getString(R.string.sched_result_ok, r.done)
                        : ctx.getString(R.string.sched_result_err, SchedText.errorText(ctx, r));
                ran++;
                if (!r.ok()) failed++;
                if (lines.length() > 0) lines.append('\n');
                lines.append(ctx.getString(R.string.sched_noti_line,
                        ctx.getString(ScheduleReceiver.actionLabel(it.action)), it.lastResult));
            }
            // 不管跑没跑都要重排：这次没到点可能是时间刚被改过，不重排就再也不会响
            ScheduleStore.saveAndReschedule(ctx, list);
        } catch (Throwable t) {
            // 异常也要把「正在跑」清掉，否则下一次启动会永远显示「上次被中断」
            Log.w(TAG, "Scheduled run failed: " + t);
        } finally {
            ScheduleRunState.end(ctx);
        }

        if (ran > 0) notifyResult(ran, failed, lines.toString());
        stopSelf();
    }

    private Notification buildProgress() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null
                && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.sched_noti_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_file)
                .setContentTitle(getString(R.string.sched_noti_channel))
                .setContentText(getString(R.string.sched_svc_running))
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF2BE0C8);
        return b.build();
    }

    /**
     * 执行结果通知。
     *
     * <p>不设点击跳转：{@code sched} 包不能引用 {@code ui.ScheduleActivity}（依赖方向门），
     * 而隐式 Intent 启动 Activity 又多一条清单 filter 与一个「看不见的入口」。
     * 结果同时写进了任务列表的「上次」，通知只负责提醒「刚才跑过了」。</p>
     */
    private void notifyResult(int ran, int failed, String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.sched_noti_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_file)
                .setContentTitle(getString(failed > 0
                        ? R.string.sched_noti_partial : R.string.sched_noti_title, ran))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        try {
            nm.notify(RESULT_ID, b.build());
        } catch (Throwable t) {
            // 未授予通知权限（Android 13+）时部分 ROM 会抛：结果已在列表里，不该因此崩
            Log.w(TAG, "Failed to post schedule notification: " + t);
        }
    }

    @Override
    public void onDestroy() {
        if (foreground) {
            try {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
            } catch (Throwable t) {
                Log.w(TAG, "ScheduleService: stopForeground failed: " + t);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
