package com.hex91.file;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

/**
 * v1.70.0 时间戳页：上方是每秒刷新的当前时间，下面两个换算区（时间戳↔日期）都是输入即算。
 *
 * <p>单位识别交给 {@link TimeCore#parseStamp}（≤10 位秒、≤13 位毫秒、≤16 位微秒），
 * 日期解析支持 {@code 2024-01-01 12:00:00}、{@code 2024/01/01}、{@code 20240101120000}、
 * 带 {@code Z} 或 {@code +08:00} 后缀等写法。
 */
public class TimeToolActivity extends BaseActivity {

    private TextView nowTv, nowStamp, outStamp, outDate;
    private EditText inStamp, inDate;
    private TextView pauseBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean ticking = true;

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            nowTv.setText(TimeCore.fmt(now, "yyyy-MM-dd HH:mm:ss", null)
                    + "  " + TimeCore.weekday(now));
            nowStamp.setText("秒     " + (now / 1000L) + "\n毫秒   " + now);
            if (ticking) ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_time);

        nowTv = (TextView) findViewById(R.id.tm_now);
        nowStamp = (TextView) findViewById(R.id.tm_now_stamp);
        outStamp = (TextView) findViewById(R.id.tm_out_stamp);
        outDate = (TextView) findViewById(R.id.tm_out_date);
        inStamp = (EditText) findViewById(R.id.tm_in_stamp);
        inDate = (EditText) findViewById(R.id.tm_in_date);
        pauseBtn = (TextView) findViewById(R.id.tm_pause);

        findViewById(R.id.tm_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        nowTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ToolUi.copy(TimeToolActivity.this, String.valueOf(System.currentTimeMillis() / 1000L), "当前秒级时间戳");
            }
        });
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ticking = !ticking;
                pauseBtn.setText(ticking ? "暂停" : "继续");
                if (ticking) ui.post(tick);
            }
        });

        TextWatcher w = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b1, int c) {
            }

            public void afterTextChanged(Editable s) {
                convertStamp();
                convertDate();
            }
        };
        inStamp.addTextChangedListener(w);
        inDate.addTextChangedListener(w);

        if (b == null) {
            long now = System.currentTimeMillis();
            inStamp.setText(String.valueOf(now / 1000L));
            inDate.setText(TimeCore.fmt(now, "yyyy-MM-dd HH:mm:ss", null));
        }
        // 秒表只在 onResume 里启动：onCreate 之后再 post 一次会让两条 Runnable 链并存，
        // 表现为刷新速度翻倍（每 0.5s 跳两次）
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);   // 先摘掉可能残留的旧链，再启动唯一一条
        if (ticking) ui.post(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
    }

    private void convertStamp() {
        String s = inStamp.getText().toString().trim();
        if (s.length() == 0) {
            outStamp.setText("—");
            return;
        }
        try {
            TimeCore.Stamp st = TimeCore.parseStamp(s);
            outStamp.setText("识别单位  " + st.unit + "\n" + TimeCore.describe(st.millis));
            outStamp.setTextColor(ThemeManager.text(this));
        } catch (IllegalArgumentException e) {
            outStamp.setText("✗ " + e.getMessage());
            outStamp.setTextColor(ThemeManager.err(this));
        }
    }

    private void convertDate() {
        String s = inDate.getText().toString().trim();
        if (s.length() == 0) {
            outDate.setText("—");
            return;
        }
        try {
            long ms = TimeCore.parseDateTime(s);
            outDate.setText(String.format(Locale.US, "秒     %d\n毫秒   %d\n微秒   %d\n\n%s",
                    ms / 1000L, ms, ms * 1000L, TimeCore.relative(ms)));
            outDate.setTextColor(ThemeManager.text(this));
        } catch (IllegalArgumentException e) {
            outDate.setText("✗ " + e.getMessage());
            outDate.setTextColor(ThemeManager.err(this));
        }
    }
}
