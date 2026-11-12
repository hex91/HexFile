package com.hex91.file.ui;

import com.hex91.file.*;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * v1.69.0 Activity 记录服务（无障碍）。
 *
 * <p>原理：注册 {@code typeWindowStateChanged | typeWindowContentChanged} 后，应用每次页面切换
 * 系统都会回调 {@link #onAccessibilityEvent}，事件里带 {@code packageName} 与 {@code className}，
 * 后者就是当前 Activity 的完整类名 —— 这就是「记录别的应用打开了哪个 Activity」的可行通道。
 *
 * <p>为什么不用 ActivityManager：Android 5.1 起 {@code getRunningTasks} 只返回自己的任务，
 * 普通应用拿不到别人的栈顶页面，除非有 root。无障碍是唯一免 root 的方案。
 *
 * <p>隐私：只取包名/类名，不开 {@code canRetrieveWindowContent}（不读屏幕内容），
 * 记录只存在内存里（{@link ActivityLog}），关掉开关即停止，清空即抹掉。
 */
public class ActivityTraceService extends AccessibilityService {

    private static final String TAG = "HexActivityTrace";

    /** SharedPreferences 名（与浮窗、详情页共用） */
    public static final String PREFS = "activity_log";
    /** 记录总开关：true = 无障碍事件入库 */
    public static final String KEY_ENABLED = "enabled";
    /** 浮窗是否展示 */
    public static final String KEY_FLOAT = "float_on";
    /** 浮窗显示条数（3~5） */
    public static final String KEY_ROWS = "float_rows";

    private static volatile boolean alive = false;

    /** 服务是否已连接（用于界面判断「无障碍是否真的开着」） */
    public static boolean isAlive() {
        return alive;
    }

    /**
     * 无障碍服务是否已在系统设置里启用。
     * <p>读 Secure 表里的服务列表，比 {@link #isAlive()} 更可靠 —— 用户刚在设置里打开、
     * 服务还没 onServiceConnected 时也能正确判断。
     */
    public static boolean isEnabled(android.content.Context c) {
        try {
            String flat = Settings.Secure.getString(
                    c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) return false;
            String me = c.getPackageName() + "/" + ActivityTraceService.class.getName();
            String me2 = c.getPackageName() + "/.ActivityTraceService";
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(me2)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳系统无障碍设置页（不同 ROM 页面不同，失败再退回总设置） */
    public static void openSettings(android.content.Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        // 进程被系统回收后重建：恢复总开关状态，避免「设置里开着但实际没记」
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        ActivityLog.setRecording(sp.getBoolean(KEY_ENABLED, false));
        Log.i(TAG, "connected, recording=" + ActivityLog.isRecording());
        if (ActivityLog.isRecording() && sp.getBoolean(KEY_FLOAT, false)) {
            try {
                startService(new Intent(this, ActivityFloatService.class)
                        .setAction(ActivityFloatService.ACTION_SHOW));
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || !ActivityLog.isRecording()) return;
        int type = e.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        CharSequence pc = e.getPackageName();
        CharSequence cc = e.getClassName();
        if (pc == null || cc == null) return;
        String pkg = pc.toString();
        String cls = cc.toString();
        // 不记录自己：打开记录页/浮窗这个动作本身不是用户想观察的目标，避免刷掉别的应用的记录
        if (getPackageName().equals(pkg)) return;
        if (!ActivityLog.looksLikeActivity(pkg, cls)) return;
        ActivityLog.add(this, pkg, cls, null);
    }

    @Override
    public void onInterrupt() {
        // 系统要求实现；无需处理
    }

    @Override
    public boolean onUnbind(Intent intent) {
        alive = false;
        ActivityLog.setRecording(false);
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
        } catch (Throwable ignore) {
        }
        stopService(new Intent(this, ActivityFloatService.class));
        return super.onUnbind(intent);
    }
}
