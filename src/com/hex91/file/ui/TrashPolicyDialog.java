package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.fs.Trash;
import com.hex91.file.fs.TrashPolicy;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 回收站保留策略弹窗。
 *
 * <p>v1.82.0 起长在回收站顶栏，v1.83.0 又搬进「设置」——两处入口共用同一份实现，
 * 避免天数/容量/开关的解析规则各写一遍后走偏。</p>
 */
final class TrashPolicyDialog {

    private TrashPolicyDialog() {}

    /**
     * @param onChanged 保存并清理完成后的回调（页面刷新用），可为 null
     */
    static void show(final Activity a, final Runnable onChanged) {
        if (a == null || a.isFinishing()) return;
        final TrashPolicy p = TrashPolicy.load(Trash.dir());

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 8, pad, 0);

        final CheckBox cb = new CheckBox(a);
        cb.setText(a.getString(R.string.trash_enable));
        cb.setTextSize(14);
        cb.setChecked(p.enabled);
        box.addView(cb);

        TextView hint = new TextView(a);
        hint.setText(a.getString(R.string.trash_enable_hint));
        hint.setTextSize(11);
        hint.setTextColor(ThemeManager.textSub(a));
        hint.setPadding(0, 2, 0, 8);
        box.addView(hint);

        final EditText etDays = new EditText(a);
        etDays.setHint(a.getString(R.string.trash_days_hint));
        etDays.setTextSize(14);
        etDays.setSingleLine(true);
        etDays.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDays.setText(String.valueOf(p.maxDays));
        box.addView(etDays);

        final EditText etMb = new EditText(a);
        etMb.setHint(a.getString(R.string.trash_limit_hint));
        etMb.setTextSize(14);
        etMb.setSingleLine(true);
        etMb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMb.setText(String.valueOf(p.maxBytes / (1024 * 1024)));
        box.addView(etMb);

        new HfDialogBuilder(a)
                .setTitle(a.getString(R.string.trash_policy_title))
                .setView(box)
                .setPositiveButton(a.getString(R.string.trash_save_apply), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        p.enabled = cb.isChecked();
                        p.maxDays = parseInt(etDays.getText().toString(), TrashPolicy.DEFAULT_DAYS);
                        long mb = parseInt(etMb.getText().toString(),
                                (int) (TrashPolicy.DEFAULT_BYTES / (1024 * 1024)));
                        p.maxBytes = mb <= 0 ? 0 : mb * 1024L * 1024L;
                        if (!p.save(Trash.dir())) {
                            Toast.makeText(a, a.getString(R.string.trash_save_fail), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        TrashPolicy.Report rep = p.apply(Trash.dir(), System.currentTimeMillis());
                        Toast.makeText(a, TrashReport.clean(a, rep), Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    }
                })
                .setNegativeButton(a.getString(R.string.common_cancel), null)
                .show();
    }

    /** 策略的当前规则，一行摘要（设置页副标题用） */
    static String summary(Context c) {
        TrashPolicy p = TrashPolicy.load(Trash.dir());
        if (!p.enabled) return c.getString(R.string.trash_summary_off);
        StringBuilder sb = new StringBuilder(c.getString(R.string.trash_summary_on));
        sb.append(p.maxDays > 0 ? c.getString(R.string.trash_policy_days, p.maxDays) : c.getString(R.string.trash_policy_no_days));
        sb.append(p.maxBytes > 0
                ? c.getString(R.string.trash_policy_limit, p.maxBytes / (1024 * 1024))
                : c.getString(R.string.trash_policy_no_limit));
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
