package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.TimeCore;
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
 *
 * <p>v2.78.0（F-14/65）：{@link TimeCore} 改成只产码，本页负责把单位码 / 相对时间 / 错误码
 * 翻成 R.string —— 所以「哪段文案」全在这一层，改文案不用动核心逻辑。
 */
public class TimeToolActivity extends BaseActivity {

    /** 页头秒表用（不带毫秒，秒级刷新才不抖） */
    private static final String PAT_STAMP = "yyyy-MM-dd HH:mm:ss";
    /** 详情行用 */
    private static final String PAT_FULL = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final String PAT_ISO = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    private static final String PAT_RFC = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /** 单位码 → 资源，下标与 {@link TimeCore#UNIT_SECOND} 等保持一一对应 */
    private static final int[] UNIT_LABELS = {
            R.string.tt_unit_second, R.string.tt_unit_millis,
            R.string.tt_unit_micros, R.string.tt_unit_nanos
    };

    /** 错误码 → 资源，下标与 {@link TimeCore#ERR_EMPTY_STAMP} 等对应（0 号不用） */
    private static final int[] ERROR_LABELS = {
            0,
            R.string.tt_err_empty_stamp, R.string.tt_err_stamp_number,
            R.string.tt_err_stamp_overflow, R.string.tt_err_empty_time,
            R.string.tt_err_time_unknown
    };

    /** 相对时间单位码 → 资源：整句带参，避免「数字 + 量词」硬拼出复数和语序问题 */
    private static final int[] REL_PAST = {
            R.string.tt_rel_ms_past, R.string.tt_rel_sec_past, R.string.tt_rel_min_past,
            R.string.tt_rel_hour_past, R.string.tt_rel_day_past,
            R.string.tt_rel_month_past, R.string.tt_rel_year_past
    };
    private static final int[] REL_FUTURE = {
            R.string.tt_rel_ms_future, R.string.tt_rel_sec_future, R.string.tt_rel_min_future,
            R.string.tt_rel_hour_future, R.string.tt_rel_day_future,
            R.string.tt_rel_month_future, R.string.tt_rel_year_future
    };

    private TextView nowTv, nowStamp, outStamp, outDate;
    private EditText inStamp, inDate;
    private TextView pauseBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean ticking = true;

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            nowTv.setText(TimeCore.fmt(now, PAT_STAMP, null) + "  " + weekday(now));
            nowStamp.setText(getString(R.string.tt_now_stamp, now / 1000L, now));
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
                ToolUi.copy(TimeToolActivity.this, String.valueOf(System.currentTimeMillis() / 1000L),
                        getString(R.string.tt_copy_now));
            }
        });
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ticking = !ticking;
                pauseBtn.setText(ticking ? R.string.tt_pause : R.string.tt_continue);
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
            inDate.setText(TimeCore.fmt(now, PAT_STAMP, null));
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
            outStamp.setText(R.string.tt_placeholder);
            return;
        }
        try {
            TimeCore.Stamp st = TimeCore.parseStamp(s);
            outStamp.setText(getString(R.string.tt_unit_detected, getString(UNIT_LABELS[st.unit]))
                    + "\n" + describe(st.millis));
            outStamp.setTextColor(ThemeManager.text(this));
        } catch (TimeCore.TimeException e) {
            outStamp.setText(errorText(e));
            outStamp.setTextColor(ThemeManager.err(this));
        }
    }

    private void convertDate() {
        String s = inDate.getText().toString().trim();
        if (s.length() == 0) {
            outDate.setText(R.string.tt_placeholder);
            return;
        }
        try {
            long ms = TimeCore.parseDateTime(s);
            outDate.setText(getString(R.string.tt_date_rows,
                    ms / 1000L, ms, ms * 1000L, relativeText(ms)));
            outDate.setTextColor(ThemeManager.text(this));
        } catch (TimeCore.TimeException e) {
            outDate.setText(errorText(e));
            outDate.setTextColor(ThemeManager.err(this));
        }
    }

    /** 本地 / UTC / ISO / RFC / 人读 / 相对 / 纪元秒毫秒，一行一条方便直接复制 */
    private String describe(long millis) {
        StringBuilder sb = new StringBuilder(320);
        sb.append(getString(R.string.tt_row_local, TimeCore.fmt(millis, PAT_FULL, null))).append('\n');
        sb.append(getString(R.string.tt_row_utc, TimeCore.fmt(millis, PAT_FULL, "UTC"))).append('\n');
        sb.append(getString(R.string.tt_row_iso, TimeCore.fmt(millis, PAT_ISO, null))).append('\n');
        // RFC 1123 的星期/月份缩写是英文规范，中文环境也必须是 Wed / Nov
        sb.append(getString(R.string.tt_row_rfc,
                TimeCore.fmt(millis, PAT_RFC, "UTC", Locale.US))).append('\n');
        sb.append(getString(R.string.tt_row_human,
                TimeCore.fmt(millis, getString(R.string.tt_pat_human), null), weekday(millis))).append('\n');
        sb.append(getString(R.string.tt_row_relative, relativeText(millis))).append('\n');
        sb.append(getString(R.string.tt_row_epoch, millis / 1000L, millis));
        return sb.toString();
    }

    private String weekday(long millis) {
        String[] names = getResources().getStringArray(R.array.tt_weekdays);
        return names[TimeCore.weekdayIndex(millis)];
    }

    private String relativeText(long millis) {
        TimeCore.Relative r = TimeCore.relative(millis);
        String num = r.oneDecimal
                ? (r.count / 10L) + "." + Math.abs(r.count % 10L)
                : String.valueOf(r.count);
        int[] table = r.future ? REL_FUTURE : REL_PAST;
        return getString(table[r.unit], num);
    }

    private String errorText(TimeCore.TimeException e) {
        int res = (e.code >= 0 && e.code < ERROR_LABELS.length) ? ERROR_LABELS[e.code] : 0;
        String msg = res == 0 ? e.getMessage() : getString(res);
        return getString(R.string.tt_err, msg);
    }
}
