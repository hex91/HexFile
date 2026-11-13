package com.hex91.file.utils;

/**
 * v2.4.0（O-17）：语法高亮用的调色板——暗色判断与提亮算法。
 *
 * <p><b>为什么从 {@code ui.ThemeManager} 里抽出来</b>：高亮器家族
 * （{@link MtSyntaxHighlighter} / {@link PlaintextSyntaxHighlighter}）住在 {@code utils}，
 * 却要调 {@code ThemeManager.syntaxColor(color)} —— {@code utils} 因此反向依赖 {@code ui}。
 * 依赖方向定了就是 {@code ui → utils}，不能为了一个「把颜色提亮」的函数倒过来。</p>
 *
 * <p>真正与主题相关的只有一件事：现在是不是暗色。所以这里只保留一个 volatile 标记，
 * {@code ThemeManager.setDarkFlag} 在切换主题时同步过来；提亮算法（HSV 亮度下限、
 * 饱和度上限）也只有这一份，{@code ThemeManager} 不再自己写第二遍。</p>
 *
 * <p>不引用 {@code android.*} 以外的任何工程代码，纯算法，可桌面单测。</p>
 */
public final class SyntaxPalette {

    /** 当前是否暗色（由 {@code ThemeManager} 在切换主题时同步） */
    private static volatile boolean dark = false;

    private SyntaxPalette() {}

    public static void setDark(boolean d) {
        dark = d;
    }

    public static boolean isDark() {
        return dark;
    }

    /** 按当前主题适配一个语义色：亮色原样返回，暗色提亮 */
    public static int adapt(int color) {
        return dark ? lighten(color) : color;
    }

    /**
     * 把颜色整体提亮（HSV 亮度至少 62%，饱和度上限 72%）。
     *
     * <p>为什么不是简单加个 alpha：高亮色是「深蓝/深绿」这类低亮度高饱和色，
     * 在深色底上对比度不足；直接调亮度会把红色变粉、蓝色变灰，
     * 所以同时压一下饱和度。</p>
     */
    public static int lighten(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.62f) hsv[2] = 0.62f;
        if (hsv[1] > 0.72f) hsv[1] = 0.72f;
        return android.graphics.Color.HSVToColor(hsv);
    }
}
