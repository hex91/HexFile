package com.hex91.file;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端模拟器界面（v1.49.0 起改用 Termux Apache-2.0 终端引擎）。
 *
 * <p>架构：{@link TerminalView} 负责渲染/输入/选区/缩放手势，{@link TerminalSession} 负责与
 * NDK 伪终端（libtermux.so）的进程桥接，仿真引擎为 Termux 的 TerminalEmulator（VT100/xterm，
 * 含 Unicode 宽度、回流、鼠标、文本选区）。本 Activity 仅做编排：构造会话、接入两个 Client 回调、
 * 顶栏操作与额外按键条。</p>
 *
 * <p>终端配色与字号可在「Aa」设置对话框选择：四套方案（经典黑/高对比/墨蓝/纸白）+ 字号 8–32 步进调节，
 * 捏合缩放同效，均持久化到 SharedPreferences。顶栏与按键条跟随应用主题。</p>
 */
public final class TerminalActivity extends BaseActivity implements TerminalSessionClient, TerminalViewClient {

    /** 启动 extra：初始工作目录 */
    public static final String EXTRA_CWD = "cwd";

    /** 终端 HOME（profile 与 hex 助手所在目录，已加入 PATH）。
     *  放在应用私有外部存储下，避免 Android 10+ 作用域存储禁止直接写 /sdcard 的问题。 */

    private TerminalView mView;
    private TextView mTitle;
    private TerminalSession mSession;
    private String mCwd;

    /** 额外按键：Ctrl 锁定态（供 {@link #readControlKey()} 返回） */
    private boolean mCtrlMode = false;
    private TextView mCtrlBtn;

    // ----------------------------------------------------------------
    // 显示配置：字号 + 配色方案（持久化到 SharedPreferences）
    // ----------------------------------------------------------------

    private static final String PREFS = "terminal_display";
    private static final String KEY_FONT = "font_size";
    private static final String KEY_SCHEME = "scheme";

    /** 配色方案：{背景, 前景, 光标}。经典黑 / 高对比 / 墨蓝(Solarized 暗) / 纸白(浅色) */
    private static final int[][] SCHEMES = {
            {0xFF0B0B0B, 0xFFE6E6E6, 0xFFE6E6E6}, // 0 经典黑
            {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF}, // 1 高对比
            {0xFF002B36, 0xFFEEE8D5, 0xFFEEE8D5}, // 2 墨蓝
            {0xFFF7F3E8, 0xFF333333, 0xFF333333}, // 3 纸白
    };
    private static final String[] SCHEME_NAMES = {"经典黑", "高对比", "墨蓝", "纸白"};

    private int mScheme = 0;
    private TextView[] mSchemeChips;
    private TextView mSizeValText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        mTitle = findViewById(R.id.term_title);
        mView = findViewById(R.id.term_view);
        // Termux 的 TerminalView 重写了 onCheckIsTextEditor() 但未在构造器设置 focusableInTouchMode，
        // 触摸模式下 requestFocus() 会失败，导致 showSoftInput 因视图未 served 被拒、软键盘弹不出来。
        mView.setFocusable(true);
        mView.setFocusableInTouchMode(true);

        mView.setTerminalViewClient(this);

        // 读持久化显示配置：字号（默认 16，13 在高分屏上偏小看不清）+ 配色方案
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mTextSize = Math.max(8f, Math.min(32f, sp.getFloat(KEY_FONT, 16f)));
        mScheme = sp.getInt(KEY_SCHEME, 0);

        // 顺序必须是先 setTextSize（创建内部 mRenderer）再 setTypeface，否则 NPE
        mView.setTextSize((int) mTextSize);
        mView.setTypeface(Typeface.MONOSPACE);
        // 先应用配色再建会话：emulator 创建时会从 TerminalColors.COLOR_SCHEME 拷贝色表
        applyScheme(mScheme, false);

        mCwd = getIntent().getStringExtra(EXTRA_CWD);
        if (mCwd == null || mCwd.isEmpty()) {
            File ext = getExternalFilesDir(null);
            mCwd = (ext != null ? ext.getAbsolutePath() : "/sdcard");
        }

        setupTermHome();
        createSession(mCwd);

