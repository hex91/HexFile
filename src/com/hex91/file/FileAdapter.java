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
import android.widget.ImageView;
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

    /** v2.0.1：图标方块的圆角（px）。原来每次绑定都算一次 applyDimension。 */
    private final float iconCorner;

    public FileAdapter(Context ctx) {
        this.ctx = ctx;
        iconCorner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 7,
                ctx.getResources().getDisplayMetrics());
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

    // ---------- 单行局部刷新（v2.0.1，F-16）----------

    /** 列表本体（下拉刷新容器里的那个 ListView）；setAdapter 之后 bind 一次 */
    private android.widget.AbsListView boundList;
    /** 网格本体；与列表共用同一份数据，单行刷新时两个都刷（只有一个在显示） */
    private android.widget.AbsListView boundGrid;

    public void bindList(android.widget.AbsListView v) { boundList = v; }

    public void bindGrid(android.widget.AbsListView v) { boundGrid = v; }

    /**
     * 只重画一行：勾选 / 取消勾选走这里，不再整表 {@link #notifyDataSetChanged()}。
     *
     * <p>三个要点：</p>
     * <ol>
     *   <li>行不可见（滚出屏幕）时直接返回 —— 它滚回来本来就会重新绑定，不必为看不见的行刷表；</li>
     *   <li>仍然复用 {@code getView(pos, 那个 View, 父容器)}，渲染逻辑只有一份，选中态不可能走样；</li>
     *   <li>列表与网格互为镜像：谁在显示刷谁，另一个是 GONE 会被跳过。</li>
     * </ol>
     */
    public void refreshRow(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        boolean painted = repaintRow(boundList, this, pos);
        if (mirror instanceof FileGridAdapter) {
            painted |= ((FileGridAdapter) mirror).refreshRow(pos);
        }
        // 还没 bind 过任何视图（构造后、setAdapter 前）就走老路，别让调用方以为刷了
        if (!painted && boundList == null && boundGrid == null) notifyDataSetChanged();
    }

    /** 把 pos 对应的子 View 重画一遍；视图未绑定 / 不可见 / 下标越界都返回 false */
    static boolean repaintRow(android.widget.AbsListView av, BaseAdapter ad, int pos) {
        if (av == null || av.getVisibility() != View.VISIBLE) return false;
        int idx = pos - av.getFirstVisiblePosition();
        if (idx < 0 || idx >= av.getChildCount()) return false;
        ad.getView(pos, av.getChildAt(idx), av);
        return true;
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
        // v2.0.1（F-16）：只重画这一行；以前点一下 10,000 项的目录要整表重绑
        refreshRow(i);
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

    /**
     * 行视图引用缓存（v2.0.1，F-16）。
     *
     * <p>原来每次 {@code getView} 都要 5 次 {@code findViewById}（icon / thumb / badge / name /
     * detail），每次都是在视图树里递归查找；滚动时每帧几十行，查找次数就上千。缓存到
     * {@code convert.setTag(vh)} 之后，绑定只剩属性赋值。</p>
     *
     * <p><b>注意</b>：这里的 {@code convert.setTag(vh)} 与缩略图防串图用的
     * {@code thumb.setTag(key)} 是两个不同的 View，互不影响，别合并成一个。</p>
     */
    private static final class VH {
        TextView icon, badge, name, detail;
        ImageView thumb;
    }

    /** 新行 inflate 后（或 tag 不是 VH 时）把 5 个引用找一遍并塞进 VH */
    private static void bindRow(View row, VH vh) {
        vh.icon = (TextView) row.findViewById(R.id.icon);
        vh.thumb = (ImageView) row.findViewById(R.id.thumb);
        vh.badge = (TextView) row.findViewById(R.id.badge);
        vh.name = (TextView) row.findViewById(R.id.name);
        vh.detail = (TextView) row.findViewById(R.id.detail);
    }

    public View getView(int i, View convert, ViewGroup parent) {
        final VH vh;
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
            vh = new VH();
            bindRow(convert, vh);
            convert.setTag(vh);
        } else {
            Object tag = convert.getTag();
            if (tag instanceof VH) {
                vh = (VH) tag;
            } else {
                // 防御：万一拿到不是本适配器 inflate 的行，重找一遍也不会崩
                vh = new VH();
                bindRow(convert, vh);
                convert.setTag(vh);
            }
        }
        FileItem it = get(i);
        if (it.loadingRow) {
            // 读取中占位行：复用条目布局，只留一行灰字，缩略图/角标全部隐藏
            vh.icon.setVisibility(View.GONE);
            vh.thumb.setTag(null);
            vh.thumb.setImageDrawable(null);
            vh.thumb.setVisibility(View.GONE);
            vh.badge.setVisibility(View.GONE);
            vh.name.setText("读取中…");
            vh.detail.setText("");
            convert.setBackgroundColor(0);
            return convert;
        }

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
            vh.icon.setVisibility(View.GONE);
            vh.badge.setVisibility(isGif ? View.VISIBLE : View.GONE);
            final String key = ThumbLoader.key(it.file);
            final ImageView tv = vh.thumb;
            if (key.equals(tv.getTag())) {
                // 复用中且 tag 未变：已有内容先保留，避免闪烁
            } else {
                tv.setTag(key);
                tv.setImageBitmap(null);
                Bitmap mem = ThumbLoader.getMem(key);
                if (mem != null) {
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
            vh.icon.setVisibility(View.VISIBLE);
            vh.badge.setVisibility(View.GONE);
            vh.thumb.setTag(null);
            vh.thumb.setVisibility(View.GONE);
            // v1.69.0：签名文件 → 🔑 图标（含魔数判定：改了扩展名的 keystore 也认得）
            boolean ks = isKeystoreItem(it, low, isRealFile);
            if (ks) {
                vh.icon.setText(FileItem.KEY_ICON);
                vh.icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF6D4C41);
                bg.setCornerRadius(iconCorner);
                vh.icon.setBackground(bg);
            } else {
                // 复用 convert 时必须把字号还原，否则滚动后普通条目会残留大号 emoji 字号
                vh.icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
                // MT 式类型图标：圆角彩色方块 + 缩写
                vh.icon.setText(it.typeLabel());
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(it.typeColor());
                bg.setCornerRadius(iconCorner);
                vh.icon.setBackground(bg);
            }
        }
        if (isImg || isApk || isVid) vh.thumb.setVisibility(View.VISIBLE);

        boolean sel = selectionMode && it.selected;
        vh.name.setText(styledName(it, sel));
        vh.detail.setText((sel ? "已选中 · " : "") + detailWithPerm(it));
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
