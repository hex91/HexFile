package com.hex91.file.apk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hex91.file.R;
import com.hex91.file.utils.LogRing;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v2.13.0（F-03）：分包安装（{@code .apks} / {@code .xapk} / {@code .apkm}）的落地实现。
 *
 * <p><b>为什么不能复用「安装 APK」那条老路</b>：现有安装是发
 * {@code ACTION_INSTALL_PACKAGE} 让系统安装器接手，它一次只认一个文件；分包包是
 * 一次装 base + N 个 split，只有 {@link PackageInstaller} 的会话能表达「这几份是同一个应用」。
 * 以前唯一的多次安装路径在 {@code BackupRestore} 里，靠 root 跑 {@code pm install-multiple}
 * —— 没 root 的机器等于装不了。</p>
 *
 * <p><b>层次</b>：包结构解析在 {@link SplitPackage}（纯 JDK，桌面可测）；这里只做
 * 「解包 → 开会话 → 写字节 → commit」与结果通报。结果接收器在 ui 层，本类不引用 ui，
 * 由调用方把结果 Intent 模板传进来 —— apk 层不需要知道「谁来收」，也不需要知道
 * 「点通知该跳到哪个页面」（用 {@code getLaunchIntentForPackage} 就够）。</p>
 *
 * <p><b>同一时刻只允许一个会话</b>：进度、取消、OBB 统计都走静态字段，两个安装并发会互相
 * 覆盖。重复调用直接拒绝并给一句话，比悄悄排队好。</p>
 */
public final class SplitInstaller {

    private static final String TAG = "SplitInstaller";

    /**
     * 结果广播的 action（接收器自己再按 {@link #EXTRA_TOKEN} 匹配）。
     * 结果本体用 PackageInstaller 自己的 extra（{@code EXTRA_STATUS}），不另塞布尔值 ——
     * 多一份「我理解的成败」就多一处可能与系统不一致的地方。
     */
    public static final String ACTION_RESULT = "com.hex91.file.action.SPLIT_INSTALL_RESULT";
    public static final String EXTRA_TOKEN = "token";

    private static final String CHANNEL = "install_result";
    private static final int NOTIF_ID = 0x5B11;
    private static final int BUF = 128 * 1024;
    /** 会话提交后等结果的兜底时长：超过就认为广播丢了，解除忙标志免得下次装不了 */
    private static final long WATCHDOG_MS = 5 * 60 * 1000L;

