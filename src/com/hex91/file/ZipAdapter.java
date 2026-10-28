package com.hex91.file;

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

    public ZipAdapter(Context c) { this.ctx = c; }

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
        name.setText(e.name + (e.dir ? "/" : ""));
        detail.setText(e.dir ? "文件夹" : FileOps.formatSize(e.size));
        return cv;
    }
}
