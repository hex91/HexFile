package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.core.MimeUtil;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 单页「打开方式」对话框（v2.122.0 引入，v2.123.0 改单页）。
 *
 * <p>对标截图里的 MT 式布局：只有一个页面，图标网格列出「对这个文件适用的打开方式」——
 * 十六进制、文本、属性、字符串等，以及一个「系统应用」入口。点系统应用后本页切换为
 * 系统应用列表（按文件真实 MIME 查询），顶部给「返回」回到网格。不再有
 * 内置 / 文本 / 图片 / 视频 / 音频 这排标签。</p>
 *
 * <p>三层约束：</p>
 * <ul>
 *   <li>文案只在 ui 层产生，全部走 {@code R.string}，由 {@link OpenWithKind} 提供纯 JDK 的分发依据；</li>
 *   <li>内置查看器收 {@code File.getAbsolutePath()}（见各 Activity 的 EXTRA 常量），
 *       系统应用才走 {@link OpenWith#intentFor(File)} 的 content:// 授权；</li>
 *   <li>属性不在这里实现——通过 {@link PropsHandler} 回调宿主，因为属性弹窗依赖宿主的
 *       刷新/重命名/删除等能力（见 PropsController.Host），ui 的公共对话框不该反向依赖 MainActivity。</li>
 * </ul>
 */
public final class OpenWithDialog {

    private static final int MAX_APPS = 30;

    /** 属性入口回调：宿主负责弹自己的属性窗，传 null 表示不显示属性项 */
    public interface PropsHandler {
        void showProps(File f);
    }

    private OpenWithDialog() {}

    /** 不提供属性入口的调用（属性项不会出现） */
    public static void show(Context ctx, File f) {
        show(ctx, f, null);
    }

    /**
     * 弹出单页打开方式。
     *
     * @param ctx      上下文（非 Activity 时给 Intent 加 NEW_TASK）
     * @param f        目标文件，必须是已存在的普通文件
     * @param props    属性回调，可为 null
     */
    public static void show(final Context ctx, final File f, final PropsHandler props) {
        if (ctx == null || f == null || !f.isFile()) return;
        final float den = ctx.getResources().getDisplayMetrics().density;
        int pad = dp(den, 14);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(ctx.getString(R.string.ow_title, f.getName()));
        title.setTextSize(16);
        title.setTextColor(ThemeManager.text(ctx));
        title.setPadding(0, 0, 0, dp(den, 10));
        root.addView(title);

        final LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(den, 4), 0, 0);
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
        renderMain(ctx, den, f, props, page, holder);
    }

    /** 主页面：单页网格，只列对这个文件适用的方式 */
    private static void renderMain(final Context ctx, final float den, final File f,
                                   final PropsHandler props, final LinearLayout page,
                                   final AlertDialog[] holder) {
        page.removeAllViews();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);

        // 先收集适用的项，属性项仅在有回调时加入
        List<OpenWithKind> items = new ArrayList<OpenWithKind>();
        for (OpenWithKind k : OpenWithKind.BUILT_INS) {
            if (k == OpenWithKind.PROPERTIES && props == null) continue;
            if (OpenWithKind.applies(k, f.getName())) items.add(k);
        }
        for (int i = 0; i < items.size(); i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 3; j++) {
                int pos = i + j;
                View cell = pos < items.size()
                        ? builtInCell(ctx, den, items.get(pos), f, props, page, holder)
                        : new View(ctx);
                row.addView(cell, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(row);
        }
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /** 系统应用页：按文件真实 MIME 查包；顶部「返回」回到主网格 */
    private static void renderApps(final Context ctx, final float den, final File f,
                                   final PropsHandler props, final LinearLayout page,
                                   final AlertDialog[] holder) {
        page.removeAllViews();

        TextView back = new TextView(ctx);
        back.setText(ctx.getString(R.string.ow_back));
        back.setTextSize(14);
        back.setTextColor(ThemeManager.accent(ctx));
        back.setPadding(dp(den, 4), dp(den, 6), dp(den, 4), dp(den, 6));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { renderMain(ctx, den, f, props, page, holder); }
        });
        page.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        List<ResolveInfo> apps = queryApps(ctx, f);
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
            final PackageManager pm = ctx.getPackageManager();
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

    /** 按文件真实 MIME 查系统应用，按名称排序，最多 MAX_APPS 个 */
    private static List<ResolveInfo> queryApps(Context ctx, File f) {
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        final PackageManager pm = ctx.getPackageManager();
        Intent q = OpenWith.intentFor(f);
        try {
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
            if (found != null) out.addAll(found);
        } catch (Throwable t) {
            android.util.Log.w("OpenWithDialog", "query apps for " + MimeUtil.of(f.getName()) + " failed: " + t);
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

    /** 网格单元：色块图标 + 名称 */
    private static View builtInCell(final Context ctx, final float den, final OpenWithKind kind,
                                     final File f, final PropsHandler props, final LinearLayout page,
                                     final AlertDialog[] holder) {
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
            public void onClick(View v) {
                if (kind == OpenWithKind.SYSTEM) {
                    renderApps(ctx, den, f, props, page, holder);
                } else {
                    openBuiltIn(ctx, f, kind, props);
                }
            }
        });
        return cell;
    }

    /** 内置方式分发：属性走回调，其余只构造 Intent，文案与提示留在 ui 层 */
    private static void openBuiltIn(Context ctx, File f, OpenWithKind kind, PropsHandler props) {
        try {
            if (kind == OpenWithKind.PROPERTIES) {
                if (props != null) props.showProps(f);
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

    private static int labelRes(OpenWithKind kind) {
        switch (kind) {
            case HEX: return R.string.ow_hex;
            case TEXT: return R.string.ow_item_text;
            case PROPERTIES: return R.string.pr_title;
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
            case PROPERTIES: return "i";
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
