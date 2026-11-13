package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.Trash;
import com.hex91.file.fs.TrashPolicy;
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
        if (data.isEmpty()) empty.setText("回收站为空\n\n" + policyLine());
        title.setText("回收站 · " + data.size() + " 项");
        updateTotalSize();
    }

    /** v1.82.1：空状态直接摊开当前规则，避免「功能做了但找不到」 */
    private String policyLine() {
        TrashPolicy p = TrashPolicy.load(Trash.dir());
        if (!p.enabled) return "自动清理：关闭（点右上角「⚙ 保留策略」设置）";
        StringBuilder sb = new StringBuilder("自动清理：开启");
        sb.append(p.maxDays > 0 ? " · 保留 " + p.maxDays + " 天" : " · 不限天数");
        sb.append(p.maxBytes > 0
                ? " · 上限 " + (p.maxBytes / (1024 * 1024)) + " MB"
                : " · 不限容量");
        return sb.append("\n（点右上角「⚙ 保留策略」修改）").toString();
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

    /** v1.82.0：保留策略设置（v1.83.0 起实现抽到 TrashPolicyDialog，与设置页共用） */
    private void showPolicy() {
        TrashPolicyDialog.show(this, new Runnable() {
            public void run() { reload(); }
        });
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
