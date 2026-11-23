package com.hex91.file.apk;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hex91.file.fs.RootUtil;
import com.hex91.file.fs.Shell;
import com.hex91.file.utils.LogRing;

import java.io.File;

/**
 * v2.121.0：应用安装的统一入口——「有特权就静默，没有就自建界面」。
 *
 * <h3>三条路径的真实边界</h3>
 * <table>
 *   <tr><th>后端</th><th>系统安装器页面</th><th>系统确认弹窗</th><th>底层签名/版本校验</th></tr>
 *   <tr><td>Root（su）</td><td>不出现</td><td>不出现</td><td>仍执行</td></tr>
 *   <tr><td>Shizuku（shell）</td><td>不出现</td><td>不出现</td><td>仍执行</td></tr>
 *   <tr><td>无特权</td><td>不出现（改用 {@link DirectInstaller}）</td><td>Android 8+ 仍会出现</td><td>仍执行</td></tr>
 * </table>
 *
 * <p>第三行是本功能对普通设备的实际收益：ROM 安装器页面上的推荐、广告和营销提示
 * 消失了，进度与失败原因都在 HexFile 自己的界面里。第二行才是用户说的「不经过系统
 * 扫描」——严格说跳过的是安装器 UI 与 Play Protect 的交互提示，包管理器自身的
 * 签名与兼容性校验不可能也不应该跳过。</p>
 *
 * <h3>为什么 APK 先落到 /data/local/tmp</h3>
 * Android 11 起 system_server 看到的 {@code /sdcard} 与 shell 看到的不一致，直接
 * {@code pm install /sdcard/...} 会报 INVALID_URI。命令拼装在 {@link InstallCommand}，
 * 本类只负责按顺序执行「拷贝 → chmod → pm install → 清理」。</p>
 *
 * <p><b>层次</b>：本类返回错误码、系统原文与后端编号，不产用户文案；界面文案由 ui 层映射。</p>
 */
public final class AppInstaller {

    private static final String TAG = "AppInstaller";

    /** 准备阶段：拷贝临时副本 / 创建会话 */
    public static final int STAGE_PREPARE = 0;
    /** 安装阶段：pm install 执行中 / 会话写入完成 */
    public static final int STAGE_INSTALLING = 1;
    /** 即将出现系统确认弹窗（仅无特权回退路径）；界面应在此收起进度条 */
    public static final int STAGE_SYSTEM_CONFIRM = 2;

    /** 进度与结果都回主线程；backend 取 Shell.ROOT / Shell.SHIZUKU / Shell.NONE */
    public interface Listener {
        void onStage(int stage, int backend);
        void onProgress(int percent);
        void onResult(int code, String detail, int backend);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sBusy;
    private static volatile boolean sCancel;
    private static volatile Listener sListener;

    private AppInstaller() {}

    public static boolean busy() { return sBusy; }

    /**
     * 用户取消。特权路径只在命令发起前的检查点生效——已经交给 pm 的会话无法撤回，
     * 此时谎报「已取消」比多等几十秒更有害；无特权路径由 DirectInstaller abandon 会话。
     */
    public static void cancel() {
        sCancel = true;
        DirectInstaller.cancel();
    }

    /**
     * 开始安装。立即返回，所有耗时操作都在后台线程。
     *
     * @param resultIntent 结果 Intent 模板（显式 component 指向 InstallResultReceiver），
     *                     仅在无特权回退时交给 PackageInstaller 使用
     */
    public static void install(final Context ctx, final File apk, final Intent resultIntent,
            Listener l) {
        synchronized (AppInstaller.class) {
            if (sBusy) {
                if (l != null) l.onResult(InstallCommand.ERR_BUSY, null, Shell.NONE);
                return;
            }
            sBusy = true;
        }
        final Context app = ctx.getApplicationContext();
        sListener = l;
        sCancel = false;
        final int backend = Shell.backend(RootUtil.isEnabled());
        if (backend == Shell.NONE) {
            stage(STAGE_SYSTEM_CONFIRM, backend);
            DirectInstaller.install(app, apk, resultIntent, new DirectInstaller.Listener() {
                public void onProgress(int percent) { progress(percent); }
                public void onResult(int code, String detail) {
                    sBusy = false;
                    result(code, detail, backend);
                }
            });
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    privilegedInstall(app, apk, backend);
                } catch (Throwable t) {
                    Log.e(TAG, "privileged install failed", t);
                    LogRing.add(TAG, "privileged install: " + t);
                    done(InstallCommand.classify(-1, t.getMessage()), brief(t), backend);
                }
            }
        }, "app-install").start();
    }

    // ------------------------------------------------------------------ 特权路径

    private static void privilegedInstall(Context app, File apk, int backend) {
        if (apk == null || !apk.isFile()) {
            done(InstallCommand.ERR_INVALID, apk == null ? null : apk.getAbsolutePath(), backend);
            return;
        }
        if (sCancel) {
            done(InstallCommand.ERR_CANCELLED, null, backend);
            return;
        }
        String token = String.valueOf(System.currentTimeMillis());
        String tmp = InstallCommand.tempPath(apk.getName(), token);
        stage(STAGE_PREPARE, backend);
        progress(10);

        RootUtil.RunResult cp = RootUtil.runCapture(
                InstallCommand.copyCommand(apk.getAbsolutePath(), tmp), 60);
        if (cp == null) {
            done(InstallCommand.ERR_TIMEOUT, "copy timeout", backend);
            return;
        }
        if (cp.exit != 0) {
            done(InstallCommand.ERR_IO, cp.out, backend);
            return;
        }
        RootUtil.run(InstallCommand.chmodCommand(tmp));
        if (sCancel) {
            cleanup(tmp);
            done(InstallCommand.ERR_CANCELLED, null, backend);
            return;
        }
        progress(40);
        stage(STAGE_INSTALLING, backend);

        RootUtil.RunResult r = RootUtil.runCapture(
                InstallCommand.installCommand(tmp, app.getPackageName()), 180);
        cleanup(tmp);
        if (r == null) {
            done(InstallCommand.ERR_TIMEOUT, "install timeout", backend);
            return;
        }
        progress(100);
        done(InstallCommand.classify(r.exit, r.out), r.out, backend);
    }

    private static void cleanup(String tmp) {
        RootUtil.run(InstallCommand.removeCommand(tmp));
    }

    // ------------------------------------------------------------------ 回调

    private static void done(int code, String detail, int backend) {
        sBusy = false;
        result(code, detail, backend);
    }

    private static void result(final int code, final String detail, final int backend) {
        final Listener l = sListener;
        sListener = null;
        if (l == null) return;
        MAIN.post(new Runnable() {
            public void run() { l.onResult(code, detail, backend); }
        });
    }

    private static void stage(final int stage, final int backend) {
        final Listener l = sListener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            public void run() { l.onStage(stage, backend); }
        });
    }

    private static void progress(final int percent) {
        final Listener l = sListener;
        if (l == null) return;
        MAIN.post(new Runnable() {
            public void run() { l.onProgress(percent); }
        });
    }

    private static String brief(Throwable t) {
        if (t == null) return null;
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
