package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jadx.core.xmlgen.entry.RawNamedValue;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC 编辑器++（对标 MT Manager 同名功能）：
 *   1. 从 APK/zip 提取 resources.arsc，基于 jadx 的二进制解析器（Apache-2.0）解码
 *   2. 类型分组列表（可折叠）+ 实时搜索（名称/值/资源ID）
 *   3. 点条目编辑：字符串值 / 整数布尔颜色 / 引用 / 条目名；bag 条目可逐项编辑
 *   4. 💾 保存：写 arsc（池追加 + 4 字节补丁）→ 重打包（.so 4096 对齐）→ 设备端重签名 → 安装
 */
public class ArscEditorActivity extends BaseActivity {

    public static final String EXTRA_APK = "apk";      // zip/apk 路径
    public static final String EXTRA_ENTRY = "entry";  // zip 内 arsc 条目名，默认 resources.arsc

    /** 列表行：三级树（level 0 = 类型，level 1 = 资源名，level 2 = 配置值） */
    private static class Row {
        boolean isHeader;      // level 0 类型头
        boolean isName;        // level 1 资源名头
        int level;
        String typeName;
        int count;
        boolean expanded;
        List<Row> children;    // level0/1 的子行
        // level 2 值行
        ResourceEntry entry;
        String name, config, value, resId;
    }

    private File apk;
    private String entryName = "resources.arsc";
    private String apkBase;
    private ArscFile arsc;
    private boolean dirty;
    private final List<ArscWriter.Edit> edits = new ArrayList<ArscWriter.Edit>();

    private final List<Row> allRows = new ArrayList<Row>();
    private final List<Row> viewRows = new ArrayList<Row>();

