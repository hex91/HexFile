package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.RadixCore;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * v1.70.0 进制转换页：输入即出 2/8/10/16/36 五种结果，点任意一行可复制。
 *
 * <p>用 {@link java.math.BigInteger} 解析，所以多少位都行（1000 位十进制也不溢出）；
 * 额外给出二进制位宽、占用字节数与 HEX 字节视图，写协议/看内存时直接照抄。
 */
public class RadixActivity extends BaseActivity {

    /**
     * 进制的显示名。v2.64.0：由中文常量数组改成资源 id 数组 —— 名字要随语言变，
     * 且同一份名字既给 Spinner 又给结果行标签与复制提示用，故每次按 ids 取一遍。
     */
    private static final int[] BASE_LABEL_IDS = {
            R.string.rd_base_bin, R.string.rd_base_oct, R.string.rd_base_dec,
            R.string.rd_base_hex, R.string.rd_base_36};

    private EditText in;
    private Spinner baseSp;
    private TextView status, info;
    private final TextView[] values = new TextView[RadixCore.BASES.length];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_radix);

        in = (EditText) findViewById(R.id.rd_in);
        baseSp = (Spinner) findViewById(R.id.rd_base);
        status = (TextView) findViewById(R.id.rd_status);
        info = (TextView) findViewById(R.id.rd_info);

        final String[] baseLabel = new String[BASE_LABEL_IDS.length];
        for (int i = 0; i < baseLabel.length; i++) baseLabel[i] = getString(BASE_LABEL_IDS[i]);
        baseSp.setAdapter(ToolUi.adapter(this, baseLabel));
        baseSp.setSelection(2); // 默认十进制

        LinearLayout box = (LinearLayout) findViewById(R.id.rd_box);
        for (int i = 0; i < RadixCore.BASES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = ToolUi.dp(this, 6);
            row.setLayoutParams(rp);
            row.setBackground(ToolUi.card(this, 10));
            row.setPadding(ToolUi.dp(this, 10), ToolUi.dp(this, 9), ToolUi.dp(this, 10), ToolUi.dp(this, 9));

            TextView label = new TextView(this);
            label.setText(baseLabel[i]);
            label.setTextSize(11f);
            label.setTextColor(ThemeManager.textSub(this));
            label.setWidth(ToolUi.dp(this, 92));
            row.addView(label);

            TextView val = new TextView(this);
            val.setTextSize(15f);
            val.setTypeface(android.graphics.Typeface.MONOSPACE);
            val.setTextColor(ThemeManager.text(this));
            val.setTextIsSelectable(true);
            val.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(val);
            values[i] = val;

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ToolUi.copy(RadixActivity.this, values[idx].getText(),
                            getString(R.string.rd_copy_label, baseLabel[idx]));
                }
            });
            box.addView(row);
        }

        in.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void afterTextChanged(Editable s) {
                convert();
            }
        });
        baseSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                convert();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        findViewById(R.id.rd_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.rd_paste).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                    ToolUi.toast(RadixActivity.this, getString(R.string.rd_clip_empty));
                    return;
                }
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(RadixActivity.this);
                in.setText(t.toString().trim());
                in.setSelection(in.getText().length());
            }
        });

        in.setText("255");
    }

    private void convert() {
        String s = in.getText().toString().trim();
        if (s.length() == 0) {
            for (TextView v : values) v.setText("—");
            info.setText("");
            status.setText(R.string.rd_hint);
            status.setTextColor(ThemeManager.textSub(this));
            return;
        }
        int base = RadixCore.BASES[Math.max(0, baseSp.getSelectedItemPosition())];
        try {
            RadixCore.Result r = RadixCore.convert(s, base);
            for (int i = 0; i < values.length; i++) values[i].setText(r.values[i]);
            info.setText(getString(R.string.rd_info, r.bits, r.byteLen,
                    RadixCore.groupBits(r.values[0]), r.hexBytes));
            status.setTextColor(ThemeManager.textSub(this));
            status.setText(getString(R.string.rd_status, base));
        } catch (IllegalArgumentException e) {
            for (TextView v : values) v.setText("—");
            info.setText("");
            status.setTextColor(ThemeManager.err(this));
            status.setText(getString(R.string.rd_err, e.getMessage()));
        }
    }
}
