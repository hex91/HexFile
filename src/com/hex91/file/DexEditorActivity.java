package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dex 编辑器++（v1.8，对标 MT Manager 同名功能）：
 *   1. MultiDex 选择对话框（复选全部 classes*.dex，全选/取消/确定）
 *   2. 临时工程：dex 提取 + baksmali（重复打开自动复用，秒开）
 *   3. 类树浏览（包可展开/收起，类蓝色标记）+ 实时搜索类名
 *   4. 点类 → 自带文本编辑器改 smali（返回自动保存在原文件）
 *   5. ⚙ 编译：整个工程 smali → 单个 classes.dex（跨 dex 合并），输出到 APK 同目录 <apk名>_dexpp/
 *   6. 写回 APK（v1.10）：替换首个选中 dex + 移除其余选中的 dex → 重打包 → 重签名（64K 方法数守卫）
 */
public class DexEditorActivity extends BaseActivity {

    public static final String EXTRA_APK = "apk";    // zip/apk 路径（与 EXTRA_FILE 二选一）
    public static final String EXTRA_DEXES = "dexes"; // 预勾选的 zip 内 dex 条目名
    public static final String EXTRA_FILE = "file";   // 独立 dex 文件路径

    private static final Comparator<String> CI = String.CASE_INSENSITIVE_ORDER;

    private File apk;               // zip 模式下的 apk/zip
    private File singleDex;         // 单文件模式
    private String projName;
    private File workDir;           // 工程根：src_*.dex + smali/<dex>/
    private final List<String> dexes = new ArrayList<String>();

    private TextView titleSub;
    private EditText searchBox;
    private ListView listView;

    /** 类树节点 */
    private static class Node {
        String name;                 // 段名
        String path;                 // 全路径（类不带 .smali）
        boolean isClass;
        boolean expanded;
        int depth;                   // 层级（flatten 时写入）
        List<Node> children = new ArrayList<Node>();
    }

