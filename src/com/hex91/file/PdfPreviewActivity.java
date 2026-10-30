package com.hex91.file;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/**
 * PDF 预览（v1.58.0）：系统 PdfRenderer 渲染，上一页/下一页导航 + 页码，
 *  双击放大/还原；长按当前页导出 PNG 到 /sdcard/HexFile/PDF导出/。
 *  加密 PDF 会打开失败并给出提示。
 */
public class PdfPreviewActivity extends BaseActivity {

    static final String EXTRA_FILE = "file";

    private File pdf;
    private PdfRenderer renderer;
    private ImageView iv;
    private TextView pageText;
    private int page = 0;
    private float zoom = 1f;
    private Bitmap current;
    private long lastTap = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pdf = new File(getIntent().getStringExtra(EXTRA_FILE));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF2B2B2B);

        iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setPadding(0, 8, 0, 8);
        iv.setBackgroundColor(0xFF2B2B2B);
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastTap < 350) {          // 双击：放大 2x / 还原
                    zoom = zoom > 1.5f ? 1f : 2f;
                    iv.setScaleType(zoom > 1.5f ? ImageView.ScaleType.MATRIX
                            : ImageView.ScaleType.FIT_CENTER);
                    if (zoom > 1.5f) {
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.postScale(zoom, zoom);
                        iv.setImageMatrix(m);
                    } else {
                        iv.setImageMatrix(new android.graphics.Matrix());
                    }
                }
                lastTap = now;
            }
        });
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { exportPng(); return true; }
        });
        root.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部导航条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ThemeManager.topBar(this));
        float den = getResources().getDisplayMetrics().density;
        bar.setPadding((int) (16 * den), (int) (6 * den), (int) (16 * den), (int) (6 * den));
        TextView prev = navBtn("‹ 上一页", den);
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (page > 0) { page--; render(); } }
        });
        bar.addView(prev);
        pageText = new TextView(this);
        pageText.setTextColor(ThemeManager.topBarText(this));
        pageText.setTextSize(14);
        pageText.setGravity(Gravity.CENTER);
        bar.addView(pageText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = navBtn("下一页 ›", den);
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (renderer != null && page < renderer.getPageCount() - 1) { page++; render(); }
            }
        });
        bar.addView(next);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    renderer = new PdfRenderer(ParcelFileDescriptor.open(pdf,
                            ParcelFileDescriptor.MODE_READ_ONLY));
                } catch (Throwable t) {
                    err = t instanceof SecurityException
                            ? "PDF 已加密，无法预览"
                            : "打开失败: " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            Toast.makeText(PdfPreviewActivity.this, ferr,
                                    Toast.LENGTH_LONG).show();
                            finish();
                        } else render();
                    }
                });
            }
        }, "pdf-open").start();
    }

    private TextView navBtn(String text, float den) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(ThemeManager.topBarText(this));
        t.setPadding((int) (10 * den), (int) (8 * den), (int) (10 * den), (int) (8 * den));
        return t;
    }

    private void render() {
        if (renderer == null) return;
        final int count = renderer.getPageCount();
        page = Math.max(0, Math.min(page, count - 1));
        new Thread(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                String err = null;
                try {
                    synchronized (PdfPreviewActivity.this) {
                        PdfRenderer.Page p = renderer.openPage(page);
                        // 按屏幕宽适配，限 2048 防 OOM
                        int targetW = Math.min(2048, getResources().getDisplayMetrics()
                                .widthPixels * 2);
                        float sc = (float) targetW / p.getWidth();
                        bmp = Bitmap.createBitmap(targetW,
                                Math.max(1, Math.round(p.getHeight() * sc)),
                                Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        p.render(bmp, null, null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        p.close();
                    }
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final Bitmap fb = bmp;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) { toast("渲染失败: " + ferr); return; }
                        if (fb == null) return;
                        if (current != null && !current.isRecycled()) current.recycle();
                        current = fb;
                        iv.setImageBitmap(current);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        zoom = 1f;
                        pageText.setText((page + 1) + " / " + count);
                    }
                });
            }
        }, "pdf-render").start();
    }

    /** 导出当前页为 PNG（用 FOR_PRINT 高质量重渲） */
    private void exportPng() {
        if (renderer == null || current == null) return;
        Toast.makeText(this, "导出中…", Toast.LENGTH_SHORT).show();
        final int idx = page;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = null;
                try {
                    File dir = new File(android.os.Environment.getExternalStorageDirectory(),
                            "HexFile/PDF导出");
                    dir.mkdirs();
                    String base = pdf.getName();
                    if (base.toLowerCase().endsWith(".pdf")) base = base.substring(0, base.length() - 4);
                    out = new File(dir, base + "_p" + (idx + 1) + ".png");
                    int n = 1;
                    while (out.exists()) out = new File(dir, base + "_p" + (idx + 1) + "(" + (n++) + ").png");
                    synchronized (PdfPreviewActivity.this) {
                        PdfRenderer.Page p = renderer.openPage(idx);
                        Bitmap bmp = Bitmap.createBitmap(p.getWidth() * 3,
                                p.getHeight() * 3, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        p.close();
                        FileOutputStream fo = new FileOutputStream(out);
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                        fo.close();
                        bmp.recycle();
                    }
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final String ferr = err;
                final File fout = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ferr != null ? "导出失败: " + ferr
                                : "已导出 → " + fout.getAbsolutePath());
                    }
                });
            }
        }, "pdf-export").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (current != null && !current.isRecycled()) current.recycle();
        if (renderer != null) {
            try { renderer.close(); } catch (Throwable ignored) {}
            renderer = null;
        }
        super.onDestroy();
    }
}
