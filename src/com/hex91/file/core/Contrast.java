package com.hex91.file.core;

/**
 * 颜色对比度工具（纯 Java，可桌面单测）。
 *
 * <p>实现 WCAG 2.1 的相对亮度与对比度公式。用途：判断某个前景色叠在某个背景上是否还看得清，
 * 看不清就把它往黑/白方向推，直到达标。</p>
 *
 * <p>典型场景是顶栏按钮：部分色板把 {@code hfTopBar} 与 {@code hfAccent} 设成同一个色值，
 * 而 SOFT 胶囊是「accent 14% 底 + accent 文字」——叠在同色顶栏上，文字与背景同色，
 * 表现出来就是「按钮不存在」。</p>
 */
public final class Contrast {

    /** 大号文字/图标的最低可辨阈值（WCAG AA 对非正文的要求） */
    public static final double MIN_TEXT = 3.0;

    private Contrast() {}

    /** 相对亮度 0..1 */
    public static double luminance(int color) {
        return 0.2126 * chan((color >> 16) & 0xFF)
                + 0.7152 * chan((color >> 8) & 0xFF)
                + 0.0722 * chan(color & 0xFF);
    }

    private static double chan(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** 两色对比度，范围 1..21（1 = 完全相同，21 = 纯黑对纯白） */
    public static double ratio(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 在 bg 上最清楚的那个极值色（黑或白） */
    public static int readableOn(int bg) {
        return ratio(bg, 0xFF000000) >= ratio(bg, 0xFFFFFFFF) ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * 保证 fg 在 bg 上至少有 min 的对比度。不足时按 18% 步长同时试探「变暗」和「变亮」，
     * 取第一个达标且对比更高的方向；12 步都不达标就退回 {@link #readableOn(int)}。
     *
     * <p>返回颜色保留 fg 的 alpha 通道。</p>
     */
    public static int ensure(int bg, int fg, double min) {
        if (ratio(bg, fg) >= min) return fg;
        int dark = fg;
        int light = fg;
        for (int i = 0; i < 12; i++) {
            dark = mix(dark, 0xFF000000, 0.18);
            light = mix(light, 0xFFFFFFFF, 0.18);
            boolean dOk = ratio(bg, dark) >= min;
            boolean lOk = ratio(bg, light) >= min;
            if (dOk && lOk) return ratio(bg, dark) >= ratio(bg, light) ? dark : light;
            if (dOk) return dark;
            if (lOk) return light;
        }
        int best = readableOn(bg);
        return (fg & 0xFF000000) | (best & 0x00FFFFFF);
    }

    /** 线性混合：p=0 返回 a，p=1 返回 b；结果保留 a 的 alpha */
    public static int mix(int a, int b, double p) {
        if (p <= 0) return a;
        if (p >= 1) return (a & 0xFF000000) | (b & 0x00FFFFFF);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * p);
        int g = (int) Math.round(ag + (bgc - ag) * p);
        int bl = (int) Math.round(ab + (bb - ab) * p);
        return (a & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
