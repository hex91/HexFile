package com.hex91.file.ui;

import com.hex91.file.apk.ArchiveUtil;
import com.hex91.file.R;
import com.hex91.file.fs.FileItem;
import com.hex91.file.fs.FileOps;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 压缩包内部条目列表适配器（复用 item_file 布局，数据源为 ArchiveUtil.Entry） */
public class ZipAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<ArchiveUtil.Entry> items = new ArrayList<>();

    /**
     * v2.16.0（F-21）：搜索结果显示完整路径而不是短名。
     *
     * <p>目录浏览时短名是对的（当前目录已经写在标题里）；搜索结果来自整个包，
     * 只显示 {@code AndroidManifest.xml} 会出现多行一模一样、点下去却是不同文件的情况。</p>
     */
    private boolean showPath;

    public ZipAdapter(Context c) { this.ctx = c; }

    public void setShowPath(boolean v) { this.showPath = v; }

    public void set(List<ArchiveUtil.Entry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public ArchiveUtil.Entry get(int i) { return items.get(i); }

    public int getCount() { return items.size(); }
    public Object getItem(int i) { return items.get(i); }
    public long getItemId(int i) { return i; }

    public View getView(int i, View cv, ViewGroup parent) {
        if (cv == null) cv = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
        ArchiveUtil.Entry e = items.get(i);
        // v1.6.0 起 item_file 的图标位是 TextView 类型角标（与 FileAdapter 同款渲染）
        TextView icon = (TextView) cv.findViewById(R.id.icon);
        TextView name = (TextView) cv.findViewById(R.id.name);
        TextView detail = (TextView) cv.findViewById(R.id.detail);
        icon.setText(FileItem.typeLabel(e.name, e.dir));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(FileItem.typeColor(e.name, e.dir));
        bg.setCornerRadius(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 7, ctx.getResources().getDisplayMetrics()));
        icon.setBackground(bg);
        String shown = showPath ? e.full : e.name;
        name.setText(shown + (e.dir && !shown.endsWith("/") ? "/" : ""));
        detail.setText(e.dir ? ctx.getString(R.string.common_folder) : FileOps.formatSize(e.size));
        return cv;
    }
}