        // 弹软键盘需同时满足：视图已量出尺寸 + 窗口已获得焦点（二者谁后到都触发，仅自动弹一次）
        mView.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutKeyboardListener);

        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { createSession(mCwd); showKeyboard(); }
        });
        findViewById(R.id.btn_font).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDisplayDialog(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 直接在仿真器上清屏（光标回位 + 清可见屏 + 清回滚缓冲）。
                // 不能往 shell stdin 写转义序列——那是键盘输入通道，shell 会把序列当字符回显（"JJ"）。
                TerminalSession s = mView.getCurrentSession();
                if (s != null && s.getEmulator() != null) {
                    s.getEmulator().clearScreen();
                    mView.onScreenUpdated();
                }
            }
        });
        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPaste(); }
        });
        findViewById(R.id.btn_exit).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        buildExtraKeys();
    }

    /** 创建（或重建）终端会话，并接入视图。视图量出尺寸后 {@link TerminalView} 才会真正起进程。 */
    private void createSession(String cwd) {
        if (mSession != null && mSession.isRunning()) mSession.finishIfRunning();

        File dir = new File(cwd);
        if (!dir.isDirectory()) cwd = "/sdcard";

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("HOME", termHome());
        env.put("PATH", termHome() + "/bin:/system/bin:/system/xbin:/sbin:/vendor/bin");
        env.put("LANG", "en_US.UTF-8");
        env.put("COLORTERM", "truecolor");
        List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) envList.add(e.getKey() + "=" + e.getValue());

        mSession = new TerminalSession("/system/bin/sh", cwd, new String[]{"-l"},
                envList.toArray(new String[0]), 2000, this);
        mView.attachSession(mSession);
    }

    /** 顶栏“粘贴”：把系统剪贴板内容写入终端 */
    private void doPaste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && mSession != null) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null) writeUtf8(t.toString());
        }
    }

    /** 以 UTF-8 把字符串写入终端（Termux 的 write 仅接受 byte[] + 偏移 + 长度） */
    private void writeUtf8(String s) {
        if (mSession == null || s == null) return;
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mSession.write(b, 0, b.length);
    }

    // ----------------------------------------------------------------
    // 显示配置：配色方案 + 字号
    // ----------------------------------------------------------------

    /**
     * 应用配色方案：改 {@link TerminalColors#COLOR_SCHEME} 的前景/背景/光标三个默认色索引，
     * 再让活动仿真器的当前色表整体重置并重绘。注意 Termux 渲染器对「默认背景」单元格不画矩形，
     * 依赖视图自身背景透出，因此必须同步 {@code mView.setBackgroundColor}。
     *
     * @param persist false 用于 onCreate（写 SharedPreferences 留给用户操作时再做）
     */
    private void applyScheme(int index, boolean persist) {
        if (index < 0 || index >= SCHEMES.length) index = 0;
        mScheme = index;
        int bg = SCHEMES[index][0], fg = SCHEMES[index][1], cur = SCHEMES[index][2];
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg;
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg;
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = cur;
        if (mView != null) {
            mView.setBackgroundColor(bg);
            TerminalSession s = mView.getCurrentSession();
            if (s != null && s.getEmulator() != null) {
                s.getEmulator().mColors.reset();
                mView.onScreenUpdated();
            }
        }
        if (persist) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SCHEME, index).apply();
            refreshSchemeChips();
        }
    }

    /** 应用字号（8–32）并持久化。 */
    private void applyFontSize(float size) {
        mTextSize = Math.max(8f, Math.min(32f, size));
        mView.setTextSize((int) mTextSize);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_FONT, mTextSize).apply();
        if (mSizeValText != null) mSizeValText.setText(String.valueOf((int) mTextSize));
    }

    /** 「Aa」按钮：显示设置对话框（字号步进调节 + 四套配色方案单选）。 */
    private void showDisplayDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(4));

        // —— 字号行 ——
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("字号");
        sizeLabel.setTextSize(15);
        sizeLabel.setTextColor(ThemeManager.text(this));
        box.addView(sizeLabel);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(0, dp(8), 0, dp(8));

        mSizeValText = new TextView(this);
        mSizeValText.setText(String.valueOf((int) mTextSize));
        mSizeValText.setTextSize(18);
        mSizeValText.setTextColor(ThemeManager.accent(this));
        mSizeValText.setMinWidth(dp(44));
        mSizeValText.setGravity(Gravity.CENTER);

        TextView minus = dialogKey("A−");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyFontSize(mTextSize - 1); }
        });
        TextView plus = dialogKey("A＋");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyFontSize(mTextSize + 1); }
        });
        sizeRow.addView(minus);
        sizeRow.addView(mSizeValText);
        sizeRow.addView(plus);
        box.addView(sizeRow);

        // —— 配色行 ——
        TextView schemeLabel = new TextView(this);
        schemeLabel.setText("配色");
        schemeLabel.setTextSize(15);
        schemeLabel.setTextColor(ThemeManager.text(this));
        box.addView(schemeLabel);

        LinearLayout schemeRow = new LinearLayout(this);
        schemeRow.setOrientation(LinearLayout.HORIZONTAL);
        schemeRow.setPadding(0, dp(8), 0, dp(8));
        mSchemeChips = new TextView[SCHEMES.length];
        for (int i = 0; i < SCHEMES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(SCHEME_NAMES[i]);
            chip.setTextSize(14);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setTextColor(ThemeManager.text(this));
            chip.setBackgroundResource(android.R.drawable.list_selector_background);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyScheme(idx, true); }
            });
            mSchemeChips[i] = chip;
            schemeRow.addView(chip);
        }
        refreshSchemeChips();
        box.addView(schemeRow);

        new AlertDialog.Builder(this)
                .setTitle("终端显示")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 对话框里的调节按钮样式 */
    private TextView dialogKey(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setTextColor(ThemeManager.text(this));
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        return b;
    }

    /** 选中配色高亮 */
    private void refreshSchemeChips() {
        if (mSchemeChips == null) return;
        for (int i = 0; i < mSchemeChips.length; i++) {
            boolean sel = (i == mScheme);
            mSchemeChips[i].setBackgroundColor(sel ? ThemeManager.accent(this) : 0);
            mSchemeChips[i].setTextColor(sel ? ThemeManager.onAccent(this) : ThemeManager.text(this));
        }
    }

    private void showKeyboard() {
        mView.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(mView, InputMethodManager.SHOW_IMPLICIT);
    }

    /** 自动弹键盘：布局完成且窗口有焦点后才执行一次（onCreate 时二者均未就绪，提前 showSoftInput 会被 IME 忽略）。 */
    private boolean mAutoKeyboardDone = false;

    private final android.view.ViewTreeObserver.OnGlobalLayoutListener mLayoutKeyboardListener =
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() { tryAutoShowKeyboard(); }
            };

    private void tryAutoShowKeyboard() {
        if (mAutoKeyboardDone) return;
        if (mView.getWidth() > 0 && mView.getHeight() > 0 && mView.hasWindowFocus()) {
            mAutoKeyboardDone = true;
            mView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutKeyboardListener);
            showKeyboard();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) tryAutoShowKeyboard();
    }

    /** 额外按键条：Ctrl(锁定) / Esc / Tab / 符号 / 方向键 / Home / End */
    private void buildExtraKeys() {
        LinearLayout row = findViewById(R.id.keys_row);
        final int[][] keys = {
                {0, R.string.term_key_ctrl},
                {1, R.string.term_key_esc},
                {2, R.string.term_key_tab},
                {3, R.string.term_key_slash},
                {4, R.string.term_key_dash},
                {5, R.string.term_key_pipe},
                {6, R.string.term_key_up},
                {7, R.string.term_key_down},
                {8, R.string.term_key_left},
                {9, R.string.term_key_right},
                {10, R.string.term_key_home},
                {11, R.string.term_key_end},
        };
        for (int k = 0; k < keys.length; k++) {
            final int kind = keys[k][0];
            TextView b = new TextView(this);
            b.setText(keys[k][1]);
            b.setTextSize(14);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(8), dp(12), dp(8));
            b.setTextColor(ThemeManager.text(this));
            b.setBackgroundResource(android.R.drawable.list_selector_background);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (mSession == null) return;
                    if (kind == 0) {
                        mCtrlMode = !mCtrlMode;
                        b.setBackgroundColor(mCtrlMode ? ThemeManager.accent(TerminalActivity.this) : 0);
                        b.setTextColor(mCtrlMode ? ThemeManager.onAccent(TerminalActivity.this) : ThemeManager.text(TerminalActivity.this));
                    } else {
                        TerminalSession s = mView.getCurrentSession();
                        if (s != null && s.getEmulator() != null) {
                            switch (kind) {
                                case 1: mView.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0); break;
                                case 2: mView.handleKeyCode(KeyEvent.KEYCODE_TAB, 0); break;
                                case 6: mView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); break;
                                case 7: mView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); break;
                                case 8: mView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); break;
                                case 9: mView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); break;
                                case 10: mView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); break;
                                case 11: mView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); break;
                                default:
                                    String ch = (kind == 3) ? "/" : (kind == 4) ? "-" : "|";
                                    writeUtf8(ch);
                            }
                        }
                    }
                }
            });
            if (kind == 0) mCtrlBtn = b;
            row.addView(b);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 终端 HOME 目录：优先应用私有外部存储（始终可写），回退到内部私有存储。 */
    private String termHome() {
        File ext = getExternalFilesDir(null);
        File base = (ext != null) ? ext : getFilesDir();
        return new File(base, "term").getAbsolutePath();
    }

    /** 初始化终端 HOME：写入 .profile 与 hex 助手脚本（仅首次/缺失时） */
    private void setupTermHome() {
        try {
            File home = new File(termHome());
            home.mkdirs();
            File bin = new File(home, "bin");
            bin.mkdirs();
            File profile = new File(home, ".profile");
            // 每次启动都重写（内容小，保证旧版本安装升级后立即拿到最新配置）
            writeFile(profile,
                    "# HexFile 终端配置\n" +
                    "alias ll='ls -la'\n" +
                    "alias la='ls -a'\n" +
                    "alias l='ls -l'\n" +
                    "alias grep='grep --color=auto'\n" +
                    // Android 10+ 应用私有外部存储挂载为 noexec，脚本不能直接执行（execve 被拒，
                    // 报 can't execute: permission denied）；sh 读脚本是普通文件读取不受限制，
                    // 因此用函数包装：hex → sh $HOME/bin/hex
                    "hex() { sh \"$HOME/bin/hex\" \"$@\"; }\n");
            File hex = new File(bin, "hex");
            if (!hex.exists()) {
                writeFile(hex,
                        "#!/system/bin/sh\n" +
                        "# HexFile 终端助手\n" +
                        "case \"$1\" in\n" +
                        "  help)\n" +
                        "    echo \"HexFile 终端助手 (hex)\"\n" +
                        "    echo \"  hex help   显示本帮助\"\n" +
                        "    echo \"  hex tools  列出内置工具\"\n" +
                        "    echo \"  hex theme  显示主题信息\"\n" +
                        "    echo \"  hex clear  清屏\"\n" +
                        "    ;;\n" +
                        "  tools)\n" +
                        "    echo \"可用：sh、toybox 全套(ls/cat/cp/mv/rm/grep/sed/awk/ps/...)、pm、am、mount\"\n" +
                        "    echo \"自定义脚本目录：\"$HOME\"/bin (已在 PATH)\"\n" +
                        "    ;;\n" +
                        "  theme)\n" +
                        "    echo \"终端配色：经典黑/高对比/墨蓝/纸白（顶栏 Aa 可切换），字号 A−/A＋ 可调。\"\n" +
                        "    ;;\n" +
                        "  clear)\n" +
                        "    clear\n" +
                        "    ;;\n" +
                        "  *)\n" +
                        "    echo \"用法: hex <help|tools|theme|clear>\"\n" +
                        "    ;;\n" +
                        "esac\n");
                hex.setExecutable(true);
            }
        } catch (Throwable ignore) {
        }
    }

    private void writeFile(File f, String text) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    // ----------------------------------------------------------------
    // TerminalSessionClient：会话 → Activity 的回调
    // ----------------------------------------------------------------

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mView != null) mView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        final String t = changedSession.getTitle();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mTitle != null) mTitle.setText((t == null || t.isEmpty()) ? "终端" : "终端 — " + t);
            }
        });
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(TerminalActivity.this,
                        "终端会话已结束（按 ＋ 新建）", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setText(text);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        doPaste();
    }

    @Override
    public void onBell(TerminalSession session) {
        // 视觉/震动反馈按需扩展；当前保持安静
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) { Log.e(tag, message); }
    @Override
    public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override
    public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override
    public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override
    public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override
    public void logStackTrace(String tag, Exception e) { Log.e(tag, "", e); }

    // ----------------------------------------------------------------
    // TerminalViewClient：视图 → Activity 的回调
    // ----------------------------------------------------------------

    private float mTextSize = 13f;

    @Override
    public float onScale(float scale) {
        // 捏合缩放：调整字号（Termux 原逻辑由 Activity 决定新字号），与 Aa 对话框共用持久化
        applyFontSize(mTextSize * scale);
        return 1f; // 复位 scale，使后续缩放回合以当前字号为基准
    }

    @Override
    public void onSingleTapUp(android.view.MotionEvent e) {
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override
    public boolean shouldEnforceCharBasedInput() { return false; }
    @Override
    public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override
    public boolean isTerminalViewSelected() { return true; }
    @Override
    public void copyModeChanged(boolean copyMode) { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override
    public boolean onLongPress(android.view.MotionEvent event) { return false; }

    @Override
    public boolean readControlKey() { return mCtrlMode; }
    @Override
    public boolean readAltKey() { return false; }
    @Override
    public boolean readShiftKey() { return false; }
    @Override
    public boolean readFnKey() { return false; }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }

    @Override
    public void onEmulatorSet() { }

    // ----------------------------------------------------------------
    // 生命周期
    // ----------------------------------------------------------------

    /**
     * 终端会话（shell 进程）必须跨配置/主题变化存活：关闭重建，
     * 避免软键盘弹出、屏幕旋转、深浅色切换杀掉正在运行的 shell。
     * 终端视图自带固定近黑配色，不依赖应用明暗主题，无需重建。
     */
    @Override
    protected boolean shouldRecreateOnThemeChange() { return false; }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 不重建：Termux 的 TerminalView 会自行处理尺寸/重排与缩放手势。
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onPause() {
        super.onPause();
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null && mView != null) im.hideSoftInputFromWindow(mView.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSession != null) mSession.finishIfRunning();
    }
}
