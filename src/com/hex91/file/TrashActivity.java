package com.hex91.file;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_trash);

        ListView lv = (ListView) findViewById(R.id.list_trash);
        empty = (TextView) findViewById(R.id.tv_empty);
        adapter = new TrashAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                showEntryOps(data.get(i));
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (data.isEmpty()) { toast("回收站已为空"); return; }
                new AlertDialog.Builder(TrashActivity.this)
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
    }

    private void showEntryOps(final Trash.Entry e) {
        String msg = "原路径: " + e.origPath
                + "\n删除时间: " + FileOps.formatTime(e.time)
                + "\n类型: " + (e.isDir ? "文件夹" : "文件");
        new AlertDialog.Builder(this)
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
