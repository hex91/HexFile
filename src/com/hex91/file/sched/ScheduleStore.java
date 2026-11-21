package com.hex91.file.sched;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;

/**
 * 定时任务的持久化与闹钟排程（v2.17.0，F-23）。
 *
 * <p>存 {@code SharedPreferences} 而不是文件：最多十几条记录，读写都在主线程上，
 * 用文件还得自己处理「写一半断电」。文本格式交给 {@link ScheduleCodec}，这里只管存取与闹钟。</p>
 *
 * <p><b>为什么只注册一个闹钟</b>：每条任务一个 PendingIntent 要管 requestCode、
 * 要处理「删任务时撤回哪个」，还容易出现改完时间忘了重排的幽灵闹钟。现在永远只为
 * 「最早的下一次」注册一个闹钟，响过之后按最新状态重算 —— 状态只有一处，排错也只有一处。</p>
 *
 * <p><b>为什么用 {@code setAndAllowWhileIdle} 而不是精确闹钟</b>：精确闹钟（{@code setExact*}）
 * 在 Android 12+ 需要 {@code SCHEDULE_EXACT_ALARM} 权限，用户得去系统设置里单独授予，
 * 而这里要做的「每天清一次缓存」并不需要精确到秒。{@code setAndAllowWhileIdle} 在 Doze
 * 下仍会触发，代价是最多延后几分钟 —— 这个代价换掉一个权限弹窗是值得的。</p>
 */
public final class ScheduleStore {

    /** 闹钟广播（显式 Intent 投递给 {@link ScheduleReceiver}） */
    public static final String ACTION_TICK = "com.hex91.file.action.SCHEDULE_TICK";

    private static final String PREF = "hf_schedule";
    private static final String KEY = "items";
    private static final int REQ_TICK = 1001;

    private ScheduleStore() {}

    public static List<ScheduleItem> load(Context c) {
        if (c == null) return new java.util.ArrayList<ScheduleItem>();
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return ScheduleCodec.decode(sp.getString(KEY, ""));
    }

    /** 只写不排程（内部用）；改任务一律走 {@link #saveAndReschedule} */
    public static void save(Context c, List<ScheduleItem> list) {
        if (c == null) return;
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, ScheduleCodec.encode(list)).apply();
    }

    /** 保存后立刻按新表重排下一次闹钟 —— 增删改都只调这一个，不会漏掉重排 */
    public static void saveAndReschedule(Context c, List<ScheduleItem> list) {
        save(c, list);
        scheduleNext(c);
    }

    /**
     * 按当前任务表注册「最早的下一次」；一条启用的任务都没有时撤掉闹钟。
     *
     * <p>每次调用都先算出最早时刻再注册，重复调用是安全的（同一个 PendingIntent，
     * {@code FLAG_UPDATE_CURRENT} 会把旧的替换掉），所以开机、改表、执行完都可以放心再调一次。</p>
     */
    public static void scheduleNext(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tick(c);
        List<ScheduleItem> list = load(c);
        long now = System.currentTimeMillis();
        long best = -1;
        for (int i = 0; i < list.size(); i++) {
            ScheduleItem it = list.get(i);
            if (it == null || !it.enabled) continue;
            long t = ScheduleRule.nextRun(it.rule, now);
            if (t <= 0) continue;                       // 规则坏了：不排，等用户改
            if (best < 0 || t < best) best = t;
        }
        if (best < 0) {
            am.cancel(pi);
            return;
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi);
        } catch (Throwable t) {
            // 个别 ROM 在「自启动被禁止」时抛 SecurityException：任务仍在表里，
            // 下次打开应用会重排，这里只记一笔，别让设置页崩掉
            Log.w("HexFile", "Failed to register schedule alarm: " + t);
        }
    }

    /** 撤掉已注册的闹钟（用户清空全部任务时用） */
    public static void cancel(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(tick(c));
    }

    private static PendingIntent tick(Context c) {
        Intent it = new Intent(c, ScheduleReceiver.class).setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_TICK, it, flags);
    }
}
