package com.hex91.file;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * v1.55.0 高级文件搜索：
 * - 关键词：通配符 * ?（大小写不敏感）或正则（可切换）
 * - 类型过滤：文本 / 图片 / 视频 / 音频 / APK / 压缩包 / 文档
 * - 大小范围：最小 ~ 最大（MB）
 * - 修改时间：距今天数下限 ~ 上限（如 max=7 表示最近 7 天）
 * 关键词留空时仅按条件过滤；所有条件叠加生效。
 */
public class SearchActivity extends BaseActivity {

    public static final String EXTRA_START = "start_dir";

    private static final int MAX = 1000;
    private static final int MAX_DEPTH = 12;

    /** 类型过滤标签（与 TYPE_EXT 一一对应，下标 0 = 全部） */
    private static final String[] TYPES = {
            "全部", "文本", "图片", "视频", "音频", "APK", "压缩包", "文档"
    };

    /** 各类型的扩展名集合（小写，不含点） */
    private static final String[][] TYPE_EXT = {
            null, // 全部
            {"txt", "md", "log", "xml", "html", "htm", "css", "js", "json", "csv",
                    "ini", "conf", "properties", "java", "c", "cpp", "h", "hpp", "py", "sh", "bat", "smali"},
            {"jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "ico"},
            {"mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "3gp", "ts", "webm"},
            {"mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "amr", "opus"},
            {"apk", "apks", "xapk"},
            {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "iso", "jar", "apk.001"},
            {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "mobi", "azw3"}
    };

    private File startDir;
    private final List<File> results = new ArrayList<File>();
    private ResultAdapter adapter;
    private TextView status;
    private volatile boolean searching;

    private EditText etKw, etSizeMin, etSizeMax, etDaysMin, etDaysMax;
    private Spinner spType;
    private android.widget.CheckBox cbRegex;
    private View panelAdv;

    /** 一次搜索的过滤条件（后台线程只读） */
    private static class Filter {
        Pattern namePat;    // null = 不限名称
        boolean regexFind;  // true = 正则 find()，false = 通配 matches()
        int type;           // 0=全部
        long sizeMin = -1, sizeMax = -1;   // 字节
        long dayMin = -1, dayMax = -1;     // 距今天数
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);

        etKw = (EditText) findViewById(R.id.et_keyword);
        status = (TextView) findViewById(R.id.tv_status);
        ListView lv = (ListView) findViewById(R.id.list_result);
        etSizeMin = (EditText) findViewById(R.id.et_size_min);
        etSizeMax = (EditText) findViewById(R.id.et_size_max);
        etDaysMin = (EditText) findViewById(R.id.et_days_min);
        etDaysMax = (EditText) findViewById(R.id.et_days_max);
        spType = (Spinner) findViewById(R.id.sp_type);
        cbRegex = (android.widget.CheckBox) findViewById(R.id.cb_regex);
        panelAdv = findViewById(R.id.panel_adv);

        String start = getIntent().getStringExtra(EXTRA_START);
        startDir = start != null ? new File(start)
                : android.os.Environment.getExternalStorageDirectory();
        ((TextView) findViewById(R.id.tv_start)).setText("搜索范围: " + startDir.getPath());

        ArrayAdapter<String> sa = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, TYPES);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(sa);

        Button btnAdv = (Button) findViewById(R.id.btn_adv);
        btnAdv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                panelAdv.setVisibility(
                        panelAdv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

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
                doSearch();
            }
        });
    }

    /** 读取界面条件；返回 null 表示输入非法（已提示） */
    private Filter buildFilter() {
        Filter f = new Filter();
        String kw = etKw.getText().toString().trim();
        if (!kw.isEmpty()) {
            try {
                if (cbRegex.isChecked()) {
                    f.namePat = Pattern.compile(kw, Pattern.CASE_INSENSITIVE);
                    f.regexFind = true;
                } else {
                    f.namePat = Pattern.compile(wildcardToRegex(kw), Pattern.CASE_INSENSITIVE);
                }
            } catch (PatternSyntaxException e) {
                toast("正则表达式错误: " + e.getDescription());
                return null;
            }
        }
        f.type = spType.getSelectedItemPosition();
        f.sizeMin = parseLong(etSizeMin, -1);
        f.sizeMax = parseLong(etSizeMax, -1);
        f.dayMin = parseLong(etDaysMin, -1);
        f.dayMax = parseLong(etDaysMax, -1);
        if (f.sizeMin >= 0) f.sizeMin *= 1024L * 1024L;
        if (f.sizeMax >= 0) f.sizeMax *= 1024L * 1024L;
        if (f.sizeMax >= 0 && f.sizeMin >= 0 && f.sizeMin > f.sizeMax) {
            toast("大小下限大于上限");
            return null;
        }
        if (f.dayMax >= 0 && f.dayMin >= 0 && f.dayMin > f.dayMax) {
            toast("天数下限大于上限");
            return null;
        }
        boolean any = f.namePat != null || f.type != 0
                || f.sizeMin >= 0 || f.sizeMax >= 0 || f.dayMin >= 0 || f.dayMax >= 0;
        if (!any) { toast("请输入关键词，或在筛选中设置条件"); return null; }
        return f;
    }

    private static long parseLong(EditText et, long def) {
        try {
            String s = et.getText().toString().trim();
            return s.isEmpty() ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void doSearch() {
        final Filter ft = buildFilter();
        if (ft == null) return;
        if (searching) { toast("正在搜索中，请稍候..."); return; }
        results.clear();
        adapter.notifyDataSetChanged();
        searching = true;
        status.setText(filterSummary(ft));
        new Thread(new Runnable() {
            public void run() {
                walk(startDir, ft, 0);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        searching = false;
                        status.setText(filterSummary(ft) + " → 完成，共 "
                                + results.size() + " 个结果"
                                + (results.size() >= MAX ? "（已达上限）" : ""));
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private static String filterSummary(Filter f) {
        StringBuilder sb = new StringBuilder("筛选: ");
        if (f.namePat != null) sb.append(f.regexFind ? "正则 " : "名称 ");
        if (f.type != 0) sb.append(TYPES[f.type]).append(' ');
        if (f.sizeMin >= 0 || f.sizeMax >= 0) sb.append("大小 ");
        if (f.dayMin >= 0 || f.dayMax >= 0) sb.append("时间 ");
        if (sb.length() == 4) sb.append("无");
        return sb.toString();
    }

    private void walk(File dir, Filter ft, int depth) {
        if (dir == null || depth > MAX_DEPTH || results.size() >= MAX) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        long now = System.currentTimeMillis();
        for (File f : kids) {
            if (results.size() >= MAX) break;
            String n = f.getName();
            if (n.equals(Trash.DIR_NAME)) continue;
            if (accept(f, ft, now)) results.add(f);
            if (f.isDirectory()) walk(f, ft, depth + 1);
        }
    }

    /** 条件判定：目录仅受名称过滤（且无类型/大小/时间条件时列出）；文件全条件叠加 */
    private boolean accept(File f, Filter ft, long now) {
        if (ft.namePat != null) {
            boolean m = ft.regexFind
                    ? ft.namePat.matcher(f.getName()).find()
                    : ft.namePat.matcher(f.getName()).matches();
            if (!m) return false;
        }
        if (f.isDirectory()) {
            return ft.type == 0 && ft.sizeMin < 0 && ft.sizeMax < 0 && ft.dayMin < 0 && ft.dayMax < 0;
        }
        if (ft.type != 0 && !matchType(f.getName(), ft.type)) return false;
        long len = f.length();
        if (ft.sizeMin >= 0 && len < ft.sizeMin) return false;
        if (ft.sizeMax >= 0 && len > ft.sizeMax) return false;
        if (ft.dayMin >= 0 || ft.dayMax >= 0) {
            long days = (now - f.lastModified()) / (24L * 3600 * 1000);
            if (days < 0) days = 0;
            if (ft.dayMin >= 0 && days < ft.dayMin) return false;
            if (ft.dayMax >= 0 && days > ft.dayMax) return false;
        }
        return true;
    }

    private static boolean matchType(String name, int type) {
        String[] exts = TYPE_EXT[type];
        if (exts == null) return true;
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) return false;
        String ext = n.substring(dot + 1);
        for (String e : exts) if (e.equals(ext)) return true;
        return false;
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
                    + "  ·  " + (f.isDirectory() ? "文件夹" : FileOps.formatSize(f.length()))
                    + "  ·  " + FileOps.formatTime(f.lastModified());
            detail.setText(d);
            return convert;
        }
    }
}
