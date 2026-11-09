package com.hex91.file;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 局域网快传的前台服务（v1.74.0）。
 *
 * <p>不开这个服务时，页面一退出就停止监听——这是默认行为，避免长期占用发现端口。
 * 用户点「后台接收」后由本服务常驻，锁屏或切到别的应用也能收文件。</p>
 *
 * <p>服务只负责持有 {@link LanNode} 的生命周期，收发逻辑全在 LanNode 里，
 * 所以 Activity 与服务共享同一份设备表，不会出现「服务收到了但页面看不到」。</p>
 */
public class LanShareService extends Service {

    private static final String CHANNEL = "lan_share";
    private static final int NOTIF_ID = 0x4C41;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LanNode node = LanNode.get(this);
        node.setKeepReceiving(true);
        boolean ok = node.start();
        startForeground(NOTIF_ID, build(node, ok));
        return START_STICKY;
    }

    private Notification build(LanNode node, boolean ok) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "局域网快传",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, LanShareActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        String title = ok ? "局域网快传已开启" : "局域网快传未能启动";
        String text = ok
                ? node.selfName() + " · 点击查看设备 / 停止接收"
                : "发现端口被占用，请关闭其他快传类应用后重试";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_lan)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF2BE0C8);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
