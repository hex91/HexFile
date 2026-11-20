package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.CryptoCore;
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

        algSp.setAdapter(ToolUi.adapter(this, algNames()));
        int alg = getIntent().getIntExtra(ToolboxActivity.EXTRA_ALG, CryptoCore.A_AES);
        if (alg < 0 || alg >= CryptoCore.ALG_COUNT) alg = CryptoCore.A_AES;
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
                ToolUi.copy(CipherActivity.this, outTv.getText(),
                CipherActivity.this.getString(R.string.cph_copy_label));
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

        String hint = sym ? getString(R.string.cph_key_hint_prefix, keyHintText(a))
                : (hash && CryptoCore.needKey(a) ? getString(R.string.cph_hmac_hint) : "");
        keyHint.setText(hint);
        keyHint.setVisibility(hint.length() == 0 ? View.GONE : View.VISIBLE);

        if (sym) {
            go1.setText(R.string.cph_encrypt);
            go2.setText(R.string.cph_decrypt);
            go2.setVisibility(View.VISIBLE);
        } else if (hash) {
            go1.setText(R.string.cph_compute);
            go2.setVisibility(View.GONE);
        } else {
            go1.setText(R.string.cph_encode);
            go2.setText(R.string.cph_decode);
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
            ToolUi.toast(this, getString(R.string.cph_need_input));
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
        } catch (CryptoCore.CryptoException e) {
            outTv.setText("");
            status.setTextColor(ThemeManager.err(this));
            status.setText(getString(R.string.cph_err_prefix, cryptoErrorText(e)));
        } catch (Throwable e) {
            outTv.setText("");
            status.setTextColor(ThemeManager.err(this));
            status.setText(getString(R.string.cph_op_fail, e));
        }
    }

    /** 算法名：顺序必须与 CryptoCore.A_* 常量一一对应 */
    private String[] algNames() {
        if (names == null) names = getResources().getStringArray(R.array.cph_alg_names);
        return names;
    }

    private String[] names;

    /** 对称算法的密钥长度说明（核心层只给编号，说明文字在这里） */
    private String keyHintText(int a) {
        switch (a) {
            case CryptoCore.A_AES:
                return getString(R.string.cph_hint_aes);
            case CryptoCore.A_DES:
                return getString(R.string.cph_hint_des);
            case CryptoCore.A_3DES:
                return getString(R.string.cph_hint_3des);
            case CryptoCore.A_SM4:
                return getString(R.string.cph_hint_sm4);
            default:
                return getString(R.string.cph_hint_any);
        }
    }

    /** 把 CryptoCore 的错误码翻成文案（v2.80.0 F-14/67） */
    private String cryptoErrorText(CryptoCore.CryptoException e) {
        switch (e.code) {
            case CryptoCore.ERR_NO_KEY:
                return getString(R.string.cph_err_no_key, keyHintText(alg()));
            case CryptoCore.ERR_BAD_PAD:
                return getString(R.string.cph_err_bad_pad);
            case CryptoCore.ERR_CT_BLOCK:
                return getString(R.string.cph_err_ct_block);
            case CryptoCore.ERR_OP_FAIL:
                return getString(R.string.cph_err_op, e.text);
            case CryptoCore.ERR_CT_ALIGN16:
                return getString(R.string.cph_err_ct_align16, e.value);
            case CryptoCore.ERR_DIGEST_UNSUPPORTED:
                return getString(R.string.cph_err_digest_unsupported, e.text);
            case CryptoCore.ERR_HMAC_FAIL:
                return getString(R.string.cph_err_hmac_fail, e.text);
            case CryptoCore.ERR_AES_KEY:
                return getString(R.string.cph_err_aes_key, e.value);
            case CryptoCore.ERR_DES_KEY:
                return getString(R.string.cph_err_des_key, e.value);
            case CryptoCore.ERR_3DES_KEY:
                return getString(R.string.cph_err_3des_key, e.value);
            case CryptoCore.ERR_SM4_KEY:
                return getString(R.string.cph_err_sm4_key, e.value);
            default:
                return getString(R.string.cph_err_no_cipher);
        }
    }

    private String describe(int a, boolean forward, String r, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append(algNames()[a]);
        if (CryptoCore.isHash(a)) {
            sb.append(getString(R.string.cph_bits_chars, r.length() * 4, r.length()));
        } else if (CryptoCore.isSym(a)) {
            sb.append(getString(forward ? R.string.cph_suffix_enc : R.string.cph_suffix_dec));
            sb.append(" · ").append(mode == CryptoCore.MODE_CBC ? "CBC" : "ECB");
            sb.append(getString(R.string.cph_out_chars, r.length()));
        } else {
            sb.append(getString(forward ? R.string.cph_suffix_encode : R.string.cph_suffix_decode));
            sb.append(getString(R.string.cph_out_chars, r.length()));
        }
        if (ms > 0) sb.append(String.format(Locale.US, " · %d ms", ms));
        if (!CryptoCore.isHash(a) && !CryptoCore.isSym(a) && !forward) {
            // Base64/URL 解码失败时结果可能含乱码，提示一下
            sb.append(getString(R.string.cph_b64_note));
        }
        return sb.toString();
    }
}
