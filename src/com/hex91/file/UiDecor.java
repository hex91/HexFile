package com.hex91.file;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.75.0 统一的「圆角 + 半透明 + 主题色」视觉组件。
 *
 * <p>背景：本应用没有 AndroidX，MaterialButton / Chip / BottomSheet 都用不了，
 * 之前各页面要么直接用系统 {@code Button}（方角、灰底、与主题无关），要么把操作写成
 * 一行可点击的纯文字（看不出可点）。这里把两件事收敛成一处：</p>
 * <ul>
 *   <li>{@link #pill} / {@link #action} / {@link #iconBtn}：胶囊与图标按钮，强调色半透明底
 *       + 同色描边 + 水波纹按下态，明暗主题自动跟随；</li>
 *   <li>{@link #card} / {@link #round}：卡片底与任意圆角矩形，供列表行、分组块复用。</li>
 * </ul>
 *
 * <p>颜色混合全部用位运算实现（不依赖 {@code android.graphics.Color}），
 * 因此 {@link #alpha} 可以在桌面 JVM 上直接单测。</p>
 */
public final class UiDecor {

    private UiDecor() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 纯函数（可单测）

    /**
     * 给颜色套一个透明度。输入 0~255，越界自动夹取；
     * 输出保留 RGB、替换 alpha 通道。
     */
    public static int alpha(int color, int a) {
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 取颜色的亮度（0~255），用于决定叠加色该用黑还是白 */
    public static int luma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /**
     * 在给定底色上叠一层「更亮或更暗」的同色系，用于卡片底：
     * 亮色主题往白里提，暗色主题往黑里压，就不会出现「暗色主题下卡片反而更亮」。
     */
    public static int blend(int base, int overlay, int percent) {
        if (percent <= 0) return base;
        if (percent > 100) percent = 100;
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (overlay >> 16) & 0xFF, og = (overlay >> 8) & 0xFF, ob = overlay & 0xFF;
        int r = br + (or - br) * percent / 100;
        int g = bg + (og - bg) * percent / 100;
        int b = bb + (ob - bb) * percent / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- 背景

    /** 圆角矩形；strokeDp <= 0 时不描边 */
    static GradientDrawable round(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0f) g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    /** 卡片底：主背景向 surface 方向叠一点，描边用半透明分隔色，暗色下不会发白 */
    static GradientDrawable cardBg(Context c) {
        int bg = ThemeManager.bg(c);
        int sf = ThemeManager.surface(c);
        int fill = blend(bg, sf, ThemeManager.isDark(c) ? 55 : 70);
        return round(c, fill, alpha(ThemeManager.divider(c), 0x88), 0.6f, 12f);
    }

    /** 套一层原生 ripple 按下态（API 21+ 自带，无需 selector XML） */
    static Drawable pressable(GradientDrawable bg, int rippleColor) {
        try {
            return new RippleDrawable(ColorStateList.valueOf(alpha(rippleColor, 0x40)), bg, null);
        } catch (Throwable t) {
            return bg;
        }
    }

    /**
     * 「半透明强调色」按钮底：fill 为强调色 14% 填充 + 45% 描边，
     * 实心按钮则是强调色 88% 填充、无描边。
     */
    static Drawable pillBg(Context c, boolean primary) {
        int accent = ThemeManager.accent(c);
        GradientDrawable g = primary
                ? round(c, alpha(accent, 0xE6), 0, 0f, 10f)
                : round(c, alpha(accent, 0x24), alpha(accent, 0x66), 0.8f, 10f);
        return pressable(g, primary ? 0xFFFFFFFF : accent);
    }

    // ---------------------------------------------------------------- 控件

    /** 胶囊按钮：icon 可空（无图标就只显示文字） */
    static TextView pill(Context c, String icon, String label, boolean primary) {
        TextView tv = new TextView(c);
        tv.setText(icon == null || icon.length() == 0 ? label : icon + "  " + label);
        tv.setTextSize(12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
        paintPill(tv, c, primary);
        return tv;
    }

    /** 给已有 TextView 套胶囊外观（用于 XML 里的按钮与状态切换） */
    static void paintPill(TextView tv, Context c, boolean primary) {
        // XML 里的 TextView 默认没有内边距，直接套底会把字贴到描边上
        if (tv.getPaddingLeft() == 0 && tv.getPaddingRight() == 0) {
            tv.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
        }
        tv.setBackground(pillBg(c, primary));
        tv.setTextColor(primary ? ThemeManager.onAccent(c) : ThemeManager.accent(c));
    }

    /** 行内小操作：图标 + 短文字，尺寸比 pill 更小，用于列表每行右侧 */
    static TextView action(Context c, String icon, String label, int color) {
        TextView tv = new TextView(c);
        tv.setText(icon == null || icon.length() == 0 ? label : icon + " " + label);
        tv.setTextSize(11.5f);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 9), dp(c, 6), dp(c, 9), dp(c, 6));
        GradientDrawable g = round(c, alpha(color, 0x1F), alpha(color, 0x55), 0.7f, 8f);
        tv.setBackground(pressable(g, color));
        tv.setTextColor(color);
        return tv;
    }

    /**
     * 分页标签：选中 = 强调色 18% 底 + 同色描边 + 强调色文字（略加粗）；
     * 未选 = 全透明底 + 次级文字。只换背景和字色，不重建视图。
     */
    static void paintTab(TextView tv, Context c, boolean on) {
        int accent = ThemeManager.accent(c);
        GradientDrawable g = on
                ? round(c, alpha(accent, 0x22), alpha(accent, 0x55), 0.8f, 9f)
                : round(c, 0x00000000, 0x00000000, 0f, 9f);
        tv.setBackground(pressable(g, accent));
        tv.setTextColor(on ? accent : ThemeManager.textSub(c));
        tv.getPaint().setFakeBoldText(on);
        tv.invalidate();
    }

    /** 纯图标方形按钮（顶栏用），38dp 视觉、可点的半透明底 */
    static TextView iconBtn(Context c, String icon, int color) {
        TextView tv = new TextView(c);
        tv.setText(icon);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        int side = dp(c, 38);
        GradientDrawable g = round(c, alpha(color, 0x1A), alpha(color, 0x3D), 0.7f, 10f);
        tv.setBackground(pressable(g, color));
        tv.setTextColor(color);
        tv.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        return tv;
    }

    /** 竖向卡片容器（圆角 + 内边距），用来包一组操作 */
    static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 12);
        box.setPadding(p, p, p, p);
        box.setBackground(cardBg(c));
        return box;
    }

    /** 分组标题（小号、强调色、字距略大） */
    static TextView sectionTitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTextColor(alpha(ThemeManager.textSub(c), 0xEE));
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(c, 4), dp(c, 10), 0, dp(c, 4));
        return tv;
    }

    /** 一像素（或 0.6dp）半透明分隔线 */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(alpha(ThemeManager.divider(c), 0xB0));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }
}
