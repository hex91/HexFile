package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.utils.BackupRestore;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.RootUtil;
import com.hex91.file.fs.WorkDir;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileFilter;
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
    public static class AppRow {
        String label;
        String pkg;
        String version;
        String sourceDir;
        String[] splitSourceDirs;   // v1.51.0：split 分包 APK 绝对路径（非空即 App Bundle 安装）
        String dataDir;             // v1.53.0：应用私有数据目录（/data/data/<pkg>）
        long size;
        boolean system;   // v1.47.0：是否系统应用（含被更新的系统应用）
        boolean enabled;  // v1.53.0：是否已启用（禁用即「冻结」）

        /** 导出基名（清洗非法字符，不含扩展名）；供单 APK 与分包文件夹共用 */
        static String exportBaseName(String label, String version) {
            String name = (label + "_" + version).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            if (name.length() > 80) name = name.substring(0, 80);
            return name;
        }

        /** 导出文件名：应用名_版本.apk（清洗非法字符；放 AppRow 避免类初始化器，桌面可测） */
        public static String exportFileName(String label, String version) {
            return exportBaseName(label, version) + ".apk";
        }
    }

    private static final String TAG = "HexFile";

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
        searchBox.setHint(s(R.string.am_search_hint));
        searchBox.setSingleLine(true);
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, 0);
        root.addView(searchBox);

        // v1.47.0：筛选条 全部 / 第三方 / 系统
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] chipNames = {s(R.string.am_filter_all), s(R.string.am_filter_third),
                s(R.string.am_filter_system)};
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
                        r.dataDir = pi.applicationInfo.dataDir;                 // v1.53.0：数据目录（可能含加密路径）
                        r.system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        r.enabled = pi.applicationInfo.enabled;                  // v1.53.0：冻结状态
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
        String mode = filterMode == FILTER_ALL ? s(R.string.am_filter_all)
                : filterMode == FILTER_THIRD ? s(R.string.am_filter_third)
                : s(R.string.am_filter_system);
        countView.setText(s(R.string.am_count, mode, shown.size(), all.size(), third, sys));
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
        hs.append(s(R.string.am_hdr_pkg, r.pkg)).append('\n')
                .append(s(R.string.am_hdr_version, r.version)).append('\n')
                .append(s(R.string.am_hdr_type, r.system
                        ? s(R.string.am_type_system) : s(R.string.am_type_third)));
        if (r.size > 0) hs.append('\n').append(s(R.string.am_hdr_size, FileOps.formatSize(r.size)));
        hs.append('\n').append(s(R.string.am_hdr_path, r.sourceDir));
        if (r.splitSourceDirs != null && r.splitSourceDirs.length > 0) {
            hs.append('\n').append(s(R.string.am_hdr_splits, r.splitSourceDirs.length));
        }
        if (!r.enabled) hs.append('\n').append(s(R.string.am_hdr_frozen));
        header.setText(hs.toString());
        header.setTextSize(13);
        header.setTextColor(ThemeManager.textSub(this));
        box.addView(header);

        addActionRow(box, s(R.string.am_act_export), new View.OnClickListener() {
            public void onClick(View v) { exportApk(r); }
        });
        addActionRow(box, s(R.string.am_act_backup), new View.OnClickListener() {
            public void onClick(View v) { backupApp(r); }
        });
        addActionRow(box, s(R.string.am_act_restore), new View.OnClickListener() {
            public void onClick(View v) { restoreApp(r); }
        });
        addActionRow(box, r.enabled ? s(R.string.am_act_freeze) : s(R.string.am_act_unfreeze),
                new View.OnClickListener() {
            public void onClick(View v) { toggleFreeze(r); }
        });
        addActionRow(box, s(R.string.am_act_perms), new View.OnClickListener() {
            public void onClick(View v) { showPermissions(r); }
        });
        addActionRow(box, s(R.string.am_act_clear), new View.OnClickListener() {
            public void onClick(View v) { clearData(r); }
        });
        addActionRow(box, s(R.string.am_act_uninstall), new View.OnClickListener() {
            public void onClick(View v) { confirmUninstall(r); }
        });
        addActionRow(box, s(R.string.am_act_appinfo), new View.OnClickListener() {
            public void onClick(View v) { openAppInfo(r); }
        });
        addActionRow(box, s(R.string.am_act_copypkg), new View.OnClickListener() {
            public void onClick(View v) { copyPkg(r); }
        });

        new HfDialogBuilder(this)
                .setTitle(r.label)
                .setView(box)
                .setNegativeButton(s(R.string.common_close), null)
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
            toast(s(R.string.am_err_appinfo));
        }
    }

    private void copyPkg(final AppRow r) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pkg", r.pkg));
            toast(s(R.string.am_pkg_copied));
        }
    }

    /** v1.51.0：清理应用数据与缓存（需 Root；pm clear 同时清 data 与 cache） */
    private void clearData(final AppRow r) {
        if (r.pkg.equals(getPackageName())) { toast(s(R.string.am_err_clear_self)); return; }
        if (!RootUtil.detect() || !RootUtil.isRootAvailable()) {
            toast(s(R.string.am_err_root_clear));
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.am_clearing, r.label));
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = RootUtil.run("pm clear " + r.pkg);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(ok ? s(R.string.am_cleared, r.label) : s(R.string.am_clear_fail));
                    }
                });
            }
        }, "app-clear").start();
    }

    /** v1.51.0：卸载应用（Root 走 su pm uninstall --user 0；无 Root 走系统卸载器） */
    private void confirmUninstall(final AppRow r) {
        new HfDialogBuilder(this)
                .setTitle(s(R.string.am_uninstall_title))
                .setMessage(s(R.string.am_uninstall_msg, r.label, r.pkg))
                .setPositiveButton(s(R.string.am_uninstall_ok), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { uninstallApp(r); }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    private void uninstallApp(final AppRow r) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.am_uninstalling, r.label));
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
                            toast(ok ? s(R.string.am_uninstalled, r.label) : s(R.string.am_uninstall_fail));
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
                                toast(s(R.string.am_err_uninstall_start));
                            }
                        }
                    });
                }
            }
        }, "app-uninstall").start();
    }

    private void exportApk(final AppRow r) {
        if (r.sourceDir == null) { toast(s(R.string.am_err_no_apk_path)); return; }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.am_exporting, r.label));
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
                    File dstDir = new File(WorkDir.base(), "Apps");
                    File dst = new File(dstDir, exportFileName(r.label, r.version));
                    ok = FileOps.copyOverwrite(new File(r.sourceDir), dst);
                    dstPath = dst.getPath();
                } else {
                    File dstDir = new File(WorkDir.base(),
                            "Apps/" + AppRow.exportBaseName(r.label, r.version));
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
                            toast(fsplit
                                    ? s(R.string.am_exported_splits, dstPath,
                                            r.splitSourceDirs.length + 1)
                                    : s(R.string.am_exported_to, dstPath));
                        } else {
                            toast(s(R.string.am_export_fail));
                        }
                    }
                });
            }
        }, "apk-export").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 取资源文案（F-14/12：本页文案全部走 values/values-en） */
    private String s(int id) {
        return getString(id);
    }

    private String s(int id, Object... args) {
        return getString(id, args);
    }

    private boolean rootReady() {
        return RootUtil.detect() && RootUtil.isRootAvailable();
    }

    // ===== v1.53.0 应用管理二期 =====

    /** 冻结 / 解冻（Root：pm disable-user / pm enable）。禁用即「冻结」应用。 */
    private void toggleFreeze(final AppRow r) {
        if (r.pkg.equals(getPackageName())) { toast(s(R.string.am_err_freeze_self)); return; }
        if (!rootReady()) { toast(s(R.string.am_err_root_freeze)); return; }
        final boolean freezing = r.enabled;   // 当前启用 → 执行禁用（冻结）
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(freezing ? s(R.string.am_freezing, r.label)
                : s(R.string.am_unfreezing, r.label));
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final String cmd = (freezing ? "pm disable-user --user 0 "
                        : "pm enable --user 0 ") + r.pkg;
                final boolean ok = RootUtil.run(cmd);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        if (ok) toast(freezing ? s(R.string.am_frozen, r.label)
                                : s(R.string.am_unfrozen, r.label));
                        else toast(freezing ? s(R.string.am_freeze_fail)
                                : s(R.string.am_unfreeze_fail));
                        loadApps();   // 刷新启用状态
                    }
                });
            }
        }, "app-freeze").start();
    }

    /** 权限管理：列出声明权限 + 授予状态，提供 Root 批量授予 / 撤销危险权限。 */
    private void showPermissions(final AppRow r) {
        PackageInfo pi;
        try {
            pi = getPackageManager().getPackageInfo(r.pkg,
                    android.content.pm.PackageManager.GET_PERMISSIONS);
        } catch (Exception e) { toast(s(R.string.am_err_read_perms)); return; }
        final String[] perms = pi.requestedPermissions;
        final int[] flags = pi.requestedPermissionsFlags;
        if (perms == null || perms.length == 0) { toast(s(R.string.am_no_perms)); return; }

        float d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (4 * d));
        for (int i = 0; i < perms.length; i++) {
            final String perm = perms[i];
            String level = s(R.string.am_perm_level_normal);
            String label = perm;
            try {
                PermissionInfo info = getPackageManager().getPermissionInfo(perm, 0);
                if (info.loadLabel(getPackageManager()) != null) label = info.loadLabel(getPackageManager()).toString();
                int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base == PermissionInfo.PROTECTION_DANGEROUS) level = s(R.string.am_perm_level_dangerous);
                else if (base == PermissionInfo.PROTECTION_SIGNATURE) level = s(R.string.am_perm_level_signature);
                else if (base == PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM) {
                level = s(R.string.am_perm_level_sig_system);
            }
            } catch (Exception ignored) {}
            boolean granted = (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            TextView t = new TextView(this);
            t.setTextSize(13);
            t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            t.setText(s(R.string.am_perm_row, label, perm, level,
                    granted ? s(R.string.am_perm_granted) : s(R.string.am_perm_denied)));
            t.setTextColor(granted ? ThemeManager.accent(this) : ThemeManager.textSub(this));
            box.addView(t);
        }
        sv.addView(box);

        new HfDialogBuilder(this)
                .setTitle(s(R.string.am_perms_title, r.label))
                .setView(sv)
                .setPositiveButton(s(R.string.am_perm_grant_all), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        grantOrRevokeDangerous(r, perms, true);
                    }
                })
                .setNegativeButton(s(R.string.am_perm_revoke_all), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        grantOrRevokeDangerous(r, perms, false);
                    }
                })
                .setNeutralButton(s(R.string.common_close), null)
                .show();
    }

    private void grantOrRevokeDangerous(final AppRow r, final String[] perms, final boolean grant) {
        if (!rootReady()) { toast(s(R.string.am_err_root_grant)); return; }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(grant ? s(R.string.am_granting) : s(R.string.am_revoking));
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                int done = 0;
                for (String p : perms) {
                    try {
                        PermissionInfo info = getPackageManager().getPermissionInfo(p, 0);
                        int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                        if (base != PermissionInfo.PROTECTION_DANGEROUS) continue;
                        String cmd = (grant ? "pm grant " : "pm revoke ") + r.pkg + " " + p;
                        if (RootUtil.run(cmd)) done++;
                    } catch (Exception ignored) {}
                }
                final int fdone = done;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(grant ? s(R.string.am_granted_n, fdone) : s(R.string.am_revoked_n, fdone));
                    }
                });
            }
        }, "perm-grant").start();
    }

    /** 备份应用：APK（含 split 分包）复制到 HexFile/Backups/<pkg>/；Root 下额外打包数据目录。 */
    private void backupApp(final AppRow r) {
        if (r.sourceDir == null) { toast(s(R.string.am_err_no_apk_path)); return; }
        final File dir = new File(WorkDir.base(), "Backups/" + r.pkg);
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.am_backing_up, r.label));
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                boolean ok = dir.mkdirs() || dir.isDirectory();
                int apkCount = 0;
                if (ok) {
                    File base = new File(r.sourceDir);
                    if (FileOps.copyOverwrite(base, new File(dir, base.getName()))) apkCount++;
                    if (r.splitSourceDirs != null) {
                        for (String s : r.splitSourceDirs) {
                            File sf = new File(s);
                            if (FileOps.copyOverwrite(sf, new File(dir, sf.getName()))) apkCount++;
                        }
                    }
                }
                final boolean rooted = rootReady();
                boolean dataOk = false;
                if (ok && rooted && r.dataDir != null) {
                    // 注意：不要用 app 进程 stat dataDir —— 非 root 读不到别的 App 私有目录。
                    // 也不要直接在 su 下归档 /data/user/0/<pkg>：KernelSU 应用内 su 处于隔离
                    // 挂载命名空间，/data/data 是刚建的空壳目录，tar 会报 No such file。
                    // 统一经 /proc/1/root 前缀访问 init 命名空间中的真实数据目录。
                    String rel = "data/user/0/" + r.pkg;
                    File gz = new File(dir, "data.tar.gz");
                    File plain = new File(dir, "data.tar");
                    gz.delete();
                    plain.delete();
                    dataOk = tarDataArchive(rel, gz, true) || tarDataArchive(rel, plain, false);
                }
                final int fApk = apkCount;
                final boolean fData = dataOk;
                final boolean fRoot = rooted;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        int key = !fRoot ? R.string.am_backed_up_no_root
                                : (fData ? R.string.am_backed_up_with_data
                                        : R.string.am_backed_up_data_fail);
                        toast(s(key, r.label, fApk, dir.getPath()));
                    }
                });
            }
        }, "app-backup").start();
    }

    /**
     * 归档应用数据目录到备份目录。
     * 应用内 su（KernelSU）处于隔离挂载命名空间，/data/data 是空壳 →
     * 优先经 /proc/1/root 前缀访问 init 命名空间的真实数据；无隔离的环境回退直接归档。
     * 产物先落 /data/local/tmp（su 命名空间对 sdcard 可见性因 ROM 而异），
     * 再由应用侧拷入备份目录（/data/local/tmp 对其它用户有 +x 遍历权，644 文件可读）。
     */
    private boolean tarDataArchive(String rel, File out, boolean gzip) {
        String flag = gzip ? "-czf" : "-cf";
        String tmp = "/data/local/tmp/hfdata" + (gzip ? ".tar.gz" : ".tar");
        RootUtil.run("rm -f '" + tmp + "' 2>/dev/null");
        RootUtil.RunResult r = RootUtil.runCapture(
                "tar " + flag + " '" + tmp + "' -C /proc/1/root '" + rel + "' 2>&1", 600);
        File tf = new File(tmp);
        boolean ok = r != null && r.exit == 0 && tf.isFile() && tf.length() >= 512;
        if (!ok) {
            // 回退：无命名空间隔离的环境（如 Magisk）直接归档
            Log.w(TAG, "backup " + out.getName() + " via /proc/1/root failed, fallback direct: "
                    + (r == null ? "timeout" : r.out));
            r = RootUtil.runCapture(
                    "tar " + flag + " '" + tmp + "' -C / '" + rel + "' 2>&1", 600);
            ok = r != null && r.exit == 0 && tf.isFile() && tf.length() >= 512;
        }
        if (ok) {
            RootUtil.run("chmod 644 '" + tmp + "' 2>/dev/null");
            ok = FileOps.copyOverwrite(tf, out) && out.isFile() && out.length() >= 512;
        }
        RootUtil.run("rm -f '" + tmp + "' 2>/dev/null");
        if (!ok) Log.e(TAG, "backup data archive failed: " + out.getName()
                + (r == null ? " timeout" : " " + r.out));
        return ok;
    }

    /** 还原应用：从 HexFile/Backups/<pkg>/ 重装 APK（含 split），Root 下恢复数据目录。 */
    private void restoreApp(final AppRow r) {
        final File dir = new File(WorkDir.base(), "Backups/" + r.pkg);
        if (!dir.isDirectory()) { toast(s(R.string.am_err_no_backup, r.label)); return; }
        File[] apks = dir.listFiles(new FileFilter() {
            public boolean accept(File f) { return f.isFile() && f.getName().endsWith(".apk"); }
        });
        if (apks == null || apks.length == 0) { toast(s(R.string.am_err_backup_no_apk)); return; }
        final File gz = new File(dir, "data.tar.gz");
        final File plain = new File(dir, "data.tar");
        final File dataTar = gz.exists() ? gz : (plain.exists() ? plain : null);
        final BackupRestore.BackupEntry e = new BackupRestore.BackupEntry();
        e.pkg = r.pkg; e.dir = dir; e.apks = apks; e.dataTar = dataTar;
        e.installed = true; e.label = r.label;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(s(R.string.am_restoring, r.label));
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                final BackupRestore.RestoreOutcome o = BackupRestore.restore(e, getPackageName());
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        toast(o.message);
                        loadApps();
                    }
                });
            }
        }, "app-restore").start();
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
                    ? "  " + FileOps.formatSize(r.size) : "")
                    + (r.system ? s(R.string.am_item_system_suffix) : ""));

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
