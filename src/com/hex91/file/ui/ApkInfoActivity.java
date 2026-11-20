package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.apk.ApkInfoUtil;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.WorkDir;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * APK 信息查看（v1.57.0）：包名/版本/SDK/权限/组件/ABI/DEX/签名一览，
 *  右上角可导出 txt 到 /sdcard/HexFile/APK信息/。全部只读解析。
 */
public class ApkInfoActivity extends BaseActivity {

    public static final String EXTRA_APK = "apk";
    private File apk;
    private ApkInfoUtil.Info info;

    private LinearLayout body;
    private static final float DEN = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float den = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setPadding((int) (12 * den), (int) (8 * den), (int) (12 * den), (int) (8 * den));
        TextView title = new TextView(this);
        title.setText(R.string.api_title);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        top.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView export = new TextView(this);
        export.setText(R.string.api_export_txt);
        export.setTextSize(14);
        export.setTextColor(ThemeManager.accent(this));
        export.setPadding((int) (14 * den), (int) (6 * den), (int) (14 * den), (int) (6 * den));
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportTxt(); }
        });
        top.addView(export);
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(ThemeManager.divider(this));
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) Math.ceil(1 * den)));

        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * den);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);

        apk = new File(getIntent().getStringExtra(EXTRA_APK));
        new Thread(new Runnable() {
            public void run() {
                ApkInfoUtil.Info i = null;
                String err = null;
                try { i = ApkInfoUtil.parse(apk); } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final ApkInfoUtil.Info fi = i;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            Toast.makeText(ApkInfoActivity.this,
                                    ApkInfoActivity.this.getString(R.string.api_parse_fail, ferr),
                                    Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            info = fi;
                            render();
                        }
                    }
                });
            }
        }, "apk-info").start();
    }

    private void render() {
        float den = getResources().getDisplayMetrics().density;
        body.removeAllViews();
        sec(getString(R.string.api_sec_basic));
        line(getString(R.string.api_lbl_file), apk.getName());
        line(getString(R.string.api_lbl_size), getString(R.string.api_size_val,
                FileOps.formatSize(info.fileSize), info.entryCount));
        line(getString(R.string.api_lbl_pkg), info.pkg);
        line(getString(R.string.api_lbl_version), info.versionName + " (" + info.versionCode + ")");
        String sdk = "min " + info.minSdk + " · target " + info.targetSdk;
        if (!info.compileSdk.isEmpty()) sdk += getString(R.string.api_sdk_compile, info.compileSdk);
        line("SDK", sdk);
        sec(getString(R.string.api_sec_sign));
        line(getString(R.string.api_lbl_scheme), info.signSchemes);
        sec(getString(R.string.api_sec_native));
        if (info.abis.isEmpty()) line(getString(R.string.api_lbl_abi), getString(R.string.api_none_lib));
        for (String abi : info.abis) line(getString(R.string.api_lbl_abi), abi);
        sec("DEX");
        line(getString(R.string.api_lbl_dex), String.valueOf(info.dexCount));
        if (info.mainDexMethods >= 0) line(getString(R.string.api_lbl_dex_methods),
                String.valueOf(info.mainDexMethods));
        comp("Activity", info.activities);
        comp("Service", info.services);
        comp("Receiver", info.receivers);
        comp("Provider", info.providers);
        sec(getString(R.string.api_sec_perms, info.permissions.size()));
        if (info.permissions.isEmpty()) line("", getString(R.string.api_none_perms));
        for (String p : info.permissions) line("", p);
    }

    // ---------- 视图构造 ----------

    private void sec(String title) {
        float den = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ThemeManager.accent(this));
        t.setPadding(0, (int) (16 * den), 0, (int) (6 * den));
        body.addView(t);
    }

    private void line(String k, String v) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView key = new TextView(this);
        key.setText(k.isEmpty() ? "·" : k);
        key.setTextSize(14);
        key.setTextColor(ThemeManager.textSub(this));
        key.setPadding(0, 0, (int) (10 * den), 0);
        row.addView(key, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                k.isEmpty() ? 0f : 0.28f));
        TextView val = new TextView(this);
        val.setText(v);
        val.setTextSize(14);
        val.setTextColor(ThemeManager.text(this));
        val.setTextIsSelectable(true);
        row.addView(val, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                k.isEmpty() ? 1f : 0.72f));
        body.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void comp(String title, java.util.List<String[]> list) {
        sec(getString(R.string.api_sec_count, title, list.size()));
        if (list.isEmpty()) line("", getString(R.string.api_none));
        for (String[] c : list) line("", c[0] + "\n" + c[1]);
    }

    private void exportTxt() {
        if (info == null) return;
        try {
            File dir = new File(WorkDir.base(), "APK信息");
            dir.mkdirs();
            String base = apk.getName();
            if (base.toLowerCase().endsWith(".apk")) base = base.substring(0, base.length() - 4);
            File out = new File(dir, base + "_info.txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(ApkInfoUtil.toText(info, apk).getBytes("UTF-8"));
            fo.close();
            Toast.makeText(this, getString(R.string.api_exported, out.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.api_export_fail, t.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
