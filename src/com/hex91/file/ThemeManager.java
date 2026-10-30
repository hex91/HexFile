package com.hex91.file;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.Window;

/**
 * v1.48.0 主题管理器 —— 运行时多主题 + 明暗模式引擎。
 *
 * 设计要点（无 AndroidX 环境下的实现）：
 *  - 主题 = 「色板索引」（22 套 MT 配色）× 「明暗模式」（亮/暗/跟随系统）
 *  - 每种组合对应 res/values/themes.xml 中的一个 style：
 *      Theme_Hex_N      = 色板 N + 亮色
 *      Theme_Hex_Dark_N = 色板 N + 暗色
 *  - 切换方式：Activity.setTheme(styleId) 必须在 setContentView 之前调用，
 *    因此统一由 BaseActivity.onCreate() 调用 ThemeManager.apply(activity) 完成。
 *  - 布局统一用 ?attr/hfXxx 引用语义色，切换主题后自动生效，无需重建视图。
 *
 * 跟随系统（MODE_FOLLOW_SYSTEM）：读取 Configuration.uiMode 的 UI_MODE_NIGHT_MASK，
 * 系统开启深色时自动使用暗色 style；系统在运行时切换深色模式会触发
 * Activity 的 onConfigurationChanged（已在 Manifest 声明 uiMode），据此重建界面。
 */
public final class ThemeManager {

    private ThemeManager() {
    }

    /** 明暗模式：跟随系统 */
    public static final int MODE_FOLLOW_SYSTEM = 0;
    /** 明暗模式：始终亮色 */
    public static final int MODE_LIGHT = 1;
    /** 明暗模式：始终暗色 */
    public static final int MODE_DARK = 2;

    private static final String PREF_NAME = "hexfile_theme";
    private static final String KEY_SCHEME = "scheme";
    private static final String KEY_MODE = "mode";

    /** 默认色板：MT 默认蓝 */
    public static final int DEFAULT_SCHEME = 0;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 当前色板索引（0..21） */
    public static int scheme(Context c) {
        int v = prefs(c).getInt(KEY_SCHEME, DEFAULT_SCHEME);
        if (v < 0 || v >= ThemeSchemes.count()) v = DEFAULT_SCHEME;
        return v;
    }

    /** 当前明暗模式（跟随系统/亮/暗） */
    public static int mode(Context c) {
        return prefs(c).getInt(KEY_MODE, MODE_FOLLOW_SYSTEM);
    }

    /** 保存主题设置 */
    public static void save(Context c, int scheme, int mode) {
        prefs(c).edit()
                .putInt(KEY_SCHEME, Math.max(0, Math.min(ThemeSchemes.count() - 1, scheme)))
                .putInt(KEY_MODE, mode)
                .apply();
    }