    /** 进度回调（主线程，可直接动 UI）；结果回调（主线程） */
    public interface Listener {
        void onProgress(int percent);
        void onResult(boolean ok, String msg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sBusy;
    private static volatile boolean sCancel;
    private static volatile Listener sListener;
    private static volatile String sToken;
    private static volatile PackageInstaller.Session sSession;
    private static int sObbOk;
    private static String sObbErr;
    private static String sPkgName;

    private SplitInstaller() {}

    public static boolean busy() { return sBusy; }

    /**
     * 开始一次分包安装（立即返回，后台线程干活）。
     *
     * @param resultIntent 结果 Intent 模板，必须带显式 component（本类只往里塞 extra）
     */
    public static void install(Context ctx, File pkg, Intent resultIntent, Listener l) {
        synchronized (SplitInstaller.class) {
            if (sBusy) {
                if (l != null) l.onResult(false, ctx.getString(R.string.split_busy));
                return;
            }
            sBusy = true;
        }
        final Context app = ctx.getApplicationContext();
        final String token = String.valueOf(System.currentTimeMillis());
        sToken = token;
        sCancel = false;
        sObbOk = 0;
        sObbErr = null;
        sPkgName = null;
        sListener = l;
        final Intent template = new Intent(resultIntent);
        new Thread(new Runnable() {
            public void run() {
                try {
                    doInstall(app, pkg, token, template);
                } catch (Throwable t) {
                    // 兜底：任何未预料异常都必须让界面收尾，否则进度条会一直转
                    finish(app, false, app.getString(R.string.split_fail, brief(t)));
                }
            }
        }, "split-install").start();
    }

    /** 用户点了取消：解包在各检查点生效；已开会话直接 abandon */
    public static void cancel() {
        sCancel = true;
        abandonQuietly(sSession);
    }

    // ------------------------------------------------------------------ 主流程

    private static void doInstall(Context app, File pkg, String token, Intent template) {
        final long t0 = System.currentTimeMillis();
        File work = new File(app.getCacheDir(), "split/" + token);
        List<File> apks = new ArrayList<File>();
        try {
            if (!work.mkdirs() && !work.isDirectory()) {
                finish(app, false, app.getString(R.string.split_fail,
                        app.getString(R.string.split_err_no_workdir)));
                return;
            }
            SplitPackage.Contents c = SplitPackage.scan(pkg);
            if (!c.hasApks()) {
                finish(app, false, app.getString(R.string.split_no_apk));
                return;
            }
            progress(0);
            extractApks(pkg, c, work, apks);
            if (sCancel) { finish(app, false, app.getString(R.string.split_cancelled)); return; }
            if (apks.isEmpty()) {
                finish(app, false, app.getString(R.string.split_no_apk));
                return;
            }
            sPkgName = packageNameOf(app.getPackageManager(), apks);
            progress(48);

            // OBB 数据包：xapk 常见。写不进 Android/obb 是系统限制，如实报告而不是静默跳过
            if (!c.obb.isEmpty() && sPkgName != null) {
                int[] r = copyObb(pkg, c, sPkgName);
                sObbOk = r[0];
                if (r[1] > 0) sObbErr = app.getString(R.string.split_obb_fail_hint, String.valueOf(r[1]));
            }
            progress(55);

            commitSession(app, apks, token, template, sPkgName);
            Log.i(TAG, "session committed in " + (System.currentTimeMillis() - t0) + " ms, apks="
                    + apks.size() + " obb=" + sObbOk);
            sSession = null;   // 已封存，abandon 不再有意义
        } catch (Cancelled ignored) {
            finish(app, false, app.getString(R.string.split_cancelled));
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            finish(app, false, app.getString(R.string.split_fail, brief(t)));
        } finally {
            // 会话封存后系统已持有字节副本，临时文件可以立刻删
            deleteTree(work);
        }
    }

    /** 解包全部 apk 条目（按未压缩大小推进 0–45%） */
    private static void extractApks(File pkg, SplitPackage.Contents c, File work, List<File> out)
            throws IOException {
        ZipFile z = new ZipFile(pkg);
        try {
            long total = Math.max(1, c.apkBytes);
            long done = 0;
            int idx = 0;
            for (SplitPackage.Item it : c.apks) {
                if (sCancel) throw new Cancelled();
                ZipEntry e = z.getEntry(it.path);
                if (e == null || e.isDirectory()) continue;
                File dst = new File(work, (idx++) + "-" + SplitPackage.safeLeaf(it.path));
                InputStream in = z.getInputStream(e);
                try {
                    OutputStream os = new FileOutputStream(dst);
                    try {
                        byte[] buf = new byte[BUF];
                        int got;
                        while ((got = in.read(buf)) > 0) {
                            if (sCancel) throw new Cancelled();
                            os.write(buf, 0, got);
                            done += got;
                            progress((int) Math.min(45, done * 45 / total));
                        }
                    } finally {
                        close(os);
                    }
                } finally {
                    close(in);
                }
                if (dst.length() == 0) {
                    LogRing.add(TAG, "empty apk entry: " + it.path);
                    continue;      // 空条目塞进会话只会让安装器报 INVALID
                }
                out.add(dst);
            }
        } finally {
            close(z);
        }
    }

    /** 会话写入 + commit（55–95%） */
    private static void commitSession(Context app, List<File> apks, String token, Intent template,
            String pkgName) throws IOException {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sid = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(sid);
        sSession = s;
        long total = 0;
        for (File f : apks) total += f.length();
        total = Math.max(1, total);
        long done = 0;
        try {
            byte[] buf = new byte[BUF];
            long lastPct = -1;
            for (int i = 0; i < apks.size(); i++) {
                File f = apks.get(i);
                InputStream in = new FileInputStream(f);
                try {
                    // 会话里同名条目会互相覆盖，所以名字带序号
                    OutputStream os = s.openWrite(i + "-" + f.getName(), 0, f.length());
                    try {
                        int got;
                        while ((got = in.read(buf)) > 0) {
                            if (sCancel) throw new Cancelled();
                            os.write(buf, 0, got);
                            done += got;
                            long pct = 55 + done * 40 / total;
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress((int) pct);
                            }
                        }
                    } finally {
                        close(os);
                    }
                } finally {
                    close(in);
                }
            }
            if (sCancel) throw new Cancelled();
            s.commit(buildPendingIntent(app, token, template, pkgName).getIntentSender());
            progress(95);
            startWatchdog(app, token);
        } catch (Throwable t) {
            abandonQuietly(s);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(brief(t));
        }
    }

