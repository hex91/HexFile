package com.hex91.file.ui;

import com.hex91.file.fs.RootIO;
import com.hex91.file.fs.RootUtil;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件列表缩略图加载器（v1.39.0，v1.91.0 增加视频帧）。
 *
 * <ul>
 *   <li>内存 LruCache（堆 1/8），key = 绝对路径|lastModified（文件被覆盖后自动失效）；</li>
 *   <li>单线程池串行解码，UI 线程回调；滑动时由调用方 tag 校验丢弃过期回调；</li>
 *   <li>图片：BitmapFactory 采样解码（目标边长约 2x 图标尺寸）；GIF 取首帧；</li>
 *   <li>APK：PackageManager.getPackageArchiveInfo 解析应用图标；</li>
 *   <li>视频：MediaMetadataRetriever 抽一帧（v1.91.0）。<b>必须落磁盘缓存</b> ——
 *       抽帧要真正解码一段视频，单次 60~300ms，一个目录几十个视频滚一遍就是十几秒卡顿；
 *       把 JPEG 存到 cacheDir 后，第二次进目录只读几十 KB 的小图。</li>
 * </ul>
 */
public final class ThumbLoader {

    /** 图片（含 GIF 首帧） */
    public static final int KIND_IMAGE = 0;
    /** APK 应用图标 */
    public static final int KIND_APK = 1;
    /** 视频首帧（v1.91.0） */
    public static final int KIND_VIDEO = 2;

    public interface Cb {
        /** UI 线程回调；bmp 可为 null（解码/解析失败） */
        void onThumb(String key, Bitmap bmp);
    }

    private static final LruCache<String, Bitmap> CACHE;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 磁盘缓存的容量上限：超过就按最后修改时间删掉最旧的一半 */
    private static final int DISK_MAX_FILES = 400;
    private static final long DISK_MAX_BYTES = 32L * 1024 * 1024;
    /** 抽帧位置：时长的 10%（避开片头黑场与台标），但不晚于 5 秒 */
    private static final long FRAME_AT_MAX_US = 5000000L;

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

    /** 兼容旧调用：apk=true 走图标，否则按图片解码 */
    public static void load(Context appCtx, File f, boolean apk, int sizePx, Cb cb) {
        load(appCtx, f, apk ? KIND_APK : KIND_IMAGE, sizePx, cb);
    }

    /**
     * 异步加载缩略图。
     *
     * @param kind   {@link #KIND_IMAGE} / {@link #KIND_APK} / {@link #KIND_VIDEO}
     * @param sizePx 目标最长边像素（建议 2x 图标尺寸，如 96）
     */
    public static void load(final Context appCtx, final File f, final int kind,
                            final int sizePx, final Cb cb) {
        final String key = key(f);
        Bitmap mem = getMem(key);
        if (mem != null) {
            cb.onThumb(key, mem);
            return;
        }
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bmp;
                if (kind == KIND_APK) bmp = loadApkIcon(appCtx, f, sizePx);
                else if (kind == KIND_VIDEO) bmp = loadVideoFrame(appCtx, f, sizePx);
                else bmp = decodeImage(f, sizePx);
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

    // ============================================================ 视频帧（v1.91.0）

    /**
     * 视频抽帧：磁盘缓存命中就直接读，否则 MediaMetadataRetriever 抽一帧并写回缓存。
     *
     * <p>三个必须注意的点：</p>
     * <ol>
     *   <li>抽帧位置不要用 0 秒 —— 大量视频开头是黑场或台标，缩略图会全是黑的。
     *       取时长 10%（上限 5 秒），并优先取关键帧（OPTION_CLOSEST_SYNC 的反面：
     *       这里用 CLOSEST_SYNC 是为了快，宁可略微偏移也不要为了精确帧多解几百毫秒）。</li>
     *   <li>API 27+ 用 {@code getScaledFrameAtTime} 直接解出小图，避免 4K 片源一次
     *       分配 32MB 位图把列表拖崩；低版本只能取出原尺寸再缩，加 OOM 兜底。</li>
     *   <li>MediaMetadataRetriever 用完必须 release，否则同时活着的解码器会耗尽
     *       （一个目录滑过去就是几十个实例）。</li>
     * </ol>
     */
    private static Bitmap loadVideoFrame(Context ctx, File f, int sizePx) {
        if (!PlayerPrefs.thumbsEnabled(ctx)) return null;
        File cached = cacheFile(ctx, f, sizePx);
        Bitmap fromDisk = readCached(cached, sizePx);
        if (fromDisk != null) {
            // 命中缓存：刷新 mtime，让 LRU 真的按「最近用」淘汰
            try { cached.setLastModified(System.currentTimeMillis()); } catch (Throwable ignore) {}
            return fromDisk;
        }

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            r.setDataSource(f.getAbsolutePath());
            long durUs = 0;
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durUs = Long.parseLong(d.trim()) * 1000L;
            } catch (Throwable ignore) {}
            long at = durUs > 0 ? Math.min(durUs / 10L, FRAME_AT_MAX_US) : 0L;

            int w = Math.max(sizePx, 2), h = Math.max(sizePx, 2);
            if (Build.VERSION.SDK_INT >= 27) {
                frame = r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            }
            if (frame == null) {
                frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
        } catch (Throwable t) {
            frame = null;
        } finally {
            try { r.release(); } catch (Throwable ignore) {}
        }
        if (frame == null) return null;

        Bitmap scaled = scaleToFit(frame, sizePx);
        if (scaled != frame) frame.recycle();
        scaled.setDensity(Bitmap.DENSITY_NONE);
        writeCached(cached, scaled);
        return scaled;
    }