    private Node root;
    private final List<Object> rows = new ArrayList<Object>(); // Node 或 String(类path，搜索态)
    private final Map<String, File> classFiles = new HashMap<String, File>();
    private boolean searchMode;
    private int classCount;

    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dexpp);

        Intent it = getIntent();
        String apkPath = it.getStringExtra(EXTRA_APK);
        String filePath = it.getStringExtra(EXTRA_FILE);

        titleSub = (TextView) findViewById(R.id.dexpp_sub);
        searchBox = (EditText) findViewById(R.id.dexpp_search);
        listView = (ListView) findViewById(R.id.dexpp_list);

        if (apkPath != null) {
            apk = new File(apkPath);
            projName = stripExt(apk.getName());
            workDir = new File(getCacheDir(), "dexpp_" + projName);
            String[] pre = it.getStringArrayExtra(EXTRA_DEXES);
            showMultiDexDialog(pre);
        } else if (filePath != null) {
            singleDex = new File(filePath);
            projName = stripExt(singleDex.getName());
            workDir = new File(getCacheDir(), "dexpp_" + projName);
            dexes.add(singleDex.getAbsolutePath());
            loadProject();
        } else {
            finish();
            return;
        }

        findViewById(R.id.dexpp_compile).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askCompile(); }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh(); }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos < rows.size()) onRowClick(rows.get(pos));
            }
        });
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ---------- MultiDex 选择（MT 同款） ----------

    private void showMultiDexDialog(final String[] prechecked) {
        final List<String> all = listZipDexes();
        if (all.isEmpty()) {
            Toast.makeText(this, "APK 内未找到 dex", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final boolean[] chk = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++)
            chk[i] = prechecked == null ? (i == 0) : contains(prechecked, all.get(i));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("MultiDex")
                .setMultiChoiceItems(all.toArray(new String[0]), chk,
                        new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        chk[which] = isChecked;
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        dexes.clear();
                        boolean any = false;
                        for (int i = 0; i < all.size(); i++) if (chk[i]) { dexes.add(all.get(i)); any = true; }
                        if (!any) {
                            Toast.makeText(DexEditorActivity.this, "请至少选择一个 dex", Toast.LENGTH_SHORT).show();
                            showMultiDexDialog(prechecked);
                            return;
                        }
                        loadProject();
                    }
                });
        final AlertDialog dlg = b.create();
        dlg.show();
        // “全选”按钮：点击后不关闭对话框，在 全选/全不选 间切换（MT 行为）
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean allOn = true;
                for (boolean c : chk) allOn &= c;
                android.widget.ListView lv = dlg.getListView();
                for (int i = 0; i < chk.length; i++) {
                    chk[i] = !allOn;
                    lv.setItemChecked(i, !allOn);
                }
            }
        });
    }

    private boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equals(s)) return true;
        return false;
    }

    private List<String> listZipDexes() {
        List<String> out = new ArrayList<String>();
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (!ze.isDirectory() && ze.getName().toLowerCase().endsWith(".dex"))
                    out.add(ze.getName());
            }
        } catch (Exception ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        Collections.sort(out, CI);
        return out;
    }

    // ---------- 工程加载（解压 + baksmali，带复用） ----------

    private void loadProject() {
        final List<String> sel = new ArrayList<String>(dexes);
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("准备工程…");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            try {
                // 复用判定：project.ini 记录与本次选择一致则跳过反编译
                File ini = new File(workDir, "project.ini");
                StringBuilder want = new StringBuilder();
                for (String s : sel) want.append(s).append('\n');
                boolean reuse = ini.exists() && readText(ini).contentEquals(want);
                if (!reuse) {
                    deleteR(workDir);
                    workDir.mkdirs();
                    writeText(ini, want.toString());
                    for (int i = 0; i < sel.size(); i++) {
                        final String dexName = sel.get(i);
                        final String msg1 = "提取 " + dexName + " …";
                        ui.post(new Runnable() { public void run() { pd.setMessage(msg1); } });
                        File src;
                        if (singleDex != null) {
                            src = singleDex;
                        } else {
                            src = new File(workDir, "src_" + i + ".dex");
                            if (!extractZipEntry(apk, dexName, src)) {
                                err = "提取失败: " + dexName;
                                break;
                            }
                        }
                        String base = stripExt(new File(dexName).getName());
                        File outDir = new File(workDir, "smali_" + base);
                        final String msg2 = "反编译 " + dexName + "\n（大 dex 需 10-60 秒）";
                        ui.post(new Runnable() { public void run() { pd.setMessage(msg2); } });
                        err = SmaliTool.decompile(src, outDir, 4);
                        if (err != null) break;
                    }
                }
                if (err == null) buildTree();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(DexEditorActivity.this)
                            .setTitle("加载失败").setMessage(e)
                            .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { finish(); }
                            }).show();
                } else {
                    titleSub.setText(dexes.size() + " 个 dex · " + classCount + " 个类 · 临时工程");
                    refresh();
                }
            }});
        }}.start();
    }

    /** 扫描 smali_* 目录建类树 */
    private void buildTree() {
        root = new Node();
        root.name = "";
        classFiles.clear();
        classCount = 0;
        for (File dir : workDir.listFiles()) {
            if (!dir.isDirectory() || !dir.getName().startsWith("smali_")) continue;
            walk(dir, dir, "");
        }
        classCount = classFiles.size();
        sortNode(root);
    }

    private void walk(File base, File dir, String prefix) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                walk(base, f, rel);
            } else if (f.getName().endsWith(".smali")) {
                String cls = rel.substring(0, rel.length() - 6);
                File old = classFiles.get(cls);
                if (old == null || f.lastModified() > old.lastModified()) classFiles.put(cls, f);
                insertClass(cls);
            }
        }
    }

    private void insertClass(String cls) {
        Node n = root;
        String[] seg = cls.split("/");
        for (int i = 0; i < seg.length; i++) {
            boolean last = i == seg.length - 1;
            Node child = null;
            for (Node c : n.children) if (c.name.equals(seg[i])) { child = c; break; }
            if (child == null) {
                child = new Node();
                child.name = seg[i];
                child.isClass = last;
                child.path = cls;
                n.children.add(child);
            }
            n = child;
        }
    }

    private void sortNode(Node n) {
        Collections.sort(n.children, new Comparator<Node>() {
            public int compare(Node a, Node b) {
                int c = CI.compare(a.name, b.name);
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
        for (Node c : n.children) sortNode(c);
    }

    // ---------- 列表刷新（浏览树 / 搜索） ----------

    private void refresh() {
        rows.clear();
        String q = searchBox.getText().toString().trim();
        searchMode = !q.isEmpty();
        if (root == null) return;
        if (searchMode) {
            String lq = q.toLowerCase();
            List<String> hits = new ArrayList<String>(classFiles.keySet());
            Collections.sort(hits, CI);
            for (String cls : hits)
                if (cls.toLowerCase().contains(lq)) rows.add(cls);
            titleSub.setText(rows.size() + " 个匹配类");
        } else {
            flatten(root, 0);
            titleSub.setText(dexes.size() + " 个 dex · " + classCount + " 个类 · 临时工程");
        }
        listView.setAdapter(new RowAdapter());
    }

    private void flatten(Node n, int depth) {
        for (Node c : n.children) {
            c.depth = depth;
            rows.add(c);
            if (!c.isClass && c.expanded) flatten(c, depth + 1);
        }
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            float den = getResources().getDisplayMetrics().density;
            TextView tv = new TextView(DexEditorActivity.this);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            int depth = 0;
            Object o = rows.get(i);
            if (o instanceof Node) {
                Node n = (Node) o;
                depth = n.depth;
                if (n.isClass) {
                    tv.setTextColor(ThemeManager.accent(DexEditorActivity.this));
                    tv.setText("● " + n.name);
                } else {
                    tv.setTextColor(ThemeManager.text(DexEditorActivity.this));
                    tv.setText(n.expanded ? "▾ " + n.name : "▸ " + n.name);
                }
            } else {
                tv.setTextColor(ThemeManager.accent(DexEditorActivity.this));
                tv.setText("● " + (String) o);
            }
            tv.setPadding((int) (16 * den + depth * 18 * den), (int) (10 * den), (int) (8 * den), (int) (10 * den));
            return tv;
        }
    }

    private void onRowClick(Object o) {
        if (o instanceof Node) {
            Node n = (Node) o;
            if (n.isClass) {
                File f = classFiles.get(n.path);
                if (f != null) openSmali(f);
            } else {
                n.expanded = !n.expanded;
                refresh();
            }
        } else {
            File f = classFiles.get((String) o);
            if (f != null) openSmali(f);
        }
    }

    private void openSmali(File f) {
        Intent i = new Intent(this, TextEditorActivity.class);
        i.putExtra(TextEditorActivity.EXTRA_FILE, f.getAbsolutePath());
        startActivity(i);
    }

    // ---------- 编译 ----------

    private void askCompile() {
        if (root == null) {
            Toast.makeText(this, "工程尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        File outDir;
        if (apk != null) outDir = new File(apk.getParentFile(), projName + "_dexpp");
        else outDir = new File(singleDex.getParentFile(), projName + "_dexpp");
        final File outDex = new File(outDir, "classes.dex");
        new AlertDialog.Builder(this)
                .setTitle("编译工程")
                .setMessage("把全部 smali 编译为 dex？\n\n输出: " + outDex.getPath()
                        + "\n\n提示: 改过的 smali 需先在编辑器里保存")
                .setPositiveButton("编译", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doCompile(outDex); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doCompile(final File outDex) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("编译中…（几千个类需 10-60 秒）");
        pd.setCancelable(false);
        pd.show();
        final List<File> dirs = new ArrayList<File>();
        for (File f : workDir.listFiles())
            if (f.isDirectory() && f.getName().startsWith("smali_")) dirs.add(f);
        new Thread() { public void run() {
            String err;
            try {
                outDex.getParentFile().mkdirs();
                if (outDex.exists()) outDex.delete();
                err = SmaliTool.assembleMulti(dirs.toArray(new File[0]), outDex, 4);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e == null) {
                    if (apk != null) {
                        // v1.10：APK 工程 → 直接提供写回（替换首个选中 dex + 移除其余选中 dex + 重签名）
                        new AlertDialog.Builder(DexEditorActivity.this)
                                .setTitle("编译成功")
                                .setMessage(outDex.getPath()
                                        + " (" + FileOps.formatSize(outDex.length()) + ")"
                                        + "\n\n是否写回 APK 并重新签名？"
                                        + (dexes.size() > 1 ? "\n\n注意: 多个 dex 已合并为一个，写回时将替换 "
                                        + dexes.get(0) + " 并移除其余 " + (dexes.size() - 1) + " 个 dex。" : "")
                                        + "\n\n（签 v1+v2+v3，产物可直接安装）")
                                .setPositiveButton("写回 APK 并签名", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) { writeBackApk(outDex); }
                                })
                                .setNegativeButton("仅保留 dex 文件", null)
                                .show();
                    } else {
                        Toast.makeText(DexEditorActivity.this,
                                "编译成功: " + outDex.getPath()
                                        + " (" + FileOps.formatSize(outDex.length()) + ")",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    new AlertDialog.Builder(DexEditorActivity.this)
                            .setTitle("编译失败")
                            .setMessage(e + "\n\n通常是某个 smali 语法有误——检查最近改过的类。")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            }});
        }}.start();
    }

    // ---------- 写回 APK（v1.10） ----------

    /** 把编译出的 dex 写回源 APK：替换首个选中 dex 条目、移除其余选中的 dex，重打包+重签名 */
    private void writeBackApk(final File outDex) {
        int mcnt = SmaliTool.dexMethodCount(outDex);
        if (mcnt > 65535) {
            new AlertDialog.Builder(this)
                    .setTitle("无法写回")
                    .setMessage("合并后的 dex 含 " + mcnt + " 个方法引用，超过单 dex 65535 的上限。\n\n"
                            + "解决办法：回到 MultiDex 对话框只勾选需要修改的 dex，改完单独写回。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        final File outApk = new File(apk.getParentFile(), projName + "_dexpp.apk");
        new AlertDialog.Builder(this)
                .setTitle("确认写回")
                .setMessage("输出: " + outApk.getPath()
                        + "\n\n替换: " + dexes.get(0)
                        + (dexes.size() > 1 ? "\n移除: " + dexes.subList(1, dexes.size()) : "")
                        + "\n\n（编辑的是哪几个 dex 就替换/移除哪几个，未选中的 dex 不受影响）")
                .setPositiveButton("写回", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doWriteBack(outDex, outApk); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doWriteBack(final File outDex, final File outApk) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写回 APK …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            File unsigned = null;
            try {
                byte[] dexBytes = readAllBytes(outDex);
                unsigned = new File(getCacheDir(), "dexpp_" + projName + "/unsigned.apk");
                Map<String, byte[]> replace = new HashMap<String, byte[]>();
                replace.put(dexes.get(0), dexBytes);
                java.util.Set<String> remove = new java.util.HashSet<String>();
                for (int i = 1; i < dexes.size(); i++) remove.add(dexes.get(i));
                ApkRepack.repack(apk, replace, remove, unsigned);
                if (outApk.exists()) outApk.delete();
                InputStream ksIn = getAssets().open("hexfile.p12");
                // 与 ARSC 编辑器一致：minSdk 21 → v1+v2+v3 全签
                ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
                ksIn.close();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e == null) {
                    new AlertDialog.Builder(DexEditorActivity.this)
                            .setTitle("写回成功")
                            .setMessage(outApk.getPath()
                                    + "\n\n可直接安装（覆盖原应用需先卸载原版）。")
                            .setPositiveButton("知道了", null).show();
                } else {
                    new AlertDialog.Builder(DexEditorActivity.this)
                            .setTitle("写回失败").setMessage(e)
                            .setPositiveButton("知道了", null).show();
                }
            }});
        }}.start();
    }

    private static byte[] readAllBytes(File f) throws java.io.IOException {
        FileInputStream fi = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            fi.close();
        }
    }

    // ---------- IO 小工具 ----------

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

    private static String readText(File f) {
        try {
            FileInputStream fi = new FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
            fi.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(s.getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {}
    }

    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteR(c);
        f.delete();
    }
}
