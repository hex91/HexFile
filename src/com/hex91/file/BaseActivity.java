package com.hex91.file;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;

/**
 * v1.48.0 主题基类 —— 所有 Activity 继承本类以统一应用主题。
 *
 * 关键点：Activity.setTheme() 必须在 setContentView() 之前调用，
 * 因此在 onCreate 开头（super.onCreate 之后、setContentView 之前）调用 ThemeManager.apply()。
 *
 * 两处需要重建界面的场景：
 *  1) 系统深色模式切换（跟随系统时）会触发 onConfigurationChanged —— 直接 recreate()；
 *  2) 主题在别处被修改（本页处于后台时用户在主题设置页改了配色/明暗模式），
 *     返回本页时 onResume 检测到当前套用的 style 已过期 —— 同样 recreate()。
 *     否则页面会停留在旧主题上，因为 setTheme 只在 onCreate 生效。
 */
public abstract class BaseActivity extends Activity {

    /** 本次创建时实际套用的 style，用于判断主题是否已被外部修改 */
    private int appliedStyle;

    /** 本次创建时系统深浅色（uiMode night 位），用于判断系统深浅色是否真的发生翻转 */
    private int appliedNight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题必须在 setContentView 之前设定
        appliedStyle = ThemeManager.apply(this);
        appliedNight = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题在本页存活期间被改过（如从主题设置页返回）→ 重建以套用新样式
        int cur = ThemeManager.styleRes(this);
        if (cur != appliedStyle && shouldRecreateOnThemeChange()) {
            Log.w("HexTheme", "onResume style changed applied=" + appliedStyle + " cur=" + cur + " -> recreate");
            appliedStyle = cur; // 收敛：避免 recreate 后二次命中导致死循环
            recreate();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 仅当「跟随系统主题」且系统深浅色真的发生翻转时才重建界面。
        // 软键盘弹出（keyboard/keyboardHidden 变更）、屏幕旋转、导航栏变化等都
        // 不应触发重建 —— 否则终端会话会被反复销毁（重建会杀掉 shell）。
        if (!shouldRecreateOnThemeChange()) return;
        boolean follow = ThemeManager.mode(this) == ThemeManager.MODE_FOLLOW_SYSTEM;
        int newNight = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (follow && newNight != appliedNight) {
            Log.w("HexTheme", "onConfigurationChanged night flip " + appliedNight + "->" + newNight + " -> recreate");
            appliedNight = newNight;
            appliedStyle = ThemeManager.styleRes(this);
            recreate();
        }
    }

    /**
     * 是否允许因主题/深浅色变化而重建 Activity。
     * 默认 true；终端等需要保持运行会话（shell 进程）存活的界面应返回 false，
     * 避免重建导致会话被杀。这类界面通常自带固定配色（如终端近黑底），
     * 不依赖应用明暗主题，重建亦无必要。
     */
    protected boolean shouldRecreateOnThemeChange() { return true; }
}
