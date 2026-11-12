package com.hex91.file.utils;

import com.hex91.file.ui.*;
import com.hex91.file.core.*;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;

/**
 * 「用其他应用打开」（v1.77.0）。
 *
 * <p>以前点击不认识的文件只会弹「属性」，视频/音频/Office 文档在文件管理器里等于打不开。
 * 这里统一走 {@code ACTION_VIEW} + {@code createChooser}，让系统去找能处理该类型的应用。</p>
 *
 * <p>三个必须注意的点：</p>
 * <ul>
 *   <li>file:// 从 Android 7 起会抛 {@code FileUriExposedException}，所以统一走
 *       {@link ApkFileProvider} 的 {@code content://}，与「分享」「安装 APK」同一套授权。</li>
 *   <li>只给 {@code setDataAndType} 时，WPS、相册这类只读 {@code EXTRA_STREAM} 的应用
 *       会拿不到流；只给 EXTRA_STREAM 时，靠 MIME 过滤的应用又找不到自己。
 *       两个都给，加上 {@code ClipData} 作为授权载体（部分 ROM 靠它才能把权限传下去）。</li>
 *   <li>判断「有没有应用能开」要在 API 30 的包可见性规则下成立，所以清单里补了
 *       {@code <queries>}，否则 {@code queryIntentActivities} 永远返回空、误判成不支持。</li>
 * </ul>
 */
public final class OpenWith {

    public static final String AUTHORITY = "com.hex91.file.files";

    private OpenWith() {}

    public static Uri uriOf(File f) {
        return Uri.parse("content://" + AUTHORITY + f.getAbsolutePath());
    }

    public static Intent intentFor(File f) {
        Uri uri = uriOf(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, MimeUtil.of(f.getName()));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri(f.getName(), uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** 是否有应用能处理该类型（可能因为包可见性漏报，调用方要把 false 当「不确定」） */
    public static boolean canOpen(Context c, File f) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.queryIntentActivities(intentFor(f), 0).size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean open(Context c, File f) {
        return open(c, f, null);
    }

    /**
     * 弹系统「打开方式」选择器。
     *
     * @return true = 已拉起；false = 没有应用能打开（调用方退回「属性」并提示）
     */
    public static boolean open(Context c, File f, String title) {
        if (f == null || !f.isFile()) return false;
        try {
            if (!canOpen(c, f)) return false;
            Intent chooser = Intent.createChooser(intentFor(f),
                    title != null ? title : "打开 " + f.getName());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.setClipData(ClipData.newRawUri(f.getName(), uriOf(f)));
            if (!(c instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