    private static PendingIntent buildPendingIntent(Context app, String token, Intent template,
            String pkgName) {
        Intent i = new Intent(template);
        i.setAction(ACTION_RESULT);
        i.putExtra(EXTRA_TOKEN, token);
        if (pkgName != null) i.putExtra("pkg", pkgName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, token.hashCode(), i, flags);
    }

    /**
     * 提交后等结果。绝大多数情况广播一两秒内就到；这里只是防「广播丢了就一直忙」——
     * 不解除的话，下次安装会一直提示「正在安装另一个分包」，只能杀进程恢复。
     */
    private static void startWatchdog(final Context app, final String token) {
        MAIN.postDelayed(new Runnable() {
            public void run() {
                if (token.equals(sToken) && sBusy) {
                    LogRing.add(TAG, "install result broadcast timeout");
                    watchdogFire(app.getString(R.string.split_watchdog_timeout));
                }
            }
        }, WATCHDOG_MS);
    }

    // ------------------------------------------------------------------ OBB

    /**
     * 把 {@code Android/obb/...} 条目写到 {@code /sdcard/Android/obb/<包名>/}。
     * 返回 {成功数, 失败数}。
     *
     * <p>Android 11+ 对 {@code Android/obb} 有额外限制，即便有「所有文件访问」也可能写不进去。
     * 这种包装完仍能启动，只是游戏会报「资源缺失」——所以失败数要报出来，并给出
     * 「手动解压到哪儿」的提示，而不是假装成功。</p>
     */
    private static int[] copyObb(File pkg, SplitPackage.Contents c, String pkgName) {
        int ok = 0, bad = 0;
        File root = new File(new File(Environment.getExternalStorageDirectory(), "Android/obb"),
                pkgName);
        if (!root.isDirectory() && !root.mkdirs()) return new int[]{0, c.obb.size()};
        try {
            ZipFile z = new ZipFile(pkg);
            try {
                for (SplitPackage.Item it : c.obb) {
                    ZipEntry e = z.getEntry(it.path);
                    if (e == null) { bad++; continue; }
                    File dst = new File(root, SplitPackage.safeLeaf(it.path));
                    InputStream in = z.getInputStream(e);
                    try {
                        OutputStream os = new FileOutputStream(dst);
                        try {
                            byte[] buf = new byte[BUF];
                            int got;
                            while ((got = in.read(buf)) > 0) os.write(buf, 0, got);
                        } finally {
                            close(os);
                        }
                    } finally {
                        close(in);
                    }
                    ok++;
                }
            } finally {
                close(z);
            }
        } catch (Throwable t) {
            LogRing.add(TAG, "obb copy failed: " + t);
            bad += Math.max(0, c.obb.size() - ok);
        }
        return new int[]{ok, bad};
    }

    // ------------------------------------------------------------------ 结果

