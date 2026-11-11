package com.hex91.file;

import android.app.Activity;
import android.content.Context;
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

    /**
     * v1.73.0：统一界面度量。系统字号被调大（国产 ROM 常见 1.15~1.30）时，
     * 手写 UI 的固定 dp 容器装不下放大的 SP 文字，表现为「有的机器字特别大」。
     * 这里把 fontScale 收敛到 [0.90, 1.10]，只改字体缩放、不改 density，
     * dp 布局与屏幕密度的对应关系保持不变。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiKit.alignMetrics(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题必须在 setContentView 之前设定
        appliedStyle = ThemeManager.apply(this);
        appliedNight = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // v1.83.0：工作目录与 Root 开关都是全局偏好，每个页面创建时收敛一次，
        // 保证用户从任意入口（通知、其他 App 跳转）直接进二级页也读到最新设置。
        WorkDir.init(this);
        RootUtil.init(this);
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
        // v1.95.0：字号被收敛过的页面，Resources 是 createConfigurationContext 出来的快照，
        // 系统不会替它更新 orientation / uiMode / screenWidthDp，必须自己同步一次。
        UiKit.syncConfig(this, newConfig);
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
