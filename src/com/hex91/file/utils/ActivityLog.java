package com.hex91.file.utils;

import com.hex91.file.ui.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * v1.69.0 Activity 记录仓库（进程内单例）。
 *
 * <p>数据来源是 {@link ActivityTraceService}（无障碍服务监听窗口切换），
 * 消费方是浮窗（{@link ActivityFloatService}）与详情页（{@link ActivityLogActivity}）。
 * 三者同进程，所以直接用静态列表传递，不做跨进程/落盘 —— 记录本身是「实时观察」性质，
 * 重启即清空更符合预期，也避免把用户的应用使用轨迹长期留在磁盘上。
 *
 * <p>上限 {@link #MAX} 条（环形），超出后丢最旧的；同一 Activity 连续重复只累加次数并刷新时间，
 * 否则像输入法、聊天列表这类频繁刷新窗口的界面会把整屏刷满同一条。
 */
public class ActivityLog {

    /** 单条记录 */
    public static class Entry {
        public final String pkg;
        public final String cls;
        public final String label;      // 应用名（取不到时回落为包名）
        public final long time;         // 最近一次出现的时间
        public final int count;         // 连续出现次数

        Entry(String pkg, String cls, String label, long time, int count) {
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
            this.time = time;
            this.count = count;
        }

        /** 包名.类名（完整） */
        public String full() {
            return pkg + "/" + cls;
        }

        /** Activity 短名：取类名最后一段，页面里配合「包名」一起看更清楚 */
        public String shortName() {
            if (cls == null) return "?";
            int i = cls.lastIndexOf('.');
            return i >= 0 ? cls.substring(i + 1) : cls;
        }

        /** 列表主标题：应用名 · Activity 短名 */
        public String title() {
            return label + "  ·  " + shortName();
        }
    }

    /** 环形上限：足够翻查最近几百次页面跳转，又不至于占内存 */
    public static final int MAX = 500;

    private static final ArrayList<Entry> LIST = new ArrayList<Entry>();
    private static final ArrayList<Runnable> WATCHERS = new ArrayList<Runnable>();
    private static final HashMap<String, String> LABELS = new HashMap<String, String>();
    /** 记录总开关（无障碍服务侧也读这个值，关掉后不再收事件） */
    private static volatile boolean recording = false;
    /** 累计接收条数（含被合并的重复，用于界面显示「已记录 N 次」） */
    private static int total = 0;

    public static void setRecording(boolean on) {
        recording = on;
    }

    public static boolean isRecording() {
        return recording;
    }

    public static int total() {
        synchronized (LIST) {
            return total;
        }
    }

    /**
     * 记一条。
     *
     * @param label 应用名，允许 null（内部按包名查 PackageManager，查不到就用包名）
     */
    public static void add(Context ctx, String pkg, String cls, String label) {
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) return;
        // ctx 允许为 null（桌面单测）：此时直接用包名当显示名，避免碰 PackageManager
        String name = label != null ? label : (ctx != null ? labelOf(ctx, pkg) : pkg);
        synchronized (LIST) {
            total++;
            if (!LIST.isEmpty()) {
                Entry top = LIST.get(0);
                if (top.pkg.equals(pkg) && top.cls.equals(cls)) {
                    // 同一页面反复触发窗口事件：只刷新时间与次数，不新增行
                    LIST.set(0, new Entry(pkg, cls, name, System.currentTimeMillis(), top.count + 1));
                    notifyWatchersLocked();
                    return;
                }
            }
            LIST.add(0, new Entry(pkg, cls, name, System.currentTimeMillis(), 1));
            while (LIST.size() > MAX) LIST.remove(LIST.size() - 1);
        }
        notifyWatchers();
    }

    /** 快照（倒序：最新的在前） */
    public static List<Entry> snapshot() {
        synchronized (LIST) {
            return new ArrayList<Entry>(LIST);
        }
    }

    public static int size() {
        synchronized (LIST) {
            return LIST.size();
        }
    }

    public static void clear() {
        synchronized (LIST) {
            LIST.clear();
            total = 0;
        }
        notifyWatchers();
    }

    /** 监视器：浮窗与详情页都挂一个，有新记录时刷新 */
    public static void addWatcher(Runnable r) {
        if (r == null) return;
        synchronized (WATCHERS) {
            if (!WATCHERS.contains(r)) WATCHERS.add(r);
        }
    }

    public static void removeWatcher(Runnable r) {
        synchronized (WATCHERS) {
            WATCHERS.remove(r);
        }
    }

    private static void notifyWatchers() {
        synchronized (WATCHERS) {
            for (Runnable r : WATCHERS) {
                try {
                    r.run();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * 已在持锁状态下调用（add 的早退分支）—— 仍然通知，但避免重复进入 LIST 锁。
     * 这里直接复制一份监视器再回调，防止回调里又调 snapshot() 造成死锁。
     */
    private static void notifyWatchersLocked() {
        Runnable[] arr;
        synchronized (WATCHERS) {
            arr = WATCHERS.toArray(new Runnable[0]);
        }
        for (Runnable r : arr) {
            try {
                r.run();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 应用名缓存（PackageManager 查询不便宜，浮窗每几百毫秒就可能要一次） */
    public static String labelOf(Context ctx, String pkg) {
        if (pkg == null) return "?";
        synchronized (LABELS) {
            String v = LABELS.get(pkg);
            if (v != null) return v;
        }
        String name = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) name = l.toString();
        } catch (Throwable ignore) {
        }
        synchronized (LABELS) {
            if (LABELS.size() > 300) LABELS.clear();
            LABELS.put(pkg, name);
        }
        return name;
    }

    /**
     * 类名是否「看起来是一个 Activity」。
     *
     * <p>无障碍事件里 {@code TYPE_WINDOW_CONTENT_CHANGED} 的 className 往往是
     * {@code android.widget.FrameLayout}、{@code android.view.View} 这类容器，直接记录会把列表刷成
     * 一堆 ViewGroup。这里按前缀/后缀做白名单式过滤，纯函数便于单测。
     */
    public static boolean looksLikeActivity(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (cls == null) return false;
        String c = cls.trim();
        if (c.length() < 3 || c.indexOf('.') < 0) return false;
        // 框架内部容器/弹层：不是页面
        String[] badPrefix = {
                "android.widget.", "android.view.", "android.webkit.",
                "android.app.", "android.inputmethodservice.", "android.graphics.",
                "android.text.", "android.animation.", "com.android.internal."
        };
        for (String p : badPrefix) if (c.startsWith(p)) return false;
        String[] badSuffix = {"PopupWindow", "Toast", "DecorView", "ViewRootImpl", "Layout"};
        for (String s : badSuffix) if (c.endsWith(s)) return false;
        // 输入法候选窗、系统弹层常见形态
        String low = c.toLowerCase(Locale.US);
        if (low.contains("inputmethod") || low.contains("popupwindow")) return false;
        return true;
    }
}
