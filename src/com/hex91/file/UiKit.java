package com.hex91.file;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 全局界面度量（v1.73.0）。
 *
 * 背景：本应用 37 个 Activity 全部手写 UI，共 258 处 setTextSize（默认 SP 单位），
 * 容器高度与间距却是固定 dp。若设备把系统字号调大（国产 ROM 默认常见 1.15~1.30，
 * 无障碍模式可达 2.0），文字会随 SP 放大而容器不变，表现为「这台机器字特别大、
 * 挤成一团」，另一台正常。
 *
 * 处理：在 Activity 创建前把 Configuration 的 fontScale 收敛到 [0.90, 1.10]。
 *   - 上限 1.10：保留用户「稍微大一点」的合理偏好，同时防止 1.3~2.0 撑破固定高度的行；
 *   - 下限 0.90：防止把字号调到极小的设备上文字看不清。
 * 只改 fontScale，不动 density/densityDpi，因此 dp 布局尺寸与屏幕密度的对应关系不受影响。
 */
public final class UiKit {

    /** 允许的最小字体缩放（系统字号设得极小时兜底） */
    public static final float FONT_SCALE_MIN = 0.90f;
    /** 允许的最大字体缩放（防止固定 dp 容器被撑破） */
    public static final float FONT_SCALE_MAX = 1.10f;

    private UiKit() {}

    /** 把系统字体缩放收敛到允许区间；非法值按 1.0 处理。纯函数，便于单测。 */
    public static float clampFontScale(float fontScale) {
        if (Float.isNaN(fontScale) || fontScale <= 0f) return 1f;
        if (fontScale < FONT_SCALE_MIN) return FONT_SCALE_MIN;
        if (fontScale > FONT_SCALE_MAX) return FONT_SCALE_MAX;
        return fontScale;
    }

    /** 是否需要替换 Context（差值太小就没必要重建 Resources） */
    public static boolean needsAdjust(float raw, float clamped) {
        return Math.abs(raw - clamped) > 0.004f;
    }

    /**
     * 返回收敛过 fontScale 的 Context；无需调整时原样返回，避免多余的对象分配。
     * 失败时回退到原 Context，绝不因为度量调整让界面起不来。
     */
    public static Context alignMetrics(Context base) {
        if (base == null) return null;
        try {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            float raw = cfg.fontScale;
            float fixed = clampFontScale(raw);
            if (!needsAdjust(raw, fixed)) return base;
            cfg.fontScale = fixed;
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }

    /**
     * 把系统最新配置同步进 Activity 自己的 Resources（v1.95.0）。
     *
     * <p>{@link #alignMetrics} 在系统字号被调出 [0.90, 1.10] 时会用
     * {@code createConfigurationContext} 另造一份 Resources —— 那是**页面创建那一刻的快照**。
     * 此后转屏、切深色模式，系统只更新 Activity 原本的 Resources，这份快照里的
     * {@code orientation} / {@code screenWidthDp} / {@code uiMode} 全部停在旧值。</p>
     *
     * <p>症状：系统字号 1.15 的机器上，`getResources().getConfiguration().orientation`
     * 在横屏播放页里依然返回竖屏 —— 播放器「横屏点返回先回竖屏」判断失败，直接退出到文件列表；
     * 同时「跟随系统深色」在页面存活期间也不生效。</p>
     */
    public static void syncConfig(android.app.Activity a, Configuration newConfig) {
        if (a == null || newConfig == null) return;
        try {
            android.content.res.Resources r = a.getResources();
            Configuration c = new Configuration(newConfig);
            // 保持与 attachMetrics 一致的收敛结果，否则同步一次字号就被打回原值
            c.fontScale = clampFontScale(c.fontScale);
            r.updateConfiguration(c, r.getDisplayMetrics());
        } catch (Throwable ignored) {
            // 同步失败不影响功能，调用方（播放器）另有窗口尺寸兜底
        }
    }

    /** dp → px */
    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** sp → px（已包含收敛后的 fontScale） */
    public static int sp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity);
    }
}
