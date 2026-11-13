package com.hex91.file.ui;

import com.hex91.file.utils.Bg;
import com.hex91.file.utils.ShizukuBridge;
import com.hex91.file.*;
import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.LeftoverScan;
import com.hex91.file.fs.RootUtil;
import com.hex91.file.fs.TempFiles;
import com.hex91.file.fs.Trash;
import com.hex91.file.fs.WorkDir;
import com.hex91.file.core.ThemeSchemes;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 设置页（v1.83.0）。
 *
 * <p>结构刻意做成「分组卡片 + 行」的构建器，而不是固定 XML：
 * 每个分区由 {@link #section(String)} 开卡，条目由 {@link #row}/{@link #switchRow}/
 * {@link #infoRow} 往里加。以后要加设置项，只在 {@link #build()} 里多写一行调用，
 * 不用改布局、不用加控件 id。</p>
 *
 * <p>为什么要这个页面：主题入口原来只在侧抽屉，Root 开关没有，工作目录写死在代码里，
 * 回收站策略藏在二级页顶栏 —— 四件事分散在四个地方，用户很难找。</p>
 */
public class SettingsActivity extends BaseActivity {

    private LinearLayout container;
    private float den;
    /** 首次 onResume 不重建（onCreate 已建过一次），之后每次返回都刷新摘要 */
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        den = getResources().getDisplayMetrics().density;
        container = (LinearLayout) findViewById(R.id.settings_container);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        ((TextView) findViewById(R.id.tv_ver_top)).setText("v" + versionName());

        build();
        registerShizukuResult();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从主题页/回收站返回后摘要可能已变；主题变化时 BaseActivity 会 recreate，这里只兜底文字
        if (resumedOnce) build();
        resumedOnce = true;
    }

    /** Shizuku 的授权结果监听（用 Object 持有，避免低版本设备加载该接口失败） */
    private Object shizukuListener;

    private void registerShizukuResult() {
        if (!ShizukuBridge.supported()) return;
        try {
            shizukuListener = new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != ShizukuBridge.REQ_CODE) return;
                    ShizukuBridge.invalidate();
                    build();
                    toast(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? getString(R.string.settings_shizuku_granted)
                            : getString(R.string.settings_shizuku_denied));
                }
            };
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(
                    (rikka.shizuku.Shizuku.OnRequestPermissionResultListener) shizukuListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shizukuListener == null) return;
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(
                    (rikka.shizuku.Shizuku.OnRequestPermissionResultListener) shizukuListener);
        } catch (Throwable ignored) {
        }
        shizukuListener = null;
    }

    // ---------------------------------------------------------------- 页面构建

    private void build() {
        container.removeAllViews();

        // ---- 外观 ----
        LinearLayout look = section(getString(R.string.settings_section_appearance));
        row(look, getString(R.string.settings_theme), themeSummary(), "", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ThemeActivity.class));
            }
        });

        // ---- 文件与目录 ----
        LinearLayout files = section(getString(R.string.settings_section_files));
        row(files, getString(R.string.settings_workdir), getString(R.string.settings_workdir_sub),
                WorkDir.path(), new View.OnClickListener() {
                    public void onClick(View v) { pickWorkDir(); }
                });
        if (WorkDir.isCustom()) {
            row(files, getString(R.string.settings_workdir_reset), WorkDir.defaultPath(), "", new View.OnClickListener() {
                public void onClick(View v) { resetWorkDir(); }
            });
        }
        // v1.99.2（R-06）：上次压缩/解压失败留下的 .bakrepl / .part 在这里能看见
        row(files, getString(R.string.settings_leftovers), getString(R.string.settings_leftovers_sub),
                "", new View.OnClickListener() {
                    public void onClick(View v) { scanLeftovers(); }
                });

        // ---- 权限 ----
        LinearLayout perm = section(getString(R.string.settings_section_permissions));
        switchRow(perm, getString(R.string.settings_root),
                RootUtil.isRootAvailable()
                        ? getString(R.string.settings_root_sub_on)
                        : getString(R.string.settings_root_sub_off),
                RootUtil.isEnabled(), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        RootUtil.saveEnabled(SettingsActivity.this, on);
                        toast(on ? getString(R.string.settings_root_on_toast) : getString(R.string.settings_root_off_toast));
                    }
                });
        infoRow(perm, getString(R.string.settings_status), rootStatus());

        // v1.86.0：Shizuku —— 无 root 设备上的 shell 权限
        switchRow(perm, getString(R.string.settings_shizuku),
                getString(R.string.settings_shizuku_sub),
                ShizukuBridge.isEnabled(this), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        ShizukuBridge.setEnabled(SettingsActivity.this, on);
                        build();
                        toast(on ? getString(R.string.settings_shizuku_on_toast) : getString(R.string.settings_shizuku_off_toast));
                    }
                });
        infoRow(perm, getString(R.string.settings_shizuku_status), ShizukuBridge.statusText(this));
        if (ShizukuBridge.supported() && ShizukuBridge.alive() && !ShizukuBridge.granted()) {
            row(perm, getString(R.string.settings_shizuku_request), getString(R.string.settings_shizuku_request_sub), "",
                    new View.OnClickListener() {
                        public void onClick(View v) { ShizukuBridge.request(SettingsActivity.this); }
                    });
        }

        // ---- 系统集成（v1.84.0） ----
        LinearLayout sys = section(getString(R.string.settings_section_system));
        switchRow(sys, getString(R.string.settings_saf),
                getString(R.string.settings_saf_sub),
                HfDocumentsProvider.isVisible(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        getSharedPreferences(HfDocumentsProvider.PREF, MODE_PRIVATE)
                                .edit().putBoolean(HfDocumentsProvider.KEY_VISIBLE, on).apply();
                        HfDocumentsProvider.notifyRootsChanged(SettingsActivity.this);
                        toast(on ? getString(R.string.settings_saf_on_toast) : getString(R.string.settings_saf_off_toast));
                    }
                });
        row(sys, getString(R.string.settings_saf_try), getString(R.string.settings_saf_try_sub), "",
                new View.OnClickListener() {
                    public void onClick(View v) { openSystemPicker(); }
                });

        // ---- 桌面小组件（v1.85.0） ----
        LinearLayout widget = section(getString(R.string.settings_section_widget));
        row(widget, getString(R.string.settings_widget_add),
                getString(R.string.settings_widget_add_sub),
                "", null);
        row(widget, getString(R.string.settings_widget_refresh), getString(R.string.settings_widget_refresh_sub), "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        WidgetProvider.refreshIfExists(SettingsActivity.this);
                        toast(getString(R.string.settings_widget_refreshed));
                    }
                });

        // ---- 视频播放（v1.91.0） ----
        LinearLayout video = section(getString(R.string.settings_section_video));
        switchRow(video, getString(R.string.settings_thumbs),
                getString(R.string.settings_thumbs_sub),
                PlayerPrefs.thumbsEnabled(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        PlayerPrefs.setThumbsEnabled(SettingsActivity.this, on);
                        if (!on) ThumbLoader.clearDiskCache(SettingsActivity.this);
                        toast(on ? getString(R.string.settings_thumbs_on) : getString(R.string.settings_thumbs_off));
                    }
                });
        infoRow(video, getString(R.string.settings_thumb_cache), bytesText(ThumbLoader.diskCacheSize(this)));
        row(video, getString(R.string.settings_thumb_cache_clear), getString(R.string.settings_thumb_cache_clear_sub), "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        int n = ThumbLoader.clearDiskCache(SettingsActivity.this);
                        toast(getString(R.string.settings_thumb_cache_cleared, n));
                        build();
                    }
                });
        row(video, getString(R.string.settings_playback_clear), getString(R.string.settings_playback_clear_sub), "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        PlayerPrefs.clearPositions(SettingsActivity.this);
                        toast(getString(R.string.settings_playback_cleared));
                    }
                });
        infoRow(video, getString(R.string.settings_player_engine), getString(R.string.settings_player_engine_value));

        // ---- 回收站 ----
        LinearLayout trash = section(getString(R.string.settings_section_trash));
        row(trash, getString(R.string.settings_trash_policy), TrashPolicyDialog.summary(), "", new View.OnClickListener() {
            public void onClick(View v) {
                TrashPolicyDialog.show(SettingsActivity.this, new Runnable() {
                    public void run() { build(); }
                });
            }
        });
        row(trash, getString(R.string.settings_trash_open), Trash.dir().getAbsolutePath(), "", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, TrashActivity.class));
            }
        });

        // ---- 关于（版本号固定在最下） ----
        LinearLayout about = section(getString(R.string.settings_section_about));
        infoRow(about, getString(R.string.settings_version), versionName() + "（" + versionCode() + "）");
        infoRow(about, getString(R.string.settings_workdir), WorkDir.path());

        TextView foot = new TextView(this);
        foot.setText(getString(R.string.settings_footer));
        foot.setTextSize(11);
        foot.setTextColor(ThemeManager.textSub(this));
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(18), 0, 0);
        container.addView(foot);
    }

    /** 分区标题 + 一张圆角卡片；返回的卡片供 row/switchRow/infoRow 追加条目 */
    private LinearLayout section(String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(11.5f);
        h.setTextColor(ThemeManager.textSub(this));
        h.setPadding(dp(6), dp(18), 0, dp(6));
        container.addView(h);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiDecor.cardBg(this));
        // 行的高亮是矩形水波纹，不裁剪会从卡片圆角外露出直角
        card.setClipToOutline(true);
        container.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 可点击行：标题 + 副标题 + 右侧当前值 + `›` */
    private void row(LinearLayout card, String title, String sub, String value,
                     View.OnClickListener click) {
        LinearLayout r = newRow(card, click);
        r.addView(labelBox(title, sub), weight1());

        if (value != null && value.length() > 0) {
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextSize(12);
            v.setTextColor(ThemeManager.textSub(this));
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.START);   // 长路径保留尾部
            v.setGravity(Gravity.END);
            v.setMaxWidth((int) (160 * den));
            r.addView(v);
        }
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(ThemeManager.textSub(this));
        arrow.setPadding(dp(6), 0, 0, 0);
        r.addView(arrow);
    }

    /** 只读行（版本号等） */
    private void infoRow(LinearLayout card, String title, String value) {
        LinearLayout r = newRow(card, null);
        r.addView(labelBox(title, null), weight1());
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(12.5f);
        v.setTextColor(ThemeManager.textSub(this));
        v.setGravity(Gravity.END);
        r.addView(v);
    }

    /** 开关行：标题 + 副标题 + 右侧 Switch */
    private void switchRow(LinearLayout card, String title, String sub, boolean on,
                           CompoundButton.OnCheckedChangeListener l) {
        LinearLayout r = newRow(card, null);
        r.addView(labelBox(title, sub), weight1());
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        r.addView(sw);
    }

    /** 行容器：可选分隔线 + 水波纹背景 */
    private LinearLayout newRow(LinearLayout card, View.OnClickListener click) {
        if (card.getChildCount() > 0) card.addView(divider());
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(12), dp(12));
        if (click != null) {
            r.setClickable(true);
            r.setFocusable(true);
            Drawable bg = selectableBg();
            if (bg != null) r.setBackground(bg);
            r.setOnClickListener(click);
        }
        card.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** 左侧文字块（标题 + 可选副标题） */
    private View labelBox(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(ThemeManager.text(this));
        box.addView(t);

        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(11.5f);
            s.setTextColor(ThemeManager.textSub(this));
            s.setPadding(0, dp(3), dp(8), 0);
            box.addView(s);
        }
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(ThemeManager.divider(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Drawable selectableBg() {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    && tv.resourceId != 0) {
                return getDrawable(tv.resourceId);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int dp(float v) {
        return Math.round(v * den);
    }

    // ---------------------------------------------------------------- 动作

    /** v1.84.0：拉起系统的「打开文件」界面，验证 SAF 入口是否生效 */
    private void openSystemPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.settings_no_picker, t.getClass().getSimpleName()));
        }
    }

    // ---------------------------------------------------------------- 遗留文件（v1.99.2 R-06）

    /**
     * 扫描工作目录里的遗留文件。
     *
     * <p>扫描只读（{@link LeftoverScan#scan} 不改任何东西）；列出后由用户点具体条目再确认删除 ——
     * {@code .bakrepl} 里可能就是原文件的全部内容，自动清理等于替用户做决定。</p>
     *
     * <p>目录大时扫描要几秒，走 {@link Bg}，不占 UI 线程。</p>
     */
    private void scanLeftovers() {
        final File root = new File(WorkDir.path());
        toast(getString(R.string.leftover_scanning, root.getPath()));
        Bg.io(new Runnable() {
            public void run() {
                // v2.4.0（R-08）：排除正在写的临时文件 —— 否则大文件复制/局域网接收
                // 进行中扫一次，用户会把任务正在写的 .part 当垃圾删掉
                final java.util.List<LeftoverScan.Hit> hits =
                        LeftoverScan.scan(root, TempFiles.snapshot());
                Bg.ui(new Runnable() {
                    public void run() { showLeftovers(hits); }
                });
            }
        }, new Bg.Fail() {
            public void onFail(Throwable t) {
                toast(getString(R.string.leftover_scan_failed, t.getClass().getSimpleName()));
            }
        });
    }

    private void showLeftovers(java.util.List<LeftoverScan.Hit> hits) {
        if (isFinishing()) return;
        if (hits.isEmpty()) {
            new HfDialogBuilder(this)
                    .setTitle(getString(R.string.leftover_none_title))
                    .setMessage(getString(R.string.leftover_none_msg))
                    .setPositiveButton(getString(R.string.common_ok), null)
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);
        long total = 0;
        for (LeftoverScan.Hit h : hits) total += h.size;
        TextView head = new TextView(this);
        head.setText(getString(R.string.leftover_head, hits.size(), FileOps.formatSize(total)));
        head.setTextSize(12f);
        head.setTextColor(ThemeManager.textSub(this));
        head.setPadding(0, 0, 0, dp(8));
        box.addView(head);
        for (final LeftoverScan.Hit h : hits) {
            TextView t = new TextView(this);
            t.setText(h.file.getName() + "\n" + h.file.getParent() + "  ·  "
                    + FileOps.formatSize(h.size));
            t.setTextSize(12.5f);
            t.setTextColor(ThemeManager.text(this));
            t.setPadding(dp(4), dp(9), dp(4), dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDeleteLeftover(h); }
            });
            box.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new HfDialogBuilder(this)
                .setTitle(getString(R.string.settings_leftovers))
                .setView(sv)
                .setPositiveButton(getString(R.string.common_close), null)
                .show();
    }

    private void confirmDeleteLeftover(final LeftoverScan.Hit h) {
        boolean isBak = h.file.getName().endsWith(LeftoverScan.BAK)
                || h.file.getName().endsWith(LeftoverScan.BAK_LEGACY);
        new HfDialogBuilder(this)
                .setTitle(isBak ? getString(R.string.leftover_delete_bak_title) : getString(R.string.leftover_delete_part_title))
                .setMessage(isBak
                        ? getString(R.string.leftover_delete_bak_msg, h.file.getPath(), h.owner)
                        : getString(R.string.leftover_delete_part_msg, h.file.getPath()))
                .setPositiveButton(getString(R.string.common_delete), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // 扫描之后任务才启动的窗口也要挡住：删掉活动 .part 等于让任务白跑
                        if (TempFiles.isActive(h.file)) {
                            toast(getString(R.string.leftover_delete_active));
                            return;
                        }
                        toast(LeftoverScan.delete(h.file) ? getString(R.string.leftover_deleted) : getString(R.string.leftover_delete_failed));
                    }
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void pickWorkDir() {
        DirPickerDialog.show(this, new File(WorkDir.path()), new DirPickerDialog.OnPick() {
            public void onPick(File dir) {
                if (dir == null) return;
                final String p = dir.getAbsolutePath();
                if (!dir.isDirectory()) {
                    new HfDialogBuilder(SettingsActivity.this)
                            .setTitle(getString(R.string.workdir_missing_title))
                            .setMessage(getString(R.string.workdir_missing_msg, p))
                            .setPositiveButton(getString(R.string.workdir_create_use), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    if (!dir.mkdirs() && !dir.isDirectory()) {
                                        toast(getString(R.string.workdir_create_failed));
                                        return;
                                    }
                                    applyWorkDir(p);
                                }
                            })
                            .setNegativeButton(getString(R.string.common_cancel), null)
                            .show();
                    return;
                }
                applyWorkDir(p);
            }
        });
    }

    private void applyWorkDir(String p) {
        if (!WorkDir.set(this, p)) {
            toast(getString(R.string.workdir_save_failed));
            return;
        }
        toast(getString(R.string.workdir_switched));
        build();
    }

    private void resetWorkDir() {
        new HfDialogBuilder(this)
                .setTitle(getString(R.string.workdir_reset_title))
                .setMessage(getString(R.string.workdir_reset_msg, WorkDir.defaultPath()))
                .setPositiveButton(getString(R.string.workdir_reset_action), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        WorkDir.reset(SettingsActivity.this);
                        toast(getString(R.string.workdir_reset_done));
                        build();
                    }
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    /** Root 当前可用性的人类可读描述（只读缓存，不触发新的探测） */
    private String rootStatus() {
        if (!RootUtil.isEnabled()) return getString(R.string.root_status_disabled);
        if (RootUtil.isRealRoot()) return getString(R.string.root_status_root);
        if (ShizukuBridge.usable()) return getString(R.string.root_status_shizuku);
        return getString(R.string.root_status_none);
    }

    private String themeSummary() {
        int scheme = ThemeManager.scheme(this);
        String name = (scheme >= 0 && scheme < ThemeSchemes.NAMES.length)
                ? ThemeSchemes.NAMES[scheme] : getString(R.string.theme_name_default);
        int mode = ThemeManager.mode(this);
        String modeName = mode == ThemeManager.MODE_FOLLOW_SYSTEM
                ? getString(R.string.theme_mode_system) : (ThemeManager.isDark(this) ? getString(R.string.theme_mode_dark) : getString(R.string.theme_mode_light));
        return name + " · " + modeName;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private long versionCode() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 缓存体积显示：不足 1MB 用 KB，避免一堆 0.0 MB */
    private String bytesText(long b) {
        if (b <= 0) return getString(R.string.common_empty);
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
