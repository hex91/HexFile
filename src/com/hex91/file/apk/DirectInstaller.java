package com.hex91.file.apk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hex91.file.utils.LogRing;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v2.121.0：单 APK 的 PackageInstaller 会话安装（无特权时的自建通道）。
 *
 * <p><b>它解决什么</b>：原入口发 {@code ACTION_INSTALL_PACKAGE}，由 ROM 的安装器页面接手，
 * 那一页在国产 ROM 上常带推荐、广告和「风险提醒」。改走本类的会话后，进度与提示都在
 * HexFile 自己的界面里，ROM 安装器的推广页不再出现。</p>
 *
 * <p><b>它不解决什么</b>：Android 8+ 对第三方会话的 {@code commit} 仍要求系统确认，
 * 签名/版本/兼容性校验也始终由 {@code PackageManagerService} 执行。因此这条通道
 * 「无广告」但「不静默」。真正静默的那条在 {@link AppInstaller} 的特权分支里。</p>
 *
 * <p><b>与 SplitInstaller 的分工</b>：{@link SplitInstaller} 处理 zip 容器
 * （apks/xapk/apkm，一次写 base + N 个 split），本类处理单个 apk 文件。两者都往
 * {@code InstallResultReceiver} 送结果，靠 action 区分；不合并是因为解包/OBB 这些
 * 状态对单文件毫无意义，硬合进去只会让两条路径都变复杂。</p>
 *
 * <p><b>层次</b>：本类只返回错误码与系统原文，不产任何用户文案；通知与文案映射在 ui 层。</p>
 */
public final class DirectInstaller {

    private static final String TAG = "DirectInstaller";

    /** 结果广播 action（receiver 按 action 分发到本类或 SplitInstaller） */
    public static final String ACTION_RESULT = "com.hex91.file.action.DIRECT_INSTALL_RESULT";
    public static final String EXTRA_TOKEN = "token";

    private static final int BUF = 128 * 1024;
    private static final long WATCHDOG_MS = 5 * 60 * 1000L;

    /** 进度回调（主线程）与结果回调（主线程）。code 取 InstallCommand 常量 */
    public interface Listener {
        void onProgress(int percent);
        void onResult(int code, String detail);
    }

    /** 交给 ui 层的结果：是否需要它补通知（界面已销毁时为 false） */
    public static final class Result {
        public int code;
        public String detail;
        public boolean toUi;

        Result(int code, String detail, boolean toUi) {
            this.code = code;
            this.detail = detail;
            this.toUi = toUi;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sBusy;
    private static volatile boolean sCancel;
    private static volatile Listener sListener;
    private static volatile String sToken;
    private static volatile PackageInstaller.Session sSession;

    private DirectInstaller() {}

    public static boolean busy() { return sBusy; }

    /**
     * 开始一次单文件安装（立即返回，后台线程写会话）。
     *
     * @param resultIntent 结果 Intent 模板，必须带显式 component
     */
    public static void install(Context ctx, File apk, Intent resultIntent, Listener l) {
        synchronized (DirectInstaller.class) {
            if (sBusy) {
                if (l != null) l.onResult(InstallCommand.ERR_BUSY, null);
                return;
            }
            sBusy = true;
        }
        final Context app = ctx.getApplicationContext();
        final String token = String.valueOf(System.currentTimeMillis());
        sToken = token;
        sCancel = false;
        sListener = l;
        final Intent template = new Intent(resultIntent);
        new Thread(new Runnable() {
            public void run() {
                try {
                    doInstall(app, apk, token, template);
                } catch (Throwable t) {
                    Log.e(TAG, "install failed", t);
                    finish(InstallCommand.classify(-1, t.getMessage()), brief(t));
                }
            }
        }, "direct-install").start();
    }

    /** 用户取消：只对尚未 commit 的写入阶段有效；已 commit 交给系统处理 */
    public static void cancel() {
        sCancel = true;
        abandonQuietly(sSession);
    }

    // ------------------------------------------------------------------ 主流程

    private static void doInstall(Context app, File apk, String token, Intent template)
            throws IOException {
        if (!apk.isFile()) {
            finish(InstallCommand.ERR_INVALID, apk.getAbsolutePath());
            return;
        }
        long size = apk.length();
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setSize(size);
        int sid = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(sid);
        sSession = s;
        try {
            InputStream in = new FileInputStream(apk);
            try {
                OutputStream os = s.openWrite("base-" + apk.getName(), 0, size);
                try {
                    byte[] buf = new byte[BUF];
                    long done = 0;
                    int got;
                    while ((got = in.read(buf)) > 0) {
                        if (sCancel) throw new Cancelled();
                        os.write(buf, 0, got);
                        done += got;
                        progress((int) Math.min(95, size <= 0 ? 95 : done * 95 / size));
                    }
                } finally {
                    close(os);
                }
            } finally {
                close(in);
            }
            if (sCancel) throw new Cancelled();
            s.commit(buildPendingIntent(app, token, template).getIntentSender());
            progress(96);
            startWatchdog(token);
            sSession = null;   // 会话已封存，abandon 不再有意义
        } catch (Throwable t) {
            abandonQuietly(s);
            sSession = null;
            if (t instanceof Cancelled) finish(InstallCommand.ERR_CANCELLED, null);
            else if (t instanceof IOException) finish(InstallCommand.ERR_IO, brief(t));
            else finish(InstallCommand.classify(-1, t.getMessage()), brief(t));
        }
    }

    private static PendingIntent buildPendingIntent(Context app, String token, Intent template) {
        Intent i = new Intent(template);
        i.setAction(ACTION_RESULT);
        i.putExtra(EXTRA_TOKEN, token);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, token.hashCode(), i, flags);
    }

