package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC++ 编辑（v1.64.0，对标 MT Manager 同名功能）：
 *   树状列表：包名 → 资源类型目录（anim/attr/color/…）→ 配置（type-info / 默认 / -hdpi / -night / -v25 …）。
 *   点配置节点 → 生成 values 风格 XML 源码 → 进入自带文本编辑器（可改可保存）；
 *   保存时把 XML 应用回 resources.arsc（ArscPlus.applyXml → ArscWriter）→ 重打包 → 提示重签名。
 *
 * <p>type-info 节点为类型元信息（只读），点击显示统计。</p>
 */
public class ArscPlusActivity extends BaseActivity {

    public static final String EXTRA_APK = "apk";      // zip/apk 路径
    public static final String EXTRA_ENTRY = "entry";  // zip 内 arsc 条目名，默认 resources.arsc

    /** 树节点 */
    private static class Node {
        String label;              // 显示名
        boolean isPkg;             // level 0 包名
        boolean isType;            // level 1 类型目录
        boolean isTypeInfo;        // level 2 type-info（只读）
        String type, cfg;          // 配置节点的类型与配置串
        int count;                 // 条目数
        int depth;                 // 缩进层级（包=0）
        boolean expanded;
        List<Node> children = new ArrayList<Node>();
    }

    private File apk;
    private String entryName = "resources.arsc";
    private String apkBase;
    private ArscFile arsc;

    private Node pkgRoot;
    private final List<Node> rows = new ArrayList<Node>();
    private ListView listView;
    private RowAdapter adapter;
    private TextView titleSub;
    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        String apkPath = it.getStringExtra(EXTRA_APK);
        if (apkPath == null) { finish(); return; }
        apk = new File(apkPath);
        apkBase = stripExt(apk.getName());
        if (it.getStringExtra(EXTRA_ENTRY) != null) entryName = it.getStringExtra(EXTRA_ENTRY);

