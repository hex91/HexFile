package com.hex91.file;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.45.0 应用管理：已安装应用列表（图标/名称/包名/版本）+ 提取安装包。
 * - 列表：PackageManager.getInstalledPackages（Manifest 已加 QUERY_ALL_PACKAGES，
 *   targetSdk 30 包可见性限制下仍可查全部），按应用名排序，顶部搜索过滤
 * - 图标：LruCache + 后台 loadIcon + tag 校验（复用防串图，同 ThumbLoader 思路）
 * - 提取安装包：sourceDir 位于 /data/app（root 区）→ FileOps.copyOverwrite
 *   （内部 File API 失败自动走 RootIO su cp -a，v1.44.0 通道）；导出到
 *   /sdcard/HexFile/Apps/<应用名>_<版本>.apk
 */
public class AppManagerActivity extends BaseActivity {

    /** 列表行模型 */
    static class AppRow {
        String label;
        String pkg;
        String version;
        String sourceDir;
        String[] splitSourceDirs;   // v1.51.0：split 分包 APK 绝对路径（非空即 App Bundle 安装）
        long size;
        boolean system;   // v1.47.0：是否系统应用（含被更新的系统应用）

        /** 导出基名（清洗非法字符，不含扩展名）；供单 APK 与分包文件夹共用 */
        static String exportBaseName(String label, String version) {
            String name = (label + "_" + version).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            if (name.length() > 80) name = name.substring(0, 80);
            return name;
        }

        /** 导出文件名：应用名_版本.apk（清洗非法字符；放 AppRow 避免类初始化器，桌面可测） */
        static String exportFileName(String label, String version) {
            return exportBaseName(label, version) + ".apk";
        }
    }

    // v1.47.0 筛选模式
    private static final int FILTER_ALL = 0, FILTER_THIRD = 1, FILTER_SYSTEM = 2;
    private int filterMode = FILTER_ALL;
    private TextView[] chips = new TextView[3];

    private final List<AppRow> all = new ArrayList<>();
    private final List<AppRow> shown = new ArrayList<>();
    private AppAdapter adapter;
    private EditText searchBox;
    private TextView countView;
    private boolean mFirstResume = true;   // v1.51.0：首帧 onResume 不重复加载（onCreate 已加载）
    // 惰性初始化（桌面测试跑 AppRow.exportFileName 会触发外部类 <clinit>，不能在静态字段里 new Android 桩对象）
    private static LruCache<String, Drawable> ICONS;
    private static ExecutorService ICON_POOL;

    private static LruCache<String, Drawable> icons() {
        if (ICONS == null) ICONS = new LruCache<String, Drawable>(256);
        return ICONS;
    }

    private static ExecutorService iconPool() {
        if (ICON_POOL == null) ICON_POOL = Executors.newSingleThreadExecutor();
        return ICON_POOL;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        searchBox = new EditText(this);
        searchBox.setHint("搜索应用名 / 包名");
        searchBox.setSingleLine(true);
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, 0);
        root.addView(searchBox);

