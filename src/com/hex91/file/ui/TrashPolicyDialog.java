package com.hex91.file.ui;

import com.hex91.file.fs.*;
import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;
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
        cb.setText("启用自动清理");
        cb.setTextSize(14);
        cb.setChecked(p.enabled);
        box.addView(cb);

        TextView hint = new TextView(a);
        hint.setText("超过保留天数或容量上限时，自动彻底删除最旧的条目。目录占用按递归计算。");
        hint.setTextSize(11);
        hint.setTextColor(ThemeManager.textSub(a));
        hint.setPadding(0, 2, 0, 8);
        box.addView(hint);

        final EditText etDays = new EditText(a);
        etDays.setHint("保留天数（0 = 不限）");
        etDays.setTextSize(14);
        etDays.setSingleLine(true);
        etDays.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDays.setText(String.valueOf(p.maxDays));
        box.addView(etDays);

        final EditText etMb = new EditText(a);
        etMb.setHint("容量上限 MB（0 = 不限）");
        etMb.setTextSize(14);
        etMb.setSingleLine(true);
        etMb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMb.setText(String.valueOf(p.maxBytes / (1024 * 1024)));
        box.addView(etMb);

        new HfDialogBuilder(a)
                .setTitle("回收站保留策略")
                .setView(box)
                .setPositiveButton("保存并清理", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        p.enabled = cb.isChecked();
                        p.maxDays = parseInt(etDays.getText().toString(), TrashPolicy.DEFAULT_DAYS);
                        long mb = parseInt(etMb.getText().toString(),
                                (int) (TrashPolicy.DEFAULT_BYTES / (1024 * 1024)));
                        p.maxBytes = mb <= 0 ? 0 : mb * 1024L * 1024L;
                        if (!p.save(Trash.dir())) {
                            Toast.makeText(a, "策略保存失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        TrashPolicy.Report rep = p.apply(Trash.dir(), System.currentTimeMillis());
                        Toast.makeText(a, rep.summary(), Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 策略的当前规则，一行摘要（设置页副标题用） */
    static String summary() {
        TrashPolicy p = TrashPolicy.load(Trash.dir());
        if (!p.enabled) return "自动清理已关闭";
        StringBuilder sb = new StringBuilder("已开启");
        sb.append(p.maxDays > 0 ? " · 保留 " + p.maxDays + " 天" : " · 不限天数");
        sb.append(p.maxBytes > 0
                ? " · 上限 " + (p.maxBytes / (1024 * 1024)) + " MB"
                : " · 不限容量");
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