    /** 防「广播丢了就一直忙」：超时只解除忙标志，不假报成功 */
    private static void startWatchdog(final String token) {
        MAIN.postDelayed(new Runnable() {
            public void run() {
                if (token.equals(sToken) && sBusy) {
                    LogRing.add(TAG, "install result broadcast timeout");
                    Result r = finish(InstallCommand.ERR_TIMEOUT, null);
                    if (r != null && !r.toUi) LogRing.add(TAG, "watchdog: no ui listener");
                }
            }
        }, WATCHDOG_MS);
    }

    // ------------------------------------------------------------------ 结果

    /**
     * 接收器把广播交回来。返回 {@link Result} 供 ui 层在界面已销毁时补通知；
     * 在场监听器存在时 {@code toUi=true}，ui 层不应再重复提示。
     */
    public static Result deliver(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (sToken != null && token != null && !sToken.equals(token)) {
            LogRing.add(TAG, "stale result ignored: " + token);
            return null;
        }
        int code = mapStatus(status, msg);
        return finish(code, msg);
    }

    /** 系统状态 → 逻辑码；不产文案，系统原文作为 detail 留给 ui 层展开 */
    private static int mapStatus(int status, String msg) {
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                return InstallCommand.OK;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return InstallCommand.ERR_ABORTED;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return InstallCommand.ERR_BLOCKED;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return InstallCommand.ERR_CONFLICT;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return InstallCommand.ERR_INVALID;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return InstallCommand.ERR_STORAGE;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return InstallCommand.ERR_INCOMPATIBLE;
            default:
                return InstallCommand.classify(-1, msg);
        }
    }

    private static Result finish(int code, String detail) {
        Listener l = sListener;
        sListener = null;
        sBusy = false;
        sSession = null;
        boolean toUi = l != null;
        if (l != null) deliverTo(l, code, detail);
        return new Result(code, detail, toUi);
    }

    private static void deliverTo(final Listener l, final int code, final String detail) {
        MAIN.post(new Runnable() {
            public void run() { l.onResult(code, detail); }
        });
    }

    private static void progress(final int pct) {
        final Listener l = sListener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            public void run() { l.onProgress(pct); }
        });
    }

    // ------------------------------------------------------------------ 工具

    private static void abandonQuietly(PackageInstaller.Session s) {
        if (s == null) return;
        try {
            s.abandon();
        } catch (Throwable t) {
            LogRing.add(TAG, "abandon: " + t);
        }
    }

    private static String brief(Throwable t) {
        if (t == null) return null;
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (Throwable t) {
            LogRing.add(TAG, "close: " + t);
        }
    }

    /** 写会话中途取消：统一异常，免得每层判返回值 */
    private static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }
}
