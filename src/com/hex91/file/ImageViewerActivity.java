package com.hex91.file;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 图片查看器（v1.18.0）：
 *  - 大图采样加载（inSampleSize，最长边目标 2048px，防 OOM）
 *  - 双指缩放 + 单指拖动 + 双击放大/还原
 *  - 旋转 90° + 保存写回（全分辨率重解码后旋转重编码，JPEG/PNG/WEBP 按扩展名）
 *  - GIF 动图播放（android.graphics.Movie，逐帧 invalidate）
 *  - 标题：文件名 + 原始像素尺寸 + 文件大小
 */
public class ImageViewerActivity extends BaseActivity {

    public static final String EXTRA_FILE = "file";

    private File file;
    private String lower;
    private TextView title;

    private ZoomView zoom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        String path = getIntent().getStringExtra(EXTRA_FILE);
        file = path != null ? new File(path) : null;
        if (file == null || !file.isFile()) { toast("文件不存在"); finish(); return; }
        lower = file.getName().toLowerCase();

        boolean gif = lower.endsWith(".gif");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        title = new TextView(this);
        title.setSingleLine(true);
        title.setPadding(dp(12), dp(24), dp(12), dp(6));
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        zoom = new ZoomView(gif);
        root.addView(zoom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (!gif) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            Button bRot = mkBtn(bar, blp, "旋转");
            bRot.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { zoom.rotate90(); }
            });
            Button bSave = mkBtn(bar, blp, "保存");
            bSave.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { zoom.saveRotated(); }
            });
            Button bClose = mkBtn(bar, blp, "关闭");
            bClose.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { finish(); }
            });
            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        setContentView(root);
        new Thread(new Runnable() {
            public void run() { zoom.load(); }
        }, "img-load").start();
    }

    private Button mkBtn(LinearLayout bar, LinearLayout.LayoutParams lp, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        bar.addView(b, lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ 手势视图

    private class ZoomView extends View {

        final boolean gif;
        Bitmap bmp;                 // 采样后的显示位图
        Movie movie;                // GIF
        long movieStart;
        boolean playing;

        int contentW, contentH;     // 内容尺寸（位图或 movie 框）
        String dimText = "";

        final Matrix m = new Matrix();
        final float[] vals = new float[9];
        boolean fitted = false;

        final ScaleGestureDetector sgd;
        final GestureDetector gd;
        float lastX, lastY;

        ZoomView(boolean gif) {
            super(ImageViewerActivity.this);
            this.gif = gif;
            sgd = new ScaleGestureDetector(getContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            m.postScale(d.getScaleFactor(), d.getScaleFactor(),
                                    d.getFocusX(), d.getFocusY());
                            invalidate();
                            return true;
                        }
                    });
            gd = new GestureDetector(getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            m.getValues(vals);
                            float s = vals[Matrix.MSCALE_X];
                            if (s > getFitScale() * 1.2f) fit();
                            else {
                                m.reset();
                                float scale = getFitScale() * 2.5f;
                                m.postScale(scale, scale, e.getX(), e.getY());
                                clamp();
                            }
                            invalidate();
                            return true;
                        }
                        @Override
                        public boolean onDown(MotionEvent e) { return true; }
                    });
        }

        /** 后台加载：GIF 直接 Movie；位图按最长边 2048 采样 */
        void load() {
            try {
                if (gif) {
                    InputStream in = null;
                    try {
                        in = new FileInputStream(file);
                        movie = Movie.decodeStream(in);
                    } catch (Exception re) {
                        // v1.44.0：root 区回退（su cat → decodeByteArray）
                        byte[] rb = RootIO.readBytes(file);
                        if (rb != null) movie = Movie.decodeByteArray(rb, 0, rb.length);
                    } finally {
                        if (in != null) try { in.close(); } catch (Exception ignored) {}
                    }
                    if (movie != null) {
                        contentW = movie.width();
                        contentH = movie.height();
                        dimText = contentW + "×" + contentH;
                        playing = true;
                        post(new Runnable() {
                            public void run() {
                                if (!fitted) fitInit();
                                invalidate();
                            }
                        });
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                    if (o.outWidth <= 0 && RootUtil.isRootAvailable()) {
                        // v1.44.0：root 区回退——File API 读不到，用 su cat 取字节再取 bounds
                        byte[] rb = RootIO.readBytes(file);
                        if (rb != null) BitmapFactory.decodeByteArray(rb, 0, rb.length, o);
                    }
                    if (o.outWidth <= 0) {
                        post(new Runnable() {
                            public void run() { toast("无法解码该图片"); finish(); }
                        });
                        return;
                    }
                    int sample = 1;
                    int maxSide = Math.max(o.outWidth, o.outHeight);
                    while (maxSide / (sample * 2) >= 2048) sample *= 2;
                    final int sampleF = sample;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    Bitmap bRaw = BitmapFactory.decodeFile(file.getAbsolutePath(), o2);
                    if (bRaw == null) {
                        // v1.44.0：root 区回退
                        byte[] rb = RootIO.readBytes(file);
                        if (rb != null) bRaw = BitmapFactory.decodeByteArray(rb, 0, rb.length, o2);
                    }
                    final Bitmap b = bRaw;
                    post(new Runnable() {
                        public void run() {
                            if (b == null) { toast("无法解码该图片"); finish(); return; }
                            bmp = b;
                            contentW = b.getWidth();
                            contentH = b.getHeight();
                            dimText = o.outWidth + "×" + o.outHeight
                                    + (sampleF > 1 ? "（显示采样 1/" + sampleF + "）" : "");
                            fitInit();
                            invalidate();
                        }
                    });
                }
                post(new Runnable() {
                    public void run() { updateTitle(); }
                });
            } catch (Throwable t) {
                post(new Runnable() {
                    public void run() { toast("加载失败: " + t.getMessage()); finish(); }
                });
            }
        }

        void updateTitle() {
            String kind = gif ? "GIF 动图" : "图片";
            title.setText(file.getName() + "（" + kind + "，" + dimText + "，"
                    + FileOps.formatSize(file.length()) + "）");
        }

        float getFitScale() {
            if (contentW <= 0 || getWidth() <= 0) return 1f;
            return Math.min((float) getWidth() / contentW, (float) getHeight() / contentH);
        }

        /** 初始适配：居中 + 不放大（小图原尺寸居中） */
        void fitInit() {
            fit();
            fitted = true;
            invalidate();
        }

        void fit() {
            m.reset();
            if (contentW <= 0 || getWidth() == 0) return;
            float s = getFitScale();
            m.postTranslate((getWidth() - contentW) / 2f, (getHeight() - contentH) / 2f);
            if (s < 1f) {
                // 大图缩小适配：绕中心缩
                m.postScale(s, s, getWidth() / 2f, getHeight() / 2f);
            }
        }

        /** 限制平移：内容小于屏幕时居中，大于屏幕时不许拖出边界 */
        void clamp() {
            if (contentW <= 0) return;
            m.getValues(vals);
            float s = vals[Matrix.MSCALE_X];
            float w = contentW * s, h = contentH * s;
            float tx = vals[Matrix.MTRANS_X], ty = vals[Matrix.MTRANS_Y];
            float minX = getWidth() - w, minY = getHeight() - h;
            if (w <= getWidth()) tx = (getWidth() - w) / 2f;
            else tx = Math.max(minX, Math.min(0, tx));
            if (h <= getHeight()) ty = (getHeight() - h) / 2f;
            else ty = Math.max(minY, Math.min(0, ty));
            m.postTranslate(tx - vals[Matrix.MTRANS_X], ty - vals[Matrix.MTRANS_Y]);
        }

        void rotate90() {
            if (bmp == null) return;
            Matrix r = new Matrix();
            r.postRotate(90);
            Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), r, true);
            if (nb != bmp) bmp.recycle();
            bmp = nb;
            contentW = bmp.getWidth();
            contentH = bmp.getHeight();
            rotationTurns = (rotationTurns + 1) % 4;
            fitted = false;
            fitInit();
        }

        /** 全分辨率重解码 → 应用累计旋转 → 按扩展名重编码写回 */
        void saveRotated() {
            if (bmp == null || rotationTurns == 0) { toast("没有旋转需要保存"); return; }
            final int turns = rotationTurns;
            toast("保存中…");
            new Thread(new Runnable() {
                public void run() {
                    String err = null;
                    try {
                        Bitmap full = BitmapFactory.decodeFile(file.getAbsolutePath());
                        if (full == null) throw new Exception("重新解码失败");
                        Matrix r = new Matrix();
                        r.postRotate(turns * 90f);
                        Bitmap out = Bitmap.createBitmap(full, 0, 0,
                                full.getWidth(), full.getHeight(), r, true);
                        full.recycle();
                        java.io.FileOutputStream os = new java.io.FileOutputStream(file);
                        Bitmap.CompressFormat fmt = lower.endsWith(".png")
                                ? Bitmap.CompressFormat.PNG
                                : lower.endsWith(".webp") ? Bitmap.CompressFormat.WEBP
                                : Bitmap.CompressFormat.JPEG;
                        out.compress(fmt, 92, os);
                        os.close();
                        out.recycle();
                    } catch (Throwable t) {
                        err = t.getMessage();
                    }
                    final String ferr = err;
                    post(new Runnable() {
                        public void run() {
                            if (ferr == null) {
                                rotationTurns = 0;
                                toast("已保存（旋转 " + turns * 90 + "°）");
                            } else {
                                toast("保存失败: " + ferr);
                            }
                        }
                    });
                }
            }, "img-save").start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (gif && movie != null) {
                long now = android.os.SystemClock.uptimeMillis();
                if (movieStart == 0) movieStart = now;
                int dur = movie.duration();
                if (dur <= 0) dur = 100;
                int rel = (int) ((now - movieStart) % dur);
                movie.setTime(rel);
                m.getValues(vals);
                canvas.save();
                canvas.concat(m);
                movie.draw(canvas, 0, 0);
                canvas.restore();
                if (playing) postInvalidateOnAnimation();
                return;
            }
            if (bmp != null) {
                m.getValues(vals);
                canvas.save();
                canvas.concat(m);
                canvas.drawBitmap(bmp, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                canvas.restore();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (bmp == null && movie == null) return false;
            sgd.onTouchEvent(e);
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!sgd.isInProgress()) {
                        m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        clamp();
                        invalidate();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    clamp();
                    invalidate();
                    break;
            }
            return true;
        }
    }

    /** 累计旋转次数（90° 步进；保存时应用并清零） */
    int rotationTurns = 0;
}
