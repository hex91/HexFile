package com.hex91.file.apk;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.hex91.file.utils.LogRing;

/**
 * v2.125.0：处理 {@code PackageInstaller.STATUS_PENDING_USER_ACTION}。
 *
 * <p>Android 11 起，第三方会话安装的结果不一定直接是成功或失败。系统可能回一个
 * 「待用户确认」，并把确认页的 Intent 放在 {@code Intent.EXTRA_INTENT} 里；调用方必须
 * 自己启动它。旧实现把这个状态当成 {@code ERR_UNKNOWN}，于是用户看到「安装失败未知错误」
 * 却没有任何系统确认页——这正是未 root 设备上的典型现象。</p>
 *
 * <p>本类只做两件事：从系统结果里取出确认 Intent、把它启动起来。不产用户文案；
 * 启动失败时返回 false，由调用方按已有错误码路径收尾。</p>
 */
public final class InstallConfirm {

    private static final String TAG = "InstallConfirm";

    private InstallConfirm() {}

    /** 系统结果里是否带着可启动的确认页；没有则只能按失败处理 */
    @SuppressWarnings("deprecation")
    public static Intent extract(Intent result) {
        if (result == null) return null;
        Bundle extras = result.getExtras();
        if (extras == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return extras.getParcelable(Intent.EXTRA_INTENT, Intent.class);
        }
        return extras.getParcelable(Intent.EXTRA_INTENT);
    }

    /**
     * 启动系统确认页。结果广播由 BroadcastReceiver 收到，应用可能在后台，
     * 所以必须带 {@code FLAG_ACTIVITY_NEW_TASK}。
     */
    public static boolean launch(Context ctx, Intent confirm) {
        if (ctx == null || confirm == null) return false;
        try {
            Intent i = new Intent(confirm);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "launch confirmation failed", t);
            LogRing.add(TAG, "launch confirmation: " + t);
            return false;
        }
    }
}
