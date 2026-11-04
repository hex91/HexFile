package com.hex91.file;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

import com.hex91.file.HexCore.Type;

/**
 * HEX 编辑器（v1.68.1）：
 *
 * <p>布局：顶栏（文件名 / 文件解析 / ⌨ 键盘 / 💾 保存）+ 可横滑工具条（含 Aa 字号）
 * + 与行文本严格对齐的列头 + 列表 + 底部十六进制键盘。
 *
 * <p>编辑模型：**逐字节、就地改内存、点 💾 才落盘**
 * <ul>
 *   <li>点行内任意字节 → 该字节成为光标（蓝底），底部键盘同步定位到它；</li>
 *   <li>底部只有 0-9 / A-F 十六进制键：按满 2 位立刻写入该字节并自动右移一格，
 *       不再有「从行尾追加」的整行输入框，也不用系统键盘；</li>
 *   <li>◀ / ▶ 逐字节移动，⌫ 退半字节或退回上一字节；</li>
 *   <li>保存图标默认灰掉，内存与磁盘不一致时才点亮，点击后按「差异区间」批量写入；</li>
 *   <li>字号可调（Aa，60%~250%），放大后列头与正文一起横向滚动，始终对齐。</li>
 * </ul>
 *
 * <p>另有「块跳转」（按 16B~4KB 分块定位）与「文件解析」（{@link FileInspect}：魔数、ELF 头/节表、
 * ZIP 签名块与证书）。
 *
 * <p>限制：只加载并允许编辑前 4MB；更大的文件超出部分只读（不会误改）。
 */
