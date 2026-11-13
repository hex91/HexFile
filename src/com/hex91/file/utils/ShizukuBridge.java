package com.hex91.file.utils;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * v1.86.0：Shizuku 桥接——无 Root 设备上的「shell 权限」。
 *
 * <p>Shizuku 让普通应用借用 adb shell（uid 2000）的身份执行命令，
 * 因此无需 root 就能做 {@code pm}、{@code cp}、读写 shell 可访问的目录等。
 * 它不是 root：{@code /data/data/<别的应用>} 仍是 700，照样读不到——
 * 这一点必须在界面上说清楚，否则用户会以为「开了就能看全盘」。</p>
 *
 * <h3>为什么必须有 provider</h3>
 * Shizuku 的 binder 不从 Service 拿，而是由客户端自己声明一个
 * {@code rikka.shizuku.ShizukuProvider}（authorities = 包名 + ".shizuku"），
 * 通过它的 {@code call()} 把 binder 交给应用。少了这个声明，
 * {@link #alive()} 永远是 false，且没有任何错误提示——这是最常见的踩坑点。
 *
 * <h3>版本门槛</h3>
 * api AAR 的 minSdk 是 24、provider 是 23，本应用 minSdk 21。
 * 所以在 21/22 上必须完全不触碰这些类（否则 NoClassDefFoundError），
 * 统一由 {@link #supported()} 拦在入口，并且所有调用都包 try/catch(Throwable)。
 */
public final class ShizukuBridge {

    /** 授权请求码（与 Activity 的 onActivityResult 无关，Shizuku 走自己的 listener） */
    public static final int REQ_CODE = 0x5A1;

    /** 与 WorkDir/RootUtil 共用偏好文件 */
    public static final String PREF = "hf_settings";
    /** 用户开关：允许使用 Shizuku（默认开；没装 Shizuku 时开着也无副作用） */
    public static final String KEY_ENABLED = "shizuku_enabled";

    /** ping / 授权状态的短缓存，避免 isRootAvailable() 热路径反复走 binder */
    private static volatile long checkedAt;
    private static volatile boolean cachedAlive;
    private static volatile boolean cachedGranted;
    private static final long TTL_MS = 2000;

    private ShizukuBridge() {}

    /** 系统版本够不够（Shizuku 要求 6.0+） */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 23;
    }

    /** 用户是否允许使用（设置页开关） */
    public static boolean isEnabled(android.content.Context c) {
        if (c == null) return true;
        try {
            return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(android.content.Context c, boolean on) {
        try {
            c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable ignored) {
        }
        invalidate();
    }

    /** 清掉缓存（授权完成、切换开关后调用） */
    public static void invalidate() {
        checkedAt = 0;
    }

    /** Shizuku 服务是否在运行（已安装且已启动） */
    public static boolean alive() {
        refresh();
        return cachedAlive;
    }

    /** 是否已获得授权 */
    public static boolean granted() {
        refresh();
        return cachedGranted;
    }

    /** 真正可用：服务在跑且已授权 */
    public static boolean usable() {
        return supported() && alive() && granted();
    }

    /** 服务版本号；不可用返回 -1 */
    public static int version() {
        if (!supported()) return -1;
        try {
            return rikka.shizuku.Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void refresh() {
        if (!supported()) {
            cachedAlive = false;
            cachedGranted = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt < TTL_MS) return;
        synchronized (ShizukuBridge.class) {
            if (System.currentTimeMillis() - checkedAt < TTL_MS) return;
            boolean a = false, g = false;
            try {
                a = rikka.shizuku.Shizuku.pingBinder();
                if (a) {
                    g = rikka.shizuku.Shizuku.checkSelfPermission()
                            == PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable t) {
                // 未装 Shizuku / provider 未声明 / 类缺失 —— 一律当不可用
                a = false;
                g = false;
            }
            cachedAlive = a;
            cachedGranted = g;
            checkedAt = System.currentTimeMillis();
        }
    }

    /** 弹授权框（Shizuku app 会显示确认界面） */
    public static void request(Activity a) {
        if (!supported()) {
            if (a != null) android.widget.Toast.makeText(a,
                    "当前系统版本过低，Shizuku 需要 Android 6.0 以上",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            rikka.shizuku.Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            if (a != null) android.widget.Toast.makeText(a,
                    "无法请求授权：" + t.getClass().getSimpleName(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 以 shell 身份执行命令。调用方负责读流与 waitFor。
     *
     * <p><b>不能在主线程调用</b>：Shizuku 内部会做 binder 事务，主线程调用会抛
     * {@code IllegalStateException}。</p>
     */
    public static Process start(String shellCmd) throws Exception {
        if (!usable()) throw new IllegalStateException("Shizuku 未就绪或未授权");
        return rikka.shizuku.Shizuku.newProcess(
                new String[]{"sh", "-c", shellCmd}, null, null);
    }

    /** 给设置页用的状态文案 */
    public static String statusText(android.content.Context c) {
        if (!supported()) return "系统版本过低（需 Android 6.0+）";
        if (!isEnabled(c)) return "已关闭";
        if (!alive()) return "未检测到 Shizuku（需先安装并启动 Shizuku）";
        if (!granted()) return "Shizuku 已运行，尚未授权";
        return "已连接 · 版本 " + version() + "（shell 权限）";
    }
}
