package com.hex91.file;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 图片批处理（v1.58.0）：缩放 / 格式转换 / 文字水印。零第三方依赖。
 *   - 解码用 inSampleSize 采样，源图超 4096 边长自动降采样防 OOM；
 *   - WebP 有损输出（API18+ 全可用，QUALITY 参数 0-100）；
 *   - 输出名 <base>_p.<ext>，冲突自动加 (n) 序号；
 *   - 水印画右下角，带阴影，字号随图宽自适应。
 */
public final class ImageBatchUtil {

    /** JPEG / PNG / WEBP / KEEP（按源扩展名） */
    public static final String[] FORMATS = {"KEEP", "JPEG", "PNG", "WEBP"};

    public static final class Options {
        public int scalePercent = 100;      // 10-400
        public int formatIdx = 0;           // FORMATS 下标
        public int quality = 85;            // JPEG/WEBP 有效
        public String watermark = null;     // null = 不加水印
    }

    private ImageBatchUtil() {}

    /** 处理单张图，写入 outDir；返回输出文件。失败抛异常。 */
    public static File process(File in, File outDir, Options o) throws Exception {
        if (!in.isFile()) throw new IOException("文件不存在: " + in.getName());
        // 1. 采样解码（超 4096 边长先降采样）
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(in.getAbsolutePath(), bo);
        if (bo.outWidth <= 0 || bo.outHeight <= 0) throw new IOException("无法解码图片");
        int sample = 1;
        while (bo.outWidth / (sample * 2) >= 4096 || bo.outHeight / (sample * 2) >= 4096)
            sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(in.getAbsolutePath(), d);
        if (src == null) throw new IOException("解码失败（文件损坏或格式不支持）");
        try {
            // 2. 缩放
            Bitmap bmp = src;
            if (o.scalePercent != 100) {
                float sc = o.scalePercent / 100f;
                int w = Math.max(1, Math.round(src.getWidth() * sc));
                int h = Math.max(1, Math.round(src.getHeight() * sc));
                Matrix m = new Matrix();
                m.postScale(sc, sc);
                Bitmap s = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (s != src) src.recycle();
                bmp = s;
            }
            // 3. 水印
            if (o.watermark != null && !o.watermark.trim().isEmpty()) {
                Bitmap w = bmp.copy(bmp.getConfig() == null
                        ? Bitmap.Config.ARGB_8888 : bmp.getConfig(), true);
                if (w != bmp) bmp.recycle();
                Canvas cv = new Canvas(w);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setTextSize(Math.max(24f, w.getWidth() / 18f));
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setShadowLayer(6f, 3f, 3f, 0x80000000);
                p.setColor(0xCCFFFFFF);
                float tw = p.measureText(o.watermark.trim());
                float x = w.getWidth() - tw - w.getWidth() * 0.03f;
                float y = w.getHeight() - p.descent() - w.getHeight() * 0.03f;
                cv.drawText(o.watermark.trim(), Math.max(8f, x), Math.max(p.getTextSize(), y), p);
                bmp = w;
            }
            // 4. 编码
            Bitmap.CompressFormat fmt = resolveFormat(o.formatIdx, in.getName());
            File out = uniqueOut(outDir, in.getName(), extOf(fmt));
            FileOutputStream fo = new FileOutputStream(out);
            boolean ok = bmp.compress(fmt, o.quality, fo);
            fo.close();
            bmp.recycle();
            if (!ok) { out.delete(); throw new IOException("编码失败"); }
            return out;
        } finally {
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    private static Bitmap.CompressFormat resolveFormat(int idx, String name) {
        String f = FORMATS[Math.max(0, Math.min(FORMATS.length - 1, idx))];
        if ("JPEG".equals(f)) return Bitmap.CompressFormat.JPEG;
        if ("PNG".equals(f)) return Bitmap.CompressFormat.PNG;
        if ("WEBP".equals(f)) return Bitmap.CompressFormat.WEBP;
        // KEEP：按源扩展名，认不出转 JPEG
        String low = name.toLowerCase();
        if (low.endsWith(".png")) return Bitmap.CompressFormat.PNG;
        if (low.endsWith(".webp")) return Bitmap.CompressFormat.WEBP;
        return Bitmap.CompressFormat.JPEG;
    }

    private static String extOf(Bitmap.CompressFormat fmt) {
        if (fmt == Bitmap.CompressFormat.PNG) return "png";
        if (fmt == Bitmap.CompressFormat.WEBP) return "webp";
        return "jpg";
    }

    private static File uniqueOut(File dir, String srcName, String ext) {
        String base = srcName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File f = new File(dir, base + "_p." + ext);
        int n = 1;
        while (f.exists()) f = new File(dir, base + "_p(" + (n++) + ")." + ext);
        return f;
    }

    /** 是否图片批处理支持的格式（gif/bmp 解码后转出即静态帧） */
    public static boolean supported(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }
}
