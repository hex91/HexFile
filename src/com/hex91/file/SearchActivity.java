package com.hex91.file;

import com.hex91.file.R;
import com.hex91.file.fs.*;
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
    /** 主线程回调（进度/收尾），避免每次都 new Handler */
    private Handler ui;

    private EditText etKw, etSizeMin, etSizeMax, etDaysMin, etDaysMax;
    private EditText etContent;          // v1.81.0 内容搜索
    private android.widget.CheckBox cbIgnore;
    private Button btnSearch;
    private Spinner spType;
    private android.widget.CheckBox cbRegex;
    private View panelAdv;

    /** v1.81.0：内容命中片段（key = 绝对路径），结果列表第二行显示 */
    private final java.util.HashMap<String, String> snippets =
            new java.util.HashMap<String, String>();
    /** 内容搜索可随时停止 */
    private volatile boolean cancel;
    private volatile int scannedFiles;
    /** 内容搜索单文件上限 */
    private static final long CONTENT_MAX_BYTES = 4L * 1024 * 1024;
    /** 内容搜索最多扫多少个文件（防止误点根目录扫一天） */
    private static final int MAX_SCAN_FILES = 50000;

    /** 一次搜索的过滤条件（后台线程只读） */
    private static class Filter {
        Pattern namePat;    // null = 不限名称
        boolean regexFind;  // true = 正则 find()，false = 通配 matches()
        int type;           // 0=全部
        long sizeMin = -1, sizeMax = -1;   // 字节
        long dayMin = -1, dayMax = -1;     // 距今天数
        String content;     // v1.81.0：文件内容包含；null = 不搜内容
        boolean ignoreCase;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);
        ui = new Handler(Looper.getMainLooper());

        etKw = (EditText) findViewById(R.id.et_keyword);
        status = (TextView) findViewById(R.id.tv_status);
        ListView lv = (ListView) findViewById(R.id.list_result);
        etSizeMin = (EditText) findViewById(R.id.et_size_min);
        etSizeMax = (EditText) findViewById(R.id.et_size_max);
        etDaysMin = (EditText) findViewById(R.id.et_days_min);
        etDaysMax = (EditText) findViewById(R.id.et_days_max);
        etContent = (EditText) findViewById(R.id.et_content);
        cbIgnore = (android.widget.CheckBox) findViewById(R.id.cb_ignore);
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

        // v1.81.0：长按结果 → 直接打开文件（内容搜索时常用：看一眼命中的文本）
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                File f = results.get(i);
                if (!f.isDirectory() && !OpenWith.open(SearchActivity.this, f))
                    toast("没有可打开该文件的应用");
                return true;
            }
        });

        btnSearch = (Button) findViewById(R.id.btn_search);
        btnSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (searching) {          // 搜索中再点 = 停止
                    cancel = true;
                    btnSearch.setText("停止中…");
                    return;
                }
                doSearch();
            }
        });
        // v1.76.0：筛选 / 搜索 从系统方角按钮改胶囊
        UiDecor.barButton(btnAdv, this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.barButton(btnSearch, this, UiDecor.KIND_PRIMARY, 12.5f);
        UiDecor.gap(btnSearch, this, 4f);
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
        // v1.81.0：内容关键词（超长截断，避免误粘一整段文本）
        String c = etContent.getText().toString().trim();
        if (c.length() > 200) c = c.substring(0, 200);
        f.content = c.isEmpty() ? null : c;
        f.ignoreCase = cbIgnore.isChecked();
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
        boolean any = f.namePat != null || f.content != null || f.type != 0
                || f.sizeMin >= 0 || f.sizeMax >= 0 || f.dayMin >= 0 || f.dayMax >= 0;
        if (!any) { toast("请输入名称关键词、文件内容，或在筛选中设置条件"); return null; }
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
        snippets.clear();
        cancel = false;
        scannedFiles = 0;
        adapter.notifyDataSetChanged();
        searching = true;
        btnSearch.setText("停止");
        status.setText(filterSummary(ft));
        new Thread(new Runnable() {
            public void run() {
                walk(startDir, ft, 0);
                ui.post(new Runnable() {
                    public void run() {
                        searching = false;
                        btnSearch.setText("搜索");
                        status.setText(filterSummary(ft) + (cancel ? " → 已停止" : " → 完成")
                                + "，共 " + results.size() + " 个结果"
                                + (results.size() >= MAX ? "（已达上限）" : "")
                                + (ft.content != null ? "，扫描 " + scannedFiles + " 个文件" : ""));
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    /** 内容搜索进度：每 256 个文件回主线程更新一次状态栏 */
    private void postProgress(final Filter ft) {
        ui.post(new Runnable() {
            public void run() {
                if (!searching) return;
                status.setText(filterSummary(ft) + " · 已扫描 " + scannedFiles
                        + " 个文件 · 命中 " + results.size());
            }
        });
    }

    private static String filterSummary(Filter f) {
        StringBuilder sb = new StringBuilder("筛选: ");
        if (f.namePat != null) sb.append(f.regexFind ? "正则 " : "名称 ");
        if (f.content != null) sb.append("内容 ");
        if (f.type != 0) sb.append(TYPES[f.type]).append(' ');
        if (f.sizeMin >= 0 || f.sizeMax >= 0) sb.append("大小 ");
        if (f.dayMin >= 0 || f.dayMax >= 0) sb.append("时间 ");
        if (sb.length() == 4) sb.append("无");
        return sb.toString();
    }

    private void walk(File dir, Filter ft, int depth) {
        if (dir == null || depth > MAX_DEPTH || results.size() >= MAX || cancel) return;
        if (ft.content != null && scannedFiles >= MAX_SCAN_FILES) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        long now = System.currentTimeMillis();
        for (File f : kids) {
            if (results.size() >= MAX || cancel) break;
            if (ft.content != null && scannedFiles >= MAX_SCAN_FILES) break;
            String n = f.getName();
            // 回收站里的东西是被「删除」的，不该出现在搜索结果里（旧代码只比较了目录名，永远不命中）
            if (f.isDirectory() && f.getAbsolutePath().equals(Trash.dir().getAbsolutePath())) continue;
            if (f.isDirectory()) {
                if (ft.content == null && accept(f, ft, now)) results.add(f);
                walk(f, ft, depth + 1);
                continue;
            }
            if (ft.content == null) {
                if (accept(f, ft, now)) results.add(f);
                continue;
            }
            // ---- v1.81.0：内容搜索 ----
            scannedFiles++;
            if ((scannedFiles & 0xFF) == 0) postProgress(ft);
            if (!acceptFile(f, ft, now)) continue;
            ContentGrep.Result gr = ContentGrep.grepFile(
                    f, ft.content, false, ft.ignoreCase, 2, CONTENT_MAX_BYTES);
            ContentGrep.Match m = gr.first();
            if (m == null) continue;
            snippets.put(f.getAbsolutePath(),
                    "L" + m.line + ": " + ContentGrep.snippet(m.text, ft.content, 90));
            results.add(f);
            if ((scannedFiles & 0xFF) == 0) postProgress(ft);
        }
    }

    /** 条件判定：目录仅受名称过滤（且无类型/大小/时间条件时列出）；文件全条件叠加 */
    private boolean accept(File f, Filter ft, long now) {
        if (!matchName(f, ft)) return false;
        if (f.isDirectory()) {
            return ft.type == 0 && ft.sizeMin < 0 && ft.sizeMax < 0
                    && ft.dayMin < 0 && ft.dayMax < 0;
        }
        return acceptFile(f, ft, now);
    }

    /** 文件名条件（内容搜索时同样先生效） */
    private boolean matchName(File f, Filter ft) {
        if (ft.namePat == null) return true;
        return ft.regexFind
                ? ft.namePat.matcher(f.getName()).find()
                : ft.namePat.matcher(f.getName()).matches();
    }

    /** 文件级条件：类型 / 大小 / 时间（不含名称） */
    private boolean acceptFile(File f, Filter ft, long now) {
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
            String sn = snippets.get(f.getAbsolutePath());
            if (sn != null) {
                // 内容命中：优先展示片段（前面的行号+文本最有用，尾部省略路径）
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.setText(sn + "  ·  " + f.getParent());
            } else {
                detail.setEllipsize(android.text.TextUtils.TruncateAt.START);
                String d = f.getParent()
                        + "  ·  " + (f.isDirectory() ? "文件夹" : FileOps.formatSize(f.length()))
                        + "  ·  " + FileOps.formatTime(f.lastModified());
                detail.setText(d);
            }
            return convert;
        }
    }
}
