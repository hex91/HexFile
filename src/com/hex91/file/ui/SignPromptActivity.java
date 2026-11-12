package com.hex91.file.ui;

import com.hex91.file.apk.*;
import com.hex91.file.core.*;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * v1.66.0 统一签名弹窗（透明 Activity）：
 * 退出 APK 浏览时有未签名修改版（<名>_edit.apk）时弹出，
 * 三个选项：暂不签名 / 内置签名文件签名 / 自有签名文件签名。
 * 签名成功后原地覆盖 _edit.apk（保持只生成一个文件）。
 *
 * v1.66.1：
 *  - 修复签名目标：只签 <名>_edit.apk（此前误签原包）；
 *  - 界面重做：不再用系统 AlertDialog（大黑框），改为自绘圆角卡片，
 *    配色全部取自 ThemeManager 当前主题（亮/暗 + 22 色板自动适配）。
 */
public class SignPromptActivity extends Activity {

    private static final int REQ_KEYSTORE = 7001;

    private File apk;
    private Uri keystoreUri;

    private LinearLayout card;

    /** 与 BaseActivity 一致：收敛系统字体缩放，避免卡片文字被撑破（v1.73.0） */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(UiKit.alignMetrics(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        apk = new File(getIntent().getStringExtra("apk"));

        if (!apk.isFile()) {
            Toast.makeText(this, "修改版不存在（可能已被删除）", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 全屏透明底 + 半透明黑色遮罩
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0x99000000);
        root.setClickable(true);                       // 吞掉空白处点击
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { skipSign(); }
        });

        // 圆角卡片
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(ThemeManager.surface(this), dp(22)));
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(28), 0, dp(28), 0);
        root.addView(card, cardLp);

        setContentView(root);
        showMainCard();
    }

    // ================= 主卡片：标题 + 文件信息 + 三个选项 =================

    private void showMainCard() {
        card.removeAllViews();

        TextView title = new TextView(this);
        title.setText("APK 签名");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("修改版尚未签名。未签名的 APK 可能无法安装，建议先签名。");
        sub.setTextSize(13);
        sub.setTextColor(ThemeManager.textSub(this));
        sub.setPadding(0, dp(4), 0, dp(12));
        card.addView(sub);

        // 文件信息条
        TextView filePill = new TextView(this);
        filePill.setText(apk.getName());
        filePill.setTextSize(13);
        filePill.setTextColor(ThemeManager.text(this));
        filePill.setSingleLine(true);
        filePill.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        filePill.setBackground(rounded(ThemeManager.surfaceAlt(this), dp(10)));
        filePill.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.addView(filePill, matchWrap(dp(0), dp(12)));

        // 主选项：内置签名（accent 实底）
        TextView btn1 = optionButton("内置签名文件签名（v1+v2+v3）",
                ThemeManager.accent(this), ThemeManager.onAccent(this), true);
        btn1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { signBuiltin(); }
        });
        card.addView(btn1, matchWrap(dp(0), dp(14)));

        // 次选项：自有签名文件（surfaceAlt 底）
        TextView btn2 = optionButton("使用自有签名文件签名",
                ThemeManager.surfaceAlt(this), ThemeManager.text(this), false);
        btn2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickKeystore(); }
        });
        card.addView(btn2, matchWrap(dp(0), dp(10)));

        // 文字选项：暂不签名
        TextView btn3 = new TextView(this);
        btn3.setText("暂不签名（未签名 APK 可能无法安装）");
        btn3.setTextSize(14);
        btn3.setGravity(Gravity.CENTER);
        btn3.setTextColor(ThemeManager.textSub(this));
        btn3.setPadding(0, dp(10), 0, dp(2));
        btn3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { skipSign(); }
        });
        card.addView(btn3, matchWrap(0, 0));
    }

    // ================= 密码卡片：自有 keystore =================

    private void showPasswordCard(final Uri ksUri) {
        card.removeAllViews();

        TextView title = new TextView(this);
        title.setText("自有签名文件签名");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        card.addView(title);

        TextView sub = new TextView(this);
        String ksName = ksUri.getLastPathSegment();
        sub.setText("签名文件: " + (ksName != null ? ksName : "已选择")
                + "\n目标: " + apk.getName());
        sub.setTextSize(13);
        sub.setTextColor(ThemeManager.textSub(this));
        sub.setPadding(0, dp(4), 0, dp(12));
        card.addView(sub);

        final EditText pass = new EditText(this);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("keystore 密码");
        pass.setTextSize(14);
        pass.setTextColor(ThemeManager.text(this));
        pass.setHintTextColor(ThemeManager.textSub(this));
        pass.setBackground(rounded(ThemeManager.surfaceAlt(this), dp(10)));
        pass.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(pass, matchWrap(dp(0), dp(4)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(ThemeManager.textSub(this));
        cancel.setPadding(0, dp(12), 0, dp(2));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMainCard(); }
        });
        row.addView(cancel, rowWeight(1f));

        TextView ok = optionButton("签名", ThemeManager.accent(this),
                ThemeManager.onAccent(this), true);
        ok.setPadding(0, 0, 0, 0);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String pwd = pass.getText().toString();
                if (pwd.isEmpty()) {
                    Toast.makeText(SignPromptActivity.this, "密码不能为空",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                signCustom(ksUri, pwd);
            }
        });
        row.addView(ok, rowWeight(1f));
        ((LinearLayout.LayoutParams) ok.getLayoutParams()).setMargins(dp(8), dp(12), 0, dp(2));

        card.addView(row, matchWrap(0, 0));
    }

    // ================= 进行中 / 收尾 =================

    private void showProgressCard(String msg) {
        card.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        row.addView(pb, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(14);
        tv.setTextColor(ThemeManager.text(this));
        tv.setPadding(dp(14), 0, 0, 0);
        row.addView(tv);
        card.addView(row, matchWrap(dp(8), dp(8)));
    }

    /** 暂不签名：本轮不再提示 */
    private void skipSign() {
        finish();
    }

    private void done(String err) {
        Toast.makeText(this,
                err != null ? "签名失败: " + err : "已签名 → " + apk.getName(),
                Toast.LENGTH_LONG).show();
        finish();
    }

    // ================= 签名执行 =================

    /** 内置 hexfile.p12 签名，v1+v2+v3，原地覆盖 _edit.apk */
    private void signBuiltin() {
        showProgressCard("正在签名…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    signInPlace(new Signer() {
                        public void sign(File out) throws Exception {
                            java.io.InputStream ks = getAssets().open("hexfile.p12");
                            try {
                                ApkSignerTool.sign(ks, ApkSignerTool.KEY_PASSWORD, apk, out, 21);
                            } finally {
                                try { ks.close(); } catch (Exception ignored) {}
                            }
                        }
                    });
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() { done(ferr); }
                });
            }
        }, "edit-sign").start();
    }

    /** 自有 keystore 签名（自动选别名），原地覆盖 _edit.apk */
    private void signCustom(final Uri keystoreUri, final String pwd) {
        showProgressCard("正在签名…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(keystoreUri);
                    byte[] ksBytes;
                    try {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        ksBytes = bo.toByteArray();
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                    }
                    final byte[] fks = ksBytes;
                    final char[] pw = pwd.toCharArray();
                    signInPlace(new Signer() {
                        public void sign(File out) throws Exception {
                            ApkSignerTool.signAutoAlias(fks, pw, apk, out, 21);
                        }
                    });
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() { done(ferr); }
                });
            }
        }, "edit-csign").start();
    }

    private interface Signer {
        void sign(File out) throws Exception;
    }

    /** 签名到临时文件 → 原子覆盖 _edit.apk（AtomicReplace：先 rename，失败才备份+复制） */
    private void signInPlace(Signer signer) throws Exception {
        File tmp = new File(apk.getParentFile(), apk.getName() + ".signtmp");
        if (tmp.exists()) tmp.delete();
        signer.sign(tmp);
        ApkEditSession.safeReplace(tmp, apk);
        ApkEditSession.markSigned(apk);
    }

    // ================= 文件选择 =================

    private void pickKeystore() {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(
                    android.content.Intent.createChooser(i, "选择签名文件（.p12/.jks/.keystore）"),
                    REQ_KEYSTORE);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_KEYSTORE || res != RESULT_OK || data == null
                || data.getData() == null) {
            showMainCard();      // 未选择 → 回主卡片
            return;
        }
        keystoreUri = data.getData();
        showPasswordCard(keystoreUri);
    }

    @Override
    public void onBackPressed() {
        // 密码页返回主卡片，主卡片返回直接关
        if (keystoreUri != null) {
            keystoreUri = null;
            showMainCard();
        } else {
            finish();
        }
    }

    // ================= UI 工具 =================

    /** 选项按钮：圆角底色 + 居中文本 */
    private TextView optionButton(String text, int bg, int fg, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(fg);
        tv.setBackground(rounded(bg, dp(14)));
        tv.setPadding(dp(12), dp(13), dp(12), dp(13));
        return tv;
    }

    private static LinearLayout.LayoutParams matchWrap(int l, int t) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, 0, 0);
        return lp;
    }

    private static LinearLayout.LayoutParams rowWeight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
