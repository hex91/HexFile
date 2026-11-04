package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
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
 *   3. 类树浏览（包可展开/收起，目录图标随主题，单链包合并为 com.baidu 格式）+ 实时搜索类名
 *   4. 点类 → 直接进自带文本编辑器改 smali（v1.63.1 去掉选择对话框；
 *      编辑器顶栏「</>」图标一键转 Java；长按列表项直接反编译；v1.66.9 返回静默保存）
 *   5. ⚙ 编译：整个工程 smali → 单个 classes.dex（跨 dex 合并），输出到应用内部缓存
 *      工程目录（v1.66.10 起，不再在 APK 旁生成 <名>_dexpp 目录）
 *   6. 写回 APK（v1.66.9 单一流程）：编译（进度条）→ 更新确认弹窗（含自动签名复选框，
 *      长按复选框进签名设置：内置/自定义密钥 + v1/v2/v3 方案）→ 写回 → 签名 → 完成。
 *      退出编辑器时有未写回 smali → 单弹窗「是否编译并保存文件」走同一流程。
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
    /**
     * v1.66.6：smali 未写回检测基线——mtime 大于此值的 smali 视为「改了但没编译写回」。
     * 工程加载成功时初始化（复用旧工程则读 .editbase 续用，防重开丢失状态），
     * 写回 APK 成功后刷新。仅 APK 工程用（独立 dex 无签名概念）。
     */
    private long editBaseAt;

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
    private RowAdapter adapter;
    private final List<Object> rows = new ArrayList<Object>(); // Node 或 String(类path，搜索态)
    private final Map<String, File> classFiles = new HashMap<String, File>();
    /** v1.63.0：smali_ 目录名 → 对应 dex 文件（反编译时用作 CFR 类型库） */
    private final Map<String, File> smaliDirDex = new HashMap<String, File>();
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
        // v1.66.9：单一 adapter + notifyDataSetChanged——展开/收起不重建列表，滚动位置原地保持
        adapter = new RowAdapter();
        listView.setAdapter(adapter);

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
        // v1.63.1：长按类 → 直接反编译为 Java（平时单击直接进 smali 编辑）
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= rows.size()) return false;
                Object o = rows.get(pos);
                String path = (o instanceof Node) ? ((Node) o).isClass ? ((Node) o).path : null : (String) o;
                if (path == null) return false;
                File f = classFiles.get(path);
                if (f != null) { openJava(f); return true; }
                return false;
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
                            // v1.66.0：会话中该 dex 有未落地修改则优先
                            ApkEditSession.overlayPending(apk, dexName, src);
                        }
                        String base = stripExt(new File(dexName).getName());
                        File outDir = new File(workDir, "smali_" + base);
                        smaliDirDex.put(outDir.getName(), src);
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
                    initEditBase();
                    titleSub.setText(dexes.size() + " 个 dex · " + classCount + " 个类 · 临时工程");
                    refresh();
                }
            }});
        }}.start();
    }

    // ---------- v1.66.6：smali 未写回检测（退出拦截） ----------

    /** 加载成功后初始化未写回检测基线：复用旧工程读 .editbase 续用，否则记当前时间 */
    private void initEditBase() {
        if (apk == null) return;
        File f = new File(workDir, ".editbase");
        if (f.isFile()) {
            try {
                editBaseAt = Long.parseLong(readText(f).toString().trim());
                return;
            } catch (Throwable ignored) {}
        }
        editBaseAt = System.currentTimeMillis();
        saveEditBase();
    }

    private void saveEditBase() {
        try { writeText(new File(workDir, ".editbase"), String.valueOf(editBaseAt)); }
        catch (Throwable ignored) {}
    }

    /** 工程里是否有 mtime 晚于基线的 smali（= 改了但没编译写回 APK） */
    private boolean hasUnwrittenSmali() {
        if (apk == null || workDir == null || !workDir.isDirectory() || editBaseAt <= 0) return false;
        for (File dir : workDir.listFiles()) {
            if (!dir.isDirectory() || !dir.getName().startsWith("smali_")) continue;
            if (hasNewer(dir, editBaseAt)) return true;
        }
        return false;
    }

    private static boolean hasNewer(File dir, long base) {
        File[] cs = dir.listFiles();
        if (cs == null) return false;
        for (File c : cs) {
            if (c.isDirectory()) { if (hasNewer(c, base)) return true; }
            else if (c.getName().endsWith(".smali") && c.lastModified() > base) return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        // v1.66.9：有未写回的 smali → 唯一一次弹窗「是否编译并保存文件」，
        // 确定后走 编译（进度条）→ 更新压缩包（含自动签名复选框）→ 完成退出
        if (apk != null && hasUnwrittenSmali()) {
            new AlertDialog.Builder(this)
                    .setTitle("smali 修改尚未写回")
                    .setMessage("是否编译并保存文件？\n\n"
                            + "工程里的 smali 改动还没有写回 " + apk.getName()
                            + "，不编译保存的话这些修改不会生效。")
                    .setPositiveButton("编译并保存", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { compileAndOfferUpdate(true); }
                    })
                    .setNegativeButton("不保存退出", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { finish(); }
                    })
                    .setNeutralButton("取消", null)
                    .show();
            return;
        }
        super.onBackPressed();
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
        mergeChains(root);
        sortNode(root);   // 合并后的节点名（com.baidu）重新参与排序
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

    /**
     * v1.66.9：单链包合并——只有一个子包的包链条合并为一个节点，
     * 按 java 包格式显示（com.baidu）；类节点不参与合并。
     */
    private void mergeChains(Node n) {
        for (Node c : n.children) mergeChains(c);
        for (int i = 0; i < n.children.size(); i++) {
            Node c = n.children.get(i);
            while (!c.isClass && c.children.size() == 1 && !c.children.get(0).isClass) {
                Node only = c.children.get(0);
                c.name = c.name + "." + only.name;
                c.children = only.children;
            }
        }
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
        // v1.66.9：复用单一 adapter + notifyDataSetChanged——
        // ListView 原地刷新，展开/收起时点击项位置不变（子项插在其后），不再跳回顶部
        adapter.notifyDataSetChanged();
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

        private TextView baseTv(float den) {
            TextView tv = new TextView(DexEditorActivity.this);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setIncludeFontPadding(false);
            tv.setPadding(0, 0, (int) (8 * den), 0);
            return tv;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            float den = getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(DexEditorActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int depth = 0;
            Object o = rows.get(i);
            if (o instanceof Node) {
                Node n = (Node) o;
                depth = n.depth;
                if (n.isClass) {
                    TextView tv = baseTv(den);
                    tv.setTextColor(ThemeManager.accent(DexEditorActivity.this));
                    tv.setText("● " + n.name);
                    row.addView(tv);
                } else {
                    // v1.66.9：目录图标（随主题变色；展开态换色区分）
                    FolderIconView ic = new FolderIconView(DexEditorActivity.this);
                    ic.setOpen(n.expanded);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            (int) (18 * den), (int) (14 * den));
                    lp.rightMargin = (int) (7 * den);
                    row.addView(ic, lp);
                    TextView tv = baseTv(den);
                    tv.setTextColor(ThemeManager.text(DexEditorActivity.this));
                    tv.setText(n.name);
                    row.addView(tv);
                }
            } else {
                TextView tv = baseTv(den);
                tv.setTextColor(ThemeManager.accent(DexEditorActivity.this));
                tv.setText("● " + (String) o);
                row.addView(tv);
            }
            row.setPadding((int) (16 * den + depth * 18 * den), (int) (10 * den), 0, (int) (10 * den));
            return row;
        }
    }

    /** v1.66.9：自绘目录图标（资源管理器风格），颜色取主题色，展开/收起形态不同 */
    private static final class FolderIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean open;

        FolderIconView(android.content.Context c) { super(c); }

        void setOpen(boolean o) { open = o; }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float tabH = h * 0.34f, r = w * 0.14f;
            int color = ThemeManager.textSub(getContext());
            p.setColor(color);
            if (!open) {
                // 收起：左上小耳 + 主体圆角矩形
                RectF tab = new RectF(0, 0, w * 0.46f, tabH + r);
                cv.drawRoundRect(tab, r, r, p);
                RectF body = new RectF(0, tabH, w, h);
                cv.drawRoundRect(body, r, r, p);
            } else {
                // 展开：背板 + 前板后倾（颜色用主题强调色区分）
                p.setColor(ThemeManager.accent(getContext()));
                RectF back = new RectF(0, 0, w * 0.58f, tabH * 1.15f);
                cv.drawRoundRect(back, r, r, p);
                Path front = new Path();
                front.moveTo(0, tabH * 0.75f);
                front.lineTo(w, tabH * 0.75f);
                front.lineTo(w * 0.88f, h);
                front.lineTo(w * 0.06f, h);
                front.close();
                cv.drawPath(front, p);
            }
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

    /**
     * v1.63.1：单击类 → 直接进 smali 编辑（不再弹选择对话框）；
     * 编辑器顶栏有「转 Java」图标可一键反编译；长按列表项 → 直接反编译。
     */
    private void openSmali(File f) {
        Intent i = new Intent(this, TextEditorActivity.class);
        i.putExtra(TextEditorActivity.EXTRA_FILE, f.getAbsolutePath());
        i.putExtra(TextEditorActivity.EXTRA_J2S_CLS, clsOf(f));
        // v1.66.9：返回静默保存，不再弹「是否保存」——写回集中在退出本编辑器时
        i.putExtra(TextEditorActivity.EXTRA_SILENT_SAVE, true);
        File dx = dexOf(f);
        if (dx != null)
            i.putExtra(TextEditorActivity.EXTRA_J2S_DEX, dx.getAbsolutePath());
        startActivity(i);
    }

    /** 长按类 → 直接反编译为 Java 源码（DecompileActivity）。 */
    private void openJava(File f) {
        Intent i = new Intent(this, DecompileActivity.class);
        i.putExtra(DecompileActivity.EXTRA_SMALI, f.getAbsolutePath());
        i.putExtra(DecompileActivity.EXTRA_CLS, clsOf(f));
        File dx = dexOf(f);
        if (dx != null)
            i.putExtra(DecompileActivity.EXTRA_DEX, dx.getAbsolutePath());
        startActivity(i);
    }

    /** smali 文件 → 类路径（com/foo/Bar）。 */
    private String clsOf(File f) {
        File cur = f.getParentFile();
        while (cur != null) {
            File par = cur.getParentFile();
            if (par != null && workDir != null && par.equals(workDir)) {
                String rel = f.getAbsolutePath().substring(cur.getAbsolutePath().length() + 1);
                return rel.substring(0, rel.length() - 6); // 去掉 .smali
            }
            cur = par;
        }
        String n = f.getName();
        return n.endsWith(".smali") ? n.substring(0, n.length() - 6) : n;
    }

    /** smali 文件 → 它所属 dex（用于生成反编译类型库）。 */
    private File dexOf(File f) {
        File cur = f;
        while (cur != null) {
            File par = cur.getParentFile();
            if (par != null && workDir != null && par.equals(workDir)) {
                return smaliDirDex.get(cur.getName());
            }
            cur = par;
        }
        return null;
    }

    // ---------- 编译 + 更新压缩包（v1.66.9 统一流程） ----------

    private void askCompile() {
        if (root == null) {
            Toast.makeText(this, "工程尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        compileAndOfferUpdate(false);
    }

    /**
     * 编译工程 → 弹「更新压缩包」确认（含自动签名复选框）→ 写回 → 签名。
     * 全程仅两个弹窗：进度条 + 更新确认；不再有 编译确认/写回确认/已保存 三个弹窗。
     * @param exitWhenDone 退出拦截触发的流程完成后结束本 Activity
     */
    private void compileAndOfferUpdate(final boolean exitWhenDone) {
        if (root == null) {
            Toast.makeText(this, "工程尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        // v1.66.10：编译输出放应用内部缓存工程目录（不再在 APK/Dex 旁生成 <名>_dexpp 目录）
        final File outDex = new File(workDir, "out_classes.dex");

        // 进度条（v1.67.0：横向真实走动——smali 编译拿不到阶段回调，用渐近曲线平滑推进，完成打满）
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("正在编译");
        pd.setMessage("把全部 smali 编译为 dex…（几千个类需 10-60 秒）");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        final android.os.Handler ph = new android.os.Handler();
        final long t0 = System.currentTimeMillis();
        final Runnable tick = new Runnable() { public void run() {
            double sec = (System.currentTimeMillis() - t0) / 1000.0;
            pd.setProgress((int) Math.min(90, 90 * (1 - Math.exp(-sec / 12.0))));
            ph.postDelayed(this, 200);
        }};
        ph.post(tick);

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
            // v1.66.10：单 dex 模式直接原地写回（原文件首次备份到工程缓存，模型与 APK 一致）
            if (err == null && apk == null) {
                try {
                    File bak = new File(workDir, "origin_" + singleDex.getName());
                    if (!bak.isFile()) copyFile(singleDex, bak);
                    File tmp = new File(workDir, "repl_tmp.dex");
                    copyFile(outDex, tmp);
                    ApkEditSession.safeReplace(tmp, singleDex);
                    outDex.delete();
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                ph.removeCallbacksAndMessages(null);
                pd.setProgress(100);
                pd.dismiss();
                if (e == null) {
                    if (apk != null) askUpdateArchive(outDex, exitWhenDone);
                    else {
                        Toast.makeText(DexEditorActivity.this,
                                "已写回 → " + singleDex.getName()
                                        + "（原文件已备份到工程缓存）",
                                Toast.LENGTH_LONG).show();
                        if (exitWhenDone) finish();
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

    /** 编译成功 → 「哪个 dex 已被修改，是否更新压缩包」+ 自动签名复选框（长按进签名设置） */
    private void askUpdateArchive(final File outDex, final boolean exitWhenDone) {
        // 64K 方法数守卫
        int mcnt = SmaliTool.dexMethodCount(outDex);
        if (mcnt > 65535) {
            new AlertDialog.Builder(this)
                    .setTitle("无法写回")
                    .setMessage("合并后的 dex 含 " + mcnt + " 个方法引用，超过单 dex 65535 的上限。\n\n"
                            + "解决办法：回到 MultiDex 对话框只勾选需要修改的 dex，改完单独写回。")
                    .setPositiveButton("知道了", null).show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append(dexes.get(0)).append(" 已被修改");
        if (dexes.size() > 1)
            msg.append("\n\n多个 dex 已合并为一个：写回时将替换 ").append(dexes.get(0))
               .append(" 并移除其余 ").append(dexes.size() - 1).append(" 个 dex");
        msg.append("\n\n是否更新到压缩包 ").append(apk.getName()).append(" ？")
           .append("\n（首次修改自动备份 同名_bak.apk）");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView tv = new TextView(this);
        tv.setText(msg.toString());
        tv.setTextSize(15);
        tv.setTextColor(ThemeManager.text(this));
        box.addView(tv);
        final CheckBox autoSign = new CheckBox(this);
        autoSign.setText("自动签名（长按可设置密钥与签名方案）");
        autoSign.setTextSize(14);
        autoSign.setChecked(true);
        autoSign.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showSignSettings();
                return true;
            }
        });
        box.addView(autoSign);

        new AlertDialog.Builder(this)
                .setTitle("编译成功")
                .setView(box)
                .setPositiveButton("更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doUpdateArchive(outDex, autoSign.isChecked(), exitWhenDone);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doUpdateArchive(final File outDex, final boolean autoSign, final boolean exitWhenDone) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写回 " + apk.getName() + " …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        final android.os.Handler ph = new android.os.Handler();
        final long t0 = System.currentTimeMillis();
        final Runnable tick = new Runnable() { public void run() {
            double sec = (System.currentTimeMillis() - t0) / 1000.0;
            pd.setProgress((int) Math.min(90, 90 * (1 - Math.exp(-sec / 8.0))));
            ph.postDelayed(this, 200);
        }};
        ph.post(tick);
        new Thread() { public void run() {
            String err = null;
            try {
                byte[] dexBytes = readAllBytes(outDex);
                // v1.66.8：登记进 ApkEditSession → 重打包写回原包（首次修改自动备份 _bak.apk）
                ApkEditSession.putEdit(DexEditorActivity.this, apk, dexes.get(0), dexBytes);
                if (dexes.size() > 1)
                    ApkEditSession.putRemove(DexEditorActivity.this, apk,
                            dexes.subList(1, dexes.size()));
                // 未写回检测基线刷新（之后的 smali 改动才算未写回）
                editBaseAt = System.currentTimeMillis();
                saveEditBase();
                // v1.66.10：中间产物已写进包，删除内部缓存副本
                outDex.delete();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (err == null && autoSign) {
                try {
                    signApkByPrefs();
                } catch (Throwable t) {
                    // 签名失败不算写回失败——包已更新，仅提示
                    err = "已写回，但签名失败: " + t.getMessage();
                }
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                ph.removeCallbacksAndMessages(null);
                pd.setProgress(100);
                pd.dismiss();
                if (e == null) {
                    Toast.makeText(DexEditorActivity.this,
                            autoSign ? "已写回并签名 → " + apk.getName()
                                     : "已写回 → " + apk.getName()
                                       + "（未签名，可能无法安装）",
                            Toast.LENGTH_LONG).show();
                    if (exitWhenDone) finish();
                } else {
                    new AlertDialog.Builder(DexEditorActivity.this)
                            .setTitle("完成（有提示）").setMessage(e)
                            .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    if (exitWhenDone) finish();
                                }
                            }).show();
                }
            }});
        }}.start();
    }

    // ---------- v1.66.9 签名设置（长按「自动签名」复选框） ----------

    private static final String[] SCHEMES = {"v1+v2+v3（推荐）", "v1", "v2", "v3", "v1+v2", "v2+v3"};

    private SharedPreferences signPrefs() {
        return getSharedPreferences("dexpp_sign", MODE_PRIVATE);
    }

    /** 按签名设置原地签名 apk（临时文件 → 原子覆盖） */
    private void signApkByPrefs() throws Exception {
        SharedPreferences sp = signPrefs();
        boolean v1, v2, v3;
        switch (sp.getInt("scheme", 0)) {
            case 1: v1 = true; v2 = false; v3 = false; break;
            case 2: v1 = false; v2 = true; v3 = false; break;
            case 3: v1 = false; v2 = false; v3 = true; break;
            case 4: v1 = true; v2 = true; v3 = false; break;
            case 5: v1 = false; v2 = true; v3 = true; break;
            default: v1 = true; v2 = true; v3 = true; break;
        }
        File tmp = new File(apk.getParentFile(), apk.getName() + ".signtmp");
        if (tmp.exists()) tmp.delete();
        if (sp.getInt("ks_mode", 0) == 1) {
            // v1.67.0：从签名管理读取已导入密钥（密码一并保存，无需再从文件加载）
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.defaultEntry(this);
            if (e == null) throw new IllegalStateException(
                    "自定义签名已失效（可能已删除），请在自动签名设置中重新选择");
            if (e.broken()) throw new IllegalStateException(
                    "该签名的密码已损坏（换机/清除数据导致），请到「签名管理」重新导入");
            byte[] ksBytes = SignKeyManagerActivity.loadKeyBytes(this, e.id);
            ApkSignerTool.signAutoAlias(ksBytes,
                    e.storePass.toCharArray(),
                    e.keyPass != null ? e.keyPass.toCharArray() : null,
                    e.alias, apk, tmp, 21, v1, v2, v3);
        } else {
            java.io.InputStream ks = getAssets().open("hexfile.p12");
            try {
                ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, tmp, 21, v1, v2, v3);
            } finally {
                ks.close();
            }
        }
        ApkEditSession.safeReplace(tmp, apk);
        ApkEditSession.markSigned(apk);
    }

    /** 签名设置弹窗：密钥（内置 / 已导入签名，点选进入选择列表）/ 签名方案下拉；确定后持久化 */
    private void showSignSettings() {
        final SharedPreferences sp = signPrefs();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        float den = getResources().getDisplayMetrics().density;

        TextView l1 = new TextView(this);
        l1.setText("签名密钥（点选更换）");
        l1.setTextSize(13);
        l1.setTextColor(ThemeManager.textSub(this));
        box.addView(l1);
        final TextView ksSel = new TextView(this);
        ksSel.setTextSize(15);
        ksSel.setTextColor(ThemeManager.accent(this));
        ksSel.setPadding(0, (int) (6 * den), 0, (int) (10 * den));
        ksSel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showKeyPicker(); }
        });
        box.addView(ksSel);
        ksSelRef = ksSel;
        updateKeySelText(ksSel);

        TextView l2 = new TextView(this);
        l2.setText("签名方案");
        l2.setTextSize(13);
        l2.setTextColor(ThemeManager.textSub(this));
        l2.setPadding(0, (int) (10 * den), 0, 0);
        box.addView(l2);
        final Spinner scheme = new Spinner(this);
        scheme.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, SCHEMES));
        scheme.setSelection(sp.getInt("scheme", 0));
        box.addView(scheme);

        new AlertDialog.Builder(this)
                .setTitle("自动签名设置")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sp.edit()
                          .putInt("scheme", scheme.getSelectedItemPosition())
                          .apply();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「签名密钥」行当前选择文案 */
    private void updateKeySelText(TextView tv) {
        SharedPreferences sp = signPrefs();
        if (sp.getInt("ks_mode", 0) == 1) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.defaultEntry(this);
            tv.setText(e == null ? "自定义签名已失效（点选更换）"
                    : e.broken() ? "自定义签名密码已损坏（点选更换/重新导入）"
                    : "自定义签名：" + e.name + "（点选更换）");
        } else {
            tv.setText("内置签名文件（点选更换）");
        }
    }

    /** 密钥选择列表：内置 / 已导入签名（★默认）/ 管理签名… */
    private void showKeyPicker() {
        final SharedPreferences sp = signPrefs();
        final List<SignKeyManagerActivity.KeyEntry> keys = new ArrayList<SignKeyManagerActivity.KeyEntry>();
        // 枚举已导入签名（复用管理页的存储）
        File dir = new File(getFilesDir(), "signkeys");
        File[] fs = dir.listFiles();
        List<String> ids = new ArrayList<String>();
        if (fs != null) for (File f : fs)
            if (f.getName().endsWith(".meta")) ids.add(f.getName().substring(0, f.getName().length() - 5));
        java.util.Collections.sort(ids);
        String defId = sp.getString("key_id", null);
        if (defId != null && ids.remove(defId)) ids.add(0, defId);
        for (String id : ids) {
            SignKeyManagerActivity.KeyEntry e = SignKeyManagerActivity.loadEntry(this, id);
            if (e != null) keys.add(e);
        }

        List<String> items = new ArrayList<String>();
        items.add("内置签名文件");
        String defId2 = sp.getString("key_id", null);
        for (SignKeyManagerActivity.KeyEntry e : keys)
            items.add((e.id.equals(defId2) ? "★ " : "") + e.name
                    + (e.broken() ? "  ⚠ 密码已损坏"
                                  : (e.keyCount > 0 ? "（" + e.keyCount + " 个私钥别名）" : "")));
        items.add("管理签名…（导入 / 查看证书信息 / 删除）");

        new AlertDialog.Builder(this)
                .setTitle("选择签名密钥")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            sp.edit().putInt("ks_mode", 0).remove("key_id").apply();
                        } else if (which == items.size() - 1) {
                            startActivity(new Intent(DexEditorActivity.this,
                                    SignKeyManagerActivity.class));
                        } else {
                            SignKeyManagerActivity.KeyEntry e = keys.get(which - 1);
                            if (e.broken()) {
                                new AlertDialog.Builder(DexEditorActivity.this)
                                        .setTitle(e.name)
                                        .setMessage("该签名的密码已无法解密（换机/恢复出厂/清除数据后 "
                                                + "Android Keystore 密钥丢失）。请到「签名管理」删除后重新导入。")
                                        .setPositiveButton("去签名管理", new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) {
                                                startActivity(new Intent(DexEditorActivity.this,
                                                        SignKeyManagerActivity.class));
                                            }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                return;
                            }
                            sp.edit().putInt("ks_mode", 1).putString("key_id", e.id).apply();
                        }
                        updateKeySelText(ksSelRef);
                    }
                })
                .show();
    }

    /** showKeyPicker 选择后刷新「签名密钥」行文案（弱引用当前行） */
    private TextView ksSelRef;

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

    /** 文件复制（内部缓存目录内使用，v1.66.10 单 dex 原地写回用） */
    private static void copyFile(File src, File dst) throws java.io.IOException {
        FileInputStream fi = null;
        FileOutputStream fo = null;
        try {
            fi = new FileInputStream(src);
            fo = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) fo.write(buf, 0, n);
        } finally {
            // 两个流都可能在对方构造前打开成功，finally 内 null 安全关闭
            if (fo != null) try { fo.close(); } catch (Exception ignored) {}
            if (fi != null) try { fi.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean extractZipEntry(File zip, String entryName, File out) {
        ZipFile zf = null;
        InputStream in = null;
        FileOutputStream fo = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) return false;
            in = zf.getInputStream(ze);
            fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            // v1.67.1：任一步抛异常也必须关闭，否则 Windows 下句柄锁死文件
            if (fo != null) try { fo.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 读文本；失败返回 ""（调用方按「无内容」处理：project.ini 不一致会触发重建，
     * .editbase 读不到会用当前时间，均安全）。流在 finally 关闭。
     */
    private static String readText(File f) {
        FileInputStream fi = null;
        try {
            fi = new FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (fi != null) try { fi.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeText(File f, String s) {
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(f);
            fo.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (fo != null) try { fo.close(); } catch (Exception ignored) {}
        }
    }

    private static void deleteR(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteR(c);
        f.delete();
    }
}