public class HexViewerActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";
    private static final long LIMIT = 4L * 1024 * 1024; // 4MB
    private static final int ROW = 16;
    private static final int MAX_UNDO = 200;
    /** 字号设置持久化 */
    private static final String PREF = "hex_view";
    private static final String KEY_FONT_SCALE = "font_scale";

    private static final char[] HEXC = "0123456789ABCDEF".toCharArray();

    private static final String[] TYPE_LABELS = {
            "十六进制 HEX", "文本 TEXT",
            "Int8", "UInt8", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64",
            "Float32", "Float64"
    };
    private static final Type[] TYPE_VALS = {
            Type.HEX, Type.TEXT,
            Type.INT8, Type.UINT8, Type.INT16, Type.UINT16, Type.INT32, Type.UINT32,
            Type.INT64, Type.UINT64, Type.FLOAT32, Type.FLOAT64
    };
    private static final String[] CHARSETS = {"ASCII", "UTF-8", "UTF-16LE", "ISO-8859-1", "Shift_JIS"};
    private static final int[] BASE_ORDER = {HexCore.BASE_HEX, HexCore.BASE_BIN,
            HexCore.BASE_OCT, HexCore.BASE_DEC};
    private static final String[] BASE_NAME = {"HEX", "BIN", "OCT", "DEC"};

    private File file;
    private long fileSize;
    private boolean truncated;
    /** 工作缓冲（编辑改这里，保存时才落盘） */
    private byte[] data;
    /** 载入时的快照：用于 diff 保存与 dirty 判定 */
    private byte[] orig;
    private boolean dirty;

    private ListView list;
    private android.widget.HorizontalScrollView listScroll, headerScroll;
    private TextView title, sub, header, saveBtn, baseBtn, charsetBtn;
    private View panel;
    private TextView panelOffTv, panelCurTv, panelHint;
    private LinearLayout keysBox;

    private int codeBase = HexCore.BASE_HEX;
    private String charsetName = "ASCII";

    /** 行/列头字号（px）：100% 时按屏幕宽度自适应，保证一行字符正好铺满不换行 */
    private float rowPx, headPx;
    /** 用户字号倍数（Aa 调整，持久化到 hex_view） */
    private float fontScale = 1f;
    /** 等宽字体在 200px 下 "0" 的宽度，用于任意字号下的列宽反算 */
    private float charW200 = 120f;

    private int selectedRow = 0;
    private int cursorCol = 0;
    /** 当前待编辑字节的绝对偏移（点哪个字节就是哪个，不再按 8 对齐） */
    private int editOff = 0;
    private boolean panelOpen = false;
    /** 半个字节的输入缓冲：满 2 位才写入，保证「按两位写一个字节」 */
    private String nibble = "";
    /** 最近一次按下时的横坐标（相对列表），用于把点击换算成第几个字节 */
    private float downX = -1;

    // 搜索
    private int[] matches = null;
    private int patLen = 0;
    private int curMatch = 0;

    // 撤销/重做（内存快照）
    private static final class HexEdit {
        final int off;
        final byte[] oldB;
        final byte[] newB;

        HexEdit(int off, byte[] oldB, byte[] newB) {
            this.off = off;
            this.oldB = oldB;
            this.newB = newB;
        }
    }

    private final ArrayDeque<HexEdit> undoStack = new ArrayDeque<HexEdit>();
    private final ArrayDeque<HexEdit> redoStack = new ArrayDeque<HexEdit>();

    // ============================================================ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_hex);

        String path = getIntent().getStringExtra(EXTRA_FILE);
        if (path == null) { finish(); return; }
        file = new File(path);
        fileSize = file.length();
        truncated = fileSize > LIMIT;

        title = (TextView) findViewById(R.id.hex_title);
        sub = (TextView) findViewById(R.id.hex_sub);
        header = (TextView) findViewById(R.id.hex_header);
        list = (ListView) findViewById(R.id.hex_list);
        listScroll = (android.widget.HorizontalScrollView) findViewById(R.id.hex_list_scroll);
        headerScroll = (android.widget.HorizontalScrollView) findViewById(R.id.hex_header_scroll);
        saveBtn = (TextView) findViewById(R.id.hex_save);
        baseBtn = (TextView) findViewById(R.id.hex_base);
        charsetBtn = (TextView) findViewById(R.id.hex_charset);
        panel = findViewById(R.id.hex_panel);
        panelOffTv = (TextView) findViewById(R.id.hex_panel_off);
        panelCurTv = (TextView) findViewById(R.id.hex_panel_cur);
        panelHint = (TextView) findViewById(R.id.hex_panel_hint);
        keysBox = (LinearLayout) findViewById(R.id.hex_keys);

        fontScale = getSharedPreferences(PREF, MODE_PRIVATE).getFloat(KEY_FONT_SCALE, 1f);
        if (fontScale < 0.6f || fontScale > 2.5f) fontScale = 1f;

        title.setText(file.getName());
        header.setTypeface(Typeface.MONOSPACE);
        measureTextSize();
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, headPx);
        header.setText(headerLine());
        syncScroll();
        buildKeys();

        findViewById(R.id.hex_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.hex_info).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFileInfo(); }
        });
        findViewById(R.id.hex_keyboard).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePanel(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        findViewById(R.id.hex_jump).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askJump(); }
        });
        findViewById(R.id.hex_block).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askBlock(); }
        });
        findViewById(R.id.hex_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSearch(); }
        });
        findViewById(R.id.hex_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askCopy(); }
        });
        findViewById(R.id.hex_undo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { performUndo(); }
        });
        findViewById(R.id.hex_redo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { performRedo(); }
        });
        baseBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleBase(); }
        });
        charsetBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askCharset(); }
        });
        findViewById(R.id.hex_font).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askFontSize(); }
        });

        findViewById(R.id.hex_panel_prev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { moveEdit(-1); }
        });
        findViewById(R.id.hex_panel_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { moveEdit(1); }
        });
        findViewById(R.id.hex_panel_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePanel(); }
        });

        // 点击行的哪个字节就选中哪个字节：ListView 的 itemClick 里拿不到 x，这里先把按下坐标留下
        list.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) downX = e.getX();
                return false;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                selectByte(i * ROW + columnAt(downX, i), i);
            }
        });
        // 长按 = 选中该字节并直接打开十六进制键盘（比先点字节再点 ⌨ 快一步）
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                selectByte(i * ROW + columnAt(downX, i), i);
                openPanelAt(editOff);
                return true;
            }
        });

        setDirty(false);
        loadAsync();
    }

    @Override
    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
                .setTitle("有未保存的修改")
                .setMessage("内存里有改动尚未写回文件。\n\n" + file.getName())
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { saveThenFinish(); }
                })
                .setNeutralButton("放弃修改", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================ 载入

    private void loadAsync() {
        new Thread(new Runnable() {
            public void run() {
                byte[] d = readPrefix();
                final byte[] fd = d;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fd == null) {
                            toast("无法读取文件");
                            finish();
                            return;
                        }
                        data = fd;
                        orig = fd.clone();
                        list.setAdapter(adapter);
                        list.setSelection(0);
                        updateStatus();
                    }
                });
            }
        }, "hex-load").start();
    }

    private byte[] readPrefix() {
        int n = (int) Math.min(LIMIT, Math.max(0, fileSize));
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] d = new byte[n];
                int off = 0, r;
                while (off < n && (r = in.read(d, off, n - off)) > 0) off += r;
                if (off == n) return d;
                byte[] cut = new byte[off];
                System.arraycopy(d, 0, cut, 0, off);
                return cut;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            // root 区回退（su cat）
            byte[] rb = RootIO.readBytes(file);
            if (rb == null) return null;
            if (rb.length > LIMIT) {
                byte[] cut = new byte[(int) LIMIT];
                System.arraycopy(rb, 0, cut, 0, cut.length);
                return cut;
            }
            return rb;
        }
    }

    /**
     * 按屏幕可用宽度算字号：HEX 一行 76 字符（8 偏移 + 2 空格 + 16×3 + 2 + 16），
     * 100% 正好铺满一行 → 列头与行永远对齐、文本列不会被挤出屏幕。
     * 用户用 Aa 放大后按倍数放大，并把列表加宽交给横向滚动，不再裁字。
     */
    private void measureTextSize() {
        int avail = getResources().getDisplayMetrics().widthPixels - dp(16);
        int totalChars = HexCore.textColumn(codeBase, ROW) + ROW;
        android.text.TextPaint p = new android.text.TextPaint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(200f);
        float w200 = p.measureText("0");
        charW200 = w200 > 0 ? w200 : 120f;
        float fit;
        if (avail <= 0) {
            fit = dp(11);
        } else {
            fit = 200f * avail / (charW200 * totalChars);
            // 下限 9sp 保可读性；BIN 进制一行 172 字符放不下时靠横向滚动而不是缩成蚂蚁字
            fit = Math.max(dp(9), Math.min(fit, dp(13)));
        }
        rowPx = fit * fontScale;
        headPx = rowPx;
        updateListWidth(totalChars);
    }

    /** 按当前字号把列表/列头加宽到「一行字符完整放得下」，超出屏宽就横向滚动 */
    private void updateListWidth(int totalChars) {
        if (list == null) return;
        float cw = charW200 * rowPx / 200f;
        int need = dp(16) + Math.round(totalChars * cw) + dp(2);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels, need);
        setViewWidth(list, width);
        if (header != null) setViewWidth(header, width);
    }

    private void setViewWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || lp.width == w) return;
        lp.width = w;
        v.setLayoutParams(lp);
    }

    /** 列头与正文横向滚动双向同步（放大量超过屏宽时两行仍严格对齐） */
    private void syncScroll() {
        if (listScroll == null || headerScroll == null) return;
        headerScroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        int x = headerScroll.getScrollX();
                        if (listScroll.getScrollX() != x) listScroll.scrollTo(x, 0);
                    }
                });
        listScroll.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        int x = listScroll.getScrollX();
                        if (headerScroll.getScrollX() != x) headerScroll.scrollTo(x, 0);
                    }
                });
    }

    /**
     * 把点击横坐标换算成「第几个字节」：偏移区/空格归到首字节，文本列也能反查。
     * list 的触摸坐标不受外层横向滚动影响（滚动发生在外层，list 自己不动）。
     */
    private int columnAt(float x, int row) {
        if (x < 0) return cursorCol;
        float cw = charW200 * rowPx / 200f;
        if (cw <= 0) return 0;
        int ci = (int) Math.floor((x - dp(8)) / cw);
        int col = HexCore.columnAt(ci, codeBase, ROW);
        if (data != null) {
            int max = Math.min(ROW - 1, data.length - 1 - row * ROW);
            if (max >= 0 && col > max) col = max;
        }
        return col;
    }

    /** 选中某字节（行内点击/长按/键盘联动都走这里） */
    private void selectByte(int off, int row) {
        if (data == null) return;
        if (off < 0) off = 0;
        if (off >= data.length) off = Math.max(0, data.length - 1);
        if (row >= 0) selectedRow = row;
        else selectedRow = off / ROW;
        cursorCol = off - selectedRow * ROW;
        if (cursorCol < 0) cursorCol = 0;
        editOff = off;
        nibble = "";
        if (panelOpen) refreshPanel();
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        if (data == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(FileOps.formatSize(fileSize));
        sb.append(" · 偏移 0x").append(String.format(Locale.US, "%08X", selectedRow * ROW + cursorCol));
        sb.append(" · ").append(BASE_NAME[idxOfBase(codeBase)]);
        if (truncated) sb.append(" · 仅前 4MB");
        if (dirty) sb.append(" · ✎ 未保存");
        sub.setText(sb.toString());
    }

    /** 保存按钮的启用状态：只有真的改过才亮 */
    private void setDirty(boolean d) {
        dirty = d;
        if (saveBtn != null) {
            saveBtn.setEnabled(d);
            saveBtn.setAlpha(d ? 1f : 0.3f);
        }
        updateStatus();
    }

    // ============================================================ 行文本 / 着色

    /** 列头：偏移区 + 每字节列号 + 文本列（排版在 HexCore.headerLine，桌面可单测） */
    private String headerLine() {
        // 进制写在工具条按钮上，列头只保留与行严格等宽的字符，避免加宽后错位
        return HexCore.headerLine(codeBase, ROW);
    }

    /** 一行明文（排版在 HexCore.rowLine，保证与列头同宽） */
    private String rowText(int row) {
        int base = row * ROW;
        String text = null;
        if (data != null) {
            int len = Math.min(ROW, data.length - base);
            if (len > 0) text = decodeRow(base, len);
        }
        return HexCore.rowLine(data, row, codeBase, ROW, text);
    }

    private CharSequence rowSpan(int row) {
        String s = rowText(row);
        SpannableString sp = new SpannableString(s);
        final int txt = ThemeManager.text(this);
        final int subc = ThemeManager.textSub(this);
        final int accent = ThemeManager.accent(this);

        int offEnd = Math.min(8, s.length());
        sp.setSpan(new ForegroundColorSpan(subc), 0, offEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        int w = HexCore.codeWidth(codeBase);
        int textStart = HexCore.textColumn(codeBase, ROW);
        int base = row * ROW;
        if (data != null) {
            for (int c = 0; c < ROW; c++) {
                int idx = base + c;
                if (idx >= data.length) break;
                if ((data[idx] & 0xFF) == 0) {
                    int st = HexCore.byteStart(c, codeBase);
                    if (st + w <= s.length())
                        sp.setSpan(new ForegroundColorSpan(ThemeManager.textSub(this)),
                                st, st + w, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // 文本列：可打印字符用正文色，其余灰度
            int len = Math.min(ROW, data.length - base);
            for (int c = 0; c < len; c++) {
                int v = data[base + c] & 0xFF;
                int pos = textStart + c;
                if (pos >= s.length()) break;
                if (v < 0x20 || v >= 0x7F) {
                    sp.setSpan(new ForegroundColorSpan(ThemeManager.textSub(this)),
                            pos, pos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        // 搜索命中（普通黄 / 当前项更亮）
        if (matches != null && patLen > 0) {
            for (int k = 0; k < matches.length; k++) {
                int mo = matches[k];
                boolean cur = (k == curMatch);
                for (int bdx = 0; bdx < patLen; bdx++) {
                    int bo = mo + bdx;
                    if (bo / ROW != row) continue;
                    int col = bo % ROW;
                    int st = HexCore.byteStart(col, codeBase);
                    if (st + w > s.length()) continue;
                    sp.setSpan(new BackgroundColorSpan(cur ? 0xFFFFEB3B : 0xC8E6C9),
                            st, st + w, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        // 光标格：当前行 + 当前列（点键盘前看得见要改哪一字节）
        if (row == selectedRow) {
            int st = HexCore.byteStart(Math.min(cursorCol, ROW - 1), codeBase);
            if (st + w <= s.length()) {
                sp.setSpan(new BackgroundColorSpan(accent), st, st + w,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new ForegroundColorSpan(ThemeManager.onAccent(this)), st, st + w,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sp;
    }

    private String decodeRow(int base, int len) {
        byte[] slice = new byte[len];
        System.arraycopy(data, base, slice, 0, len);
        if ("ASCII".equals(charsetName)) {
            StringBuilder sb = new StringBuilder(len);
            for (byte b : slice) sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            return sb.toString();
        }
        try {
            String s = new String(slice, charsetName);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(Character.isISOControl(c) ? '.' : c);
            }
            return sb.toString();
        } catch (Exception e) {
            return new String(slice, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .replaceAll("[^\\x20-\\x7E]", ".");
        }
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return data == null ? 0 : (data.length + ROW - 1) / ROW; }

        public Object getItem(int i) { return null; }

        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = (convert instanceof TextView) ? (TextView) convert
                    : new TextView(HexViewerActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowPx);
            tv.setPadding(dp(8), dp(3), dp(8), dp(3));
            tv.setSingleLine(true);
            tv.setText(rowSpan(i));
            tv.setBackgroundColor(i == selectedRow
                    ? ThemeManager.selected(HexViewerActivity.this) : 0x00000000);
            return tv;
        }
    };

    // ============================================================ 底部十六进制键盘

    /** ⌨ 开关：打开时定位到「当前选中的那个字节」，不是行首也不是行尾 */
    private void togglePanel() {
        if (panelOpen) closePanel();
        else openPanelAt(editOff);
    }

    private void openPanelAt(int off) {
        if (data == null) { toast("尚未加载完成"); return; }
        panelOpen = true;
        editOff = Math.max(0, Math.min(off, Math.max(0, data.length - 1)));
        nibble = "";
        panel.setVisibility(View.VISIBLE);
        syncCursor();
        refreshPanel();
        ensureVisible();
        adapter.notifyDataSetChanged();
    }

    private void closePanel() {
        panelOpen = false;
        nibble = "";
        panel.setVisibility(View.GONE);
    }

    /** 逐字节移动光标（◀/▶）：不再按 8 字节对齐，点到哪就是哪 */
    private void moveEdit(int delta) {
        if (data == null) return;
        int next = editOff + delta;
        if (next < 0) next = 0;
        if (next >= data.length) next = Math.max(0, data.length - 1);
        editOff = next;
        nibble = "";
        syncCursor();
        refreshPanel();
        ensureVisible();
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** 生成十六进制键盘：0-9 + A-F + 退格 + 收起（不弹系统键盘，只有这 18 个键） */
    private void buildKeys() {
        if (keysBox == null) return;
        keysBox.removeAllViews();
        String[] rows = {"012345", "6789AB", "CDEF⌫✕"};
        for (String row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            keysBox.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < row.length(); i++) {
                final char c = row.charAt(i);
                TextView k = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                k.setLayoutParams(lp);
                k.setGravity(android.view.Gravity.CENTER);
                k.setText(String.valueOf(c));
                k.setTypeface(Typeface.MONOSPACE);
                k.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f);
                boolean fn = (c == '⌫' || c == '✕');
                k.setTextColor(fn ? ThemeManager.textSub(this) : ThemeManager.accent(this));
                k.setBackground(keyBg());
                k.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (c == '⌫') backspace();
                        else if (c == '✕') closePanel();
                        else typeHex(c);
                    }
                });
                line.addView(k);
            }
        }
    }

    private android.graphics.drawable.Drawable keyBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(ThemeManager.surface(this));
        g.setCornerRadius(dp(8));
        g.setStroke(Math.max(1, dp(0.6f)), ThemeManager.divider(this));
        return g;
    }

    /**
     * 按下一个十六进制键：第 1 位只进缓冲（显示 "C_" 等第 2 位），第 2 位立刻写入该字节并右移一格。
     * 这样「选中哪个字节就写哪个字节」，不存在从行尾追加整行的行为。
     */
    private void typeHex(char c) {
        if (data == null || data.length == 0) return;
        if (editOff < 0 || editOff >= data.length) return;
        if (nibble.length() == 0) {
            nibble = String.valueOf(c);
            refreshPanel();
            return;
        }
        int v = (HexCore.hexVal(nibble.charAt(0)) << 4) | HexCore.hexVal(c);
        nibble = "";
        applyBytes(editOff, new byte[]{(byte) v}, true);
        if (editOff < data.length - 1) editOff++;
        syncCursor();
        refreshPanel();
        ensureVisible();
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** 退格：先退半个字节，没有半字节就退回上一字节 */
    private void backspace() {
        if (data == null) return;
        if (!nibble.isEmpty()) {
            nibble = "";
        } else if (editOff > 0) {
            editOff--;
        }
        syncCursor();
        refreshPanel();
        ensureVisible();
        adapter.notifyDataSetChanged();
    }

    /** 面板/光标同步：光标行 = 当前字节所在行，蓝底格 = 当前字节 */
    private void syncCursor() {
        if (data == null) return;
        if (editOff < 0) editOff = 0;
        if (editOff >= data.length) editOff = Math.max(0, data.length - 1);
        selectedRow = editOff / ROW;
        cursorCol = editOff % ROW;
    }

    /** 刷新面板文字：偏移 / 当前值 / 原值 / 半字节缓冲 */
    private void refreshPanel() {
        if (data == null) return;
        int off = Math.max(0, Math.min(editOff, Math.max(0, data.length - 1)));
        int v = off < data.length ? (data[off] & 0xFF) : 0;
        panelOffTv.setText(String.format(Locale.US, "%08X", off));
        StringBuilder sb = new StringBuilder(24);
        sb.append(String.format(Locale.US, "%02X", v));
        if (orig != null && off < orig.length && (orig[off] & 0xFF) != v) {
            sb.append(String.format(Locale.US, " (原 %02X)", orig[off] & 0xFF));
        }
        sb.append(nibble.isEmpty() ? "  → 按 2 位十六进制" : "  → " + nibble + "_");
        panelCurTv.setText(sb.toString());
    }

    /** 光标滚出可视区时把它带回屏幕中部 */
    private void ensureVisible() {
        if (list == null || data == null) return;
        int first = list.getFirstVisiblePosition();
        int last = list.getLastVisiblePosition();
        if (selectedRow < first || selectedRow > last) {
            list.setSelectionFromTop(selectedRow, dp(80));
        }
    }

    // ============================================================ 编辑 / 撤销 / 保存

    /** 改内存；返回是否真的发生变化（quiet=true 时不弹「内容未变化」，逐字节输入用） */
    private boolean applyBytes(int off, byte[] bytes) {
        return applyBytes(off, bytes, false);
    }

    private boolean applyBytes(int off, byte[] bytes, boolean quiet) {
        if (data == null || off < 0 || off + bytes.length > data.length) {
            if (!quiet) toast("超出已加载范围");
            return false;
        }
        byte[] old = new byte[bytes.length];
        System.arraycopy(data, off, old, 0, bytes.length);
        if (Arrays.equals(old, bytes)) return false;
        System.arraycopy(bytes, 0, data, off, bytes.length);
        undoStack.push(new HexEdit(off, old, bytes.clone()));
        while (undoStack.size() > MAX_UNDO) undoStack.removeLast();
        redoStack.clear();
        selectedRow = off / ROW;
        cursorCol = off % ROW;
        setDirty(true);
        adapter.notifyDataSetChanged();
        updateStatus();
        return true;
    }

    private void performUndo() {
        HexEdit e = undoStack.pollLast();
        if (e == null) { toast("无可撤销"); return; }
        System.arraycopy(e.oldB, 0, data, e.off, e.oldB.length);
        redoStack.push(e);
        editOff = e.off;
        syncCursor();
        setDirty(!Arrays.equals(orig, data));
        if (panelOpen) refreshPanel();
        adapter.notifyDataSetChanged();
        updateStatus();
        toast("已撤销");
    }

    private void performRedo() {
        HexEdit e = redoStack.pollLast();
        if (e == null) { toast("无可重做"); return; }
        System.arraycopy(e.newB, 0, data, e.off, e.newB.length);
        undoStack.push(e);
        editOff = e.off;
        syncCursor();
        setDirty(!Arrays.equals(orig, data));
        if (panelOpen) refreshPanel();
        adapter.notifyDataSetChanged();
        updateStatus();
        toast("已重做");
    }

    private void save() {
        if (data == null || !dirty) return;
        final android.app.ProgressDialog pd =
                android.app.ProgressDialog.show(this, "保存", "写入文件…", true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int patches = 0;
                try {
                    patches = writeDiff();
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String fe = err;
                final int fp = patches;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            toast("保存失败: " + fe);
                            return;
                        }
                        orig = data.clone();
                        setDirty(false);
                        resetSearch();
                        adapter.notifyDataSetChanged();
                        toast("已保存 " + fp + " 段改动");
                    }
                });
            }
        }, "hex-save").start();
    }

    private void saveThenFinish() {
        final android.app.ProgressDialog pd =
                android.app.ProgressDialog.show(this, "保存", "写入文件…", true, false);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try { writeDiff(); } catch (Throwable t) { err = t.getMessage(); }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fe != null) toast("保存失败: " + fe);
                        else finish();
                    }
                });
            }
        }, "hex-save-exit").start();
    }

    /** 只把「与原文件不同」的连续区间写回，避免整份重写 */
    private int writeDiff() throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            int i = 0, patches = 0;
            while (i < data.length) {
                if (data[i] == orig[i]) { i++; continue; }
                int s = i;
                while (i < data.length && data[i] != orig[i]) i++;
                raf.seek(s);
                raf.write(data, s, i - s);
                patches++;
            }
            return patches;
        } finally {
            raf.close();
        }
    }

    // ============================================================ 跳转 / 块

    private void askJump() {
        if (data == null) return;
        final EditText et = new EditText(this);
        et.setHint("偏移：1024 或 0x400");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("跳转到偏移")
                .setView(et)
                .setPositiveButton("跳转", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        long off = parseOffset(et.getText().toString().trim(), data.length);
                        if (off < 0) {
                            toast("偏移无效（0 ~ " + (data.length - 1) + "）");
                            return;
                        }
                        gotoOffset((int) off);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long parseOffset(String s, long max) {
        if (s == null || s.isEmpty()) return -1;
        try {
            long off = s.startsWith("0x") || s.startsWith("0X")
                    ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
            return (off >= 0 && off < max) ? off : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void gotoOffset(int off) {
        if (data == null || data.length == 0) return;
        editOff = Math.max(0, Math.min(off, data.length - 1));
        syncCursor();
        list.setSelectionFromTop(selectedRow, dp(80));
        if (panelOpen) refreshPanel();
        nibble = "";
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    /** 块跳转：选块大小 + 块号（或上一块/下一块），按块对齐定位 */
    private void askBlock() {
        if (data == null) return;
        final float den = getResources().getDisplayMetrics().density;
        final int[] sizes = {16, 64, 256, 512, 1024, 4096};
        final String[] sizeNames = {"16 B", "64 B", "256 B", "512 B", "1 KB", "4 KB"};
        final int[] size = {256};
        final int[] block = {selectedRow * ROW / 256};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * den);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView info = new TextView(this);
        info.setTextColor(ThemeManager.textSub(this));
        info.setTextSize(12);
        box.addView(info);

        TextView sizeTv = new TextView(this);
        sizeTv.setTextColor(ThemeManager.accent(this));
        sizeTv.setTextSize(15);
        sizeTv.setPadding(0, (int) (10 * den), 0, 0);
        box.addView(sizeTv);

        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, sizeNames));
        sp.setSelection(2);
        box.addView(sp);

        final EditText numEt = new EditText(this);
        numEt.setHint("块号（从 0 开始）");
        numEt.setText(String.valueOf(Math.max(0, block[0])));
        numEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(numEt);

        final Runnable refreshInfo = new Runnable() {
            public void run() {
                int sz = size[0];
                int total = Math.max(1, (data.length + sz - 1) / sz);
                int cur = Math.max(0, Math.min(selectedRow * ROW / sz, total - 1));
                info.setText("文件 " + FileOps.formatSize(data.length)
                        + " · 当前第 " + cur + " 块 / 共 " + total + " 块");
                sizeTv.setText("块大小: " + sz + " 字节");
            }
        };
        refreshInfo.run();
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                size[0] = sizes[pos];
                numEt.setText(String.valueOf(selectedRow * ROW / sizes[pos]));
                refreshInfo.run();
            }

            public void onNothingSelected(AdapterView<?> a) {}
        });

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView prev = new TextView(this);
        prev.setText("◀ 上一块");
        prev.setTextSize(14);
        prev.setTextColor(ThemeManager.accent(this));
        prev.setPadding(0, (int) (14 * den), 0, (int) (6 * den));
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = selectedRow * ROW / size[0];
                numEt.setText(String.valueOf(Math.max(0, cur - 1)));
            }
        });
        TextView next = new TextView(this);
        next.setText("下一块 ▶");
        next.setTextSize(14);
        next.setTextColor(ThemeManager.accent(this));
        next.setPadding((int) (24 * den), (int) (14 * den), 0, (int) (6 * den));
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = selectedRow * ROW / size[0];
                numEt.setText(String.valueOf(cur + 1));
            }
        });
        btns.addView(prev);
        btns.addView(next);
        box.addView(btns);

        new AlertDialog.Builder(this)
                .setTitle("跳转到块")
                .setView(box)
                .setPositiveButton("跳转", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int sz = size[0];
                        int n;
                        try {
                            n = Integer.parseInt(numEt.getText().toString().trim());
                        } catch (Exception e) {
                            toast("块号无效");
                            return;
                        }
                        long off = (long) n * sz;
                        if (off < 0 || off >= data.length) {
                            toast("块号超出范围（共 " + ((data.length + sz - 1) / sz) + " 块）");
                            return;
                        }
                        gotoOffset((int) off);
                        toast("第 " + n + " 块 @ 0x" + Long.toHexString(off).toUpperCase(Locale.US));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================ 文件解析

    private void showFileInfo() {
        if (data == null) { toast("尚未加载完成"); return; }
        final android.app.ProgressDialog pd =
                android.app.ProgressDialog.show(this, "文件解析", "读取文件头…", true, false);
        new Thread(new Runnable() {
            public void run() {
                String text;
                String magic = FileInspect.magicName(data);
                StringBuilder sb = new StringBuilder(FileInspect.describe(
                        data, file.getName(), fileSize));
                if ("ZIP".equals(magic)) {
                    sb.append('\n').append(FileInspect.signatureInfo(file));
                } else if ("JKS".equals(magic) || "BKS".equals(magic) || "DER".equals(magic)) {
                    sb.append("\n提示：签名文件可用「长按 → 签名信息」输入密码后解析别名与证书。\n");
                }
                text = sb.toString();
                final String ft = text;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        ScrollView sv = new ScrollView(HexViewerActivity.this);
                        TextView tv = new TextView(HexViewerActivity.this);
                        int pad = (int) (16 * getResources().getDisplayMetrics().density);
                        sv.setPadding(pad, pad / 2, pad, 0);
                        tv.setTypeface(Typeface.MONOSPACE);
                        tv.setTextSize(12);
                        tv.setTextIsSelectable(true);
                        tv.setTextColor(ThemeManager.text(HexViewerActivity.this));
                        tv.setText(ft);
                        sv.addView(tv);
                        FileInspect.Elf elf = FileInspect.parseElf(data);
                        AlertDialog.Builder b = new AlertDialog.Builder(HexViewerActivity.this)
                                .setTitle("文件解析")
                                .setView(sv)
                                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) { copy(ft); }
                                })
                                .setNegativeButton("关闭", null);
                        if (elf != null && !elf.sections.isEmpty()) {
                            b.setNeutralButton("跳到节…", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) { askSection(elf); }
                            });
                        }
                        b.show();
                    }
                });
            }
        }, "hex-info").start();
    }

    /** ELF 节表 → 点哪节跳到哪（偏移超出已读范围时给出提示） */
    private void askSection(final FileInspect.Elf elf) {
        final java.util.List<FileInspect.Section> secs = new java.util.ArrayList<FileInspect.Section>();
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        for (FileInspect.Section s : elf.sections) {
            if (s.type == 0 && (s.name == null || s.name.length() == 0)) continue;
            if (s.size <= 0) continue;
            secs.add(s);
            labels.add((s.name == null || s.name.length() == 0 ? "(匿名节)" : s.name)
                    + "   " + s.typeName() + "   off=0x" + Long.toHexString(s.offset)
                    + "   " + FileOps.formatSize(s.size));
        }
        if (labels.isEmpty()) { toast("没有可跳转的节"); return; }
        new AlertDialog.Builder(this)
                .setTitle("跳到节")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        FileInspect.Section s = secs.get(which);
                        if (s.offset >= data.length) {
                            toast("该节偏移超出已加载范围");
                            return;
                        }
                        gotoOffset((int) s.offset);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ============================================================ 搜索 / 进制 / 字符集 / 复制

    private void askSearch() {
        if (data == null) return;
        final View v = getLayoutInflater().inflate(R.layout.dialog_hex_search, null);
        final Spinner sp = (Spinner) v.findViewById(R.id.dlg_type);
        final RadioGroup rg = (RadioGroup) v.findViewById(R.id.dlg_endian);
        final EditText input = (EditText) v.findViewById(R.id.dlg_input);
        final TextView status = (TextView) v.findViewById(R.id.dlg_status);

        sp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View view, int pos, long id) {
                rg.setVisibility(HexCore.isNumeric(TYPE_VALS[pos]) ? View.VISIBLE : View.GONE);
                input.setHint(hintFor(TYPE_VALS[pos]));
            }

            public void onNothingSelected(AdapterView<?> a) {}
        });

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("类型搜索")
                .setView(v)
                .setPositiveButton("搜索", null)
                .setNeutralButton("下一项", null)
                .setNegativeButton("上一项", null)
                .show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                int pos = sp.getSelectedItemPosition();
                boolean be = ((RadioButton) v.findViewById(R.id.rb_be)).isChecked();
                byte[] pat;
                try {
                    pat = HexCore.patternFor(TYPE_VALS[pos], input.getText().toString(), be);
                } catch (IllegalArgumentException e) {
                    status.setText(e.getMessage());
                    return;
                }
                matches = HexCore.findAll(data, pat);
                patLen = pat.length;
                curMatch = 0;
                if (matches.length == 0) {
                    status.setText("未找到匹配");
                    adapter.notifyDataSetChanged();
                    return;
                }
                status.setText("");
                gotoMatch(0);
            }
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { gotoMatch(curMatch + 1); }
        });
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { gotoMatch(curMatch - 1); }
        });
    }

    private void gotoMatch(int idx) {
        if (matches == null || matches.length == 0) return;
        curMatch = (idx % matches.length + matches.length) % matches.length;
        gotoOffset(matches[curMatch]);
    }

    private void resetSearch() {
        matches = null;
        patLen = 0;
        curMatch = 0;
    }

    private String hintFor(Type t) {
        switch (t) {
            case HEX: return "十六进制，如 DE AD BE EF";
            case TEXT: return "文本（UTF-8），如 hello";
            case FLOAT32:
            case FLOAT64: return "浮点数，如 3.14";
            default: return "整数，如 12345";
        }
    }

    private void cycleBase() {
        for (int k = 0; k < BASE_ORDER.length; k++) {
            if (BASE_ORDER[k] == codeBase) {
                codeBase = BASE_ORDER[(k + 1) % BASE_ORDER.length];
                break;
            }
        }
        baseBtn.setText("进制 " + BASE_NAME[idxOfBase(codeBase)]);
        measureTextSize();
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, headPx);
        header.setText(headerLine());
        adapter.notifyDataSetChanged();
    }

    private int idxOfBase(int base) {
        for (int k = 0; k < BASE_ORDER.length; k++) if (BASE_ORDER[k] == base) return k;
        return 0;
    }

    // ============================================================ 字号

    /** 「Aa」：字号调节（60%~250%，100% = 按屏宽自适应铺满一行） */
    private void askFontSize() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(14), dp(22), dp(2));

        TextView label = new TextView(this);
        label.setText("字号");
        label.setTextSize(15);
        label.setTextColor(ThemeManager.text(this));
        box.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));

        final TextView val = new TextView(this);
        val.setTextSize(18);
        val.setTextColor(ThemeManager.accent(this));
        val.setMinWidth(dp(64));
        val.setGravity(android.view.Gravity.CENTER);
        val.setText(percentText());

        final TextView preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setText("0F 1A 2B 3C 4D 5E 6F 70  81 92 A3 B4 C5 D6 E7 F8");
        preview.setSingleLine(true);
        preview.setTextColor(ThemeManager.text(this));
        preview.setPadding(0, dp(10), 0, dp(6));
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowPx);

        final View.OnClickListener minus = new View.OnClickListener() {
            public void onClick(View v) {
                applyFontScale(fontScale - 0.1f);
                val.setText(percentText());
                preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowPx);
            }
        };
        final View.OnClickListener plus = new View.OnClickListener() {
            public void onClick(View v) {
                applyFontScale(fontScale + 0.1f);
                val.setText(percentText());
                preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowPx);
            }
        };

        row.addView(chipKey("A−", minus, true));
        row.addView(val);
        row.addView(chipKey("A＋", plus, true));
        box.addView(row);

        TextView tip = new TextView(this);
        tip.setTextSize(11);
        tip.setTextColor(ThemeManager.textSub(this));
        tip.setText("100% = 按屏宽自适应铺满一行；放大后左右滑动查看整行，列头会跟着对齐。");
        box.addView(tip);

        box.addView(preview);

        new AlertDialog.Builder(this)
                .setTitle("显示设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { applyFontScale(1f); }
                })
                .show();
    }

    private String percentText() {
        return Math.round(fontScale * 100) + "%";
    }

    private TextView chipKey(String text, View.OnClickListener l, boolean wide) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(ThemeManager.accent(this));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(wide ? 18 : 10), dp(8), dp(wide ? 18 : 10), dp(8));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setOnClickListener(l);
        return t;
    }

    /** 应用字号：重算自适应基数 × 倍数 → 列头/行同步，并加宽列表交给横向滚动 */
    private void applyFontScale(float scale) {
        fontScale = Math.max(0.6f, Math.min(2.5f, Math.round(scale * 10f) / 10f));
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putFloat(KEY_FONT_SCALE, fontScale).apply();
        measureTextSize();
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, headPx);
        header.setText(headerLine());
        adapter.notifyDataSetChanged();
    }

    private void askCharset() {
        new AlertDialog.Builder(this)
                .setTitle("文本预览字符集")
                .setSingleChoiceItems(CHARSETS, indexOfCharset(), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        charsetName = CHARSETS[w];
                        charsetBtn.setText(charsetName);
                        d.dismiss();
                        adapter.notifyDataSetChanged();
                        toast("字符集：" + charsetName);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int indexOfCharset() {
        for (int i = 0; i < CHARSETS.length; i++) if (CHARSETS[i].equals(charsetName)) return i;
        return 0;
    }

    private void askCopy() {
        if (data == null) return;
        new AlertDialog.Builder(this)
                .setTitle("复制")
                .setItems(new String[]{"当前行 HEX", "当前行文本", "选中字节（光标处 1 字节）"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                int base = selectedRow * ROW;
                                if (w == 2) {
                                    int idx = base + cursorCol;
                                    if (idx < data.length) {
                                        copy(String.format(Locale.US, "%02X", data[idx] & 0xFF));
                                        toast("已复制 " + (data[idx] & 0xFF));
                                    }
                                    return;
                                }
                                int end = Math.min(base + ROW, data.length);
                                if (end <= base) { toast("此行没有数据"); return; }
                                if (w == 0) {
                                    copy(HexCore.bytesToHex(data, base, end - base));
                                    toast("已复制 HEX");
                                } else {
                                    copy(decodeRow(base, end - base));
                                    toast("已复制文本");
                                }
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { toast("剪贴板不可用"); return; }
        cm.setPrimaryClip(ClipData.newPlainText("hex", text));
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
