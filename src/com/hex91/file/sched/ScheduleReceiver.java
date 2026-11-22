package com.hex91.file.sched;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.hex91.file.R;

import java.util.List;

/**
 * 定时任务的触发端（v2.17.0，F-23）。
 *
 * <p>只处理三件事：</p>
 * <ol>
 *   <li>闹钟到点 → 执行「最近一个计划点还没跑过」的任务，然后重排下一次；</li>
 *   <li>开机 / 应用被覆盖安装 → 由 {@link BootReceiver} 处理（那个 receiver 必须是
 *       exported，所以不能兼做本类的工作）；</li>
 *   <li>别的 action → 忽略。这个 receiver 在清单里是 {@code exported="false"}，
 *       外部应用发不过来，开机广播由系统投递。</li>
 * </ol>
 *
 * <p><b>为什么用 goAsync</b>：清空一个几万文件的目录、压缩一个几百 MB 的目录都会超过
 * 广播的 10 秒上限。{@code goAsync()} 把这次广播的生命周期延长到 {@code finish()} 调用，
 * 期间系统不会因为「receiver 没返回」而 ANR 或强杀进程。</p>
 */
public class ScheduleReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "hex_schedule";
    private static final int NOTI_ID = 7301;

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;

        // 开机/升级由 BootReceiver 处理：它必须 exported=true，而这里执行的是
        // 会删文件的动作，绝不能暴露给其他应用
        if (!ScheduleStore.ACTION_TICK.equals(a)) return;

        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    runDue(app);
                } catch (Throwable t) {
                    Log.w("HexFile", "Scheduled task threw: " + t);
                } finally {
                    pr.finish();
                }
            }
        }, "schedule-tick").start();
    }

    /** 执行所有「最近一个计划点还没跑过」的任务 */
    private static void runDue(Context ctx) {
        // R-42：广播执行路径也要拿到真实卷根，否则「保存时拦住了、到点又放行」
        VolumeRoots.of(ctx);
        List<ScheduleItem> list = ScheduleStore.load(ctx);
        long now = System.currentTimeMillis();
        int ran = 0, failed = 0;
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            ScheduleItem it = list.get(i);
            if (it == null || !it.enabled) continue;
            long due = ScheduleRule.prevRun(it.rule, now);
            // due > it.lastRun 才执行：计划点晚于上次执行，说明这一轮还没跑过。
            // 设备睡了三天醒来，只会补跑最近一次，不会连跑三次（那才是灾难）。
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
                    ctx.getString(actionLabel(it.action)), it.lastResult));
        }
        // 不管跑没跑都要重排：这次没到点可能是时间刚被改过，不重排就再也不会响
        ScheduleStore.saveAndReschedule(ctx, list);
        if (ran > 0) notify(ctx, ran, failed, lines.toString());
    }

    /** 动作名（列表页与通知共用，一处定义免得两边文案走偏） */
    public static int actionLabel(String action) {
        if (ScheduleItem.ACTION_ZIP.equals(action)) return R.string.sched_action_zip;
        if (ScheduleItem.ACTION_EXPIRE.equals(action)) return R.string.sched_action_expire;
        return R.string.sched_action_clean;
    }

    /**
     * 执行结果通知。
     *
     * <p>不设点击跳转：{@code sched} 包不能引用 {@code ui.ScheduleActivity}（依赖方向门），
     * 而隐式 Intent 启动 Activity 又多一条清单 filter 与一个「看不见的入口」。
     * 结果同时写进了任务列表的「上次」，通知只负责提醒「刚才跑过了」。</p>
     */
    private static void notify(Context ctx, int ran, int failed, String text) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.sched_noti_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_file)
                .setContentTitle(ctx.getString(failed > 0
                        ? R.string.sched_noti_partial : R.string.sched_noti_title, ran))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        try {
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable t) {
            // 未授予通知权限（Android 13+）时部分 ROM 会抛：结果已在列表里，不该因此崩
            Log.w("HexFile", "Failed to post schedule notification: " + t);
        }
    }
}
