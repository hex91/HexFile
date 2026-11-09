package com.hex91.file;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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

    /**
     * 工具条底（多选栏、编辑器底栏）：surfaceAlt + 半透明描边 + 14dp 圆角。
     * 用它把一整排按钮框成一块「浮起的操作区」，而不是贴在页面底部的一条灰带。
     */
    static Drawable barBg(Context c) {
        return round(c, ThemeManager.surfaceAlt(c),
                alpha(ThemeManager.divider(c), 0xCC), 0.8f, 14f);
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
     * 按键底（编辑器符号键、Hex 工具条小胶囊）：中性半透明 + 细描边。
     * 不用强调色，是因为这些键一排几十个，全上色会盖过正文。
     */
    static Drawable keyBg(Context c) {
        return pressable(round(c, ThemeManager.surfaceAlt(c),
                alpha(ThemeManager.divider(c), 0x99), 0.6f, 9f), ThemeManager.accent(c));
    }

    // ---------------------------------------------------------------- 按钮语义

    /** 主操作：实心强调色，一屏只该有一个 */
    public static final int KIND_PRIMARY = 0;
    /** 次操作：强调色 14% 底 + 同色描边 */
    public static final int KIND_SOFT = 1;
    /** 危险操作（删除/清空）：红色系 */
    public static final int KIND_DANGER = 2;
    /** 更轻的次操作：无底色、只有强调色文字，用于极密集的按钮栏 */
    public static final int KIND_TEXT = 3;

    /** 每种语义对应的基色 */
    static int kindColor(Context c, int kind) {
        return kind == KIND_DANGER ? ThemeManager.err(c) : ThemeManager.accent(c);
    }

    static Drawable pillBg(Context c, int kind) {
        int accent = kindColor(c, kind);
        if (kind == KIND_PRIMARY) {
            return pressable(round(c, alpha(accent, 0xE6), 0, 0f, 10f), 0xFFFFFFFF);
        }
        if (kind == KIND_TEXT) {
            return pressable(round(c, 0x00000000, 0x00000000, 0f, 10f), accent);
        }
        return pressable(round(c, alpha(accent, 0x24), alpha(accent, 0x66), 0.8f, 10f), accent);
    }

    /**
     * 「半透明强调色」按钮底：fill 为强调色 14% 填充 + 45% 描边，
     * 实心按钮则是强调色 88% 填充、无描边。
     */
    static Drawable pillBg(Context c, boolean primary) {
        return pillBg(c, primary ? KIND_PRIMARY : KIND_SOFT);
    }

    // ---------------------------------------------------------------- 控件

    /** 胶囊按钮：icon 可空（无图标就只显示文字） */
    static TextView pill(Context c, String icon, String label, boolean primary) {
        return pill(c, icon, label, primary ? KIND_PRIMARY : KIND_SOFT);
    }

    /** 胶囊按钮（按语义上色） */
    static TextView pill(Context c, String icon, String label, int kind) {
        TextView tv = new TextView(c);
        tv.setText(icon == null || icon.length() == 0 ? label : icon + "  " + label);
        tv.setTextSize(12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
        paintPill(tv, c, kind);
        return tv;
    }

    /** 给已有 TextView 套胶囊外观（用于 XML 里的按钮与状态切换） */
    static void paintPill(TextView tv, Context c, boolean primary) {
        paintPill(tv, c, primary ? KIND_PRIMARY : KIND_SOFT);
    }

    static void paintPill(TextView tv, Context c, int kind) {
        // XML 里的 TextView 默认没有内边距，直接套底会把字贴到描边上
        if (tv.getPaddingLeft() == 0 && tv.getPaddingRight() == 0) {
            tv.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
        }
        tv.setBackground(pillBg(c, kind));
        tv.setTextColor(kind == KIND_PRIMARY ? ThemeManager.onAccent(c) : kindColor(c, kind));
    }

    /**
     * 密集按钮栏用的小号胶囊（多选栏、编辑器底栏）。
     *
     * <p>与 {@link #paintPill} 的差别：这里要覆盖系统 {@code Button} 的默认外观——
     * 它自带 48dp 最小高度、88dp 最小宽度和按下抬升动画，不关掉的话
     * 圆角底会被撑成大方块、按下时整块浮起来。</p>
     */
    static void barButton(TextView tv, Context c, int kind, float textSp) {
        tv.setAllCaps(false);
        tv.setSingleLine(true);
        tv.setTextSize(textSp);
        tv.setMinWidth(0);
        tv.setMinimumWidth(0);
        tv.setMinHeight(0);
        tv.setMinimumHeight(0);
        tv.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        tv.setStateListAnimator(null);
        tv.setElevation(0f);
        tv.setBackground(pillBg(c, kind));
        tv.setTextColor(kind == KIND_PRIMARY ? ThemeManager.onAccent(c) : kindColor(c, kind));
    }

    /** 给按钮加左右间距（LinearLayout 才有 margin） */
    static void gap(TextView tv, Context c, float lrDp) {
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) lp;
            p.leftMargin = dp(c, lrDp);
            p.rightMargin = dp(c, lrDp);
            tv.setLayoutParams(p);
        }
    }

    // ---------------------------------------------------------------- 弹窗

    /**
     * 弹窗统一美化：18dp 圆角面板 + 主题表面色 + 胶囊按钮。
     *
     * <p>为什么改 windowBackground 而不是面板 View：系统 {@code AlertDialog} 的
     * 根布局（{@code AlertDialogLayout}）自己没有背景，白色圆角面板正是由
     * dialog 主题的 {@code windowBackground}（inset 16dp + shape）画的。
     * 所以只要把主题换成同结构的 drawable，就能不碰布局地换掉圆角与底色，
     * 16dp 的 inset 是留给投影的，不能省。</p>
     *
     * <p>全程 try-catch：弹窗是高频路径，任何一处 id 找不到都不该让业务崩掉。</p>
     */
    static void polishDialog(AlertDialog d) {
        if (d == null) return;
        try {
            Window w = d.getWindow();
            if (w == null) return;
            Context c = w.getContext();
            w.setBackgroundDrawableResource(ThemeManager.isDark(c)
                    ? R.drawable.hf_dialog_bg_dark : R.drawable.hf_dialog_bg_light);

            TextView title = viewById(w, idOf(c, "alertTitle"));
            if (title == null) title = viewById(w, android.R.id.title);
            if (title != null) title.setTextColor(ThemeManager.text(c));

            TextView msg = viewById(w, android.R.id.message);
            if (msg != null) msg.setTextColor(alpha(ThemeManager.text(c), 0xEE));

            // button1 = 肯定，做成实心；button2/3 = 否定/中性，做成描边
            styleDialogButton(w, c, android.R.id.button1, true);
            styleDialogButton(w, c, android.R.id.button2, false);
            styleDialogButton(w, c, android.R.id.button3, false);
        } catch (Throwable ignored) {
            // 不同 ROM 的 AlertDialog 内部 id 可能缺失，保持系统外观即可
        }
    }

    private static int idOf(Context c, String name) {
        try {
            return c.getResources().getIdentifier(name, "id", "android");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static TextView viewById(Window w, int id) {
        if (id == 0) return null;
        try {
            View v = w.findViewById(id);
            return v instanceof TextView ? (TextView) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void styleDialogButton(Window w, Context c, int id, boolean primary) {
        TextView tv = viewById(w, id);
        if (tv == null) return;
        int accent = ThemeManager.accent(c);
        tv.setAllCaps(false);
        tv.setMinWidth(0);
        tv.setMinimumWidth(0);
        tv.setMinHeight(0);
        tv.setMinimumHeight(0);
        tv.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        tv.setTextColor(primary ? ThemeManager.onAccent(c) : accent);
        tv.setBackground(pillBg(c, primary ? KIND_PRIMARY : KIND_SOFT));
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) lp;
            p.leftMargin = dp(c, 4);
            p.rightMargin = dp(c, 4);
            tv.setLayoutParams(p);
        }
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
