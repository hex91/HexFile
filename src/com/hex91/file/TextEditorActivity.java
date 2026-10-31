package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文本编辑器（v1.4 → v1.11 语法高亮 → v1.12 行号 + 搜索/替换 → v1.25 查找增强/跳转行
 *           → v1.26 多语言高亮 + 行操作 + 主菜单 + 代码补全 + Markdown 预览
 *           → v1.28 集成 Markor 编辑器引擎（gsantner/markor, Apache 2.0））：
 *  - 编辑器核心为 Markor 移植：HighlightingEditor（视口高亮/后台计算/编辑 fixup）
 *    + LineNumbersView（独立行号 View，平级放在同一滚动容器，天然对齐）
 *    + SyntaxHighlighterBase / MarkdownSyntaxHighlighter / PlaintextSyntaxHighlighter
 *    + AutoTextFormatter（markdown 自动缩进/列表续写/自动编号）。
 *  - 多语言语法高亮：smali/xml/html/json/java/python/shell/properties/c/cpp/js/markdown/text，
 *    经 MtSyntaxHighlighter 桥接进 Markor 高亮框架；"语法"菜单可手动切换，后缀自动识别。
 *  - 行操作（编辑菜单 ✎）：复制行/剪切行/删除行/清空行/替换行/重复行/转大小写/增减缩进/自定义。
 *  - 主菜单（⋮）：文件(重新打开/另存为/分享)/搜索/语法/上·下位置/跳转/只读/
 *    高亮开关/代码补全/设置(字号)/预览 Markdown/关闭。
 *  - v1.63.1：smali 文件顶栏出现「</>」图标，一键转 Java（先落盘再反编译）；保存改为图标。
 *  - 代码补全：开启后输入标识符自动按当前语言关键字弹出候选（PopupWindow）。
 *  - 编码显示：标题显示实际编码，支持 UTF-8-BOM / UTF-16(BOM) 探测与回写。
 *  - 底部快捷符号栏：点按插入光标处。
 */
