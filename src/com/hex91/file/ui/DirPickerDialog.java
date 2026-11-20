package com.hex91.file.ui;

import com.hex91.file.R;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 目录选择对话框（只选目录）。
 *
 * <p>与主界面「对比目标」里的选择器同源，但剥掉了文件项与 APK 特判：
 * 设置页只需要一个能逐级进入、能「选当前目录」的轻量浏览器。</p>
 */
public final class DirPickerDialog {

    public interface OnPick {
        void onPick(File dir);
    }

    private DirPickerDialog() {}

    /**
     * @param start 初始目录，null 时从 /sdcard 开始
     * @param cb    选中回调（回到主线程）
     */
    public static void show(final Activity a, File start, final OnPick cb) {
        if (a == null || a.isFinishing()) return;

        final File[] cur = {start != null && start.isDirectory() ? start
                : new File("/sdcard")};
        final boolean[] hasParent = {cur[0].getParentFile() != null};
        final List<File> kids = new ArrayList<File>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                a, android.R.layout.simple_list_item_1);
        final ListView lv = new ListView(a);
        lv.setAdapter(adapter);
        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = new Runnable() {
            public void run() {
                File d = cur[0];
                kids.clear();
                adapter.clear();
                File parent = d.getParentFile();
                hasParent[0] = parent != null && parent.canRead();
                if (hasParent[0]) adapter.add(a.getString(R.string.dpd_up));
                File[] arr = d.listFiles();
                if (arr != null) {
                    Arrays.sort(arr, new Comparator<File>() {
                        public int compare(File x, File y) {
                            return x.getName().compareToIgnoreCase(y.getName());
                        }
                    });
                    for (File k : arr) {
                        // 只列目录；无权限的目录也列出来（部分 ROM 的 canRead 会误报 false）
                        if (!k.isDirectory()) continue;
                        kids.add(k);
                        adapter.add(a.getString(R.string.dpd_dir, k.getName()));
                    }
                }
                if (kids.isEmpty()) adapter.add(a.getString(R.string.dpd_empty));
                if (holder[0] != null) holder[0].setTitle(a.getString(R.string.dpd_title, d.getAbsolutePath()));
            }
        };

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                int off = hasParent[0] ? 1 : 0;
                if (hasParent[0] && pos == 0) {
                    cur[0] = cur[0].getParentFile();
                    refresh[0].run();
                    return;
                }
                int idx = pos - off;
                if (idx < 0 || idx >= kids.size()) return;
                cur[0] = kids.get(idx);
                refresh[0].run();
            }
        });

        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        // 固定高度：AlertDialog 对 weight 的二次测量不可靠，给个 320dp 更稳
        int h = (int) (320 * a.getResources().getDisplayMetrics().density);
        wrap.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));

        holder[0] = new HfDialogBuilder(a)
                .setTitle(R.string.dpd_pick)
                .setView(wrap)
                .setPositiveButton(R.string.main_cmp_pick_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onPick(cur[0]);
                    }
                })
                .setNeutralButton(R.string.dpd_manual, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        askManual(a, cur[0], cb);
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        // 先 refresh 再 show：setTitle 里要拿到 dialog 实例
        refresh[0].run();
        holder[0].show();
    }

    /** 手输绝对路径（外置卡路径很长，逐级点太累） */
    private static void askManual(final Activity a, final File cur, final OnPick cb) {
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setTextSize(14);
        et.setText(cur.getAbsolutePath());
        et.setSelection(et.getText().length());
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 8, pad, 0);
        box.addView(et);
        new HfDialogBuilder(a)
                .setTitle(R.string.dpd_path_title)
                .setMessage(R.string.dpd_path_msg)
                .setView(box)
                .setPositiveButton(R.string.common_confirm, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onPick(new File(et.getText().toString().trim()));
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }
}
