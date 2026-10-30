package com.hex91.file;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * strings 提取查看器（v1.38.0）：
 *  - 后台扫描二进制文件，列出全部可读字符串（ASCII + UTF-16LE，带文件偏移）
 *  - 顶部搜索框实时过滤；点击条目复制该字符串
 *  - 「复制全部」导出所有命中（每行: 偏移  内容）
 */
public class StringsViewerActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";

    private static final int C_BAR = 0xFF212121;      // 顶栏（MT 同款深色）
    private static final int C_TX = 0xFF37474F;
    private static final int C_SUB = 0xFF78909C;
    private static final int C_U16 = 0xFF6A1B9A;      // UTF-16 标记色

    private File file;
    private List<StringExtract.Hit> all = new ArrayList<StringExtract.Hit>();
    private final List<StringExtract.Hit> shown = new ArrayList<StringExtract.Hit>();
    private HitAdapter adapter;
    private TextView countView;
    private String lastSummary = "";
    private boolean truncated = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        file = new File(getIntent().getStringExtra(EXTRA_FILE));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // 顶栏：标题
        TextView title = new TextView(this);
        title.setText("strings 提取\n" + file.getName());
        title.setTextSize(14);
        title.setTextColor(ThemeManager.topBarText(this));
        title.setBackgroundColor(C_BAR);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title);

        // 搜索框
        EditText search = new EditText(this);
        search.setHint("过滤字符串…");
        search.setTextSize(13);
        search.setSingleLine(true);
        root.addView(search);

        countView = new TextView(this);
        countView.setText("正在扫描…");
        countView.setTextSize(12);
        countView.setTextColor(C_SUB);
        countView.setPadding(pad, (int) (4 * getResources().getDisplayMetrics().density),
                pad, (int) (4 * getResources().getDisplayMetrics().density));
        root.addView(countView);

        // 复制全部按钮
        android.widget.Button copyAll = new android.widget.Button(this);
        copyAll.setText("复制全部");
        copyAll.setTextSize(12);
        root.addView(copyAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        adapter = new HitAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                StringExtract.Hit h = shown.get(pos);
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("string", h.text));
                toast("已复制: " + (h.text.length() > 40 ? h.text.substring(0, 40) + "…" : h.text));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable s) { refilter(s.toString()); }
        });
        copyAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyAll(); }
        });

        // 后台扫描
        new Thread(new Runnable() {
            public void run() {
                final StringExtract.Result r = StringExtract.extract(file, 4);
                runOnUiThread(new Runnable() {
                    public void run() {
                        all = r.hits;
                        truncated = r.truncated;
                        refilter("");
                        String head = file.getName() + " · 扫描 " + r.scannedBytes / 1024 + " KB · 命中 " + all.size();
                        if (r.error != null) {
                            lastSummary = "扫描失败: " + r.error;
                        } else {
                            lastSummary = head + (truncated ? "（超上限已截断）" : "");
                        }
                        countView.setText(lastSummary);
                    }
                });
            }
        }, "strings").start();
    }

    private void refilter(String q) {
        shown.clear();
        if (q == null || q.isEmpty()) {
            shown.addAll(all);
        } else {
            String ql = q.toLowerCase();
            for (StringExtract.Hit h : all) {
                if (h.text.toLowerCase().contains(ql)) shown.add(h);
            }
        }
        adapter.notifyDataSetChanged();
        countView.setText(q == null || q.isEmpty() ? lastSummary
                : "过滤 \"" + q + "\" → " + shown.size() + " 条 / 共 " + all.size() + " 条");
    }

    private void copyAll() {
        if (shown.isEmpty()) { toast("无内容可复制"); return; }
        StringBuilder sb = new StringBuilder();
        for (StringExtract.Hit h : shown) {
            sb.append(StringExtract.hex(h.offset)).append(h.utf16 ? "  U  " : "    ")
              .append(h.text).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("strings", sb.toString()));
        toast("已复制 " + shown.size() + " 条");
    }

    private class HitAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int position, View convertView, ViewGroup parent) {
            StringExtract.Hit h = shown.get(position);
            LinearLayout ll = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : new LinearLayout(StringsViewerActivity.this);
            ll.setOrientation(LinearLayout.HORIZONTAL);
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            ll.setPadding(pad, (int) (4 * getResources().getDisplayMetrics().density),
                    pad, (int) (4 * getResources().getDisplayMetrics().density));
            if (ll.getChildCount() == 0) {
                TextView off = new TextView(StringsViewerActivity.this);
                off.setTextSize(11);
                off.setTypeface(android.graphics.Typeface.MONOSPACE);
                off.setTextColor(C_SUB);
                off.setPadding(0, 0, (int) (10 * getResources().getDisplayMetrics().density), 0);
                ll.addView(off, new LinearLayout.LayoutParams(
                        (int) (76 * getResources().getDisplayMetrics().density),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView tx = new TextView(StringsViewerActivity.this);
                tx.setTextSize(13);
                tx.setSingleLine(true);
                ll.addView(tx, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            TextView off = (TextView) ll.getChildAt(0);
            TextView tx = (TextView) ll.getChildAt(1);
            off.setText(StringExtract.hex(h.offset) + (h.utf16 ? " U" : ""));
            tx.setText(h.text);
            tx.setTextColor(h.utf16 ? C_U16 : C_TX);
            return ll;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
