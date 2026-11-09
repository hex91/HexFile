package com.hex91.file;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * v1.69.0 Activity 记录浮窗（前台服务）。
 *
 * <p>一个半透明的悬浮小卡片，叠在别的应用上面，实时显示最近 3~5 条页面切换记录；
 * 点击卡片进入 {@link ActivityLogActivity} 看全部（含应用图标、包名、完整类名、时间与次数）。
 *
 * <p>交互：拖动移动（位置记忆）、右上角 ✕ 关闭浮窗、点卡片其余区域看全部。
 * 仅在无障碍服务已启用时才有数据 —— 没数据时卡片显示「等待页面切换」。
 */
public class ActivityFloatService extends Service {

    public static final String ACTION_SHOW = "com.hex91.file.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.hex91.file.FLOAT_HIDE";

    /**
     * v1.73.0：与 Activity 一致地收敛系统字体缩放。
     * 浮窗卡片高度按 dp 固定，系统字号调大后文字会溢出卡片，这里同样限制在 [0.9, 1.1]。
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiKit.alignMetrics(base));
    }

    private static final int NOTI_ID = 0x91AC;
    private static final String CHANNEL = "activity_trace";

    private WindowManager wm;
    private View root;
    private LinearLayout rowsBox;
    private TextView headCount;
    private WindowManager.LayoutParams lp;

    private int rowCount = 3;
    private int screenW, screenH;

    /** 浮窗是否正在显示（界面据此决定「启动」还是「已开启」） */
    public static volatile boolean running = false;

    /** ActivityLog 有新记录时的刷新回调（可能来自任意线程 → 内部切主线程） */
    private final Runnable watcher = new Runnable() {
        public void run() {
            if (root != null) root.post(new Runnable() {
                public void run() {
                    refreshRows();
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        SharedPreferences sp = getSharedPreferences(ActivityTraceService.PREFS, MODE_PRIVATE);
        rowCount = Math.max(3, Math.min(5, sp.getInt(ActivityTraceService.KEY_ROWS, 3)));
        startForegroundCompat();
        running = true;
        buildFloat();
        ActivityLog.addWatcher(watcher);
        refreshRows();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ActivityLog.removeWatcher(watcher);
        try {
            if (root != null && root.isAttachedToWindow()) wm.removeView(root);
        } catch (Throwable ignore) {
        }
        root = null;
        running = false;
        getSharedPreferences(ActivityTraceService.PREFS, MODE_PRIVATE)
                .edit().putBoolean(ActivityTraceService.KEY_FLOAT, false).apply();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================================================ 通知（前台服务要求）

    private void startForegroundCompat() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "Activity 记录浮窗", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setDescription("在其他应用上方显示最近的页面切换记录");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, ActivityLogActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_activity_trace)
                .setContentTitle("Activity 记录已开启")
                .setContentText("浮窗显示最近 " + rowCount + " 条页面切换")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        Intent hide = new Intent(this, ActivityFloatService.class).setAction(ACTION_HIDE);
        PendingIntent stop = PendingIntent.getService(this, 1, hide,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "关闭浮窗", stop).build());
        startForeground(NOTI_ID, b.build());
    }

    // ============================================================ 浮窗视图

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildFloat() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(0xD9000000);                      // 85% 不透明黑：透出下层又不糊字
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(0.8f)), 0x33FFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(10), dp(7), dp(10), dp(7));

        // --- 头行：绿点 + 标题 + 条数 + 关闭 ---
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(this);
        dot.setText("\u25CF");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        dot.setTextColor(0xFF4CAF50);
        head.addView(dot);