    /**
     * 系统当前是否为深色模式。
     * API 29+ 系统深色开关会写进 Configuration.uiMode；更低版本系统无全局深色开关，返回 false。
     */
    public static boolean isSystemDark(Context c) {
        try {
            Configuration cfg = c.getResources().getConfiguration();
            int mask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mask == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前是否为暗色模式的全局标记。
     * 语法高亮器等无 Context 的静态工具类靠它决定配色（暗色下需提亮）。
     */
    private static volatile boolean sDark = false;

    public static void setDarkFlag(boolean dark) {
        sDark = dark;
    }

    public static boolean darkFlag() {
        return sDark;
    }

    /** 生效的实际模式：跟随系统时按系统当前状态解析 */
    public static int effectiveMode(Context c) {
        int m = mode(c);
        if (m == MODE_FOLLOW_SYSTEM) return isSystemDark(c) ? MODE_DARK : MODE_LIGHT;
        return m;
    }

    /** 22 套亮色 style（索引与 ThemeSchemes 一致） */
    private static final int[] LIGHT_STYLES = {
            R.style.Theme_Hex_0, R.style.Theme_Hex_1, R.style.Theme_Hex_2,
            R.style.Theme_Hex_3, R.style.Theme_Hex_4, R.style.Theme_Hex_5,
            R.style.Theme_Hex_6, R.style.Theme_Hex_7, R.style.Theme_Hex_8,
            R.style.Theme_Hex_9, R.style.Theme_Hex_10, R.style.Theme_Hex_11,
            R.style.Theme_Hex_12, R.style.Theme_Hex_13, R.style.Theme_Hex_14,
            R.style.Theme_Hex_15, R.style.Theme_Hex_16, R.style.Theme_Hex_17,
            R.style.Theme_Hex_18, R.style.Theme_Hex_19, R.style.Theme_Hex_20,
            R.style.Theme_Hex_21
    };

    /** 22 套暗色 style */
    private static final int[] DARK_STYLES = {
            R.style.Theme_Hex_Dark_0, R.style.Theme_Hex_Dark_1, R.style.Theme_Hex_Dark_2,
            R.style.Theme_Hex_Dark_3, R.style.Theme_Hex_Dark_4, R.style.Theme_Hex_Dark_5,
            R.style.Theme_Hex_Dark_6, R.style.Theme_Hex_Dark_7, R.style.Theme_Hex_Dark_8,
            R.style.Theme_Hex_Dark_9, R.style.Theme_Hex_Dark_10, R.style.Theme_Hex_Dark_11,
            R.style.Theme_Hex_Dark_12, R.style.Theme_Hex_Dark_13, R.style.Theme_Hex_Dark_14,
            R.style.Theme_Hex_Dark_15, R.style.Theme_Hex_Dark_16, R.style.Theme_Hex_Dark_17,
            R.style.Theme_Hex_Dark_18, R.style.Theme_Hex_Dark_19, R.style.Theme_Hex_Dark_20,
            R.style.Theme_Hex_Dark_21
    };

    /** 当前生效的 style 资源 id */
    public static int styleRes(Context c) {
        int s = scheme(c);
        boolean dark = effectiveMode(c) == MODE_DARK;
        int[] table = dark ? DARK_STYLES : LIGHT_STYLES;
        if (s < 0 || s >= table.length) s = DEFAULT_SCHEME;
        return table[s];
    }

    /**
     * 应用主题到 Activity（必须在 setContentView 之前调用）。
     * 同时设置状态栏/导航栏颜色，兼容无 android:statusBarColor 的老系统。
     *
     * @return 实际套用的 style 资源 id（供 BaseActivity 判断主题是否已变更）
     */
    public static int apply(Activity a) {
        int style = styleRes(a);
        a.setTheme(style);
        // 记录暗色标记，供无 Context 的语法高亮器读取配色
        setDarkFlag(effectiveMode(a) == MODE_DARK);
        // 系统主题切换后窗口属性需刷新
        try {
            Window w = a.getWindow();
            w.setStatusBarColor(attrColor(a, R.attr.hfStatusBar, 0xFF212121));
            w.setNavigationBarColor(attrColor(a, R.attr.hfNavBar, 0xFF212121));
        } catch (Throwable ignored) {
        }
        return style;
    }

    /** 从当前主题读取语义色 attr */
    public static int attrColor(Context c, int attrId, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attrId, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) {
                    return c.getResources().getColor(tv.resourceId);
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    // ---------- 语义色快捷取值 ----------

    public static int bg(Context c) {
        return attrColor(c, R.attr.hfBg, 0xFFFFFFFF);
    }

    public static int surface(Context c) {
        return attrColor(c, R.attr.hfSurface, 0xFFFFFFFF);
    }

    public static int surfaceAlt(Context c) {
        return attrColor(c, R.attr.hfSurfaceAlt, 0xFFF5F5F5);
    }

    public static int text(Context c) {
        return attrColor(c, R.attr.hfText, 0xFF212121);
    }

    public static int textSub(Context c) {
        return attrColor(c, R.attr.hfTextSub, 0xFF757575);
    }

    public static int divider(Context c) {
        return attrColor(c, R.attr.hfDivider, 0xFFE0E0E0);
    }

    public static int topBar(Context c) {
        return attrColor(c, R.attr.hfTopBar, 0xFF212121);
    }

    public static int topBarText(Context c) {
        return attrColor(c, R.attr.hfTopBarText, 0xFFFFFFFF);
    }

    public static int accent(Context c) {
        return attrColor(c, R.attr.hfAccent, 0xFF1E88E5);
    }

    public static int onAccent(Context c) {
        return attrColor(c, R.attr.hfOnAccent, 0xFFFFFFFF);
    }

    public static int selected(Context c) {
        return attrColor(c, R.attr.hfSelected, 0xFFBBDEFB);
    }

    /** 成功/校验通过色（暗色下提亮以保证对比度） */
    public static int ok(Context c) {
        return statusColor(c, 0xFF2E7D32, 0xFF81C784);
    }

    /** 失败/错误色（暗色下提亮以保证对比度） */
    public static int err(Context c) {
        return statusColor(c, 0xFFC62828, 0xFFEF9A9A);
    }

    /** 状态色：亮色用深色系，暗色用浅色系 */
    private static int statusColor(Context c, int light, int dark) {
        return isDark(c) ? dark : light;
    }

    /**
     * 语法高亮等「彩色语义」在暗色背景下需要整体提亮，
     * 否则深色系高亮色在深色底上几乎不可读。
     */
    public static int syntaxColor(Context c, int color) {
        if (!isDark(c)) return color;
        return lighten(color);
    }

    /** 无 Context 版本的语法色适配（供静态高亮器使用） */
    public static int syntaxColor(int color) {
        if (!sDark) return color;
        return lighten(color);
    }

    /** 把颜色整体提亮（HSV 亮度至少 62%，饱和度上限 72%） */
    private static int lighten(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.62f) hsv[2] = 0.62f;
        if (hsv[1] > 0.72f) hsv[1] = 0.72f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** 是否处于暗色模式（用于需要程序化判断的场景，如语法高亮配色） */
    public static boolean isDark(Context c) {
        return effectiveMode(c) == MODE_DARK;
    }
}
