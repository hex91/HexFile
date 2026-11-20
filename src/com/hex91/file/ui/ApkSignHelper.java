package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ApkSignerTool;


import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.67.4：自动签名的统一入口（Dex 编辑器 / Manifest 编辑器 / ARSC 编辑器共用一套设置）。
 *
 * <p>设置持久化在 {@code dexpp_sign}：{@code ks_mode}(0=内置 1=已导入)、{@code key_id}、
 * {@code scheme}（见 {@link #SCHEMES}）。长按“自动签名”复选框进入 {@link #showSettings}。</p>
 */
public final class ApkSignHelper {

    public static final String PREFS = "dexpp_sign";
    /**
     * 方案下拉的显示名。下标与 {@link #schemeFlags(int)} 一一对应，不能改顺序。
     * v2.70.0：只有第 0 项带推荐字样需要翻译，其余是纯 ASCII 的 v1/v2/v3 组合，直接留字面量。
     */
    private static String[] schemeLabels(Context c) {
        return new String[]{c.getString(R.string.ash_scheme_default),
                "v1", "v2", "v3", "v1+v2", "v2+v3"};
    }

    private ApkSignHelper() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 复选框：默认选中；长按进入签名设置 */
    public static CheckBox addAutoSignCheckBox(final Activity a, LinearLayout box,
            String text, boolean checked) {
        final CheckBox cb = new CheckBox(a);
        cb.setText(text);
        cb.setTextSize(14);
        cb.setChecked(checked);
        cb.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showSettings(a);
                return true;
            }
        });
        box.addView(cb);
        return cb;
    }

    /** 签名方案下标 → v1/v2/v3 开关 */
    public static boolean[] schemeFlags(int pos) {
        switch (pos) {
            case 1: return new boolean[]{true, false, false};
            case 2: return new boolean[]{false, true, false};
            case 3: return new boolean[]{false, false, true};
            case 4: return new boolean[]{true, true, false};
            case 5: return new boolean[]{false, true, true};
            default: return new boolean[]{true, true, true};
        }
    }

    /** 按已保存设置原地签名 apk（临时文件 → 原子覆盖） */
    public static void signInPlace(Context ctx, File apk) throws Exception {
        SharedPreferences sp = prefs(ctx);
        boolean[] f = schemeFlags(sp.getInt("scheme", 0));
        File tmp = new File(apk.getParentFile(), apk.getName() + ".signtmp");
        if (tmp.exists()) tmp.delete();
        if (sp.getInt("ks_mode", 0) == 1) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.defaultEntry(ctx);
            if (e == null) throw new IllegalStateException(
                    ctx.getString(R.string.ash_custom_gone));
            if (e.broken()) throw new IllegalStateException(
                    ctx.getString(R.string.sf_broken_reimport));
            byte[] ksBytes = SignKeyManagerActivity.loadKeyBytes(ctx, e.id);
            ApkSignerTool.signAutoAlias(ksBytes,
                    e.storePass.toCharArray(),
                    e.keyPass != null ? e.keyPass.toCharArray() : null,
                    e.alias, apk, tmp, 21, f[0], f[1], f[2]);
        } else {
            java.io.InputStream ks = ctx.getAssets().open("hexfile.p12");
            try {
                ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, tmp, 21, f[0], f[1], f[2]);
            } finally {
                ks.close();
            }
        }
        ApkEditSession.safeReplace(tmp, apk);
        ApkEditSession.markSigned(apk);
    }

    /** 签名设置弹窗：密钥（内置 / 已导入签名，点选更换）+ 签名方案 */
    public static void showSettings(final Activity a) {
        final SharedPreferences sp = prefs(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        float den = a.getResources().getDisplayMetrics().density;

        TextView l1 = new TextView(a);
        l1.setText(R.string.ash_key_label);
        l1.setTextSize(13);
        l1.setTextColor(ThemeManager.textSub(a));
        box.addView(l1);

        final TextView ksSel = new TextView(a);
        ksSel.setTextSize(15);
        ksSel.setTextColor(ThemeManager.accent(a));
        ksSel.setPadding(0, (int) (6 * den), 0, (int) (10 * den));
        ksSel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showKeyPicker(a, sp, ksSel); }
        });
        box.addView(ksSel);
        updateKeySelText(a, sp, ksSel);

        TextView l2 = new TextView(a);
        l2.setText(R.string.ash_scheme_label);
        l2.setTextSize(13);
        l2.setTextColor(ThemeManager.textSub(a));
        l2.setPadding(0, (int) (10 * den), 0, 0);
        box.addView(l2);
        final Spinner scheme = new Spinner(a);
        scheme.setAdapter(new ArrayAdapter<String>(a,
                android.R.layout.simple_spinner_dropdown_item, schemeLabels(a)));
        scheme.setSelection(sp.getInt("scheme", 0));
        box.addView(scheme);

        new HfDialogBuilder(a)
                .setTitle(R.string.ash_title)
                .setView(box)
                .setPositiveButton(R.string.common_confirm, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt("scheme", scheme.getSelectedItemPosition()).apply();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private static void updateKeySelText(Context ctx, SharedPreferences sp, TextView tv) {
        if (sp.getInt("ks_mode", 0) == 1) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.defaultEntry(ctx);
            tv.setText(e == null ? ctx.getString(R.string.ash_custom_gone_short)
                    : e.broken() ? ctx.getString(R.string.ash_custom_broken_short)
                    : ctx.getString(R.string.ash_custom_named, e.name));
        } else {
            tv.setText(R.string.ash_builtin);
        }
    }

    /** 密钥选择列表：内置 / 已导入签名（★默认）/ 管理签名… */
    private static void showKeyPicker(final Activity a, final SharedPreferences sp,
            final TextView ksSel) {
        final List<SignKeyManagerActivity.KeyEntry> keys =
                new ArrayList<SignKeyManagerActivity.KeyEntry>();
        File dir = new File(a.getFilesDir(), "signkeys");
        File[] fs = dir.listFiles();
        List<String> ids = new ArrayList<String>();
        if (fs != null) for (File f : fs)
            if (f.getName().endsWith(".meta"))
                ids.add(f.getName().substring(0, f.getName().length() - 5));
        java.util.Collections.sort(ids);
        String defId = sp.getString("key_id", null);
        if (defId != null && ids.remove(defId)) ids.add(0, defId);
        for (String id : ids) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.loadEntry(a, id);
            if (e != null) keys.add(e);
        }

        List<String> items = new ArrayList<String>();
        items.add(a.getString(R.string.ash_builtin_bare));
        String defId2 = sp.getString("key_id", null);
        for (SignKeyManagerActivity.KeyEntry e : keys)
            items.add((e.id.equals(defId2) ? "★ " : "") + e.name
                    + (e.broken() ? a.getString(R.string.ash_broken_tag)
                                  : (e.keyCount > 0 ? a.getString(R.string.sf_key_count, e.keyCount) : "")));
        items.add(a.getString(R.string.sf_manage));

        new HfDialogBuilder(a)
                .setTitle(R.string.ash_pick_title)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            sp.edit().putInt("ks_mode", 0).remove("key_id").apply();
                        } else if (which == items.size() - 1) {
                            a.startActivity(new Intent(a, SignKeyManagerActivity.class));
                        } else {
                            SignKeyManagerActivity.KeyEntry e = keys.get(which - 1);
                            if (e.broken()) {
                                new HfDialogBuilder(a)
                                        .setTitle(e.name)
                                        .setMessage(R.string.ash_broken_msg)
                                        .setPositiveButton(R.string.ash_go_manage,
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, int w) {
                                                        a.startActivity(new Intent(a,
                                                                SignKeyManagerActivity.class));
                                                    }
                                                })
                                        .setNegativeButton(R.string.common_cancel, null)
                                        .show();
                                return;
                            }
                            sp.edit().putInt("ks_mode", 1).putString("key_id", e.id).apply();
                        }
                        updateKeySelText(a, sp, ksSel);
                    }
                })
                .show();
    }
}
