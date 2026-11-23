package com.hex91.file.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.hex91.file.R;
import com.hex91.file.apk.InstallCommand;
import com.hex91.file.utils.LogRing;

/**
 * v2.121.0：单文件安装的结果通知（仅界面已销毁时发）。
 *
 * <p>文案映射放在 ui 层：apk 层的 {@link InstallCommand} 只给错误码和系统原文，
 * 中英文在这里落地。通知 id 与通道名都和分包安装（{@code SplitInstaller}）分开，
 * 避免两个会话的结果互相覆盖。</p>
 */
public final class InstallNotifier {

    private static final String TAG = "InstallNotifier";
    private static final String CHANNEL = "install_result_direct";
    private static final int NOTIF_ID = 0x5C12;

    private InstallNotifier() {}

    public static void notify(Context ctx, int code, String detail) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                            ctx.getString(R.string.install_notif_channel),
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
            boolean ok = InstallCommand.ok(code);
            String text = UtilText.installResult(ctx, code, detail);
            b.setSmallIcon(ok ? android.R.drawable.stat_sys_download_done
                            : android.R.drawable.stat_notify_error)
                    .setContentTitle(ctx.getString(ok
                            ? R.string.install_notif_ok : R.string.install_notif_fail))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            LogRing.add(TAG, "notify: " + t);
        }
    }
}
