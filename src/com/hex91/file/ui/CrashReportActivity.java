package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.MimeUtil;
import com.hex91.file.utils.CrashLog;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * v2.12.0（F-18）：崩溃日志页。
 *
 * <p>只做三件事：把最近一次崩溃的现场原样显示出来、允许分享、允许清空。
 * 不做「上报」——这个应用没有服务器，硬加一个上报通道既不诚实也不合规；
 * 用户想给谁看，就让他自己选分享。</p>
 *
 * <p>进入本页即视为「已读」（{@link CrashLog#markSeen}），否则下次启动还会再提示一次，
 * 变成骚扰。</p>
 */
public class CrashReportActivity extends BaseActivity {

    /** 可选：指定要看的崩溃文件；不传则看最近一次 */
    public static final String EXTRA_FILE = "file";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent() != null ? getIntent().getStringExtra(EXTRA_FILE) : null;
        File target = path != null ? new File(path) : CrashLog.newest(this);
        final String text = target != null ? CrashLog.read(this, target)
                : getString(R.string.crash_none);
        CrashLog.markSeen(this);

        float den = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeManager.bg(this));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(ThemeManager.surface(this));
        top.setPadding((int) (16 * den), (int) (10 * den), (int) (16 * den), (int) (10 * den));
        TextView title = new TextView(this);
        List<File> all = CrashLog.list(this);
        title.setText(getString(R.string.crash_title) + (all.size() > 1
                ? "（" + getString(R.string.crash_count, all.size()) + "）" : ""));
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeManager.text(this));
        top.addView(title);
        if (target != null) {
            TextView sub = new TextView(this);
            sub.setText(target.getName());
            sub.setTextSize(11);
            sub.setTextColor(ThemeManager.textSub(this));
            top.addView(sub);
        }
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(11.5f);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);   // 堆栈对齐靠等宽
        body.setTextColor(ThemeManager.text(this));
        body.setPadding((int) (12 * den), (int) (12 * den), (int) (12 * den), (int) (12 * den));
        ScrollView sc = new ScrollView(this);
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ThemeManager.surface(this));
        bar.setPadding((int) (8 * den), (int) (6 * den), (int) (8 * den), (int) (6 * den));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        Button bShare = mkBtn(bar, blp, getString(R.string.crash_share));
        bShare.setEnabled(target != null);
        bShare.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { share(target); }
        });
        Button bDel = mkBtn(bar, blp, getString(R.string.crash_delete));
        bDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                CrashLog.clear(CrashReportActivity.this);
                Toast.makeText(CrashReportActivity.this, R.string.crash_deleted,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        Button bClose = mkBtn(bar, blp, getString(R.string.common_close));
        bClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private Button mkBtn(LinearLayout bar, LinearLayout.LayoutParams lp, String label) {
        Button b = new Button(this);
        b.setText(label);
        UiDecor.barButton(b, this, UiDecor.KIND_SOFT, 12f);
        bar.addView(b, lp);
        return b;
    }

    /** 走项目自带的 ApkFileProvider（content:// + 绝对路径），避免 file:// 在 API 24+ 被拒 */
    private void share(File f) {
        if (f == null || !f.isFile()) return;
        try {
            Uri uri = Uri.parse("content://com.hex91.file.files" + f.getAbsolutePath());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(MimeUtil.of(f.getName()));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            send.setClipData(ClipData.newRawUri(f.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, getString(R.string.crash_share));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.crash_share_failed, String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }
}