public class TextEditorActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_ARCHIVE = "archive";
    public static final String EXTRA_ENTRY = "entry";
    public static final String EXTRA_EXPORT = "export";
    public static final String EXTRA_READONLY = "readonly";
    public static final String EXTRA_RT_AXML = "rt_axml";
    public static final String EXTRA_RT_APK = "rt_apk";
    /** v1.63.1：smali 编辑时的类名（com/foo/Bar）与所属 dex，用于「转 Java」反编译 */
    public static final String EXTRA_J2S_CLS = "j2s_cls";
    public static final String EXTRA_J2S_DEX = "j2s_dex";
    /** v1.64.0：ARSC++ 编辑 —— 保存时把 values XML 应用回 resources.arsc */
    public static final String EXTRA_ARSCPP_TYPE = "arscpp_type";
    public static final String EXTRA_ARSCPP_CFG = "arscpp_cfg";
    public static final String EXTRA_ARSCPP_ENTRY = "arscpp_entry";
    private static final long SOFT_LIMIT = 5L * 1024 * 1024;
    private static final int SEARCH_BG = 0x66FFF59D;

    private File file;
    private File archiveFile;
    private String entryName;
    private File exportDir;
    private File rtAxmlFile;
    private File rtApkFile;
    private String j2sCls;   // smali 类名（转 Java 用），null = 非 smali 场景
    private String j2sDex;   // 所属工程 dex（CFR 类型库），可为 null
    private String arscppType, arscppCfg, arscppEntry; // ARSC++ 编辑（非空 = 保存走 XML→arsc）
    private HighlightingEditor editor;
    private ScrollView editorScroll;
    private LineNumbersView lineNumbers;
    private TextView titleView;
    private String encoding = "UTF-8";
    private String titleSuffix = "";
    private volatile boolean modified = false;
    private boolean loaded = false;
    private boolean suppressWatch = false;
    private boolean binaryConfirmed = false;
    private boolean utf16Bom = false;
    private boolean utf8Bom = false;

    // ---------- 编辑器状态 ----------
    private String currentLang = "text";
    private boolean userReadOnly = false;
    private boolean highlightOn = true;   // Markor 高亮引擎开关（原"流畅模式"）
    private boolean completionOn = false;
    private int fontSize = 13;
    private Handler ui = new Handler();

    // ---------- 位置历史（上/下位置）----------
    private final Deque<int[]> backStack = new ArrayDeque<int[]>();
    private final Deque<int[]> forwardStack = new ArrayDeque<int[]>();

    // ---------- 搜索 / 替换 ----------
    private LinearLayout searchPanel;
    private EditText searchText, replaceText;
    private TextView searchCount;
    private CheckBox cbCase, cbWord, cbRegex;
    private boolean optCase = false, optWord = false, optRegex = false;
    private boolean regexError = false;
    private List<int[]> matches = new ArrayList<int[]>();
    private int curMatch = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_editor);

        file = new File(getIntent().getStringExtra(EXTRA_FILE));
        String archPath = getIntent().getStringExtra(EXTRA_ARCHIVE);
        if (archPath != null) archiveFile = new File(archPath);
        entryName = getIntent().getStringExtra(EXTRA_ENTRY);
        String exportPath = getIntent().getStringExtra(EXTRA_EXPORT);
        if (exportPath != null) exportDir = new File(exportPath);
        String rtAxml = getIntent().getStringExtra(EXTRA_RT_AXML);
        String rtApk = getIntent().getStringExtra(EXTRA_RT_APK);
        if (rtAxml != null && rtApk != null) {
            rtAxmlFile = new File(rtAxml);
            rtApkFile = new File(rtApk);
        }
        j2sDex = getIntent().getStringExtra(EXTRA_J2S_DEX);
        arscppType = getIntent().getStringExtra(EXTRA_ARSCPP_TYPE);
        arscppCfg = getIntent().getStringExtra(EXTRA_ARSCPP_CFG);
        arscppEntry = getIntent().getStringExtra(EXTRA_ARSCPP_ENTRY);
        boolean readOnly = getIntent().getBooleanExtra(EXTRA_READONLY, false);
        titleView = (TextView) findViewById(R.id.editor_title);
        editor = (HighlightingEditor) findViewById(R.id.editor_text);
        editorScroll = (ScrollView) findViewById(R.id.editor_scroll);
        lineNumbers = (LineNumbersView) findViewById(R.id.editor_lines);
        currentLang = SyntaxHighlight.detectLang(file.getName());
        // 静态光标颜色跟随主题强调色（暗色模式下原紫色在深色底上偏暗）
        editor.setCursorColor(ThemeManager.accent(this));

        // ---- Markor 编辑器引擎接线（v1.28）----
        // 行号 View 与编辑器平级（同一滚动容器），由编辑器自身滚动监听驱动重绘
        lineNumbers.setEditText(editor);
        lineNumbers.setLineNumbersEnabled(true);
        // 外层滚动容器滚动位置保存/恢复（高亮 reflow 时防止跳动）
        editor.setScrollCallbacks(new HighlightingEditor.ScrollGetter() {
            public int[] get() {
                return new int[]{editorScroll.getScrollX(), editorScroll.getScrollY()};
            }
        }, new HighlightingEditor.ScrollApplier() {
            public void apply(int x, int y) {
                editorScroll.scrollTo(x, y);
            }
        });
        // markdown 自动缩进 / 列表续写 / 自动编号
        editor.setAutoFormatters(
                new AutoTextFormatter(MdFormatPatterns.MARKDOWN),
                AutoTextFormatter.makeRenumberWatcher(MdFormatPatterns.MARKDOWN));

        if (rtAxmlFile != null) {
            titleSuffix = "\n↳ 编译写回 " + rtApkFile.getName();
        } else if (arscppType != null) {
            titleSuffix = "\n↳ ARSC++ 写回 " + archiveFile.getName() + "!" + arscppEntry
                    + "  (" + ArscPlus.cfgLabel(arscppType, arscppCfg) + ")";
        } else if (readOnly) {
            editor.setKeyListener(null);
            editor.setTextIsSelectable(true);
            userReadOnly = true;
            findViewById(R.id.editor_save).setVisibility(View.GONE);
            titleSuffix = "\n↳ 只读预览（二进制 XML）";
            titleView.setText(file.getName() + titleSuffix);
            titleSuffix = "";
        } else if (archiveFile != null)
            titleSuffix = "\n↳ 写回 " + archiveFile.getName() + "!" + entryName;
        else if (exportDir != null)
            titleSuffix = "\n↳ 导出 " + exportDir;

        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                if (!suppressWatch) modified = true;
                if (searchPanel != null && searchPanel.getVisibility() == View.VISIBLE) {
                    clearSearchHighlight();
                    matches.clear();
                    searchCount.setText("0/0");
                }
                if (completionOn && loaded && !userReadOnly) scheduleCompletion();
            }
        });

        // 顶部工具栏
        findViewById(R.id.editor_more).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMainMenu(); }
        });
        findViewById(R.id.editor_editmenu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showEditMenu(); }
        });
        findViewById(R.id.editor_undo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editor.onTextContextMenuItem(android.R.id.undo); }
        });
        findViewById(R.id.editor_redo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editor.onTextContextMenuItem(android.R.id.redo); }
        });
        findViewById(R.id.editor_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        // v1.63.1：「转 Java」图标 —— 仅 smali 文件显示，一键反编译为 Java 源码
        View j2s = findViewById(R.id.editor_java);
        j2sCls = deriveJ2sCls();
        if (j2sCls != null) {
            j2s.setVisibility(View.VISIBLE);
            j2s.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { toJava(); }
            });
        }
        findViewById(R.id.editor_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finishWithCheck(); }
        });
        findViewById(R.id.editor_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSearch(); }
        });
        findViewById(R.id.editor_goto).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoLine(); }
        });

        // 底部快捷符号栏
        LinearLayout symBar = (LinearLayout) findViewById(R.id.editor_symbols);
        for (int i = 0; i < symBar.getChildCount(); i++) {
            final Button btn = (Button) symBar.getChildAt(i);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    insertAtCursor(btn.getText().toString());
                }
            });
        }

        // 搜索面板
        searchPanel = (LinearLayout) findViewById(R.id.editor_search_panel);
        searchText = (EditText) findViewById(R.id.editor_search_text);
        replaceText = (EditText) findViewById(R.id.editor_replace_text);
        searchCount = (TextView) findViewById(R.id.editor_search_count);
        searchText.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) { doSearch(); }
            public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.editor_search_prev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoMatch(curMatch - 1); }
        });
        findViewById(R.id.editor_search_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoMatch(curMatch + 1); }
        });
        findViewById(R.id.editor_search_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSearch(); }
        });
        findViewById(R.id.editor_replace_one).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { replaceOne(); }
        });
        findViewById(R.id.editor_replace_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { replaceAll(); }
        });

        cbCase = (CheckBox) findViewById(R.id.editor_opt_case);
        cbWord = (CheckBox) findViewById(R.id.editor_opt_word);
        cbRegex = (CheckBox) findViewById(R.id.editor_opt_regex);
        CompoundButton.OnCheckedChangeListener optLis =
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean c) {
                        optCase = cbCase.isChecked();
                        optWord = cbWord.isChecked();
                        optRegex = cbRegex.isChecked();
                        if (searchPanel.getVisibility() == View.VISIBLE) doSearch();
                    }
                };
        cbCase.setOnCheckedChangeListener(optLis);
        cbWord.setOnCheckedChangeListener(optLis);
        cbRegex.setOnCheckedChangeListener(optLis);

        loadAsync();
    }

    // ============================================================ 搜索 / 替换

    private void toggleSearch() {
        if (searchPanel.getVisibility() == View.GONE) {
            searchPanel.setVisibility(View.VISIBLE);
            searchText.requestFocus();
            doSearch();
        } else {
            searchPanel.setVisibility(View.GONE);
            clearSearchHighlight();
            matches.clear();
            searchCount.setText("0/0");
        }
    }

    private void doSearch() {
        clearSearchHighlight();
        matches.clear();
        curMatch = 0;
        regexError = false;
        String key = searchText.getText().toString();
        if (key.isEmpty()) { searchCount.setText("0/0"); return; }
        String text = editor.getText().toString();
        matches = findMatches(text, key);
        if (regexError) { searchCount.setText("正则错误"); return; }
        if (matches.isEmpty()) { searchCount.setText("0/0"); return; }
        applyHighlights();
        gotoMatch(0);
    }

    private List<int[]> findMatches(String text, String key) {
        List<int[]> res = new ArrayList<int[]>();
        if (key.isEmpty()) return res;
        if (optRegex) {
            int flags = optCase ? 0 : Pattern.CASE_INSENSITIVE;
            try {
                Pattern p = Pattern.compile(key, flags);
                Matcher m = p.matcher(text);
                while (m.find()) {
                    int s = m.start(), e = m.end();
                    if (optWord && !isWholeWord(text, s, e)) continue;
                    res.add(new int[]{s, e});
                }
            } catch (PatternSyntaxException ex) {
                regexError = true;
            }
            return res;
        }
        if (optCase) {
            int idx = 0;
            while ((idx = text.indexOf(key, idx)) >= 0) {
                int e = idx + key.length();
                if (optWord && !isWholeWord(text, idx, e)) { idx = e; continue; }
                res.add(new int[]{idx, e});
                idx = e;
            }
        } else {
            String lt = text.toLowerCase();
            String lk = key.toLowerCase();
            int idx = 0;
            while ((idx = lt.indexOf(lk, idx)) >= 0) {
                int e = idx + key.length();
                if (optWord && !isWholeWord(text, idx, e)) { idx = e; continue; }
                res.add(new int[]{idx, e});
                idx = e;
            }
        }
        return res;
    }

    private boolean isWholeWord(String text, int s, int e) {
        boolean before = (s == 0) || !Character.isLetterOrDigit(text.charAt(s - 1));
        boolean after = (e >= text.length()) || !Character.isLetterOrDigit(text.charAt(e));
        return before && after;
    }

    private void applyHighlights() {
        // 搜索高亮经 Markor 的 additional span 机制注入，与语法高亮统一管理、互不冲突
        List<SyntaxHighlighterBase.SpanGroup> groups = new ArrayList<SyntaxHighlighterBase.SpanGroup>();
        for (int[] m : matches) {
            groups.add(SyntaxHighlighterBase.createBackgroundHighlight(m[0], m[1], SEARCH_BG));
        }
        editor.setSearchMatches(groups);
        editor.applyDynamicHighlight();
    }

    private void clearSearchHighlight() {
        editor.clearSearchMatches();
    }

    private void gotoMatch(int idx) {
        if (matches.isEmpty()) return;
        int n = matches.size();
        idx = ((idx % n) + n) % n;
        curMatch = idx;
        int[] m = matches.get(idx);
        jumpTo(m[0], m[1]);
        searchCount.setText((idx + 1) + "/" + n);
    }

    private void replaceOne() {
        if (matches.isEmpty()) return;
        String key = searchText.getText().toString();
        String repl = replaceText.getText().toString();
        int oldCur = curMatch;
        Editable s = editor.getText();
        suppressWatch = true;
        if (optRegex) {
            try {
                int flags = optCase ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern p = Pattern.compile(key, flags);
                Matcher mt = p.matcher(s.toString());
                StringBuffer sb = new StringBuffer();
                int handled = 0;
                while (mt.find()) {
                    if (handled == oldCur) { mt.appendReplacement(sb, repl); handled++; break; }
                    handled++;
                }
                mt.appendTail(sb);
                s.replace(0, s.length(), sb.toString());
            } catch (PatternSyntaxException ex) {
                suppressWatch = false;
                searchCount.setText("正则错误");
                return;
            }
        } else {
            int[] m = matches.get(curMatch);
            s.replace(m[0], m[1], repl);
        }
        suppressWatch = false;
        modified = true;
        doSearch();
        if (!matches.isEmpty()) gotoMatch(Math.min(oldCur, matches.size() - 1));
    }

    private void replaceAll() {
        if (matches.isEmpty()) return;
        String key = searchText.getText().toString();
        String repl = replaceText.getText().toString();
        Editable s = editor.getText();
        suppressWatch = true;
        if (optRegex) {
            try {
                int flags = optCase ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern p = Pattern.compile(key, flags);
                String replaced = p.matcher(s.toString()).replaceAll(repl);
                s.replace(0, s.length(), replaced);
            } catch (PatternSyntaxException ex) {
                suppressWatch = false;
                searchCount.setText("正则错误");
                return;
            }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                int[] m = matches.get(i);
                s.replace(m[0], m[1], repl);
            }
        }
        suppressWatch = false;
        modified = true;
        clearSearchHighlight();
        matches.clear();
        searchCount.setText("0/0");
        toast("已替换全部");
    }

    // ============================================================ 跳转行

    private void gotoLine() {
        if (!loaded) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("行号");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("跳转到行")
                .setView(input)
                .setPositiveButton("跳转", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyGotoLine(input.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyGotoLine(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        int line;
        try { line = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { toast("请输入数字"); return; }
        if (line < 1) { toast("行号从 1 开始"); return; }
        String text = editor.getText().toString();
        // 与行号区共用同一套换行符语义（'\n' / '\r' / "\r\n"），
        // 否则混合换行文件里跳转落点与行号不一致
        int offset = MtTextUtils.offsetOfLogicalLine(text, line);
        jumpTo(offset, offset);
        final int off = offset;
        editor.post(new Runnable() {
            public void run() {
                Layout lay = editor.getLayout();
                if (lay != null) {
                    int ln = lay.getLineForOffset(off);
                    int y = lay.getLineTop(ln);
                    editorScroll.scrollTo(0, Math.max(0, y - 20));
                }
            }
        });
    }

    // ============================================================ 位置历史

    /** 记录当前位置并跳转到 [a,b]（用于搜索/跳转/行操作后的可回溯导航） */
    private void jumpTo(int a, int b) {
        if (loaded) {
            int cs = editor.getSelectionStart(), ce = editor.getSelectionEnd();
            if (cs >= 0 && (backStack.isEmpty() || backStack.peekLast()[0] != cs)) {
                backStack.addLast(new int[]{cs, ce});
                if (backStack.size() > 300) backStack.removeFirst();
            }
        }
        forwardStack.clear();
        editor.setSelection(a, b);
        ensureCursorVisible();
    }

    /** 把光标行滚进外层滚动容器视口 */
    private void ensureCursorVisible() {
        editor.post(new Runnable() {
            public void run() {
                Layout lay = editor.getLayout();
                int sel = editor.getSelectionStart();
                if (lay == null || sel < 0) return;
                int line = lay.getLineForOffset(sel);
                int top = lay.getLineTop(line);
                int lineH = lay.getLineBottom(line) - lay.getLineTop(line);
                int cur = editorScroll.getScrollY();
                int viewH = editorScroll.getHeight();
                if (top < cur) {
                    editorScroll.scrollTo(0, Math.max(0, top - 20));
                } else if (top + lineH > cur + viewH - 40) {
                    editorScroll.scrollTo(0, Math.max(0, top - viewH / 2));
                }
            }
        });
    }

    private void goBack() {
        if (backStack.isEmpty()) { toast("没有可回退的位置"); return; }
        int cs = editor.getSelectionStart(), ce = editor.getSelectionEnd();
        forwardStack.addLast(new int[]{cs, ce});
        int[] p = backStack.removeLast();
        editor.setSelection(p[0], p[1]);
        ensureCursorVisible();
    }

    private void goForward() {
        if (forwardStack.isEmpty()) { toast("没有可前进的位置"); return; }
        int cs = editor.getSelectionStart(), ce = editor.getSelectionEnd();
        backStack.addLast(new int[]{cs, ce});
        int[] p = forwardStack.removeLast();
        editor.setSelection(p[0], p[1]);
        ensureCursorVisible();
    }

    // ============================================================ 加载

    private void loadAsync() {
        new Thread(new Runnable() {
            public void run() {
                final long size = file.length();
                final String err = binaryConfirmed ? null : peekBinary();
                if ("binary".equals(err)) {
                    runOnUiThread(new Runnable() {
                        public void run() { askBinary(err); }
                    });
                    return;
                }
                final String encHint = err;
                final boolean tooBig = size > SOFT_LIMIT;
                final Loaded ld = readAll(encHint);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ld == null) {
                            toast("无法读取文件");
                            finish();
                            return;
                        }
                        if (tooBig) toast("文件较大(" + FileOps.formatSize(size) + ")，编辑可能卡顿");
                        encoding = ld.encoding;
                        titleView.setText(file.getName() + "  (" + encoding + ", "
                                + FileOps.formatSize(size) + ")" + titleSuffix);
                        fontSize = (int) (editor.getTextSize() / getResources().getDisplayMetrics().scaledDensity);
                        setHighlightedText(ld.text, false);
                        loaded = true;
                        recordPos();
                    }
                });
            }
        }, "text-load").start();
    }

    private void recordPos() {
        int cs = editor.getSelectionStart();
        if (cs >= 0) backStack.addLast(new int[]{cs, editor.getSelectionEnd()});
    }

    /**
     * 二进制粗检 + 编码探测：
     * @return null = 普通文本；"binary" = 疑似二进制；否则为探测到的编码名（含 BOM 情形）
     */
    private String peekBinary() {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            int n = (int) Math.min(8192L, raf.length());
            byte[] head = new byte[n];
            raf.readFully(head);
            if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF)
                return "UTF-8-BOM";
            if (n >= 2) {
                int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF;
                if (b0 == 0xFF && b1 == 0xFE) return "UTF-16LE";
                if (b0 == 0xFE && b1 == 0xFF) return "UTF-16BE";
            }
            int nul = 0, nulEven = 0;
            for (int i = 0; i < n; i++) {
                if (head[i] == 0) { nul++; if ((i & 1) == 0) nulEven++; }
            }
            if (nul == 0) return null;
            if (nul * 8 >= n && (nulEven == 0 || nulEven == nul))
                return nulEven == 0 ? "UTF-16LE" : "UTF-16BE";
            return "binary";
        } catch (Exception e) {
            return null;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) {}
        }
    }

    private void askBinary(String type) {
        new AlertDialog.Builder(this)
                .setTitle("疑似二进制文件")
                .setMessage("该文件含二进制数据，文本方式打开会乱码。\n仍要打开，还是改用 HEX 查看？")
                .setPositiveButton("用 HEX 查看", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { toHex(); }
                })
                .setNegativeButton("仍要打开", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        binaryConfirmed = true;
                        loadAsync();
                    }
                })
                .setNeutralButton("关闭", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setCancelable(false)
                .show();
    }

    private void toHex() {
        Intent i = new Intent(this, HexViewerActivity.class);
        i.putExtra(HexViewerActivity.EXTRA_FILE, file.getAbsolutePath());
        startActivity(i);
        finish();
    }

    private static class Loaded {
        CharSequence text;
        String encoding;
    }

    private Loaded readAll(String encHint) {
        byte[] data = null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            data = new byte[(int) file.length()];
            int off = 0, r;
            while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
        } catch (Exception e) {
            // v1.44.0：root 区回退（su cat，File API 无权限时）
            data = RootIO.readBytes(file);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        if (data == null) return null;
        try {
            Loaded ld = new Loaded();
        int start = 0;
        if ("UTF-8-BOM".equals(encHint)) {
            ld.encoding = "UTF-8-BOM";
            start = 3; utf8Bom = true;
            int off2 = Math.min(start, data.length);
            ld.text = new String(data, off2, data.length - off2, StandardCharsets.UTF_8);
        } else if (encHint != null) {
                ld.encoding = encHint;
                if (data.length >= 2) {
                    int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
                    if ("UTF-16LE".equals(encHint) && b0 == 0xFF && b1 == 0xFE) { start = 2; utf16Bom = true; }
                    else if ("UTF-16BE".equals(encHint) && b0 == 0xFE && b1 == 0xFF) { start = 2; utf16Bom = true; }
                }
                ld.text = new String(data, start, data.length - start, encHint);
            } else {
                try {
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data));
                    ld.encoding = "UTF-8";
                } catch (CharacterCodingException e) {
                    ld.encoding = "GBK";
                }
                ld.text = new String(data, ld.encoding);
            }
            return ld;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================ 高亮

    /**
     * 设置当前语言的高亮器并填充文本；keepCursor 时尽量保留光标位置。
     * v1.28：改用 Markor 高亮框架——setHighlighter 后 setText，
     * 后续编辑的增量高亮 / fixup / 视口应用全部由 HighlightingEditor 内部完成。
     */
    private void setHighlightedText(CharSequence plain, boolean keepCursor) {
        int a = editor.getSelectionStart(), b = editor.getSelectionEnd();
        suppressWatch = true;
        editor.setHighlighter(MtSyntaxHighlighter.forLang(currentLang));
        editor.setHighlightingEnabled(highlightOn);
        editor.setText(plain);
        suppressWatch = false;
        if (keepCursor && a >= 0)
            editor.setSelection(Math.min(a, editor.length()), Math.min(b, editor.length()));
        else editor.setSelection(0);
    }

    // ============================================================ 符号栏 / 插入

    private void insertAtCursor(String sym) {
        if (userReadOnly) { toast("只读模式"); return; }
        if ("Tab".equals(sym)) sym = "\t";
        else if ("换行".equals(sym)) sym = "\n";
        else if (" ".equals(sym)) sym = " ";
        editor.insertOrReplaceTextOnCursor(sym);
        suppressWatch = false;
        modified = true;
        ensureCursorVisible();
    }

    // ============================================================ 编辑操作菜单（✎）

    private void showEditMenu() {
        final String[] items = {"复制行", "剪切行", "删除行", "清空行", "替换行",
                "重复行", "转为大写", "转为小写", "增加缩进", "减小缩进", "自定义", "格式化"};
        new AlertDialog.Builder(this)
                .setTitle("编辑操作")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0: copyLine(); break;
                            case 1: cutLine(); break;
                            case 2: deleteLine(); break;
                            case 3: clearLine(); break;
                            case 4: replaceLine(); break;
                            case 5: duplicateLine(); break;
                            case 6: transformCase(true); break;
                            case 7: transformCase(false); break;
                            case 8: indentLine(true); break;
                            case 9: indentLine(false); break;
                            case 10: showCustomMenu(); break;
                            case 11: showFormatMenu(); break;
                        }
                    }
                }).show();
    }

    /** 返回当前（或选区覆盖的）行范围 [start, end)，end 含行尾换行符之前 */
    private int[] lineRange() {
        Editable s = editor.getText();
        int len = s.length();
        int selS = editor.getSelectionStart();
        int selE = editor.getSelectionEnd();
        if (selS < 0) selS = selE = 0;
        int start = selS;
        // 行边界兼容 '\n' / '\r'（与行号、跳转同一套换行符语义）
        while (start > 0 && !MtTextUtils.isBreakChar(s.charAt(start - 1))) start--;
        int end = Math.max(selS, selE);
        while (end < len && !MtTextUtils.isBreakChar(s.charAt(end))) end++;
        return new int[]{start, end}; // [start, end) 不含换行符
    }

    private void copyLine() {
        int[] r = lineRange();
        String line = editor.getText().subSequence(r[0], r[1]).toString();
        setClip(line);
        toast("已复制行");
    }

    private void cutLine() {
        if (userReadOnly) { toast("只读模式"); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        int lineEnd = (r[1] < s.length()) ? r[1] + 1 : r[1]; // 连同换行一起切
        setClip(s.subSequence(r[0], r[1]).toString());
        suppressWatch = true;
        s.delete(r[0], lineEnd);
        suppressWatch = false;
        modified = true;
        editor.setSelection(r[0]);
    }

    private void deleteLine() {
        if (userReadOnly) { toast("只读模式"); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        int lineEnd = (r[1] < s.length()) ? r[1] + 1 : r[1];
        suppressWatch = true;
        s.delete(r[0], lineEnd);
        suppressWatch = false;
        modified = true;
        editor.setSelection(r[0]);
    }

    private void clearLine() {
        if (userReadOnly) { toast("只读模式"); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        suppressWatch = true;
        s.replace(r[0], r[1], "");
        suppressWatch = false;
        modified = true;
        editor.setSelection(r[0]);
    }

    private void replaceLine() {
        if (userReadOnly) { toast("只读模式"); return; }
        final int[] r = lineRange();
        final EditText input = new EditText(this);
        input.setHint("替换为该行内容");
        new AlertDialog.Builder(this)
                .setTitle("替换行")
                .setView(input)
                .setPositiveButton("替换", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Editable s = editor.getText();
                        suppressWatch = true;
                        s.replace(r[0], r[1], input.getText().toString());
                        suppressWatch = false;
                        modified = true;
                        editor.setSelection(r[0]);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void duplicateLine() {
        if (userReadOnly) { toast("只读模式"); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        String line = s.subSequence(r[0], r[1]).toString();
        String add = "\n" + line;
        suppressWatch = true;
        s.insert(r[1], add);
        suppressWatch = false;
        modified = true;
        editor.setSelection(r[1] + add.length());
    }

    private void transformCase(boolean upper) {
        if (userReadOnly) { toast("只读模式"); return; }
        Editable s = editor.getText();
        int selS = editor.getSelectionStart(), selE = editor.getSelectionEnd();
        int[] r = lineRange();
        int a = (selS != selE) ? Math.min(selS, selE) : r[0];
        int b = (selS != selE) ? Math.max(selS, selE) : r[1];
        if (a == b) { toast("请先选中或移动光标到行内"); return; }
        String t = s.subSequence(a, b).toString();
        t = upper ? t.toUpperCase() : t.toLowerCase();
        suppressWatch = true;
        s.replace(a, b, t);
        suppressWatch = false;
        modified = true;
        editor.setSelection(a, a + t.length());
    }

    private void indentLine(boolean add) {
        if (userReadOnly) { toast("只读模式"); return; }
        Editable s = editor.getText();
        String text = s.toString();
        int len = text.length();
        int selS = editor.getSelectionStart(), selE = editor.getSelectionEnd();
        if (selS < 0) selS = selE = 0;
        // 行边界兼容 '\n' / '\r'（与行号、跳转同一套换行符语义）
        int firstLineStart = selS;
        while (firstLineStart > 0 && !MtTextUtils.isBreakChar(text.charAt(firstLineStart - 1))) firstLineStart--;
        int lastLineEnd = Math.max(selS, selE);
        while (lastLineEnd < len && !MtTextUtils.isBreakChar(text.charAt(lastLineEnd))) lastLineEnd++;
        int firstIdx = MtTextUtils.countBreaks(text, 0, firstLineStart);
        int lastIdx = MtTextUtils.countBreaks(text, 0, lastLineEnd);
        String[] lines = MtTextUtils.splitLines(text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String ln = lines[i];
            if (i >= firstIdx && i <= lastIdx) {
                if (add) ln = "\t" + ln;
                else if (ln.startsWith("\t")) ln = ln.substring(1);
                else if (ln.startsWith(" ")) ln = ln.substring(1);
            }
            sb.append(ln);
        }
        suppressWatch = true;
        s.replace(0, s.length(), sb.toString());
        suppressWatch = false;
        modified = true;
        editor.setSelection(firstLineStart + (add ? 1 : 0));
    }

    private void showCustomMenu() {
        final String[] items = {"行首插入文本", "行尾插入文本", "删除空行", "按行排序", "删除重复行"};
        new AlertDialog.Builder(this)
                .setTitle("自定义")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0: surroundLine(true); break;
                            case 1: surroundLine(false); break;
                            case 2: removeEmptyLines(); break;
                            case 3: sortLines(); break;
                            case 4: dedupeLines(); break;
                        }
                    }
                }).show();
    }

    private void surroundLine(boolean head) {
        if (userReadOnly) { toast("只读模式"); return; }
        final EditText input = new EditText(this);
        input.setHint(head ? "行首插入" : "行尾插入");
        new AlertDialog.Builder(this)
                .setTitle(head ? "行首插入" : "行尾插入")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int wi) {
                        int[] r = lineRange();
                        Editable s = editor.getText();
                        String text = s.toString();
                        String ins = input.getText().toString();
                        if (ins.isEmpty()) return;
                        int shift = 0;   // 已插入内容造成的累计偏移
                        int pos = r[0];
                        while (pos <= r[1]) {
                            int lineEnd = pos;
                            while (lineEnd < r[1] && !MtTextUtils.isBreakChar(text.charAt(lineEnd))) lineEnd++;
                            if (head) s.insert(pos + shift, ins);
                            else s.insert(lineEnd + shift, ins);
                            shift += ins.length();
                            if (lineEnd >= r[1]) break;
                            // 跳到下一行起点：跳过换行符（CRLF 跳 2 个字符）
                            pos = lineEnd + 1;
                            if (lineEnd < text.length() && text.charAt(lineEnd) == '\r'
                                    && pos < text.length() && text.charAt(pos) == '\n') pos++;
                        }
                        modified = true;
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void removeEmptyLines() {
        if (userReadOnly) { toast("只读模式"); return; }
        Editable s = editor.getText();
        String[] lines = MtTextUtils.splitLines(s.toString());
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            if (!ln.trim().isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(ln); }
        }
        suppressWatch = true;
        s.replace(0, s.length(), sb.toString());
        suppressWatch = false;
        modified = true;
        toast("已删除空行");
    }

    private void sortLines() {
        if (userReadOnly) { toast("只读模式"); return; }
        String[] lines = MtTextUtils.splitLines(editor.getText().toString());
        java.util.Arrays.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        suppressWatch = true;
        editor.getText().replace(0, editor.length(), sb.toString());
        suppressWatch = false;
        modified = true;
        toast("已按行排序");
    }

    private void dedupeLines() {
        if (userReadOnly) { toast("只读模式"); return; }
        String[] lines = MtTextUtils.splitLines(editor.getText().toString());
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            if (set.add(ln)) { if (sb.length() > 0) sb.append("\n"); sb.append(ln); }
        }
        suppressWatch = true;
        editor.getText().replace(0, editor.length(), sb.toString());
        suppressWatch = false;
        modified = true;
        toast("已删除重复行");
    }

    private void setClip(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("hexfile", text));
    }

    // ============================================================ 主菜单（≡ / ⋮）

    private void showMainMenu() {
        final List<String> items = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        items.add("文件"); acts.add(0);
        items.add("搜索"); acts.add(1);
        items.add("语法 (" + langLabel(currentLang) + ")"); acts.add(2);
        items.add("上个位置"); acts.add(3);
        items.add("下个位置"); acts.add(4);
        items.add("转到指定行"); acts.add(5);
        items.add((userReadOnly ? "☑ " : "☐ ") + "只读模式"); acts.add(6);
        items.add((highlightOn ? "☑ " : "☐ ") + "语法高亮"); acts.add(7);
        items.add((completionOn ? "☑ " : "☐ ") + "代码补全"); acts.add(8);
        items.add("设置"); acts.add(9);
        items.add("格式化"); acts.add(12);
        if ("markdown".equals(currentLang))
            { items.add("预览 Markdown"); acts.add(10); }
        items.add("关闭文件"); acts.add(11);

        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        switch (acts.get(w)) {
                            case 0: showFileMenu(); break;
                            case 1: toggleSearch(); break;
                            case 2: showSyntaxMenu(); break;
                            case 3: goBack(); break;
                            case 4: goForward(); break;
                            case 5: gotoLine(); break;
                            case 6: toggleReadOnly(); break;
                            case 7:
                                highlightOn = !highlightOn;
                                editor.setHighlightingEnabled(highlightOn);
                                toast(highlightOn ? "语法高亮开" : "语法高亮关");
                                break;
                            case 8: completionOn = !completionOn; toast(completionOn ? "代码补全开" : "代码补全关"); break;
                            case 9: showSettings(); break;
                            case 10: previewMarkdown(); break;
                            case 11: finishWithCheck(); break;
                            case 12: showFormatMenu(); break;
                        }
                    }
                }).show();
    }

    private String langLabel(String id) {
        for (SyntaxHighlight.LangInfo li : SyntaxHighlight.listLanguages())
            if (li.id.equals(id)) return li.label;
        return id;
    }

    private void showFileMenu() {
        final String[] items = {"重新打开", "另存为副本", "分享"};
        new AlertDialog.Builder(this)
                .setTitle("文件")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) { modified = false; loadAsync(); }
                        else if (w == 1) saveAsCopy();
                        else shareFile();
                    }
                }).show();
    }

    private void showSyntaxMenu() {
        List<SyntaxHighlight.LangInfo> langs = SyntaxHighlight.listLanguages();
        String[] names = new String[langs.size()];
        for (int i = 0; i < langs.size(); i++) names[i] = langs.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("语法高亮")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        currentLang = langs.get(w).id;
                        String text = editor.getText().toString();
                        setHighlightedText(text, true);
                        toast("语法：" + langs.get(w).label);
                    }
                }).show();
    }

    private android.text.method.KeyListener savedKeyListener;

    private void toggleReadOnly() {
        if (userReadOnly) {
            userReadOnly = false;
            editor.setKeyListener(savedKeyListener);
            editor.setTextIsSelectable(false);
            toast("已退出只读");
        } else {
            userReadOnly = true;
            savedKeyListener = editor.getKeyListener();
            editor.setKeyListener(null);
            editor.setTextIsSelectable(true);
            toast("只读模式");
        }
    }

    private void showSettings() {
        // v1.63.4：放大/缩小 → 直接输入 sp
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.valueOf(fontSize));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("字体大小（sp，8-40）")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            float v = Float.parseFloat(input.getText().toString().trim());
                            v = Math.max(8f, Math.min(40f, v));
                            fontSize = (int) v;
                            editor.setTextSize(v);
                            toast("字体 " + fontSize + "sp");
                        } catch (Exception e) {
                            toast("格式错误");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================ 格式化（v1.29.0）

    private void showFormatMenu() {
        final String[] items = {"JSON 美化", "JSON 压缩", "XML/HTML 美化", "代码美化（{} 语言）"};
        final int[] modes = {FormatUtil.FMT_JSON, FormatUtil.FMT_JSON_MIN,
                FormatUtil.FMT_XML, FormatUtil.FMT_CODE};
        final int detected = FormatUtil.detectFormat(
                file != null ? file.getName() : "", editor.getText().toString());
        String title = "格式化";
        if (detected == FormatUtil.FMT_JSON) title += "（检测到 JSON）";
        else if (detected == FormatUtil.FMT_XML) title += "（检测到 XML/HTML）";
        else if (detected == FormatUtil.FMT_CODE) title += "（检测到代码）";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doFormat(modes[w]);
                    }
                }).show();
    }

    private void doFormat(int mode) {
        if (userReadOnly) { toast("只读模式"); return; }
        String src = editor.getText().toString();
        if (src.trim().isEmpty()) { toast("内容为空"); return; }
        if (src.length() > 2 * 1024 * 1024) { toast("文件过大（>2MB），不建议格式化"); return; }
        try {
            String out = FormatUtil.format(src, mode);
            if (out.equals(src)) { toast("格式已规范，无变化"); return; }
            int caret = editor.getSelectionStart();
            suppressWatch = true;
            editor.setText(out);
            suppressWatch = false;
            if (caret >= 0 && caret <= editor.length()) editor.setSelection(caret);
            modified = true;
            toast("已格式化（可点工具栏撤销恢复）");
        } catch (FormatUtil.FormatException e) {
            toast("格式化失败：" + e.getMessage());
        }
    }

    private void previewMarkdown() {
        String html = MarkdownRender.render(editor.getText().toString());
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        b.setTitle("Markdown 预览").setView(wv)
                .setPositiveButton("关闭", null).show();
    }

    // ============================================================ 代码补全

    private PopupWindow completionPop;
    private Runnable completionTask;

    private void scheduleCompletion() {
        if (completionTask != null) ui.removeCallbacks(completionTask);
        completionTask = new Runnable() {
            public void run() { showCompletion(); }
        };
        ui.postDelayed(completionTask, 350);
    }

    private void showCompletion() {
        if (!completionOn || userReadOnly) return;
        Editable s = editor.getText();
        int sel = editor.getSelectionStart();
        if (sel < 0) return;
        // 当前词前缀
        int start = sel;
        while (start > 0 && isWordChar(s.charAt(start - 1))) start--;
        if (start == sel) return; // 没在词中
        String word = s.subSequence(start, sel).toString();
        if (word.length() < 1) return;
        Set<String> cand = SyntaxHighlight.completions(currentLang);
        List<String> matched = new ArrayList<String>();
        for (String c : cand) {
            if (c.length() > word.length() && c.toLowerCase().startsWith(word.toLowerCase()))
                matched.add(c);
        }
        if (matched.isEmpty()) { dismissCompletion(); return; }

        ListView lv = new ListView(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, matched);
        lv.setAdapter(ad);
        final int fStart = start, fWordLen = word.length();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                String pick = matched.get(pos);
                String rest = pick.substring(fWordLen);
                Editable e = editor.getText();
                int cur = editor.getSelectionStart();
                suppressWatch = true;
                e.insert(cur, rest);
                suppressWatch = false;
                modified = true;
                dismissCompletion();
            }
        });
        dismissCompletion();
        completionPop = new PopupWindow(lv, 220, Math.min(300, matched.size() * 48 + 10), true);
        completionPop.setOutsideTouchable(true);
        completionPop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
        Layout lay = editor.getLayout();
        if (lay != null) {
            // 编辑器已改为外层滚动容器驱动（自身 scrollX/Y 恒 0），
            // 弹窗定位改用编辑器在屏幕中的绝对坐标
            int[] loc = new int[2];
            editor.getLocationOnScreen(loc);
            int line = lay.getLineForOffset(fStart);
            int x = loc[0] + (int) lay.getPrimaryHorizontal(fStart) + editor.getPaddingLeft();
            int y = loc[1] + lay.getLineBottom(line) + 4;
            completionPop.showAtLocation(editor, Gravity.NO_GRAVITY, x, y);
        }
    }

    private void dismissCompletion() {
        if (completionPop != null && completionPop.isShowing()) completionPop.dismiss();
        completionPop = null;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    // ============================================================ 转 Java（smali → 反编译）

    /**
     * smali 文件 → 类名（com/foo/Bar）：
     * 优先用 Dex 编辑器++ 传入的 extra，否则从 smali_* 工程目录自行推导；
     * 非 smali 文件返回 null（隐藏「转 Java」图标）。
     */
    private String deriveJ2sCls() {
        if (file == null || !file.getName().endsWith(".smali")) return null;
        String via = getIntent().getStringExtra(EXTRA_J2S_CLS);
        if (via != null && !via.isEmpty()) return via;
        File cur = file.getParentFile();
        while (cur != null) {
            if (cur.getName().startsWith("smali_")) {
                String rel = file.getAbsolutePath().substring(cur.getAbsolutePath().length() + 1);
                return rel.endsWith(".smali") ? rel.substring(0, rel.length() - 6) : rel;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    /** 一键转 Java：先把未保存的改动落盘（反编译读磁盘文件），再进 DecompileActivity。 */
    private void toJava() {
        if (file == null || j2sCls == null) return;
        if (modified) {
            try {
                byte[] data = encodeForSave(editor.getText().toString());
                boolean ok;
                try {
                    FileOutputStream fo = new FileOutputStream(file);
                    fo.write(data);
                    fo.close();
                    ok = true;
                } catch (Exception ro) {
                    ok = RootIO.writeBytes(file, data);   // root 区回退
                }
                if (!ok) { toast("保存失败，无法反编译最新内容"); return; }
                modified = false;
            } catch (Exception e) {
                toast("保存失败: " + e.getMessage());
                return;
            }
        }
        Intent i = new Intent(this, DecompileActivity.class);
        i.putExtra(DecompileActivity.EXTRA_SMALI, file.getAbsolutePath());
        i.putExtra(DecompileActivity.EXTRA_CLS, j2sCls);
        if (j2sDex != null) i.putExtra(DecompileActivity.EXTRA_DEX, j2sDex);
        startActivity(i);
    }

    // ============================================================ 保存 / 退出

    private byte[] encodeForSave(String text) throws Exception {
        byte[] body = text.getBytes(encoding.equals("UTF-8-BOM") ? "UTF-8" : encoding);
        if (utf8Bom) {
            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] data = new byte[body.length + 3];
            System.arraycopy(bom, 0, data, 0, 3);
            System.arraycopy(body, 0, data, 3, body.length);
            return data;
        }
        if (!utf16Bom) return body;
        byte[] bom = "UTF-16LE".equals(encoding)
                ? new byte[]{(byte) 0xFF, (byte) 0xFE}
                : new byte[]{(byte) 0xFE, (byte) 0xFF};
        byte[] data = new byte[body.length + 2];
        System.arraycopy(bom, 0, data, 0, 2);
        System.arraycopy(body, 0, data, 2, body.length);
        return data;
    }

    private void saveAsCopy() {
        final String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        final File out = new File(file.getParentFile(), base + "_copy" + ext);
        try {
            FileOutputStream fo = new FileOutputStream(out);
            fo.write(encodeForSave(editor.getText().toString()));
            fo.close();
            toast("已另存为 " + out.getName());
        } catch (Exception e) {
            toast("另存为失败: " + e.getMessage());
        }
    }

    private void shareFile() {
        try {
            File tmp = new File(getCacheDir(), file.getName());
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(encodeForSave(editor.getText().toString()));
            fo.close();
            Uri uri = Uri.parse("content://com.hex91.file.files" + tmp.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享"));
        } catch (Exception e) {
            toast("分享失败: " + e.getMessage());
        }
    }

    private void saveRoundtrip() {
        final String text = editor.getText().toString();
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("编译 XML 并写回…");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                boolean userError = false;
                File outApk = null;
                try {
                    byte[] orig = readAllBytes(rtAxmlFile);
                    AxmlFile axml = AxmlFile.parse(orig);
                    byte[] newAxml = AxmlTextSync.sync(axml, text);
                    AxmlFile check = AxmlFile.parse(newAxml);
                    if (check.elements.size() != axml.elements.size())
                        throw new IllegalStateException("回读校验失败：元素数不一致");
                    String base = rtApkFile.getName().toLowerCase().endsWith(".apk")
                            ? rtApkFile.getName().substring(0, rtApkFile.getName().length() - 4)
                            : rtApkFile.getName();
                    File unsigned = new File(getCacheDir(), "rt_unsigned.apk");
                    Map<String, byte[]> replace = new java.util.HashMap<String, byte[]>();
                    replace.put("AndroidManifest.xml", newAxml);
                    ApkRepack.repack(rtApkFile, replace, unsigned);
                    outApk = new File(rtApkFile.getParentFile(), base + "_xmlpp.apk");
                    if (outApk.exists()) outApk.delete();
                    java.io.InputStream ks = getAssets().open("hexfile.p12");
                    ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, unsigned, outApk, 21);
                    ks.close();
                } catch (IllegalArgumentException e) {
                    err = e.getMessage();
                    userError = true;
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                final boolean fuerr = userError;
                final File fout = outApk;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (ferr != null) {
                            new AlertDialog.Builder(TextEditorActivity.this)
                                    .setTitle(fuerr ? "不支持的修改" : "编译失败")
                                    .setMessage(ferr)
                                    .setPositiveButton("知道了", null).show();
                            return;
                        }
                        modified = false;
                        new AlertDialog.Builder(TextEditorActivity.this)
                                .setTitle("已编译并签名")
                                .setMessage("XML 已编译写回并签名:\n" + fout.getPath()
                                        + "\n\n「写回原 APK」= 用此修改版覆盖原 APK")
                                .setPositiveButton("写回原 APK",
                                        new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        writeBackSigned(fout, rtApkFile);
                                    }
                                })
                                .setNeutralButton("直接安装",
                                        new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        installSigned(fout);
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "rt-save").start();
    }

    private byte[] readAllBytes(File f) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private void writeBackSigned(final File signed, final File orig) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("写回原 APK …");
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
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
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        toast(ferr != null ? "写回失败: " + ferr
                                : "已写回原 APK（重新打开即可看到修改）");
                    }
                });
            }
        }, "rt-writeback").start();
    }

    private void installSigned(File apkFile) {
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

    private void save() {
        if (file == null) return;
        if (rtAxmlFile != null) { saveRoundtrip(); return; }
        if (arscppType != null) { saveArscPlus(); return; }   // v1.64.0：ARSC++ 编辑
        final String text = editor.getText().toString();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    byte[] data = encodeForSave(text);
                    boolean saved;
                    try {
                        FileOutputStream out = new FileOutputStream(file);
                        out.write(data);
                        out.close();
                        saved = true;
                    } catch (Exception ro) {
                        // v1.44.0：root 区回退（su cat > file）
                        saved = RootIO.writeBytes(file, data);
                        if (!saved) throw ro;
                    }
                    if (archiveFile != null && entryName != null) {
                        if (!ArchiveWrite.writeEntry(archiveFile, entryName, data))
                            err = "写回压缩包失败";
                    } else if (exportDir != null) {
                        exportDir.mkdirs();
                        FileOutputStream ex = new FileOutputStream(
                                new File(exportDir, file.getName()));
                        ex.write(data);
                        ex.close();
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final boolean done = err == null;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (done) {
                            modified = false;
                            if (archiveFile != null) {
                                toast("已写回压缩包 (" + encoding + ")");
                                if (archiveFile.getName().toLowerCase().endsWith(".apk"))
                                    askAutoSign();
                            } else if (exportDir != null) {
                                toast("已保存并导出到 " + exportDir.getPath());
                            } else {
                                toast("已保存 (" + encoding + ")");
                            }
                        } else {
                            toast("保存失败: " + ferr);
                        }
                    }
                });
            }
        }, "text-save").start();
    }

    /**
     * v1.64.0：ARSC++ 编辑保存 —— 编辑的是 values 风格 XML，
     * 保存时解析 XML → 生成对 resources.arsc 的修改（ArscPlus.applyXml → ArscWriter.write）
     * → 重打包（.so 4096 对齐）覆盖原 APK → 提示重签名。
     */
    private void saveArscPlus() {
        final String text = editor.getText().toString();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int nEdits = 0;
                try {
                    // 1) XML 文本照常落盘（编辑器缓存，下次打开还是最新内容）
                    byte[] data = encodeForSave(text);
                    try {
                        FileOutputStream out = new FileOutputStream(file);
                        out.write(data);
                        out.close();
                    } catch (Exception ro) {
                        if (!RootIO.writeBytes(file, data)) throw ro;
                    }
                    // 2) 重新解析 APK 内的 arsc（避免用过期模型）
                    byte[] arscBytes = readZipEntry(archiveFile, arscppEntry);
                    ArscFile af = ArscFile.parse(arscBytes);
                    // 3) XML → 编辑列表（值级 + 结构化 v1.64.2: rename/delete/add）
                    java.util.List<ArscEditor.Op> ops =
                            new java.util.ArrayList<ArscEditor.Op>();
                    java.util.List<ArscWriter.Edit> edits =
                            ArscPlus.applyXml(af, arscppType, arscppCfg, text, ops);
                    nEdits = edits.size() + ops.size();
                    if (nEdits > 0) {
                        // ArscEditor：无结构化编辑走兼容补丁路径；有 delete/add 走全量重编码
                        byte[] newArsc = ArscEditor.write(af, edits, ops);
                        // 4) 重打包覆盖原 APK（保持 .so 4096 对齐），随后提示重签名
                        java.util.Map<String, byte[]> replace =
                                new java.util.HashMap<String, byte[]>();
                        replace.put(arscppEntry, newArsc);
                        File tmp = new File(getCacheDir(), "arscpp_save.apk");
                        if (tmp.exists()) tmp.delete();
                        ApkRepack.repack(archiveFile, replace, tmp);
                        copyFileTo(tmp, archiveFile);
                        tmp.delete();
                    }
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName();
                }
                final String ferr = err;
                final int fn = nEdits;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            new AlertDialog.Builder(TextEditorActivity.this)
                                    .setTitle("写回失败")
                                    .setMessage(ferr)
                                    .setPositiveButton("知道了", null).show();
                        } else if (fn == 0) {
                            modified = false;
                            toast("没有需要写回的修改");
                        } else {
                            modified = false;
                            toast("已写回 " + arscppEntry + "（" + fn + " 处修改）");
                            if (archiveFile.getName().toLowerCase().endsWith(".apk"))
                                askAutoSign();
                        }
                    }
                });
            }
        }, "arscpp-save").start();
    }

    private static byte[] readZipEntry(File zip, String entryName) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        try {
            java.util.zip.ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) throw new Exception("压缩包内找不到 " + entryName);
            java.io.InputStream in = zf.getInputStream(ze);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            zf.close();
        }
    }

    private static void copyFileTo(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private void askAutoSign() {
        new AlertDialog.Builder(this)
                .setTitle("APK 已修改")
                .setMessage(archiveFile.getName()
                        + " 内容已变更，签名已失效。\n是否立即用内置密钥重新签名？")
                .setPositiveButton("自动签名", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        toast("签名中…");
                        new Thread(new Runnable() {
                            public void run() {
                                String err = null;
                                File out = new File(archiveFile.getParentFile(),
                                        stripExt(archiveFile.getName()) + "_signed.apk");
                                try {
                                    java.io.InputStream ks = getAssets().open("hexfile.p12");
                                    ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD,
                                            archiveFile, out, 21);
                                    ks.close();
                                } catch (Throwable t) {
                                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                                }
                                final String ferr = err;
                                final File fout = out;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(ferr != null ? "签名失败: " + ferr
                                                : "已签名 → " + fout.getName());
                                    }
                                });
                            }
                        }, "editor-sign").start();
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void finishWithCheck() {
        if (modified) {
            onBackPressed();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (!loaded || !modified) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
                .setTitle("未保存的修改")
                .setMessage("内容已修改，是否保存？")
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        save();
                        finish();
                    }
                })
                .setNegativeButton("不保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
