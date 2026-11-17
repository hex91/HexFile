package com.hex91.file;

import android.app.Application;

import com.hex91.file.utils.CrashLog;
import com.hex91.file.utils.LogRing;

/**
 * v2.12.0（F-18）：进程入口。
 *
 * <p>这个类唯一的职责是<b>把崩溃处理器装到最早的时机</b>。挂成 Application 而不是在
 * MainActivity.onCreate 里装，是因为「启动即崩」这类问题（资源缺键、Application
 * 初始化顺序、机型相关的类加载失败）恰恰发生在 Activity 起来之前 —— 装在 Activity 里
 * 等于对最难排的一类崩溃完全失明。</p>
 *
 * <p>代价要说清：加 Application 子类改变了进程启动路径，任何初始化异常都会变成
 * 「一启动就闪退」。所以这里<b>只做一件事</b>，不加载配置、不建目录、不碰网络；
 * 崩溃处理器的内部实现也全部包了 try/catch（见 {@link CrashLog}）。</p>
 */
public class HfApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        LogRing.add("HfApp", "process start");
    }
}
