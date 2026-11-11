package com.hex91.file;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 文件列表适配器：MT 风格条目（类型彩色图标 + 名称 + 时间/大小），支持排序与隐藏文件 */
public class FileAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<FileItem> items = new ArrayList<FileItem>();

    /** 排序方式：名称 / 大小 / 修改时间 */
    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_TIME = 2;
    public int sortMode = SORT_NAME;
    /** 是否显示隐藏文件（. 开头） */
    public boolean showHidden = false;
    /** v1.96.0：目录读取中（见 setLoading） */
    private boolean loading;
    /** 应用根目录（v1.40.0）：当前目录等于它时不显示 .. 返回项；null = 只要有上级就显示 */
    public File rootDir = null;

    public FileAdapter(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * v1.96.0：换取目录结果（在后台线程读完后回主线程调用）。
     *
     * <p>原来这里是 {@code setDir}，把 listFiles/排序/逐项 lstat 全在主线程做完；
     * 现在 IO 部分搬进 {@link DirReader}，这里只做「换数据 + 通知」。</p>
     */
    public void setResult(DirReader.Result r) {
        loading = false;
        items.clear();
        if (r != null) items.addAll(r.items);
        notifyDataSetChanged();
    }

    /**
     * v1.96.0：标记「正在读取」。
     * <p>列表非空时保留旧内容（切目录瞬间不闪白，读完整体替换）；
     * 列表为空时显示一行「读取中…」。读取期间点击被
     * {@code MainActivity} 忽略，避免操作到上一个目录的条目。</p>
     */
    public void setLoading(boolean b) {
        loading = b;
        notifyDataSetChanged();
    }

    public boolean isLoading() {
        return loading;
    }

    /** 直接展示给定条目（压缩包内浏览用；调用方已排好序，首项可为 .. 返回项） */
    public void setItems(List<FileItem> list) {
        // v1.99.0（O-05）：这里是「数据已经就位」，必须同时收掉读取态。
        // 原来只换数据不清 loading：从目录里点开一个 zip，进压缩包走的就是 setItems，
        // loading 留在 true，而 MainActivity.handleItemClick 开头有 isLoading() 拦截
        // → 压缩包里的条目怎么点都没反应（只能返回键退出），看着像「应用卡死」。
        loading = false;
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    /**
     * v1.81.0：填充符号链接信息。
     * <p>lstat 一次判类型（有缓存），确认是链接再 readlink。悬空链接 {@code f.exists()}
     * 为 false，单独标记，列表显示「(失效)」。</p>
     */
    private static void fillLink(FileItem it) {
        if (it.file == null || it.upEntry) return;
        int k = LinkUtil.kind(it.file);
        if (k == LinkUtil.NONE) return;
        String t = LinkUtil.target(it.file);
        it.linkTarget = t == null ? "" : t;
        it.linkBroken = (k == LinkUtil.BROKEN);
        // 指向目录 → 按目录处理：部分 FUSE 实现 isDirectory() 不跟随链接，
        // 这里用 lstat 的结论兜底，保证点进去能当目录打开
        if (k == LinkUtil.DIR) it.forceDir = true;
    }

    // ---------- 网格视图联动（v1.80.0）----------

    /** 同一个目录可能同时挂着列表与网格两套 Adapter，数据变更要一起通知 */
    private BaseAdapter mirror;

    public void setMirror(BaseAdapter m) {
        mirror = m;
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        if (mirror != null) mirror.notifyDataSetChanged();
    }

    // ---------- 多选模式 ----------

    /** 是否处于多选模式（点击 = 勾选/取消） */
    public boolean selectionMode = false;

    public void setSelectionMode(boolean m) {
        selectionMode = m;
        if (!m) clearSelection();
        notifyDataSetChanged();
    }

    public void toggle(int i) {
        FileItem it = items.get(i);
        it.selected = !it.selected;
        notifyDataSetChanged();
    }

    public void selectAll(boolean all) {
        for (FileItem it : items) if (!it.isArchUp() && !it.upEntry) it.selected = all;
        notifyDataSetChanged();
    }

    public int selectedCount() {
        int n = 0;
        for (FileItem it : items) if (it.selected) n++;
        return n;
    }

    public List<File> getSelectedFiles() {
        List<File> out = new ArrayList<File>();
        for (FileItem it : items) if (it.selected) out.add(it.file);
        return out;
    }

    /** 选中项全集（含压缩包条目；批量操作按 isArchEntry 分流） */
    public List<FileItem> getSelectedItems() {
        List<FileItem> out = new ArrayList<FileItem>();
        for (FileItem it : items) if (it.selected) out.add(it);
        return out;
    }

    private void clearSelection() {
        for (FileItem it : items) it.selected = false;
    }

    public FileItem get(int i) {
        if (loading && items.isEmpty()) return FileItem.loading();
        return items.get(i);
    }

    public int size() { return items.size(); }

    public int getCount() { return (loading && items.isEmpty()) ? 1 : items.size(); }

    public Object getItem(int i) { return get(i); }

    public long getItemId(int i) { return i; }

    public View getView(int i, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
        }
        FileItem it = get(i);
        TextView icon = (TextView) convert.findViewById(R.id.icon);
        if (it.loadingRow) {
            // 读取中占位行：复用条目布局，只留一行灰字，缩略图/角标全部隐藏
            android.widget.ImageView tv =
                    (android.widget.ImageView) convert.findViewById(R.id.thumb);
            icon.setVisibility(View.GONE);
            tv.setTag(null);
            tv.setImageDrawable(null);
            tv.setVisibility(View.GONE);
            ((TextView) convert.findViewById(R.id.badge)).setVisibility(View.GONE);
            ((TextView) convert.findViewById(R.id.name)).setText("读取中…");
            ((TextView) convert.findViewById(R.id.detail)).setText("");
            convert.setBackgroundColor(0);
            return convert;
        }
        android.widget.ImageView thumb = (android.widget.ImageView) convert.findViewById(R.id.thumb);
        TextView badge = (TextView) convert.findViewById(R.id.badge);
        TextView name = (TextView) convert.findViewById(R.id.name);
        TextView detail = (TextView) convert.findViewById(R.id.detail);

        String low = it.name().toLowerCase();
        // v1.39.0：真实文件的图片缩略图 / APK 应用图标（压缩包内条目不做，需解包太重）
        boolean isRealFile = !it.isArchEntry() && !it.isDir() && it.file != null && it.file.isFile();
        boolean isImg = isRealFile && (low.endsWith(".jpg") || low.endsWith(".jpeg")
                || low.endsWith(".png") || low.endsWith(".gif") || low.endsWith(".webp")
                || low.endsWith(".bmp"));
        boolean isApk = isRealFile && low.endsWith(".apk");
        boolean isGif = isImg && low.endsWith(".gif");
        // v1.91.0：视频也出缩略图（抽帧结果有磁盘缓存，第二次进目录几乎是瞬时）
        boolean isVid = isRealFile && MimeUtil.isVideo(low);

        if (isImg || isApk || isVid) {
            // 缩略图模式：隐藏彩色角标，异步加载（回调以 tag 校验，回收复用安全）
            icon.setVisibility(View.GONE);
            badge.setVisibility(isGif ? View.VISIBLE : View.GONE);
            final String key = ThumbLoader.key(it.file);
            final android.widget.ImageView tv = thumb;
            if (key.equals(tv.getTag())) {
                // 复用中且 tag 未变：已有内容先保留，避免闪烁
            } else {
                tv.setTag(key);
                tv.setImageBitmap(null);
                Bitmap mem = ThumbLoader.getMem(key);                if (mem != null) {
                    tv.setImageBitmap(mem);
                } else {
                    final int px = (int) (38 * ctx.getResources().getDisplayMetrics().density) * 2;
                    final android.content.Context app = ctx.getApplicationContext();
                    final int kind = isApk ? ThumbLoader.KIND_APK
                            : isVid ? ThumbLoader.KIND_VIDEO : ThumbLoader.KIND_IMAGE;
                    ThumbLoader.load(app, it.file, kind, px, new ThumbLoader.Cb() {
                        public void onThumb(String k, android.graphics.Bitmap bmp) {
                            if (k.equals(tv.getTag()) && bmp != null) tv.setImageBitmap(bmp);
                        }
                    });
                }
            }
        } else {
            icon.setVisibility(View.VISIBLE);
            badge.setVisibility(View.GONE);
            thumb.setTag(null);
            thumb.setVisibility(View.GONE);
            // v1.69.0：签名文件 → 🔑 图标（含魔数判定：改了扩展名的 keystore 也认得）
            boolean ks = isKeystoreItem(it, low, isRealFile);
            if (ks) {
                icon.setText(FileItem.KEY_ICON);
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF6D4C41);
                bg.setCornerRadius(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 7, ctx.getResources().getDisplayMetrics()));
                icon.setBackground(bg);
            } else {
                // 复用 convert 时必须把字号还原，否则滚动后普通条目会残留大号 emoji 字号
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
                // MT 式类型图标：圆角彩色方块 + 缩写
                icon.setText(it.typeLabel());
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(it.typeColor());
                bg.setCornerRadius(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 7, ctx.getResources().getDisplayMetrics()));
                icon.setBackground(bg);
            }
        }
        if (isImg || isApk || isVid) thumb.setVisibility(View.VISIBLE);

        boolean sel = selectionMode && it.selected;
        name.setText(styledName(it, sel));
        detail.setText((sel ? "已选中 · " : "") + detailWithPerm(it));
        convert.setBackgroundColor(sel ? ThemeManager.selected(ctx) : 0x00000000);
        return convert;
    }

    /**
     * v1.81.0：名称 + 符号链接目标。
     * 链接后缀用小一号的次要色，悬空链接用错误色，和文件名区分开但又不抢主信息。
     */
    private CharSequence styledName(FileItem it, boolean sel) {
        String suffix = it.linkSuffix();
        if (suffix == null) return (sel ? "✓ " : "") + it.name();
        // 超长目标截断，避免把文件名挤出屏幕（TextView 是单行尾部省略）
        if (suffix.length() > 48) suffix = suffix.substring(0, 46) + "…";
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (sel) sb.append("✓ ");
        sb.append(it.name());
        int st = sb.length();
        sb.append(suffix);
        int color = it.linkBroken ? ThemeManager.err(ctx) : ThemeManager.textSub(ctx);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.78f), st, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * v1.69.0：是否签名文件。
     * <p>先看扩展名（零开销）；扩展名不认识时再看魔数 —— 有人会把 keystore 改名成
     * {@code sign} / {@code xxx.bin} 藏在目录里，这一步能把它认出来。
     * 魔数只读前 4 字节，并按「路径+大小+修改时间」缓存，列表滚动不会反复 IO。
     */
    private boolean isKeystoreItem(FileItem it, String low, boolean isRealFile) {
        if (it.upEntry || it.isDir()) return false;
        if (FileItem.isKeystoreName(low)) return true;
        if (!isRealFile) return false;
        // 只对「看起来可能是密钥库」的文件读魔数，避免整个目录逐个 open
        int dot = low.lastIndexOf('.');
        String ext = dot >= 0 ? low.substring(dot + 1) : "";
        if (!(ext.isEmpty() || "bin".equals(ext) || "dat".equals(ext)
                || "key".equals(ext) || "store".equals(ext) || "bks".equals(ext))) {
            return false;
        }
        final File f = it.file;
        if (f.length() < 12) return false;
        String ck = f.getAbsolutePath() + "#" + f.length() + "#" + f.lastModified();
        synchronized (KS_CACHE) {
            Boolean hit = KS_CACHE.get(ck);
            if (hit != null) return hit;
        }
        boolean ks = false;
        try {
            byte[] head = FileInspect.readHead(f, 8);
            String m = FileInspect.magicName(head);
            ks = "JKS".equals(m) || "BKS".equals(m);
        } catch (Throwable ignore) {
        }
        synchronized (KS_CACHE) {
            if (KS_CACHE.size() > 512) KS_CACHE.clear();
            KS_CACHE.put(ck, ks);
        }
        return ks;
    }

    /** 魔数判定缓存（key = 路径#大小#mtime） */
    private static final java.util.HashMap<String, Boolean> KS_CACHE = new java.util.HashMap<String, Boolean>();

    /** v1.43.0：详情行 = 权限串（rw-r--r--，如有）+ 原详情；未缓存则异步取后整表刷新 */
    private String detailWithPerm(FileItem it) {
        String base = it.detail();
        if (it.isArchEntry() || it.upEntry || it.file == null) return base;
        FileAttrs.Attr a = FileAttrs.get(it.file);
        if (a != null) return a.perm + "  " + base;
        final File f = it.file;
        FileAttrs.load(f, new FileAttrs.Callback() {
            public void onAttr(FileAttrs.Attr at) {
                if (at != null) notifyDataSetChanged();
            }
        });
        return base;
    }
}
