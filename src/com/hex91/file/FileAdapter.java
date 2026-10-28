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

    /** 排序方式：0=名称 1=大小 2=修改时间 */
    public int sortMode = 0;
    /** 是否显示隐藏文件（. 开头） */
    public boolean showHidden = false;
    /** 应用根目录（v1.40.0）：当前目录等于它时不显示 .. 返回项；null = 只要有上级就显示 */
    public File rootDir = null;

    public FileAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setDir(File dir) {
        items.clear();
        File[] files = dir.listFiles();
        // v1.42.0：listFiles 被 SELinux 拒绝（/data 等）且已 root → su ls 列取回退
        java.util.Set<String> rootDirNames = null;
        if (files == null && RootUtil.isRootAvailable()) {
            java.util.List<String> lines = RootUtil.suList(dir.getPath());
            if (lines != null) {
                java.util.ArrayList<File> fs = new java.util.ArrayList<>();
                rootDirNames = new java.util.HashSet<>();
                for (String line : lines) {
                    if (line.length() < 2) continue;
                    boolean d = line.endsWith("/");
                    String n = d ? line.substring(0, line.length() - 1) : line;
                    if (n.equals(".") || n.equals("..") || n.isEmpty()) continue;
                    fs.add(new File(dir, n));
                    if (d) rootDirNames.add(n);
                }
                files = fs.toArray(new File[0]);
            }
        }
        if (files != null) {
            final Comparator<File> byName = new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
            Arrays.sort(files, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory())
                        return a.isDirectory() ? -1 : 1;
                    int r;
                    if (sortMode == 1) {
                        r = Long.compare(a.length(), b.length());
                        if (r == 0) r = byName.compare(a, b);
                    } else if (sortMode == 2) {
                        r = Long.compare(b.lastModified(), a.lastModified());
                        if (r == 0) r = byName.compare(a, b);
                    } else {
                        r = byName.compare(a, b);
                    }
                    return r;
                }
            });
            for (File f : files) {
                if (!showHidden && f.getName().startsWith(".")) continue;
                FileItem it = new FileItem(f);
                if (rootDirNames != null && rootDirNames.contains(f.getName()))
                    it.forceDir = true;
                items.add(it);
            }
        }
        // v1.40.0：非顶层目录 → 顶部插 .. 返回项（file 指向上级，点击即返回）
        // v1.42.0：root 设备上级目录即便不可读（/data）也允许返回
        File up = dir.getParentFile();
        boolean atRoot = rootDir != null
                && dir.getAbsolutePath().equals(rootDir.getAbsolutePath());
        if (up != null && (up.canRead() || RootUtil.isRootAvailable()) && !atRoot) {
            items.add(0, FileItem.up(up));
        }
        notifyDataSetChanged();
        Log.d("HexFile", "setDir=" + dir.getPath() + " items=" + items.size());
    }

    /** 直接展示给定条目（压缩包内浏览用；调用方已排好序，首项可为 .. 返回项） */
    public void setItems(List<FileItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
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

    public FileItem get(int i) { return items.get(i); }

    public int size() { return items.size(); }

    public int getCount() { return items.size(); }

    public Object getItem(int i) { return items.get(i); }

    public long getItemId(int i) { return i; }

    public View getView(int i, View convert, ViewGroup parent) {
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
        }
        FileItem it = items.get(i);
        TextView icon = (TextView) convert.findViewById(R.id.icon);
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

        if (isImg || isApk) {
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
                    final boolean apkF = isApk;
                    ThumbLoader.load(app, it.file, apkF, px, new ThumbLoader.Cb() {
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
            // MT 式类型图标：圆角彩色方块 + 缩写
            icon.setText(it.typeLabel());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(it.typeColor());
            bg.setCornerRadius(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 7, ctx.getResources().getDisplayMetrics()));
            icon.setBackground(bg);
        }
        if (isImg || isApk) thumb.setVisibility(View.VISIBLE);

        boolean sel = selectionMode && it.selected;
        name.setText((sel ? "✓ " : "") + it.name());
        detail.setText((sel ? "已选中 · " : "") + detailWithPerm(it));
        convert.setBackgroundColor(sel ? ThemeManager.selected(ctx) : 0x00000000);
        return convert;
    }

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
