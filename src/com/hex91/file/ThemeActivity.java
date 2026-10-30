package com.hex91.file;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * v1.48.0 主题设置页 —— 对标 MT Manager 的「主题设置」界面。
 *
 * 版式（对标用户提供的 MT 截图）：
 *   - 顶部标题「主题设置」
 *   - 明暗模式：跟随系统 / 浅色 / 深色
 *   - 主题色列表：每行 = 单选按钮 + 一条该主题色的色条
 *   - 底部：取消 / 确定
 *
 * 交互：点击色板即时预览（当前 Activity 重建以套用新样式），
 *      确定后持久化并返回；取消则恢复进入前的设置。
 */
public class ThemeActivity extends BaseActivity {

    private LinearLayout schemeGroup;
    private RadioGroup modeGroup;

    /** 进入本页时的原始设置，取消时用于还原（static：重建页面后仍需保留） */
    private static int sOrigScheme = 0;
    private static int sOrigMode = ThemeManager.MODE_FOLLOW_SYSTEM;

    /** 本次选择（尚未确定） */
    private int pendingScheme;
    private int pendingMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);

        // 首次进入记录原始设置；重建（预览）后保留原值，供「取消」还原
        if (savedInstanceState == null) {
            sOrigScheme = ThemeManager.scheme(this);
            sOrigMode = ThemeManager.mode(this);
        }
        pendingScheme = ThemeManager.scheme(this);
        pendingMode = ThemeManager.mode(this);
        schemeGroup = (LinearLayout) findViewById(R.id.scheme_group);
        modeGroup = (RadioGroup) findViewById(R.id.mode_group);

        buildSchemes();
        syncModeSelection();

        // 明暗模式切换：即时预览
        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                pendingMode = modeOf(checkedId);
                preview();
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restoreAndFinish();
            }
        });

        findViewById(R.id.btn_ok).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ThemeManager.save(ThemeActivity.this, pendingScheme, pendingMode);
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private int modeOf(int checkedId) {
        if (checkedId == R.id.mode_light) return ThemeManager.MODE_LIGHT;
        if (checkedId == R.id.mode_dark) return ThemeManager.MODE_DARK;
        return ThemeManager.MODE_FOLLOW_SYSTEM;
    }

    /**
     * 还原到进入本页时的设置并退出。
     * 「取消」按钮与系统返回键共用此路径 —— 预览期间主题已写入偏好，
     * 若不还原，用户的临时选择会残留。
     */
    private void restoreAndFinish() {
        if (pendingScheme != sOrigScheme || pendingMode != sOrigMode) {
            ThemeManager.save(this, sOrigScheme, sOrigMode);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        restoreAndFinish();
    }

    private void syncModeSelection() {
        int id = R.id.mode_follow;
        if (pendingMode == ThemeManager.MODE_LIGHT) id = R.id.mode_light;
        else if (pendingMode == ThemeManager.MODE_DARK) id = R.id.mode_dark;
        modeGroup.check(id);
    }

    /**
     * 动态生成色板行：单选按钮 + 色条在同一行（按钮居左，色条占满右侧剩余宽度）。
     * 选中互斥不依赖 RadioGroup（嵌套的 RadioButton 它不管理），而是点击后
     * 立即预览重建列表，由 pendingScheme 决定唯一选中项。
     */
    private void buildSchemes() {
        schemeGroup.removeAllViews();
        int n = ThemeSchemes.count();
        for (int i = 0; i < n; i++) {
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(2), 0);

            RadioButton rb = new RadioButton(this);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    ThemeSchemes.COLORS[i]));
            rb.setChecked(i == pendingScheme);
            row.addView(rb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 色条：紧贴按钮右侧，占满整行剩余宽度
            View bar = new View(this);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(ThemeSchemes.COLORS[i]);
            bar.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(24), 1f);
            lp.setMargins(dp(8), dp(5), dp(18), dp(5));
            row.addView(bar, lp);

            // 按钮或色条任意位置点击都选择该主题
            View.OnClickListener pick = new View.OnClickListener() {
                public void onClick(View v) {
                    pendingScheme = idx;
                    preview();
                }
            };
            rb.setOnClickListener(pick);
            row.setOnClickListener(pick);

            schemeGroup.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 即时预览：重建当前页以套用新样式。
     * 预览值先落到偏好（供重建后 apply() 读取），但「确定」才视为最终选择，
     * 「取消」会还原到 sOrigScheme/sOrigMode。
     */
    private void preview() {
        ThemeManager.save(ThemeActivity.this, pendingScheme, pendingMode);
        recreate();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
