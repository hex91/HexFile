package com.hex91.file.sched;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用升级后重排闹钟（v2.17.0，F-23）。
 *
 * <p><b>为什么单独一个 receiver</b>：{@code BOOT_COMPLETED} 是系统广播，
 * 接收它的组件必须是 {@code exported="true"}，否则收不到。而「到点执行」那个 receiver
 * 一旦也是 exported，任何应用都能给它发一个 {@code ACTION_TICK}，反复触发用户的
 * 「清空目录」—— 那是实打实的数据丢失。</p>
 *
 * <p>所以拆开：这个只做「按表重排闹钟」，被恶意触发最多是把闹钟重设一次（等价操作）；
 * 真正动文件的那个 {@link ScheduleReceiver} 保持 {@code exported="false"}，
 * 只有本应用自己的 PendingIntent 能唤到它。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        // 部分国产 ROM 的开机广播用 QUICKBOOT_POWERON（非标准但常见）
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        try {
            ScheduleStore.scheduleNext(ctx);
        } catch (Throwable t) {
            // 自启动被系统/ROM 拦下时不重排就是了，下次打开应用还会再排一次
            Log.w("HexFile", "开机重排定时任务失败: " + t);
        }
    }
}
