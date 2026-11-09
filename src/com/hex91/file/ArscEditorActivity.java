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
import android.widget.CheckBox;
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
import java.util.LinkedHashMap;
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
 *      v1.67.5：确定后立刻在列表里显示新值并标「✎ 已修改」（附原值），
 *      同时重建三级树并把列表滚到刚改的条目 —— 与 AndroidManifest 编辑器一致
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
    /**
     * 待保存修改：key = 条目 + 修改类别（+bag 下标），value = 写盘用的 Edit。
     * v1.67.5：改成按 key 归并 —— 同一条目重复编辑只保留最后一次，改回原值即撤销。
     */
    private final LinkedHashMap<EditKey, ArscWriter.Edit> edits =
            new LinkedHashMap<EditKey, ArscWriter.Edit>();

    /** 有未保存修改的条目（按对象身份），供列表绘制快速判断 */
    private final java.util.Set<ResourceEntry> changedEntries =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ResourceEntry, Boolean>());

    /** edits 的复合键（条目按对象身份区分，与 ArscWriter 的 EntrySpan 身份查找一致） */
    private static final class EditKey {
        final ResourceEntry entry;
        final int kind;
        final int mapIndex;

        EditKey(ResourceEntry entry, int kind, int mapIndex) {
            this.entry = entry;
            this.kind = kind;
            this.mapIndex = mapIndex;
        }

        public boolean equals(Object o) {
            if (!(o instanceof EditKey)) return false;
            EditKey k = (EditKey) o;
            return entry == k.entry && kind == k.kind && mapIndex == k.mapIndex;
        }

        public int hashCode() {
            return System.identityHashCode(entry) * 31 + kind * 7 + mapIndex;
        }
    }

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
        setContentView(R.layout.activity_arscpp);

        Intent it = getIntent();
        String apkPath = it.getStringExtra(EXTRA_APK);
        if (apkPath == null) { finish(); return; }
        apk = new File(apkPath);
        apkBase = stripExt(apk.getName());
        if (it.getStringExtra(EXTRA_ENTRY) != null) entryName = it.getStringExtra(EXTRA_ENTRY);

        titleSub = (TextView) findViewById(R.id.arscpp_sub);
        // v1.67.4：顶栏标题不再沿用旧名「ARSC++」，与实际入口一致
        ((TextView) findViewById(R.id.arscpp_title)).setText("ARSC 编辑器");
        searchBox = (EditText) findViewById(R.id.arscpp_search);
        listView = (ListView) findViewById(R.id.arscpp_list);

        findViewById(R.id.arscpp_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSave(); }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh(false); }
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
                ApkEditSession.overlayPending(apk, entryName, arscFile); // 会话修改优先
                arsc = ArscFile.parse(arscFile);
                buildRows();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                pd.dismiss();
                if (e != null) {
                    new HfDialogBuilder(ArscEditorActivity.this)
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
        // 保留旧展开状态（编辑重建后不丢失折叠位置）——必须在 clear 之前采集
        Map<String, Boolean> typeExp = new HashMap<String, Boolean>();
        Map<String, Boolean> nameExp = new HashMap<String, Boolean>();
        for (Row r : allRows) {
            if (r.isHeader) typeExp.put(r.typeName, Boolean.valueOf(r.expanded));
            else if (r.isName) nameExp.put(r.typeName + "/" + r.name, Boolean.valueOf(r.expanded));
        }
        allRows.clear();
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

    // ---------- 未保存修改的预览（v1.67.5） ----------
    // arsc 对象在保存前不会被改动，所以行文本不能只读 r.value / r.name，
    // 必须叠加 edits 里的待写值，否则「确定」后看不见任何变化。

    /** 该条目的简单值修改（字符串 / 整数），无则 null */
    private ArscWriter.Edit valueEdit(ResourceEntry e) {
        ArscWriter.Edit ed = edits.get(new EditKey(e, ArscWriter.Edit.VALUE_STRING, -1));
        if (ed == null) ed = edits.get(new EditKey(e, ArscWriter.Edit.VALUE_RAW, -1));
        return ed;
    }

    /** bag 条目的待改命名值：mapIndex → 新值文本 */
    private Map<Integer, String> bagOverrides(ResourceEntry e) {
        Map<Integer, String> ov = new LinkedHashMap<Integer, String>();
        List<RawNamedValue> nvs = e.getNamedValues();
        if (nvs == null) return ov;
        for (int i = 0; i < nvs.size(); i++) {
            ArscWriter.Edit ed = edits.get(new EditKey(e, ArscWriter.Edit.MAP_STRING, i));
            if (ed != null) ov.put(Integer.valueOf(i), ed.str);
        }
        return ov;
    }

    /** 该行当前显示的键名（含未保存的重命名） */
    private String currentName(Row r) {
        ArscWriter.Edit ed = edits.get(new EditKey(r.entry, ArscWriter.Edit.NAME, -1));
        return ed != null ? ed.str : (r.name == null ? "" : r.name);
    }

    /** 该行当前显示的值（含未保存的修改） */
    private String displayValue(Row r) {
        ArscWriter.Edit ed = valueEdit(r.entry);
        if (ed != null) {
            if (ed.kind == ArscWriter.Edit.VALUE_STRING) return ed.str;
            RawValue v = r.entry.getSimpleValue();
            return ArscValues.displayTyped(arsc,
                    v == null ? ArscDefs.TYPE_INT_DEC : v.getDataType(), ed.raw);
        }
        if (r.entry.getNamedValues() != null) {
            Map<Integer, String> ov = bagOverrides(r.entry);
            if (!ov.isEmpty()) return ArscValues.bagDisplay(arsc, r.entry, ov);
        }
        return r.value == null ? "" : r.value;
    }

    private boolean isChanged(ResourceEntry e) {
        return changedEntries.contains(e);
    }

    private void putEdit(ArscWriter.Edit e) {
        edits.put(new EditKey(e.entry, e.kind, e.mapIndex), e);
        refreshChangedSet();
    }

    private void removeEdit(ResourceEntry entry, int kind, int mapIndex) {
        edits.remove(new EditKey(entry, kind, mapIndex));
        refreshChangedSet();
    }

    /** dirty 与「已改动条目」集合唯一的重算入口（列表绘制按它打标记 / 上色） */
    private void refreshChangedSet() {
        changedEntries.clear();
        for (ArscWriter.Edit ed : edits.values()) changedEntries.add(ed.entry);
        dirty = !edits.isEmpty();
    }

    // ---------- 列表刷新（浏览 / 搜索） ----------

    private void refresh() { refresh(false); }

    /**
     * @param keepScroll 展开/折叠后保持当前滚动位置（v1.67.4：原来每次 setAdapter 都会跳回顶部）
     */
    private void refresh(boolean keepScroll) {
        int first = listView.getFirstVisiblePosition();
        int top = 0;
        View fv = listView.getChildAt(0);
        if (fv != null) top = fv.getTop();
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
                // 搜索按「当前值」（含未保存修改）匹配，刚改完也能搜到新值
                String cur = displayValue(r);
                if (currentName(r).toLowerCase().contains(q)
                        || cur.toLowerCase().contains(q)
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
                + (dirty ? " · ✎未保存" : ""));
        if (adapter == null) {
            adapter = new RowAdapter();
            listView.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        if (keepScroll) listView.setSelectionFromTop(first, top);
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
                boolean ch = false;
                if (r.children != null) {
                    for (Row nh : r.children) {
                        if (nh.children == null) continue;
                        for (Row leaf : nh.children) {
                            if (isChanged(leaf.entry)) { ch = true; break; }
                        }
                        if (ch) break;
                    }
                }
                TextView tv = new TextView(ctx);
                tv.setText((r.expanded ? "▾ " : "▸ ") + r.typeName + "  (" + r.count + ")"
                        + (ch ? "  ✎" : ""));
                tv.setTextSize(15);
                tv.setTextColor(ThemeManager.accent(ArscEditorActivity.this));
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                box.addView(tv);
            } else if (r.isName) {                  // level 1：资源名
                Row first = (r.children != null && !r.children.isEmpty()) ? r.children.get(0) : null;
                String nm = first != null ? currentName(first) : r.name;
                boolean renamed = first != null && !nm.equals(r.name);
                boolean ch = false;
                if (r.children != null) {
                    for (Row leaf : r.children) {
                        if (isChanged(leaf.entry)) { ch = true; break; }
                    }
                }
                TextView tv = new TextView(ctx);
                tv.setText((r.expanded ? "▾ " : "▸ ") + nm
                        + "   " + r.resId + "  (" + r.count + ")"
                        + (ch || renamed ? "  ✎" : ""));
                tv.setTextSize(14);
                tv.setTextColor(ThemeManager.accent(ArscEditorActivity.this));
                box.addView(tv);
                if (renamed) {
                    TextView old = new TextView(ctx);      // 重命名后保留原名，便于核对
                    old.setText("原资源名: " + r.name);
                    old.setTextSize(11);
                    old.setTextColor(ThemeManager.textSub(ArscEditorActivity.this));
                    box.addView(old);
                }
            } else {                                // level 2：配置值
                boolean changed = isChanged(r.entry);
                TextView val = new TextView(ctx);
                String v = shorten(displayValue(r), 150);
                val.setText("[" + r.config + "] " + v + (changed ? "   ✎ 已修改" : ""));
                val.setTextSize(13);
                val.setTextColor(changed
                        ? ThemeManager.accent(ArscEditorActivity.this)
                        : ThemeManager.text(ArscEditorActivity.this));
                val.setMaxLines(changed ? 6 : 3);
                box.addView(val);
                if (changed) {
                    TextView old = new TextView(ctx);
                    old.setText("原值: " + shorten(r.value == null ? "" : r.value, 150));
                    old.setTextSize(11);
                    old.setTextColor(ThemeManager.textSub(ArscEditorActivity.this));
                    old.setMaxLines(3);
                    box.addView(old);
                }
            }
            return box;
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private void onRowClick(Row r) {
        if (r.level == 0 || r.level == 1) {         // 折叠/展开
            r.expanded = !r.expanded;
            refresh(true);
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
        nameEt.setText(currentName(r));   // 已改过则显示待保存的新名
        nameEt.setTextSize(14);
        box.addView(label("资源名（同资源各配置一起改名）"));
        box.addView(nameEt);

        TextView cfgTv = new TextView(this);
        cfgTv.setText("配置: " + r.config + "    ID: " + r.resId
                + (r.entry.getNamedValues() != null ? "    (bag/复杂条目)" : ""));
        cfgTv.setTextSize(12);
        cfgTv.setTextColor(ThemeManager.textSub(this));
        box.addView(cfgTv);

        final EditText valEt = new EditText(this);
        valEt.setText(displayValue(r));   // 已改过则显示待保存的新值
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

        if (isChanged(r.entry)) {
            TextView old = new TextView(this);
            old.setText("原值: " + shorten(r.value == null ? "" : r.value, 200)
                    + "\n（确定后标记为已修改，保存才写盘）");
            old.setTextSize(11);
            old.setTextColor(ThemeManager.textSub(this));
            box.addView(old);
        }

        new HfDialogBuilder(this)
                .setTitle("编辑资源")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyEdit(r, nameEt.getText().toString(), valEt.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("还原", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // 撤销该资源的所有待保存修改（重命名 + 值）
                        removeRenameGroup(r);
                        removeEdit(r.entry, ArscWriter.Edit.VALUE_STRING, -1);
                        removeEdit(r.entry, ArscWriter.Edit.VALUE_RAW, -1);
                        rebuildAndReveal(r.entry);
                    }
                })
                .show();
    }

    /** bag（style 等复杂条目）：列出命名值，点字符串项可改 */
    private void showBagDialog(final Row r) {
        final List<RawNamedValue> nvs = r.entry.getNamedValues();
        final Map<Integer, String> ov = bagOverrides(r.entry);
        String[] labels = new String[nvs.size()];
        for (int i = 0; i < nvs.size(); i++) {
            String cur = ov.get(Integer.valueOf(i));
            labels[i] = ArscValues.namedValueName(arsc, nvs.get(i))
                    + " = " + (cur != null ? cur : ArscValues.rawDisplay(arsc, nvs.get(i).getRawValue()))
                    + (cur != null ? "   ✎" : "");
        }
        new HfDialogBuilder(this)
                .setTitle(currentName(r) + "  (bag " + nvs.size() + " 项)")
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
        final String orig = ArscValues.rawDisplay(arsc, nv.getRawValue());
        final String cur = bagOverrides(r.entry).get(Integer.valueOf(mapIndex));
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
        et.setText(cur != null ? cur : orig);   // 已改过则显示待保存值
        et.setTextSize(14);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        box.addView(et);
        new HfDialogBuilder(this)
                .setTitle("编辑 bag 值")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString();
                        if (s.equals(orig)) {
                            removeEdit(r.entry, ArscWriter.Edit.MAP_STRING, mapIndex); // 改回原值＝撤销
                        } else if (s.equals(cur)) {
                            // 未变，保持原样
                        } else {
                            putEdit(new ArscWriter.Edit(r.entry, mapIndex, s));
                        }
                        rebuildAndReveal(r.entry);
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("还原", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        removeEdit(r.entry, ArscWriter.Edit.MAP_STRING, mapIndex);
                        rebuildAndReveal(r.entry);
                    }
                })
                .show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(ThemeManager.accent(this));
        return tv;
    }

    /**
     * 应用对话框的修改（v1.67.5）：
     * 1. 先校验，校验失败整单不动（不会出现「名字改了值没改」的半截状态）；
     * 2. 登记待写值 —— arsc 对象本身不动，列表靠 edits 叠加重绘，所以立刻能看到新值；
     * 3. 重建三级树（改名会影响分组）并把列表定位到刚改的条目。
     */
    private void applyEdit(Row r, String newName, String newValue) {
        ResourceEntry e = r.entry;
        String name = newName.trim();
        if (name.length() == 0) {
            toast("资源名不能为空");
            return;
        }

        // ---- 1. 校验值 ----
        RawValue v = e.getSimpleValue();
        boolean editable = v != null && ArscValues.editable(e);
        boolean valueChanged = false;
        Integer raw = null;
        if (editable && !newValue.equals(displayValue(r))) {
            if (!ArscValues.isStringValue(v)) {
                raw = ArscValues.parseInput(v.getDataType(), newValue, arsc);
                if (raw == null) {
                    toast("值格式不符合类型（" + typeHint(v.getDataType()) + "）");
                    return;
                }
            }
            valueChanged = true;
        }

        // ---- 2. 资源名：同一资源的所有配置一起改名 ----
        if (!name.equals(currentName(r))) renameGroup(r, name);

        // ---- 3. 值：改回原值即撤销该处修改 ----
        if (valueChanged) {
            if (ArscValues.isStringValue(v)) {
                if (newValue.equals(r.value)) {
                    removeEdit(e, ArscWriter.Edit.VALUE_STRING, -1);
                } else {
                    putEdit(new ArscWriter.Edit(e, newValue));
                }
            } else if (raw != null) {
                // 用「展示文本」判断是否回到原值：RGB8/ARGB4 等类型解析后高位会被归一化，
                // 直接比 raw 会把「只是大小写不同」的输入误判成改动
                if (ArscValues.displayTyped(arsc, v.getDataType(), raw.intValue()).equals(r.value)) {
                    removeEdit(e, ArscWriter.Edit.VALUE_RAW, -1);
                } else {
                    putEdit(new ArscWriter.Edit(e, raw.intValue()));
                }
            }
        }
        refreshChangedSet();

        // ---- 4. 重绘 + 定位 ----
        rebuildAndReveal(e);
        toast(isChanged(e) ? "已标记修改，记得保存" : "已恢复为原值");
    }

    /** 同一个资源（类型 + 原资源名）的所有配置条目一起改名 */
    private void renameGroup(Row leaf, String newName) {
        for (Row h : allRows) {
            if (!h.isHeader || h.children == null) continue;
            if (!h.typeName.equals(leaf.typeName)) continue;
            for (Row nh : h.children) {
                if (nh.children == null || !leaf.name.equals(nh.name)) continue;
                for (Row c : nh.children) {
                    if (newName.equals(c.name)) {
                        removeEdit(c.entry, ArscWriter.Edit.NAME, -1);   // 改回原名
                    } else {
                        putEdit(new ArscWriter.Edit(c.entry, newName, true));
                    }
                }
                return;
            }
        }
    }

    /** 撤销该资源整组的重命名 */
    private void removeRenameGroup(Row leaf) {
        renameGroup(leaf, leaf.name);
    }

    /**
     * 重建三级树并把列表滚动到目标条目（v1.67.5：改完不再「停在原地看不出变化」）。
     * 改名会改变分组键，所以必须重建；展开状态由 buildRows 保留，这里再强制展开目标所在的两级。
     */
    private void rebuildAndReveal(ResourceEntry target) {
        buildRows();
        Row found = null;
        for (Row h : allRows) {
            if (!h.isHeader || h.children == null) continue;
            boolean hit = false;
            for (Row nh : h.children) {
                if (nh.children == null) continue;
                for (Row c : nh.children) {
                    if (c.entry == target) {
                        h.expanded = true;
                        nh.expanded = true;
                        found = c;
                        hit = true;
                        break;
                    }
                }
                if (hit) break;
            }
            if (hit) break;
        }
        refresh();
        if (found == null) return;
        int pos = viewRows.indexOf(found);
        if (pos < 0 && searchBox.getText().length() > 0) {
            searchBox.setText("");        // 搜索过滤把目标挡住了 → 清空搜索（会同步触发 refresh）
            pos = viewRows.indexOf(found);
        }
        if (pos < 0) return;
        final int p = pos;
        listView.post(new Runnable() {
            public void run() {
                listView.setSelectionFromTop(Math.max(0, p - 1), 0);
            }
        });
    }

    private static String typeHint(int dataType) {
        switch (dataType) {
            case ArscDefs.TYPE_REFERENCE:
            case ArscDefs.TYPE_ATTRIBUTE:
            case ArscDefs.TYPE_DYNAMIC_REFERENCE:
            case ArscDefs.TYPE_DYNAMIC_ATTRIBUTE:
                return "@0x 资源id 或 @type/name";
            case ArscDefs.TYPE_INT_DEC:
                return "十进制整数";
            case ArscDefs.TYPE_INT_HEX:
                return "0x 十六进制";
            case ArscDefs.TYPE_INT_BOOLEAN:
                return "true / false";
            case ArscDefs.TYPE_INT_COLOR_ARGB8:
                return "#aarrggbb";
            case ArscDefs.TYPE_INT_COLOR_RGB8:
                return "#rrggbb";
            case ArscDefs.TYPE_INT_COLOR_ARGB4:
                return "#argb";
            case ArscDefs.TYPE_INT_COLOR_RGB4:
                return "#rgb";
            case ArscDefs.TYPE_FLOAT:
                return "浮点数";
            default:
                return "该类型暂不支持编辑";
        }
    }

    // ---------- 保存：写 arsc → 重打包 → 重签名 ----------

    private void askSave() { askSave(false); }

    /** v1.67.4：保存（含可选自动签名）；exitWhenDone 用于退出拦截 */
    private void askSave(final boolean exitWhenDone) {
        if (arsc == null) { toast("尚未加载完成"); return; }
        if (!dirty) {
            if (exitWhenDone) finish(); else toast("没有修改");
            return;
        }
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView tv = new TextView(this);
        tv.setText("将 " + edits.size() + " 处修改写入 " + entryName
                + " 并直接保存到该 APK：\n\n" + apk.getName()
                + "\n\n（首次修改时自动备份为 同名_bak.apk）");
        tv.setTextSize(15);
        tv.setTextColor(ThemeManager.text(this));
        box.addView(tv);
        final CheckBox autoSign = ApkSignHelper.addAutoSignCheckBox(this, box,
                "保存后自动签名（长按设置密钥 / 签名方案）", true);
        new HfDialogBuilder(this)
                .setTitle("保存修改")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSave(autoSign.isChecked(), exitWhenDone);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSave(final boolean autoSign, final boolean exitWhenDone) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写入 arsc …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        final android.os.Handler ph = new android.os.Handler();
        final long t0 = System.currentTimeMillis();
        final Runnable tick = new Runnable() { public void run() {
            double sec = (System.currentTimeMillis() - t0) / 1000.0;
            pd.setProgress((int) Math.min(90, 90 * (1 - Math.exp(-sec / 6.0))));
            ph.postDelayed(this, 200);
        }};
        ph.post(tick);
        new Thread() { public void run() {
            String err = null;
            try {
                byte[] newArsc = ArscWriter.write(arsc,
                        new ArrayList<ArscWriter.Edit>(edits.values()));
                // v1.66.8：登记 + 会话重打包写回原包（首次修改自动备份 _bak.apk）
                ApkEditSession.putEdit(ArscEditorActivity.this, apk, entryName, newArsc);
                if (autoSign) ApkSignHelper.signInPlace(ArscEditorActivity.this, apk);
                // v1.67.5：保存后以新文件为新基线 —— 重解析 + 重建树，
                // 否则列表还按旧的 arsc 渲染（保存了却看不到新值），且再次保存会重复叠加编辑
                arsc = ArscFile.parse(newArsc);
                buildRows();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String e = err;
            ui.post(new Runnable() { public void run() {
                ph.removeCallbacksAndMessages(null);
                pd.setProgress(100);
                pd.dismiss();
                if (e != null) {
                    new HfDialogBuilder(ArscEditorActivity.this)
                            .setTitle("保存失败").setMessage(e)
                            .setPositiveButton("知道了", null).show();
                } else {
                    edits.clear();          // 已写盘，待写队列清空（基线已换成新文件）
                    refreshChangedSet();
                    refresh();
                    toast(autoSign ? "已保存并签名 → " + apk.getName()
                                   : "已保存 → " + apk.getName() + "（未签名，可能无法安装）");
                    if (exitWhenDone) finish();
                }
            }});
        }}.start();
    }

    // ---------- 退出确认 ----------

    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        float den = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView tv = new TextView(this);
        tv.setText(entryName + " 有 " + edits.size() + " 处修改尚未保存。\n\n" + apk.getName());
        tv.setTextSize(15);
        tv.setTextColor(ThemeManager.text(this));
        box.addView(tv);
        final CheckBox autoSign = ApkSignHelper.addAutoSignCheckBox(this, box,
                "保存后自动签名（长按设置密钥 / 签名方案）", true);
        new HfDialogBuilder(this)
                .setTitle("数据已修改")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSave(autoSign.isChecked(), true);
                    }
                })
                .setNeutralButton("放弃修改", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        edits.clear();
                        refreshChangedSet();
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
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
