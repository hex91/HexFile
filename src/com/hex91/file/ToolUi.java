package com.hex91.file;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v1.70.0 工具箱的公共 UI 小工具：dp、卡片底、主题化 Spinner、复制、提示。
 *
 * <p>抽出来的原因：5 个工具页都要「圆角卡片 + 跟随主题的下拉框 + 点一下复制」，
 * 各写一遍容易出现选中态配色不一致。
 */
final class ToolUi {

    private ToolUi() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 圆角卡片背景（surfaceAlt 底 + 细描边） */
    static GradientDrawable card(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(ThemeManager.surfaceAlt(c));
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 0.6f)), ThemeManager.divider(c));
        return g;
    }

    /** 强调色按钮底 */
    static GradientDrawable accentBg(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(ThemeManager.accent(c));
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 主题化 Spinner 适配器：选中项与下拉项都跟随明暗主题，字号统一 */
    static ArrayAdapter<String> adapter(final Context c, final String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(ThemeManager.text(c));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                tv.setPadding(dp(c, 2), 0, dp(c, 2), 0);
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setTextColor(ThemeManager.text(c));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                tv.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
                return tv;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    static void copy(Context c, CharSequence text, String what) {
        if (text == null || text.length() == 0) {
            toast(c, "没有可复制的内容");
            return;
        }
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("hexfile", text));
        toast(c, what + "已复制（" + text.length() + " 字符）");
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /** 给 TextView 挂「点一下复制」，顺便把按下态做出来（无背景时用 alpha） */
    static void copyOnClick(final TextView tv, final Context c, final String what) {
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(c, tv.getText(), what);
            }
        });
    }

    /** 单选 chip 的视觉：选中 = 强调色底 + onAccent 字；未选 = 卡片底 + 主文字 */
    static void chipState(TextView chip, boolean on, Context c) {
        if (on) {
            chip.setBackground(accentBg(c, 8));
            chip.setTextColor(ThemeManager.onAccent(c));
        } else {
            chip.setBackground(card(c, 8));
            chip.setTextColor(ThemeManager.textSub(c));
        }
    }
}