    /**
     * 接收器把广播交回来：在场监听者优先；进程被杀过（监听器为空）则发通知 ——
     * 「装没装上」这件事不能因为界面没了就消失。
     */
    public static void deliver(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (sToken != null && token != null && !sToken.equals(token)) {
            LogRing.add(TAG, "stale result ignored: " + token);   // 上一个会话的迟到广播
            return;
        }
        String pkg = intent.getStringExtra("pkg");
        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        String text;
        if (ok) {
            String name = sPkgName != null ? sPkgName : (pkg == null ? "" : pkg);
            text = sObbOk > 0
                    ? ctx.getString(R.string.split_ok_obb, name, String.valueOf(sObbOk))
                    : ctx.getString(R.string.split_ok, name);
            if (sObbErr != null) text = text + "\n" + sObbErr;
        } else {
            text = ctx.getString(R.string.split_fail, describe(ctx, status, msg));
        }
        Listener l = sListener;
        sListener = null;
        sBusy = false;
        sSession = null;
        if (l != null) {
            deliverTo(l, ok, text);
        } else {
            notify(ctx, ok, text);
        }
    }

    /** 把会话状态码翻成人话；认不出就带上系统原文 */
    private static String describe(Context ctx, int status, String msg) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return ctx.getString(R.string.split_err_aborted);
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return ctx.getString(R.string.split_blocked);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return ctx.getString(R.string.split_err_conflict) + tail(ctx, msg);
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return ctx.getString(R.string.split_err_invalid) + tail(ctx, msg);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return ctx.getString(R.string.split_err_storage) + tail(ctx, msg);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return ctx.getString(R.string.split_err_incompatible) + tail(ctx, msg);
            default:
                return (msg == null || msg.isEmpty()) ? String.valueOf(status) : msg;
        }
    }

    /** 系统原文尾巴：括号样式交给资源（中文全角 / 英文半角） */
    private static String tail(Context ctx, String msg) {
        return (msg == null || msg.isEmpty()) ? ""
                : ctx.getString(R.string.split_paren, msg);
    }

    /** 超时兜底：只解除忙标志、通知在场监听者，不发系统通知（进程还活着，通知是噪音） */
    private static void watchdogFire(String msg) {
        Listener l = sListener;
        sListener = null;
        sBusy = false;
        sSession = null;
        if (l != null) deliverTo(l, false, msg);
    }

    /** 收尾（异常/取消/提前退出都走这里）：解除忙标志，结果照原路送回 */
    private static void finish(Context app, boolean ok, String msg) {
        sBusy = false;
        sSession = null;
        Listener l = sListener;
        sListener = null;
        if (l != null) {
            deliverTo(l, ok, msg);
        } else if (app != null) {
            notify(app, ok, msg);
        }
    }

    private static void deliverTo(final Listener l, final boolean ok, final String msg) {
        MAIN.post(new Runnable() {
            public void run() { l.onResult(ok, msg); }
        });
    }

    private static void progress(final int pct) {
        final Listener l = sListener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            public void run() { l.onProgress(pct); }
        });
    }

    // ------------------------------------------------------------------ 通知

    private static void notify(Context ctx, boolean ok, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                            ctx.getString(R.string.split_channel),
                            NotificationManager.IMPORTANCE_DEFAULT));
                }
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                b.setContentIntent(PendingIntent.getActivity(ctx, 0, launch, flags));
            }
            b.setSmallIcon(ok ? android.R.drawable.stat_sys_download_done
                            : android.R.drawable.stat_notify_error)
                    .setContentTitle(ctx.getString(ok ? R.string.split_notif_ok
                            : R.string.split_notif_fail))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            LogRing.add(TAG, "notify failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String packageNameOf(PackageManager pm, List<File> apks) {
        for (File f : apks) {
            try {
                android.content.pm.PackageInfo pi =
                        pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi != null && pi.packageName != null) return pi.packageName;
            } catch (Throwable t) {
                LogRing.add(TAG, "archive info failed: " + t);
            }
        }
        return null;
    }

    private static void abandonQuietly(PackageInstaller.Session s) {
        if (s == null) return;
        try {
            s.abandon();
        } catch (Throwable t) {
            LogRing.add(TAG, "abandon: " + t);
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (Throwable t) {
            LogRing.add(TAG, "close: " + t);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) LogRing.add(TAG, "delete failed: " + f);
    }

    /** 解包中途取消：统一走这一个异常，免得每层都要判返回值 */
    private static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }
}
