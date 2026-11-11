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
final class Bg {

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

    /** 丢到后台跑；异常只记日志，不会影响下一条任务 */
    static void io(final Runnable r) {
        IO.execute(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.e(TAG, "bg task failed: " + r.getClass().getName(), t);
                }
            }
        });
    }

    /** 回主线程（已在主线程则直接执行，避免多绕一圈消息队列、也便于单测） */
    static void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else UI.post(r);
    }

    /** 当前是否在主线程（少数需要提前分支的场景用） */
    static boolean onUi() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
