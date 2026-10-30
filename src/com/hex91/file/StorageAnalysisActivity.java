package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 存储分析（v1.59.0）：目录占用树下钻 + 大文件 Top20 排行 + 重复文件查找入口。
 *  顶栏面包屑逐级返回；点子目录下钻，点文件弹操作（删除）。
 */
public class StorageAnalysisActivity extends BaseActivity {

    static final String EXTRA_ROOT = "root";

    private File current;
    private LinearLayout body;
    private TextView breadcrumb;
    private TextView summary;
    private ScrollView sv;
    private int scrollY = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float den = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // 顶栏：标题 + 面包屑 + 动作
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.topBar(this));
        top.setPadding((int) (12 * den), (int) (8 * den), (int) (12 * den), (int) (8 * den));
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("存储分析");
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.topBarText(this));
        row1.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dup = act("重复文件", den);
        dup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (current == null) return;
                startActivity(new android.content.Intent(StorageAnalysisActivity.this,
                        DupFinderActivity.class)
                        .putExtra(DupFinderActivity.EXTRA_ROOT, current.getAbsolutePath()));
            }
        });
        row1.addView(dup);
        TextView refresh = act("刷新", den);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (current != null) scan(current); }
        });
        row1.addView(refresh);
        top.addView(row1);
        breadcrumb = new TextView(this);
        breadcrumb.setTextSize(12);
        breadcrumb.setTextColor(ThemeManager.topBarText(this));
        breadcrumb.setSingleLine(true);
        breadcrumb.setPadding(0, (int) (4 * den), 0, 0);
        top.addView(breadcrumb);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = new TextView(this);
        summary.setTextSize(13);
        summary.setTextColor(ThemeManager.textSub(this));
        int p = (int) (12 * den);
        summary.setPadding(p, (int) (8 * den), p, (int) (4 * den));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(p, 0, p, p);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String r = getIntent().getStringExtra(EXTRA_ROOT);
        current = r != null ? new File(r)
                : android.os.Environment.getExternalStorageDirectory();
        scan(current);
    }

    private TextView act(String text, float den) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(ThemeManager.accent(this));
        t.setPadding((int) (12 * den), (int) (6 * den), 0, (int) (6 * den));
        return t;
    }

    /** 后台扫描当前目录：总大小 + 子目录占用 + 大文件 Top20 */
    private void scan(final File dir) {
        final ProgressDialog pd = ProgressDialog.show(this, "扫描中", dir.getName(), true, false);
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            List<DiskScanUtil.DirStat> subDirs = new java.util.ArrayList<DiskScanUtil.DirStat>();
            List<DiskScanUtil.FileSize> top;
            long total; int files;
            public void run() {
                try {
                    total = DiskScanUtil.scanSize(dir, new DiskScanUtil.DirVisitor() {
                        public void onDir(File d, long size, int fc) {
                            if (!d.equals(dir)) {
                                DiskScanUtil.DirStat s = new DiskScanUtil.DirStat();
                                s.dir = d; s.size = size; s.fileCount = fc;
                                subDirs.add(s);
                            }
                        }
                    });
                    // 子目录占用降序
                    java.util.Collections.sort(subDirs, new java.util.Comparator<DiskScanUtil.DirStat>() {
                        public int compare(DiskScanUtil.DirStat a, DiskScanUtil.DirStat b) {
                            return Long.compare(b.size, a.size);
                        }
                    });
                    top = DiskScanUtil.topFiles(dir, 20, null);
                } catch (Throwable t) { /* 扫描失败显示空 */ }
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        long cost = System.currentTimeMillis() - t0;
                        render(dir, total, files, subDirs, top, cost);
                    }
                });
            }
        }, "disk-scan").start();
    }

    private void render(File dir, long total, int files,
            List<DiskScanUtil.DirStat> subDirs, List<DiskScanUtil.FileSize> top, long costMs) {
        current = dir;
        scrollY = sv.getScrollY();
        body.removeAllViews();
        float den = getResources().getDisplayMetrics().density;
        breadcrumb.setText(dir.getAbsolutePath());

        // 面包屑返回上级
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            body.addView(dirRow("⬆️ ..（上一级）", "", 0, 0, parent, true));
        }
        summary.setText("共 " + FileOps.formatSize(total) + " · 子目录 "
                + subDirs.size() + " 个 · 扫描 " + costMs + "ms（点击目录下钻）");

        // 子目录占用排行（横条对比）
        if (!subDirs.isEmpty()) {
            body.addView(secTitle("目录占用（" + subDirs.size() + "）"));
            long max = subDirs.get(0).size;
            for (DiskScanUtil.DirStat s : subDirs) {
                body.addView(barRow(s.dir.getName(),
                        FileOps.formatSize(s.size), max == 0 ? 0 : s.size / (float) max,
                        s.dir));
            }
        }
        // 大文件 Top20
        if (top != null && !top.isEmpty()) {
            body.addView(secTitle("大文件 Top" + top.size()));
            for (final DiskScanUtil.FileSize fs : top) {
                TextView row = new TextView(this);
                row.setTextSize(13);
                row.setTextColor(ThemeManager.text(this));
                row.setSingleLine(false);
                row.setText("📄 " + fs.file.getName() + "\n"
                        + FileOps.formatSize(fs.size) + " · "
                        + fs.file.getParentFile().getName());
                row.setPadding(0, (int) (8 * den), 0, (int) (8 * den));
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { askDelete(fs.file); }
                });
                body.addView(row);
                body.addView(div());
            }
        }
        if (subDirs.isEmpty() && (top == null || top.isEmpty())) {
            TextView empty = new TextView(this);
            empty.setText("（空目录）");
            empty.setTextColor(ThemeManager.textSub(this));
            empty.setPadding(0, (int) (24 * den), 0, 0);
            body.addView(empty);
        }
        sv.post(new Runnable() { public void run() { sv.scrollTo(0, scrollY); } });
    }

    private TextView secTitle(String t) {
        float den = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(ThemeManager.accent(this));
        tv.setPadding(0, (int) (14 * den), 0, (int) (6 * den));
        return tv;
    }

    private View div() {
        View v = new View(this);
        v.setBackgroundColor(ThemeManager.divider(this));
        return v;
    }

    /** 目录/文件行（简单版） */
    private View dirRow(String name, String sub, long size, int files, final File dir, boolean up) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (10 * den), 0, (int) (10 * den));
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(14);
        n.setTextColor(ThemeManager.text(this));
        row.addView(n);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(ThemeManager.textSub(this));
            row.addView(s);
        }
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scan(dir); }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(div());
        return wrap;
    }

    /** 带占用比例横条的目录行 */
    private View barRow(String name, String sizeText, float ratio, final File dir) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (10 * den), 0, (int) (10 * den));
        LinearLayout line1 = new LinearLayout(this);
        TextView n = new TextView(this);
        n.setText("📁 " + name);
        n.setTextSize(14);
        n.setTextColor(ThemeManager.text(this));
        n.setSingleLine(true);
        line1.addView(n, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = new TextView(this);
        s.setText(sizeText);
        s.setTextSize(13);
        s.setTextColor(ThemeManager.textSub(this));
        line1.addView(s);
        row.addView(line1);
        // 比例条
        FrameLayout barBox = new FrameLayout(this);
        barBox.setBackgroundColor(ThemeManager.surfaceAlt(this));
        View bar = new View(this);
        bar.setBackgroundColor(ThemeManager.accent(this));
        barBox.addView(bar, new FrameLayout.LayoutParams(
                Math.max((int) (2 * den), (int) (320 * den * ratio)),
                (int) (4 * den)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (5 * den);
        row.addView(barBox, blp);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scan(dir); }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(div());
        return wrap;
    }

    private void askDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage(f.getName() + "\n" + FileOps.formatSize(f.length())
                        + "\n\n确定删除？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (f.delete()) {
                            Toast.makeText(StorageAnalysisActivity.this,
                                    "已删除", Toast.LENGTH_SHORT).show();
                            scan(current);
                        } else {
                            Toast.makeText(StorageAnalysisActivity.this,
                                    "删除失败（可能无权限）", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
