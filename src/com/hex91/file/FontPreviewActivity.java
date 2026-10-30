package com.hex91.file;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 字体预览（v1.58.0）：加载 ttf/otf，中英文+数字样张实时渲染，
 *  字号滑条 12-72sp，自定义文本即时预览。加载失败提示不支持的字体。
 */
public class FontPreviewActivity extends BaseActivity {

    static final String EXTRA_FILE = "file";

    private Typeface typeface;
    private TextView sampleEn, sampleZh, sampleNum, sampleCustom;
    private EditText customInput;
    private float sizeSp = 28f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File font = new File(getIntent().getStringExtra(EXTRA_FILE));
        try {
            typeface = Typeface.createFromFile(font);
        } catch (Throwable t) {
            Toast.makeText(this, "无法加载字体: " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        // 标题栏：文件名 + 字号调节
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.surface(this));
        top.setPadding((int) (16 * den), (int) (10 * den), (int) (16 * den), (int) (10 * den));
        TextView title = new TextView(this);
        title.setText(font.getName());
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        top.addView(title);
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView szLabel = new TextView(this);
        szLabel.setText("字号");
        szLabel.setTextSize(13);
        szLabel.setTextColor(ThemeManager.textSub(this));
        szLabel.setPadding(0, 0, (int) (10 * den), 0);
        sizeRow.addView(szLabel);
        SeekBar seek = new SeekBar(this);
        seek.setMax(60);
        seek.setProgress(16);   // 12 + 16 = 28sp
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                sizeSp = 12 + p;
                applySize();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        sizeRow.addView(seek, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(sizeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * den);
        body.setPadding(pad, pad, pad, pad);

        sampleEn = mkSample("AaBbCcDdEeFf GgHhIiJj\nThe quick brown fox\njumps over the lazy dog 0123456789");
        body.addView(sampleEn);
        sampleZh = mkSample("永国信仰山流水\n汉仪字体预览样张\n春眠不觉晓，处处闻啼鸟。");
        body.addView(sampleZh);
        sampleNum = mkSample("0123456789 !@#$%^&*()\n.,:;?/\\|[]{}()<>+-=~`'\"");
        body.addView(sampleNum);

        // 自定义文本
        TextView hint = new TextView(this);
        hint.setText("自定义预览文本：");
        hint.setTextSize(12);
        hint.setTextColor(ThemeManager.textSub(this));
        hint.setPadding(0, (int) (14 * den), 0, (int) (4 * den));
        body.addView(hint);
        customInput = new EditText(this);
        customInput.setText("在此输入任意文字预览字体效果");
        customInput.setTextSize(13);
        customInput.setTextColor(ThemeManager.text(this));
        body.addView(customInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sampleCustom = mkSample("在此输入任意文字预览字体效果");
        body.addView(sampleCustom);
        customInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                sampleCustom.setText(s.length() > 0 ? s.toString() : " ");
            }
        });

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applySize();
    }

    private TextView mkSample(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(typeface);
        t.setTextColor(ThemeManager.text(this));
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, 0, 0, 24);
        return t;
    }

    private void applySize() {
        if (sampleEn == null) return;
        sampleEn.setTextSize(sizeSp);
        sampleZh.setTextSize(sizeSp);
        sampleNum.setTextSize(sizeSp * 0.8f);
        sampleCustom.setTextSize(sizeSp);
    }
}
