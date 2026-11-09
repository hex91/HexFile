package com.hex91.file;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.69.0 Activity 记录页。
 *
 * <p>顶部是总开关（同时控制「记录入库」与「浮窗展示」）、两处系统授权状态、浮窗显示条数；
 * 下面是完整记录列表：应用图标 + 应用名 · Activity 短名 + {@code 包名:完整类名} + 时间 + 次数。
 * 点条目可复制 / 直接启动该 Activity / 打开应用信息，长按直接复制。
 *
 * <p>权限链：无障碍（收事件）+ 悬浮窗（画浮窗）。缺哪个这里就直接跳哪个设置页，
 * 返回后 onResume 会自动续上，不用再点一次开关。
 */
public class ActivityLogActivity extends BaseActivity {

    private static final int REQ_OVERLAY = 0x91A1;

    private Switch sw;
    private TextView sub, permTv, emptyView;
    private ListView list;
    private LogAdapter adapter;

    private final ArrayList<ActivityLog.Entry> data = new ArrayList<ActivityLog.Entry>();

    private final LruCache<String, Drawable> icons = new LruCache<String, Drawable>(96);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable watcher = new Runnable() {
        public void run() {
            ui.post(new Runnable() {
                public void run() {
                    reload();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_activity_log);

        sw = (Switch) findViewById(R.id.al_switch);
        sub = (TextView) findViewById(R.id.al_sub);
        permTv = (TextView) findViewById(R.id.al_perm);
        emptyView = (TextView) findViewById(R.id.al_empty);
        list = (ListView) findViewById(R.id.al_list);
        adapter = new LogAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.al_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.al_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askClear();
            }
        });
        permTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fixPermission();
            }
        });
        // 先恢复开关状态，再挂监听：否则恢复到 true 会立刻触发一次 startRecording
        // （表现为「刚进页面就弹无障碍对话框」）
        sw.setChecked(prefs().getBoolean(ActivityTraceService.KEY_ENABLED, false));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                if (checked) startRecording();
                else stopRecording();
            }
        });
        for (int i = 3; i <= 5; i++) {
            final int n = i;
            int id = i == 3 ? R.id.al_rows_3 : (i == 4 ? R.id.al_rows_4 : R.id.al_rows_5);
            findViewById(id).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setRows(n);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRowsChips();
        refreshStatus();
        // 从系统设置页返回：权限齐了就自动续上，不用用户再点一次开关
        if (sw.isChecked() && ActivityTraceService.isEnabled(this)) {
            ActivityLog.setRecording(true);
            if (canOverlay(this) && !ActivityFloatService.running) startFloat();
        }
        reload();
        ActivityLog.addWatcher(watcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ActivityLog.removeWatcher(watcher);
    }

    @Override
    protected void onDestroy() {
        ActivityLog.removeWatcher(watcher);
        pool.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(ActivityTraceService.PREFS, MODE_PRIVATE);
    }

    // ============================================================ 开关 / 权限

    private static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private void startRecording() {
        prefs().edit().putBoolean(ActivityTraceService.KEY_ENABLED, true).apply();
        ActivityLog.setRecording(true);
        refreshStatus();
        if (!ActivityTraceService.isEnabled(this)) {
            new HfDialogBuilder(this)
                    .setTitle("需要无障碍权限")
                    .setMessage("Android 不允许普通应用直接读取别的应用当前页面，"
                            + "只能通过无障碍服务拿到「哪个应用打开了哪个 Activity」。\n\n"
                            + "请到「设置 → 无障碍 → HexFile 的 Activity 记录」打开开关，"
                            + "返回后会自动开始记录。\n\n"
                            + "若开关是灰的或提示「受限制的设置」，先到「应用信息 → 右上角 ⋮ → "
                            + "允许受限制的设置」放开限制。\n\n"
                            + "本功能只读取包名与类名，不读取屏幕内容、不联网、不上传。")
                    .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ActivityTraceService.openSettings(ActivityLogActivity.this);
                        }
                    })
                    .setNegativeButton("稍后", null)
                    .show();
            return;
        }
        if (!canOverlay(this)) {
            toast("还需要「显示在其他应用上层」权限才能显示浮窗");
            requestOverlay();
            return;
        }
        startFloat();
    }

    private void stopRecording() {
        prefs().edit()
                .putBoolean(ActivityTraceService.KEY_ENABLED, false)
                .putBoolean(ActivityTraceService.KEY_FLOAT, false)
                .apply();
        ActivityLog.setRecording(false);
        stopService(new Intent(this, ActivityFloatService.class));
        refreshStatus();
        toast("已停止记录");
    }

    private void fixPermission() {
        if (!ActivityTraceService.isEnabled(this)) {
            new HfDialogBuilder(this)
                    .setTitle("无障碍权限")
                    .setMessage("请在「设置 → 无障碍 → 已下载的服务 / HexFile」中开启 Activity 记录。\n\n"
                            + "若开关是灰的或提示「受限制的设置」，先到「应用信息 → 右上角 ⋮ → "
                            + "允许受限制的设置」放开限制。\n\n"
                            + "只读取包名与 Activity 类名，不读取屏幕内容。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ActivityTraceService.openSettings(ActivityLogActivity.this);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!canOverlay(this)) {
            requestOverlay();
            return;
        }
        if (!ActivityFloatService.running) startFloat();
        toast("权限已就绪");
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        } catch (Throwable t) {
            toast("无法打开悬浮窗权限页，请到系统设置手动授权");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == REQ_OVERLAY) {
            refreshStatus();
            if (canOverlay(this) && sw.isChecked()) {
                ActivityLog.setRecording(true);
                startFloat();
            }
        }
    }

    private void startFloat() {
        try {
            Intent it = new Intent(this, ActivityFloatService.class)
                    .setAction(ActivityFloatService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
            else startService(it);
            prefs().edit().putBoolean(ActivityTraceService.KEY_FLOAT, true).apply();
        } catch (Throwable t) {
            toast("浮窗启动失败：" + t.getClass().getSimpleName());
        }
    }

    private void refreshStatus() {
        boolean acc = ActivityTraceService.isEnabled(this);
        boolean ov = canOverlay(this);
        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务：").append(acc ? "已开启" : "未开启（点此去开启）");
        sb.append("\n悬浮窗权限：").append(ov ? "已授权" : "未授权（点此去授权）");
        if (acc && ov) {
            sb.append("\n状态：").append(ActivityFloatService.running ? "记录中，浮窗显示中" : "已就绪");
        }
        permTv.setText(sb.toString());
        permTv.setTextColor(!acc || !ov ? 0xFFFF9800 : ThemeManager.textSub(this));

        sub.setText(ActivityLog.isRecording()
                ? "记录中 · " + ActivityLog.size() + " 条"
                : "已停止");
    }

    // ============================================================ 浮窗条数

    private int rowsValue() {
        return Math.max(3, Math.min(5, prefs().getInt(ActivityTraceService.KEY_ROWS, 3)));
    }

    private void setRows(int n) {
        prefs().edit().putInt(ActivityTraceService.KEY_ROWS, n).apply();
        refreshRowsChips();
        if (ActivityFloatService.running) {
            // 重建浮窗以套用新条数（比在服务里加广播简单可靠）
            stopService(new Intent(this, ActivityFloatService.class));
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (sw.isChecked() && canOverlay(ActivityLogActivity.this)) startFloat();
                }
            }, 260);
        }
    }

    private void refreshRowsChips() {
        int cur = rowsValue();
        int[] ids = {R.id.al_rows_3, R.id.al_rows_4, R.id.al_rows_5};
        for (int i = 0; i < ids.length; i++) {
            TextView tv = (TextView) findViewById(ids[i]);
            boolean on = (i + 3) == cur;
            tv.setTextColor(on ? ThemeManager.onAccent(this) : ThemeManager.text(this));
            tv.setBackgroundColor(on ? ThemeManager.accent(this) : ThemeManager.surfaceAlt(this));
        }
    }

    // ============================================================ 列表

    private void reload() {
        data.clear();
        data.addAll(ActivityLog.snapshot());
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
        refreshStatus();
    }

    private void askClear() {
        if (data.isEmpty()) {
            toast("还没有记录");
            return;
        }
        new HfDialogBuilder(this)
                .setTitle("清空记录")
                .setMessage("将删除当前 " + data.size() + " 条页面切换记录（不影响浮窗开关）。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ActivityLog.clear();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEntry(final ActivityLog.Entry e) {
        String msg = "应用：" + e.label + "\n"
                + "包名：" + e.pkg + "\n"
                + "Activity：" + e.cls + "\n"
                + "最近：" + timeText(e.time)
                + (e.count > 1 ? "（连续 " + e.count + " 次）" : "");
        new HfDialogBuilder(this)
                .setTitle(e.shortName())
                .setMessage(msg)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copy(e.pkg + ":" + e.cls);
                    }
                })
                .setNeutralButton("启动", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        launch(e);
                    }
                })
                .setNegativeButton("应用信息", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openAppInfo(e.pkg);
                    }
                })
                .show();
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("activity", s));
            toast("已复制：" + s);
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void launch(ActivityLog.Entry e) {
        try {
            Intent it = new Intent();
            it.setClassName(e.pkg, e.cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            toast("无法启动：" + t.getClass().getSimpleName() + "（该页面可能未导出或已删除）");
        }
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("无法打开应用信息");
        }
    }

    private String timeText(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        java.util.Calendar now = java.util.Calendar.getInstance();
        boolean sameDay = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
        String fmt = sameDay ? "%tH:%tM:%tS" : "%tm-%td %tH:%tM";
        return String.format(Locale.US, fmt, t, t, t, t);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ============================================================ 适配器

    private class Holder {
        ImageView icon;
        TextView title;
        TextView sub;
        TextView right;
    }

    private class LogAdapter extends BaseAdapter {
        public int getCount() {
            return data.size();
        }

        public Object getItem(int i) {
            return data.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                LinearLayout row = new LinearLayout(ActivityLogActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));

                h = new Holder();
                h.icon = new ImageView(ActivityLogActivity.this);
                h.icon.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(h.icon);

                LinearLayout mid = new LinearLayout(ActivityLogActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                mlp.leftMargin = dp(11);
                mid.setLayoutParams(mlp);

                h.title = new TextView(ActivityLogActivity.this);
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setTextColor(ThemeManager.text(ActivityLogActivity.this));
                mid.addView(h.title);

                h.sub = new TextView(ActivityLogActivity.this);
                h.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                h.sub.setTextColor(ThemeManager.textSub(ActivityLogActivity.this));
                h.sub.setPadding(0, dp(2), 0, 0);
                mid.addView(h.sub);
                row.addView(mid);

                h.right = new TextView(ActivityLogActivity.this);
                h.right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                h.right.setGravity(Gravity.END);
                h.right.setTextColor(ThemeManager.textSub(ActivityLogActivity.this));
                h.right.setPadding(dp(6), 0, 0, 0);
                row.addView(h.right);

                row.setTag(h);
                convert = row;
            } else {
                h = (Holder) convert.getTag();
            }

            final ActivityLog.Entry e = data.get(i);
            h.title.setText(e.label + "  ·  " + e.shortName());
            h.sub.setText(e.pkg + " : " + e.cls);
            h.right.setText(timeText(e.time) + (e.count > 1 ? "\n\u00D7" + e.count : ""));

            // 图标：LruCache 命中直接给；否则后台加载（tag 校验防复用串图）
            h.icon.setTag(e.pkg);
            Drawable cached = icons.get(e.pkg);
            if (cached != null) {
                h.icon.setBackgroundColor(0x00000000);
                h.icon.setImageDrawable(cached);
            } else {
                h.icon.setImageDrawable(null);
                h.icon.setBackgroundColor(ThemeManager.surfaceAlt(ActivityLogActivity.this));
                final ImageView iv = h.icon;
                final String pkg = e.pkg;
                pool.execute(new Runnable() {
                    public void run() {
                        Drawable d = null;
                        try {
                            d = getPackageManager().getApplicationIcon(pkg);
                        } catch (Throwable ignore) {
                        }
                        final Drawable fd = d;
                        if (fd == null) return;
                        ui.post(new Runnable() {
                            public void run() {
                                icons.put(pkg, fd);
                                if (pkg.equals(iv.getTag())) {
                                    iv.setBackgroundColor(0x00000000);
                                    iv.setImageDrawable(fd);
                                }
                            }
                        });
                    }
                });
            }

            convert.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showEntry(e);
                }
            });
            convert.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    copy(e.pkg + ":" + e.cls);
                    return true;
                }
            });
            return convert;
        }
    }
}
