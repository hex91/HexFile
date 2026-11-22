package com.hex91.file.sched;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 定时任务「这一轮跑到哪」的落盘标记（v2.113.0，R-41）。
 *
 * <p>只用两个布尔量，不记具体进度：{@code running} 表示「当前有一轮正在执行」，
 * {@code interrupted} 表示「上一轮没走到结尾」。前者在服务启动时被读到、在结束时被清掉，
 * 于是「启动时还是 true」就等价于「上次被系统杀了」。</p>
 *
 * <p>为什么不记进度、不续跑：清空目录、压缩目录这些动作不是幂等的，
 * 半途接着跑比重新跑一遍更危险（比如压缩已完成一半，续跑会得到一个内容和清单对不上的包）。
 * 这里只需要让用户<b>知道</b>上次没跑完，而不是假装它能接着跑。</p>
 */
public final class ScheduleRunState {

    private static final String PREFS = "sched_run";
    private static final String K_RUNNING = "running";
    private static final String K_INTERRUPTED = "interrupted";

    private ScheduleRunState() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 标记「开始执行」。
     *
     * @return 上次是否被中断（true 时同时把 interrupted 置上，等列表页来取）
     */
    public static boolean begin(Context c) {
        SharedPreferences p = sp(c);
        boolean dirty = p.getBoolean(K_RUNNING, false);
        SharedPreferences.Editor e = p.edit().putBoolean(K_RUNNING, true);
        // 只在真的脏了的时候写 interrupted：否则会把一条用户还没看到的提示冲掉
        if (dirty) e.putBoolean(K_INTERRUPTED, true);
        e.apply();
        return dirty;
    }

    /** 正常结束（或异常退出但线程还活着）：清掉「正在跑」 */
    public static void end(Context c) {
        sp(c).edit().putBoolean(K_RUNNING, false).apply();
    }

    /**
     * 取走「上次被中断」这条提示（取一次就清）。
     *
     * <p>由列表页在 onResume 调用；取走后不再重复提示，用户已经知道了。</p>
     */
    public static boolean takeInterrupted(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean(K_INTERRUPTED, false)) return false;
        p.edit().putBoolean(K_INTERRUPTED, false).apply();
        return true;
    }
}
