package com.hex91.file;

import com.hex91.file.fs.*;
import com.hex91.file.core.*;
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
                            ? "Shizuku 授权成功，命令已可走 shell 权限"
                            : "Shizuku 授权被拒绝");
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
        LinearLayout look = section("外观");
        row(look, "主题与配色", themeSummary(), "", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ThemeActivity.class));
            }
        });

        // ---- 文件与目录 ----
        LinearLayout files = section("文件与目录");
        row(files, "工作目录", "回收站、备份、Smali 输出、网络下载都放这里。切换后旧目录内容不会自动迁移。",
                WorkDir.path(), new View.OnClickListener() {
                    public void onClick(View v) { pickWorkDir(); }
                });
        if (WorkDir.isCustom()) {
            row(files, "恢复默认目录", WorkDir.defaultPath(), "", new View.OnClickListener() {
                public void onClick(View v) { resetWorkDir(); }
            });
        }
        // v1.99.2（R-06）：上次压缩/解压失败留下的 .bakrepl / .part 在这里能看见
        row(files, "遗留文件", "上次失败留下的 .bakrepl（原内容备份）/ .part（半成品）。只列出，不动任何文件。",
                "", new View.OnClickListener() {
                    public void onClick(View v) { scanLeftovers(); }
                });

        // ---- 权限 ----
        LinearLayout perm = section("权限");
        switchRow(perm, "使用 Root 权限",
                RootUtil.isRootAvailable()
                        ? "当前设备已获取 Root。关闭后一切操作改走普通文件 API。"
                        : "当前设备未检测到 Root；打开开关只表示「允许尝试」，无 su 时仍走普通 API。",
                RootUtil.isEnabled(), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        RootUtil.saveEnabled(SettingsActivity.this, on);
                        toast(on ? "已允许使用 Root" : "已停用 Root，/data 等目录将不可访问");
                    }
                });
        infoRow(perm, "当前状态", rootStatus());

        // v1.86.0：Shizuku —— 无 root 设备上的 shell 权限
        switchRow(perm, "使用 Shizuku（免 Root）",
                "装好 Shizuku 并授权后，用 shell 身份执行命令。注意：它不是 root，"
                        + "/data/data 下别的应用私有目录（700）仍读不到。",
                ShizukuBridge.isEnabled(this), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        ShizukuBridge.setEnabled(SettingsActivity.this, on);
                        build();
                        toast(on ? "已允许使用 Shizuku" : "已停用 Shizuku，命令改走普通 API");
                    }
                });
        infoRow(perm, "Shizuku 状态", ShizukuBridge.statusText(this));
        if (ShizukuBridge.supported() && ShizukuBridge.alive() && !ShizukuBridge.granted()) {
            row(perm, "申请 Shizuku 授权", "会弹出 Shizuku 的确认框，选择「允许」即可", "",
                    new View.OnClickListener() {
                        public void onClick(View v) { ShizukuBridge.request(SettingsActivity.this); }
                    });
        }

        // ---- 系统集成（v1.84.0） ----
        LinearLayout sys = section("系统集成");
        switchRow(sys, "在系统文件选择器中显示",
                "开启后，系统「文件」应用侧边栏会多出本应用；微信 / 邮件等点「打开文件」时也能直接选这里。",
                HfDocumentsProvider.isVisible(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        getSharedPreferences(HfDocumentsProvider.PREF, MODE_PRIVATE)
                                .edit().putBoolean(HfDocumentsProvider.KEY_VISIBLE, on).apply();
                        HfDocumentsProvider.notifyRootsChanged(SettingsActivity.this);
                        toast(on ? "已对系统文件选择器开放" : "已隐藏，系统文件选择器不再显示本应用");
                    }
                });
        row(sys, "在系统选择器中试一下", "调用系统的「打开文件」界面，确认能看到 HexFile 入口", "",
                new View.OnClickListener() {
                    public void onClick(View v) { openSystemPicker(); }
                });

        // ---- 桌面小组件（v1.85.0） ----
        LinearLayout widget = section("桌面小组件");
        row(widget, "添加到桌面",
                "在桌面长按空白处 → 小组件 → 找到 HexFile，把卡片拖到桌面。卡片内含工作目录、下载、图片、回收站四个入口。",
                "", null);
        row(widget, "立即刷新卡片", "重新读取存储占用（没有添加过卡片时不会有变化）", "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        WidgetProvider.refreshIfExists(SettingsActivity.this);
                        toast("已刷新小组件");
                    }
                });

        // ---- 视频播放（v1.91.0） ----
        LinearLayout video = section("视频播放");
        switchRow(video, "视频缩略图",
                "文件列表里给视频显示首帧。关掉后只显示类型角标（省电、省内存，老机器建议关）。",
                PlayerPrefs.thumbsEnabled(this),
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton cb, boolean on) {
                        PlayerPrefs.setThumbsEnabled(SettingsActivity.this, on);
                        if (!on) ThumbLoader.clearDiskCache(SettingsActivity.this);
                        toast(on ? "已开启：重新进目录即可看到视频首帧" : "已关闭，并清掉了已有的缩略图缓存");
                    }
                });
        infoRow(video, "缩略图缓存", bytesText(ThumbLoader.diskCacheSize(this)));
        row(video, "清除缩略图缓存", "删掉缓存目录（cacheDir/vthumb），下次进目录会重新抽帧", "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        int n = ThumbLoader.clearDiskCache(SettingsActivity.this);
                        toast("已删除 " + n + " 个缩略图缓存文件");
                        build();
                    }
                });
        row(video, "清除播放记录", "忘掉所有视频的续播位置（已看的片子下次从头开始）", "",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        PlayerPrefs.clearPositions(SettingsActivity.this);
                        toast("播放记录已清除");
                    }
                });
        infoRow(video, "播放内核", "Media3 ExoPlayer 1.4.1（HLS/DASH/RTSP/本地容器）");

        // ---- 回收站 ----
        LinearLayout trash = section("回收站");
        row(trash, "保留策略", TrashPolicyDialog.summary(), "", new View.OnClickListener() {
            public void onClick(View v) {
                TrashPolicyDialog.show(SettingsActivity.this, new Runnable() {
                    public void run() { build(); }
                });
            }
        });
        row(trash, "打开回收站", Trash.dir().getAbsolutePath(), "", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, TrashActivity.class));
            }
        });

        // ---- 关于（版本号固定在最下） ----
        LinearLayout about = section("关于");
        infoRow(about, "版本", versionName() + "（" + versionCode() + "）");
        infoRow(about, "工作目录", WorkDir.path());

        TextView foot = new TextView(this);
        foot.setText("HexFile · 双栏文件管理器");
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
            toast("当前系统没有文件选择器：" + t.getClass().getSimpleName());
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
        toast("正在扫描 " + root.getPath());
        Bg.io(new Runnable() {
            public void run() {
                final java.util.List<LeftoverScan.Hit> hits = LeftoverScan.scan(root);
                Bg.ui(new Runnable() {
                    public void run() { showLeftovers(hits); }
                });
            }
        }, new Bg.Fail() {
            public void onFail(Throwable t) {
                toast("扫描失败：" + t.getClass().getSimpleName());
            }
        });
    }

    private void showLeftovers(java.util.List<LeftoverScan.Hit> hits) {
        if (isFinishing()) return;
        if (hits.isEmpty()) {
            new HfDialogBuilder(this)
                    .setTitle("没有遗留文件")
                    .setMessage("工作目录里没有 .bakrepl / .replacebak / .part。\n\n"
                            + "压缩包写回失败时才会留下备份，平时看不到是正常的。")
                    .setPositiveButton("好", null)
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
        head.setText(hits.size() + " 个，共 " + FileOps.formatSize(total)
                + "。点条目可单独删除。");
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
                .setTitle("遗留文件")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmDeleteLeftover(final LeftoverScan.Hit h) {
        boolean isBak = h.file.getName().endsWith(LeftoverScan.BAK)
                || h.file.getName().endsWith(LeftoverScan.BAK_LEGACY);
        new HfDialogBuilder(this)
                .setTitle(isBak ? "删除备份？" : "删除半成品？")
                .setMessage(h.file.getPath() + "\n\n"
                        + (isBak
                        ? "这是 " + h.owner + " 的备份，里面可能装着上一次替换失败前的原内容。"
                          + "删掉就无法从这里恢复了。"
                        : "这是没写完的临时文件，删除不会影响已完成的文件。"))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        toast(LeftoverScan.delete(h.file) ? "已删除" : "删除失败（可能被占用）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickWorkDir() {
        DirPickerDialog.show(this, new File(WorkDir.path()), new DirPickerDialog.OnPick() {
            public void onPick(File dir) {
                if (dir == null) return;
                final String p = dir.getAbsolutePath();
                if (!dir.isDirectory()) {
                    new HfDialogBuilder(SettingsActivity.this)
                            .setTitle("目录不存在")
                            .setMessage(p + "\n\n是否创建该目录并设为工作目录？")
                            .setPositiveButton("创建并使用", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    if (!dir.mkdirs() && !dir.isDirectory()) {
                                        toast("创建失败，请检查存储权限或换个位置");
                                        return;
                                    }
                                    applyWorkDir(p);
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return;
                }
                applyWorkDir(p);
            }
        });
    }

    private void applyWorkDir(String p) {
        if (!WorkDir.set(this, p)) {
            toast("保存失败，请检查存储权限");
            return;
        }
        toast("工作目录已切换");
        build();
    }

    private void resetWorkDir() {
        new HfDialogBuilder(this)
                .setTitle("恢复默认工作目录")
                .setMessage("将改回 " + WorkDir.defaultPath()
                        + "\n\n当前目录里的回收站、备份不会被移动，仍留在原处。")
                .setPositiveButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        WorkDir.reset(SettingsActivity.this);
                        toast("已恢复默认");
                        build();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    /** Root 当前可用性的人类可读描述（只读缓存，不触发新的探测） */
    private String rootStatus() {
        if (!RootUtil.isEnabled()) return "已停用";
        if (RootUtil.isRealRoot()) return "已获取 root（uid 0）";
        if (ShizukuBridge.usable()) return "无 root，当前用 Shizuku（shell）";
        return "未获取 root（或尚未检测）";
    }

    private String themeSummary() {
        int scheme = ThemeManager.scheme(this);
        String name = (scheme >= 0 && scheme < ThemeSchemes.NAMES.length)
                ? ThemeSchemes.NAMES[scheme] : "默认";
        int mode = ThemeManager.mode(this);
        String modeName = mode == ThemeManager.MODE_FOLLOW_SYSTEM
                ? "跟随系统" : (ThemeManager.isDark(this) ? "深色" : "浅色");
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
    private static String bytesText(long b) {
        if (b <= 0) return "空";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
