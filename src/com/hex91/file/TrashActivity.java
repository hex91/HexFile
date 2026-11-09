package com.hex91.file;

import android.app.AlertDialog;
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

/** 回收站管理：恢复 / 彻底删除 / 清空 */
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
        android.widget.Button btnPolicy = (android.widget.Button) findViewById(R.id.btn_policy);
        UiDecor.barButton(btnPolicy, this, UiDecor.KIND_SOFT, 11.5f);
        btnPolicy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPolicy(); }
        });
        UiDecor.gap(btnPolicy, this, 4f);

        // v1.76.0：顶栏「清空」按钮 → 危险色小胶囊
        UiDecor.barButton((android.widget.Button) findViewById(R.id.btn_clear),
                this, UiDecor.KIND_DANGER, 11.5f);
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {            public void onClick(View v) {
                if (data.isEmpty()) { toast("回收站已为空"); return; }
                new HfDialogBuilder(TrashActivity.this)
                        .setTitle("清空回收站")
                        .setMessage("将彻底删除全部 " + data.size() + " 项，无法恢复！")
                        .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                toast(Trash.clearAll() ? "已清空" : "清空失败");
                                reload();
                            }
                        })
                        .setNegativeButton("取消", null)
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
        title.setText("回收站 · " + data.size() + " 项");
        updateTotalSize();
    }

    /** 目录条目的占用要递归算，放后台，避免回收站里有个大文件夹时进页面卡一下 */
    private void updateTotalSize() {
        final List<Trash.Entry> snap = new ArrayList<Trash.Entry>(data);
        new Thread(new Runnable() {
            public void run() {
                long total = 0;
                for (Trash.Entry e : snap) total += Trash.sizeOf(e.store);
                final String text = "回收站 · " + snap.size() + " 项 · " + FileOps.formatSize(total);
                runOnUiThread(new Runnable() {
                    public void run() { title.setText(text); }
                });
            }
        }).start();
    }

    /** v1.82.0：保留策略设置 */
    private void showPolicy() {
        final TrashPolicy p = TrashPolicy.load(Trash.dir());

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 8, pad, 0);

        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("启用自动清理");
        cb.setTextSize(14);
        cb.setChecked(p.enabled);
        box.addView(cb);

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("超过保留天数或容量上限时，自动彻底删除最旧的条目。目录占用按递归计算。");
        hint.setTextSize(11);
        hint.setTextColor(ThemeManager.textSub(this));
        hint.setPadding(0, 2, 0, 8);
        box.addView(hint);

        android.widget.EditText etDays = new android.widget.EditText(this);
        etDays.setHint("保留天数（0 = 不限）");
        etDays.setTextSize(14);
        etDays.setSingleLine(true);
        etDays.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDays.setText(String.valueOf(p.maxDays));
        box.addView(etDays);

        android.widget.EditText etMb = new android.widget.EditText(this);
        etMb.setHint("容量上限 MB（0 = 不限）");
        etMb.setTextSize(14);
        etMb.setSingleLine(true);
        etMb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etMb.setText(String.valueOf(p.maxBytes / (1024 * 1024)));
        box.addView(etMb);

        new HfDialogBuilder(this)
                .setTitle("回收站保留策略")
                .setView(box)
                .setPositiveButton("保存并清理", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        p.enabled = cb.isChecked();
                        p.maxDays = parseInt(etDays.getText().toString(), TrashPolicy.DEFAULT_DAYS);
                        long mb = parseInt(etMb.getText().toString(),
                                (int) (TrashPolicy.DEFAULT_BYTES / (1024 * 1024)));
                        p.maxBytes = mb <= 0 ? 0 : mb * 1024L * 1024L;
                        if (!p.save(Trash.dir())) { toast("策略保存失败"); return; }
                        final TrashPolicy.Report rep = p.apply(Trash.dir(), System.currentTimeMillis());
                        toast(rep.summary());
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void showEntryOps(final Trash.Entry e) {
        String msg = "原路径: " + e.origPath
                + "\n删除时间: " + FileOps.formatTime(e.time)
                + "\n类型: " + (e.isDir ? "文件夹" : "文件");
        new HfDialogBuilder(this)
                .setTitle(e.name)
                .setMessage(msg)
                .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast(Trash.restore(e) ? "已恢复到原路径" : "恢复失败");
                        reload();
                    }
                })
                .setNeutralButton("彻底删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast(Trash.purge(e) ? "已彻底删除" : "删除失败");
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

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
            name.setText((e.isDir ? "[文件夹] " : "") + e.name);
            File store = e.store;
            long size = 0;
            if (store != null && store.isFile()) size = store.length();
            detail.setText(FileOps.formatTime(e.time) + "  ·  "
                    + (e.isDir ? "目录" : FileOps.formatSize(size))
                    + "  ·  " + e.origPath);
            return convert;
        }
    }
}
