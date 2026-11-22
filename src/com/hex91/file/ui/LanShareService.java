package com.hex91.file.ui;

import com.hex91.file.net.LanNode;
import com.hex91.file.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

    /**
     * R-47：后台接收最长 30 分钟。
     *
     * <p>原来这个服务一旦开着就一直开着 —— 用户点了「后台接收」，之后忘了，
     * 手机在咖啡馆 Wi-Fi 里就一直挂着一个局域网投递口。加一个上限，
     * 到点自己收摊；需要继续再点一次。</p>
     */
    private static final long MAX_KEEP_MS = 30 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = new Runnable() {
        public void run() {
            stopSelf();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LanNode node = LanNode.get(this);
        node.setKeepReceiving(true);
        boolean ok = node.start();
        startForeground(NOTIF_ID, build(node, ok));
        // 每次启动重新计时：用户又点了一次「后台接收」，说明他现在确实要用
        ui.removeCallbacks(autoStop);
        ui.postDelayed(autoStop, MAX_KEEP_MS);
        return START_STICKY;
    }

    private Notification build(LanNode node, boolean ok) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        getString(R.string.ls_channel),
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

        String title = getString(ok ? R.string.ls_on : R.string.ls_off);
        String text = ok
                ? getString(R.string.ls_text_on, node.selfName())
                : getString(R.string.ls_text_off);

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
        ui.removeCallbacks(autoStop);
        // 服务没了就不该还留着「后台接收」的偏好，否则下次进页面会显示「开着」但其实没监听
        try {
            LanNode.get(this).setKeepReceiving(false);
        } catch (Throwable t) {
            android.util.Log.w("HexFile", "LanShareService: reset keep flag failed: " + t);
        }
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
