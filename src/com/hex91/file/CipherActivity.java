package com.hex91.file;

import com.hex91.file.core.*;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

/**
 * v1.70.0 加解密页。
 *
 * <p>一套界面覆盖 18 种算法：界面按算法的性质动态显隐「密钥 / IV / 模式 / 输出格式」，
 * 按钮文案也跟着变（哈希只有一个「计算」，对称是「加密/解密」，编码是「编码/解码」）。
 *
 * <p>密文输入不用选格式：{@link CryptoCore#parseCipherText} 会自己判断是 HEX 还是 Base64。
 */
public class CipherActivity extends BaseActivity {

    private Spinner algSp;
    private EditText keyEt, ivEt, inEt;
    private View keyBox, ivBox, outBox;
    private TextView keyHint, outTv, status, go1, go2;
    private TextView modeEcb, modeCbc, fmtB64, fmtHex;

    private int mode = CryptoCore.MODE_ECB;
    private int outFmt = CryptoCore.OUT_B64;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_cipher);

        algSp = (Spinner) findViewById(R.id.cp_alg);
        keyEt = (EditText) findViewById(R.id.cp_key);
        ivEt = (EditText) findViewById(R.id.cp_iv);
        inEt = (EditText) findViewById(R.id.cp_in);
        keyBox = findViewById(R.id.cp_key_box);
        ivBox = findViewById(R.id.cp_iv_box);
        outBox = findViewById(R.id.cp_out_box);
        keyHint = (TextView) findViewById(R.id.cp_key_hint);
        outTv = (TextView) findViewById(R.id.cp_out);
        status = (TextView) findViewById(R.id.cp_status);
        go1 = (TextView) findViewById(R.id.cp_go1);
        go2 = (TextView) findViewById(R.id.cp_go2);
        modeEcb = (TextView) findViewById(R.id.cp_mode_ecb);
        modeCbc = (TextView) findViewById(R.id.cp_mode_cbc);
        fmtB64 = (TextView) findViewById(R.id.cp_fmt_b64);
        fmtHex = (TextView) findViewById(R.id.cp_fmt_hex);

        algSp.setAdapter(ToolUi.adapter(this, CryptoCore.NAMES));
        int alg = getIntent().getIntExtra(ToolboxActivity.EXTRA_ALG, CryptoCore.A_AES);
        if (alg < 0 || alg >= CryptoCore.NAMES.length) alg = CryptoCore.A_AES;
        algSp.setSelection(alg);

        algSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshUi();
                outTv.setText("");
                status.setText("");
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        findViewById(R.id.cp_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.cp_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(CipherActivity.this, outTv.getText(), "结果");
            }
        });
        go1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                run(true);
            }
        });
        go2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                run(false);
            }
        });
        modeEcb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mode = CryptoCore.MODE_ECB;
                refreshUi();
            }
        });
        modeCbc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mode = CryptoCore.MODE_CBC;
                refreshUi();
            }
        });
        fmtB64.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                outFmt = CryptoCore.OUT_B64;
                refreshUi();
            }
        });
        fmtHex.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                outFmt = CryptoCore.OUT_HEX;
                refreshUi();
            }
        });

        go1.setBackground(ToolUi.accentBg(this, 10));
        refreshUi();
    }

    private int alg() {
        return algSp.getSelectedItemPosition();
    }

    /** 按当前算法调整可见区域与按钮文案 */
    private void refreshUi() {
        int a = alg();
        boolean sym = CryptoCore.isSym(a);
        boolean hash = CryptoCore.isHash(a);

        keyBox.setVisibility(CryptoCore.needKey(a) ? View.VISIBLE : View.GONE);
        ivBox.setVisibility(sym ? View.VISIBLE : View.GONE);
        outBox.setVisibility(sym ? View.VISIBLE : View.GONE);
        ivEt.setVisibility(mode == CryptoCore.MODE_CBC ? View.VISIBLE : View.GONE);

        String hint = sym ? "需要 " + CryptoCore.keyHint(a)
                : (hash && CryptoCore.needKey(a) ? "HMAC 密钥，任意长度" : "");
        keyHint.setText(hint);
        keyHint.setVisibility(hint.length() == 0 ? View.GONE : View.VISIBLE);

        if (sym) {
            go1.setText("加密");
            go2.setText("解密");
            go2.setVisibility(View.VISIBLE);
        } else if (hash) {
            go1.setText("计算");
            go2.setVisibility(View.GONE);
        } else {
            go1.setText("编码");
            go2.setText("解码");
            go2.setVisibility(View.VISIBLE);
        }
        go2.setBackground(ToolUi.card(this, 10));

        ToolUi.chipState(modeEcb, mode == CryptoCore.MODE_ECB, this);
        ToolUi.chipState(modeCbc, mode == CryptoCore.MODE_CBC, this);
        ToolUi.chipState(fmtB64, outFmt == CryptoCore.OUT_B64, this);
        ToolUi.chipState(fmtHex, outFmt == CryptoCore.OUT_HEX, this);
    }

    private void run(boolean forward) {
        int a = alg();
        String input = inEt.getText().toString();
        if (input.length() == 0) {
            ToolUi.toast(this, "请先填写输入内容");
            return;
        }
        try {
            long t0 = System.nanoTime();
            String r = CryptoCore.run(a, forward, input, keyEt.getText().toString(),
                    ivEt.getText().toString(), mode, outFmt);
            long ms = (System.nanoTime() - t0) / 1000000L;
            outTv.setText(r);
            status.setTextColor(ThemeManager.textSub(this));
            status.setText(describe(a, forward, r, ms));
        } catch (IllegalArgumentException e) {
            outTv.setText("");
            status.setTextColor(ThemeManager.err(this));
            status.setText("✗ " + e.getMessage());
        } catch (Throwable e) {
            outTv.setText("");
            status.setTextColor(ThemeManager.err(this));
            status.setText("✗ 运算失败：" + e);
        }
    }

    private String describe(int a, boolean forward, String r, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append(CryptoCore.name(a));
        if (CryptoCore.isHash(a)) {
            sb.append(" · ").append(r.length() * 4).append(" 位 / ").append(r.length()).append(" 字符");
        } else if (CryptoCore.isSym(a)) {
            sb.append(forward ? " 加密" : " 解密");
            sb.append(" · ").append(mode == CryptoCore.MODE_CBC ? "CBC" : "ECB");
            sb.append(" · 输出 ").append(r.length()).append(" 字符");
        } else {
            sb.append(forward ? " 编码" : " 解码");
            sb.append(" · 输出 ").append(r.length()).append(" 字符");
        }
        if (ms > 0) sb.append(String.format(Locale.US, " · %d ms", ms));
        if (!CryptoCore.isHash(a) && !CryptoCore.isSym(a) && !forward) {
            // Base64/URL 解码失败时结果可能含乱码，提示一下
            sb.append("\n解码结果按 UTF-8 显示，二进制内容请在「编码转换」里转 HEX 查看");
        }
        return sb.toString();
    }
}
