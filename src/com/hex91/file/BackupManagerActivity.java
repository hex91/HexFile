package com.hex91.file;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.54.0 备份管理：扫描 HexFile/Backups 下所有备份目录（不论对应应用是否已安装），
 * 逐条提供「还原」与「删除备份」。解决此前「应用卸载后无法还原」的结构性缺口——
 * 还原入口不再依赖已安装应用列表，未安装的应用也能从备份一键救回。
 */
public class BackupManagerActivity extends BaseActivity {

    private List<BackupRestore.BackupEntry> entries = new ArrayList<>();
    private BackupAdapter adapter;
    private TextView emptyView;
    private TextView hintView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("备份管理");
        title.setTextSize(20);
        title.setPadding((int) (14 * d), (int) (14 * d), (int) (14 * d), (int) (6 * d));
        title.setTextColor(ThemeManager.text(this));
        root.addView(title);

        hintView = new TextView(this);
        hintView.setText("列出所有已备份的应用（含已卸载）。还原需 Root：重装 APK + 恢复数据目录。");
        hintView.setTextSize(12);
        hintView.setPadding((int) (14 * d), 0, (int) (14 * d), (int) (8 * d));
        hintView.setTextColor(ThemeManager.textSub(this));
        root.addView(hintView);

        ListView list = new ListView(this);
        adapter = new BackupAdapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(list, lp);

        emptyView = new TextView(this);
        emptyView.setText("暂无备份。可在「应用管理 → 应用菜单 → 备份应用」创建备份。");
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding((int) (20 * d), (int) (40 * d), (int) (20 * d), 0);
        emptyView.setTextColor(ThemeManager.textSub(this));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        setContentView(root);
        loadBackups();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBackups();
    }

    private void loadBackups() {
        new Thread(new Runnable() {
            public void run() {
                final List<BackupRestore.BackupEntry> list = BackupRestore.listBackups(
                        BackupManagerActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        entries.clear();
                        entries.addAll(list);
                        adapter.notifyDataSetChanged();
                        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }, "bk-list").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 还原某个备份（后台线程 + 进度框 + 细分失败提示） */
    private void doRestore(final BackupRestore.BackupEntry e) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在还原 " + e.label + " …");
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final BackupRestore.RestoreOutcome o = BackupRestore.restore(e, getPackageName());
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(o.message);
                        loadBackups();   // 刷新（已安装状态可能变化）
                    }
                });
            }
        }, "bk-restore").start();
    }

    /** 删除某个备份目录（二次确认 + 递归删除） */
    private void confirmDelete(final BackupRestore.BackupEntry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除备份")
                .setMessage("确定删除 " + e.label + " 的备份吗？\n目录：" + e.dir.getPath()
                        + "\n此操作不可恢复。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int w) {
                        deleteDir(e.dir);
                        toast("已删除备份：" + e.label);
                        loadBackups();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean deleteDir(File dir) {
        if (dir == null) return false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        return dir.delete();
    }

    private class BackupAdapter extends BaseAdapter {
        public int getCount() { return entries.size(); }
        public Object getItem(int i) { return entries.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            final BackupRestore.BackupEntry e = entries.get(i);
            float d = getResources().getDisplayMetrics().density;

            LinearLayout row;
            ImageView icon;
            TextView title, sub;
            Button btnRestore, btnDelete;
            if (convert == null) {
                row = new LinearLayout(BackupManagerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

                icon = new ImageView(BackupManagerActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        (int) (42 * d), (int) (42 * d));
                ilp.rightMargin = (int) (12 * d);
                icon.setLayoutParams(ilp);
                row.addView(icon);

                LinearLayout col = new LinearLayout(BackupManagerActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                col.setLayoutParams(clp);

                title = new TextView(BackupManagerActivity.this);
                title.setTextSize(15);
                title.setTextColor(ThemeManager.text(BackupManagerActivity.this));
                col.addView(title);

                sub = new TextView(BackupManagerActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(ThemeManager.textSub(BackupManagerActivity.this));
                col.addView(sub);
                row.addView(col);

                btnRestore = new Button(BackupManagerActivity.this);
                btnRestore.setText("还原");
                btnRestore.setTextSize(13);
                row.addView(btnRestore);

                btnDelete = new Button(BackupManagerActivity.this);
                btnDelete.setText("删除");
                btnDelete.setTextSize(13);
                row.addView(btnDelete);
            } else {
                row = (LinearLayout) convert;
                icon = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                title = (TextView) col.getChildAt(0);
                sub = (TextView) col.getChildAt(1);
                btnRestore = (Button) row.getChildAt(2);
                btnDelete = (Button) row.getChildAt(3);
            }

            // 图标：已安装取应用图标，否则用 HexFile 图标占位
            Drawable dr = null;
            if (e.installed) {
                try {
                    dr = getPackageManager().getApplicationIcon(e.pkg);
                } catch (PackageManager.NameNotFoundException ignore) {
                    dr = null;
                }
            }
            if (dr == null) dr = getResources().getDrawable(R.drawable.ic_launcher);
            icon.setImageDrawable(dr);

            title.setText(e.label);
            String data = e.dataTar != null ? "数据:有" : "数据:无";
            sub.setText((e.installed ? "已安装" : "未安装") + " · " + e.apks.length + " 个APK · " + data);

            btnRestore.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { doRestore(e); }
            });
            btnDelete.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDelete(e); }
            });
            return row;
        }
    }
}