    private TextView titleSub;
    private EditText searchBox;
    private ListView listView;

    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arscpp);

        Intent it = getIntent();
        String apkPath = it.getStringExtra(EXTRA_APK);
        if (apkPath == null) { finish(); return; }
        apk = new File(apkPath);
        apkBase = stripExt(apk.getName());
        if (it.getStringExtra(EXTRA_ENTRY) != null) entryName = it.getStringExtra(EXTRA_ENTRY);

        titleSub = (TextView) findViewById(R.id.arscpp_sub);
        searchBox = (EditText) findViewById(R.id.arscpp_search);
        listView = (ListView) findViewById(R.id.arscpp_list);

        findViewById(R.id.arscpp_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSave(); }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh(); }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos < viewRows.size()) onRowClick(viewRows.get(pos));
            }
        });

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
                buildRows();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(ArscEditorActivity.this)
                            .setTitle("加载失败").setMessage(e)
                            .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { finish(); }
                            }).show();
                } else {
                    refresh();
                }
            }});
        }}.start();
    }

    /** 三级树组行：类型 → 资源名 → 各配置值（与 Dex 编辑器同风格） */
    private void buildRows() {
        allRows.clear();
        // 保留旧展开状态（编辑重建后不丢失折叠位置）
        Map<String, Boolean> typeExp = new HashMap<String, Boolean>();
        Map<String, Boolean> nameExp = new HashMap<String, Boolean>();
        for (Row r : allRows) {
            if (r.isHeader) typeExp.put(r.typeName, r.expanded);
            else if (r.isName) nameExp.put(r.typeName + "/" + r.name, r.expanded);
        }
        Map<String, Map<String, List<Row>>> tree =
                new java.util.LinkedHashMap<String, Map<String, List<Row>>>();
        for (ResourceEntry e : arsc.storage.getResources()) {
            Row r = new Row();
            r.entry = e;
            r.level = 2;
            r.typeName = e.getTypeName() == null ? "?" : e.getTypeName();
            r.name = e.getKeyName() == null ? "?" : e.getKeyName();
            r.config = e.getConfig() == null ? "" : e.getConfig();
            r.value = ArscValues.valueDisplay(arsc, e);
            r.resId = ArscValues.resIdStr(e.getId());
            Map<String, List<Row>> names = tree.get(r.typeName);
            if (names == null) {
                names = new java.util.LinkedHashMap<String, List<Row>>();
                tree.put(r.typeName, names);
            }
            List<Row> cfgs = names.get(r.name);
            if (cfgs == null) { cfgs = new ArrayList<Row>(); names.put(r.name, cfgs); }
            cfgs.add(r);
        }
        List<String> types = new ArrayList<String>(tree.keySet());
        Collections.sort(types, String.CASE_INSENSITIVE_ORDER);
        for (String t : types) {
            Map<String, List<Row>> names = tree.get(t);
            Row h = new Row();
            h.isHeader = true;
            h.level = 0;
            h.typeName = t;
            Boolean oldT = typeExp.get(t);
            h.expanded = oldT != null ? oldT.booleanValue() : t.equals("string");
            h.children = new ArrayList<Row>();
            List<String> ns = new ArrayList<String>(names.keySet());
            Collections.sort(ns, String.CASE_INSENSITIVE_ORDER);
            int leaves = 0;
            for (String n : ns) {
                List<Row> cfgs = names.get(n);
                Collections.sort(cfgs, new Comparator<Row>() {
                    public int compare(Row a, Row b) {
                        return a.config.compareTo(b.config);
                    }
                });
                Row nh = new Row();
                nh.isName = true;
                nh.level = 1;
                nh.typeName = t;
                nh.name = n;
                nh.resId = cfgs.get(0).resId;
                nh.count = cfgs.size();
                Boolean oldN = nameExp.get(t + "/" + n);
                nh.expanded = oldN != null ? oldN.booleanValue() : false;
                nh.children = cfgs;
                h.children.add(nh);
                leaves += cfgs.size();
                allRows.add(nh);
                allRows.addAll(cfgs);
            }
            h.count = leaves;
            allRows.add(h);
        }
    }

    // ---------- 列表刷新（浏览 / 搜索） ----------

    private void refresh() {
        viewRows.clear();
        String q = searchBox.getText().toString().trim().toLowerCase();
        if (q.isEmpty()) {
            for (Row h : allRows) {
                if (!h.isHeader) continue;
                viewRows.add(h);
                if (!h.expanded) continue;
                for (Row nh : h.children) {
                    viewRows.add(nh);
                    if (!nh.expanded) continue;
                    viewRows.addAll(nh.children);
                }
            }
        } else {
            // 搜索：命中的值行沿 类型→资源名 链自动展开显示
            Map<String, Map<String, List<Row>>> hit =
                    new java.util.LinkedHashMap<String, Map<String, List<Row>>>();
            for (Row r : allRows) {
                if (r.isHeader || r.isName) continue;
                if (r.name.toLowerCase().contains(q)
                        || r.value.toLowerCase().contains(q)
                        || r.resId.toLowerCase().contains(q)
                        || r.config.toLowerCase().contains(q)) {
                    Map<String, List<Row>> nm = hit.get(r.typeName);
                    if (nm == null) {
                        nm = new java.util.LinkedHashMap<String, List<Row>>();
                        hit.put(r.typeName, nm);
                    }
                    List<Row> b = nm.get(r.name);
                    if (b == null) { b = new ArrayList<Row>(); nm.put(r.name, b); }
                    b.add(r);
                }
            }
            List<String> types = new ArrayList<String>(hit.keySet());
            Collections.sort(types, String.CASE_INSENSITIVE_ORDER);
            for (String t : types) {
                Row h = new Row();
                h.isHeader = true; h.level = 0; h.typeName = t;
                h.expanded = true; h.children = new ArrayList<Row>();
                Map<String, List<Row>> nm = hit.get(t);
                int leaves = 0;
                for (Map.Entry<String, List<Row>> en : nm.entrySet()) {
                    Row nh = new Row();
                    nh.isName = true; nh.level = 1; nh.typeName = t; nh.name = en.getKey();
                    nh.resId = en.getValue().get(0).resId;
                    nh.count = en.getValue().size();
                    nh.expanded = true; nh.children = en.getValue();
                    h.children.add(nh);
                    leaves += en.getValue().size();
                }
                h.count = leaves;
                viewRows.add(h);
                for (Row nh : h.children) {
                    viewRows.add(nh);
                    viewRows.addAll(nh.children);
                }
            }
        }
        int total = 0;
        for (Row r : allRows) if (!r.isHeader && !r.isName) total++;
        titleSub.setText(pkgLine() + " · " + total + " 个值"
                + (dirty ? " · ●未保存" : ""));
        listView.setAdapter(new RowAdapter());
    }

    private String pkgLine() {
        if (arsc == null) return entryName;
        return apk.getName() + " · " + arsc.appPackage();
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return viewRows.size(); }
        public Object getItem(int i) { return viewRows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            final ArscEditorActivity ctx = (ArscEditorActivity) parent.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            Row r = viewRows.get(i);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (10 * den);
            int indent = (int) (r.level * 18 * den);
            box.setPadding(indent + pad, pad, pad, pad);
            if (r.isHeader) {                       // level 0：类型
                TextView tv = new TextView(ctx);
                tv.setText((r.expanded ? "▾ " : "▸ ") + r.typeName + "  (" + r.count + ")");
                tv.setTextSize(15);
                tv.setTextColor(ThemeManager.accent(ArscEditorActivity.this));
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(tv);
            } else if (r.isName) {                  // level 1：资源名
                TextView tv = new TextView(ctx);
                tv.setText((r.expanded ? "▾ " : "▸ ") + r.name
                        + "   " + r.resId + "  (" + r.count + ")");
                tv.setTextSize(14);
                tv.setTextColor(ThemeManager.accent(ArscEditorActivity.this));
                box.addView(tv);
            } else {                                // level 2：配置值
                TextView val = new TextView(ctx);
                String v = r.value == null ? "" : r.value;
                val.setText("[" + r.config + "] "
                        + (v.length() > 150 ? v.substring(0, 150) + "…" : v));
                val.setTextSize(13);
                val.setTextColor(ThemeManager.text(ArscEditorActivity.this));
                val.setMaxLines(3);
                box.addView(val);
            }
            return box;
        }
    }

    private void onRowClick(Row r) {
        if (r.level == 0 || r.level == 1) {         // 折叠/展开
            r.expanded = !r.expanded;
            refresh();
        } else if (r.entry.getNamedValues() != null) {
            showBagDialog(r);
        } else {
            showEditDialog(r);
        }
    }

    // ---------- 编辑对话框 ----------

    private void showEditDialog(final Row r) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * den);
        box.setPadding(pad, pad, pad, pad);

        final EditText nameEt = new EditText(this);
        nameEt.setText(r.name);
        nameEt.setTextSize(14);
        box.addView(label("资源名"));
        box.addView(nameEt);

        TextView cfgTv = new TextView(this);
        cfgTv.setText("配置: " + r.config + "    ID: " + r.resId
                + (r.entry.getNamedValues() != null ? "    (bag/复杂条目)" : ""));
        cfgTv.setTextSize(12);
        cfgTv.setTextColor(ThemeManager.textSub(this));
        box.addView(cfgTv);

        final EditText valEt = new EditText(this);
        valEt.setText(r.value);
        valEt.setTextSize(14);
        RawValue v = r.entry.getSimpleValue();
        boolean editable = v != null && ArscValues.editable(r.entry);
        if (v != null && ArscValues.isStringValue(v)) {
            valEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            valEt.setMinLines(2);
        } else if (editable) {
            valEt.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        valEt.setEnabled(editable);
        box.addView(label("值"));
        box.addView(valEt);

        new AlertDialog.Builder(this)
                .setTitle("编辑资源")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyEdit(r, nameEt.getText().toString(), valEt.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** bag（style 等复杂条目）：列出命名值，点字符串项可改 */
    private void showBagDialog(final Row r) {
        final List<RawNamedValue> nvs = r.entry.getNamedValues();
        String[] labels = new String[nvs.size()];
        for (int i = 0; i < nvs.size(); i++) {
            labels[i] = ArscValues.namedValueName(arsc, nvs.get(i))
                    + " = " + ArscValues.rawDisplay(arsc, nvs.get(i).getRawValue());
        }
        new AlertDialog.Builder(this)
                .setTitle(r.name + "  (bag " + nvs.size() + " 项)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        RawNamedValue nv = nvs.get(which);
                        if (ArscValues.isStringValue(nv.getRawValue())) {
                            showNamedValueDialog(r, which);
                        } else {
                            toast("该项类型暂不支持编辑: 0x"
                                    + Integer.toHexString(nv.getRawValue().getDataType()));
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showNamedValueDialog(final Row r, final int mapIndex) {
        final List<RawNamedValue> nvs = r.entry.getNamedValues();
        final RawNamedValue nv = nvs.get(mapIndex);
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * den);
        box.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText(ArscValues.namedValueName(arsc, nv));
        t.setTextSize(14);
        t.setTextColor(ThemeManager.accent(this));
        box.addView(t);
        final EditText et = new EditText(this);
        et.setText(ArscValues.rawDisplay(arsc, nv.getRawValue()));
        et.setTextSize(14);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("编辑 bag 值")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString();
                        if (!s.equals(ArscValues.rawDisplay(arsc, nv.getRawValue()))) {
                            edits.add(new ArscWriter.Edit(r.entry, mapIndex, s));
                            dirty = true;
                            buildRows();
                            refresh();
                            toast("已修改（记得保存）");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(ThemeManager.accent(this));
        return tv;
    }

    private void applyEdit(Row r, String newName, String newValue) {
        ResourceEntry e = r.entry;
        boolean changed = false;
        if (!newName.equals(r.name)) {
            edits.add(new ArscWriter.Edit(e, newName, true));
            r.name = newName;
            changed = true;
        }
        RawValue v = e.getSimpleValue();
        if (v != null && !newValue.equals(r.value)) {
            if (ArscValues.isStringValue(v)) {
                edits.add(new ArscWriter.Edit(e, newValue));
                changed = true;
            } else {
                Integer raw = ArscValues.parseInput(v.getDataType(), newValue, arsc);
                if (raw == null) {
                    toast("数值格式错误或该类型不支持编辑");
                    return;
                }
                edits.add(new ArscWriter.Edit(e, raw.intValue()));
                changed = true;
            }
        }
        if (changed) {
            dirty = true;
            buildRows();   // 全量重建：同步更新三级树的资源名头与值
            refresh();
            toast("已修改（记得保存）");
        }
    }

    // ---------- 保存：写 arsc → 重打包 → 重签名 ----------

    private void askSave() {
        if (arsc == null) { toast("尚未加载完成"); return; }
        if (!dirty) { toast("没有修改"); return; }
        final File outApk = new File(apk.getParentFile(), apkBase + "_arscpp.apk");
        new AlertDialog.Builder(this)
                .setTitle("保存并重新签名")
                .setMessage("将修改写入 " + entryName + " 并重打包签名：\n\n" + outApk.getPath()
                        + "\n\n（.so 保持 4096 对齐，签 v1+v2+v3，可直接安装）")
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doSave(outApk); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSave(final File outApk) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写入 arsc …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            File unsigned = null;
            try {
                byte[] newArsc = ArscWriter.write(arsc, edits);
                unsigned = new File(getCacheDir(), "arscpp_" + apkBase + "/unsigned.apk");
                Map<String, byte[]> replace = new HashMap<String, byte[]>();
                replace.put(entryName, newArsc);
                ApkRepack.repack(apk, replace, unsigned);
                if (outApk.exists()) outApk.delete();
                InputStream ksIn = getAssets().open("hexfile.p12");
                // minSdk 21 → v1+v2+v3 全签（覆盖 Android 5.0+；仅 v2/v3 无法装到 API<24 设备）
                ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
                ksIn.close();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(ArscEditorActivity.this)
                            .setTitle("保存失败").setMessage(e)
                            .setPositiveButton("知道了", null).show();
                } else {
                    dirty = false;
                    refresh();
                    new AlertDialog.Builder(ArscEditorActivity.this)
                            .setTitle("已保存并签名")
                            .setMessage("已签名保存为:\n" + outApk.getPath()
                                    + "\n\n「写回原 APK」= 用此修改版覆盖原 APK（覆盖后重新打开该 APK 即为修改版）")
                            .setPositiveButton("写回原 APK",
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    writeBack(outApk, apk);
                                }
                            })
                            .setNeutralButton("安装", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { installApk(outApk); }
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                }
            }});
        }}.start();
    }

    private void installApk(File apkFile) {
        try {
            Uri uri = Uri.parse("content://com.hex91.file.files" + apkFile.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("无法启动安装: " + e);
        }
    }

    /** 签名产物覆盖写回原 APK（后台 + 进度框） */
    private void writeBack(final File signed, final File orig) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写回原 APK …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(signed);
                java.io.FileOutputStream out = new java.io.FileOutputStream(orig);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String e = err;
            runOnUiThread(new Runnable() { public void run() {
                pd.dismiss();
                toast(e != null ? "写回失败: " + e
                        : "已写回原 APK（重新打开即可看到修改）");
            }});
        }}.start();
    }

    // ---------- 退出确认 ----------

    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
                .setTitle("放弃未保存的修改？")
                .setMessage("修改尚未写入 apk")
                .setPositiveButton("保存并退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { askSave(); }
                })
                .setNegativeButton("直接退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNeutralButton("取消", null)
                .show();
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

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
        fi.close();
        return bo.toByteArray();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
