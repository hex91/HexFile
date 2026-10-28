package com.hex91.file;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件列表缩略图加载器（v1.39.0）：
 *  - 内存 LruCache（堆 1/8），key = 绝对路径|lastModified（改动即失效）
 *  - 单线程池串行解码，UI 线程回调；列表快速滑动时由调用方 tag 校验丢弃过期回调
 *  - 图片：BitmapFactory 采样解码（目标边长约 2x 图标尺寸）；GIF 取首帧
 *  - APK：PackageManager.getPackageArchiveInfo 解析应用图标（解析失败回退 null 由调用方显示类型角标）
 */
public final class ThumbLoader {

    public interface Cb {
        /** UI 线程回调；bmp 可为 null（解码/解析失败） */
        void onThumb(String key, Bitmap bmp);
    }

    private static final LruCache<String, Bitmap> CACHE;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static {
        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        if (max < 4 * 1024 * 1024) max = 4 * 1024 * 1024;
        CACHE = new LruCache<String, Bitmap>(max) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    private ThumbLoader() {}

    /** 缓存 key：路径 + 修改时间（文件被覆盖后自动换 key） */
    public static String key(File f) {
        return f.getAbsolutePath() + "|" + f.lastModified();
    }

    public static Bitmap getMem(String key) {
        synchronized (CACHE) { return CACHE.get(key); }
    }

    private static void putMem(String key, Bitmap bmp) {
        if (bmp != null) synchronized (CACHE) { CACHE.put(key, bmp); }
    }

    /**
     * 异步加载缩略图。
     * @param sizePx 目标最长边像素（建议 2x 图标尺寸，如 96）
     */
    public static void load(Context appCtx, final File f, final boolean apk,
                            final int sizePx, final Cb cb) {
        final String key = key(f);
        Bitmap mem = getMem(key);
        if (mem != null) {
            cb.onThumb(key, mem);
            return;
        }
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bmp = apk ? loadApkIcon(appCtx, f, sizePx) : decodeImage(f, sizePx);
                putMem(key, bmp);
                final Bitmap out = bmp;
                UI.post(new Runnable() {
                    public void run() { cb.onThumb(key, out); }
                });
            }
        });
    }

    /** 图片采样解码（GIF 由 BitmapFactory 取首帧）。v1.44.1：File API 失败 → su cat 字节回退 */
    private static Bitmap decodeImage(File f, int sizePx) {
        Bitmap b = decodeViaFile(f, sizePx);
        if (b == null) b = decodeViaSu(f, sizePx);
        return b;
    }

    private static Bitmap decodeViaFile(File f, int sizePx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= sizePx && o.outHeight / (s * 2) >= sizePx) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b != null) b.setDensity(Bitmap.DENSITY_NONE);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.44.1：root 区回退——su cat 取字节后按同参数 decodeByteArray */
    private static Bitmap decodeViaSu(File f, int sizePx) {
        if (!RootUtil.isRootAvailable()) return null;
        byte[] rb = RootIO.readBytes(f);
        if (rb == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(rb, 0, rb.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= sizePx && o.outHeight / (s * 2) >= sizePx) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(rb, 0, rb.length, o2);
            if (b != null) b.setDensity(Bitmap.DENSITY_NONE);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** APK 应用图标（解析失败返回 null） */
    private static Bitmap loadApkIcon(Context ctx, File f, int sizePx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || pi.applicationInfo == null) return null;
            pi.applicationInfo.sourceDir = f.getAbsolutePath();
            pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
            Drawable d = pi.applicationInfo.loadIcon(ctx.getPackageManager());
            if (d == null) return null;
            int w = Math.max(sizePx, d.getIntrinsicWidth());
            int h = Math.max(sizePx, d.getIntrinsicHeight());
            if (w > 512) w = 512;
            if (h > 512) h = 512;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
