package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.Trash;
import com.hex91.file.fs.TrashPolicy;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 回收站管理：恢复 / 彻底删除 / 清空 / 保留策略 */
public class TrashActivity extends BaseActivity {

    private final List<Trash.Entry> data = new ArrayList<Trash.Entry>();
    private TrashAdapter adapter;
    private TextView empty;
    private TextView title;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_trash);

        ListView lv = (ListView) findViewById(R.id.list_trash);
        empty = (TextView) findViewById(R.id.tv_empty);
        title = (TextView) findViewById(R.id.tv_title);
        adapter = new TrashAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                showEntryOps(data.get(i));
            }
        });

        // v1.82.0：保留策略（天数 / 容量 / 自动清理开关）
        // v1.82.1：「策略」太抽象，用户找不到 → 改成「⚙ 保留策略」，并支持被主界面菜单直呼
        // v1.83.0：改用顶栏专用配色——SOFT 胶囊的 accent 文字在 accent 同色顶栏上会隐形
        android.widget.Button btnPolicy = (android.widget.Button) findViewById(R.id.btn_policy);
        UiDecor.barButtonOnBar(btnPolicy, this, UiDecor.KIND_SOFT, 11.5f);
        btnPolicy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPolicy(); }
        });
        UiDecor.gap(btnPolicy, this, 4f);

        // v1.76.0：顶栏「清空」按钮 → 危险色小胶囊
        UiDecor.barButtonOnBar((android.widget.Button) findViewById(R.id.btn_clear),
                this, UiDecor.KIND_DANGER, 11.5f);
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {            public void onClick(View v) {
                if (data.isEmpty()) { toast(s(R.string.trash_toast_empty)); return; }
                new HfDialogBuilder(TrashActivity.this)
                        .setTitle(s(R.string.trash_clear_title))
                        .setMessage(s(R.string.trash_clear_msg, data.size()))
                        .setPositiveButton(s(R.string.trash_clear_ok), new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                toast(s(Trash.clearAll() ? R.string.trash_cleared : R.string.trash_clear_fail));
                                reload();
                            }
                        })
                        .setNegativeButton(s(R.string.common_cancel), null)
                        .show();
            }
        });

        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(Trash.list());
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        if (data.isEmpty()) empty.setText(s(R.string.trash_empty) + "\n\n" + policyLine());
        title.setText(s(R.string.trash_title_count, data.size()));
        updateTotalSize();
    }

    /** v1.82.1：空状态直接摊开当前规则，避免「功能做了但找不到」 */
    private String policyLine() {
        TrashPolicy p = TrashPolicy.load(Trash.dir());
        if (!p.enabled) return s(R.string.trash_policy_off);
        StringBuilder sb = new StringBuilder(s(R.string.trash_policy_on));
        sb.append(p.maxDays > 0 ? s(R.string.trash_policy_days, p.maxDays) : s(R.string.trash_policy_no_days));
        sb.append(p.maxBytes > 0
                ? s(R.string.trash_policy_limit, p.maxBytes / (1024 * 1024))
                : s(R.string.trash_policy_no_limit));
        return sb.append(s(R.string.trash_policy_hint)).toString();
    }

    /** 目录条目的占用要递归算，放后台，避免回收站里有个大文件夹时进页面卡一下 */
    private void updateTotalSize() {
        final List<Trash.Entry> snap = new ArrayList<Trash.Entry>(data);
        new Thread(new Runnable() {
            public void run() {
                long total = 0;
                for (Trash.Entry e : snap) total += Trash.sizeOf(e.store);
                final String text = s(R.string.trash_title_size, snap.size(), FileOps.formatSize(total));
                runOnUiThread(new Runnable() {
                    public void run() { title.setText(text); }
                });
            }
        }).start();
    }

    /** v1.82.0：保留策略设置（v1.83.0 起实现抽到 TrashPolicyDialog，与设置页共用） */
    private void showPolicy() {
        TrashPolicyDialog.show(this, new Runnable() {
            public void run() { reload(); }
        });
    }

    private void showEntryOps(final Trash.Entry e) {
        String msg = s(R.string.trash_info_path, e.origPath)
                + s(R.string.trash_info_time, FileOps.formatTime(e.time))
                + s(R.string.trash_info_type, s(e.isDir ? R.string.trash_folder : R.string.trash_file));
        new HfDialogBuilder(this)
                .setTitle(e.name)
                .setMessage(msg)
                .setPositiveButton(s(R.string.trash_restore), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast(s(Trash.restore(e) ? R.string.trash_restored : R.string.trash_restore_fail));
                        reload();
                    }
                })
                .setNeutralButton(s(R.string.trash_purge), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast(s(Trash.purge(e) ? R.string.trash_purged : R.string.trash_purge_fail));
                        reload();
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String s(int id) { return getString(id); }
    private String s(int id, Object... args) { return getString(id, args); }

    private class TrashAdapter extends BaseAdapter {

        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = android.view.LayoutInflater.from(TrashActivity.this)
                        .inflate(R.layout.item_result, parent, false);
            }
            Trash.Entry e = data.get(i);
            TextView name = (TextView) convert.findViewById(R.id.name);
            TextView detail = (TextView) convert.findViewById(R.id.detail);
            name.setText((e.isDir ? s(R.string.trash_folder_tag) + " " : "") + e.name);
            File store = e.store;
            long size = 0;
            if (store != null && store.isFile()) size = store.length();
            detail.setText(s(R.string.trash_detail,
                    FileOps.formatTime(e.time),
                    e.isDir ? s(R.string.trash_dir) : FileOps.formatSize(size),
                    e.origPath));
            return convert;
        }
    }
}
