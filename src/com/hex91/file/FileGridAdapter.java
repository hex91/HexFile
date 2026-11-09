package com.hex91.file;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 网格视图适配器（v1.80.0）。
 *
 * 数据不复制、不另存：直接读同一个 {@link FileAdapter} 的下标，
 * 所以排序、隐藏文件、多选勾选状态两套视图天然一致，不需要双向同步。
 * 数据变化由 FileAdapter.notifyDataSetChanged() 通过 mirror 转发过来。
 *
 * 单元格 = 正方形缩略图区 + 单行文件名：
 *  - 图片/APK 走 ThumbLoader（复用列表那份 LRU，同一张图不会解码两次）；
 *  - 目录/文档显示类型角标（沿用 MT 式彩色方块）；
 *  - 多选时右上角打勾 + 底色高亮，点按行为与列表完全一致（外层共用同一套回调）。
 */
public class FileGridAdapter extends BaseAdapter {

    private final Context ctx;
    private final FileAdapter data;
    /** 缩略图目标边长（px）：大约是单元格的两倍，避免放大发虚又不至于占太多内存 */
    private final int thumbPx;
    private final GradientDrawable thumbBg;
    private final GradientDrawable checkBg;

    public FileGridAdapter(Context c, FileAdapter d) {
        this.ctx = c;
        this.data = d;
        float density = c.getResources().getDisplayMetrics().density;
        thumbPx = (int) (96 * density);
        thumbBg = UiDecor.round(c, ThemeManager.surfaceAlt(c), 0, 0f, 10f);
        checkBg = new GradientDrawable();
        checkBg.setShape(GradientDrawable.OVAL);
        checkBg.setColor(ThemeManager.accent(c));
    }

    public int getCount() { return data.getCount(); }

    public Object getItem(int i) { return data.get(i); }

    public long getItemId(int i) { return i; }

    public View getView(int i, View convert, ViewGroup parent) {
        View v = convert;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_grid, parent, false);
        }
        FileItem it = data.get(i);
        View frame = v.findViewById(R.id.grid_frame);
        ImageView thumb = (ImageView) v.findViewById(R.id.grid_thumb);
        TextView icon = (TextView) v.findViewById(R.id.grid_icon);
        TextView check = (TextView) v.findViewById(R.id.grid_check);
        TextView name = (TextView) v.findViewById(R.id.grid_name);

        String low = it.name().toLowerCase(Locale.CHINA);
        boolean real = !it.isArchEntry() && !it.isDir() && it.file != null && it.file.isFile();
        boolean isImg = real && (low.endsWith(".jpg") || low.endsWith(".jpeg")
                || low.endsWith(".png") || low.endsWith(".gif") || low.endsWith(".webp")
                || low.endsWith(".bmp"));
        boolean isApk = real && low.endsWith(".apk");

        if (isImg || isApk) {
            icon.setVisibility(View.GONE);
            thumb.setVisibility(View.VISIBLE);
            thumb.setBackground(thumbBg);
            thumb.setClipToOutline(true);        // 圆角只画背景，图要跟着裁
            final String key = ThumbLoader.key(it.file);
            if (!key.equals(thumb.getTag())) {
                thumb.setTag(key);
                thumb.setImageBitmap(null);
                Bitmap mem = ThumbLoader.getMem(key);
                if (mem != null) {
                    thumb.setImageBitmap(mem);
                } else {
                    final ImageView tv = thumb;
                    final boolean apkF = isApk;
                    ThumbLoader.load(ctx.getApplicationContext(), it.file, apkF, thumbPx,
                            new ThumbLoader.Cb() {
                                public void onThumb(String k, Bitmap bmp) {
                                    if (k.equals(tv.getTag()) && bmp != null) tv.setImageBitmap(bmp);
                                }
                            });
                }
            }
        } else {
            thumb.setVisibility(View.GONE);
            thumb.setTag(null);
            thumb.setImageDrawable(null);
            icon.setVisibility(View.VISIBLE);
            boolean ks = !it.upEntry && !it.isDir() && FileItem.isKeystoreName(low);
            if (it.upEntry) {
                icon.setText("..");
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
                icon.setTextColor(ThemeManager.onAccent(ctx));
                icon.setBackground(roundBg(ThemeManager.accent(ctx), 10f));
            } else if (ks) {
                icon.setText(FileItem.KEY_ICON);
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
                icon.setTextColor(0xFFFFFFFF);
                icon.setBackground(roundBg(0xFF6D4C41, 10f));
            } else {
                icon.setText(it.typeLabel());
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
                icon.setTextColor(0xFFFFFFFF);
                icon.setBackground(roundBg(it.typeColor(), 10f));
            }
        }

        boolean sel = data.selectionMode && it.selected;
        check.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (sel) {
            check.setBackground(checkBg);
            check.setTextColor(ThemeManager.onAccent(ctx));
        }
        if (sel) {
            frame.setBackground(UiDecor.round(ctx,
                    UiDecor.alpha(ThemeManager.accent(ctx), 0x38),
                    UiDecor.alpha(ThemeManager.accent(ctx), 0x88), 1.2f, 11f));
            name.setTextColor(ThemeManager.accent(ctx));
        } else {
            frame.setBackground(null);
            name.setTextColor(ThemeManager.text(ctx));
        }
        // v1.81.0：符号链接在格子里加 → 尾巴（目标全文放列表模式看）
        String linkSfx = it.linkSuffix();
        if (linkSfx != null) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(it.name());
            int st = sb.length();
            sb.append(it.linkBroken ? "  ⚠" : "  →");
            sb.setSpan(new android.text.style.ForegroundColorSpan(
                            it.linkBroken ? ThemeManager.err(ctx) : ThemeManager.textSub(ctx)),
                    st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            name.setText(sb);
        } else {
            name.setText(it.name());
        }
        return v;
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(UiDecor.dp(ctx, radiusDp));
        return g;
    }
}
