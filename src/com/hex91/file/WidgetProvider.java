package com.hex91.file;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.widget.RemoteViews;

import java.io.File;

/**
 * v1.85.0 桌面小组件（P2 第二项）。
 *
 * <p>4×1 卡片：顶行是应用名 + 存储摘要 + 刷新，中间一条占用进度，底部四个常用入口
 * （工作目录 / 下载 / 图片 / 回收站）。点任意入口直接打开应用并跳到对应目录。</p>
 *
 * <h3>为什么不用 ?attr 上色</h3>
 * RemoteViews 是在 <b>launcher 的进程与主题</b>里 inflate 的，本应用的
 * {@code hfAccent} 在那里根本不存在，所以卡片固定用深色半透明 + 白字
 * （见 {@code widget_card.xml}）。同理，按钮背景自带 ripple，不用
 * {@code ?android:attr/selectableItemBackground}——部分第三方桌面缺这个 attr 会 inflate 失败。
 *
 * <h3>刷新时机</h3>
 * 系统最小节拍是 30 分钟，等不起；所以应用侧改动后直接调 {@link #refreshIfExists(Context)}
 * 发显式更新，卡片右上角的 ⟳ 也走同一条路。
 */
public class WidgetProvider extends AppWidgetProvider {

    /** 卡片内「刷新」按钮的广播 */
    public static final String ACTION_REFRESH = "com.hex91.file.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(context));
            } catch (Throwable ignored) {
                // 单个 id 失败（例如 launcher 正在重启）不该影响其余卡片
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refreshIfExists(context);
        }
    }

    /** 有卡片时才刷（没有就跳过，避免每次回前台都白跑一遍 binder） */
    public static void refreshIfExists(Context c) {
        if (c == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(c);
            for (int id : ids) mgr.updateAppWidget(id, v);
        } catch (Throwable ignored) {
        }
    }

    /** 组装卡片内容（也用于「立即刷新」） */
    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_file);

        long total = 0, avail = 0;
        try {
            File ext = Environment.getExternalStorageDirectory();
            StatFs fs = new StatFs(ext.getAbsolutePath());
            total = fs.getTotalBytes();
            avail = fs.getAvailableBytes();
        } catch (Throwable ignored) {
            // StatFs 在极少数机型上会抛（挂载点异常）→ 显示「存储不可用」
        }
        v.setTextViewText(R.id.widget_storage, WidgetText.summary(total, avail));
        v.setProgressBar(R.id.widget_bar, 100, WidgetText.usedPercent(total, avail), false);

        // 整卡点击 → 打开主界面（沿用 MainActivity 已有的 "dir" extra 机制）
        v.setOnClickPendingIntent(R.id.widget_root,
                activity(c, 1, new Intent(c, MainActivity.class)));
        v.setOnClickPendingIntent(R.id.widget_work,
                dir(c, 2, new File(WorkDir.base().getAbsolutePath())));
        v.setOnClickPendingIntent(R.id.widget_download,
                dir(c, 3, publicDir(Environment.DIRECTORY_DOWNLOADS)));
        v.setOnClickPendingIntent(R.id.widget_pictures,
                dir(c, 4, publicDir(Environment.DIRECTORY_PICTURES)));
        v.setOnClickPendingIntent(R.id.widget_trash,
                activity(c, 5, new Intent(c, TrashActivity.class)));

        Intent refresh = new Intent(c, WidgetProvider.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.widget_refresh,
                PendingIntent.getBroadcast(c, 9, refresh, piFlags()));
        return v;
    }

    private static File publicDir(String type) {
        File f = Environment.getExternalStoragePublicDirectory(type);
        return f != null ? f : WorkDir.base();
    }

    /** 打开某个目录；目录不存在就退回工作目录，避免点了没反应 */
    private static PendingIntent dir(Context c, int req, File target) {
        File f = (target != null && target.isDirectory()) ? target : WorkDir.base();
        Intent i = new Intent(c, MainActivity.class)
                .putExtra("dir", f.getAbsolutePath());
        return activity(c, req, i);
    }

    private static PendingIntent activity(Context c, int req, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, piFlags());
    }

    /**
     * Android 12 起 PendingIntent 必须声明可变性；本应用 targetSdk 30，但
     * 设备可能是 12+，这里统一按 API 23+ 传 IMMUTABLE（小组件的 Intent 从不被改写）。
     */
    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