        TextView title = new TextView(this);
        title.setText("  Activity");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title);

        headCount = new TextView(this);
        headCount.setTextColor(0x99FFFFFF);
        headCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        headCount.setPadding(dp(6), 0, 0, 0);
        head.addView(headCount);

        View spacer = new View(this);
        LinearLayout.LayoutParams sp0 = new LinearLayout.LayoutParams(0, 1, 1f);
        head.addView(spacer, sp0);

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        close.setTextColor(0xCCFFFFFF);
        close.setPadding(dp(8), dp(2), dp(2), dp(2));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopSelf();
            }
        });
        head.addView(close);

        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 细分隔线
        View line = new View(this);
        line.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lpLine = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lpLine.topMargin = dp(4);
        lpLine.bottomMargin = dp(2);
        card.addView(line, lpLine);

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(rowsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("点此查看全部 \u203A");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        hint.setTextColor(0x80FFFFFF);
        hint.setPadding(0, dp(4), 0, 0);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点卡片（非 ✕）→ 详情页
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(ActivityFloatService.this, ActivityLogActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignore) {
                }
            }
        });

        int width = Math.max(dp(200), (int) (screenW * 0.66f));
        lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences sp = getSharedPreferences(ActivityTraceService.PREFS, MODE_PRIVATE);
        lp.x = sp.getInt("float_x", Math.max(0, screenW - width - dp(12)));
        lp.y = sp.getInt("float_y", screenH / 3);

        root = card;
        attachDrag(card);
        try {
            wm.addView(card, lp);
        } catch (Throwable t) {
            // 没有悬浮窗权限 / 系统限制：直接收尾，界面层会提示去授权
            stopSelf();
        }
    }

    /** 拖动移动 + 未移动视为点击（ListView 场景同理：用手指位移阈值区分） */
    private void attachDrag(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (!moved) return true;
                        lp.x = Math.max(-dp(20), Math.min((int) (startX + dx), screenW - dp(60)));
                        lp.y = Math.max(0, Math.min((int) (startY + dy), screenH - dp(48)));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignore) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) view.performClick();
                        else savePos();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void savePos() {
        try {
            getSharedPreferences(ActivityTraceService.PREFS, MODE_PRIVATE)
                    .edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 只显示最近 rowCount 条：「应用名 · Activity 短名」，一行一条 */
    private void refreshRows() {
        if (rowsBox == null) return;
        List<ActivityLog.Entry> all = ActivityLog.snapshot();
        rowsBox.removeAllViews();
        headCount.setText("· " + all.size());
        int n = Math.min(rowCount, all.size());
        if (n == 0) {
            TextView tv = new TextView(this);
            tv.setText(ActivityTraceService.isEnabled(this)
                    ? "等待页面切换…"
                    : "无障碍未开启（点卡片去开启）");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tv.setTextColor(0x99FFFFFF);
            tv.setSingleLine(true);
            tv.setPadding(0, dp(1), 0, dp(1));
            rowsBox.addView(tv);
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            ActivityLog.Entry e = all.get(i);
            TextView tv = new TextView(this);
            tv.setText(shortLabel(e.label, 10) + " \u00B7 " + e.shortName()
                    + (e.count > 1 ? " \u00D7" + e.count : ""));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tv.setTextColor(i == 0 ? 0xFFFFFFFF : 0xCCFFFFFF);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setPadding(0, dp(1), 0, dp(1));
            // 最新一条左侧加一小段竖线，视觉上区分「当前」
            if (i == 0) tv.setCompoundDrawablesWithIntrinsicBounds(colorBar(), null, null, null);
            rowsBox.addView(tv);
        }
        // 最后一条下面给出时间提示，避免用户以为是历史日志
        TextView tail = new TextView(this);
        tail.setText(String.format(Locale.US, "%tH:%tM:%tS  最新", now, now, now));
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        tail.setTextColor(0x59FFFFFF);
        rowsBox.addView(tail);
    }

    /** 绿色小竖条（最新一条的标记） */
    private android.graphics.drawable.Drawable colorBar() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0xFF4CAF50);
        g.setCornerRadius(dp(2));
        g.setSize(dp(2), dp(10));
        return g;
    }

    private String shortLabel(String s, int max) {
        if (s == null) return "?";
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }
}
