package com.hex91.file;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manifest 编辑器++（v1.11，对标 MT 的 AndroidManifest 编辑）：
 *   1. 解析 APK 内 AndroidManifest.xml（二进制 AXML，AxmlFile 自研 span 解析）
 *   2. 元素分组 + 属性列表（android:versionCode / package / uses-permission…）
 *   3. 点属性 → 编辑对话框（string 值走池追加，int/hex/bool/ref 走 4 字节补丁）
 *   4. 👀 预览 pretty XML（只读）
 *   5. 💾 保存 → AxmlWriter.write → ApkRepack(replace AndroidManifest.xml) → v1+v2+v3 重签名
 */
public class ManifestEditorActivity extends BaseActivity {

    public static final String EXTRA_APK = "apk";

    private static class Row {
        boolean isHeader;
        AxmlFile.XElem elem;     // header 行
        AxmlFile.XAttr attr;     // 属性行
        String path, value, type;
    }

    private File apk;
    private String apkBase;
    private AxmlFile axml;
    private boolean dirty;
    /** 已编辑属性 → 新值（保持插入序；同一属性重复编辑覆盖） */
    private final LinkedHashMap<AxmlFile.XAttr, String> edits =
            new LinkedHashMap<AxmlFile.XAttr, String>();

    private final List<Row> allRows = new ArrayList<Row>();
    private final List<Row> viewRows = new ArrayList<Row>();

