package com.hex91.file;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 对比工具 UI（v1.17.0）：
 *  - folder：目录对比（统计 + 彩色差异列表，支持哈希校验）
 *  - text  ：文本对比（行号 + 增删着色）
 *  - hash  ：哈希对比（MD5/SHA-1/SHA-256/CRC32 四算法表格）
 *  - apk   ：APK 对比（清单信息差异 + zip 条目差异）
 */
public class CompareActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PATH_A = "pathA";
    public static final String EXTRA_PATH_B = "pathB";
    public static final String EXTRA_VERIFY_HASH = "verifyHash";

    public static final String MODE_FOLDER = "folder";
    public static final String MODE_TEXT = "text";
    public static final String MODE_HASH = "hash";
    public static final String MODE_APK = "apk";

    // 状态色（浅色底 + 深色字）—— 暗色模式下底色需压暗、字色需提亮，故改为动态取色
    private static final int C_ADD_BG = 0xFFE8F5E9, C_ADD_TX = 0xFF2E7D32;
    private static final int C_MOD_BG = 0xFFFFF3E0, C_MOD_TX = 0xFFE65100;
    private static final int C_DEL_BG = 0xFFFFEBEE, C_DEL_TX = 0xFFC62828;

    /** 差异底色：暗色模式下压暗为深色底 */
    private int bgOf(int light) {
        return ThemeManager.isDark(this) ? ThemeManager.syntaxColor(this, light) : light;
    }

    /** 差异字色：暗色模式下提亮 */
    private int txOf(int light) {
        return ThemeManager.syntaxColor(this, light);
    }


    private String mode;
    private File fa, fb;
    private boolean verifyHash;
    private TextView titleView, summaryView;
    private ListView listView;
    private final List<Object> rows = new ArrayList<Object>();
    private RowAdapter adapter;
    private String report = "";
    private boolean done = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_compare);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        fa = new File(getIntent().getStringExtra(EXTRA_PATH_A));
        fb = new File(getIntent().getStringExtra(EXTRA_PATH_B));
        verifyHash = getIntent().getBooleanExtra(EXTRA_VERIFY_HASH, false);

        titleView = (TextView) findViewById(R.id.compare_title);
        summaryView = (TextView) findViewById(R.id.compare_summary);
        listView = (ListView) findViewById(R.id.compare_list);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);

        titleView.setText(modeTitle() + "\nA: " + fa.getName() + "  ↔  B: " + fb.getName());
        summaryView.setText("正在对比…");

        ((Button) findViewById(R.id.compare_close)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        ((Button) findViewById(R.id.compare_copy)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyReport(); }
        });

        // v1.37.0：目录对比差异条目可操作（打开/再对比/复制到另一侧/删除）
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (!MODE_FOLDER.equals(mode)) return;
                if (position < 0 || position >= rows.size()) return;
                Object row = rows.get(position);
                if (row instanceof CompareUtil.Item) showItemMenu((CompareUtil.Item) row);
            }
        });

        runCompare();
    }

    private String modeTitle() {
        if (MODE_FOLDER.equals(mode)) return "目录对比";
        if (MODE_TEXT.equals(mode)) return "文本对比";
        if (MODE_HASH.equals(mode)) return "哈希对比";
        if (MODE_APK.equals(mode)) return "APK 对比";
        return "对比";
    }

    // ============================================================ 后台执行

    private void runCompare() {
        new Thread(new Runnable() {
            public void run() {
                if (MODE_FOLDER.equals(mode)) doFolder();
                else if (MODE_TEXT.equals(mode)) doText();
                else if (MODE_HASH.equals(mode)) doHash();
                else if (MODE_APK.equals(mode)) doApk();
            }
        }, "compare").start();
    }

    private void doFolder() {
        final CompareUtil.Result r = CompareUtil.compareDirs(fa, fb, verifyHash);
        runOnUiThread(new Runnable() {
            public void run() {
                done = true;
                rows.clear();
                rows.addAll(r.items);
                if (r.error != null) summaryView.setText(CompareUtil.summary(r));
                else summaryView.setText(CompareUtil.summary(r));
                adapter.notifyDataSetChanged();
                report = folderReport(r);
                if (r.items.isEmpty())
                    summaryView.setText(CompareUtil.summary(r) + "\n（无差异）");
            }
        });
    }

    private void doText() {
        final TextDiffUtil.Result r = TextDiffUtil.diffFiles(fa, fb);
        runOnUiThread(new Runnable() {
            public void run() {
                done = true;
                if (r.tooBig || r.error != null) {
                    summaryView.setText("无法对比：" + (r.error == null ? "未知原因" : r.error));
                    return;
                }
                rows.clear();
                rows.addAll(r.lines);
                summaryView.setText("A " + r.totalA + " 行 (" + r.encoding + ") → B " + r.totalB
                        + " 行 · 新增 " + r.nAdd + " · 删除 " + r.nDel);
                adapter.notifyDataSetChanged();
                report = TextDiffUtil.report(r, fa.getName(), fb.getName());
            }
        });
    }

    private void doHash() {
        final java.util.Map<String, String> ha = HashUtil.hashAll(fa);
        final java.util.Map<String, String> hb = HashUtil.hashAll(fb);
        runOnUiThread(new Runnable() {
            public void run() {
                done = true;
                rows.clear();
                StringBuilder sb = new StringBuilder();
                sb.append("哈希对比\nA: ").append(fa.getAbsolutePath()).append('\n');
                sb.append("B: ").append(fb.getAbsolutePath()).append('\n');
                boolean allSame = true;
                for (String alg : HashUtil.ALGS) {
                    HashRow hr = new HashRow();
                    hr.alg = alg;
                    hr.ha = ha.get(alg);
                    hr.hb = hb.get(alg);
                    hr.same = hr.ha != null && hr.ha.equals(hr.hb);
                    if (!hr.same) allSame = false;
                    rows.add(hr);
                    sb.append(alg).append(": ").append(hr.ha == null ? "?" : hr.ha).append('\n');
                    sb.append(alg).append(": ").append(hr.hb == null ? "?" : hr.hb).append('\n');
                }
                summaryView.setText(allSame ? "全部算法一致 ✓ 两文件内容相同"
                        : "存在不一致的算法 ✗ 两文件内容不同");
                adapter.notifyDataSetChanged();
                report = sb.toString();
            }
        });
    }

    private void doApk() {
        final ApkCompareUtil.Result r = ApkCompareUtil.compare(fa, fb, getPackageManager());
        runOnUiThread(new Runnable() {
            public void run() {
                done = true;
                rows.clear();
                rows.add("── 清单信息（" + r.nRowDiff + " 项不同）──");
                for (ApkCompareUtil.RowDiff rd : r.rows) {
                    if (!rd.same) rows.add(rd);
                }
                if (r.nRowDiff == 0) rows.add("（清单信息一致）");
                rows.add("── 条目差异（新增 " + r.entries.nAdded + " · 修改 "
                        + r.entries.nModified + " · 删除 " + r.entries.nDeleted + "）──");
                rows.addAll(r.entries.items);
                summaryView.setText("A " + CompareUtil.fmtSize(r.sizeA) + " ↔ B "
                        + CompareUtil.fmtSize(r.sizeB)
                        + (r.shaA != null && r.shaA.equals(r.shaB) ? " · SHA-256 一致" : "")
                        + (r.entries.items.isEmpty() && r.nRowDiff == 0 ? "\n（完全一致）" : ""));
                adapter.notifyDataSetChanged();
                report = ApkCompareUtil.report(r, fa.getName(), fb.getName());
            }
        });
    }

    // ============================================================ 差异条目操作（v1.37.0）

    private static final int OP_TEXT = 0, OP_HASH = 1, OP_OPEN_A = 2, OP_OPEN_B = 3,
            OP_COPY_AB = 4, OP_COPY_BA = 5, OP_DEL_A = 6, OP_DEL_B = 7;

    /** 差异条目点击 → 按状态弹操作菜单 */
    private void showItemMenu(final CompareUtil.Item it) {
        final List<String> ops = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        if (it.status == CompareUtil.MODIFIED && !it.dir) {
            ops.add("文本对比");             acts.add(OP_TEXT);
            ops.add("哈希对比");             acts.add(OP_HASH);
            ops.add("打开 A 侧");            acts.add(OP_OPEN_A);
            ops.add("打开 B 侧");            acts.add(OP_OPEN_B);
            ops.add("复制 A→B（覆盖）");     acts.add(OP_COPY_AB);
            ops.add("复制 B→A（覆盖）");     acts.add(OP_COPY_BA);
        } else if (it.status == CompareUtil.MODIFIED) {
            ops.add("复制 A→B");             acts.add(OP_COPY_AB);
            ops.add("复制 B→A");             acts.add(OP_COPY_BA);
        } else if (it.status == CompareUtil.DELETED) {   // 仅 A 有
            ops.add("打开");                 acts.add(OP_OPEN_A);
            ops.add("复制 A→B");             acts.add(OP_COPY_AB);
            ops.add("删除 A 侧");            acts.add(OP_DEL_A);
        } else if (it.status == CompareUtil.ADDED) {     // 仅 B 有
            ops.add("打开");                 acts.add(OP_OPEN_B);
            ops.add("复制 B→A");             acts.add(OP_COPY_BA);
            ops.add("删除 B 侧");            acts.add(OP_DEL_B);
        } else {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(it.dir ? it.relPath + "/" : it.relPath)
                .setItems(ops.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                int op = acts.get(w);
                                switch (op) {
                                    case OP_TEXT:
                                        subCompare(MODE_TEXT, it); break;
                                    case OP_HASH:
                                        subCompare(MODE_HASH, it); break;
                                    case OP_OPEN_A:
                                        openOne(new File(fa, it.relPath)); break;
                                    case OP_OPEN_B:
                                        openOne(new File(fb, it.relPath)); break;
                                    case OP_COPY_AB:
                                        syncCopy(it, true); break;
                                    case OP_COPY_BA:
                                        syncCopy(it, false); break;
                                    case OP_DEL_A:
                                        confirmDelete(it, true); break;
                                    case OP_DEL_B:
                                        confirmDelete(it, false); break;
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 对单个差异文件发起子对比（文本/哈希），复用本 Activity 的两种模式 */
    private void subCompare(String subMode, CompareUtil.Item it) {
        File a = new File(fa, it.relPath);
        File b = new File(fb, it.relPath);
        if (!a.isFile() || !b.isFile()) { toast("文件不存在，无法对比"); return; }
        startActivity(new Intent(this, CompareActivity.class)
                .putExtra(EXTRA_MODE, subMode)
                .putExtra(EXTRA_PATH_A, a.getAbsolutePath())
                .putExtra(EXTRA_PATH_B, b.getAbsolutePath()));
    }

    /** 打开单个文件：图片 → 查看器，文本类 → 编辑器，其余提示 */
    private void openOne(File f) {
        if (f == null || !f.exists()) { toast("文件不存在"); return; }
        String low = f.getName().toLowerCase();
        if (MainActivity.isImageExt(low)) {
            startActivity(new Intent(this, ImageViewerActivity.class)
                    .putExtra(ImageViewerActivity.EXTRA_FILE, f.getAbsolutePath()));
        } else if (MainActivity.isTextName(low, f.length())) {
            startActivity(new Intent(this, TextEditorActivity.class)
                    .putExtra(TextEditorActivity.EXTRA_FILE, f.getAbsolutePath()));
        } else {
            toast("该类型请回到文件管理器中打开");
        }
    }

    /** 把一侧的条目复制到另一侧（同步缺失/覆盖不同），完成后刷新对比 */
    private void syncCopy(final CompareUtil.Item it, final boolean aToB) {
        final File src = new File(aToB ? fa : fb, it.relPath);
        final File dst = new File(aToB ? fb : fa, it.relPath);
        if (!src.exists()) { toast("源不存在"); return; }
        final ProgressDialog pd = ProgressDialog.show(this, null,
                "复制中…", true, false);
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = FileOps.copyOverwrite(src, dst);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(ok ? (aToB ? "已复制 A→B" : "已复制 B→A")
                                : "复制失败（权限或空间不足？）");
                        if (ok) refresh();
                    }
                });
            }
        }, "cmp-copy").start();
    }

    /** 删除一侧条目（二次确认），完成后刷新对比 */
    private void confirmDelete(final CompareUtil.Item it, final boolean sideA) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("将从 " + (sideA ? "A 侧" : "B 侧") + " 删除：\n"
                        + it.relPath + (it.dir ? "/" : "")
                        + "\n\n此操作不可撤销！")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final File f = new File(sideA ? fa : fb, it.relPath);
                        new Thread(new Runnable() {
                            public void run() {
                                final boolean ok = FileOps.deleteRecursive(f);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(ok ? "已删除" : "删除失败");
                                        if (ok) refresh();
                                    }
                                });
                            }
                        }, "cmp-del").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 操作后重跑对比（保留原哈希校验选项） */
    private void refresh() {
        summaryView.setText("正在对比…");
        rows.clear();
        adapter.notifyDataSetChanged();
        done = false;
        report = "";
        runCompare();
    }

    // ============================================================ 适配器

    private static class HashRow {
        String alg, ha, hb;
        boolean same;
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int position, View convertView, ViewGroup parent) {
            Object row = rows.get(position);
            float den = getResources().getDisplayMetrics().density;
            int pad = (int) (10 * den);

            if (row instanceof String) {   // section header
                TextView tv = new TextView(CompareActivity.this);
                tv.setText((String) row);
                tv.setTextSize(12);
                tv.setTextColor(ThemeManager.accent(CompareActivity.this));
                tv.setPadding(pad, (int) (8 * den), pad, (int) (8 * den));
                tv.setBackgroundColor(ThemeManager.surfaceAlt(CompareActivity.this));
                return tv;
            }
            if (row instanceof HashRow) {  // 哈希表行
                HashRow hr = (HashRow) row;
                LinearLayout ll = new LinearLayout(CompareActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(pad, (int) (8 * den), pad, (int) (8 * den));
                ll.setBackgroundColor(hr.same ? Color.TRANSPARENT : bgOf(C_MOD_BG));
                TextView t1 = new TextView(CompareActivity.this);
                t1.setText(hr.alg + (hr.same ? "  ✓ 一致" : "  ✗ 不一致"));
                t1.setTextSize(13);
                t1.setTextColor(hr.same ? txOf(C_ADD_TX) : txOf(C_MOD_TX));
                ll.addView(t1);
                TextView t2 = new TextView(CompareActivity.this);
                t2.setText("A: " + (hr.ha == null ? "(失败)" : hr.ha));
                t2.setTextSize(11);
                t2.setTextColor(ThemeManager.text(CompareActivity.this));
                ll.addView(t2);
                TextView t3 = new TextView(CompareActivity.this);
                t3.setText("B: " + (hr.hb == null ? "(失败)" : hr.hb));
                t3.setTextSize(11);
                t3.setTextColor(ThemeManager.text(CompareActivity.this));
                ll.addView(t3);
                return ll;
            }
            if (row instanceof TextDiffUtil.Line) {   // 文本 diff 行
                TextDiffUtil.Line l = (TextDiffUtil.Line) row;
                LinearLayout ll = new LinearLayout(CompareActivity.this);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setPadding(pad, (int) (4 * den), pad, (int) (4 * den));
                int bg = l.type == TextDiffUtil.ADD ? bgOf(C_ADD_BG)
                        : l.type == TextDiffUtil.DEL ? bgOf(C_DEL_BG) : Color.TRANSPARENT;
                ll.setBackgroundColor(bg);
                TextView an = new TextView(CompareActivity.this);
                an.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (36 * den), LinearLayout.LayoutParams.WRAP_CONTENT));
                an.setText(l.aNo > 0 ? String.valueOf(l.aNo) : "");
                an.setTextSize(10);
                an.setTextColor(ThemeManager.textSub(CompareActivity.this));
                an.setGravity(android.view.Gravity.END);
                TextView bn = new TextView(CompareActivity.this);
                bn.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (36 * den), LinearLayout.LayoutParams.WRAP_CONTENT));
                bn.setText(l.bNo > 0 ? String.valueOf(l.bNo) : "");
                bn.setTextSize(10);
                bn.setTextColor(ThemeManager.textSub(CompareActivity.this));
                bn.setGravity(android.view.Gravity.END);
                TextView tx = new TextView(CompareActivity.this);
                tx.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                tx.setText((l.type == TextDiffUtil.ADD ? "+ " : l.type == TextDiffUtil.DEL ? "- " : "  ")
                        + l.text);
                tx.setTextSize(12);
                tx.setTextColor(l.type == TextDiffUtil.ADD ? txOf(C_ADD_TX)
                        : l.type == TextDiffUtil.DEL ? txOf(C_DEL_TX) : ThemeManager.text(CompareActivity.this));
                tx.setSingleLine(true);
                ll.addView(an);
                ll.addView(bn);
                ll.addView(tx);
                return ll;
            }
            if (row instanceof ApkCompareUtil.RowDiff) {   // APK 清单行
                ApkCompareUtil.RowDiff rd = (ApkCompareUtil.RowDiff) row;
                LinearLayout ll = new LinearLayout(CompareActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(pad, (int) (6 * den), pad, (int) (6 * den));
                ll.setBackgroundColor(bgOf(C_MOD_BG));
                TextView t1 = new TextView(CompareActivity.this);
                t1.setText("~ " + rd.label);
                t1.setTextSize(12);
                t1.setTextColor(txOf(C_MOD_TX));
                ll.addView(t1);
                TextView t2 = new TextView(CompareActivity.this);
                t2.setText("A: " + (rd.valA == null ? "(无)" : rd.valA));
                t2.setTextSize(11);
                t2.setTextColor(ThemeManager.text(CompareActivity.this));
                ll.addView(t2);
                TextView t3 = new TextView(CompareActivity.this);
                t3.setText("B: " + (rd.valB == null ? "(无)" : rd.valB));
                t3.setTextSize(11);
                t3.setTextColor(ThemeManager.text(CompareActivity.this));
                ll.addView(t3);
                return ll;
            }
            // CompareUtil.Item（目录/APK 条目差异）
            CompareUtil.Item it = (CompareUtil.Item) row;
            LinearLayout ll = new LinearLayout(CompareActivity.this);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setPadding(pad, (int) (6 * den), pad, (int) (6 * den));
            int bg, tx;
            String sign;
            if (it.status == CompareUtil.ADDED) { bg = bgOf(C_ADD_BG); tx = txOf(C_ADD_TX); sign = "+ "; }
            else if (it.status == CompareUtil.DELETED) { bg = bgOf(C_DEL_BG); tx = txOf(C_DEL_TX); sign = "- "; }
            else { bg = bgOf(C_MOD_BG); tx = txOf(C_MOD_TX); sign = "~ "; }
            ll.setBackgroundColor(bg);
            TextView t1 = new TextView(CompareActivity.this);
            t1.setText(sign + it.relPath + (it.dir ? "/" : ""));
            t1.setTextSize(12);
            t1.setTextColor(tx);
            ll.addView(t1);
            if (it.note != null && !it.note.isEmpty()) {
                TextView t2 = new TextView(CompareActivity.this);
                t2.setText(it.note);
                t2.setTextSize(10);
                t2.setTextColor(ThemeManager.textSub(CompareActivity.this));
                ll.addView(t2);
            }
            return ll;
        }
    }

    // ============================================================ 报告

    private String folderReport(CompareUtil.Result r) {
        StringBuilder sb = new StringBuilder(CompareUtil.summary(r)).append('\n');
        for (CompareUtil.Item it : r.items) {
            String sign = it.status == CompareUtil.ADDED ? "+ "
                    : it.status == CompareUtil.DELETED ? "- " : "~ ";
            sb.append(sign).append(it.relPath).append(it.dir ? "/" : "");
            if (it.note != null && !it.note.isEmpty()) sb.append("  (").append(it.note).append(")");
            sb.append('\n');
        }
        return sb.toString();
    }

    private void copyReport() {
        if (!done || report.isEmpty()) {
            toast("对比尚未完成");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("对比报告", report));
        toast("报告已复制到剪贴板");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