    /** 等比缩到最长边 = sizePx（已经够小就原样返回） */
    private static Bitmap scaleToFit(Bitmap src, int sizePx) {
        int w = src.getWidth(), h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= sizePx || longSide <= 0) return src;
        float s = (float) sizePx / longSide;
        int nw = Math.max(1, Math.round(w * s));
        int nh = Math.max(1, Math.round(h * s));
        try {
            return Bitmap.createScaledBitmap(src, nw, nh, true);
        } catch (Throwable t) {
            return src;
        }
    }

    private static File cacheDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "vthumb");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return d;
    }

    private static File cacheFile(Context ctx, File src, int sizePx) {
        File d = cacheDir(ctx);
        if (d == null) return null;
        // 文件名里带上 size 与文件长度：同一视频换尺寸不会互相覆盖，长度变了（被覆盖）自动换名
        String name = Integer.toHexString((src.getAbsolutePath() + "|" + src.length()).hashCode())
                + "-" + sizePx + ".jpg";
        return new File(d, name);
    }

    private static Bitmap readCached(File cache, int sizePx) {
        if (cache == null || !cache.isFile() || cache.length() <= 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // 缩略图不需要 alpha，省一半内存
            return BitmapFactory.decodeFile(cache.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCached(File cache, Bitmap bmp) {
        if (cache == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cache);
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            out.flush();
        } catch (Throwable ignore) {
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
        prune(cache.getParentFile());
    }

    /** 目录超限就删最旧的一半（按 mtime） */
    private static void prune(File dir) {
        if (dir == null) return;
        File[] all = dir.listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (all.length <= DISK_MAX_FILES && total <= DISK_MAX_BYTES) return;
        java.util.Arrays.sort(all, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int drop = Math.max(1, all.length / 2);
        for (int i = 0; i < drop; i++) {
            try { all[i].delete(); } catch (Throwable ignore) {}
        }
    }

    /** 设置页「清除视频缩略图缓存」用：删掉整个缓存目录（下次自动重建） */
    public static int clearDiskCache(Context ctx) {
        File d = new File(ctx.getCacheDir(), "vthumb");
        File[] all = d.listFiles();
        int n = 0;
        if (all != null) {
            for (File f : all) {
                if (f.delete()) n++;
            }
        }
        synchronized (CACHE) { CACHE.evictAll(); }
        return n;
    }

    /** 缓存占用（字节），设置页展示用 */
    public static long diskCacheSize(Context ctx) {
        File d = new File(ctx.getCacheDir(), "vthumb");
        File[] all = d.listFiles();
        long total = 0;
        if (all != null) for (File f : all) total += f.length();
        return total;
    }
}