    private TextView titleSub;
    private EditText searchBox;
    private ListView listView;

    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manifestpp);

        Intent it = getIntent();
        String apkPath = it.getStringExtra(EXTRA_APK);
        if (apkPath == null) { finish(); return; }
        apk = new File(apkPath);
        apkBase = stripExt(apk.getName());

        titleSub = (TextView) findViewById(R.id.mfpp_sub);
        searchBox = (EditText) findViewById(R.id.mfpp_search);
        listView = (ListView) findViewById(R.id.mfpp_list);

        findViewById(R.id.mfpp_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSave(); }
        });
        findViewById(R.id.mfpp_xml).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPreview(); }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh(); }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos < viewRows.size() && !viewRows.get(pos).isHeader)
                    showEditDialog(viewRows.get(pos));
            }
        });

        loadManifest();
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    // ---------- 加载 ----------

    private void loadManifest() {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("解析 AndroidManifest.xml …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            try {
                byte[] data = readZipEntry(apk, "AndroidManifest.xml");
                if (data == null) throw new IllegalStateException("APK 内未找到 AndroidManifest.xml");
                axml = AxmlFile.parse(data);
                buildRows();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(ManifestEditorActivity.this)
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

    private void buildRows() {
        allRows.clear();
        for (AxmlFile.XElem el : axml.elements) {
            if (el.attrs.isEmpty()) continue;   // 无属性的元素（如 end 元素）不占行
            Row h = new Row();
            h.isHeader = true;
            h.elem = el;
            h.path = el.path;
            allRows.add(h);
            for (AxmlFile.XAttr a : el.attrs) {
                Row r = new Row();
                r.attr = a;
                r.path = el.path;
                r.value = currentValue(a);
                r.type = axml.typeName(a);
                allRows.add(r);
            }
        }
    }

    /** 当前值（编辑过则显示编辑值） */
    private String currentValue(AxmlFile.XAttr a) {
        String edited = edits.get(a);
        return edited != null ? edited : axml.valueDisplay(a);
    }

    // ---------- 列表 ----------

    private void refresh() {
        viewRows.clear();
        String q = searchBox.getText().toString().trim().toLowerCase();
        for (Row r : allRows) {
            if (r.isHeader) {
                if (q.isEmpty() || r.path.toLowerCase().contains(q)) viewRows.add(r);
            } else {
                String name = axml.attrDisplayName(r.attr).toLowerCase();
                if (q.isEmpty() || name.contains(q) || r.value.toLowerCase().contains(q)) {
                    viewRows.add(r);
                }
            }
        }
        int attrCount = 0;
        for (Row r : allRows) if (!r.isHeader) attrCount++;
        titleSub.setText(apk.getName() + " · " + axml.elements.size() + " 个元素 · "
                + attrCount + " 个属性" + (dirty ? " · ●未保存" : ""));
        listView.setAdapter(new RowAdapter());
    }

    private class RowAdapter extends BaseAdapter {
        public int getCount() { return viewRows.size(); }
        public Object getItem(int i) { return viewRows.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            final ManifestEditorActivity ctx = (ManifestEditorActivity) parent.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            Row r = viewRows.get(i);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (12 * den);
            box.setPadding(pad, pad, pad, pad);
            if (r.isHeader) {
                TextView tv = new TextView(ctx);
                int d = r.elem.depth;
                StringBuilder indent = new StringBuilder();
                for (int k = 0; k < d && k < 6; k++) indent.append("· ");
                tv.setText(indent + "‹" + r.path + "›");
                tv.setTextSize(15);
                tv.setTextColor(ThemeManager.accent(ManifestEditorActivity.this));
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(tv);
            } else {
                boolean changed = edits.containsKey(r.attr);
                TextView name = new TextView(ctx);
                name.setText(axml.attrDisplayName(r.attr)
                        + "   [" + r.type + "]" + (changed ? " ●" : ""));
                name.setTextSize(14);
                name.setTextColor(ThemeManager.text(ManifestEditorActivity.this));
                box.addView(name);
                TextView val = new TextView(ctx);
                String v = r.value == null ? "" : r.value;
                val.setText(changed ? v : "= " + v);
                val.setTextSize(12);
                val.setTextColor(changed
                        ? ThemeManager.accent(ManifestEditorActivity.this)
                        : ThemeManager.textSub(ManifestEditorActivity.this));
                val.setMaxLines(3);
                box.addView(val);
            }
            return box;
        }
    }

    // ---------- 编辑对话框 ----------

    private void showEditDialog(final Row r) {
        final AxmlFile.XAttr a = r.attr;
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * den);
        box.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText("元素: " + r.path + "\n类型: " + r.type
                + (a.isString() ? "（字符串 → 写回时追加进字符串池）" : "（非字符串 → 4 字节原位补丁）"));
        info.setTextSize(12);
        info.setTextColor(ThemeManager.textSub(this));
        box.addView(info);

        box.addView(label("值"));
        final EditText valEt = new EditText(this);
        valEt.setText(r.value);
        valEt.setTextSize(14);
        valEt.setSingleLine(false);
        valEt.setMaxLines(6);
        box.addView(valEt);

        String typeHint = a.isString() ? "任意文本"
                : (a.dataType == ArscDefs.TYPE_INT_BOOLEAN ? "true / false"
                : (a.dataType == ArscDefs.TYPE_INT_HEX ? "0x 十六进制"
                : (a.dataType == ArscDefs.TYPE_REFERENCE ? "@0x 资源 id"
                : (a.dataType == ArscDefs.TYPE_FLOAT ? "浮点数" : "十进制整数"))));
        TextView hint = new TextView(this);
        hint.setText("输入格式: " + typeHint);
        hint.setTextSize(12);
        hint.setTextColor(ThemeManager.textSub(this));
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(axml.attrDisplayName(a))
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = valEt.getText().toString();
                        if (applyEdit(a, v)) refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("还原", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (edits.remove(a) != null) {
                            dirty = !edits.isEmpty();
                            refresh();
                        }
                    }
                })
                .show();
    }

    /** 校验并登记编辑；返回是否合法 */
    private boolean applyEdit(AxmlFile.XAttr a, String v) {
        if (!a.isString()) {
            Long parsed = AxmlWriter.parseValue(axml, a, v);
            if (parsed == null) {
                Toast.makeText(this, "值格式不符合类型 " + axml.typeName(a), Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        String orig = axml.valueDisplay(a);
        if (v.equals(orig)) {
            edits.remove(a);   // 与原值相同 → 取消编辑
        } else {
            edits.put(a, v);
        }
        dirty = !edits.isEmpty();
        return true;
    }

    // ---------- 预览 ----------

    private void showPreview() {
        if (axml == null) return;
        float den = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextColor(0xFF37474F);
        tv.setText(axml.prettyXml());
        tv.setPadding((int) (12 * den), (int) (12 * den), (int) (12 * den), (int) (12 * den));
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("AndroidManifest.xml（只读预览）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ---------- 保存：写 AXML → 重打包 → 重签名 ----------

    private void askSave() {
        if (axml == null) { toast("尚未加载完成"); return; }
        if (!dirty) { toast("没有修改"); return; }
        final File outApk = new File(apk.getParentFile(), apkBase + "_mfpp.apk");
        new AlertDialog.Builder(this)
                .setTitle("保存并重新签名")
                .setMessage("将 " + edits.size() + " 处修改写回 AndroidManifest.xml 并重打包签名：\n\n"
                        + outApk.getPath() + "\n\n（签 v1+v2+v3，可直接安装）")
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doSave(outApk); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSave(final File outApk) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写入 AndroidManifest.xml …");
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            File unsigned = null;
            try {
                List<AxmlWriter.Edit> list = new ArrayList<AxmlWriter.Edit>();
                for (Map.Entry<AxmlFile.XAttr, String> en : edits.entrySet())
                    list.add(new AxmlWriter.Edit(en.getKey(), en.getValue()));
                byte[] newAxml = AxmlWriter.write(axml, list);

                // 回读校验：XAttr 的值是解析时缓存的字段（valueDisplay 不现读 buffer），
                // 必须用新文件里同位置的属性对象（写回不改结构，元素/属性顺序不变）
                AxmlFile check = AxmlFile.parse(newAxml);
                for (AxmlWriter.Edit ed : list) {
                    int ei = axml.elements.indexOf(ed.attr.owner);
                    int ai = axml.elements.get(ei).attrs.indexOf(ed.attr);
                    AxmlFile.XAttr fresh = check.elements.get(ei).attrs.get(ai);
                    if (!ed.newValue.equals(check.valueDisplay(fresh)))
                        throw new IllegalStateException("回读校验失败: "
                                + axml.attrDisplayName(ed.attr) + " 期望 " + ed.newValue
                                + " 实际 " + check.valueDisplay(fresh));
                }

                unsigned = new File(getCacheDir(), "mfpp_" + apkBase + "/unsigned.apk");
                // 关键：FileOutputStream 不会自动创建父目录，必须手动 mkdirs，
                // 否则 repack 写未签名文件时抛 FileNotFoundException → “保存失败”
                File up = unsigned.getParentFile();
                if (up != null && !up.exists()) up.mkdirs();
                Map<String, byte[]> replace = new java.util.HashMap<String, byte[]>();
                replace.put("AndroidManifest.xml", newAxml);
                ApkRepack.repack(apk, replace, unsigned);
                if (outApk.exists()) outApk.delete();
                InputStream ksIn = getAssets().open("hexfile.p12");
                ApkSignerTool.sign(ksIn, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
                ksIn.close();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
                Throwable c = t.getCause();
                while (c != null) {
                    err += "\n↳ " + c.getClass().getSimpleName() + ": " + c.getMessage();
                    c = c.getCause();
                }
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new AlertDialog.Builder(ManifestEditorActivity.this)
                            .setTitle("保存失败").setMessage(e)
                            .setPositiveButton("知道了", null).show();
                } else {
                    dirty = false;
                    refresh();
                    new AlertDialog.Builder(ManifestEditorActivity.this)
                            .setTitle("已生成并签名")
                            .setMessage("已签名保存为:\n" + outApk.getPath()
                                    + "\n\n「写回原 APK」= 用此修改版覆盖原 APK（覆盖后重新打开该 APK 即为修改版）")
                            .setPositiveButton("写回原 APK",
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    writeBack(outApk, apk);
                                }
                            })
                            .setNeutralButton("直接安装",
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    installSigned(outApk);
                                }
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                }
            }});
        }}.start();
    }

    // ---------- IO 小工具 ----------

    /** 签名产物覆盖写回原 APK（后台 + 进度框，完成提示刷新） */
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
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    toast("写回失败: " + e);
                } else {
                    toast("已写回原 APK（重新打开即可看到修改）");
                }
            }});
        }}.start();
    }

    /** 调起系统安装器安装签名产物（与 MainActivity.installApk 同通道） */
    private void installSigned(File f) {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://" + getPackageName() + ".files" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("安装失败: " + e.getMessage());
        }
    }

    private static byte[] readZipEntry(File zip, String entryName) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) return null;
            InputStream in = zf.getInputStream(ze);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9E9E9E);
        return tv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
