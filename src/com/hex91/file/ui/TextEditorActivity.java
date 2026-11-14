package com.hex91.file.ui;

import com.hex91.file.utils.AutoTextFormatter;
import com.hex91.file.utils.MarkdownRender;
import com.hex91.file.utils.MdFormatPatterns;
import com.hex91.file.utils.MtSyntaxHighlighter;
import com.hex91.file.utils.MtTextUtils;
import com.hex91.file.utils.SyntaxHighlight;
import com.hex91.file.utils.SyntaxHighlighterBase;
import com.hex91.file.R;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ArscEditor;
import com.hex91.file.apk.ArscFile;
import com.hex91.file.apk.ArscPlus;
import com.hex91.file.apk.ArscWriter;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.RootIO;
import com.hex91.file.core.FormatUtil;
import com.hex91.file.apk.ArchiveWrite;
import com.hex91.file.apk.AxmlFile;
import com.hex91.file.apk.AxmlTextSync;
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
    /** v1.66.9：Dex 编辑器打开的工程 smali——返回时有改动直接静默保存，不再弹「是否保存」
     *  （编译/写回集中在退出 Dex 编辑器时一次完成） */
    public static final String EXTRA_SILENT_SAVE = "silent_save";
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
    private boolean silentSave; // v1.66.9：返回时静默保存（Dex 编辑器工程 smali）
    private String arscppType, arscppCfg, arscppEntry; // ARSC++ 编辑（非空 = 保存走 XML→arsc）
    private HighlightingEditor editor;
    private ScrollView editorScroll;
    private LineNumbersView lineNumbers;
    private TextView titleView;
    private String encoding = "UTF-8";
    private String titleSuffix = "";
    private volatile boolean modified;   // dirty 状态，统一经 setModified() 维护（联动保存图标可点态）
    private View saveBtn;                // 保存图标
    private boolean pendingFinishAfterSave = false;   // v1.64.3：返回时选「保存」→ 保存完成后自动退出
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
        silentSave = getIntent().getBooleanExtra(EXTRA_SILENT_SAVE, false);
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
            titleSuffix = getString(R.string.te_suffix_compile, rtApkFile.getName());
        } else if (arscppType != null) {
            titleSuffix = getString(R.string.te_suffix_arsc, archiveFile.getName(),
                    arscppEntry + "  (" + ArscPlus.cfgLabel(arscppType, arscppCfg) + ")");
        } else if (readOnly) {
            editor.setKeyListener(null);
            editor.setTextIsSelectable(true);
            userReadOnly = true;
            findViewById(R.id.editor_save).setVisibility(View.GONE);
            titleSuffix = getString(R.string.te_suffix_readonly_bin);
            titleView.setText(file.getName() + titleSuffix);
            titleSuffix = "";
        } else if (archiveFile != null)
            titleSuffix = getString(R.string.te_suffix_writeback, archiveFile.getName(), entryName);
        else if (exportDir != null)
            titleSuffix = getString(R.string.te_suffix_export, exportDir);

        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                if (!suppressWatch) setModified(true);
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
        saveBtn = findViewById(R.id.editor_save);
        saveBtn.setOnClickListener(new View.OnClickListener() {
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

        // v1.76.0：底部操作区统一胶囊化
        styleBottomBar(symBar);

        // 搜索面板
        searchPanel = (LinearLayout) findViewById(R.id.editor_search_panel);        searchText = (EditText) findViewById(R.id.editor_search_text);
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

        // v1.64.3：初始未修改 → 保存图标置灰不可点；有改动后自动恢复可点
        setModified(false);
        loadAsync();
    }

    // ============================================================ 搜索 / 替换

    /**
     * v1.76.0：编辑器底部三块操作区统一外观。
     *
     * <ul>
     *   <li>符号键：系统 {@code Button} 的白色方角块（暗色主题下是一片刺眼白块）
     *       → 中性圆角软底，文字跟随主题；</li>
     *   <li>替换/全部：小胶囊；</li>
     *   <li>底部「搜索 跳转 关闭」：搜索=主操作实心，跳转/关闭=描边，
     *       并给后两个加左右间距，三个块之间不再粘在一起。</li>
     * </ul>
     */
    private void styleBottomBar(LinearLayout symBar) {
        for (int i = 0; i < symBar.getChildCount(); i++) {
            View v = symBar.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            tv.setAllCaps(false);
            tv.setStateListAnimator(null);
            tv.setElevation(0f);
            tv.setMinWidth(0);
            tv.setMinimumWidth(0);
            tv.setMinHeight(0);
            tv.setMinimumHeight(0);
            tv.setPadding(UiDecor.dp(this, 8), 0, UiDecor.dp(this, 8), 0);
            tv.setBackground(UiDecor.keyBg(this));
            tv.setTextColor(ThemeManager.text(this));
        }
        UiDecor.barButton((TextView) findViewById(R.id.editor_replace_one), this, UiDecor.KIND_SOFT, 12f);
        UiDecor.barButton((TextView) findViewById(R.id.editor_replace_all), this, UiDecor.KIND_SOFT, 12f);
        UiDecor.gap((TextView) findViewById(R.id.editor_replace_all), this, 4f);

        UiDecor.barButton((TextView) findViewById(R.id.editor_search), this, UiDecor.KIND_PRIMARY, 12.5f);
        UiDecor.barButton((TextView) findViewById(R.id.editor_goto), this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.barButton((TextView) findViewById(R.id.editor_close), this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.gap((TextView) findViewById(R.id.editor_goto), this, 4f);
        UiDecor.gap((TextView) findViewById(R.id.editor_close), this, 4f);
    }

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
        if (regexError) { searchCount.setText(R.string.te_regex_error); return; }
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
                searchCount.setText(R.string.te_regex_error);
                return;
            }
        } else {
            int[] m = matches.get(curMatch);
            s.replace(m[0], m[1], repl);
        }
        suppressWatch = false;
        setModified(true);
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
                searchCount.setText(R.string.te_regex_error);
                return;
            }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                int[] m = matches.get(i);
                s.replace(m[0], m[1], repl);
            }
        }
        suppressWatch = false;
        setModified(true);
        clearSearchHighlight();
        matches.clear();
        searchCount.setText("0/0");
        toast(getString(R.string.te_replaced_all));
    }

    // ============================================================ 跳转行

    private void gotoLine() {
        if (!loaded) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.te_hint_line);
        input.setSingleLine(true);
        new HfDialogBuilder(this)
                .setTitle(R.string.te_goto_title)
                .setView(input)
                .setPositiveButton(R.string.te_goto_action, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        applyGotoLine(input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void applyGotoLine(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        int line;
        try { line = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { toast(getString(R.string.te_err_need_number)); return; }
        if (line < 1) { toast(getString(R.string.te_err_line_from_1)); return; }
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
        if (backStack.isEmpty()) { toast(getString(R.string.te_no_back)); return; }
        int cs = editor.getSelectionStart(), ce = editor.getSelectionEnd();
        forwardStack.addLast(new int[]{cs, ce});
        int[] p = backStack.removeLast();
        editor.setSelection(p[0], p[1]);
        ensureCursorVisible();
    }

    private void goForward() {
        if (forwardStack.isEmpty()) { toast(getString(R.string.te_no_forward)); return; }
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
                            toast(getString(R.string.te_err_read_file));
                            finish();
                            return;
                        }
                        if (tooBig) toast(getString(R.string.te_large_file, FileOps.formatSize(size)));
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
        new HfDialogBuilder(this)
                .setTitle(R.string.te_bin_title)
                .setMessage(R.string.te_bin_msg)
                .setPositiveButton(R.string.te_bin_open_hex, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { toHex(); }
                })
                .setNegativeButton(R.string.te_bin_open_anyway, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        binaryConfirmed = true;
                        loadAsync();
                    }
                })
                .setNeutralButton(R.string.common_close, new DialogInterface.OnClickListener() {
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
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        if ("Tab".equals(sym)) sym = "\t";
        else if ("换行".equals(sym)) sym = "\n";
        else if (" ".equals(sym)) sym = " ";
        editor.insertOrReplaceTextOnCursor(sym);
        suppressWatch = false;
        setModified(true);
        ensureCursorVisible();
    }

    // ============================================================ 编辑操作菜单（✎）

    private void showEditMenu() {
        final String[] items = { getString(R.string.te_op_copy_line),
                getString(R.string.te_op_cut_line), getString(R.string.te_op_delete_line),
                getString(R.string.te_op_clear_line), getString(R.string.te_op_replace_line),
                getString(R.string.te_op_dup_line), getString(R.string.te_op_upper),
                getString(R.string.te_op_lower), getString(R.string.te_op_indent),
                getString(R.string.te_op_outdent), getString(R.string.te_op_custom),
                getString(R.string.te_op_format) };
        new HfDialogBuilder(this)
                .setTitle(R.string.te_edit_ops_title)
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
        toast(getString(R.string.te_copied_line));
    }

    private void cutLine() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        int lineEnd = (r[1] < s.length()) ? r[1] + 1 : r[1]; // 连同换行一起切
        setClip(s.subSequence(r[0], r[1]).toString());
        suppressWatch = true;
        s.delete(r[0], lineEnd);
        suppressWatch = false;
        setModified(true);
        editor.setSelection(r[0]);
    }

    private void deleteLine() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        int lineEnd = (r[1] < s.length()) ? r[1] + 1 : r[1];
        suppressWatch = true;
        s.delete(r[0], lineEnd);
        suppressWatch = false;
        setModified(true);
        editor.setSelection(r[0]);
    }

    private void clearLine() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        suppressWatch = true;
        s.replace(r[0], r[1], "");
        suppressWatch = false;
        setModified(true);
        editor.setSelection(r[0]);
    }

    private void replaceLine() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        final int[] r = lineRange();
        final EditText input = new EditText(this);
        input.setHint(R.string.te_hint_replace_with);
        new HfDialogBuilder(this)
                .setTitle(R.string.te_replace_line_title)
                .setView(input)
                .setPositiveButton(R.string.te_replace_action, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Editable s = editor.getText();
                        suppressWatch = true;
                        s.replace(r[0], r[1], input.getText().toString());
                        suppressWatch = false;
                        setModified(true);
                        editor.setSelection(r[0]);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null).show();
    }

    private void duplicateLine() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        int[] r = lineRange();
        Editable s = editor.getText();
        String line = s.subSequence(r[0], r[1]).toString();
        String add = "\n" + line;
        suppressWatch = true;
        s.insert(r[1], add);
        suppressWatch = false;
        setModified(true);
        editor.setSelection(r[1] + add.length());
    }

    private void transformCase(boolean upper) {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        Editable s = editor.getText();
        int selS = editor.getSelectionStart(), selE = editor.getSelectionEnd();
        int[] r = lineRange();
        int a = (selS != selE) ? Math.min(selS, selE) : r[0];
        int b = (selS != selE) ? Math.max(selS, selE) : r[1];
        if (a == b) { toast(getString(R.string.te_sel_required)); return; }
        String t = s.subSequence(a, b).toString();
        t = upper ? t.toUpperCase() : t.toLowerCase();
        suppressWatch = true;
        s.replace(a, b, t);
        suppressWatch = false;
        setModified(true);
        editor.setSelection(a, a + t.length());
    }

    private void indentLine(boolean add) {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
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
        setModified(true);
        editor.setSelection(firstLineStart + (add ? 1 : 0));
    }

    private void showCustomMenu() {
        final String[] items = { getString(R.string.te_custom_ins_head),
                getString(R.string.te_custom_ins_tail), getString(R.string.te_custom_del_empty),
                getString(R.string.te_custom_sort), getString(R.string.te_custom_dedup) };
        new HfDialogBuilder(this)
                .setTitle(R.string.te_custom_title)
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
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        final EditText input = new EditText(this);
        input.setHint(head ? R.string.te_hint_ins_head : R.string.te_hint_ins_tail);
        new HfDialogBuilder(this)
                .setTitle(head ? R.string.te_hint_ins_head : R.string.te_hint_ins_tail)
                .setView(input)
                .setPositiveButton(R.string.te_confirm, new DialogInterface.OnClickListener() {
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
                        setModified(true);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null).show();
    }

    private void removeEmptyLines() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        Editable s = editor.getText();
        String[] lines = MtTextUtils.splitLines(s.toString());
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            if (!ln.trim().isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(ln); }
        }
        suppressWatch = true;
        s.replace(0, s.length(), sb.toString());
        suppressWatch = false;
        setModified(true);
        toast(getString(R.string.te_deleted_empty));
    }

    private void sortLines() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
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
        setModified(true);
        toast(getString(R.string.te_sorted));
    }

    private void dedupeLines() {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        String[] lines = MtTextUtils.splitLines(editor.getText().toString());
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            if (set.add(ln)) { if (sb.length() > 0) sb.append("\n"); sb.append(ln); }
        }
        suppressWatch = true;
        editor.getText().replace(0, editor.length(), sb.toString());
        suppressWatch = false;
        setModified(true);
        toast(getString(R.string.te_deduped));
    }

    private void setClip(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("hexfile", text));
    }

    // ============================================================ 主菜单（≡ / ⋮）

    private void showMainMenu() {
        final List<String> items = new ArrayList<String>();
        final List<Integer> acts = new ArrayList<Integer>();
        items.add(getString(R.string.te_menu_file)); acts.add(0);
        items.add(getString(R.string.te_menu_search)); acts.add(1);
        items.add(getString(R.string.te_menu_lang, langLabel(currentLang))); acts.add(2);
        items.add(getString(R.string.te_menu_prev)); acts.add(3);
        items.add(getString(R.string.te_menu_next)); acts.add(4);
        items.add(getString(R.string.te_menu_goto)); acts.add(5);
        items.add((userReadOnly ? "☑ " : "☐ ") + getString(R.string.te_readonly)); acts.add(6);
        items.add((highlightOn ? "☑ " : "☐ ") + getString(R.string.te_menu_highlight)); acts.add(7);
        items.add((completionOn ? "☑ " : "☐ ") + getString(R.string.te_menu_completion)); acts.add(8);
        items.add(getString(R.string.te_menu_settings)); acts.add(9);
        items.add(getString(R.string.te_menu_format)); acts.add(12);
        if ("markdown".equals(currentLang))
            { items.add(getString(R.string.te_menu_preview_md)); acts.add(10); }
        items.add(getString(R.string.te_menu_close_file)); acts.add(11);

        new HfDialogBuilder(this)
                .setTitle(R.string.te_menu_title)
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
                                toast(getString(highlightOn ? R.string.te_hl_on : R.string.te_hl_off));
                                break;
                            case 8: completionOn = !completionOn; toast(getString(completionOn
                                    ? R.string.te_comp_on : R.string.te_comp_off)); break;
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
        final String[] items = { getString(R.string.te_file_reopen), getString(R.string.te_file_save_as),
                getString(R.string.te_file_share) };
        new HfDialogBuilder(this)
                .setTitle(R.string.te_menu_file)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) { setModified(false); loadAsync(); }
                        else if (w == 1) saveAsCopy();
                        else shareFile();
                    }
                }).show();
    }

    private void showSyntaxMenu() {
        List<SyntaxHighlight.LangInfo> langs = SyntaxHighlight.listLanguages();
        String[] names = new String[langs.size()];
        for (int i = 0; i < langs.size(); i++) names[i] = langs.get(i).label;
        new HfDialogBuilder(this)
                .setTitle(R.string.te_menu_highlight)
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        currentLang = langs.get(w).id;
                        String text = editor.getText().toString();
                        setHighlightedText(text, true);
                        toast(getString(R.string.te_syntax_selected, langs.get(w).label));
                    }
                }).show();
    }

    private android.text.method.KeyListener savedKeyListener;

    private void toggleReadOnly() {
        if (userReadOnly) {
            userReadOnly = false;
            editor.setKeyListener(savedKeyListener);
            editor.setTextIsSelectable(false);
            toast(getString(R.string.te_readonly_off));
        } else {
            userReadOnly = true;
            savedKeyListener = editor.getKeyListener();
            editor.setKeyListener(null);
            editor.setTextIsSelectable(true);
            toast(getString(R.string.te_readonly));
        }
    }

    private void showSettings() {
        // v1.63.4：放大/缩小 → 直接输入 sp
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.valueOf(fontSize));
        input.setSelection(input.getText().length());
        new HfDialogBuilder(this)
                .setTitle(R.string.te_font_title)
                .setView(input)
                .setPositiveButton(R.string.te_confirm, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            float v = Float.parseFloat(input.getText().toString().trim());
                            v = Math.max(8f, Math.min(40f, v));
                            fontSize = (int) v;
                            editor.setTextSize(v);
                            toast(getString(R.string.te_font_applied, fontSize));
                        } catch (Exception e) {
                            toast(getString(R.string.te_format_error));
                        }
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    // ============================================================ 格式化（v1.29.0）

    private void showFormatMenu() {
        final String[] items = { getString(R.string.te_fmt_json_pretty),
                getString(R.string.te_fmt_json_min), getString(R.string.te_fmt_xml),
                getString(R.string.te_fmt_code) };
        final int[] modes = { FormatUtil.FMT_JSON, FormatUtil.FMT_JSON_MIN,
                FormatUtil.FMT_XML, FormatUtil.FMT_CODE };
        final int detected = FormatUtil.detectFormat(
                file != null ? file.getName() : "", editor.getText().toString());
        String title = getString(R.string.te_fmt_title);
        if (detected == FormatUtil.FMT_JSON)
            title = getString(R.string.te_fmt_title_detected, "JSON");
        else if (detected == FormatUtil.FMT_XML)
            title = getString(R.string.te_fmt_title_detected, "XML/HTML");
        else if (detected == FormatUtil.FMT_CODE)
            title = getString(R.string.te_fmt_title_detected,
                    getString(R.string.te_fmt_kind_code));
        new HfDialogBuilder(this)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doFormat(modes[w]);
                    }
                }).show();
    }

    private void doFormat(int mode) {
        if (userReadOnly) { toast(getString(R.string.te_readonly)); return; }
        String src = editor.getText().toString();
        if (src.trim().isEmpty()) { toast(getString(R.string.te_err_empty_content)); return; }
        if (src.length() > 2 * 1024 * 1024) { toast(getString(R.string.te_err_too_big_fmt)); return; }
        try {
            String out = FormatUtil.format(src, mode);
            if (out.equals(src)) { toast(getString(R.string.te_fmt_no_change)); return; }
            int caret = editor.getSelectionStart();
            suppressWatch = true;
            editor.setText(out);
            suppressWatch = false;
            if (caret >= 0 && caret <= editor.length()) editor.setSelection(caret);
            setModified(true);
            toast(getString(R.string.te_fmt_done));
        } catch (FormatUtil.FormatException e) {
            toast(getString(R.string.te_fmt_failed, e.getMessage()));
        }
    }

    private void previewMarkdown() {
        String html = MarkdownRender.render(editor.getText().toString());
        AlertDialog.Builder b = new HfDialogBuilder(this);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        b.setTitle(R.string.te_md_preview_title).setView(wv)
                .setPositiveButton(R.string.common_close, null).show();
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
                setModified(true);
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
                if (!ok) { toast(getString(R.string.te_err_save_decompile)); return; }
                setModified(false);
            } catch (Exception e) {
                toast(getString(R.string.te_err_save, e.getMessage()));
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

    /**
     * v1.64.3：修改状态统一入口 ——
     * 未修改时保存图标置灰不可点（防误触保存），产生改动后恢复可点。
     * 全部 dirty 写入（TextWatcher/行操作/替换/补全等）都走这里。
     */
    private void setModified(boolean m) {
        modified = m;
        if (saveBtn != null) {
            saveBtn.setEnabled(m);
            saveBtn.setAlpha(m ? 1f : 0.35f);
        }
    }

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
            toast(getString(R.string.te_saved_as, out.getName()));
        } catch (Exception e) {
            toast(getString(R.string.te_err_save_as, e.getMessage()));
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
            startActivity(Intent.createChooser(i, getString(R.string.te_file_share)));
        } catch (Exception e) {
            toast(getString(R.string.te_err_share, e.getMessage()));
        }
    }

    private void saveRoundtrip() {
        final String text = editor.getText().toString();
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.te_compiling_xml));
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
                    // v1.66.8：登记 + 会话重打包写回原包（首次修改自动备份 _bak.apk）
                    outApk = ApkEditSession.putEdit(TextEditorActivity.this,
                            rtApkFile, "AndroidManifest.xml", newAxml);
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
                            pendingFinishAfterSave = false;
                            new HfDialogBuilder(TextEditorActivity.this)
                                    .setTitle(fuerr ? R.string.te_compile_unsupported
                                            : R.string.te_compile_failed)
                                    .setMessage(ferr)
                                    .setPositiveButton(R.string.common_got_it, null).show();
                            return;
                        }
                        setModified(false);
                        toast(getString(R.string.te_compiled_output, fout.getName()));
                        if (pendingFinishAfterSave) finish();
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
                        if (ApkEditSession.isApk(archiveFile)) {
                            // v1.66.8：登记修改并重打包写回原包（首次修改自动备份 _bak.apk）
                            ApkEditSession.putEdit(TextEditorActivity.this,
                                    archiveFile, entryName, data);
                        } else if (!ArchiveWrite.writeEntry(archiveFile, entryName, data)) {
                            err = getString(R.string.te_err_writeback);
                        }
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
                final boolean editGenerated = done && archiveFile != null && entryName != null
                        && ApkEditSession.isApk(archiveFile);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (done) {
                            setModified(false);
                            if (editGenerated) {
                                toast(getString(R.string.te_saved_writeback_bak));
                                if (pendingFinishAfterSave) finish();
                            } else if (archiveFile != null) {
                                toast(getString(R.string.te_written_archive, encoding));
                                if (pendingFinishAfterSave) finish();
                            } else if (exportDir != null) {
                                toast(getString(R.string.te_saved_export, exportDir.getPath()));
                                if (pendingFinishAfterSave) finish();
                            } else {
                                toast(getString(R.string.te_saved_encoding, encoding));
                                if (pendingFinishAfterSave) finish();
                            }
                        } else {
                            pendingFinishAfterSave = false;  // 保存失败留在编辑器，可重试
                            toast(getString(R.string.te_err_save, ferr));
                        }
                    }
                });
            }
        }, "text-save").start();
    }

    /**
     * v1.64.0：ARSC++ 编辑保存 —— 编辑的是 values 风格 XML，
     * 保存时解析 XML → 生成对 resources.arsc 的修改（ArscPlus.applyXml → ArscWriter.write）
     * → v1.66.0：登记到 ApkEditSession 统一重生成 <名>_edit.apk（不原地写回、不再单独签名）。
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
                    // 2) 重新解析 arsc（会话中有未落地修改则优先，避免用过期模型）
                    byte[] arscBytes = ApkEditSession.editBytes(archiveFile, arscppEntry);
                    if (arscBytes == null) arscBytes = readZipEntry(archiveFile, arscppEntry);
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
                        // 4) 登记 + 会话重打包写回原包（首次修改时备份 _bak.apk）
                        ApkEditSession.putEdit(TextEditorActivity.this,
                                archiveFile, arscppEntry, newArsc);
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
                            pendingFinishAfterSave = false;
                            new HfDialogBuilder(TextEditorActivity.this)
                                    .setTitle(R.string.te_err_save_title)
                                    .setMessage(ferr)
                                    .setPositiveButton(R.string.common_got_it, null).show();
                        } else if (fn == 0) {
                            setModified(false);
                            toast(getString(R.string.te_nothing_to_save));
                            if (pendingFinishAfterSave) finish();
                        } else {
                            setModified(false);
                            toast(getString(R.string.te_arscpp_saved, arscppEntry, fn));
                            if (pendingFinishAfterSave) finish();
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
        // v1.66.9：Dex 编辑器工程 smali → 静默保存退出（无弹窗；编译/写回集中在退出 Dex 编辑器时）
        if (silentSave) {
            pendingFinishAfterSave = true;
            save();
            return;
        }
        new HfDialogBuilder(this)
                .setTitle(R.string.te_unsaved_title)
                .setMessage(R.string.te_unsaved_msg)
                .setPositiveButton(R.string.common_save, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // v1.64.3：不立即退出 —— 保存完成后按场景提示自动签名，再统一退出
                        pendingFinishAfterSave = true;
                        save();
                    }
                })
                .setNegativeButton(R.string.te_unsaved_discard, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNeutralButton(R.string.common_cancel, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
