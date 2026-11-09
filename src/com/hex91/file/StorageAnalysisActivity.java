package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 存储分析（v1.59.0，v1.73.0 重构）：
 *  目录占用树下钻 + 大文件 Top20 排行 + 重复文件查找入口。
 *
 * v1.73.0 三处修正：
 *  1) 进度条按「占本目录总量」的真实比例绘制，长度用 weight 分配随容器自适应，
 *     不再使用固定 320dp（窄屏会超出、宽屏偏短，是「不同分辨率显示不同」的来源之一）；
 *  2) 扫描进度改为真实百分比：先数条目得到分母，再按已处理文件数推进，
 *     取消可中断，不再用无限转圈；
 *  3) 目录下钻与返回栈绑定：记录进入本页的根目录，返回手势逐级回退，
 *     只有回到入口根目录时才真正退出本页（此前任意层级按返回都会直接回主界面）。
 */
public class StorageAnalysisActivity extends BaseActivity {

    static final String EXTRA_ROOT = "root";

    /** 进入本页时的起点：返回到这一级才允许退出 Activity */
    private File entryRoot;
    private String entryPath;

    private File current;
    private LinearLayout body;
    private TextView breadcrumb;
    private TextView summary;
    private TextView backBtn;
    private ScrollView sv;
    private int scrollY = 0;

