package com.hex91.file;

import android.app.Activity;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Locale;

import com.hex91.file.HexCore;
import com.hex91.file.HexCore.Type;

/**
 * HEX 编辑器（v1.31~1.32，借鉴 bined 思想增量增强）：
 *  - 类型搜索：HEX / 文本 / Int8~UInt64 / Float32~64，可选大小端；KMP 搜索 + 匹配高亮 + 上/下项。
 *  - 编辑写回：按「偏移 + 类型 + 值」写入（覆盖 / 插入两种模式），RandomAccessFile 写盘 + 内存同步。
 *  - 进制视图：HEX / BIN / OCT / DEC 切换；长按需切换文本预览字符集（ASCII/UTF-8/UTF-16LE/ISO-8859-1/Shift_JIS）。
 *  - 撤销/重做：每次编辑入栈，可回退。
 *  - 选区：长按设起点、点选扩展，编辑按钮弹出「复制为 HEX / 文本 / 在此写入」。
 * 限制：仅加载并支持编辑前 4MB（插入可超出，受内存限制）；写入需文件可写。
 */
public class HexViewerActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";
    private static final long LIMIT = 4L * 1024 * 1024; // 4MB

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

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
    private static final int[] BASE_ORDER = {HexCore.BASE_HEX, HexCore.BASE_BIN, HexCore.BASE_OCT, HexCore.BASE_DEC};
    private static final String[] BASE_NAME = {"HEX", "BIN", "OCT", "DEC"};

    private byte[] data;
    private boolean truncated = false;
    private long fileSize = 0;
    private File file;
    private ListView list;

    // 搜索/选中/编辑状态
    private int[] matches = null;
    private int patLen = 0;
    private int curMatch = 0;
    private int selectedRow = -1;
    private long selectedOffset = 0;

    private int codeBase = HexCore.BASE_HEX;
    private String charsetName = "ASCII";
    private boolean insertMode = false;
    private int selAnchor = -1; // 选区起点行（-1 表示无选区）

    private final ArrayDeque<HexCore.EditAction> undoStack = new ArrayDeque<>();
    private final ArrayDeque<HexCore.EditAction> redoStack = new ArrayDeque<>();

    private TextView searchStatus;
    private Button baseBtn, modeBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_hex);

        file = new File(getIntent().getStringExtra(EXTRA_FILE));
        fileSize = file.length();
        truncated = fileSize > LIMIT;

        TextView title = (TextView) findViewById(R.id.hex_title);
        title.setText(file.getName() + "  (" + FileOps.formatSize(fileSize)
                + (truncated ? "，仅显示前 4MB)" : ")"));

        list = (ListView) findViewById(R.id.hex_list);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                selectedRow = i;
                selectedOffset = (long) i * 16;
                if (selAnchor < 0) {
                    copy(rowText(i));
                    toast("已选中偏移 " + selectedOffset + "，可点「编辑」");
                }
                adapter.notifyDataSetChanged();
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                if (selAnchor < 0) {
                    selAnchor = i;
                    selectedRow = i;
                    toast("选区起点 行" + i + "，再点其他行扩展；「编辑」可复制/写入");
                } else {
                    selAnchor = -1;
                    toast("已取消选区");
                }
                adapter.notifyDataSetChanged();
                return true;
            }
        });

        findViewById(R.id.hex_jump).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askJump(); }
        });
        findViewById(R.id.hex_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askSearch(); }
        });
        findViewById(R.id.hex_edit).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onEditButton(); }
        });
        findViewById(R.id.hex_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        baseBtn = (Button) findViewById(R.id.hex_base);
        baseBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleBase(); }
        });
        baseBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { askCharset(); return true; }
        });
        findViewById(R.id.hex_undo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { performUndo(); }
        });
        findViewById(R.id.hex_redo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { performRedo(); }
        });
        modeBtn = (Button) findViewById(R.id.hex_mode);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                insertMode = !insertMode;
                modeBtn.setText(insertMode ? "插入" : "覆盖");
                toast(insertMode ? "已切换为插入模式" : "已切换为覆盖模式");
            }
        });

        loadAsync(file);
    }

    private void cycleBase() {
        for (int k = 0; k < BASE_ORDER.length; k++) {
            if (BASE_ORDER[k] == codeBase) {
                codeBase = BASE_ORDER[(k + 1) % BASE_ORDER.length];
                break;
            }
        }
        baseBtn.setText("进制 " + BASE_NAME[idxOfBase(codeBase)]);
        adapter.notifyDataSetChanged();
    }

    private int idxOfBase(int base) {
        for (int k = 0; k < BASE_ORDER.length; k++) if (BASE_ORDER[k] == base) return k;
        return 0;
    }

    private void askCharset() {
        new AlertDialog.Builder(this)
                .setTitle("文本预览字符集")
                .setSingleChoiceItems(CHARSETS, indexOfCharset(), new DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        charsetName = CHARSETS[w];
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

    private void loadAsync(final File f) {
        new Thread(new Runnable() {
            public void run() {
                FileInputStream in = null;
                try {
                    in = new FileInputStream(f);
                    int n = (int) Math.min(LIMIT, fileSize);
                    data = new byte[n];
                    int off = 0, r;
                    while (off < n && (r = in.read(data, off, n - off)) > 0) off += r;
                } catch (Exception e) {
                    // v1.44.0：root 区回退（su cat）
                    byte[] rb = RootIO.readBytes(f);
                    if (rb != null && rb.length > LIMIT) {
                        byte[] cut = new byte[(int) LIMIT];
                        System.arraycopy(rb, 0, cut, 0, cut.length);
                        rb = cut;
                    }
                    data = rb;
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignored) {}
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (data == null) {
                            toast("无法读取文件");
                            finish();
                            return;
                        }
                        list.setAdapter(adapter);
                    }
                });
            }
        }, "hex-load").start();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return data == null ? 0 : (data.length + 15) / 16; }
        public Object getItem(int i) { return null; }
        public long getItemId(int i) { return i; }
        public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = (convert instanceof TextView) ? (TextView) convert
                    : new TextView(HexViewerActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(11);
            tv.setTextColor(ThemeManager.text(HexViewerActivity.this));
            tv.setPadding(dp(8), dp(2), dp(8), dp(2));

            int lo = Math.min(selAnchor, selectedRow);
            int hi = Math.max(selAnchor, selectedRow);
            boolean inSel = selAnchor >= 0 && i >= lo && i <= hi;
            tv.setBackgroundColor(inSel ? ThemeManager.selected(HexViewerActivity.this) : 0x00000000);

            String s = rowText(i);
            SpannableString sp = new SpannableString(s);
            if (matches != null && patLen > 0) {
                for (int k = 0; k < matches.length; k++) {
                    int mo = matches[k];
                    boolean cur = (k == curMatch);
                    for (int bdx = 0; bdx < patLen; bdx++) {
                        int bo = mo + bdx;
                        if (bo / 16 == i) {
                            int col = bo % 16;
                            int start = HexCore.byteStart(col, codeBase);
                            sp.setSpan(new BackgroundColorSpan(cur ? 0xFFFFEB3B : 0xFFC8E6C9),
                                    start, start + HexCore.codeWidth(codeBase), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    }
                }
            }
            tv.setText(sp);
            return tv;
        }
    };

    /** 生成一行: "0000F0A0  <codes...>  <text>" */
    private String rowText(int row) {
        int base = row * 16;
        StringBuilder sb = new StringBuilder(120);
        for (int s = 28; s >= 0; s -= 4) sb.append(HEX[(base >>> s) & 0xF]);
        sb.append("  ");
        int w = HexCore.codeWidth(codeBase);
        for (int c = 0; c < 16; c++) {
            if (c == 8) sb.append(' ');
            int idx = base + c;
            if (idx < data.length) {
                sb.append(HexCore.formatCode(data[idx] & 0xFF, codeBase));
            } else {
                for (int z = 0; z < w; z++) sb.append(' ');
            }
            sb.append(' ');
        }
        sb.append(' ');
        int len = Math.min(16, data.length - base);
        if (len > 0) sb.append(decodeRow(data, base, len));
        return sb.toString();
    }

    private String decodeRow(byte[] d, int base, int len) {
        byte[] slice = new byte[len];
        System.arraycopy(d, base, slice, 0, len);
        if (charsetName.equals("ASCII")) {
            StringBuilder sb = new StringBuilder(len);
            for (byte b : slice) sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            return sb.toString();
        }
        try {
            String s = new String(slice, charsetName);
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(Character.isISOControl(c) || c == '�' ? '.' : c);
            }
            return sb.toString();
        } catch (Exception e) {
            return new String(slice, java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7E]", ".");
        }
    }

    // ============ 跳转 ============
    private void askJump() {
        if (data == null) return;
        final EditText et = new EditText(this);
        et.setHint("偏移，如 1024 或 0x400");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("跳转到偏移")
                .setView(et)
                .setPositiveButton("跳转", new AlertDialog.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        long off = parseOffset(et.getText().toString().trim(), data.length);
                        if (off < 0) { toast("偏移无效（范围 0 ~ " + (data.length - 1) + "）"); return; }
                        list.setSelectionFromTop((int) (off / 16), 0);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long parseOffset(String s, long max) {
        if (s == null || s.isEmpty()) return -1;
        try {
            long off = s.startsWith("0x") ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
            return (off >= 0 && off < max) ? off : -1;
        } catch (Exception e) { return -1; }
    }

    // ============ 搜索 ============
    private void askSearch() {
        if (data == null) return;
        final View v = getLayoutInflater().inflate(R.layout.dialog_hex_search, null);
        final Spinner sp = (Spinner) v.findViewById(R.id.dlg_type);
        final RadioGroup rg = (RadioGroup) v.findViewById(R.id.dlg_endian);
        final EditText input = (EditText) v.findViewById(R.id.dlg_input);
        searchStatus = (TextView) v.findViewById(R.id.dlg_status);

        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
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
                .setOnCancelListener(new AlertDialog.OnCancelListener() {
                    public void onCancel(android.content.DialogInterface d) { searchStatus = null; }
                })
                .show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                int pos = sp.getSelectedItemPosition();
                boolean be = ((RadioButton) v.findViewById(R.id.rb_be)).isChecked();
                byte[] pat;
                try {
                    pat = HexCore.patternFor(TYPE_VALS[pos], input.getText().toString(), be);
                } catch (IllegalArgumentException e) {
                    if (searchStatus != null) searchStatus.setText(e.getMessage());
                    return;
                }
                matches = HexCore.findAll(data, pat);
                patLen = pat.length;
                if (matches.length == 0) {
                    curMatch = 0;
                    if (searchStatus != null) searchStatus.setText("未找到匹配");
                    adapter.notifyDataSetChanged();
                    return;
                }
                curMatch = 0;
                gotoMatch(0);
                if (searchStatus != null) searchStatus.setText("");
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
        int row = matches[curMatch] / 16;
        list.setSelectionFromTop(row, 0);
        adapter.notifyDataSetChanged();
        if (searchStatus != null) searchStatus.setText("匹配 " + (curMatch + 1) + " / " + matches.length);
    }

    private String hintFor(Type t) {
        switch (t) {
            case HEX: return "十六进制，如 DE AD BE EF";
            case TEXT: return "文本（UTF-8），如 hello";
            case FLOAT32: case FLOAT64: return "浮点数，如 3.14";
            default: return "整数，如 12345";
        }
    }

    // ============ 编辑按钮（含选区菜单） ============
    private void onEditButton() {
        if (data == null) return;
        if (selAnchor >= 0 && selAnchor != selectedRow) {
            final int from = Math.min(selAnchor, selectedRow) * 16;
            final int to = (Math.max(selAnchor, selectedRow) + 1) * 16;
            new AlertDialog.Builder(this)
                    .setTitle("选区操作（行 " + Math.min(selAnchor, selectedRow) + "~" + Math.max(selAnchor, selectedRow) + "）")
                    .setItems(new String[]{"复制为 HEX", "复制为文本", "在此偏移写入"}, new DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            byte[] range = HexCore.copyRange(data, from, to);
                            if (w == 0) {
                                copy(HexCore.bytesToHex(range, 0, range.length));
                                toast("已复制 HEX");
                            } else if (w == 1) {
                                copy(decodeRow(range, 0, range.length));
                                toast("已复制文本");
                            } else {
                                askEdit(from, null);
                            }
                            selAnchor = -1;
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { selAnchor = -1; adapter.notifyDataSetChanged(); }
                    })
                    .show();
        } else {
            selAnchor = -1;
            askEdit(selectedOffset, selectedRow >= 0 ? selectedRow : null);
        }
    }

    private void askEdit(long defaultOffset, Integer prefillRow) {
        if (data == null) return;
        final View v = getLayoutInflater().inflate(R.layout.dialog_hex_edit, null);
        final EditText offEt = (EditText) v.findViewById(R.id.dlg_offset);
        final Spinner sp = (Spinner) v.findViewById(R.id.dlg_type);
        final RadioGroup rg = (RadioGroup) v.findViewById(R.id.dlg_endian);
        final EditText valEt = (EditText) v.findViewById(R.id.dlg_value);
        final TextView status = (TextView) v.findViewById(R.id.dlg_status);

        offEt.setText(prefillRow != null ? ("0x" + Integer.toHexString(prefillRow * 16).toUpperCase(Locale.US)) : String.valueOf(defaultOffset));
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View view, int pos, long id) {
                rg.setVisibility(HexCore.isNumeric(TYPE_VALS[pos]) ? View.VISIBLE : View.GONE);
                valEt.setHint(hintFor(TYPE_VALS[pos]));
            }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        if (prefillRow != null) {
            sp.setSelection(0);
            valEt.setText(HexCore.bytesToHex(data, prefillRow * 16, Math.min(16, data.length - prefillRow * 16)));
        }

        new AlertDialog.Builder(this)
                .setTitle("编辑写回（" + (insertMode ? "插入" : "覆盖") + "）")
                .setView(v)
                .setPositiveButton("写入", new AlertDialog.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        long off = parseOffset(offEt.getText().toString().trim(), data.length + 1);
                        if (off < 0) { status.setText("偏移无效"); return; }
                        int pos = sp.getSelectedItemPosition();
                        boolean be = ((RadioButton) v.findViewById(R.id.rb_be)).isChecked();
                        byte[] nb;
                        try {
                            nb = HexCore.patternFor(TYPE_VALS[pos], valEt.getText().toString(), be);
                        } catch (IllegalArgumentException e) {
                            status.setText(e.getMessage());
                            return;
                        }
                        if (insertMode) {
                            if (off > data.length) { status.setText("插入偏移越界"); return; }
                            applyEdit(new HexCore.EditAction(true, (int) off, null, nb));
                        } else {
                            if (off + nb.length > data.length) { status.setText("写入越界（仅支持前 4MB）"); return; }
                            byte[] old = HexCore.copyRange(data, (int) off, (int) off + nb.length);
                            applyEdit(new HexCore.EditAction(false, (int) off, old, nb));
                        }
                        d.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 写入并维护撤销栈。 */
    private void applyEdit(HexCore.EditAction a) {
        try {
            if (a.insert) {
                fileInsert(a.offset, a.newData);
                data = HexCore.spliceInsert(data, a.offset, a.newData);
            } else {
                fileOverwrite(a.offset, a.newData);
                HexCore.patch(data, a.offset, a.newData);
            }
            undoStack.push(a);
            redoStack.clear();
            selAnchor = -1;
            matches = null; patLen = 0; curMatch = 0;
            adapter.notifyDataSetChanged();
            toast("已写入 " + a.newData.length + " 字节 @ " + a.offset + (a.insert ? "（插入）" : ""));
        } catch (Exception e) {
            toast("写入失败：" + e.getMessage());
        }
    }

    private void fileOverwrite(int off, byte[] bytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(off);
            raf.write(bytes);
        }
    }

    private void fileInsert(int off, byte[] bytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            int tailLen = (int) (raf.length() - off);
            byte[] tail = new byte[Math.max(0, tailLen)];
            if (tailLen > 0) { raf.seek(off); raf.readFully(tail); }
            raf.seek(off);
            raf.write(bytes);
            raf.write(tail);
        }
    }

    private void performUndo() {
        HexCore.EditAction a = undoStack.pollLast();
        if (a == null) { toast("无可撤销"); return; }
        try {
            if (a.insert) {
                fileDelete(a.offset, a.newData.length);
                data = HexCore.removeRange(data, a.offset, a.newData.length);
            } else {
                fileOverwrite(a.offset, a.oldData);
                HexCore.patch(data, a.offset, a.oldData);
            }
            redoStack.push(a);
            selAnchor = -1; matches = null; patLen = 0; curMatch = 0;
            adapter.notifyDataSetChanged();
            toast("已撤销");
        } catch (Exception e) { toast("撤销失败：" + e.getMessage()); }
    }

    private void performRedo() {
        HexCore.EditAction a = redoStack.pollLast();
        if (a == null) { toast("无可重做"); return; }
        try {
            if (a.insert) {
                fileInsert(a.offset, a.newData);
                data = HexCore.spliceInsert(data, a.offset, a.newData);
            } else {
                fileOverwrite(a.offset, a.newData);
                HexCore.patch(data, a.offset, a.newData);
            }
            undoStack.push(a);
            selAnchor = -1; matches = null; patLen = 0; curMatch = 0;
            adapter.notifyDataSetChanged();
            toast("已重做");
        } catch (Exception e) { toast("重做失败：" + e.getMessage()); }
    }

    private void fileDelete(int off, int len) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long tailStart = off + len;
            int tailLen = (int) (raf.length() - tailStart);
            byte[] tail = new byte[Math.max(0, tailLen)];
            if (tailLen > 0) { raf.seek(tailStart); raf.readFully(tail); }
            raf.seek(off);
            raf.write(tail);
            raf.setLength(off + tail.length);
        }
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
