package com.hex91.file;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 递归文件搜索：支持 * ? 通配符，大小写不敏感，结果点击可跳到所在目录 */
public class SearchActivity extends BaseActivity {

    public static final String EXTRA_START = "start_dir";

    private File startDir;
    private final List<File> results = new ArrayList<File>();
    private ResultAdapter adapter;
    private TextView status;
    private volatile boolean searching;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);

        EditText et = (EditText) findViewById(R.id.et_keyword);
        status = (TextView) findViewById(R.id.tv_status);
        ListView lv = (ListView) findViewById(R.id.list_result);

        String start = getIntent().getStringExtra(EXTRA_START);
        startDir = start != null ? new File(start)
                : android.os.Environment.getExternalStorageDirectory();
        ((TextView) findViewById(R.id.tv_start)).setText("搜索范围: " + startDir.getPath());

        adapter = new ResultAdapter(results);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                File f = results.get(i);
                Intent it = new Intent(SearchActivity.this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                it.putExtra("dir", f.getParent());
                startActivity(it);
            }
        });

        Button btn = (Button) findViewById(R.id.btn_search);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String kw = ((EditText) findViewById(R.id.et_keyword))
                        .getText().toString().trim();
                doSearch(kw);
            }
        });
    }

    private void doSearch(final String kw) {
        if (kw.isEmpty()) { toast("请输入关键词，支持 * ? 通配"); return; }
        if (searching) { toast("正在搜索中，请稍候..."); return; }
        results.clear();
        adapter.notifyDataSetChanged();
        searching = true;
        status.setText("搜索中...");
        new Thread(new Runnable() {
            public void run() {
                final Pattern p = Pattern.compile(wildcardToRegex(kw), Pattern.CASE_INSENSITIVE);
                walk(startDir, p, 0);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        searching = false;
                        status.setText("完成，共 " + results.size() + " 个结果"
                                + (results.size() >= MAX ? "（已达上限）" : ""));
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private static final int MAX = 1000;
    private static final int MAX_DEPTH = 12;

    private void walk(File dir, Pattern p, int depth) {
        if (dir == null || depth > MAX_DEPTH || results.size() >= MAX) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (results.size() >= MAX) break;
            String n = f.getName();
            if (n.equals(Trash.DIR_NAME)) continue;
            if (p.matcher(n).matches()) results.add(f);
            if (f.isDirectory()) walk(f, p, depth + 1);
        }
    }

    /** 通配符转正则：* → .* ，? → . ，其余转义 */
    static String wildcardToRegex(String w) {
        StringBuilder sb = new StringBuilder();
        for (char c : w.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else {
                if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) sb.append('\\');
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class ResultAdapter extends BaseAdapter {

        private final List<File> data;

        ResultAdapter(List<File> data) { this.data = data; }

        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = android.view.LayoutInflater.from(SearchActivity.this)
                        .inflate(R.layout.item_result, parent, false);
            }
            File f = data.get(i);
            TextView name = (TextView) convert.findViewById(R.id.name);
            TextView detail = (TextView) convert.findViewById(R.id.detail);
            name.setText(f.getName());
            String d = f.getParent()
                    + "  ·  " + (f.isDirectory() ? "文件夹" : FileOps.formatSize(f.length()));
            detail.setText(d);
            return convert;
        }
    }
}