        // 代码构建界面：顶栏 + 列表（与反编译页同风格）
        int bg = attr(R.attr.hfBg, 0xFFFFFFFF);
        int topBg = attr(R.attr.hfTopBar, 0xFFEEEEEE);
        int topText = attr(R.attr.hfTopBarText, 0xFF222222);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(topBg);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20);
        back.setTextColor(topText);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(16), 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("ARSC++ 编辑");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(topText);
        titleSub = new TextView(this);
        titleSub.setTextSize(11);
        titleSub.setTextColor(topText);
        titleSub.setAlpha(0.75f);
        titles.addView(title);
        titles.addView(titleSub);
        bar.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        listView = new ListView(this);
        listView.setDivider(null);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadArsc();
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ---------- 加载 ----------

    private void loadArsc() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("解析 " + entryName + " …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            try {
                File workDir = new File(getCacheDir(), "arscpp_" + apkBase);
                workDir.mkdirs();
                File arscFile = new File(workDir, "resources.arsc");
                if (!extractZipEntry(apk, entryName, arscFile))
                    throw new IllegalStateException("提取失败: " + entryName);
                arsc = ArscFile.parse(arscFile);
                buildTree();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(ArscPlusActivity.this)
                            .setTitle("加载失败").setMessage(e)
                            .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { finish(); }
                            }).show();
                } else {
                    titleSub.setText(arsc.appPackage() + " · " + arsc.entryCount() + " 个资源");
                    refresh();
                }
            }});
        }}.start();
    }

    /** 包 → 类型 → (type-info + 配置) 三级树 */
    private void buildTree() {
        // 类型 → 配置 → 条目
        Map<String, Map<String, List<ResourceEntry>>> tree =
                new LinkedHashMap<String, Map<String, List<ResourceEntry>>>();
        for (ResourceEntry e : arsc.storage.getResources()) {
            String t = e.getTypeName() == null ? "?" : e.getTypeName();
            String c = e.getConfig() == null ? "" : e.getConfig();
            Map<String, List<ResourceEntry>> cfgs = tree.get(t);
            if (cfgs == null) { cfgs = new LinkedHashMap<String, List<ResourceEntry>>(); tree.put(t, cfgs); }
            List<ResourceEntry> list = cfgs.get(c);
            if (list == null) { list = new ArrayList<ResourceEntry>(); cfgs.put(c, list); }
            list.add(e);
        }
        pkgRoot = new Node();
        pkgRoot.isPkg = true;
        pkgRoot.label = arsc.appPackage();
        pkgRoot.expanded = true;
        List<String> types = new ArrayList<String>(tree.keySet());
        Collections.sort(types, String.CASE_INSENSITIVE_ORDER);
        for (String t : types) {
            Node tn = new Node();
            tn.isType = true;
            tn.type = t;
            tn.label = t;
            Map<String, List<ResourceEntry>> cfgs = tree.get(t);
            // type-info（只读元信息）
            Node info = new Node();
            info.isTypeInfo = true;
            info.type = t;
            info.label = "type-info";
            tn.children.add(info);
            // 配置节点（默认配置显示为类型名，其余 类型-配置）
            List<String> cs = new ArrayList<String>(cfgs.keySet());
            Collections.sort(cs, new Comparator<String>() {
                public int compare(String a, String b) {
                    if (a.isEmpty() != b.isEmpty()) return a.isEmpty() ? -1 : 1;
                    return a.compareTo(b);
                }
            });
            for (String c : cs) {
                Node cn = new Node();
                cn.type = t;
                cn.cfg = c;
                cn.label = ArscPlus.cfgLabel(t, c);
                cn.count = cfgs.get(c).size();
                tn.children.add(cn);
                tn.count += cn.count;
            }
            pkgRoot.children.add(tn);
        }
    }

    // ---------- 列表刷新 ----------

    private void refresh() {
        rows.clear();
        if (pkgRoot != null) {
            pkgRoot.depth = 0;
            rows.add(pkgRoot);
            if (pkgRoot.expanded) flatten(pkgRoot, 1);
        }
        if (adapter == null) {
            adapter = new RowAdapter();
            listView.setAdapter(adapter);
            // 关键接线（v1.64.0 修复）：点击展开/进入 XML 编辑
            listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                    if (pos < rows.size()) onRowClick(rows.get(pos));
                }
            });
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void flatten(Node n, int depth) {
        for (Node c : n.children) {
            c.depth = depth;
            rows.add(c);
            if ((c.isType || c.isPkg) && c.expanded) flatten(c, depth + 1);
        }
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            float den = getResources().getDisplayMetrics().density;
            Node n = rows.get(i);

            // 行布局：[缩进][箭头(可展开节点)][图标][文字]
            LinearLayout row = new LinearLayout(ArscPlusActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp2(16 + n.depth * 16), dp2(5), dp2(8), dp2(5));

            // 箭头：仅可展开节点（包 / 类型目录）
            if (n.isPkg || n.isType) {
                ImageView chev = new ImageView(ArscPlusActivity.this);
                chev.setImageResource(R.drawable.ic_tree_chevron);
                chev.setColorFilter(ThemeManager.textSub(ArscPlusActivity.this));
                chev.setRotation(n.expanded ? 0f : -90f);
                row.addView(chev, new LinearLayout.LayoutParams(dp2(20), dp2(20)));
            } else {
                View sp = new View(ArscPlusActivity.this);
                row.addView(sp, new LinearLayout.LayoutParams(dp2(20), 1));
            }

            // 图标：文件夹（包/类型）或 ©（叶子/type-info）
            ImageView ic = new ImageView(ArscPlusActivity.this);
            if (n.isPkg || n.isType) {
                ic.setImageResource(R.drawable.ic_tree_folder);
                ic.setColorFilter(ThemeManager.textSub(ArscPlusActivity.this));
            } else {
                ic.setImageResource(R.drawable.ic_tree_node);
                ic.setColorFilter(n.isTypeInfo
                        ? ThemeManager.textSub(ArscPlusActivity.this)
                        : ThemeManager.accent(ArscPlusActivity.this));
            }
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp2(20), dp2(20));
            ilp.leftMargin = dp2(4);
            row.addView(ic, ilp);

            // 文字
            TextView tv = new TextView(ArscPlusActivity.this);
            tv.setTextSize(n.isPkg || n.isType ? 15 : 14);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp2(8), 0, 0, 0);
            tv.setText(n.label + (n.isType ? "  (" + n.count + ")"
                    : (!n.isPkg && !n.isType && !n.isTypeInfo ? "  (" + n.count + ")" : "")));
            if (n.isPkg || n.isType) {
                tv.setTextColor(ThemeManager.text(ArscPlusActivity.this));
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            } else if (n.isTypeInfo) {
                tv.setTextColor(ThemeManager.textSub(ArscPlusActivity.this));
            } else {
                tv.setTextColor(ThemeManager.text(ArscPlusActivity.this));
            }
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }

    private int dp2(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- 点击 ----------

    private void onRowClick(Node n) {
        if (n.isPkg || n.isType) {
            n.expanded = !n.expanded;
            refresh();
        } else if (n.isTypeInfo) {
            showTypeInfo(n);
        } else {
            openConfigXml(n);
        }
    }

    private void showTypeInfo(Node n) {
        int total = 0;
        List<String> cfgs = new ArrayList<String>();
        for (Node c : n.children) {
            if (c.isTypeInfo) continue;
            cfgs.add(c.label + " (" + c.count + ")");
            total += c.count;
        }
        new AlertDialog.Builder(this)
                .setTitle("类型信息: " + n.type)
                .setMessage("条目总数: " + total
                        + "\n配置数: " + cfgs.size()
                        + "\n\n" + join(cfgs, "\n")
                        + "\n\n点配置节点（如 " + ArscPlus.cfgLabel(n.type, "")
                        + "）以 XML 源码方式批量编辑该配置下的所有值。")
                .setPositiveButton("知道了", null).show();
    }

    /** 配置节点 → 生成 XML → 进编辑器（保存时经 ArscPlus.applyXml 写回 arsc） */
    private void openConfigXml(final Node n) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("生成 " + n.label + ".xml …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            File out = null;
            try {
                final String xml = ArscPlus.buildXml(arsc, n.type, n.cfg);
                File dir = new File(getCacheDir(), "arscpp_" + apkBase);
                dir.mkdirs();
                String safeCfg = n.cfg.isEmpty() ? "" : "_" + n.cfg.replaceAll("[^\\w]", "_");
                out = new File(dir, "values_" + n.type + safeCfg + ".xml");
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(xml.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            final File f = out;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) { toast("生成失败: " + e); return; }
                Intent i = new Intent(ArscPlusActivity.this, TextEditorActivity.class);
                i.putExtra(TextEditorActivity.EXTRA_FILE, f.getAbsolutePath());
                i.putExtra(TextEditorActivity.EXTRA_ARCHIVE, apk.getAbsolutePath());
                i.putExtra(TextEditorActivity.EXTRA_ENTRY, entryName);
                i.putExtra(TextEditorActivity.EXTRA_ARSCPP_TYPE, n.type);
                i.putExtra(TextEditorActivity.EXTRA_ARSCPP_CFG, n.cfg);
                i.putExtra(TextEditorActivity.EXTRA_ARSCPP_ENTRY, entryName);
                startActivity(i);
            }});
        }}.start();
    }

    // ---------- 小工具 ----------

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static boolean extractZipEntry(File zip, String entryName, File out) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) return false;
            InputStream in = zf.getInputStream(ze);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int attr(int attrId, int def) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attrId, tv, true)) {
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
            if (tv.resourceId != 0) return getResources().getColor(tv.resourceId);
        }
        return def;
    }
}
