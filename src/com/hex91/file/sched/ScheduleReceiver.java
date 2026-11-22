package com.hex91.file.sched;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.hex91.file.R;

/**
 * 定时任务的触发端（v2.17.0，F-23；v2.113.0 改前台服务，R-41）。
 *
 * <p>只处理三件事：</p>
 * <ol>
 *   <li>闹钟到点 → 启动 {@link ScheduleService}，由服务去执行并重排下一次；</li>
 *   <li>开机 / 应用被覆盖安装 → 由 {@link BootReceiver} 处理（那个 receiver 必须是
 *       exported，所以不能兼做本类的工作）；</li>
 *   <li>别的 action → 忽略。这个 receiver 在清单里是 {@code exported="false"}，
 *       外部应用发不过来，开机广播由系统投递。</li>
 * </ol>
 *
 * <p><b>为什么不再直接执行</b>：原来这里是 {@code goAsync()} + 裸线程。广播的异步预算
 * 约 10 秒，而清空几万文件、压缩几百 MB 远超这个量级；超时后进程随时被杀，
 * 结果是「清了一半」或「zip 留半截」，用户还以为任务没跑。
 * {@code goAsync()} 并没有延长生命周期，只是把 ANR 换成了静默消失。</p>
 *
 * <p>现在执行的活全部搬进前台服务：有常驻通知、锁屏也不中断，被杀还能在下次启动时
 * 看到「上次没跑完」。这个 receiver 只做一件事 —— 把闹钟变成一次服务启动。</p>
 */
public class ScheduleReceiver extends BroadcastReceiver {

    private static final String TAG = "HexFile";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;

        // 开机/升级由 BootReceiver 处理：它必须 exported=true，而这里执行的是
        // 会删文件的动作，绝不能暴露给其他应用
        if (!ScheduleStore.ACTION_TICK.equals(a)) return;

        Context app = ctx.getApplicationContext();
        try {
            Intent svc = new Intent(app, ScheduleService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                // 26+ 必须用 startForegroundService：服务自己会在 5 秒内 startForeground
                app.startForegroundService(svc);
            } else {
                app.startService(svc);
            }
        } catch (Throwable t) {
            // 后台限制导致起不来时只记日志。绝不退回到「在 receiver 里直接跑」——
            // 那正是这一版要修掉的那条会被 10 秒预算打断的路径
            Log.w(TAG, "Cannot start ScheduleService: " + t);
        }
    }

    /** 动作名（列表页与通知共用，一处定义免得两边文案走偏） */
    public static int actionLabel(String action) {
        if (ScheduleItem.ACTION_ZIP.equals(action)) return R.string.sched_action_zip;
        if (ScheduleItem.ACTION_EXPIRE.equals(action)) return R.string.sched_action_expire;
        return R.string.sched_action_clean;
    }
}
