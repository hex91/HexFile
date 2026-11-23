package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.apk.ApkToolException;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.AxmlWriter;
import com.hex91.file.core.ArscDefs;
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
import android.widget.CheckBox;
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
    private RowAdapter adapter;

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
        pd.setMessage(getString(R.string.mf_loading));
        pd.setCancelable(false);
        pd.show();
        new Thread() { public void run() {
            String err = null;
            try {
                byte[] data = readZipEntry(apk, "AndroidManifest.xml");
                if (data == null) throw new IllegalStateException("no AndroidManifest.xml entry in this APK");
                axml = AxmlFile.parse(data);
                buildRows();
            } catch (Throwable t) {
                err = t instanceof AxmlFile.AxmlFormatException
                        ? AxmlSyncText.errorText(ManifestEditorActivity.this, t)
                        : t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new HfDialogBuilder(ManifestEditorActivity.this)
                            .setTitle(R.string.mf_load_fail)
                            .setMessage(getString(R.string.mf_load_fail_msg, e))
                            .setPositiveButton(R.string.common_close, new DialogInterface.OnClickListener() {
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
                // 搜索按“当前值”（含未保存修改）匹配，改完也能搜到新值
                String cur = currentValue(r.attr);
                if (cur == null) cur = "";
                if (q.isEmpty() || name.contains(q) || cur.toLowerCase().contains(q)) {
                    viewRows.add(r);
                }
            }
        }
        int attrCount = 0;
        for (Row r : allRows) if (!r.isHeader) attrCount++;
        titleSub.setText(dirty
                ? getString(R.string.mf_sub_dirty, apk.getName(), axml.elements.size(), attrCount)
                : getString(R.string.mf_sub, apk.getName(), axml.elements.size(), attrCount));
        // v1.67.4：复用同一个 adapter，避免每次刷新把列表滚回顶部
        if (adapter == null) {
            adapter = new RowAdapter();
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
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
                // v1.67.4：改动标记改为「✎ 已修改」文字（原来的裸「●」像是多出来的点）
                name.setText(changed
                        ? getString(R.string.mf_row_changed, axml.attrDisplayName(r.attr), r.type)
                        : getString(R.string.mf_row, axml.attrDisplayName(r.attr), r.type));
                name.setTextSize(14);
                name.setTextColor(ThemeManager.text(ManifestEditorActivity.this));
                box.addView(name);
                TextView val = new TextView(ctx);
                // v1.67.4：值必须现算——原来只在 buildRows 时取一次，改完仍显示旧值
                String v = currentValue(r.attr);
                val.setText(changed ? v : "= " + v);
                val.setTextSize(12);
                val.setTextColor(changed
                        ? ThemeManager.accent(ManifestEditorActivity.this)
                        : ThemeManager.textSub(ManifestEditorActivity.this));
                val.setMaxLines(3);
                box.addView(val);
                if (changed) {
                    TextView old = new TextView(ctx);
                    old.setText(getString(R.string.mf_row_old, axml.valueDisplay(r.attr)));
                    old.setTextSize(11);
                    old.setTextColor(ThemeManager.textSub(ManifestEditorActivity.this));
                    old.setMaxLines(2);
                    box.addView(old);
                }
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
        info.setText(getString(a.isString() ? R.string.mf_info_str : R.string.mf_info_bin,
                r.path, r.type));
        info.setTextSize(12);
        info.setTextColor(ThemeManager.textSub(this));
        box.addView(info);

        box.addView(label(getString(R.string.mf_label_value)));
        final EditText valEt = new EditText(this);
        valEt.setText(r.value);
        valEt.setTextSize(14);
        valEt.setSingleLine(false);
        valEt.setMaxLines(6);
        box.addView(valEt);

        String typeHint = a.isString() ? getString(R.string.mf_hint_any)
                : (a.dataType == ArscDefs.TYPE_INT_BOOLEAN ? "true / false"
                : (a.dataType == ArscDefs.TYPE_INT_HEX ? getString(R.string.mf_hint_hex)
                : (a.dataType == ArscDefs.TYPE_REFERENCE ? getString(R.string.mf_hint_ref)
                : (a.dataType == ArscDefs.TYPE_FLOAT ? getString(R.string.mf_hint_float)
                : getString(R.string.mf_hint_int)))));
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.mf_hint_fmt, typeHint));
        hint.setTextSize(12);
        hint.setTextColor(ThemeManager.textSub(this));
        box.addView(hint);

        new HfDialogBuilder(this)
                .setTitle(axml.attrDisplayName(a))
                .setView(box)
                .setPositiveButton(R.string.common_confirm, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = valEt.getText().toString();
                        if (applyEdit(a, v)) refresh();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .setNeutralButton(R.string.mf_revert, new DialogInterface.OnClickListener() {
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
                Toast.makeText(this, getString(R.string.mf_bad_value, axml.typeName(a)),
                        Toast.LENGTH_SHORT).show();
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
        // v1.76.0：原来写死深灰，暗色主题下预览文字几乎看不见
        tv.setTextColor(ThemeManager.text(this));
        // v1.67.4：预览要包含尚未保存的修改
        tv.setText(axml.prettyXml(edits));
        tv.setPadding((int) (12 * den), (int) (12 * den), (int) (12 * den), (int) (12 * den));
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        new HfDialogBuilder(this)
                .setTitle(R.string.mf_preview_title)
                .setView(sc)
                .setPositiveButton(R.string.common_close, null)
                .show();
    }

    // ---------- 保存：写 AXML → 重打包 → 重签名 ----------

    private void askSave() {
        if (axml == null) { toast(getString(R.string.mf_not_loaded)); return; }
        if (!dirty) { toast(getString(R.string.mf_no_changes)); return; }
        LinearLayout box = makeSavePrompt(false);
        final CheckBox autoSign = ApkSignHelper.addAutoSignCheckBox(this, box,
                getString(R.string.apk_autosign_hint), true);
        new HfDialogBuilder(this)
                .setTitle(R.string.mf_save_title)
                .setView(box)
                .setPositiveButton(R.string.common_save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSave(autoSign.isChecked(), false);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 退出拦截（v1.67.4）：有未保存修改 → 保存 / 放弃 / 取消，并可选自动签名 */
    @Override
    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        LinearLayout box = makeSavePrompt(true);
        final CheckBox autoSign = ApkSignHelper.addAutoSignCheckBox(this, box,
                getString(R.string.apk_autosign_hint), true);
        new HfDialogBuilder(this)
                .setTitle(R.string.mf_dirty_title)
                .setView(box)
                .setPositiveButton(R.string.common_save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSave(autoSign.isChecked(), true);
                    }
                })
                .setNeutralButton(R.string.mf_discard, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        edits.clear();
                        dirty = false;
                        finish();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 保存/退出提示的公共内容（文案随入口变化） */
    private LinearLayout makeSavePrompt(boolean exiting) {
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView tv = new TextView(this);
        tv.setText(getString(exiting ? R.string.mf_prompt_exit : R.string.mf_prompt_save,
                edits.size(), apk.getName()));
        tv.setTextSize(15);
        tv.setTextColor(ThemeManager.text(this));
        box.addView(tv);
        return box;
    }

    private void doSave(final boolean autoSign, final boolean exitWhenDone) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.mf_writing));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        final android.os.Handler ph = new android.os.Handler();
        final long t0 = System.currentTimeMillis();
        final Runnable tick = new Runnable() { public void run() {
            double sec = (System.currentTimeMillis() - t0) / 1000.0;
            // 渐近推进：写包/签名耗时不定，避免卡在 0%
            pd.setProgress((int) Math.min(90, 90 * (1 - Math.exp(-sec / 6.0))));
            ph.postDelayed(this, 200);
        }};
        ph.post(tick);
        new Thread() { public void run() {
            String err = null;
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
                        throw new IllegalStateException("read-back mismatch: "
                                + axml.attrDisplayName(ed.attr) + " expected " + ed.newValue
                                + " actual " + check.valueDisplay(fresh));
                }

                // v1.66.8：登记 + 会话重打包写回原包（首次修改自动备份 _bak.apk）
                ApkEditSession.putEdit(ManifestEditorActivity.this,
                        apk, "AndroidManifest.xml", newAxml);
                if (autoSign) ApkSignHelper.signInPlace(ManifestEditorActivity.this, apk);
            } catch (Throwable t) {
                if (t instanceof ApkToolException) {
                    err = ApkSignHelper.errorText(ManifestEditorActivity.this, t);
                } else if (t instanceof AxmlFile.AxmlFormatException) {
                    err = AxmlSyncText.errorText(ManifestEditorActivity.this, t);
                } else {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                Throwable c = t.getCause();
                while (c != null) {
                    err += "\n↳ " + c.getClass().getSimpleName() + ": " + c.getMessage();
                    c = c.getCause();
                }
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                ph.removeCallbacksAndMessages(null);
                pd.setProgress(100);
                pd.dismiss();
                if (e != null) {
                    new HfDialogBuilder(ManifestEditorActivity.this)
                            .setTitle(R.string.mf_save_fail)
                            .setMessage(getString(R.string.mf_save_fail_msg, e))
                            .setPositiveButton(R.string.common_got_it, null).show();
                } else {
                    dirty = false;
                    refresh();
                    Toast.makeText(ManifestEditorActivity.this,
                            getString(autoSign ? R.string.mf_saved_signed : R.string.mf_saved,
                                    apk.getName()),
                            Toast.LENGTH_LONG).show();
                    if (exitWhenDone) finish();
                }
            }});
        }}.start();
    }

    // ---------- IO 小工具 ----------

    /** 签名产物覆盖写回原 APK（后台 + 进度框，完成提示刷新） */
    private static byte[] readZipEntry(File zip, String entryName) throws Exception {
        byte[] pending = ApkEditSession.editBytes(zip, entryName);   // v1.66.0 会话修改优先
        if (pending != null) return pending;
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
        tv.setTextColor(ThemeManager.textSub(this));
        return tv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
