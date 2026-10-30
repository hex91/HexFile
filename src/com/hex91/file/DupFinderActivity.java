package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 重复文件查找（v1.59.0）：三阶段（大小→头4KB指纹→全量MD5）内容级查重。
 *  每组列出全部副本，默认勾选除第一个外的副本（保留路径最短的为正本），
 *  可改勾选后一键删除释放空间。
 */
public class DupFinderActivity extends BaseActivity {

    static final String EXTRA_ROOT = "root";

    private File root;
    private LinearLayout body;
    private ScrollView sv;
    private TextView summary;
    private List<DiskScanUtil.DupGroup> groups = new ArrayList<DiskScanUtil.DupGroup>();
    /** 各组已勾选待删的文件（组索引 → 勾选集合） */
    private List<Set<File>> checked = new ArrayList<Set<File>>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float den = getResources().getDisplayMetrics().density;
        root = new File(getIntent().getStringExtra(EXTRA_ROOT));

        LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        rootL.setBackgroundColor(ThemeManager.bg(this));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.topBar(this));
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) (12 * den), (int) (8 * den), (int) (12 * den), (int) (8 * den));
        summary = new TextView(this);
        summary.setText("扫描范围：" + root.getAbsolutePath());
        summary.setTextSize(14);
        summary.setTextColor(ThemeManager.topBarText(this));
        top.addView(summary);
        rootL.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部删除条
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(ThemeManager.surface(this));
        bottom.setPadding((int) (12 * den), (int) (6 * den), (int) (12 * den), (int) (6 * den));
        final Button del = new Button(this);
        del.setText("删除勾选项");
        del.setTextSize(13);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askDeleteChecked(); }
        });
        bottom.addView(del, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Button keepFirst = new Button(this);
        keepFirst.setText("恢复默认勾选");
        keepFirst.setTextSize(13);
        keepFirst.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { defaultChecks(); render(); }
        });
        bottom.addView(keepFirst, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(13);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bottom.addView(close, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * den);
        body.setPadding(p, p, p, p);
        sv.addView(body);
        rootL.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootL.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(rootL);

        startScan();
    }

    private void startScan() {
        final ProgressDialog pd = ProgressDialog.show(this, "查找重复文件",
                "三阶段：大小分组 → 快速指纹 → 全量校验", true, false);
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            List<DiskScanUtil.DupGroup> r = null;
            String err = null;
            public void run() {
                try {
                    r = DiskScanUtil.findDuplicates(root, 1024 * 1024, null);  // ≥1MB 才查
                } catch (Throwable t) { err = t.getMessage(); }
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (err != null) {
                            summary.setText("扫描失败: " + err);
                            return;
                        }
                        groups = r != null ? r : groups;
                        long waste = 0;
                        for (DiskScanUtil.DupGroup g : groups)
                            waste += g.size * (g.files.size() - 1);
                        summary.setText(root.getAbsolutePath() + "\n重复组 "
                                + groups.size() + " 组 · 可释放约 "
                                + FileOps.formatSize(waste) + " · 耗时 "
                                + (System.currentTimeMillis() - t0) / 1000 + "s");
                        checked.clear();
                        for (int i = 0; i < groups.size(); i++)
                            checked.add(new HashSet<File>());
                        defaultChecks();
                        render();
                    }
                });
            }
        }, "dup-scan").start();
    }

    /** 默认勾选：每组保留路径最短的为正本，其余勾选待删 */
    private void defaultChecks() {
        for (int i = 0; i < groups.size(); i++) {
            Set<File> set = checked.get(i);
            set.clear();
            DiskScanUtil.DupGroup g = groups.get(i);
            File keep = g.files.get(0);
            for (File f : g.files) if (f.getAbsolutePath().length() < keep.getAbsolutePath().length())
                keep = f;
            for (File f : g.files) if (!f.equals(keep)) set.add(f);
        }
    }

    private void render() {
        body.removeAllViews();
        float den = getResources().getDisplayMetrics().density;
        if (groups.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("未发现重复文件（仅检查 ≥1MB 的文件）");
            t.setTextSize(14);
            t.setTextColor(ThemeManager.textSub(this));
            body.addView(t);
            return;
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            final int g = gi;
            DiskScanUtil.DupGroup grp = groups.get(gi);
            TextView head = new TextView(this);
            head.setText("组 " + (gi + 1) + " · " + grp.files.size() + " 个副本 · 单个 "
                    + FileOps.formatSize(grp.size) + " · 勾选 " + checked.get(gi).size());
            head.setTextSize(13);
            head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            head.setTextColor(ThemeManager.accent(this));
            head.setPadding(0, (int) (16 * den), 0, (int) (4 * den));
            body.addView(head);
            for (final File f : grp.files) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) (6 * den), 0, (int) (6 * den));
                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                cb.setChecked(checked.get(gi).contains(f));
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                        if (c) checked.get(g).add(f); else checked.get(g).remove(f);
                    }
                });
                row.addView(cb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView path = new TextView(this);
                path.setText(f.getAbsolutePath().replace(root.getAbsolutePath(), "~"));
                path.setTextSize(12);
                path.setTextColor(ThemeManager.text(this));
                row.addView(path, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                body.addView(row);
            }
            View div = new View(this);
            div.setBackgroundColor(ThemeManager.divider(this));
            body.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.ceil(1 * den)));
        }
    }

    private void askDeleteChecked() {
        int n = 0;
        long bytes = 0;
        for (int i = 0; i < groups.size(); i++)
            for (File f : checked.get(i)) { n++; bytes += f.length(); }
        if (n == 0) { Toast.makeText(this, "未勾选任何文件", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
                .setTitle("删除勾选项")
                .setMessage("共 " + n + " 个文件，约 " + FileOps.formatSize(bytes)
                        + "\n\n确定删除？（不可恢复）")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { doDeleteChecked(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDeleteChecked() {
        int ok = 0, fail = 0;
        for (int i = 0; i < groups.size(); i++)
            for (File f : checked.get(i))
                if (f.delete()) ok++; else fail++;
        Toast.makeText(this, "已删除 " + ok + " 个" + (fail > 0 ? "，失败 " + fail + " 个" : ""),
                Toast.LENGTH_LONG).show();
        // 从组里移除已删文件，组剩 ≤1 的丢弃
        for (int i = groups.size() - 1; i >= 0; i--) {
            groups.get(i).files.removeAll(checked.get(i));
            if (groups.get(i).files.size() <= 1) { groups.remove(i); checked.remove(i); }
            else checked.set(i, new HashSet<File>());
        }
        long waste = 0;
        for (DiskScanUtil.DupGroup g : groups) waste += g.size * (g.files.size() - 1);
        summary.setText(root.getAbsolutePath() + "\n剩余重复组 " + groups.size()
                + " 组 · 可释放约 " + FileOps.formatSize(waste));
        render();
    }
}
