package com.hex91.file;

import com.hex91.file.R;
import com.hex91.file.core.*;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * v1.70.0 编码转换页。
 *
 * <p>核心：左选「源格式」、右选「目标格式」，输入框内容即时转换（不需要点按钮）。
 * 因为 {@link CodecCore} 内部统一走「格式 → 字节 → 格式」，所以 14 种格式任意两两互转，
 * 不需要为每个组合写一条转换路径。
 *
 * <p>支持预设：外部用 {@code from/to} 传入初始格式（工具箱卡片点进来时用）。
 */
public class CodecActivity extends BaseActivity {

    private Spinner fromSp, toSp;
    private EditText in;
    private TextView out, status;

    /** 程序内改 Spinner 时抑制回调，避免「切格式 → 回调 → 再切」的自激 */
    private boolean guard;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_codec);

        fromSp = (Spinner) findViewById(R.id.cd_from);
        toSp = (Spinner) findViewById(R.id.cd_to);
        in = (EditText) findViewById(R.id.cd_in);
        out = (TextView) findViewById(R.id.cd_out);
        status = (TextView) findViewById(R.id.cd_status);

        fromSp.setAdapter(ToolUi.adapter(this, CodecCore.NAMES));
        toSp.setAdapter(ToolUi.adapter(this, CodecCore.NAMES));

        int from = getIntent().getIntExtra(ToolboxActivity.EXTRA_FROM, CodecCore.F_TEXT);
        int to = getIntent().getIntExtra(ToolboxActivity.EXTRA_TO, CodecCore.F_HEX);
        if (from < 0 || from >= CodecCore.NAMES.length) from = CodecCore.F_TEXT;
        if (to < 0 || to >= CodecCore.NAMES.length) to = CodecCore.F_HEX;
        fromSp.setSelection(from);
        toSp.setSelection(to);
        ((TextView) findViewById(R.id.cd_sub)).setText(
                CodecCore.name(from) + "  →  " + CodecCore.name(to) + "（输入即转换）");

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                syncSub();
                convert();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        fromSp.setOnItemSelectedListener(l);
        toSp.setOnItemSelectedListener(l);

        in.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void afterTextChanged(Editable s) {
                convert();
            }
        });

        findViewById(R.id.cd_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.cd_swap).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                swap();
            }
        });
        findViewById(R.id.cd_paste).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                paste();
            }
        });
        findViewById(R.id.cd_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                in.setText("");
                out.setText("");
            }
        });
        findViewById(R.id.cd_use).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String r = out.getText().toString();
                if (r.length() == 0) {
                    ToolUi.toast(CodecActivity.this, "还没有结果");
                    return;
                }
                // 结果回填输入，并交换格式，方便「编码 → 再解码验证」
                swapFormats();
                in.setText(r);
                in.setSelection(r.length());
            }
        });
        findViewById(R.id.cd_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(CodecActivity.this, out.getText(), "结果");
            }
        });

        convert();
    }

    private int fromFmt() {
        return fromSp.getSelectedItemPosition();
    }

    private int toFmt() {
        return toSp.getSelectedItemPosition();
    }

    private void syncSub() {
        ((TextView) findViewById(R.id.cd_sub)).setText(
                CodecCore.name(fromFmt()) + "  →  " + CodecCore.name(toFmt()) + "（输入即转换）");
    }

    private void swapFormats() {
        guard = true;
        int f = fromFmt(), t = toFmt();
        fromSp.setSelection(t);
        toSp.setSelection(f);
        guard = false;
        syncSub();
    }

    /** 顶部「⇄ 互换」：格式对调，同时把当前结果挪到输入框（真正的来回验证） */
    private void swap() {
        String r = out.getText().toString();
        swapFormats();
        if (r.length() > 0) {
            in.setText(r);
            in.setSelection(r.length());
        } else {
            convert();
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
            ToolUi.toast(this, "剪贴板是空的");
            return;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        in.setText(t);
        in.setSelection(in.getText().length());
    }

    private void convert() {
        if (guard) return;
        String s = in.getText().toString();
        if (s.length() == 0) {
            out.setText("");
            status.setTextColor(ThemeManager.textSub(this));
            status.setText("支持 " + CodecCore.NAMES.length + " 种格式任意互转；粘进来就出结果");
            return;
        }
        try {
            String r = CodecCore.convert(s, fromFmt(), toFmt());
            // 超大结果不整段塞进 TextView（几十万字符会让测量/绘制明显卡顿），截断显示
            final int LIMIT = 200000;
            if (r.length() > LIMIT) {
                out.setText(r.substring(0, LIMIT) + "\n…（共 " + r.length() + " 字符，已截断显示前 " + LIMIT + " 字符）");
            } else {
                out.setText(r);
            }
            int bytes;
            try {
                bytes = CodecCore.toBytes(s, fromFmt()).length;
            } catch (RuntimeException e) {
                bytes = -1;
            }
            status.setTextColor(ThemeManager.textSub(this));
            status.setText("输入 " + s.length() + " 字符"
                    + (bytes >= 0 ? " / " + bytes + " 字节" : "")
                    + " → 输出 " + r.length() + " 字符");
        } catch (IllegalArgumentException e) {
            status.setTextColor(ThemeManager.err(this));
            status.setText("✗ " + e.getMessage());
        } catch (Throwable e) {
            status.setTextColor(ThemeManager.err(this));
            status.setText("✗ 转换失败：" + e);
        }
    }
}