    /** 扫描线程与 UI 线程共享：取消标志 */
    private volatile boolean cancelScan;
    /** 本次扫描编号，用于丢弃过期结果（连续下钻时） */
    private volatile long scanSeq;
    private volatile boolean scanning;
    /** 当前进度对话框，UI 线程写、扫描线程读 */
    private volatile ProgressDialog pd;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float den = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // 顶栏：返回 + 标题 + 面包屑 + 动作
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.topBar(this));
        top.setPadding((int) (12 * den), (int) (8 * den), (int) (12 * den), (int) (8 * den));

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        // v1.73.0：显式返回按钮，与系统返回手势走同一套逐级逻辑
        backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextSize(20);
        backBtn.setTextColor(ThemeManager.accent(this));
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(0, 0, (int) (10 * den), 0);
        backBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goUp(); }
        });
        row1.addView(backBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("存储分析");
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.topBarText(this));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
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
        breadcrumb.setEllipsize(TextUtils.TruncateAt.START);
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
        entryRoot = r != null ? new File(r)
                : android.os.Environment.getExternalStorageDirectory();
        entryPath = pathOf(entryRoot);
        current = entryRoot;
        scan(entryRoot);
    }

    /** 规范化路径，用于判断「是否已回到入口根目录」 */
    private static String pathOf(File f) {
        if (f == null) return "";
        try {
            return f.getCanonicalPath();
        } catch (Throwable t) {
            return f.getAbsolutePath();
        }
    }

    private boolean atEntryRoot() {
        return current == null || entryPath == null || entryPath.equals(pathOf(current));
    }

    /** 返回上一级；已在入口根目录则退出本页 */
    private void goUp() {
        if (!atEntryRoot()) {
            File parent = current.getParentFile();
            if (parent != null) { scan(parent); return; }
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // 扫描中按返回：先停扫描，停留在当前目录（避免用户以为卡死只能杀进程）
        if (scanning) { stopScan("已取消扫描"); return; }
        if (!atEntryRoot()) {
            File parent = current.getParentFile();
            if (parent != null) { scan(parent); return; }
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cancelScan = true;
        scanSeq++;
        ProgressDialog d = pd;
        pd = null;
        if (d != null) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private TextView act(String text, float den) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(ThemeManager.accent(this));
        t.setPadding((int) (12 * den), (int) (6 * den), 0, (int) (6 * den));
        return t;
    }

    private void stopScan(String msg) {
        cancelScan = true;
        ProgressDialog d = pd;
        pd = null;
        if (d != null) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
        scanning = false;
        if (!TextUtils.isEmpty(msg)) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 后台扫描当前目录：真实进度（先数条目当分母，再按已处理文件推进）+ 目录占用 + 大文件 Top20。
     * scanSeq 保证连续下钻时只有最后一次结果会渲染。
     */
    private void scan(final File dir) {
        // 上一轮若还在跑，直接作废
        cancelScan = true;
        final long seq = ++scanSeq;
        cancelScan = false;
        scanning = true;

        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("扫描中");
        dialog.setMessage(dir.getAbsolutePath());
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setIndeterminate(true);
        dialog.setMax(1000);
        dialog.setCancelable(true);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { cancelScan = true; }
        });
        dialog.show();
        pd = dialog;

        new Thread(new Runnable() {
            public void run() {
                final long t0 = System.currentTimeMillis();
                final long[] lastUi = new long[1];
                final DiskScanUtil.Result res = DiskScanUtil.analyze(dir, 20,
                        new DiskScanUtil.ScanProgress() {
                            public void onProgress(int done, int total, long bytes) {
                                long now = System.currentTimeMillis();
                                if (now - lastUi[0] < 120) return;   // 限制刷新频率，避免刷爆主线程
                                lastUi[0] = now;
                                final int d = done;
                                final int t = total;
                                final long by = bytes;
                                if (seq != scanSeq) return;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (seq != scanSeq || pd != dialog) return;
                                        if (t > 0) {
                                            dialog.setIndeterminate(false);
                                            dialog.setMax(t);
                                            dialog.setProgress(Math.max(0, Math.min(d, t)));
                                            dialog.setMessage(FileOps.formatSize(by) + " · "
                                                    + Math.max(0, d) + " / " + t + " 个文件");
                                        } else {
                                            dialog.setMessage(FileOps.formatSize(by) + " · 正在统计 "
                                                    + Math.max(0, d) + " 个文件");
                                        }
                                    }
                                });
                            }

                            // 被新扫描取代时旧线程也要尽快退出，否则会白跑一遍磁盘
                            public boolean isCancelled() { return cancelScan || seq != scanSeq; }
                        });
                final long cost = System.currentTimeMillis() - t0;

                runOnUiThread(new Runnable() {
                    public void run() {
                        try { dialog.dismiss(); } catch (Throwable ignored) {}
                        // 已被更新的扫描接管：不改动全局状态，交给新任务收尾
                        if (seq != scanSeq) return;
                        pd = null;
                        scanning = false;
                        if (isFinishing()) return;
                        if (res == null) {
                            Toast.makeText(StorageAnalysisActivity.this,
                                    "已取消扫描", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        render(dir, res, cost);
                    }
                });
            }
        }, "disk-scan").start();
    }

    private void render(File dir, DiskScanUtil.Result r, long costMs) {
        current = dir;
        scrollY = sv.getScrollY();
        body.removeAllViews();
        float den = getResources().getDisplayMetrics().density;
        breadcrumb.setText(dir.getAbsolutePath());
        // 已在入口根目录时返回键退化为「退出」，视觉上淡化
        backBtn.setAlpha(atEntryRoot() ? 0.45f : 1f);

        // 上一级入口（保留，方便不习惯手势的用户）
        File parent = dir.getParentFile();
        if (!atEntryRoot() && parent != null && parent.canRead()) {
            body.addView(dirRow("⬆️ ..（上一级）", "", parent));
        }
        summary.setText("共 " + FileOps.formatSize(r.total) + " · 子目录 "
                + r.subDirs.size() + " 个 · 文件 " + r.fileCount + " 个 · 扫描 "
                + costMs + "ms（点击目录下钻）");

        // 子目录占用排行（真实占比横条）
        if (!r.subDirs.isEmpty()) {
            body.addView(secTitle("目录占用（" + r.subDirs.size() + "）"));
            long sum = 0;
            for (DiskScanUtil.DirStat s : r.subDirs) {
                sum += s.size;
                body.addView(barRow(s.dir.getName(), s.size, r.total, s.dir));
            }
            // 直接放在本目录下的散文件，补上差额，让百分比能对得上 100%
            long loose = r.total - sum;
            if (loose > 0) {
                body.addView(barRow("（本目录下的文件）", loose, r.total, null));
            }
        }
        // 大文件 Top20
        if (r.top != null && !r.top.isEmpty()) {
            body.addView(secTitle("大文件 Top" + r.top.size()));
            for (final DiskScanUtil.FileSize fs : r.top) {
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
        if (r.subDirs.isEmpty() && (r.top == null || r.top.isEmpty())) {
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

    /** 上一级行 */
    private View dirRow(String name, String sub, final File dir) {
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

    /**
     * 带真实占比横条的目录行（v1.73.0）。
     * 长度 = 该目录大小 / 当前目录总量，用 weight 分配宽度，随容器自适应，
     * 不再用固定 320dp（那样窄屏会超出、宽屏偏短，正是「不同分辨率显示不同」）。
     * dir 为 null 表示不可下钻的「本目录散文件」汇总行。
     */
    private View barRow(String name, long size, long total, final File dir) {
        float den = getResources().getDisplayMetrics().density;
        double raw = total > 0 ? (double) size / (double) total : 0d;
        if (raw < 0d) raw = 0d;
        if (raw > 1d) raw = 1d;
        float ratio = (float) raw;
        int pct = Math.round(ratio * 100f);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (10 * den), 0, (int) (10 * den));

        LinearLayout line1 = new LinearLayout(this);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(this);
        n.setText((dir == null ? "📄 " : "📁 ") + name);
        n.setTextSize(14);
        n.setTextColor(ThemeManager.text(this));
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        line1.addView(n, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = new TextView(this);
        s.setTextSize(13);
        s.setTextColor(ThemeManager.textSub(this));
        s.setText(FileOps.formatSize(size) + " · " + pct + "%");
        s.setPadding((int) (8 * den), 0, 0, 0);
        line1.addView(s);
        row.addView(line1);

        // 比例条：整条按 1000 份分配权重，父容器宽度变化时自动等比伸缩
        LinearLayout barBox = new LinearLayout(this);
        barBox.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable track = new GradientDrawable();
        track.setColor(ThemeManager.surfaceAlt(this));
        track.setCornerRadius(3 * den);
        barBox.setBackground(track);

        final int SCALE = 1000;
        int w = Math.round(ratio * SCALE);
        if (w <= 0 && size > 0) w = 1;          // 再小的目录也留一丝可见宽度
        if (w > SCALE) w = SCALE;
        if (w > 0) {
            View bar = new View(this);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(ThemeManager.accent(this));
            fill.setCornerRadius(3 * den);
            bar.setBackground(fill);
            barBox.addView(bar, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, w));
        }
        if (w < SCALE) {
            View rest = new View(this);
            barBox.addView(rest, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, SCALE - w));
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (5 * den));
        blp.topMargin = (int) (5 * den);
        row.addView(barBox, blp);

        if (dir != null) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { scan(dir); }
            });
        }
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
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
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