        // v1.47.0：筛选条 全部 / 第三方 / 系统
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] chipNames = {"全部", "第三方", "系统"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(chipNames[i]);
            c.setTextSize(13);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0,
                    (int) (6 * getResources().getDisplayMetrics().density));
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    filterMode = idx;
                    styleChips();
                    refilter();
                }
            });
            chipsRow.addView(c, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            chips[i] = c;
        }
        styleChips();
        root.addView(chipsRow);

        countView = new TextView(this);
        countView.setTextColor(ThemeManager.textSub(this));
        countView.setTextSize(12);
        countView.setPadding(p, 0, p, (int) (4 * getResources().getDisplayMetrics().density));
        root.addView(countView);
        ListView lv = new ListView(this);
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        setContentView(root);

        adapter = new AppAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                showAppMenu(shown.get(pos));
            }
        });
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refilter(); }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mFirstResume) {
            mFirstResume = false;   // onCreate 已加载，首帧不重复
        } else {
            loadApps();             // 从卸载/应用信息返回后刷新列表
        }
    }

    private void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                List<AppRow> rows = new ArrayList<>();
                try {
                    List<PackageInfo> pis = getPackageManager().getInstalledPackages(
                            android.content.pm.PackageManager.GET_META_DATA);
                    for (PackageInfo pi : pis) {
                        if (pi == null || pi.applicationInfo == null) continue;
                        AppRow r = new AppRow();
                        r.pkg = pi.packageName;
                        r.label = (String) pi.applicationInfo.loadLabel(getPackageManager());
                        if (r.label == null || r.label.isEmpty()) r.label = r.pkg;
                        r.version = pi.versionName == null ? "?" : pi.versionName;
                        r.sourceDir = pi.applicationInfo.sourceDir;
                        r.splitSourceDirs = pi.applicationInfo.splitSourceDirs;   // v1.51.0：分包路径（可能为 null）
                        r.system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        File src = r.sourceDir == null ? null : new File(r.sourceDir);
                        r.size = src == null ? 0 : src.length();   // root 区可能为 0，导出后为准
                        rows.add(r);
                    }
                } catch (Exception ignored) {}
                Collections.sort(rows, new Comparator<AppRow>() {
                    public int compare(AppRow a, AppRow b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                synchronized (all) {
                    all.clear();
                    all.addAll(rows);
                }
                runOnUiThread(new Runnable() {
                    public void run() { refilter(); }
                });
            }
        }, "app-list").start();
    }

    /** v1.47.0：筛选 chip 选中态样式（选中深色底白字，未选中透明底灰字） */
    private void styleChips() {
        for (int i = 0; i < 3; i++) {
            if (i == filterMode) {
                chips[i].setBackgroundColor(ThemeManager.accent(this));
                chips[i].setTextColor(ThemeManager.onAccent(this));
            } else {
                chips[i].setBackgroundColor(0x00000000);
                chips[i].setTextColor(ThemeManager.textSub(this));
            }
        }
    }

    private void refilter() {
        int third, sys;
        synchronized (all) {
            shown.clear();
            String kw = searchBox.getText().toString().trim().toLowerCase();
            third = 0; sys = 0;
            for (AppRow r : all) {
                if (r.system) sys++; else third++;
                if (filterMode == FILTER_THIRD && r.system) continue;
                if (filterMode == FILTER_SYSTEM && !r.system) continue;
                if (kw.isEmpty() || r.label.toLowerCase().contains(kw)
                        || r.pkg.toLowerCase().contains(kw)) {
                    shown.add(r);
                }
            }
        }
        adapter.notifyDataSetChanged();
        String mode = filterMode == FILTER_ALL ? "全部"
                : filterMode == FILTER_THIRD ? "第三方" : "系统";
        countView.setText("已安装应用[" + mode + "]: " + shown.size() + " / " + all.size()
                + "（第三方 " + third + " · 系统 " + sys + "）");
    }

    /** 导出文件名：应用名_版本.apk（清洗非法字符；实现放 AppRow，此为转发，桌面测试指向 AppRow 版） */
    static String exportFileName(String label, String version) {
        return AppRow.exportFileName(label, version);
    }

    /** v1.51.0：应用操作菜单（自定义视图，避免三按钮上限；含卸载/清理/导出/信息/复制） */
    private void showAppMenu(final AppRow r) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (4 * d));

        TextView header = new TextView(this);
        StringBuilder hs = new StringBuilder();
        hs.append("包名: ").append(r.pkg).append("\n版本: ").append(r.version)
                .append("\n类型: ").append(r.system ? "系统应用" : "第三方应用");
        if (r.size > 0) hs.append("\n大小: ").append(FileOps.formatSize(r.size));
        hs.append("\n路径: ").append(r.sourceDir);
        if (r.splitSourceDirs != null && r.splitSourceDirs.length > 0)
            hs.append("\n分包: ").append(r.splitSourceDirs.length).append(" 个（导出为文件夹）");
        header.setText(hs.toString());
        header.setTextSize(13);
        header.setTextColor(ThemeManager.textSub(this));
        box.addView(header);

        addActionRow(box, "提取安装包", new View.OnClickListener() {
            public void onClick(View v) { exportApk(r); }
        });
        addActionRow(box, "清理数据缓存", new View.OnClickListener() {
            public void onClick(View v) { clearData(r); }
        });
        addActionRow(box, "卸载应用", new View.OnClickListener() {
            public void onClick(View v) { confirmUninstall(r); }
        });
        addActionRow(box, "应用信息", new View.OnClickListener() {
            public void onClick(View v) { openAppInfo(r); }
        });
        addActionRow(box, "复制包名", new View.OnClickListener() {
            public void onClick(View v) { copyPkg(r); }
        });

        new AlertDialog.Builder(this)
                .setTitle(r.label)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addActionRow(LinearLayout box, String name, View.OnClickListener l) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(15);
        t.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        t.setTextColor(ThemeManager.text(this));
        t.setOnClickListener(l);
        box.addView(t);
    }

    private void openAppInfo(final AppRow r) {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + r.pkg)));
        } catch (Exception e) {
            toast("无法打开应用信息");
        }
    }

    private void copyPkg(final AppRow r) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pkg", r.pkg));
            toast("已复制包名");
        }
    }

    /** v1.51.0：清理应用数据与缓存（需 Root；pm clear 同时清 data 与 cache） */
    private void clearData(final AppRow r) {
        if (r.pkg.equals(getPackageName())) { toast("不能清理本应用数据"); return; }
        if (!RootUtil.detect() || !RootUtil.isRootAvailable()) {
            toast("清理数据需要 Root 权限");
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在清理 " + r.label + " 的数据与缓存 …");
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = RootUtil.run("pm clear " + r.pkg);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(ok ? "已清理 " + r.label + " 的数据与缓存" : "清理失败");
                    }
                });
            }
        }, "app-clear").start();
    }

    /** v1.51.0：卸载应用（Root 走 su pm uninstall --user 0；无 Root 走系统卸载器） */
    private void confirmUninstall(final AppRow r) {
        new AlertDialog.Builder(this)
                .setTitle("卸载应用")
                .setMessage("确定卸载 " + r.label + "（" + r.pkg + "）？此操作不可撤销。")
                .setPositiveButton("卸载", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { uninstallApp(r); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uninstallApp(final AppRow r) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在卸载 " + r.label + " …");
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final boolean rooted = RootUtil.detect() && RootUtil.isRootAvailable();
                if (rooted) {
                    final boolean ok = RootUtil.run("pm uninstall --user 0 " + r.pkg);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            toast(ok ? "已卸载 " + r.label : "卸载失败（可能被系统保护）");
                            loadApps();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { pd.dismiss(); } catch (Exception ignored) {}
                            try {
                                startActivity(new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + r.pkg)));
                            } catch (Exception e) {
                                toast("无法启动卸载");
                            }
                        }
                    });
                }
            }
        }, "app-uninstall").start();
    }

    private void exportApk(final AppRow r) {
        if (r.sourceDir == null) { toast("无法获取 APK 路径"); return; }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在导出 " + r.label + " …");
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                // v1.51.0：split 分包 → 导出为文件夹（base.apk + 各 split_*.apk）；
                // 单 APK → 保持原 <应用名>_<版本>.apk 行为
                final boolean split = r.splitSourceDirs != null && r.splitSourceDirs.length > 0;
                final String dstPath;
                boolean ok;
                if (!split) {
                    File dstDir = new File(Environment.getExternalStorageDirectory(), "HexFile/Apps");
                    File dst = new File(dstDir, exportFileName(r.label, r.version));
                    ok = FileOps.copyOverwrite(new File(r.sourceDir), dst);
                    dstPath = dst.getPath();
                } else {
                    File dstDir = new File(Environment.getExternalStorageDirectory(),
                            "HexFile/Apps/" + AppRow.exportBaseName(r.label, r.version));
                    ok = dstDir.mkdirs() || dstDir.isDirectory();
                    if (ok) {
                        File base = new File(r.sourceDir);
                        ok = FileOps.copyOverwrite(base, new File(dstDir, base.getName()));
                        for (String s : r.splitSourceDirs) {
                            File sf = new File(s);
                            if (!FileOps.copyOverwrite(sf, new File(dstDir, sf.getName()))) {
                                ok = false;
                                break;
                            }
                        }
                    }
                    dstPath = dstDir.getPath();
                }
                final boolean fok = ok;
                final boolean fsplit = split;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        if (fok) {
                            String tail = fsplit ? "（含 " + (r.splitSourceDirs.length + 1)
                                    + " 个 APK 的文件夹）" : "";
                            toast("已导出到 " + dstPath + tail);
                        } else {
                            toast("导出失败（需要 Root 授权）");
                        }
                    }
                });
            }
        }, "apk-export").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            final AppRow r = shown.get(i);
            LinearLayout row;
            final ImageView icon;
            TextView title, sub;
            if (convert == null) {
                float d = getResources().getDisplayMetrics().density;
                row = new LinearLayout(AppManagerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = (int) (10 * d);
                row.setPadding(pad, pad, pad, pad);
                icon = new ImageView(AppManagerActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        (int) (42 * d), (int) (42 * d));
                ilp.rightMargin = (int) (12 * d);
                icon.setLayoutParams(ilp);
                row.addView(icon);
                LinearLayout col = new LinearLayout(AppManagerActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                title = new TextView(AppManagerActivity.this);
                title.setTextSize(15);
                title.setTextColor(ThemeManager.text(AppManagerActivity.this));
                sub = new TextView(AppManagerActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(ThemeManager.textSub(AppManagerActivity.this));
                col.addView(title);
                col.addView(sub);
                row.addView(col, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setTag(new View[]{title, sub});
            } else {
                row = (LinearLayout) convert;
                icon = (ImageView) row.getChildAt(0);
                View[] tvs = (View[]) row.getTag();
                title = (TextView) tvs[0];
                sub = (TextView) tvs[1];
            }
            title.setText(r.label);
            sub.setText(r.pkg + "  " + r.version + (r.size > 0
                    ? "  " + FileOps.formatSize(r.size) : "") + (r.system ? "  · 系统" : ""));

            // 图标：缓存命中即设；否则后台 loadIcon，tag 校验防复用串图
            Drawable c = icons().get(r.pkg);
            if (c != null) {
                icon.setImageDrawable(c);
            } else {
                icon.setTag(r.pkg);
                icon.setImageDrawable(null);
                iconPool().execute(new Runnable() {
                    public void run() {
                        Drawable dr = null;
                        try {
                            dr = getPackageManager().getApplicationIcon(r.pkg);
                        } catch (Exception ignored) {}
                        final Drawable fd = dr;
                        if (fd != null) icons().put(r.pkg, fd);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (r.pkg.equals(icon.getTag())) icon.setImageDrawable(fd);
                            }
                        });
                    }
                });
            }
            return row;
        }
    }
}
