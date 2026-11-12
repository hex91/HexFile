package com.hex91.file;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.96.0：后台执行 / 主线程回调的唯一入口。
 *
 * <p>这个仓库里原来有 130 处 {@code new Thread(...)} + 128 处 {@code runOnUiThread(...)}，
 * 每个都是裸线程、没有线程池上限、没有异常兜底：一旦 Runnable 抛异常，线程默默死掉，
 * 界面上表现成「点了没反应」，日志里什么都没有（默认 UncaughtExceptionHandler 只打到
 * stderr，release 版本里基本看不到）。</p>
 *
 * <p>统一之后解决三件事：</p>
 * <ul>
 *   <li><b>有界</b>：固定 3 条 IO 线程，切目录、读缩略图、算大小不会把线程数堆到几十条；</li>
 *   <li><b>有底</b>：任何 Throwable 都写进 logcat（tag=HexFile），不再静默；</li>
 *   <li><b>可读</b>：{@link #io} / {@link #ui} 一眼看出这段代码跑在哪条线程。</li>
 * </ul>
 *
 * <p>注意：这里只做「线程调度」，不做生命周期绑定。后台任务回调到界面前，
 * 调用方自己要用代次（generation）或 isFinishing 判断界面是否还该更新——
 * 池里的线程是进程级的，Activity 销毁后任务仍会跑完。</p>
 */
public final class Bg {

    private static final String TAG = "HexFile";

    /** IO 线程数：目录读 + 缩略图 + 杂项，3 条足够；再多只会让 su 进程互相抢 */
    private static final int IO_THREADS = 3;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final ExecutorService IO = Executors.newFixedThreadPool(
            IO_THREADS, new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "hex-bg-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private Bg() {}

    /** 后台任务失败回调（在主线程执行） */
    public interface Fail {
        void onFail(Throwable t);
    }

    /**
     * 丢到后台跑；异常记日志。
     *
     * <p>v1.97.0：只记日志是不够的 —— 任务体里「算完 → 回主线程收尾」这种写法，
     * 一旦中途抛出，收尾那步不会执行，界面就停在加载态（v1.96.0 读目录就是这个坑：
     * loading 永远 true → 该窗格点击永久被拦）。需要收尾的调用方请用
     * {@link #io(Runnable, Fail)}，保证失败也有一条回主线程的路。</p>
     */
    public static void io(final Runnable r) {
        io(r, null);
    }

    public static void io(final Runnable r, final Fail onFail) {
        IO.execute(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.e(TAG, "bg task failed: " + r.getClass().getName(), t);
                    if (onFail != null) {
                        try {
                            ui(new Runnable() {
                                public void run() { onFail.onFail(t); }
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    /** 回主线程（已在主线程则直接执行，避免多绕一圈消息队列、也便于单测） */
    public static void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else UI.post(r);
    }

    /** 当前是否在主线程（少数需要提前分支的场景用） */
    public static boolean onUi() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
