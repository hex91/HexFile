package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.OpenWithKind;
import com.hex91.file.utils.OpenWith;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 分类式「打开方式」对话框（v2.122.0）。
 *
 * <p>对标 MT 管理器的打开方式面板：顶部一行标签（内置 / 文本 / 图片 / 视频 / 音频），
 * 「内置」页网格列出本应用自己的查看器（十六进制、编辑文本、浏览压缩包等），
 * 其余四页按 MIME 列出系统里能处理该类型的应用。这样点开一个未知文件时，用户既
 * 可以直接用内置十六进制打开，也可以挑一个系统应用，而不是像 v2.121 之前只有
 * 一个系统 chooser、失败就弹属性页。</p>
 *
 * <p>三层约束：</p>
 * <ul>
 *   <li>文案只在 ui 层产生，全部走 {@code R.string}，由 {@code OpenWithKind} 提供纯 JDK 的分发依据；</li>
 *   <li>内置查看器收 {@code File.getAbsolutePath()}（见各 Activity 的 EXTRA 常量），
 *       系统应用才走 {@link OpenWith#intentFor(File)} 的 content:// 授权；</li>
 *   <li>包可见性由清单的 {@code QUERY_ALL_PACKAGES} 兜底，{@code queryIntentActivities} 失败时
 *       该页显示空态而不是崩溃。</li>
 * </ul>
 */
public final class OpenWithDialog {

    private static final int MAX_APPS = 30;

    private OpenWithDialog() {}

    /**
     * 弹出分类打开方式。
     *
     * @param ctx 上下文（非 Activity 时给 Intent 加 NEW_TASK）
     * @param f   目标文件，必须是已存在的普通文件
     */
    public static void show(final Context ctx, final File f) {
        if (ctx == null || f == null || !f.isFile()) return;
        final float den = ctx.getResources().getDisplayMetrics().density;
        final int pad = dp(den, 14);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(ctx.getString(R.string.ow_title, f.getName()));
        title.setTextSize(16);
        title.setTextColor(ThemeManager.text(ctx));
        title.setPadding(0, 0, 0, dp(den, 10));
        root.addView(title);

        final String[] tabs = {
            ctx.getString(R.string.ow_tab_builtin), ctx.getString(R.string.ow_tab_text),
            ctx.getString(R.string.ow_tab_image), ctx.getString(R.string.ow_tab_video),
            ctx.getString(R.string.ow_tab_audio)
        };
        final LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(den, 10), 0, 0);

        final LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] tabViews = new TextView[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView t = tabButton(ctx, den, tabs[i]);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { select(ctx, den, idx, tabViews, f, page); }
            });
            tabViews[i] = t;
            tabRow.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        HorizontalScrollView tabBar = new HorizontalScrollView(ctx);
        tabBar.setHorizontalScrollBarEnabled(false);
        tabBar.addView(tabRow);
        root.addView(tabBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(page, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(den, 320)));

        final AlertDialog[] holder = new AlertDialog[1];
        TextView close = new TextView(ctx);
        close.setText(R.string.ow_close);
        close.setTextSize(14);
        close.setTextColor(ThemeManager.accent(ctx));
        close.setGravity(Gravity.END);
        close.setPadding(0, dp(den, 8), 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
            }
        });
        root.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        holder[0] = new HfDialogBuilder(ctx).setView(root).show();
        select(ctx, den, 0, tabViews, f, page);
    }

    /** 切换标签：0=内置网格，1~4=系统应用列表 */
    private static void select(Context ctx, float den, int idx, TextView[] tabs, File f, LinearLayout page) {
        for (int i = 0; i < tabs.length; i++) {
            tabState(ctx, tabs[i], i == idx);
        }
        page.removeAllViews();
        if (idx == 0) renderBuiltIns(ctx, den, f, page);
        else renderApps(ctx, den, f, OpenWithKind.APP_CATEGORIES[idx - 1], page);
    }

    /** 内置页：三列网格，只列对这个文件适用的方式 */
    private static void renderBuiltIns(final Context ctx, float den, final File f, LinearLayout page) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        OpenWithKind[] kinds = OpenWithKind.BUILT_INS;
        for (int i = 0; i < kinds.length; i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 3; j++) {
                final int pos = i + j;
                View cell = pos < kinds.length && OpenWithKind.applies(kinds[pos], f.getName())
                        ? builtInCell(ctx, den, kinds[pos], f) : new View(ctx);
                row.addView(cell, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(row);
        }
        // 系统应用总入口：与四个分类页并列，避免“只想让系统挑一个”的用户被逼着选分类
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(builtInCell(ctx, den, OpenWithKind.SYSTEM, f), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        for (int j = 0; j < 2; j++) {
            row.addView(new View(ctx), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        grid.addView(row);
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /** 系统应用页：按 MIME 查包，按应用名排序 */
    private static void renderApps(final Context ctx, float den, final File f, String category, LinearLayout page) {
        final List<ResolveInfo> apps = queryApps(ctx, category);
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        if (apps.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(R.string.ow_no_app);
            empty.setTextSize(14);
            empty.setTextColor(ThemeManager.textSub(ctx));
            empty.setPadding(dp(den, 6), dp(den, 12), dp(den, 6), dp(den, 12));
            list.addView(empty);
        } else {
            PackageManager pm = ctx.getPackageManager();
            for (final ResolveInfo ri : apps) {
                TextView item = new TextView(ctx);
                CharSequence label = ri.loadLabel(pm);
                item.setText(label != null ? label.toString() : ri.activityInfo.packageName);
                item.setTextSize(14);
                item.setTextColor(ThemeManager.text(ctx));
                item.setPadding(dp(den, 8), dp(den, 10), dp(den, 8), dp(den, 10));
                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { launchApp(ctx, f, ri); }
                });
                list.addView(item, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private static List<ResolveInfo> queryApps(Context ctx, String category) {
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        final PackageManager pm = ctx.getPackageManager();
        Intent q = new Intent(Intent.ACTION_VIEW);
        q.setType(OpenWithKind.mimeOfCategory(category));
        try {
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
            if (found != null) out.addAll(found);
        } catch (Throwable t) {
            android.util.Log.w("OpenWithDialog", "query apps for " + category + " failed: " + t);
        }
        Collections.sort(out, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return labelOf(a, pm).compareToIgnoreCase(labelOf(b, pm));
            }
        });
        return out.size() > MAX_APPS ? out.subList(0, MAX_APPS) : out;
    }

    private static String labelOf(ResolveInfo ri, PackageManager pm) {
        CharSequence c = ri.loadLabel(pm);
        return c == null ? ri.activityInfo.packageName : c.toString();
    }

    private static void launchApp(Context ctx, File f, ResolveInfo ri) {
        try {
            Intent i = OpenWith.intentFor(f);
            i.setComponent(new android.content.ComponentName(
                    ri.activityInfo.packageName, ri.activityInfo.name));
            launch(ctx, i);
        } catch (Throwable t) {
            android.util.Log.w("OpenWithDialog", "launch app " + ri.activityInfo.packageName + " failed: " + t);
        }
    }

    /** 内置网格单元：色块图标 + 名称 */
    private static View builtInCell(final Context ctx, float den, final OpenWithKind kind, final File f) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(den, 4), dp(den, 8), dp(den, 4), dp(den, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(den, 10));
        bg.setColor(ThemeManager.accent(ctx));
        TextView icon = new TextView(ctx);
        icon.setText(iconText(kind));
        icon.setTextSize(15);
        icon.setTextColor(ThemeManager.onAccent(ctx));
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundDrawable(bg);
        int side = dp(den, 46);
        icon.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        cell.addView(icon);

        TextView label = new TextView(ctx);
        label.setText(labelRes(kind));
        label.setTextSize(12);
        label.setTextColor(ThemeManager.text(ctx));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(den, 5), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBuiltIn(ctx, f, kind); }
        });
        return cell;
    }

    /** 内置方式分发：只构造 Intent，文案与提示留在 ui 层 */
    private static void openBuiltIn(Context ctx, File f, OpenWithKind kind) {
        try {
            if (kind == OpenWithKind.SYSTEM) {
                if (!OpenWith.open(ctx, f, ctx.getString(R.string.ow_system_title))) {
                    Toast.makeText(ctx, R.string.ow_no_app, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            String path = f.getAbsolutePath();
            Intent i = null;
            switch (kind) {
                case HEX:
                    i = new Intent(ctx, HexViewerActivity.class)
                            .putExtra(HexViewerActivity.EXTRA_FILE, path);
                    break;
                case TEXT:
                    i = new Intent(ctx, TextEditorActivity.class)
                            .putExtra(TextEditorActivity.EXTRA_FILE, path);
                    break;
                case ARCHIVE:
                    i = new Intent(ctx, ZipBrowserActivity.class)
                            .putExtra(ZipBrowserActivity.EXTRA_ZIP, path);
                    break;
                case IMAGE:
                    i = new Intent(ctx, ImageViewerActivity.class)
                            .putExtra(ImageViewerActivity.EXTRA_FILE, path);
                    break;
                case VIDEO:
                    i = new Intent(ctx, VideoPlayerActivity.class)
                            .putExtra(VideoPlayerActivity.EXTRA_FILE, path)
                            .putExtra(VideoPlayerActivity.EXTRA_TITLE, f.getName());
                    break;
                case APK_INFO:
                    i = new Intent(ctx, ApkInfoActivity.class)
                            .putExtra(ApkInfoActivity.EXTRA_APK, path);
                    break;
                case DEX:
                    i = new Intent(ctx, DexEditorActivity.class)
                            .putExtra(DexEditorActivity.EXTRA_FILE, path);
                    break;
                case ARSC:
                    i = new Intent(ctx, ArscEditorActivity.class)
                            .putExtra(ArscEditorActivity.EXTRA_APK, path);
                    break;
                case FONT:
                    i = new Intent(ctx, FontPreviewActivity.class)
                            .putExtra(FontPreviewActivity.EXTRA_FILE, path);
                    break;
                case PDF:
                    i = new Intent(ctx, PdfPreviewActivity.class)
                            .putExtra(PdfPreviewActivity.EXTRA_FILE, path);
                    break;
                case STRINGS:
                    i = new Intent(ctx, StringsViewerActivity.class)
                            .putExtra(StringsViewerActivity.EXTRA_FILE, path);
                    break;
                default:
                    break;
            }
            if (i != null) launch(ctx, i);
        } catch (Throwable t) {
            android.util.Log.w("OpenWithDialog", "open " + kind + " failed: " + t);
        }
    }

    private static void launch(Context ctx, Intent i) {
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private static TextView tabButton(Context ctx, float den, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(dp(den, 12), dp(den, 7), dp(den, 12), dp(den, 7));
        return t;
    }

    private static void tabState(Context ctx, TextView t, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx.getResources().getDisplayMetrics().density, 6));
        bg.setColor(active ? ThemeManager.accent(ctx) : ThemeManager.surfaceAlt(ctx));
        t.setBackgroundDrawable(bg);
        t.setTextColor(active ? ThemeManager.onAccent(ctx) : ThemeManager.textSub(ctx));
    }

    private static int labelRes(OpenWithKind kind) {
        switch (kind) {
            case HEX: return R.string.ow_hex;
            case TEXT: return R.string.ow_item_text;
            case ARCHIVE: return R.string.ow_archive;
            case IMAGE: return R.string.ow_item_image;
            case VIDEO: return R.string.ow_item_video;
            case APK_INFO: return R.string.ow_apk_info;
            case DEX: return R.string.ow_dex;
            case ARSC: return R.string.ow_arsc;
            case FONT: return R.string.ow_font;
            case PDF: return R.string.ow_pdf;
            case STRINGS: return R.string.ow_strings;
            case SYSTEM: return R.string.ow_system;
            default: return R.string.ow_system;
        }
    }

    /** 图标用短标识，避免依赖 drawable 资源；均为跨语言不变的符号 */
    private static String iconText(OpenWithKind kind) {
        switch (kind) {
            case HEX: return "16";
            case TEXT: return "T";
            case ARCHIVE: return "ZIP";
            case IMAGE: return "IMG";
            case VIDEO: return "▶";
            case APK_INFO: return "APK";
            case DEX: return "DEX";
            case ARSC: return "ARS";
            case FONT: return "Aa";
            case PDF: return "PDF";
            case STRINGS: return "S";
            case SYSTEM: return "···";
            default: return "?";
        }
    }

    private static int dp(float density, float v) {
        return Math.round(v * density);
    }
}
